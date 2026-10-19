package io.github.chronosauros.contour.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import io.github.chronosauros.contour.core.DacImport
import io.github.chronosauros.contour.core.DevicePlan
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.core.DeviceProtocol
import io.github.chronosauros.contour.core.DeviceTarget
import io.github.chronosauros.contour.core.WalkPlay
import io.github.chronosauros.contour.core.native.FiioMelodyExchange
import io.github.chronosauros.contour.core.native.NativeHidReports
import io.github.chronosauros.contour.core.native.NativePort
import io.github.chronosauros.contour.core.native.NativeSession
import io.github.chronosauros.contour.core.native.NativeState
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What one lock-step read returned. WalkPlay DACs: [bands] + [preampDb]; the FiiO KA15: [nativeState]. */
data class DacSnapshot(
    val firmware: String,
    val slot: Int,
    val bands: List<WalkPlay.DeviceBand>,
    val preampDb: Int?,
    val readMs: Long,
    val protocol: DeviceProtocol? = DeviceProtocol.MICRO,
    val nativeState: NativeState? = null,
    val target: DeviceTarget = DeviceTarget.of(requireNotNull(protocol)),
) {
    val shownPreamp: Double? get() = if (nativeState != null) nativeState.preamp else preampDb?.toDouble()
    fun matches(profile: Profile): Boolean = nativeState?.matches(profile)
        ?: requireNotNull(protocol).matches(profile, bands.map { it.registers }, requireNotNull(preampDb))
    fun importExact(): DacImport = nativeState?.let { s -> runCatching<DacImport> { DacImport.Ready(s.importExact()) }
        .getOrElse { DacImport.Rejected(listOf(it.message ?: "Native state cannot be imported exactly")) } }
        ?: requireNotNull(protocol).importExact(bands, requireNotNull(preampDb))
}

/**
 * A write plus its read-back: verified = every register (freq, Q, gain, type x every device slot + preamp) as
 * written; on the Protocol Max and the TRN the bulk slot byte as well; on the KA15 the complete native USER slot state.
 * A verified KA15 write can still carry a note in [mismatches] (the slot name did not read back).
 */
data class WriteResult(
    val verified: Boolean,
    val mismatches: List<String>,
    val writeMs: Long,
    val readBackMs: Long,
    val readBack: DacSnapshot,
)

/**
 * Operations on the supported DACs (Protocol Micro, Protocol Max, TRN Black Pearl, FiiO KA15). Each one opens the HID interface, does its reports on the single
 * USB thread and releases the interface again. Writes happen only when the user asks (debug buttons).
 * [guardFor] (KA15) throws once the DAC session the operation started on has ended; [audioKeepAlive] keeps the
 * KA15's USB audio stream running for the whole HID session (it answers HID only while audio streams).
 */
