package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.Band
import io.github.chronosauros.contour.core.DeviceCapabilities
import io.github.chronosauros.contour.core.FilterType
import io.github.chronosauros.contour.core.ImportedEq
import io.github.chronosauros.contour.core.Profile
import kotlin.math.floor

/** The ten band registers of a JM12 read (0x26..0x2F -> d0..d3) and its EQ slot (2 = off, 3 = Custom). */
data class KtSnapshot(val slot: Int, val registers: Map<Int, List<Int>>) {
    fun describe(): String = "EQ slot $slot (${if (slot == KtMicroCodec.SLOT_CUSTOM) "Custom" else "off"})\n" +
        registers.toSortedMap().entries.joinToString("\n") { (r, v) -> "0x%02x: %s".format(r, v.joinToString(" ") { "%02x".format(it) }) }
}

/**
 * JCally JM12 (KT Micro KT02H20, USB 31B2:0111, product "KT02H20 HIFI Audio") on its stock firmware: the KT Micro
 * register protocol on HID report 0x4B, payload `reg 00 00 00 cmd 00 d0 d1 d2 d3` (report ID excluded). Pure, no I/O.
 * Framing and registers: devicePEQ@0617f382e76629792a5933e6933e4b396a756a93, devicePEQ/ktmicroUsbHidHandler.js.
 * Measured on a contributor's JM12 (PC, 20-21.10.2026; research/jm12-pc-2026-10-20): the DAC plays twice the stored frequency;
 * register 0x66 (devicePEQ's pregain on other KT chips) changes nothing audible, so the JM12 has no preamp; RAM reads back
 * before the commit; the commit makes the DAC re-enumerate. Contour reads and writes only 0x24 and 0x26..0x2F and sends the
 * commit: never 0x66, CLEAR (0x43) or report 0x54 (bootloader unlock).
 */
object KtMicroCodec {
    const val VENDOR_ID = 0x31B2
    const val PRODUCT_ID = 0x0111
    /** Kiwi Ears, TANCHJIM and Moondrop dongles share 31B2:0111; only this exact product string is the JM12. */
    const val PRODUCT_NAME = "KT02H20 HIFI Audio"
    const val REPORT_ID = 0x4B
    const val PAYLOAD_BYTES = 10
    const val READ = 0x52
    const val WRITE = 0x57
    const val COMMIT = 0x53
    const val SLOT = 0x24
    const val SLOT_OFF = 2
    const val SLOT_CUSTOM = 3
    const val BANDS = 5
    private const val BAND_BASE = 0x26
    /** Every register a read asks for: the slot, then gain/frequency and Q/type of each band. */
    val REGISTERS: List<Int> = listOf(SLOT) + (BAND_BASE until BAND_BASE + 2 * BANDS)
    /** Factory flat as read on a contributor's JM12: stored 1000/2000/5000/8000/10000 Hz, 0 dB, Q 0.707, PK. */
    private val FACTORY_STORED_HZ = listOf(1000, 2000, 5000, 8000, 10000)
    private const val FACTORY_Q_MILLI = 707

    /** Limits are devicePEQ's for this model (peq5Band12dBFullShelvesNoPregain), not measured on the hardware; shelves are not proven. */
    val CAPS = DeviceCapabilities("JCally JM12", VENDOR_ID, PRODUCT_ID, BANDS, -10.0, 10.0, 0.1, 5.0, 20.0, 20_000.0,
        setOf(FilterType.PEAK), false)

    private fun frame(reg: Int, cmd: Int, data: List<Int> = listOf(0, 0, 0, 0)): ByteArray =
        (listOf(reg, 0, 0, 0, cmd, 0) + data).map { it.toByte() }.toByteArray()
    fun read(reg: Int): ByteArray = frame(reg, READ)
    fun write(reg: Int, data: List<Int>): ByteArray = frame(reg, WRITE, data)
    /** Saves RAM to flash; the JM12 then drops off the bus and enumerates again about a second later. */
    val COMMIT_FRAME: ByteArray get() = frame(0, COMMIT)

    private fun u(b: Byte) = b.toInt() and 255
    /** A reply echoes the register and the command. */
    fun isReply(p: ByteArray, reg: Int, cmd: Int): Boolean = p.size >= PAYLOAD_BYTES && u(p[0]) == reg && u(p[4]) == cmd
    /** d0..d3 of a read reply; the header bytes must be zero. */
    fun parse(p: ByteArray, reg: Int): List<Int> {
        require(isReply(p, reg, READ) && listOf(1, 2, 3, 5).all { p[it] == 0.toByte() }) { "Malformed KT reply for register 0x%02x".format(reg) }
        return (6..9).map { u(p[it]) }
    }
    /** [replies]: register -> read reply payload, for every one of [REGISTERS]. */
    fun snapshot(replies: Map<Int, ByteArray>): KtSnapshot {
        require(replies.keys == REGISTERS.toSet()) { "KT read needs every register once" }
        val slot = parse(replies.getValue(SLOT), SLOT)[0]
        require(slot == SLOT_OFF || slot == SLOT_CUSTOM) { "Unknown KT EQ slot $slot" }
        return KtSnapshot(slot, REGISTERS.drop(1).associateWith { parse(replies.getValue(it), it) })
    }

