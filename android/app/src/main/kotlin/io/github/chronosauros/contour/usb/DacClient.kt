package io.github.chronosauros.contour.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import io.github.chronosauros.contour.core.DevicePlan
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.core.ProtocolMicro
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
)

/** A write plus its read-back: verified = every register (freq, Q, gain, type x 8 + preamp) as written. */
data class WriteResult(
    val verified: Boolean,
    val mismatches: List<String>,
    val writeMs: Long,
    val readBackMs: Long,
    val readBack: DacSnapshot,
)

/**
 * Operations on the Protocol Micro. Each one opens the HID interface, does its reports on the single
 * USB thread and releases the interface again. Writes happen only when the user asks (debug buttons).
 */
class DacClient(private val manager: UsbManager) {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "contour-usb") }
    private val usb = executor.asCoroutineDispatcher()

    fun shutdown() = executor.shutdown()

    private suspend fun <T> withDevice(device: UsbDevice, block: (HidTransport) -> T): T = withContext(usb) {
        HidTransport.open(manager, device).use(block)
    }

    suspend fun readDevice(device: UsbDevice): DacSnapshot = withDevice(device) { read(it) }

    /** Profile -> device plan -> write -> read back and compare. Throws with the issues when it does not fit. */
    suspend fun writeProfile(device: UsbDevice, profile: Profile): WriteResult =
        when (val plan = ProtocolMicro.plan(profile)) {
            is DevicePlan.Rejected -> throw IOException(plan.issues.joinToString("; "))
            is DevicePlan.Ready -> writePlan(device, plan)
        }

    /** Factory flat on all 8 slots, preamp 0. */
    suspend fun restoreFlat(device: UsbDevice): WriteResult = writePlan(device, ProtocolMicro.flatPlan())

    /**
     * The state just read with band 1's gain register 0.5 dB lower, like research/protocol/write_test.py
     * step 1 (devicepeq mode). Refuses when band 1 is already at the -10 dB limit.
     */
    suspend fun sendTestBand1(device: UsbDevice): WriteResult = withDevice(device) { t ->
        val now = read(t)
        val regs = now.bands.map { it.registers }
        val g = regs[0].gain256 - 128
        if (g < -10 * 256) throw IOException("band 1 already at -10 dB, refusing")
        val writes = regs.mapIndexed { i, r ->
            WalkPlay.BandWrite(r.index, r.freq.toDouble(), (if (i == 0) g else r.gain256) / 256.0, r.q256 / 256.0, r.typeCode)
        }
        write(t, DevicePlan.Ready(writes, now.preampDb), now.slot)
    }

    private suspend fun writePlan(device: UsbDevice, plan: DevicePlan.Ready): WriteResult = withDevice(device) { t ->
        // getCurrentSlot, as devicePEQ does on connect: the slot byte is echoed in every band write
        t.request(WalkPlay.versionRequest(), "version") { WalkPlay.isReply(it, WalkPlay.CMD_VERSION) }
        val slot = WalkPlay.parseSlot(t.request(WalkPlay.slotRequest(), "slot") { WalkPlay.isReply(it, WalkPlay.CMD_PEQ) })
        write(t, plan, slot)
    }

    private fun read(t: HidTransport): DacSnapshot {
        val t0 = SystemClock.elapsedRealtime()
        val version = WalkPlay.parseVersion(t.request(WalkPlay.versionRequest(), "version") { WalkPlay.isReply(it, WalkPlay.CMD_VERSION) })
        val slot = WalkPlay.parseSlot(t.request(WalkPlay.slotRequest(), "slot") { WalkPlay.isReply(it, WalkPlay.CMD_PEQ) })
        val bands = (0 until WalkPlay.BANDS).map { i ->
            WalkPlay.parseBand(t.request(WalkPlay.bandRequest(i), "band $i") { WalkPlay.isBandReply(it, i) })
        }
        val preamp = WalkPlay.parsePreamp(t.request(WalkPlay.preampRequest(), "preamp") { WalkPlay.isReply(it, WalkPlay.CMD_GLOBAL_GAIN) })
        val ms = SystemClock.elapsedRealtime() - t0
        UsbLog.line("read: firmware $version, slot $slot, preamp $preamp dB, $ms ms")
        return DacSnapshot(version, slot, bands, preamp, ms)
    }

    /** write_state(..., "devicepeq"): 8 band reports, preamp, commit sequence with devicePEQ's delays; then read back. */
    private fun write(t: HidTransport, plan: DevicePlan.Ready, slot: Int): WriteResult {
        val t0 = SystemClock.elapsedRealtime()
        for (step in WalkPlay.writeSequence(plan.bands, plan.preampDb.toDouble(), slot, commit = true)) {
            t.send(step.report)
            if (step.delayAfterMs > 0) Thread.sleep(step.delayAfterMs)
        }
        val writeMs = SystemClock.elapsedRealtime() - t0
        val back = read(t)
        val mismatches = ArrayList<String>()
        plan.bands.forEachIndexed { i, w ->
            val want = w.registers()
            val have = back.bands[i].registers
            if (want != have) mismatches += "band ${i + 1}: wrote $want, read $have"
        }
        if (back.preampDb != plan.preampDb) mismatches += "preamp: wrote ${plan.preampDb}, read ${back.preampDb}"
        val verified = mismatches.isEmpty()
        UsbLog.line("write: ${if (verified) "VERIFIED" else "MISMATCH ${mismatches.size}"}, write $writeMs ms, read-back ${back.readMs} ms")
        mismatches.forEach { UsbLog.line("  $it") }
        return WriteResult(verified, mismatches, writeMs, back.readMs, back)
    }
}
