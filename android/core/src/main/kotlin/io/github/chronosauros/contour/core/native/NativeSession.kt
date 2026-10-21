package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.*

/** Pure session contract used by the Android transport AND fake-transport tests. */
interface NativePort {
    val deviceKey: String
    fun guard()
    fun pause(ms: Long)
    fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray)
    fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray
    /** The last frame of a session, after which the DAC leaves the bus (JM12 commit): guarded before, never after. */
    fun sendFinal(id: Int, payload: ByteArray) = send(id, NativeHidReports.Kind.OUTPUT, payload)
}
/** Contour stable: the native (non-WalkPlay) DACs are FiiO (KA15, K13 R2R, SnowSky Melody and Tiny A) and the JCally JM12 (KT Micro). */
sealed interface NativeState {
    val slot: Int
    val preamp: Double?
    fun importExact(): ImportedEq
    fun matches(profile: Profile): Boolean
    fun describe(): String = when (this) {
        is Fiio -> (slotDescription?.let { "$it\n" } ?: "") + "${raw.count} native filters; pregain ${raw.preampTenths}/10 dB\n" + raw.bands.joinToString("\n")
        is Kt -> raw.describe()
    }
    /** [names]: USER slot -> name read from the DAC (KA15), empty where the device has no names. */
    data class Fiio(val codec: FiioCodec, val raw: FiioSnapshot, val names: Map<Int, String> = emptyMap()) : NativeState {
        override val slot get() = raw.activeSlot
        /** Honest slot text for the SnowSky DACs, whose slot register can be stale on a USER slot; null elsewhere. */
        val slotDescription: String? get() = if (!FiioMelodyExchange.appliesTo(codec.config)) null else when (slot) {
            in codec.config.userSlots -> "${codec.config.slotLabels[slot]} (slot $slot)"
            in 7..9 -> "USER slot (number unknown; DAC reports $slot)"
            in 0..6 -> "Slot unknown (stock preset or USER; DAC reports $slot)"
            240 -> "Close EQ (slot 240)"
            else -> "Slot unknown (DAC reports $slot)"
        }
        override val preamp get() = codec.intendedPreampDb(raw.preampTenths)
        override fun importExact(): ImportedEq = codec.importExact(raw).let { ImportedEq(it.bands, it.preampDb) }
        override fun matches(profile: Profile): Boolean = runCatching {
            codec.matches(codec.planOnExplicitSend(profile.copy(bands = codec.padToDeviceCount(profile.bands), preampDb = profile.effectivePreampDb()), slot, true).expected, raw)
        }.getOrDefault(false)
    }
    /** JM12: no preamp register, so 0. */
    data class Kt(val raw: KtSnapshot) : NativeState {
        override val slot get() = raw.slot
        override val preamp get() = 0.0
        override fun importExact(): ImportedEq = KtMicroCodec.importExact(raw)
        override fun matches(profile: Profile): Boolean = KtMicroCodec.matches(profile, raw)
    }
}
/** [restarting]: the save was committed and the DAC is re-enumerating (JM12); [readback] is its RAM before the commit. */
data class NativeWriteResult(val verified: Boolean, val reason: String?, val readback: NativeState?, val restarting: Boolean = false)

