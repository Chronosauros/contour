package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.*

/** Pure session contract used by the Android transport AND fake-transport tests. */
interface NativePort {
    val deviceKey: String
    fun guard()
    fun pause(ms: Long)
    fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray)
    fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray
}
/** Contour stable: the FiiO KA15 is the only native (non-WalkPlay) DAC. */
sealed interface NativeState {
    val slot: Int
    val preamp: Double?
    fun importExact(): ImportedEq
    fun matches(profile: Profile): Boolean
    fun describe(): String = when (this) {
        is Fiio -> "${raw.count} native filters; pregain ${raw.preampTenths}/10 dB\n" + raw.bands.joinToString("\n")
    }
    /** [names]: USER slot -> name read from the DAC (KA15), empty where the device has no names. */
    data class Fiio(val codec: FiioCodec, val raw: FiioSnapshot, val names: Map<Int, String> = emptyMap()) : NativeState {
        override val slot get() = raw.activeSlot
        override val preamp get() = raw.preampTenths / 10.0
        override fun importExact(): ImportedEq = codec.importExact(raw).let { ImportedEq(it.bands, it.preampDb) }
        override fun matches(profile: Profile): Boolean = runCatching {
            codec.matches(codec.planOnExplicitSend(profile.copy(bands = codec.padToDeviceCount(profile.bands), preampDb = profile.effectivePreampDb()), slot, true).expected, raw)
        }.getOrDefault(false)
    }
}
data class NativeWriteResult(val verified: Boolean, val reason: String?, val readback: NativeState?)

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
    fun read(): NativeState = guarded { codec().let { c -> retryTimeouts(5) { readFiio(c) } } }
    /** [targetSlot]: the USER slot chosen in the app (KA15 slot picker); null = the target's fixed destination. */
    fun write(profile: Profile, explicitHold: Boolean, targetSlot: Int? = null): NativeWriteResult = guarded {
        require(explicitHold) { "HOLD TO SEND required" }
        require(target.issues(profile).isEmpty()) { target.issues(profile).joinToString("; ") }
        // Stable builds carry only devices whose save can be read back in the same session (no reconnect proof).
        require(!requireNotNull(target.fiio).disconnectOnSave) { "Save needs a reconnect proof; not supported" }
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
                send(p.save.reportId, p.save.payload())
                pause(p.delayAfterSaveMs.toLong())
                val back = retryTimeouts { readFiio(c) }
                val ok = c.matches(p.expected, back.raw)
                if (!ok) NativeWriteResult(false, "FiiO native register mismatch", back)
                else if (!c.config.userSlotNames) NativeWriteResult(true, null, back)
                else nameAfterSave(c, back, p.targetSlot, profile.name).let { (named, note) -> NativeWriteResult(true, note, named) }
            }
        }
    }
}
