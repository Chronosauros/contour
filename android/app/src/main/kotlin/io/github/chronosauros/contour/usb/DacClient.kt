package io.github.chronosauros.contour.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import io.github.chronosauros.contour.core.DevicePlan
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.core.DeviceProtocol
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
    val preampDb: Int,
    val readMs: Long,
    val protocol: DeviceProtocol = DeviceProtocol.MICRO,
)

/** A write plus its read-back: verified = every register (freq, Q, gain, type x device slot count + preamp and bulk slot) as written. */
data class WriteResult(
    val verified: Boolean,
    val mismatches: List<String>,
    val writeMs: Long,
    val readBackMs: Long,
    val readBack: DacSnapshot,
)

/**
 * Operations on explicitly supported DACs. Each one opens the HID interface, does its reports on the single
 * USB thread and releases the interface again. Writes happen only when the user asks (debug buttons).
 */
class DacClient(private val manager: UsbManager) {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "contour-usb") }
    private val usb = executor.asCoroutineDispatcher()

    fun shutdown() = executor.shutdown()

    private suspend fun <T> withDevice(device: UsbDevice, block: (HidTransport) -> T): T = withContext(usb) {
        HidTransport.open(manager, device).use(block)
    }

    private fun protocol(device: UsbDevice): DeviceProtocol =
        DeviceProtocol.find(device.vendorId, device.productId, BuildConfig.ADVANCED) ?: throw IOException("Unsupported DAC")

    suspend fun readDevice(device: UsbDevice): DacSnapshot = withDevice(device) { read(it, protocol(device)) }

    /** Hardware volume (USB Audio Class), on the same USB thread as the HID operations. */
    suspend fun readVolume(device: UsbDevice): UacVolume.State = withContext(usb) {
        require(protocol(device) == DeviceProtocol.MICRO) { "Hardware volume unavailable on experimental TRN support" }
        UacVolume.read(manager, device)
    }

    suspend fun writeVolume(device: UsbDevice, value256: Int): UacVolume.State = withContext(usb) {
        require(protocol(device) == DeviceProtocol.MICRO) { "Hardware volume unavailable on experimental TRN support" }
        UacVolume.write(manager, device, value256)
    }

    /** A/B: only the supplied bands + TEMP_WRITE, on the existing USB thread. No read-back wait. */
    suspend fun writeTemporaryBands(device: UsbDevice, bands: List<WalkPlay.BandWrite>, slot: Int) = withDevice(device) { t ->
        require(protocol(device).supportsAb) { "A/B unavailable: TRN RAM-only writes are not hardware verified" }
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

    /** Profile -> device plan -> write -> read back and compare. Throws with the issues when it does not fit. */
    suspend fun writeProfile(device: UsbDevice, profile: Profile): WriteResult =
        when (val plan = protocol(device).plan(profile)) {
            is DevicePlan.Rejected -> throw IOException(plan.issues.joinToString("; "))
            is DevicePlan.Ready -> writePlan(device, plan)
        }

    /** Neutral flat on every device slot, preamp 0. */
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
        write(t, DevicePlan.Ready(writes, now.preampDb), now.slot, DeviceProtocol.MICRO)
    }

    private suspend fun writePlan(device: UsbDevice, plan: DevicePlan.Ready): WriteResult = withDevice(device) { t ->
        val p = protocol(device)
        require(plan.bands.size == p.caps.bands && plan.bands.indices.all { plan.bands[it].index == it })
        // TRN fails closed: VERSION + bulk slot + every band + preamp must parse before any mutating report.
        val slot = if (p.experimental) read(t, p).slot else {
            p.parseVersion(t.request(WalkPlay.versionRequest(), "version") { p.isReply(it, WalkPlay.CMD_VERSION) })
            p.parseSlot(t.request(WalkPlay.slotRequest(), "slot") { p.isBandReply(it, 0) })
        }
        write(t, plan, slot, p)
    }

    private fun read(t: HidTransport, p: DeviceProtocol): DacSnapshot {
        val t0 = SystemClock.elapsedRealtime()
        val version = p.parseVersion(t.request(WalkPlay.versionRequest(), "version") { p.isReply(it, WalkPlay.CMD_VERSION) })
        val slot = p.parseSlot(t.request(WalkPlay.slotRequest(), "slot") { p.isBandReply(it, 0) })
        val bands = (0 until p.caps.bands).map { i ->
            p.parseBand(t.request(WalkPlay.bandRequest(i), "band $i") { p.isBandReply(it, i) }, i)
        }
        val preamp = p.parsePreamp(t.request(WalkPlay.preampRequest(), "preamp") { p.isReply(it, WalkPlay.CMD_GLOBAL_GAIN) })
        val ms = SystemClock.elapsedRealtime() - t0
        UsbLog.line("read: firmware $version, slot $slot, preamp $preamp dB, $ms ms")
        return DacSnapshot(version, slot, bands, preamp, ms, p)
    }

    /** write_state(..., "devicepeq"): All device band reports, preamp, commit sequence with devicePEQ's delays; then read back. */
    private fun write(t: HidTransport, plan: DevicePlan.Ready, slot: Int, p: DeviceProtocol): WriteResult {
        val t0 = SystemClock.elapsedRealtime()
        val steps = WalkPlay.writeSequence(plan.bands, plan.preampDb.toDouble(), slot, commit = true)
        for (step in steps) {
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
        if (back.slot != slot) mismatches += "slot: wrote $slot, read ${back.slot}"
        val verified = mismatches.isEmpty()
        UsbLog.line("write: ${if (verified) "VERIFIED" else "MISMATCH ${mismatches.size}"}, write $writeMs ms, read-back ${back.readMs} ms")
        mismatches.forEach { UsbLog.line("  $it") }
        return WriteResult(verified, mismatches, writeMs, back.readMs, back)
    }
}