/** No mutation on read, qualification or local preflight. Every callback is guarded both sides. */
class NativeSession(val target: DeviceTarget, private val port: NativePort) {
    private val melody = FiioMelodyExchange.appliesTo(target.fiio)
    private companion object {
        /** After a READ slot switch, as FiioWritePlan.delayAfterSelectMs before a send. */
        const val SELECT_SETTLE_MS = 300L
    }
    private fun <T> guarded(op: () -> T): T { port.guard(); return op().also { port.guard() } }
    private fun pause(ms: Long) { if (ms > 0) guarded { port.pause(ms) } }
    private fun send(id: Int, bytes: ByteArray, kind: NativeHidReports.Kind = NativeHidReports.Kind.OUTPUT) { guarded { port.send(id, kind, bytes) }; frameDone() }
    private fun request(id: Int, bytes: ByteArray, check: (ByteArray) -> Boolean): ByteArray = guarded {
        port.request(id, bytes, check).also { require(check(it)) { "Uncorrelated native reply" } }
    }.also { frameDone() }
    // Progress of a write: every HID frame sent or answered is a step done, out of the steps [write] planned (estimated up front,
    // narrowed once the first read says whether the slot has to be switched; never fewer than one step ahead of those done).
    private var progress: ((done: Int, total: Int) -> Unit)? = null
    private var stepsDone = 0
    private var stepsTotal = 0
    private fun frameDone() { progress?.let { stepsDone++; it(stepsDone, maxOf(stepsTotal, stepsDone + 1)) } }
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
    /** One write, one echo (the SnowSky SAVE uses a different reply). KA15 stops answering
     * until it is unplugged when 12 writes go out back to back and no echo is read (hardware, 03.10.2026). A missing
     * parameter echo is not fatal; the full post-save read decides success. A SnowSky SELECT requires its echo. */
    private fun writeAndConsumeEcho(f: FiioFrame, requireEcho: Boolean = false) {
        val frame = f.payload()
        val cmd = frame[4]
        try {
            request(f.reportId, frame) { p ->
                p.size >= 6 && p[0] == 0xAA.toByte() && p[1] == 0x0A.toByte() && p[4] == cmd &&
                    (!melody || melodyEcho(frame, p))
            }
        } catch (e: java.io.IOException) { if (requireEcho || !isReplyTimeout(e)) throw e }
    }
    private fun melodyEcho(frame: ByteArray, reply: ByteArray): Boolean {
        // A name write is echoed as the stored field (LEN 9, slot + 9 bytes, upper-case, rest cleared), not byte for byte.
        if ((frame[4].toInt() and 255) == FiioCodec.NAME) return reply.size >= 18 && (reply[5].toInt() and 255) == 9 &&
            reply[6] == frame[6] && (7 until frame.size - 2).all { reply[it] == frame[it] } && reply[17] == 0xEE.toByte()
        if (reply.size < frame.size || reply[5] != frame[5] || reply[frame.lastIndex] != 0xEE.toByte()) return false
        val band = (frame[4].toInt() and 255) == FiioCodec.BAND
        if (!(6 until frame.size - 2).all { (band && it in 11..12) || reply[it] == frame[it] }) return false
        if (!band) return true
        fun q(bytes: ByteArray) = ((bytes[11].toInt() and 255) shl 8) or (bytes[12].toInt() and 255)
        return kotlin.math.abs(q(frame) - q(reply)) <= 1 + q(frame) / 250
    }
    /** Melody 1.20 sends 00 EE 00 <counter> 00 plus stale tail, never an AA SAVE echo.
     * Neither this completion report nor silence verifies a save; the following slot/curve read does. */
    private fun saveMelody(f: FiioFrame) {
        try {
            request(f.reportId, f.payload()) { p ->
                p.size >= 5 && p[0] == 0.toByte() && p[1] == 0xEE.toByte() && p[2] == 0.toByte() && p[4] == 0.toByte()
            }
        } catch (e: java.io.IOException) { if (!isReplyTimeout(e)) throw e }
    }
    private fun melodySlotMismatch(c: FiioCodec, expected: Int, actual: Int) =
        "${FiioMelodyExchange.name(c.config)} requested ${c.config.slotLabels[expected] ?: "USER"} (slot $expected), but DAC reports slot $actual; not verified"
    private fun readFiio(c: FiioCodec): NativeState.Fiio {
        // KA15: names first, as the official app does on connect. SnowSky: names last, so no name traffic sits
        // before the slot query whose reply goes stale ([FiioConfig.nameBySlotId]).
        val early = c.config.userSlotNames && !c.config.nameBySlotId
        val slot = (if (early) readNames(c) else emptyMap()).let { names -> readFiioBody(c, fiioReply(c, c.querySlot()), names) }
        return if (c.config.userSlotNames && !early) withNames(c, slot) else slot
    }
    private fun readNames(c: FiioCodec) = c.config.userSlots.sorted().associateWith { readName(c, it) }
    /** SnowSky: the names read after the curve (they never decide the slot). */
    private fun withNames(c: FiioCodec, state: NativeState.Fiio) = if (c.config.userSlotNames) state.copy(names = readNames(c)) else state
    private fun readFiioBody(c: FiioCodec, slot: ByteArray, names: Map<Int, String> = emptyMap()): NativeState.Fiio {
        val count = fiioReply(c, c.queryCount())
        val preamp = fiioReply(c, c.queryPreamp())
        val bands = c.activeBandQueries(c.parseCount(count)).map { fiioReply(c, it) }
        // USER band/preamp traffic corrupts the SnowSky slot reply. Never query/predict it here.
        // 0..6 may still be a stock preset, and Close EQ is stable: retain their closing guard.
        val opening = c.parseSlot(slot)
        val raw = if (melody && (opening in c.config.userSlots || opening in 7..9))
            c.melodyUserSnapshot(slot, count, preamp, bands)
        else {
            val closing = fiioReply(c, c.querySlot())
            // SnowSky cold plug on a USER slot: the first reply is a boot value (0 on the Tiny A, Pixel 14.10.2026) and
            // after the band reads the same register says the last band index. A stock preset answers the same both times.
            if (melody && c.parseSlot(closing) != opening && c.parseSlot(closing) in 7..9) c.melodyUserSnapshot(closing, count, preamp, bands)
            else c.snapshot(slot, count, preamp, bands, closing)
        }
        return NativeState.Fiio(c, raw, names = names)
    }
    private fun readName(c: FiioCodec, slot: Int) = c.parseName(fiioReply(c, c.queryName(slot)), slot)
    /** After a verified save the USER slot takes the profile's name. A name that does not read back is a note,
     * never a failed save: the EQ is already on the DAC. */
    private fun nameAfterSave(c: FiioCodec, back: NativeState.Fiio, slot: Int, profileName: String): Pair<NativeState.Fiio, String?> {
        val name = FiioCodec.slotName(profileName, c.config.nameLength)
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
    private fun codec(): FiioCodec = FiioCodec(requireNotNull(target.fiio) { "Not a FiiO DAC" })
    /** Renames one USER slot (KA15) and reads the name back; the DAC's EQ is untouched. */
    fun renameSlot(slot: Int, name: String, explicitUserAction: Boolean): String = guarded {
        require(explicitUserAction) { "Rename requires an explicit user action" }
        val c = codec()
        val frame = c.writeName(slot, name)
        retryTimeouts { readName(c, slot) }
        writeAndConsumeEcho(frame)
        retryTimeouts { readName(c, slot) }
    }
    fun read(): NativeState = guarded { if (target.kt) retryTimeouts(5) { readKt() } else codec().let { c -> retryTimeouts(5) { readFiio(c) } } }
    private fun readKt(): NativeState.Kt = NativeState.Kt(KtMicroCodec.snapshot(KtMicroCodec.REGISTERS.associateWith { reg ->
        request(KtMicroCodec.REPORT_ID, KtMicroCodec.read(reg)) { KtMicroCodec.isReply(it, reg, KtMicroCodec.READ) }
    }))
    /**
     * JM12: read, enable Custom if the EQ is off, write the ten band registers (each ACK consumed when it comes; a missing one
     * is not fatal), read RAM back and commit only when it matches; a mismatch is left in RAM, not saved ("unplug to drop it").
     * The commit is the last frame: the DAC re-enumerates, so the caller reads the saved state back on the new connection.
     */
    private fun writeKt(profile: Profile, explicitHold: Boolean, onProgress: ((done: Int, total: Int) -> Unit)?): NativeWriteResult {
        require(explicitHold) { "HOLD TO SEND required" }
        require(target.issues(profile).isEmpty()) { target.issues(profile).joinToString("; ") }
        port.guard()
        progress = onProgress
        stepsDone = 0
        stepsTotal = 3 * KtMicroCodec.REGISTERS.size + 1
        onProgress?.invoke(0, stepsTotal)
        try {
            val before = retryTimeouts(5) { readKt() }.raw
            val expected = KtMicroCodec.compile(profile)
            val writes = KtMicroCodec.writes(before, expected)
            stepsTotal = 2 * KtMicroCodec.REGISTERS.size + writes.size + 1
            writes.forEach { f ->
                val reg = f[0].toInt() and 255
                try { request(KtMicroCodec.REPORT_ID, f) { KtMicroCodec.isReply(it, reg, KtMicroCodec.WRITE) } }
                catch (e: java.io.IOException) { if (!isReplyTimeout(e)) throw e }
            }
            val ram = retryTimeouts(5) { readKt() }
            if (ram.raw.slot != KtMicroCodec.SLOT_CUSTOM || ram.raw.registers != expected)
                return NativeWriteResult(false, "JM12 read-back differs; not saved (unplug the JM12 to drop it)", ram)
            port.guard()
            port.sendFinal(KtMicroCodec.REPORT_ID, KtMicroCodec.COMMIT_FRAME)
            frameDone()
            return NativeWriteResult(true, null, ram, restarting = true)
        } finally { progress = null }
    }
    /** Switches the DAC to USER [slot], as its own preset button would (nothing is written or saved), and returns the slot one
     * query confirms; no band is read. A tap on a slot Contour already knows, and the end of the background scan on connect.
     * Same echo and settle handling as [readSlot]; the SnowSky DACs always select. The caller compares the answer with [slot]. */
    fun selectSlot(slot: Int, explicitUserAction: Boolean): Int = guarded {
        require(explicitUserAction) { "Switching slots requires an explicit user action" }
        val c = codec()
        val select = c.selectUserSlot(slot)
        if (c.config.consumeWriteEchoes) writeAndConsumeEcho(select, requireEcho = melody) else send(select.reportId, select.payload())
        pause(SELECT_SETTLE_MS)
        c.parseSlot(retryTimeouts { fiioReply(c, c.querySlot()) })
    }
    /** A tap on a FiiO USER slot Contour has not seen, and the background scan on connect: switches the DAC to USER [slot], as its own
     * preset button would (nothing is written or saved), and reads that slot. The SnowSky DACs always select, like a send: their USER slot reply may be stale. */
    fun readSlot(slot: Int, explicitUserAction: Boolean): NativeState.Fiio = guarded {
        require(explicitUserAction) { "Reading another slot requires an explicit user action" }
        val c = codec()
        val before = if (melody) null else read() as NativeState.Fiio
        if (before?.raw?.activeSlot != slot) {
            val select = c.selectUserSlot(slot)
            if (c.config.consumeWriteEchoes) writeAndConsumeEcho(select, requireEcho = melody) else send(select.reportId, select.payload())
            pause(SELECT_SETTLE_MS)
        }
        val selected = retryTimeouts { fiioReply(c, c.querySlot()) }
        val selectedSlot = c.parseSlot(selected)
        require(selectedSlot == slot) { if (melody) melodySlotMismatch(c, slot, selectedSlot) else "FiiO slot switch failed" }
        if (melody) withNames(c, retryTimeouts { readFiioBody(c, selected) }) else retryTimeouts(5) { readFiio(c) }
    }
    /** [targetSlot]: the USER slot chosen in the app (KA15 slot picker); null = the target's fixed destination.
     * [onProgress] (done, total): the HID frames completed so far out of those planned; called on the calling thread. */
    fun write(profile: Profile, explicitHold: Boolean, targetSlot: Int? = null, onProgress: ((done: Int, total: Int) -> Unit)? = null): NativeWriteResult =
        if (target.kt) writeKt(profile, explicitHold, onProgress) else guarded {
        require(explicitHold) { "HOLD TO SEND required" }
        require(target.issues(profile).isEmpty()) { target.issues(profile).joinToString("; ") }
        // Stable builds carry only devices whose save can be read back in the same session (no reconnect proof).
        require(!requireNotNull(target.fiio).disconnectOnSave) { "Save needs a reconnect proof; not supported" }
        // Frames planned: the read before, select + check + backup read, preamp + count + one per band, save, the read-back, the name.
        val fiio = requireNotNull(target.fiio)
        val readFrames = 3 + codec().padToDeviceCount(profile.bands).size + (if (melody) 0 else 1) + (if (fiio.userSlotNames) fiio.userSlots.size else 0)
        progress = onProgress
        stepsDone = 0
        stepsTotal = readFrames + (2 + readFrames) + (2 + codec().padToDeviceCount(profile.bands).size) + 1 + readFrames + (if (fiio.userSlotNames) 2 else 0)
        onProgress?.invoke(0, stepsTotal)
        try {
        when (val before = read()) {
            is NativeState.Fiio -> {
                val c = before.codec
                val p = c.planOnExplicitSend(profile.copy(bands = c.padToDeviceCount(profile.bands), preampDb = profile.effectivePreampDb()), targetSlot ?: target.destinationSlot!!, true)
                // The official app never switches presets to save the active one. On the Pixel a KA15 preset
                // switch silenced every later HID reply (hardware, 03.10.2026), so only switch when needed.
                // The SnowSky DACs must always select: their initial USER slot reply may already be stale.
                val alreadyActive = !melody && before.raw.activeSlot == p.targetSlot
                if (alreadyActive) stepsTotal -= 2 + readFrames // no switch, and the read before is the backup
                if (!alreadyActive) {
                    // 0x19 saves the active preset whatever its slot byte says, so another USER slot needs the switch.
                    if (c.config.consumeWriteEchoes) writeAndConsumeEcho(p.selection, requireEcho = melody) else send(p.selection.reportId, p.selection.payload())
                    pause(p.delayAfterSelectMs.toLong())
                }
                val selected = retryTimeouts { fiioReply(c, p.selectionVerification) }
                val selectedSlot = c.parseSlot(selected)
                require(selectedSlot == p.targetSlot) {
                    if (melody) melodySlotMismatch(c, p.targetSlot, selectedSlot) else "FiiO destination selection failed"
                }
                val backup = if (melody) retryTimeouts { readFiioBody(c, selected) }
                    else if (alreadyActive) before else retryTimeouts { readFiio(c) }
                val writes = c.writesAfterSelection(p, selected, backup.raw)
                writes.forEach {
                    if (c.config.consumeWriteEchoes) writeAndConsumeEcho(it) else send(it.reportId, it.payload())
                    if ((it.payload()[4].toInt() and 255) == FiioCodec.COUNT) pause(p.delayAfterCountMs.toLong())
                    pause(c.config.writeGapMs.toLong())
                }
                pause(p.delayBeforeSaveMs.toLong())
                if (melody) saveMelody(p.save) else send(p.save.reportId, p.save.payload())
                pause(p.delayAfterSaveMs.toLong())
                val back = if (melody) {
                    // SAVE restores the slot register. Anchor the snapshot before any band/preamp traffic.
                    val saved = retryTimeouts { fiioReply(c, c.querySlot()) }
                    val savedSlot = c.parseSlot(saved)
                    require(savedSlot == p.targetSlot) { melodySlotMismatch(c, p.targetSlot, savedSlot) }
                    // The names read before the send stand; nameAfterSave updates the slot written.
                    retryTimeouts { readFiioBody(c, saved) }.copy(names = before.names)
                } else retryTimeouts { readFiio(c) }
                val ok = c.matches(p.expected, back.raw)
                if (!ok) NativeWriteResult(false, "FiiO native register mismatch", back)
                else if (!c.config.userSlotNames) NativeWriteResult(true, null, back)
                else nameAfterSave(c, back, p.targetSlot, profile.name).let { (named, note) -> NativeWriteResult(true, note, named) }
            }
            is NativeState.Kt -> error("Not a FiiO DAC")
        }
        } finally { progress = null }
    }
}
