package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.*

/** Pure session contract used by the Android transport AND fake-transport tests. */
interface NativePort {
    val deviceKey: String
    fun payloadSize(id: Int, kind: NativeHidReports.Kind): Int
    fun guard()
    fun pause(ms: Long)
    fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray)
    fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray
    fun feature(id: Int): ByteArray
}
sealed interface NativeState {
    val slot: Int
    val preamp: Double?
    fun importExact(): ImportedEq
    fun matches(profile: Profile): Boolean
    fun describe(): String = when (this) {
        is Fiio -> "${raw.count} native filters; pregain ${raw.preampTenths}/10 dB\n" + raw.bands.joinToString("\n")
        is Kt -> "${raw.model.productName}; slot ${raw.slot}; pregain ${raw.pregainDb} dB\n" + raw.registers.joinToString("\n")
        is Moondrop -> "READ ONLY; slot ${raw.slot}; effective preamp UNKNOWN; observed offset ${raw.pregain.rawQ8}/256 dB (${raw.pregain.status})\n" +
            raw.bands.joinToString("\n") + "\n" + raw.registers.joinToString("\n") +
            "\n${raw.model.reasonReadOnly}\n${raw.importBlockedReason}"
        is Fosi -> "READ ONLY; partial first ${raw.bands.size} bands of bank ${raw.state.mode}; whole-bank count/layout UNKNOWN; preamp UNKNOWN; enabled=${raw.state.enabled}; ${raw.sampleFormat}\n" +
            raw.bands.joinToString("\n") + "\n${FosiCodec.READ_ONLY_REASON}"
    }
    data class Moondrop(val raw: MoondropCodec.Snapshot) : NativeState {
        override val slot get() = raw.slot
        override val preamp: Double? get() = null
        override fun importExact(): ImportedEq = error(raw.importBlockedReason)
        override fun matches(profile: Profile): Boolean = false
    }
    /** [names]: USER slot -> name read from the DAC (KA15), empty where the device has no names. */
    data class Fiio(val codec: FiioCodec, val raw: FiioSnapshot, val receiptProfile: Profile? = null,
                    val names: Map<Int, String> = emptyMap()) : NativeState {
        override val slot get() = raw.activeSlot
        override val preamp get() = raw.preampTenths / 10.0
        override fun importExact(): ImportedEq = codec.importExact(raw).let { ImportedEq(it.bands, it.preampDb) }
        override fun matches(profile: Profile): Boolean = (!codec.config.disconnectOnSave || receiptProfile == profile) && runCatching {
            codec.matches(codec.planOnExplicitSend(profile.copy(bands = codec.padToDeviceCount(profile.bands), preampDb = profile.effectivePreampDb()), slot, true).expected, raw)
        }.getOrDefault(false)
    }
    data class Kt(val raw: KtMicroCodec.Snapshot, val receiptProfile: Profile? = null) : NativeState {
        override val slot get() = raw.slot
        override val preamp get() = raw.pregainDb.toDouble()
        override fun importExact(): ImportedEq {
            val p = raw.importProfile("import", "FROM DAC", 0)
            val expected = KtMicroCodec.compile(p, raw).expectedRegisters
            require(raw.slot == 3 && expected == raw.registers) { "KT state cannot be re-encoded exactly (including slot/reserved bytes)" }
            return ImportedEq(p.bands, p.preampDb)
        }
        override fun matches(profile: Profile): Boolean = (!raw.model.reconnectAfterSave || receiptProfile == profile) && runCatching {
            val p = KtMicroCodec.compile(profile, raw)
            raw.slot == p.expectedSlot && raw.registers == p.expectedRegisters
        }.getOrDefault(false)
    }
    data class Fosi(val raw: FosiCodec.Snapshot) : NativeState {
        override val slot get() = raw.state.mode
        override val preamp: Double? get() = null
        override fun importExact(): ImportedEq = error(FosiCodec.READ_ONLY_REASON)
        override fun matches(profile: Profile): Boolean = false
    }
}
data class NativeWriteResult(val verified: Boolean, val pending: Boolean, val reason: String?, val readback: NativeState?)

