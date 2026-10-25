package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.FiioCatalog
import io.github.chronosauros.contour.core.native.FiioCodec
import io.github.chronosauros.contour.core.native.FiioRegisters
import io.github.chronosauros.contour.core.native.FiioSnapshot
import io.github.chronosauros.contour.core.native.MoondropCodec
import io.github.chronosauros.contour.core.native.WalkPlayProfiles
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The Q conventions and the coefficient rate measured on the UMC202HD rig (24-25.10.2026), applied at the register boundary like the KA15's shelf Q (04.10.2026):
 * the FiiO K13 R2R plays a peak at Q = register / sqrt2, the SnowSky Melody and Tiny A play shelves like that, the TRN does the same for shelves, and the Protocol Micro and
 * the Echo-A run the coefficients Contour sends at 93 750 Hz instead of the 96 000 Hz the vendor tool designs them for. The registers carry the DAC's convention; the editor,
 * the planners and every comparison stay in the intended domain. The TRN's own register and byte checks are in TrnWriteTest, the Micro's vendor-formula parity in ProtocolParityTest.
 */
class QConventionsTest {
    private val root2 = sqrt(2.0)
    private fun codec(name: String) = FiioCodec(FiioCatalog.rules.single { it.productName == name })
    private fun plan(c: FiioCodec, bands: List<Band>, preamp: Double? = 0.0) = c.planOnExplicitSend(bands, preamp, c.config.userSlots.first(), explicitUserAction = true)
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
    private fun profile(bands: List<Band>, preamp: Double? = 0.0) = Profile("p", "p", bands = bands, preampDb = preamp, createdAt = 0, updatedAt = 0)

    // ---- FiiO K13 R2R: peaks ---------------------------------------------------------------------------------------------------------------------------

    @Test fun `K13 R2R writes a peak Q 2 as register 2_83, reads it back as 2_00 and leaves shelves and pass filters alone`() {
        val c = codec("FIIO K13 R2R")
        val pk = plan(c, listOf(Band("p", FilterType.PEAK, 1000.0, 3.0, 2.0))).expected
        assertEquals(283, pk.bands.single().qHundredths)
        val read = c.importExact(FiioSnapshot("FIIO K13 R2R", c.config.userSlots.first(), 1, 0, listOf(FiioRegisters(0, 1000, 30, 283, 0))))
        assertEquals(2.0, read.bands.single().q, 0.002)
        assertEquals(pk.bands, plan(c, read.bands, read.preampDb).expected.bands) // the intended value re-encodes to the same registers
        // shelves and pass filters play the register as Q on the K13 R2R: unchanged
        val others = plan(c, listOf(Band("ls", FilterType.LOW_SHELF, 100.0, 3.0, 2.0), Band("hs", FilterType.HIGH_SHELF, 8000.0, -3.0, 0.71),
            Band("lp", FilterType.LOW_PASS, 8000.0, 0.0, 0.71), Band("hp", FilterType.HIGH_PASS, 100.0, 0.0, 0.71),
            Band("bp", FilterType.BAND_PASS, 1000.0, 0.0, 1.4), Band("ap", FilterType.ALL_PASS, 1000.0, 0.0, 0.71))).expected
        assertEquals(listOf(200, 71, 71, 71, 140, 71), others.bands.map { it.qHundredths })
        // every peak register comes back exactly through the intended domain
        for (q in 10..1000) {
            val native = FiioSnapshot("FIIO K13 R2R", c.config.userSlots.first(), 1, 0, listOf(FiioRegisters(0, 1000, 25, q, 0)))
            assertEquals(native, plan(c, c.importExact(native).bands, 0.0).expected, "register Q $q")
        }
    }

