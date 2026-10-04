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
import io.github.chronosauros.contour.core.native.NativeHidReports
import io.github.chronosauros.contour.core.native.NativePort
import io.github.chronosauros.contour.core.native.NativeSession
import io.github.chronosauros.contour.core.native.NativeState
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
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
 * written; on the Protocol Max the bulk slot byte as well; on the KA15 the complete native USER slot state.
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
 * Operations on the supported DACs (Protocol Micro, Protocol Max, FiiO KA15). Each one opens the HID interface, does its reports on the single
 * USB thread and releases the interface again. Writes happen only when the user asks (debug buttons).
 * [guardFor] (KA15) throws once the DAC session the operation started on has ended; [audioKeepAlive] keeps the
 * KA15's USB audio stream running for the whole HID session (it answers HID only while audio streams).
 */
class DacClient(private val manager: UsbManager, private val guardFor: (UsbDevice) -> (() -> Unit) = { {} },
    private val audioKeepAlive: (UsbDevice) -> AutoCloseable? = { null }) {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "contour-usb") }
    private val usb = executor.asCoroutineDispatcher()

    fun shutdown() = executor.shutdown()

    private suspend fun <T> withDevice(device: UsbDevice, block: (HidTransport) -> T): T {
        val fiio = target(device).fiio ?: return withContext(usb) { HidTransport.open(manager, device).use(block) }
        val guard = guardFor(device)
        return withContext(usb) {
            guard()
            val stream = if (fiio.needsAudioStream) audioKeepAlive(device) else null
            try { HidTransport.open(manager, device, guard).use(block).also { guard() } } finally { stream?.close() }
        }
    }

    private fun target(device: UsbDevice): DeviceTarget =
        DeviceTarget.find(device.vendorId, device.productId) ?: throw IOException("Unsupported DAC")

    private fun protocol(device: UsbDevice): DeviceProtocol =
        target(device).walkplay ?: throw IOException("WalkPlay-only operation unavailable on ${target(device).caps.name}")

    private fun nativeSession(device: UsbDevice, t: HidTransport, target: DeviceTarget) = NativeSession(target, object : NativePort {
        override val deviceKey = "${device.deviceName}:${device.deviceId}:${device.vendorId}:${device.productId}"
        override fun guard() = t.checkSession()
        override fun pause(ms: Long) { t.checkSession(); Thread.sleep(ms); t.checkSession() }
        override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) = t.sendNative(id, kind, payload)
        override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean) = t.requestNative(id, payload, matches)
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

    /** KA15: renames one USER slot and returns the name read back; the EQ on the DAC is untouched. */
    suspend fun renameSlot(device: UsbDevice, slot: Int, name: String): String = withDevice(device) { t ->
        UsbLog.line("native rename: slot $slot -> $name")
        nativeSession(device, t, target(device)).renameSlot(slot, name, explicitUserAction = true).also { t.checkSession() }
    }

    /** A/B: only the supplied bands + TEMP_WRITE, on the existing USB thread. No read-back wait. */
    suspend fun writeTemporaryBands(device: UsbDevice, bands: List<WalkPlay.BandWrite>, slot: Int) = withDevice(device) { t ->
        require(protocol(device).supportsAb) { "A/B unavailable on the Protocol Max: RAM-only writes are not verified" }
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
     * [targetSlot]: KA15 only, the USER slot to write (null = USER1). */
    suspend fun writeProfile(device: UsbDevice, profile: Profile, targetSlot: Int? = null): WriteResult {
        val target = target(device)
        if (!target.native) return when (val plan = requireNotNull(target.walkplay).plan(profile)) {
            is DevicePlan.Rejected -> throw IOException(plan.issues.joinToString("; "))
            is DevicePlan.Ready -> writePlan(device, plan)
        }
        target.issues(profile).takeIf { it.isNotEmpty() }?.let { throw IOException(it.joinToString("; ")) }
        return withDevice(device) { t ->
            val start = SystemClock.elapsedRealtime()
            UsbLog.line("native write: target slot ${targetSlot ?: target.destinationSlot}")
            val r = nativeSession(device, t, target).write(profile, explicitHold = true, targetSlot = targetSlot)
            t.checkSession()
            r.reason?.let { UsbLog.line("native write: ${if (r.verified) "VERIFIED, note" else "NOT VERIFIED"}: $it") }
            if (r.reason == null) UsbLog.line("native write: VERIFIED")
            WriteResult(r.verified, listOfNotNull(r.reason), SystemClock.elapsedRealtime() - start, 0,
                nativeSnapshot(target, requireNotNull(r.readback), 0))
        }
    }

    /** Micro: factory flat on all 8 slots, preamp 0. Max: neutral flat PK on all 10 slots, preamp 0. */
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

    private suspend fun writePlan(device: UsbDevice, plan: DevicePlan.Ready): WriteResult = withDevice(device) { t ->
        val p = protocol(device)
        val slot = if (p.experimental) {
            // Protocol Max fails closed: every slot in order, and VERSION + bulk slot + every band + preamp
            // must parse before the first mutating report.
            require(plan.bands.size == p.caps.bands && plan.bands.indices.all { plan.bands[it].index == it }) { "Plan does not cover every ${p.caps.name} slot" }
            read(t, p).slot
        } else {
            // getCurrentSlot, as devicePEQ does on connect: the slot byte is echoed in every band write
            t.request(WalkPlay.versionRequest(), "version") { WalkPlay.isReply(it, WalkPlay.CMD_VERSION) }
            WalkPlay.parseSlot(t.request(WalkPlay.slotRequest(), "slot") { WalkPlay.isReply(it, WalkPlay.CMD_PEQ) })
        }
        write(t, plan, slot, p)
    }

    /** MICRO: exactly the 1.2.2 predicates and parsers (DeviceProtocol delegates). MAX: strict, all 10 slots. */
    private fun read(t: HidTransport, p: DeviceProtocol): DacSnapshot {
        val t0 = SystemClock.elapsedRealtime()
        val version = p.parseVersion(t.request(WalkPlay.versionRequest(), "version") { p.isReply(it, WalkPlay.CMD_VERSION) })
        val slot = p.parseSlot(t.request(WalkPlay.slotRequest(), "slot") { p.isSlotReply(it) })
        val bands = (0 until p.caps.bands).map { i ->
            p.parseBand(t.request(WalkPlay.bandRequest(i), "band $i") { p.isBandReply(it, i) }, i)
        }
        val preamp = p.parsePreamp(t.request(WalkPlay.preampRequest(), "preamp") { p.isReply(it, WalkPlay.CMD_GLOBAL_GAIN) })
        val ms = SystemClock.elapsedRealtime() - t0
        UsbLog.line("read: ${if (p.experimental) "${p.caps.name}, " else ""}firmware $version, slot $slot, preamp $preamp dB, $ms ms")
        return DacSnapshot(version, slot, bands, preamp, ms, p)
    }

    /** write_state(..., "devicepeq"): every band report (8 Micro, 10 Max), preamp, commit sequence with devicePEQ's delays; then read back. */
    private fun write(t: HidTransport, plan: DevicePlan.Ready, slot: Int, p: DeviceProtocol): WriteResult {
        val t0 = SystemClock.elapsedRealtime()
        for (step in WalkPlay.writeSequence(plan.bands, plan.preampDb.toDouble(), slot, commit = true)) {
            t.send(step.report)
            if (step.delayAfterMs > 0) Thread.sleep(step.delayAfterMs)
        }
        val writeMs = SystemClock.elapsedRealtime() - t0
        val back = read(t, p)
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