class DacClient(private val manager: UsbManager, private val guardFor: (UsbDevice) -> (() -> Unit) = { {} },
    private val audioKeepAlive: (UsbDevice) -> AutoCloseable? = { null }) {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "contour-usb") }
    private val usb = executor.asCoroutineDispatcher()

    fun shutdown() = executor.shutdown()

    private suspend fun <T> withDevice(device: UsbDevice, block: (HidTransport) -> T): T = hidLock.withLock {
        val fiio = target(device).fiio ?: return@withLock withContext(usb) { HidTransport.open(manager, device).use(block) }
        val guard = guardFor(device)
        withContext(usb) {
            guard()
            val stream = if (fiio.needsAudioStream) audioKeepAlive(device) else null
            try { HidTransport.open(manager, device, guard).use(block).also { guard() } } finally { stream?.close() }
        }
    }

    private companion object {
        /** One HID session at a time in the whole app. A recreated Activity (the relaunch after an update, a theme change) has a
         * second DacClient for a moment, and a second session claiming the interface breaks the first ("Short native output report"). */
        val hidLock = Mutex()
    }

    private fun target(device: UsbDevice): DeviceTarget =
        DeviceTarget.find(device.vendorId, device.productId) ?: throw IOException("Unsupported DAC")

    private fun protocol(device: UsbDevice): DeviceProtocol =
        target(device).walkplay ?: throw IOException("WalkPlay-only operation unavailable on ${target(device).caps.name}")

    private fun nativeSession(device: UsbDevice, t: HidTransport, target: DeviceTarget) = NativeSession(target, object : NativePort {
        /** SnowSky Melody and Tiny A: their own bounded request boundary with input cleanup (see FiioMelodyExchange). */
        private val melody = if (FiioMelodyExchange.appliesTo(target.fiio)) FiioMelodyExchange(
            t.nativePayloadSize(requireNotNull(target.fiio).reportId, NativeHidReports.Kind.INPUT),
            { t.checkSession() }, { SystemClock.elapsedRealtime() },
            { id, payload -> t.sendNative(id, NativeHidReports.Kind.OUTPUT, payload) }, { t.receive(it) },
            FiioMelodyExchange.name(requireNotNull(target.fiio))) else null
        override val deviceKey = "${device.deviceName}:${device.deviceId}:${device.vendorId}:${device.productId}"
        override fun guard() = t.checkSession()
        override fun pause(ms: Long) { t.checkSession(); Thread.sleep(ms); t.checkSession() }
        override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) = t.sendNative(id, kind, payload)
        override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean) =
            melody?.request(id, payload, matches) ?: t.requestNative(id, payload, matches)
    })

    private fun nativeSnapshot(target: DeviceTarget, state: NativeState, ms: Long) =
        DacSnapshot("native", state.slot, emptyList(), null, ms, protocol = null, nativeState = state, target = target)

    suspend fun readDevice(device: UsbDevice): DacSnapshot = withDevice(device) { t ->
        val target = target(device)
        if (!target.native) read(t, requireNotNull(target.walkplay)) else {
            val start = SystemClock.elapsedRealtime()
            val state = nativeSession(device, t, target).read(); t.checkSession()
            val ms = SystemClock.elapsedRealtime() - start
            UsbLog.line("read: ${target.caps.name}, slot ${state.slot}, preamp ${state.preamp} dB, $ms ms")
            nativeSnapshot(target, state, ms)
        }
    }

    /** A slot Contour already knows: the DAC switches to USER [slot] (nothing is saved) and one query confirms it; returns the slot confirmed. No band is read. */
    suspend fun selectSlot(device: UsbDevice, slot: Int): Int = withDevice(device) { t ->
        val start = SystemClock.elapsedRealtime()
        val confirmed = nativeSession(device, t, target(device)).selectSlot(slot, explicitUserAction = true); t.checkSession()
        UsbLog.line("switch: slot $slot confirmed $confirmed, ${SystemClock.elapsedRealtime() - start} ms")
        confirmed
    }

    /** A FiiO USER slot Contour has not seen, and the background scan: the DAC switches to USER [slot] (nothing is saved) and that slot is read. */
    suspend fun readSlot(device: UsbDevice, slot: Int): DacSnapshot = withDevice(device) { t ->
        val target = target(device)
        val start = SystemClock.elapsedRealtime()
        UsbLog.line("native read: switch to slot $slot")
        val state = nativeSession(device, t, target).readSlot(slot, explicitUserAction = true); t.checkSession()
        UsbLog.line("read: ${target.caps.name}, slot ${state.slot}, preamp ${state.preamp} dB")
        nativeSnapshot(target, state, SystemClock.elapsedRealtime() - start)
    }

    /** KA15: renames one USER slot and returns the name read back; the EQ on the DAC is untouched. */
    suspend fun renameSlot(device: UsbDevice, slot: Int, name: String): String = withDevice(device) { t ->
        UsbLog.line("native rename: slot $slot -> $name")
        nativeSession(device, t, target(device)).renameSlot(slot, name, explicitUserAction = true).also { t.checkSession() }
    }

    /** A/B: only the supplied bands + TEMP_WRITE, on the existing USB thread. No read-back wait. */
    suspend fun writeTemporaryBands(device: UsbDevice, bands: List<WalkPlay.BandWrite>, slot: Int) = withDevice(device) { t ->
        require(protocol(device).supportsAb) { "A/B unavailable on the ${protocol(device).caps.name.removePrefix("CrinEar ")}: RAM-only writes are not verified" }
        // Encode everything before sending: invalid coefficients cannot leave a partial write.
        val steps = WalkPlay.temporaryBandSequence(bands, slot)
        val t0 = SystemClock.elapsedRealtime()
        UsbLog.line("A/B RAM: ${bands.size} changed bands, slot $slot, preamp kept")
        for (step in steps) {
            t.send(step.report)
            if (step.delayAfterMs > 0) Thread.sleep(step.delayAfterMs)
        }
        UsbLog.line("A/B RAM: TEMP_WRITE sent, ${SystemClock.elapsedRealtime() - t0} ms (DSP not verified by read-back)")
    }

    /** Profile -> device plan -> write -> read back and compare. Throws with the issues when it does not fit.
     * [targetSlot]: KA15 only, the USER slot to write (null = USER1). [onProgress]: 0..1, the frames completed out of those
     * planned (select, every band, preamp, save, the read-back), called on the USB thread. */
    suspend fun writeProfile(device: UsbDevice, profile: Profile, targetSlot: Int? = null, onProgress: ((Float) -> Unit)? = null): WriteResult {
        val target = target(device)
        if (!target.native) return when (val plan = requireNotNull(target.walkplay).plan(profile)) {
            is DevicePlan.Rejected -> throw IOException(plan.issues.joinToString("; "))
            is DevicePlan.Ready -> writePlan(device, plan, onProgress)
        }
        target.issues(profile).takeIf { it.isNotEmpty() }?.let { throw IOException(it.joinToString("; ")) }
        return withDevice(device) { t ->
            val start = SystemClock.elapsedRealtime()
            UsbLog.line("native write: target slot ${targetSlot ?: target.destinationSlot}")
            val r = nativeSession(device, t, target).write(profile, explicitHold = true, targetSlot = targetSlot,
                onProgress = onProgress?.let { report -> { done, total -> report(done.toFloat() / total) } })
            t.checkSession()
            r.reason?.let { UsbLog.line("native write: ${if (r.verified) "VERIFIED, note" else "NOT VERIFIED"}: $it") }
            if (r.reason == null) UsbLog.line("native write: VERIFIED")
            WriteResult(r.verified, listOfNotNull(r.reason), SystemClock.elapsedRealtime() - start, 0,
                nativeSnapshot(target, requireNotNull(r.readback), 0))
        }
    }

    /** Micro: factory flat on all 8 slots, preamp 0. Max: neutral flat PK on all 10 slots, preamp 0. TRN: its factory flat on all 10 slots, preamp 0. */
    suspend fun restoreFlat(device: UsbDevice): WriteResult = writePlan(device, protocol(device).flatPlan())

    /**
     * The state just read with band 1's gain register 0.5 dB lower, like research/protocol/write_test.py
     * step 1 (devicepeq mode). Refuses when band 1 is already at the -10 dB limit.
     */
    suspend fun sendTestBand1(device: UsbDevice): WriteResult = withDevice(device) { t ->
        require(protocol(device) == DeviceProtocol.MICRO) { "Test write is Micro-only" }
        val now = read(t, DeviceProtocol.MICRO)
        val regs = now.bands.map { it.registers }
        val g = regs[0].gain256 - 128
        if (g < -10 * 256) throw IOException("band 1 already at -10 dB, refusing")
        val writes = regs.mapIndexed { i, r ->
            WalkPlay.BandWrite(r.index, r.freq.toDouble(), (if (i == 0) g else r.gain256) / 256.0, r.q256 / 256.0, r.typeCode)
        }
        write(t, DevicePlan.Ready(writes, requireNotNull(now.preampDb)), now.slot, DeviceProtocol.MICRO)
    }

    private suspend fun writePlan(device: UsbDevice, plan: DevicePlan.Ready, onProgress: ((Float) -> Unit)? = null): WriteResult = withDevice(device) { t ->
        val p = protocol(device)
        // Steps planned: the read before (Max: the whole state; Micro: version and slot), every report of the write sequence, the read-back.
        val steps = Steps(
            (if (p.experimental) p.caps.bands + 3 else 2) + runCatching { p.writeSequence(plan, 0).size }
                .getOrDefault(plan.bands.size + 8) + p.caps.bands + 3, onProgress)
        val slot = if (p.experimental) {
            // Protocol Max fails closed: every slot in order, and VERSION + bulk slot + every band + preamp
            // must parse before the first mutating report.
            require(plan.bands.size == p.caps.bands && plan.bands.indices.all { plan.bands[it].index == it }) { "Plan does not cover every ${p.caps.name} slot" }
            read(t, p, steps::tick).slot
        } else {
            // getCurrentSlot, as devicePEQ does on connect: the slot byte is echoed in every band write
            t.request(WalkPlay.versionRequest(), "version") { WalkPlay.isReply(it, WalkPlay.CMD_VERSION) }.also { steps.tick() }
            WalkPlay.parseSlot(t.request(WalkPlay.slotRequest(), "slot") { WalkPlay.isReply(it, WalkPlay.CMD_PEQ) }.also { steps.tick() })
        }
        write(t, plan, slot, p, steps::tick)
    }

    /** Frames done out of [total] planned, as 0..1 for [report]; the total grows if the plan was underestimated, so it never reaches 1 early. */
    private class Steps(private val total: Int, private val report: ((Float) -> Unit)?) {
        private var done = 0
        fun tick() { done++; report?.invoke(done.toFloat() / maxOf(total, done + 1)) }
    }

    /**
     * TRN: a band that did not come back is asked for once more, alone, after the input queue is drained (on the PC
     * on 19.10.2026 a stale reply in the queue made band 1 go missing once). Any other failure is not retried here.
     */
    private fun trnBandReply(t: HidTransport, p: DeviceProtocol, i: Int): ByteArray = try {
        t.request(WalkPlay.bandRequest(i), "band $i") { p.isBandReply(it, i) }
    } catch (e: IOException) {
        if (e.message?.startsWith("the DAC did not answer") != true) throw e
        UsbLog.line("band $i missing: drain and ask once more")
        t.drain()
        t.request(WalkPlay.bandRequest(i), "band $i") { p.isBandReply(it, i) }
    }

    /** MICRO: exactly the 1.2.2 predicates and parsers (DeviceProtocol delegates). MAX, TRN: strict, all 10 slots. */
    private fun read(t: HidTransport, p: DeviceProtocol, tick: () -> Unit = {}): DacSnapshot {
        val t0 = SystemClock.elapsedRealtime()
        val version = p.parseVersion(t.request(WalkPlay.versionRequest(), "version") { p.isReply(it, WalkPlay.CMD_VERSION) }.also { tick() })
        val slot = p.parseSlot(t.request(WalkPlay.slotRequest(), "slot") { p.isSlotReply(it) }.also { tick() })
        val bands = (0 until p.caps.bands).map { i ->
            val reply = if (p == DeviceProtocol.TRN) trnBandReply(t, p, i) else t.request(WalkPlay.bandRequest(i), "band $i") { p.isBandReply(it, i) }
            p.parseBand(reply.also { tick() }, i)
        }
        val preamp = p.parsePreamp(t.request(WalkPlay.preampRequest(), "preamp") { p.isReply(it, WalkPlay.CMD_GLOBAL_GAIN) }.also { tick() })
        val ms = SystemClock.elapsedRealtime() - t0
        UsbLog.line("read: ${if (p.experimental) "${p.caps.name}, " else ""}firmware $version, slot $slot, preamp $preamp dB, $ms ms")
        return DacSnapshot(version, slot, bands, preamp, ms, p)
    }

    /** write_state(..., "devicepeq"): every band report (8 Micro, 10 Max and TRN), preamp, commit sequence with devicePEQ's delays (the TRN: its own sequence); then read back. */
    private fun write(t: HidTransport, plan: DevicePlan.Ready, slot: Int, p: DeviceProtocol, tick: () -> Unit = {}): WriteResult {
        val t0 = SystemClock.elapsedRealtime()
        for (step in p.writeSequence(plan, slot)) {
            t.send(step.report)
            if (step.delayAfterMs > 0) Thread.sleep(step.delayAfterMs)
            tick()
        }
        val writeMs = SystemClock.elapsedRealtime() - t0
        val back = read(t, p, tick)
        val mismatches = ArrayList<String>()
        plan.bands.forEachIndexed { i, w ->
            val want = w.registers()
            val have = back.bands[i].registers
            if (want != have) mismatches += "band ${i + 1}: wrote $want, read $have"
        }
        if (back.preampDb != plan.preampDb) mismatches += "preamp: wrote ${plan.preampDb}, read ${back.preampDb}"
        if (p.experimental && back.slot != slot) mismatches += "slot: wrote $slot, read ${back.slot}"
        val verified = mismatches.isEmpty()
        UsbLog.line("write: ${if (verified) "VERIFIED" else "MISMATCH ${mismatches.size}"}, write $writeMs ms, read-back ${back.readMs} ms")
        mismatches.forEach { UsbLog.line("  $it") }
        return WriteResult(verified, mismatches, writeMs, back.readMs, back)
    }
}
