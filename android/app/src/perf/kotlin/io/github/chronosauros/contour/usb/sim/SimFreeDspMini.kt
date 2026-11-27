package io.github.chronosauros.contour.usb.sim

import io.github.chronosauros.contour.core.native.FreeDspMiniCodec
import io.github.chronosauros.contour.core.native.NativeHidReports
import io.github.chronosauros.contour.core.native.NativePort
import io.github.chronosauros.contour.usb.UsbLog
import java.io.IOException

/**
 * SIMULATED Moondrop FreeDSP Mini for the review builds (`--es simulate freedsp`): it answers FreeDSP-Studio's three read frames (version `80 0C 01 00`, band `80 09 03 00 i 00`, pre-gain `80 23 00`) with replies in
 * FreeDSP-Studio's layout (report ID excluded: index at 4, frequency at 27, Q at 29, gain at 31, type at 33, active slot at 35; dsp2.rs 47-58): slot 7, eight plausible bands, pre-gain -3.0 dB, version "0.7".
 * Nothing here was read from a Mini. It fails LOUDLY on any frame with OP 0x01: the frame goes to the log as an error line and the call throws, so a write that reached it can neither be missed nor pass for a success.
 * Like the real transport it puts every report it is sent and every reply on the `ContourUsb` log, with the report ID and padded to 64 bytes. Nothing here touches USB.
 */
internal class SimFreeDspMini : NativePort {
    override val deviceKey = "simulated:FreeDSP Mini"
    private val slot = 7
    private val preGainQ8 = -768
    private val version = "0.7"
    /** Hz, gain in tenths of a dB, Q in hundredths, type code (1 low shelf, 2 peak, 3 high shelf). */
    private val bands = listOf(
        intArrayOf(60, 20, 70, 1), intArrayOf(150, -15, 100, 2), intArrayOf(400, 10, 120, 2), intArrayOf(1000, 0, 100, 2),
        intArrayOf(2500, -25, 200, 2), intArrayOf(4000, 15, 150, 2), intArrayOf(8000, -30, 200, 2), intArrayOf(12000, 20, 70, 3),
    )

    override fun guard() {}
    override fun pause(ms: Long) {}
    override fun payloadSize(id: Int, kind: NativeHidReports.Kind) = 63

    private fun wire(payload: ByteArray) = ByteArray(64).also { it[0] = 0x4B; payload.copyInto(it, 1, 0, minOf(payload.size, 63)) }

    private fun refuseWrite(payload: ByteArray) {
        if ((payload[0].toInt() and 255) == 0x01) {
            UsbLog.line("SIMULATED FreeDSP Mini: WRITE FRAME REACHED THE UNIT, THIS BUILD MUST NEVER SEND ONE: ${UsbLog.hex(wire(payload))}")
            throw IllegalStateException("a frame with OP 0x01 reached the simulated FreeDSP Mini")
        }
    }

    override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) { UsbLog.tx(wire(payload)); refuseWrite(payload) }

    override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
        UsbLog.tx(wire(payload))
        refuseWrite(payload)
        val out = ByteArray(63)
        fun put(at: Int, v: Int, n: Int = 1) { for (k in 0 until n) out[at + k] = (v shr (8 * k)).toByte() }
        when (payload[1].toInt() and 255) {
            FreeDspMiniCodec.CMD_VERSION -> { put(0, 0x80); put(1, FreeDspMiniCodec.CMD_VERSION); put(2, version.length); version.forEachIndexed { k, c -> put(3 + k, c.code) } }
            FreeDspMiniCodec.CMD_BAND -> {
                val i = payload[4].toInt() and 255
                val b = bands.getOrNull(i) ?: throw IOException("Native request timed out or returned invalid state")
                put(0, 0x80); put(1, FreeDspMiniCodec.CMD_BAND); put(2, 0x21); put(4, i)
                put(FreeDspMiniCodec.OFF_FREQ, b[0], 2); put(FreeDspMiniCodec.OFF_Q, b[2] * 256 / 100, 2); put(FreeDspMiniCodec.OFF_GAIN, b[1] * 256 / 10, 2)
                put(FreeDspMiniCodec.OFF_TYPE, b[3]); put(FreeDspMiniCodec.OFF_SLOT, slot)
            }
            FreeDspMiniCodec.CMD_PRE_GAIN -> { put(0, 0x80); put(1, FreeDspMiniCodec.CMD_PRE_GAIN); put(2, 2); put(3, preGainQ8, 2) }
            else -> throw IOException("Native request timed out or returned invalid state") // nothing else is answered: the Mini is never asked the Dawn's questions
        }
        UsbLog.rx(wire(out))
        if (!matches(out)) throw IOException("Native request timed out or returned invalid state")
        return out
    }
}
