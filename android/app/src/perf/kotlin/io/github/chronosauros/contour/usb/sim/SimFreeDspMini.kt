package io.github.chronosauros.contour.usb.sim

import io.github.chronosauros.contour.core.native.FreeDspMiniCodec
import io.github.chronosauros.contour.core.native.NativeHidReports
import io.github.chronosauros.contour.core.native.NativePort
import io.github.chronosauros.contour.usb.UsbLog
import java.io.IOException

/**
 * SIMULATED Moondrop FreeDSP Mini for the review builds (`--es simulate freedsp`, slot 7 "Custom EQ"; `--es simulate freedsp-bass`, the same unit on slot 1 "Bass Enhance"; `--es simulate freedsp-replug` replugs the
 * `freedsp` unit). It answers FreeDSP-Studio's three read frames (version `80 0C 01 00`, band `80 09 03 00 i 00`, pre-gain `80 23 00`) and is seeded with the exact replies a real unit gave a tester on 28.11.2026
 * (issue 2, test 1 log): firmware "0.7", eight PEAK bands on slot 7 as MOONDROP's app wrote them, pre-gain -2.0 dB (the second tester's reading; the first read 0.0).
 *
 * It holds two banks like the unit does: a RAM bank (what reads return) and a flash bank. A band frame is only STAGED; the commit `01 0A 04 00 00 FF FF` makes the staged bands what reads return (RAM); the save `01 01 00`
 * copies RAM to flash; [replug] restores RAM from flash, as unplugging the DAC does. Any frame outside the allow-list ([FreeDspMiniCodec.isAllowed]: the three reads, a band write with slot 7 and type 2, the commit,
 * the save) fails LOUDLY: the frame goes to the log as an error line and the call throws, so it can neither be missed nor pass for a success. Every report it is sent and every reply is on the `ContourUsb` log with the
 * report ID, padded to 64 bytes, like the real transport. Nothing here touches USB.
 */
internal class SimFreeDspMini(private val slot: Int = 7, private val variant: String = "") : NativePort {
    override val deviceKey = "simulated:FreeDSP Mini"

    private fun hexBytes(s: String) = s.trim().split(' ').map { it.toInt(16).toByte() }.toByteArray()

    /** The exact RX payloads (report ID excluded, bytes 0-35; the rest is zero) of the tester's eight band reads. */
    private val seed = listOf(
        "80 09 03 00 00 00 00 00 00 00 40 98 54 2c 80 a7 bc d3 3f 68 ab d3 7f 59 43 2c c0 1f 00 c0 00 00 00 02 00 07",
        "80 09 03 00 01 00 00 00 00 00 40 f6 ac 58 80 eb 97 a7 3f 0a 53 a7 7f 15 68 58 c0 3e 00 c0 00 00 00 02 00 07",
        "80 09 03 00 02 00 00 00 00 00 40 47 d7 b2 80 ef 3f 4e 3f b9 28 4d 7f 11 c0 b1 c0 7d 00 c0 00 00 00 02 00 07",
        "80 09 03 00 03 00 00 24 b3 b6 3f 3c ba f8 81 4a 56 53 3e c4 45 07 7e 92 f6 f5 c1 c8 00 80 00 00 fd 02 00 07",
        "80 09 03 00 04 00 00 3a f0 ad 3f 35 a8 77 82 b7 9e eb 3d cb 57 88 7d 0f 71 66 c2 f4 01 00 01 4d fd 02 00 07",
        "80 09 03 00 05 00 00 0d e1 a6 3e 66 80 7c 89 ca b7 1d 38 9a 7f 83 76 2a 67 3b c9 e8 03 80 00 00 fd 02 00 07",
        "80 09 03 00 06 00 00 ab 59 7b 3c 64 ce 16 9a 07 f6 6c 2b 9c 31 e9 65 4e b0 17 d8 b8 0b 80 00 00 fd 02 00 07",
        "80 09 03 00 07 00 00 1f 4c 27 38 d9 83 14 c5 f5 04 1d 12 27 7c eb 3a ec ae bb f5 10 27 80 00 00 fd 02 00 07",
    ).map { hexBytes(it).copyOf(63).also { r -> r[FreeDspMiniCodec.OFF_SLOT] = slot.toByte() } }.also { rows ->
        // `freedsp-odd`: a Custom EQ that is not the tester's all-PEAK bank: band 2 a LOW SHELF (type 1), band 3 an unknown type code (9), band 7 a PEAK at +15 dB (outside +-12)
        // `freedsp-loud`: only the +15 dB peak (an all-PEAK bank that opens in the editor but whose band is outside +-12 dB)
        if (variant == "odd") { rows[1][FreeDspMiniCodec.OFF_TYPE] = 1; rows[2][FreeDspMiniCodec.OFF_TYPE] = 9 }
        if (variant == "odd" || variant == "loud") { rows[6][FreeDspMiniCodec.OFF_GAIN] = 0; rows[6][FreeDspMiniCodec.OFF_GAIN + 1] = 0x0f }
    }