    @Test fun `K13 R2R takes a peak Q up to 7_07 and a shelf Q up to 10, and says which`() {
        val c = codec("FIIO K13 R2R")
        assertEquals(1000, plan(c, listOf(Band("p", FilterType.PEAK, 1000.0, 3.0, 7.07))).expected.bands.single().qHundredths)
        assertTrue(assertFailsWith<IllegalArgumentException> { plan(c, listOf(Band("p", FilterType.PEAK, 1000.0, 3.0, 7.08))) }.message!!.contains("0.07..7.07"))
        assertEquals(1000, plan(c, listOf(Band("s", FilterType.LOW_SHELF, 100.0, 3.0, 10.0))).expected.bands.single().qHundredths)
        val t = DeviceTarget.K13
        assertEquals(0.1 / root2..10.0 / root2, t.qRange(FilterType.PEAK))
        assertEquals(0.1..10.0, t.qRange(FilterType.LOW_SHELF))
        assertEquals(0.1..10.0, t.qRange(FilterType.LOW_PASS))
        assertEquals(emptyList(), t.issues(profile(listOf(Band("p", FilterType.PEAK, 1000.0, 3.0, 7.07)))))
        assertTrue(t.issues(profile(listOf(Band("p", FilterType.PEAK, 1000.0, 3.0, 8.0)))).single().startsWith("Band 1: peak Q 8.00 outside 0.07-7.07"))
        assertEquals(emptyList(), t.issues(profile(listOf(Band("s", FilterType.HIGH_SHELF, 8000.0, 3.0, 8.0)))))
        // the other FiiO devices keep the peak Q as it is
        for (other in listOf(DeviceTarget.KA15, DeviceTarget.MELODY, DeviceTarget.TINY_A))
            assertEquals(1.0, other.caps.peakQScale, other.caps.name)
        assertEquals(root2, t.caps.peakQScale)
        assertEquals(1.0, t.caps.shelfQScale)
        // a peak scale is not combinable with the vendor's peak gain compensation
        assertFailsWith<IllegalArgumentException> { FiioCodec(c.config.copy(peakingGainCompensation = true)) }
        assertFailsWith<IllegalArgumentException> { FiioCodec(c.config.copy(peakQScale = 0.0)) }
    }

    // ---- SnowSky Melody and Tiny A: shelves --------------------------------------------------------------------------------------------------------

    @Test fun `Melody and Tiny A write a shelf Q 0_70 as register 0_99 and read it back, peaks are as set`() {
        for ((name, t) in listOf("SNOWSKY Melody" to DeviceTarget.MELODY, "SNOWSKY TINY A" to DeviceTarget.TINY_A)) {
            val c = codec(name)
            assertEquals(root2, c.config.shelfQScale, name)
            assertEquals(1.0, c.config.peakQScale, name)
            val p = plan(c, listOf(Band("ls", FilterType.LOW_SHELF, 100.0, 5.0, 0.70), Band("hs", FilterType.HIGH_SHELF, 8000.0, -6.0, 1.0),
                Band("pk", FilterType.PEAK, 1000.0, 3.0, 0.70))).expected
            assertEquals(listOf(99, 141, 70), p.bands.map { it.qHundredths }, name)
            val read = c.importExact(FiioSnapshot(c.config.productName, c.config.userSlots.first(), 3, 0, p.bands))
            assertEquals(listOf(0.70, 1.0, 0.70), read.bands.map { it.q }.map { Math.round(it * 100) / 100.0 }, name)
            assertEquals(p.bands, plan(c, read.bands, read.preampDb).expected.bands, name)
            // the editor's intended shelf Q range is the register range / sqrt2, peaks keep 0.1..10
            assertEquals(0.1 / root2..10.0 / root2, t.qRange(FilterType.HIGH_SHELF), name)
            assertEquals(0.1..10.0, t.qRange(FilterType.PEAK), name)
        }
        // the KA15 keeps its own, unchanged: shelves x sqrt2, peaks as set
        val ka = plan(codec("FIIO KA15"), listOf(Band("ls", FilterType.LOW_SHELF, 100.0, 5.0, 0.70), Band("pk", FilterType.PEAK, 1000.0, 3.0, 0.70))).expected
        assertEquals(listOf(99, 70), ka.bands.map { it.qHundredths })
    }

    // ---- SnowSky Melody and Tiny A: the preamp in whole dB ------------------------------------------------------------------------------------------