/** No mutation on read, qualification or local preflight. Every callback is guarded both sides. */
class NativeSession(val target: DeviceTarget, private val port: NativePort) {
    private fun <T> guarded(op: () -> T): T { port.guard(); return op().also { port.guard() } }
    private fun pause(ms: Long) { if (ms > 0) guarded { port.pause(ms) } }
    private fun send(id: Int, bytes: ByteArray, kind: NativeHidReports.Kind = NativeHidReports.Kind.OUTPUT) = guarded { port.send(id, kind, bytes) }
    private fun request(id: Int, bytes: ByteArray, check: (ByteArray) -> Boolean): ByteArray = guarded {
        port.request(id, bytes, check).also { require(check(it)) { "Uncorrelated native reply" } }
    }
    private fun fiioReply(c: FiioCodec, f: FiioFrame): ByteArray {
        fun logical(p: ByteArray) = c.logicalReply(f.reportId, p, p.size)
        val raw = request(f.reportId, f.payload()) { p -> runCatching {
            val l = logical(p)
            require(l[4] == f.payload()[4])
            if ((l[4].toInt() and 255) in setOf(FiioCodec.BAND, FiioCodec.NAME)) require(l[6] == f.payload()[6])
        }.isSuccess }
        return logical(raw)
    }
    /** Read-only queries are idempotent, so a silent DAC (busy after a bank switch or a flash save) may be asked again.
     * Never wrap a mutation in this. Only a reply timeout is retried; a lost session or a bad reply still aborts. */
    private fun isReplyTimeout(e: java.io.IOException) = e.message?.startsWith("Native request timed out") == true
    private fun <T> retryTimeouts(attempts: Int = 8, gapMs: Long = 400, op: () -> T): T {
        var last: java.io.IOException? = null
        repeat(attempts) { i ->
            try { return op() } catch (e: java.io.IOException) {
                if (!isReplyTimeout(e)) throw e
                last = e
                if (i < attempts - 1) pause(gapMs)
            }
        }
        throw requireNotNull(last)
    }
    /** One write, one echo: the DAC answers every AA write with an AA echo of the same command. KA15 stops answering
     * until it is unplugged when 12 writes go out back to back and no echo is read (hardware, 03.10.2026). A missing
     * echo is not fatal here; the full post-save read decides success. */
    private fun writeAndConsumeEcho(f: FiioFrame) {
        val cmd = f.payload()[4]
        try {
            request(f.reportId, f.payload()) { p -> p.size >= 6 && p[0] == 0xAA.toByte() && p[1] == 0x0A.toByte() && p[4] == cmd }
        } catch (e: java.io.IOException) { if (!isReplyTimeout(e)) throw e }
    }
    private fun readFiio(c: FiioCodec): NativeState.Fiio {
        // Names first, as the official app does on connect.
        val names = if (c.config.userSlotNames) c.config.userSlots.sorted().associateWith { readName(c, it) } else emptyMap()
        val slot = fiioReply(c, c.querySlot()); val count = fiioReply(c, c.queryCount())
        val preamp = fiioReply(c, c.queryPreamp())
        val bands = c.activeBandQueries(c.parseCount(count)).map { fiioReply(c, it) }
        return NativeState.Fiio(c, c.snapshot(slot, count, preamp, bands, fiioReply(c, c.querySlot())), names = names)
    }
    private fun readName(c: FiioCodec, slot: Int) = c.parseName(fiioReply(c, c.queryName(slot)), slot)
    /** After a verified save the USER slot takes the profile's name. A name that does not read back is a note,
     * never a failed save: the EQ is already on the DAC. */
    private fun nameAfterSave(c: FiioCodec, back: NativeState.Fiio, slot: Int, profileName: String): Pair<NativeState.Fiio, String?> {
        val name = FiioCodec.slotName(profileName)
        if (name.isEmpty() || back.names[slot] == name) return back to null
        return try {
            writeAndConsumeEcho(c.writeName(slot, name))
            val read = retryTimeouts { readName(c, slot) }
            back.copy(names = back.names + (slot to read)) to (if (read == name) null else "Slot name reads back as $read, not $name")
        } catch (e: java.io.IOException) {
            if (!isReplyTimeout(e)) throw e
            back to "Slot name not confirmed: no reply"
        } catch (e: IllegalArgumentException) { back to "Slot name not confirmed: ${e.message}" }
    }
    /** Renames one USER slot (KA15) and reads the name back; the DAC's EQ is untouched. */
    fun renameSlot(slot: Int, name: String, explicitUserAction: Boolean): String = guarded {
        require(explicitUserAction) { "Rename requires an explicit user action" }
        val c = FiioCodec(requireNotNull(target.fiio) { "Not a FiiO DAC" })
        val frame = c.writeName(slot, name)
        retryTimeouts { readName(c, slot) }
        writeAndConsumeEcho(frame)
        retryTimeouts { readName(c, slot) }
    }
    private fun ktReply(f: KtMicroCodec.Frame, command: Int = KtMicroCodec.READ): KtMicroCodec.Reply {
        val reg = f.payload[0].toInt() and 255
        val bytes = request(f.reportId, f.payload) { p -> runCatching {
            KtMicroCodec.parseRegister(KtMicroCodec.Reply(port.deviceKey, f.reportId, p), port.deviceKey, reg, command)
        }.isSuccess }
        return KtMicroCodec.Reply(port.deviceKey, f.reportId, bytes)
    }
    private fun readKt(): NativeState.Kt {
        val model = requireNotNull(target.kt)
        val replies = KtMicroCodec.readQueries(model).map { ktReply(it) }
        val s = KtMicroCodec.snapshot(model, port.deviceKey, replies)
        val closing = KtMicroCodec.parseRegister(ktReply(KtMicroCodec.readRegister(KtMicroCodec.SLOT_REGISTER)), port.deviceKey, KtMicroCodec.SLOT_REGISTER)
        require(closing == s.registers.single { it.register == KtMicroCodec.SLOT_REGISTER }) { "KT slot drift during read" }
        return NativeState.Kt(s)
    }
    private fun fosiFrame(f: FosiCodec.Frame): FosiCodec.Reply? {
        val kind = if (f.kind == FosiCodec.ReportKind.FEATURE) NativeHidReports.Kind.FEATURE else NativeHidReports.Kind.OUTPUT
        val reply = if (f.operation == FosiCodec.Operation.SET_REPORT) { send(f.reportId, f.payload, kind); null }
            else guarded { FosiCodec.replyFromRaw(port.deviceKey, FosiCodec.ReportKind.FEATURE, port.feature(f.reportId)) }
        pause(f.delayAfterMs.toLong())
        return reply
    }
    private fun fosiQueries(frames: List<FosiCodec.Frame>): FosiCodec.Reply = frames.mapNotNull { fosiFrame(it) }.single()
    private fun readFosi(): NativeState.Fosi {
        val state = fosiQueries(FosiCodec.stateQueries())
        val sample = fosiQueries(FosiCodec.sampleFormatQueries())
        val s = FosiCodec.parseState(state, port.deviceKey)
        val bands = List(FosiCodec.BAND_COUNT) { fosiQueries(FosiCodec.bandReadQueries(s.mode, it)) }
        require(FosiCodec.parseState(fosiQueries(FosiCodec.stateQueries()), port.deviceKey) == s) { "DS3 state drift during read" }
        require(FosiCodec.parseSampleFormat(fosiQueries(FosiCodec.sampleFormatQueries()), port.deviceKey) == FosiCodec.parseSampleFormat(sample, port.deviceKey)) { "DS3 sample format drift during read" }
        return NativeState.Fosi(FosiCodec.snapshot(port.deviceKey, state, sample, bands))
    }
    private fun readMoondrop(): NativeState.Moondrop {
        val model = requireNotNull(target.moondrop)
        val inputBytes = port.payloadSize(model.reportId, NativeHidReports.Kind.INPUT)
        val frames = MoondropCodec.readQueries(model)
        require(inputBytes >= 36 && port.payloadSize(model.reportId, NativeHidReports.Kind.OUTPUT) >= frames.maxOf { it.minimumDescriptorOutputPayloadBytes })
        var slot: Int? = null
        val replies = frames.map { frame ->
            val bytes = request(frame.reportId, frame.payload) { p -> runCatching {
                val reply = MoondropCodec.Reply(port.deviceKey, frame.reportId, p)
                when (frame.kind) {
                    MoondropCodec.Kind.SLOT -> MoondropCodec.parseSlot(reply, port.deviceKey, inputBytes)
                    MoondropCodec.Kind.BAND -> MoondropCodec.parseBand(reply, port.deviceKey, model, requireNotNull(frame.index), requireNotNull(slot), inputBytes)
                    MoondropCodec.Kind.PREGAIN -> MoondropCodec.parsePregain(reply, port.deviceKey, inputBytes)
                    else -> error("Not an automatic read query")
                }
            }.isSuccess }
            MoondropCodec.Reply(port.deviceKey, frame.reportId, bytes).also {
                if (slot == null) slot = MoondropCodec.parseSlot(it, port.deviceKey, inputBytes)
            }
        }
        return NativeState.Moondrop(MoondropCodec.snapshot(model, port.deviceKey, replies, inputBytes))
    }
    fun read(): NativeState = guarded {
        when { target.fiio != null -> FiioCodec(target.fiio).let { c -> retryTimeouts(5) { readFiio(c) } }; target.kt != null -> readKt(); target.fosi -> readFosi(); target.moondrop != null -> readMoondrop(); else -> error("Not a native family") }
    }
    /** [targetSlot]: the USER slot chosen in the app (KA15 slot picker); null = the target's fixed destination. */
    fun write(profile: Profile, explicitHold: Boolean, targetSlot: Int? = null,
              retainPending: (NativePendingExpected) -> Unit = {}): NativeWriteResult = guarded {
        require(explicitHold) { "HOLD TO SEND required" }
        require(target.issues(profile).isEmpty()) { target.issues(profile).joinToString("; ") }
        when (val before = read()) {
            is NativeState.Fiio -> {
                val c = before.codec
                val p = c.planOnExplicitSend(profile.copy(bands = c.padToDeviceCount(profile.bands), preampDb = profile.effectivePreampDb()), targetSlot ?: target.destinationSlot!!, true)
                // The official app never switches presets to save the active one. On the Pixel a KA15 preset
                // switch silenced every later HID reply (hardware, 03.10.2026), so only switch when needed.
                val alreadyActive = before.raw.activeSlot == p.targetSlot
                if (!alreadyActive) {
                    // 0x19 saves the active preset whatever its slot byte says, so another USER slot needs the switch.
                    if (c.config.consumeWriteEchoes) writeAndConsumeEcho(p.selection) else send(p.selection.reportId, p.selection.payload())
                    pause(p.delayAfterSelectMs.toLong())
                }
                val selected = retryTimeouts { fiioReply(c, p.selectionVerification) }
                require(c.parseSlot(selected) == p.targetSlot) { "FiiO destination selection failed" }
                val backup = if (alreadyActive) before else retryTimeouts { readFiio(c) }
                val writes = c.writesAfterSelection(p, selected, backup.raw)
                writes.forEach {
                    if (c.config.consumeWriteEchoes) writeAndConsumeEcho(it) else send(it.reportId, it.payload())
                    if ((it.payload()[4].toInt() and 255) == FiioCodec.COUNT) pause(p.delayAfterCountMs.toLong())
                }
                pause(p.delayBeforeSaveMs.toLong())
                if (p.saveVerification == FiioSaveVerification.RECONNECT_AND_READ_REQUIRED)
                    guarded { retainPending(NativePendingExpected.Fiio(c.config, p.expected)) }
                send(p.save.reportId, p.save.payload())
                if (p.saveVerification == FiioSaveVerification.RECONNECT_AND_READ_REQUIRED)
                    NativeWriteResult(false, true, "Save sent; reconnect same DAC and complete read required (not verified)", null)
                else {
                    pause(p.delayAfterSaveMs.toLong())
                    val back = retryTimeouts { readFiio(c) }
                    val ok = c.matches(p.expected, back.raw)
                    if (!ok) NativeWriteResult(false, false, "FiiO native register mismatch", back)
                    else if (!c.config.userSlotNames) NativeWriteResult(true, false, null, back)
                    else nameAfterSave(c, back, p.targetSlot, profile.name).let { (named, note) -> NativeWriteResult(true, false, note, named) }
                }
            }
            is NativeState.Kt -> {
                val p = KtMicroCodec.compile(profile, before.raw)
                p.frames.forEach { f ->
                    if (p.reconnectAfterSave && (f.payload[4].toInt() and 255) == KtMicroCodec.SAVE)
                        guarded { retainPending(NativePendingExpected.Kt(p.model, p.expectedSlot, p.expectedRegisters)) }
                    if (f.expectsReply) {
                        val reply = ktReply(f, KtMicroCodec.WRITE)
                        val slot = KtMicroCodec.parseSlot(KtMicroCodec.parseRegister(reply, port.deviceKey, KtMicroCodec.SLOT_REGISTER, KtMicroCodec.WRITE))
                        require(slot == 3) { "KT selection not acknowledged" }
                    } else send(f.reportId, f.payload)
                    // Reconnect models may disappear on SAVE. Do not falsely verify or retry.
                    if (!(p.reconnectAfterSave && f.delayAfterMs > 0)) pause(f.delayAfterMs.toLong())
                }
                if (p.reconnectAfterSave) NativeWriteResult(false, true, "Save sent; same-identity reconnect/read required (not verified)", null)
                else {
                    val back = readKt()
                    val ok = back.raw.slot == p.expectedSlot && back.raw.registers == p.expectedRegisters
                    NativeWriteResult(ok, false, if (ok) null else "KT complete raw readback mismatch", back)
                }
            }
            is NativeState.Moondrop -> error(before.raw.model.reasonReadOnly)
            is NativeState.Fosi -> error(FosiCodec.READ_ONLY_REASON)
        }
    }
}