    /** What reads return now (RAM), what the unit keeps over a replug (flash), and the band frames staged by writes that are not committed yet. */
    private val ram = seed.map { it.copyOf() }.toMutableList()
    private val flash = seed.map { it.copyOf() }.toMutableList()
    private val staged = arrayOfNulls<ByteArray>(8)
    private val versionReply = hexBytes("80 0c 01 30 2e 37").copyOf(63)
    private val preGainReply = hexBytes("80 23 02 00 fe").copyOf(63) // -2.0 dB = -512 = 0xFE00

    /** The unit is unplugged and plugged back: RAM comes from flash, staged bands are gone. */
    @Synchronized fun replug() {
        for (i in 0 until 8) ram[i] = flash[i].copyOf()
        staged.fill(null)
        UsbLog.line("SIMULATED FreeDSP Mini: replugged, RAM restored from flash")
    }

    override fun guard() {}
    override fun pause(ms: Long) {}
    override fun payloadSize(id: Int, kind: NativeHidReports.Kind) = 63

    private fun wire(payload: ByteArray) = ByteArray(64).also { it[0] = 0x4B; payload.copyInto(it, 1, 0, minOf(payload.size, 63)) }

    private fun refuse(payload: ByteArray) {
        if (!FreeDspMiniCodec.isAllowed(payload)) {
            UsbLog.line("SIMULATED FreeDSP Mini: FRAME OUTSIDE THE ALLOW-LIST REACHED THE UNIT: ${UsbLog.hex(wire(payload))}")
            throw IllegalStateException("a frame outside the allow-list reached the simulated FreeDSP Mini")
        }
    }

    @Synchronized override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) {
        UsbLog.tx(wire(payload))
        refuse(payload)
        write(payload)
    }

    /** The three writes the allow-list lets through. */
    private fun write(p: ByteArray) {
        when (p[1].toInt() and 255) {
            0x09 -> {
                val i = p[4].toInt() and 255
                // the reply of band i as the unit would give it: the frame's bytes 7-35 (coefficients, frequency, Q, gain, type, 0, slot)
                staged[i] = ByteArray(63).also { r -> p.copyInto(r, 7, 7, 36); r[0] = 0x80.toByte(); r[1] = 0x09; r[2] = 0x03; r[4] = i.toByte() }
                UsbLog.line("SIMULATED FreeDSP Mini: band $i staged")
            }
            0x0A -> {
                for (i in 0 until 8) staged[i]?.let { ram[i] = it }
                staged.fill(null)
                UsbLog.line("SIMULATED FreeDSP Mini: commit, the staged bands are now what reads return (RAM)")
            }
            0x01 -> {
                for (i in 0 until 8) flash[i] = ram[i].copyOf()
                UsbLog.line("SIMULATED FreeDSP Mini: save, RAM copied to flash")
            }
        }
    }

    @Synchronized override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
        UsbLog.tx(wire(payload))
        refuse(payload)
        val out = when (payload[1].toInt() and 255) {
            FreeDspMiniCodec.CMD_VERSION -> versionReply
            FreeDspMiniCodec.CMD_BAND -> ram.getOrNull(payload[4].toInt() and 255) ?: throw IOException("Native request timed out or returned invalid state")
            FreeDspMiniCodec.CMD_PRE_GAIN -> preGainReply
            else -> throw IOException("Native request timed out or returned invalid state")
        }.copyOf()
        UsbLog.rx(wire(out))
        if (!matches(out)) throw IOException("Native request timed out or returned invalid state")
        return out
    }
}