    @Test fun `Melody and Tiny A keep whole dB of the preamp - the plan rounds down and the read-back verifies - KA15 and K13 keep 0_1 dB`() {
        for ((name, t) in listOf("SNOWSKY Melody" to DeviceTarget.MELODY, "SNOWSKY TINY A" to DeviceTarget.TINY_A)) {
            val c = codec(name)
            assertTrue(c.config.preampWholeDb && t.caps.preampWholeDb, name)
            // -3.5 dB went out as -35 and read back -30 (NOT VERIFIED) in the 22.10.2026 runs; now it is sent as the whole dB the DAC keeps: down, the quieter side (the WalkPlay family's floor(preamp + 1e-6))
            for ((db, tenths) in listOf(-3.5 to -40, -3.0 to -30, -3.4 to -40, -0.1 to -10, 0.0 to 0, 2.9 to 20, 12.0 to 120, -12.0 to -120)) {
                val plan = plan(c, listOf(Band("p", FilterType.PEAK, 1000.0, 1.0, 1.0)), db)
                assertEquals(tenths, plan.expected.preampTenths, "$name $db")
                // what the DAC keeps is exactly what the plan expects, so the read-back compares equal and imports as the whole dB
                assertEquals(tenths / 10.0, c.importExact(plan.expected).preampDb, "$name $db import")
                assertTrue(c.matches(plan.expected, plan.expected), "$name $db")
            }
            assertEquals(emptyList(), t.issues(profile(listOf(Band("p", FilterType.PEAK, 1000.0, 1.0, 1.0)), -3.5)), name)
        }
        // KA15 (register = preamp + 12 dB) and K13 hold 0.1 dB on hardware: unchanged
        assertEquals(85, plan(codec("FIIO KA15"), listOf(Band("p", FilterType.PEAK, 1000.0, 1.0, 1.0)), -3.5).expected.preampTenths)
        assertEquals(-35, plan(codec("FIIO K13 R2R"), listOf(Band("p", FilterType.PEAK, 1000.0, 1.0, 1.0)), -3.5).expected.preampTenths)
        for (t in listOf(DeviceTarget.KA15, DeviceTarget.K13)) assertFalse(t.caps.preampWholeDb, t.caps.name)
    }

    // ---- WalkPlay: coefficient rate ------------------------------------------------------------------------------------------------------------------

    private fun bytes(w: WalkPlay.BandWrite, slot: Int = 0, len: Int = WalkPlay.BAND_LEN) = WalkPlay.bandWriteReport(w, slot, len).copyOfRange(8, 28)

    @Test fun `Micro and Echo-A design their coefficients for 93750 Hz, the Max, TRN and Dawn Pro 2 keep 96000`() {
        val band = Band("p", FilterType.PEAK, 1000.0, 5.0, 2.0)
        val hs = Band("h", FilterType.HIGH_SHELF, 3000.0, -2.0, 0.71)
        val lp = Band("l", FilterType.LOW_PASS, 8000.0, 0.0, 0.71)
        val micro = DeviceProtocol.MICRO.caps
        val echo = WalkPlayProfiles.echoA.caps
        assertEquals(93750.0, micro.coefficientRateHz)
        assertEquals(93750.0, echo.coefficientRateHz)
        assertEquals(96000.0, DeviceProtocol.MAX.caps.coefficientRateHz)
        assertEquals(96000.0, DeviceProtocol.TRN.caps.coefficientRateHz)
        // Micro (HIGH SHELF emulated as a LOW SHELF of the opposite gain, with the preamp moved) and Echo-A through the Micro's planner
        for ((caps, name) in listOf(micro to "Micro", echo to "Echo-A")) {
            val plan = ProtocolMicro.plan(profile(listOf(band, hs, lp)), caps.copy(types = caps.types + FilterType.LOW_PASS)) as DevicePlan.Ready
            assertTrue(plan.bands.take(3).all { it.rateHz == 93750.0 }, name)
            assertEquals(hex(WalkPlay.computeIir(1000.0, 5.0, 2.0, WalkPlay.TYPE_PK, 93750.0)), hex(bytes(plan.bands[0])), "$name PK")
            assertEquals(hex(WalkPlay.computeIir(3000.0, 2.0, 0.71, WalkPlay.TYPE_LSQ, 93750.0)), hex(bytes(plan.bands[1])), "$name emulated HS")
            assertEquals(hex(WalkPlay.computeIir(8000.0, 0.0, 0.71, WalkPlay.TYPE_LP, 93750.0)), hex(bytes(plan.bands[2])), "$name LP")
            assertNotEquals(hex(WalkPlay.computeIir(1000.0, 5.0, 2.0, WalkPlay.TYPE_PK)), hex(bytes(plan.bands[0])), "$name differs from the 96 kHz bytes")
            // the spare slots stay the factory flat, byte for byte (the identity filter of the unit's own 96 kHz bytes)
            assertEquals(hex(bytes(WalkPlay.factoryFlat(3))), hex(bytes(plan.bands[3])), "$name spare slot")
        }
        // Max and TRN: the coefficients as before (96 000 Hz); the TRN's PK is the register as Q
        for ((p, len) in listOf(DeviceProtocol.MAX to WalkPlay.BAND_LEN, DeviceProtocol.TRN to WalkPlay.TRN_BAND_LEN)) {
            val plan = p.plan(profile(listOf(band, hs))) as DevicePlan.Ready
            assertTrue(plan.bands.all { it.rateHz == 96000.0 }, p.name)
            assertEquals(hex(WalkPlay.computeIir(1000.0, 5.0, 2.0, WalkPlay.TYPE_PK)), hex(bytes(plan.bands[0], len = len)), "${p.name} PK")
            // a shelf's coefficients are designed for the intended Q on the TRN as well as on the Max
            assertEquals(hex(WalkPlay.computeIir(3000.0, -2.0, 0.71, WalkPlay.TYPE_HSQ)), hex(bytes(plan.bands[1], len = len)), "${p.name} HS")
        }
        // Dawn Pro 2: MoondropCodec calls computeIir with its default rate
        val dawn = MoondropCodec.bandWrite(0, band)
        assertContentEquals(WalkPlay.computeIir(1000.0, 5.0, 2.0, WalkPlay.TYPE_PK), dawn.coefficients)
        assertEquals(96000.0, WalkPlay.DESIGN_FS)
    }

