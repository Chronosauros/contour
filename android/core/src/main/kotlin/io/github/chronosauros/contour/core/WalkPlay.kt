package io.github.chronosauros.contour.core

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WalkPlay HID PEQ protocol, SchemeNo11 (CrinEar Protocol Micro, 3302:C20F).
 *
 * A 1:1 port of `research/protocol/walkplay.py`, itself a port of devicePEQ's walkplayHidHandler.js
 * (0BSD, Jerome O'Flaherty, commit 0617f382) verified bit-exact against the device.
 * Every buffer here is a full 64-byte report with the report ID at [0], so `buf[n] == d[n - 1]`
 * in PROTOCOL.md's WebHID offsets. JS semantics are kept: `Math.round` = floor(x + 0.5),
 * `>> 0` truncation, int32 wrap.
 */
object WalkPlay {
    const val VENDOR_ID: Int = 0x3302
    const val PRODUCT_ID: Int = 0xC20F
    const val REPORT_ID: Int = 0x4B
    const val REPORT_SIZE: Int = 64            // report ID + 63 payload bytes
    const val READ: Int = 0x80
    const val WRITE: Int = 0x01
    const val END: Int = 0x00
    const val CMD_GLOBAL_GAIN: Int = 0x03
    const val CMD_PEQ: Int = 0x09
    const val CMD_VERSION: Int = 0x0C
    const val BANDS: Int = 8
    const val FREQ_FACTOR: Double = 0.9775     // freqCompensation ratio (realised / requested)
    const val DESIGN_FS: Double = 96_000.0     // qCompensation cosNyquist designFs, and the biquad rate

    const val TYPE_LSQ: Int = 1
    const val TYPE_PK: Int = 2
    const val TYPE_HSQ: Int = 3
    const val TYPE_LP: Int = 4
    const val TYPE_HP: Int = 5

    /** What the device holds today in every slot (factory flat): PK, 0 dB, Q 0.75, these raw frequencies. */
    val FACTORY_FREQS: IntArray = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000)
    const val FACTORY_Q: Double = 0.75

    // ---- JS number semantics ---------------------------------------------------------------------

    /** JS `Math.round`. */
    fun jsRound(x: Double): Double = floor(x + 0.5)

    /** JS ToInt32 (`x >> 0`, `x & 0xFF` operands) for |x| < 2^63: truncate, then wrap modulo 2^32. */
    fun toInt32(x: Double): Int = x.toLong().toInt()

    fun typeCode(type: FilterType): Int = when (type) {
        FilterType.LOW_SHELF -> TYPE_LSQ
        FilterType.PEAK -> TYPE_PK
        FilterType.HIGH_SHELF -> TYPE_HSQ
        FilterType.LOW_PASS -> TYPE_LP
        FilterType.HIGH_PASS -> TYPE_HP
    }

    /** devicePEQ convertToFilterType: unknown codes read as PK. */
    fun filterType(code: Int): FilterType = when (code) {
        TYPE_LSQ -> FilterType.LOW_SHELF
        TYPE_HSQ -> FilterType.HIGH_SHELF
        TYPE_LP -> FilterType.LOW_PASS
        TYPE_HP -> FilterType.HIGH_PASS
        else -> FilterType.PEAK
    }

    private fun qCompensated(typeCode: Int): Boolean = typeCode == TYPE_PK || typeCode == TYPE_LSQ || typeCode == TYPE_HSQ

    // ---- reports ---------------------------------------------------------------------------------

    /** 64-byte output report: report ID, then [payload], zero padded. */
    fun report(vararg payload: Int): ByteArray {
        require(payload.size <= REPORT_SIZE - 1)
        val buf = ByteArray(REPORT_SIZE)
        buf[0] = REPORT_ID.toByte()
        payload.forEachIndexed { i, v -> buf[i + 1] = v.toByte() }
        return buf
    }

    fun versionRequest(): ByteArray = report(READ, CMD_VERSION, END)
    /** Bulk PEQ read (getCurrentSlot): the reply is band 0's packet, the slot byte at buf[36]. */
    fun slotRequest(): ByteArray = report(READ, CMD_PEQ, END)
    fun bandRequest(index: Int): ByteArray = report(READ, CMD_PEQ, 0x00, 0x00, index, END)
    fun preampRequest(): ByteArray = report(READ, CMD_GLOBAL_GAIN, END)

    private fun u8(buf: ByteArray, i: Int): Int = buf[i].toInt() and 0xFF
    private fun u16(buf: ByteArray, i: Int): Int = u8(buf, i) or (u8(buf, i + 1) shl 8)

    /** True for an input report 0x4B answering [cmd] (devicePEQ matches on d[1]). */
    fun isReply(buf: ByteArray, cmd: Int): Boolean =
        buf.size >= 3 && u8(buf, 0) == REPORT_ID && u8(buf, 2) == cmd

    /** Band reply for [index]; the bulk slot read answers with band 0 too. */
    fun isBandReply(buf: ByteArray, index: Int): Boolean =
        isReply(buf, CMD_PEQ) && buf.size >= 37 && u8(buf, 5) == index

    /** VERSION reply: ASCII at d[3..5], e.g. "0.2". */
    fun parseVersion(buf: ByteArray): String = String(buf.copyOfRange(4, 7), Charsets.US_ASCII).trimEnd('\u0000')

    fun parseSlot(buf: ByteArray): Int = u8(buf, 36)

    /** Global gain reply: int8 dB at d[4]. */
    fun parsePreamp(buf: ByteArray): Int = buf[5].toInt()

    /** Raw registers of one band, as the device stores them. [gain256] is signed. */
    data class Registers(val index: Int, val freq: Int, val q256: Int, val gain256: Int, val typeCode: Int)

    /** A decoded band reply: registers plus the devicePEQ-decompensated (realised) values. */
    data class DeviceBand(
        val registers: Registers,
        val slotByte: Int,
        val biquad: ByteArray,
        val enabled: Boolean,
        val type: FilterType,
        val freqHz: Double,
        val gainDb: Double,
        val q: Double,
    )

    /** parse_filter / parseFilterPacket. */
    fun parseBand(buf: ByteArray): DeviceBand {
        require(buf.size >= 37) { "band reply too short: ${buf.size}" }
        val index = u8(buf, 5)
        val freqRaw = u16(buf, 28)
        val qRaw = u16(buf, 30)
        var gainRaw = u16(buf, 32)
        if (gainRaw > 32767) gainRaw -= 65536
        val typeCode = u8(buf, 34)
        val type = filterType(typeCode)
        val gain = jsRound(gainRaw / 256.0 * 100) / 100
        val qStored = jsRound(qRaw / 256.0 * 100) / 100
        val freq = if (freqRaw > 0) decompensateFreq(freqRaw) else freqRaw.toDouble()
        val q = if (qStored > 0) decompensateQ(qStored, freqRaw, typeCode) else qStored
        val uninit = freqRaw == 0xFFFF && qRaw == 0xFFFF && gainRaw == -1
        val disabled = uninit || freqRaw == 0 || freqRaw == 0xFFFF || q == 0.0
        return DeviceBand(
            registers = Registers(index, freqRaw, qRaw, gainRaw, typeCode),
            slotByte = u8(buf, 36),
            biquad = buf.copyOfRange(8, 28),
            enabled = !disabled,
            type = type,
            freqHz = freq,
            gainDb = gain,
            q = q,
        )
    }

    // ---- compensation (SchemeNo11) -----------------------------------------------------------------

    /** Realised / requested Q at the frequency actually written ('cosNyquist', designFs 96 000). */
    fun qRatio(freqSent: Double, typeCode: Int): Double {
        if (!qCompensated(typeCode) || !(freqSent > 0)) return 1.0
        val x = minOf(maxOf(freqSent / DESIGN_FS, 0.0), 0.5 - 1e-9)
        return cos(Math.PI * x)
    }

    /** User frequency -> the frequency to write (unrounded; the biquad uses it as is). */
    fun compensateFreq(freqHz: Double): Double {
        if (!(freqHz > 0)) return freqHz
        return minOf(20_000.0, maxOf(20.0, freqHz / FREQ_FACTOR))
    }

    /** User Q -> the Q to write, keyed off the compensated frequency. */
    fun compensateQ(q: Double, freqSent: Double, typeCode: Int): Double {
        if (!(q > 0)) return q
        val ratio = qRatio(freqSent, typeCode)
        if (ratio == 1.0) return q
        return minOf(10.0, maxOf(0.1, q / ratio))
    }

    fun decompensateFreq(freqRaw: Int): Double = freqRaw * FREQ_FACTOR

    fun decompensateQ(qStored: Double, freqRaw: Int, typeCode: Int): Double = qStored * qRatio(freqRaw.toDouble(), typeCode)

    // ---- writes ------------------------------------------------------------------------------------

    /**
     * One band as it goes on the wire: [freq] and [q] are ALREADY compensated (or raw register values);
     * the biquad uses them unrounded, the metadata fields get `trunc(freq)`, `round(q * 256)`, `round(gain * 256)`.
     */
    data class BandWrite(val index: Int, val freq: Double, val gainDb: Double, val q: Double, val typeCode: Int) {
        /** The registers the device will hold after this write (for the read-back comparison). */
        fun registers(): Registers {
            var g = toInt32(jsRound(gainDb * 256)) and 0xFFFF
            if (g > 32767) g -= 65536
            return Registers(index, toInt32(freq) and 0xFFFF, toInt32(jsRound(q * 256)) and 0xFFFF, g, typeCode)
        }
    }

    /** Factory flat band of [index]: raw registers, no compensation - byte-identical to the device's own. */
    fun factoryFlat(index: Int): BandWrite = BandWrite(index, FACTORY_FREQS[index].toDouble(), 0.0, FACTORY_Q, TYPE_PK)

    /** A user band -> its wire values, through the SchemeNo11 compensation. */
    fun bandWrite(index: Int, band: Band): BandWrite {
        val code = typeCode(band.type)
        val f = compensateFreq(band.freqHz)
        return BandWrite(index, f, band.gainDb, compensateQ(band.q, f, code), code)
    }

    /** computeIIRFilter: 5 x int32 LE, Q30 - b0, b1, b2, -a1, -a2 normalised by a0, RBJ at 96 kHz. */
    fun computeIir(freq: Double, gainDb: Double, q: Double, typeCode: Int): ByteArray {
        val a = sqrt(10.0.pow(gainDb / 20))
        val w0 = freq * 6.283185307179586 / 96000
        val s = sin(w0)
        val c = cos(w0)
        val alpha = s / (2 * q)
        val b0: Double
        val b1: Double
        val b2: Double
        val a0: Double
        val a1: Double
        val a2: Double
        if (typeCode == TYPE_LSQ || typeCode == TYPE_HSQ) {
            val sa = (s / 2) * sqrt((a + 1 / a) * (1 / q - 1) + 2)
            val k = 2 * sqrt(a) * sa
            if (typeCode == TYPE_LSQ) {
                b0 = a * ((a + 1) - (a - 1) * c + k); b1 = 2 * a * ((a - 1) - (a + 1) * c)
                b2 = a * ((a + 1) - (a - 1) * c - k); a0 = (a + 1) + (a - 1) * c + k
                a1 = -2 * ((a - 1) + (a + 1) * c); a2 = (a + 1) + (a - 1) * c - k
            } else {
                b0 = a * ((a + 1) + (a - 1) * c + k); b1 = -2 * a * ((a - 1) + (a + 1) * c)
                b2 = a * ((a + 1) + (a - 1) * c - k); a0 = (a + 1) - (a - 1) * c + k
                a1 = 2 * ((a - 1) - (a + 1) * c); a2 = (a + 1) - (a - 1) * c - k
            }
        } else { // PK; devicePEQ uses the PK formula for LP/HP as well
            b0 = 1 + alpha * a; b1 = -2 * c; b2 = 1 - alpha * a
            a0 = 1 + alpha / a; a1 = -2 * c; a2 = 1 - alpha / a
        }
        val scale = 1073741824.0
        val values = doubleArrayOf(
            jsRound(b0 / a0 * scale), jsRound(b1 / a0 * scale), jsRound(b2 / a0 * scale),
            -jsRound(a1 / a0 * scale), -jsRound(a2 / a0 * scale),
        )
        val out = ByteArray(20)
        values.forEachIndexed { i, v ->
            val n = toInt32(v)
            out[i * 4] = (n and 0xFF).toByte()
            out[i * 4 + 1] = ((n shr 8) and 0xFF).toByte()
            out[i * 4 + 2] = ((n shr 16) and 0xFF).toByte()
            out[i * 4 + 3] = ((n shr 24) and 0xFF).toByte()
        }
        return out
    }

    /** Filter write `01 09 18 00 i 00 00` + biquad + freq + q + gain + type + 00 + slot + 00. */
    fun bandWriteReport(w: BandWrite, slot: Int): ByteArray {
        val biquad = computeIir(w.freq, w.gainDb, w.q, w.typeCode)
        val f = toInt32(w.freq) and 0xFFFF
        val q = toInt32(jsRound(w.q * 256)) and 0xFFFF
        val g = toInt32(jsRound(w.gainDb * 256)) and 0xFFFF
        val payload = IntArray(7 + 20 + 10)
        intArrayOf(WRITE, CMD_PEQ, 0x18, 0x00, w.index, 0x00, 0x00).copyInto(payload)
        biquad.forEachIndexed { i, b -> payload[7 + i] = b.toInt() and 0xFF }
        intArrayOf(f and 0xFF, f shr 8, q and 0xFF, q shr 8, g and 0xFF, g shr 8, w.typeCode, 0x00, slot, END)
            .copyInto(payload, 27)
        return report(*payload)
    }

    /** Global gain write `01 03 02 00 g`, g = round(dB) as int8. */
    fun preampWriteReport(db: Double): ByteArray = report(WRITE, CMD_GLOBAL_GAIN, 0x02, 0x00, toInt32(jsRound(db)) and 0xFF)

    /** devicePEQ commit after the filter writes - PERSISTS to flash. */
    val COMMIT_SEQUENCE: List<ByteArray> = listOf(
        report(WRITE, 0x05, END),
        report(WRITE, 0x17, END),
        report(WRITE, 0x0A, 0x04, 0x00, 0x00, 0xFF, 0xFF, END), // TEMP_WRITE
        report(WRITE, 0x01, 0x01, END),                         // FLASH_EQ enable + persist
    )
    private val COMMIT_DELAYS_MS = longArrayOf(20, 20, 50, 0)

    /** One report and the pause after it. */
    class Step(val report: ByteArray, val delayAfterMs: Long)

    /**
     * `write_state(...)` in research/protocol/write_test.py: every band with 20 ms after it, 100 ms more,
     * the preamp and 50 ms, then (when [commit]) the commit sequence with devicePEQ's delays; 200 ms at the end.
     */
    fun writeSequence(bands: List<BandWrite>, preampDb: Double, slot: Int, commit: Boolean): List<Step> {
        val steps = ArrayList<Step>()
        bands.forEachIndexed { i, b -> steps += Step(bandWriteReport(b, slot), if (i == bands.lastIndex) 120 else 20) }
        if (!commit) {
            steps += Step(preampWriteReport(preampDb), 50 + 200)
            return steps
        }
        steps += Step(preampWriteReport(preampDb), 50)
        COMMIT_SEQUENCE.forEachIndexed { i, r ->
            steps += Step(r, COMMIT_DELAYS_MS[i] + if (i == COMMIT_SEQUENCE.lastIndex) 200 else 0)
        }
        return steps
    }
}