    private fun le(v: Int) = listOf(v and 255, (v ushr 8) and 255)
    private fun bandRegisters(i: Int, gainTenths: Int, storedHz: Int, qMilli: Int) =
        listOf(BAND_BASE + 2 * i to le(gainTenths) + le(storedHz), BAND_BASE + 2 * i + 1 to le(qMilli) + listOf(0, 0))

    /**
     * The ten band registers [profile] needs. Gain in tenths rounded down (quieter, never louder), stored frequency Hz / 2
     * and Q x 1000 rounded half up (devicePEQ's Math.round), type PK. A disabled band keeps its shape at 0 dB; bands after the
     * profile's are factory flat. The preamp is not written: the JM12 has none.
     */
    fun compile(profile: Profile): Map<Int, List<Int>> {
        require(profile.bands.size <= BANDS) { "${profile.bands.size} bands; ${CAPS.name} accepts $BANDS" }
        return (0 until BANDS).flatMap { i ->
            val b = profile.bands.getOrNull(i) ?: return@flatMap bandRegisters(i, 0, FACTORY_STORED_HZ[i], FACTORY_Q_MILLI)
            require(b.type == FilterType.PEAK) { "Band ${i + 1}: type unsupported" }
            require(b.freqHz.isFinite() && b.freqHz in CAPS.freqMinHz..CAPS.freqMaxHz) { "Band ${i + 1}: freq outside" }
            require(b.gainDb.isFinite() && b.gainDb in CAPS.gainMinDb..CAPS.gainMaxDb) { "Band ${i + 1}: gain outside" }
            require(b.q.isFinite() && b.q in CAPS.qMin..CAPS.qMax) { "Band ${i + 1}: Q outside" }
            val gain = if (b.enabled) floor(b.gainDb * 10 + 1e-6).toInt() else 0
            bandRegisters(i, gain, floor(b.freqHz / 2 + 0.5).toInt(), floor(b.q * 1000 + 0.5).toInt())
        }.toMap()
    }

    /** Enable Custom when the EQ is off, then the ten band writes, in register order. The commit is separate ([COMMIT_FRAME]). */
    fun writes(before: KtSnapshot, expected: Map<Int, List<Int>>): List<ByteArray> =
        (if (before.slot == SLOT_OFF) listOf(write(SLOT, listOf(SLOT_CUSTOM, 0, 0, 0))) else emptyList()) +
            expected.toSortedMap().map { (r, v) -> write(r, v) }

    /** The DAC plays [profile]: EQ on and every band register as [compile] writes it. The preamp is not compared. */
    fun matches(profile: Profile, s: KtSnapshot): Boolean =
        s.slot == SLOT_CUSTOM && runCatching { compile(profile) == s.registers }.getOrDefault(false)

    /** What the DAC plays, as a profile (preamp 0, trailing factory-flat bands left out); refused unless it recompiles exactly. */
    fun importExact(s: KtSnapshot): ImportedEq {
        require(s.slot == SLOT_CUSTOM) { "The JM12's EQ is off" }
        val bands = (0 until BANDS).map { i ->
            val gf = s.registers.getValue(BAND_BASE + 2 * i)
            val qt = s.registers.getValue(BAND_BASE + 2 * i + 1)
            require(qt[2] == 0 && qt[3] == 0) { "DAC band ${i + 1}: native type ${qt[2]} cannot be imported" }
            Band("kt$i", FilterType.PEAK, (gf[2] or (gf[3] shl 8)) * 2.0, (gf[0] or (gf[1] shl 8)).toShort() / 10.0,
                (qt[0] or (qt[1] shl 8)) / 1000.0)
        }
        val factory = { i: Int -> bandRegisters(i, 0, FACTORY_STORED_HZ[i], FACTORY_Q_MILLI).toMap().all { (r, v) -> s.registers[r] == v } }
        val used = maxOf(1, (0 until BANDS).indexOfLast { !factory(it) } + 1)
        val eq = ImportedEq(bands.take(used), 0.0)
        require(runCatching { compile(Profile("dac", "dac", bands = eq.bands, createdAt = 0, updatedAt = 0)) }.getOrNull() == s.registers) {
            "DAC state cannot be imported exactly"
        }
        return eq
    }
}