    @Test fun `the Q30 check of a band follows the rate it is designed for`() {
        // a shelf boost at a high corner is on the edge of signed Q30: the check asks the write's own rate, as the bytes do
        val w96 = WalkPlay.BandWrite(0, 12000.0, 10.0, 0.71, WalkPlay.TYPE_LSQ)
        val w93 = w96.copy(rateHz = 93750.0)
        assertEquals(WalkPlay.q30Violation(12000.0, 10.0, 0.71, WalkPlay.TYPE_LSQ, 96000.0), WalkPlay.q30ViolationQuantized(w96))
        assertEquals(WalkPlay.q30Violation(12000.0, 10.0, 0.71, WalkPlay.TYPE_LSQ, 93750.0), WalkPlay.q30ViolationQuantized(w93))
        assertEquals(WalkPlay.q30Violation(12000.0, 10.0, 0.71, WalkPlay.TYPE_LSQ), WalkPlay.q30ViolationQuantized(w96))
    }

    @Test fun `a WalkPlay record scale reaches the caps and a read-back divides by it before it compares`() {
        // TRN: shelves x sqrt2 in the register, peaks as set; Micro, Max: no scale
        val trn = DeviceProtocol.TRN.caps
        assertEquals(root2, trn.shelfQScale); assertEquals(1.0, trn.peakQScale)
        assertEquals(1.0, DeviceProtocol.MICRO.caps.shelfQScale); assertEquals(1.0, DeviceProtocol.MAX.caps.shelfQScale)
        assertEquals(root2, WalkPlay.bandWrite(0, Band("s", FilterType.LOW_SHELF, 100.0, 3.0, 1.0), trn).qScale)
        assertEquals(1.0, WalkPlay.bandWrite(0, Band("p", FilterType.PEAK, 100.0, 3.0, 1.0), trn).qScale)
        assertEquals(1.0, WalkPlay.bandWrite(0, Band("l", FilterType.LOW_PASS, 100.0, 0.0, 1.0), trn).qScale)
        assertEquals(0.71, WalkPlay.intendedQ(257, FilterType.HIGH_SHELF, trn), 0.003)
        assertEquals(257 / 256.0, WalkPlay.intendedQ(257, FilterType.PEAK, trn))
        // the register Q of a shelf Q 0.71 is round(0.71 x sqrt2 x 256), the bytes are those of Q 0.71
        val w = WalkPlay.bandWrite(0, Band("s", FilterType.HIGH_SHELF, 3000.0, -2.0, 0.71), trn)
        assertEquals(Math.round(0.71 * root2 * 256).toInt(), w.registers().q256)
        assertEquals(hex(WalkPlay.computeIir(3000.0, -2.0, 0.71, WalkPlay.TYPE_HSQ)), hex(bytes(w, len = WalkPlay.TRN_BAND_LEN)))
    }
}
