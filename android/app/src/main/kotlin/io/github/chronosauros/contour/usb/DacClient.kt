package io.github.chronosauros.contour.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import io.github.chronosauros.contour.core.DevicePlan
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.core.DeviceProtocol
import io.github.chronosauros.contour.core.DeviceTarget
import io.github.chronosauros.contour.core.DacImport
import io.github.chronosauros.contour.core.native.*
import io.github.chronosauros.contour.BuildConfig
import io.github.chronosauros.contour.core.WalkPlay
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/** What one lock-step read returned. */
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
 * written; on the Protocol Max and the other strict targets the bulk slot byte as well.
 */
data class WriteResult(
    val verified: Boolean,
    val mismatches: List<String>,
    val writeMs: Long,
    val readBackMs: Long,
    val readBack: DacSnapshot?,
    val pending: Boolean = false,
)

/**
 * Operations on explicitly supported DACs (Protocol Micro, Protocol Max, advanced-build targets). Each one opens the HID interface, does its reports on the single
 * USB thread and releases the interface again. Writes happen only when the user asks (debug buttons).
 */
class DacClient(private val manager: UsbManager, private val guardFor: (UsbDevice) -> (() -> Unit) = { {} },
    private val generationFor: () -> Long = { 0L }, private val audioKeepAlive: (UsbDevice) -> AutoCloseable? = { null }) {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "contour-usb") }
    private val usb = executor.asCoroutineDispatcher()

    fun shutdown() = executor.shutdown()

    private suspend fun <T> withDevice(device: UsbDevice, block: (HidTransport) -> T): T {
        val guard = guardFor(device)
        return withContext(usb) {
            guard()
            val stream = if (runCatching { target(device).fiio?.needsAudioStream }.getOrNull() == true) audioKeepAlive(device) else null
            try { HidTransport.open(manager, device, guard).use(block).also { guard() } } finally { stream?.close() }
        }
    }

    private fun protocol(device: UsbDevice): DeviceProtocol =
        target(device).walkplay ?: throw IOException("WalkPlay-only operation unavailable on a native DAC")

    private fun target(device: UsbDevice): DeviceTarget = DeviceTarget.find(device.vendorId, device.productId,
        BuildConfig.ADVANCED, device.productName) ?: throw IOException("Unsupported exact DAC identity")
    private fun nativeSession(device: UsbDevice, t: HidTransport, target: DeviceTarget): NativeSession = NativeSession(target, object : NativePort {
        override val deviceKey = "${device.deviceName}:${device.deviceId}:${device.vendorId}:${device.productId}:${device.productName}:generation:${generationFor()}"
        override fun payloadSize(id: Int, kind: NativeHidReports.Kind): Int = t.nativePayloadSize(id, kind)
        override fun guard() = t.checkSession()
        override fun pause(ms: Long) { t.checkSession(); Thread.sleep(ms); t.checkSession() }
        override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) = t.sendNative(id, kind, payload)
        override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean) = t.requestNative(id, payload, matches)
        override fun feature(id: Int) = t.featureNative(id)    })
    private fun nativeSnapshot(target: DeviceTarget, state: NativeState, ms: Long) =
        DacSnapshot("native / hardware unverified", state.slot, emptyList(), null, ms,
            protocol = null, nativeState = state, target = target)
    suspend fun readDevice(device: UsbDevice): DacSnapshot = withDevice(device) { t ->
        val target = target(device)
        if (!target.native) read(t, requireNotNull(target.walkplay)) else {
            val start = SystemClock.elapsedRealtime()
            val state = nativeSession(device, t, target).read(); t.checkSession()
            nativeSnapshot(target, state, SystemClock.elapsedRealtime() - start)
        }
    }
    /** KA15: renames one USER slot and returns the name read back; the EQ on the DAC is untouched. */
    suspend fun renameSlot(device: UsbDevice, slot: Int, name: String): String = withDevice(device) { t ->
        UsbLog.line("native rename: slot $slot -> $name")
        nativeSession(device, t, target(device)).renameSlot(slot, name, explicitUserAction = true).also { t.checkSession() }
    }

    /** Hardware volume (USB Audio Class), on the same USB thread as the HID operations. */
    suspend fun readVolume(device: UsbDevice): UacVolume.State {
        val guard = guardFor(device)
        return withContext(usb) {
            guard(); require(protocol(device) == DeviceProtocol.MICRO) { "Hardware volume is Micro-only" }
            UacVolume.read(manager, device).also { guard() }
        }
    }

    suspend fun writeVolume(device: UsbDevice, value256: Int): UacVolume.State {
        val guard = guardFor(device)
        return withContext(usb) {
            guard(); require(protocol(device) == DeviceProtocol.MICRO) { "Hardware volume is Micro-only" }
            UacVolume.write(manager, device, value256, guard).also { guard() }
        }
    }

    /** A/B: only the supplied bands + TEMP_WRITE, on the existing USB thread. No read-back wait. */
    suspend fun writeTemporaryBands(device: UsbDevice, bands: List<WalkPlay.BandWrite>, slot: Int) = withDevice(device) { t ->
        require(protocol(device).supportsAb) { "A/B unavailable on ${protocol(device).caps.name}: RAM-only writes are not verified" }
        // Encode everything before sending: invalid coefficients cannot leave a partial write.
        val steps = WalkPlay.temporaryBandSequence(bands, slot)
        val t0 = SystemClock.elapsedRealtime()
        UsbLog.line("A/B RAM: ${bands.size} changed bands, slot $slot, preamp kept")
        for (step in steps) {
            t.send(step.report)
            if (step.delayAfterMs > 0) { t.checkSession(); Thread.sleep(step.delayAfterMs); t.checkSession() }
        }
        UsbLog.line("A/B RAM: TEMP_WRITE sent, ${SystemClock.elapsedRealtime() - t0} ms (DSP not verified by read-back)")
    }

    /** Profile -> device plan -> write -> read back and compare. Throws with the issues when it does not fit. */
    suspend fun writeProfile(device: UsbDevice, profile: Profile, targetSlot: Int? = null, retainPending: (NativePendingExpected) -> Unit = {}): WriteResult {
        val target = target(device)
        if (!target.native) return when (val plan = requireNotNull(target.walkplay).plan(profile)) {
            is DevicePlan.Rejected -> throw IOException(plan.issues.joinToString("; "))
            is DevicePlan.Ready -> writePlan(device, plan)
        }
        require(target.issues(profile).isEmpty()) { target.issues(profile).joinToString("; ") }
        return withDevice(device) { t ->
            val start = SystemClock.elapsedRealtime()
            UsbLog.line("native write: target slot ${targetSlot ?: target.destinationSlot}")
            val r = nativeSession(device, t, target).write(profile, explicitHold = true, retainPending = retainPending, targetSlot = targetSlot)
            t.checkSession()
            r.reason?.let { UsbLog.line("native write: ${if (r.verified) "VERIFIED, note" else "NOT VERIFIED"}: $it") }
            WriteResult(r.verified, listOfNotNull(r.reason), SystemClock.elapsedRealtime() - start, 0,
                r.readback?.let { nativeSnapshot(target, it, 0) }, r.pending)
        }
    }

    /** Micro: factory flat on all 8 slots, preamp 0. Max: neutral flat PK on all 10 slots, preamp 0 (as in 1.3.0). */
    suspend fun restoreFlat(device: UsbDevice): WriteResult {
        require(protocol(device) == DeviceProtocol.MICRO || protocol(device).isMax) {
            "Service reset is Micro/Max-only; use a flat profile and HOLD TO SEND" }
        return writePlan(device, protocol(device).flatPlan())
    }

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

    private suspend fun writePlan(device: UsbDevice, plan: DevicePlan.Ready): WriteResult = withDevice(device) { t ->
        val p = protocol(device)
        val slot = if (p.experimental) {
            // Protocol Max and every other strict target fail closed: every slot in order, and VERSION + bulk slot +
            // every band + preamp must parse before the first mutating report. Never a cached/partial snapshot.
            require(plan.bands.size == p.caps.bands && plan.bands.indices.all { plan.bands[it].index == it }) { "Plan does not cover every ${p.caps.name} slot" }
            read(t, p).slot
        } else {
            // Micro, exactly 1.2.2/1.3.0: getCurrentSlot, as devicePEQ does on connect: the slot byte is echoed in every band write
            t.request(WalkPlay.versionRequest(), "version") { WalkPlay.isReply(it, WalkPlay.CMD_VERSION) }
            WalkPlay.parseSlot(t.request(WalkPlay.slotRequest(), "slot") { WalkPlay.isReply(it, WalkPlay.CMD_PEQ) })
                .also { t.checkSession() }
        }
        write(t, plan, slot, p)
    }

    private fun read(t: HidTransport, p: DeviceProtocol): DacSnapshot {
        val t0 = SystemClock.elapsedRealtime()
        val state = p.readComplete({ t.checkSession() }) { report, what, matches -> t.request(report, what, matches) }
        t.checkSession()
        val ms = SystemClock.elapsedRealtime() - t0
        UsbLog.line("read: ${if (p.experimental) "${p.caps.name}, " else ""}firmware ${state.firmware}, slot ${state.slot}, preamp ${state.preampDb} dB, $ms ms")
        return DacSnapshot(state.firmware, state.slot, state.bands, state.preampDb, ms, p)
    }

    /** write_state(..., "devicepeq"): every band report (8 Micro, 10 Max, catalog count), preamp, commit sequence with devicePEQ's delays; then read back. */
    private fun write(t: HidTransport, plan: DevicePlan.Ready, slot: Int, p: DeviceProtocol): WriteResult {
        val t0 = SystemClock.elapsedRealtime()
        p.executeWrite(plan, slot, { t.checkSession() }, { t.send(it) }, { Thread.sleep(it) })
        val writeMs = SystemClock.elapsedRealtime() - t0
        val back = read(t, p)
        val mismatches = ArrayList<String>()
        plan.bands.forEachIndexed { i, w ->
            val want = w.registers()
            val have = back.bands[i].registers
            if (want != have) mismatches += "band ${i + 1}: wrote $want, read $have"
            // Coefficient read-back only for catalog targets; Micro, Max (1.3.0) and TRN verify registers.
            if (p != DeviceProtocol.MICRO && !p.isMax && !p.isTrn &&
                !WalkPlay.computeIir(w.freq, w.gainDb, w.q, w.typeCode).contentEquals(back.bands[i].biquad))
                mismatches += "band ${i + 1}: coefficient read-back mismatch"

        }
        if (back.preampDb != plan.preampDb) mismatches += "preamp: wrote ${plan.preampDb}, read ${back.preampDb}"
        if (p.experimental && back.slot != slot) mismatches += "slot: wrote $slot, read ${back.slot}"
        if (p.experimental && !p.matchesReadback(plan, DeviceProtocol.ReadState(back.firmware, back.slot, back.bands, requireNotNull(back.preampDb)), slot) && mismatches.isEmpty())
            mismatches += "Incomplete raw register read-back"
        val verified = mismatches.isEmpty()
        UsbLog.line("write: ${if (verified) "VERIFIED" else "MISMATCH ${mismatches.size}"}, write $writeMs ms, read-back ${back.readMs} ms")
        mismatches.forEach { UsbLog.line("  $it") }
        return WriteResult(verified, mismatches, writeMs, back.readMs, back)
    }
}
