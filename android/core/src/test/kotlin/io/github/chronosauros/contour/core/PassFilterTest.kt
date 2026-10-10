package io.github.chronosauros.contour.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** LOW PASS / HIGH PASS on the WalkPlay scheme 16 DACs (Protocol Max in advanced builds, TRN Black Pearl): vendor tool codes 4 and 5. */
class PassFilterTest {
    private val points = listOf(20.0 to 0.707, 100.0 to 0.707, 1000.0 to 2.0, 15000.0 to 0.5)
    private val passTargets = listOf(DeviceProtocol.MAX_PASS, DeviceProtocol.TRN)
    private fun hp(gain: Double = 3.0) = Band("hp", FilterType.HIGH_PASS, 100.0, gain, 0.707)
    private fun reply(w: WalkPlay.BandWrite, slot: Int = 0): ByteArray =
        WalkPlay.bandWriteReport(w, slot).also { it[1] = WalkPlay.READ.toByte(); it[3] = 0 }

    /** The Q30 encoding of [Dsp.biquad] at 96 kHz as [b0, b1, b2, -a1, -a2] with WalkPlay's rounding. */
    private fun q30(type: FilterType, freq: Double, q: Double): ByteArray {
        val b = Dsp.biquad(Band("x", type, freq, 0.0, q), 96000.0)
        val scaled = doubleArrayOf(b.b0, b.b1, b.b2, -b.a1, -b.a2).map { it * 1073741824.0 }
        val out = ByteArray(20)
        scaled.forEachIndexed { i, v ->
            val n = (if (i < 3) WalkPlay.jsRound(v) else -WalkPlay.jsRound(-v)).toLong().toInt()
            for (k in 0..3) out[i * 4 + k] = ((n shr (8 * k)) and 0xFF).toByte()
        }
        return out
    }

    @Test fun `low pass and high pass biquads equal the Q30 encoding of the RBJ design at 96 kHz`() {
        assertEquals(96000.0, Dsp.FS)
        for ((f, q) in points) {
            assertContentEquals(q30(FilterType.LOW_PASS, f, q), WalkPlay.computeIir(f, 0.0, q, WalkPlay.TYPE_LP), "LP $f Hz Q $q")
            assertContentEquals(q30(FilterType.HIGH_PASS, f, q), WalkPlay.computeIir(f, 0.0, q, WalkPlay.TYPE_HP), "HP $f Hz Q $q")
            // Gain is ignored, and it is not the PK all-pass any more.
            assertContentEquals(WalkPlay.computeIir(f, 0.0, q, WalkPlay.TYPE_LP), WalkPlay.computeIir(f, 6.0, q, WalkPlay.TYPE_LP))
            assertContentEquals(WalkPlay.computeIir(f, 0.0, q, WalkPlay.TYPE_HP), WalkPlay.computeIir(f, -6.0, q, WalkPlay.TYPE_HP))
            val pk = WalkPlay.computeIir(f, 0.0, q, WalkPlay.TYPE_PK)
            assertFalse(pk.contentEquals(WalkPlay.computeIir(f, 0.0, q, WalkPlay.TYPE_LP)))
            assertFalse(pk.contentEquals(WalkPlay.computeIir(f, 0.0, q, WalkPlay.TYPE_HP)))
        }
    }

    @Test fun `a pass band is written with gain 0 and its own type`() {
        for (type in listOf(FilterType.LOW_PASS, FilterType.HIGH_PASS)) {
            val w = WalkPlay.bandWrite(2, Band("x", type, 100.0, 3.5, 0.707))
            assertEquals(0.0, w.gainDb)
            assertEquals(WalkPlay.typeCode(type), w.typeCode)
        }
        assertEquals(3.5, WalkPlay.bandWrite(2, Band("x", FilterType.PEAK, 100.0, 3.5, 0.707)).gainDb)
        assertEquals(WalkPlay.TYPE_LP, 4); assertEquals(WalkPlay.TYPE_HP, 5)
    }

    @Test fun `Protocol Max identity stays stable and advanced adds exactly the two pass types`() {
        val stable = DeviceProtocol.find(0x3302, 0x43CC)!!
        assertTrue(stable.isMax && !stable.isTrn)
        assertEquals(DeviceProtocol.MAX, stable)
        assertEquals(setOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF), stable.caps.types)
        assertEquals(DeviceProtocol.MAX, DeviceProtocol.find(0x3302, 0x43CC, advanced = false, productName = "Protocol Max"))
        for (name in listOf(null, "Protocol Max")) {
            val adv = DeviceProtocol.find(0x3302, 0x43CC, advanced = true, productName = name)!!
            assertTrue(adv.isMax && !adv.isTrn)
            assertEquals(DeviceProtocol.MAX_PASS, adv)
            assertEquals(5, adv.caps.types.size)
            assertEquals(stable.caps.types + FilterType.LOW_PASS + FilterType.HIGH_PASS, adv.caps.types)
            assertEquals(stable.caps.copy(types = adv.caps.types), adv.caps)
        }
        assertNotEquals(DeviceProtocol.MAX, DeviceProtocol.MAX_PASS)
        assertTrue(DeviceProtocol.TRN.isTrn && !DeviceProtocol.TRN.isMax)
        assertTrue(!DeviceProtocol.MICRO.isMax && !DeviceProtocol.MICRO.isTrn)
        assertTrue(DeviceTarget.of(DeviceProtocol.MAX).stable && DeviceTarget.of(DeviceProtocol.MAX_PASS).stable)
        assertFalse(DeviceTarget.of(DeviceProtocol.TRN).stable)
        // The advanced Max keeps the strict Max path: no descriptor, register-only checks, no catalog re-validation.
        assertFalse(DeviceProtocol.MAX_PASS.descriptorRequired)
        assertTrue(DeviceProtocol.TRN.descriptorRequired)
    }

    @Test fun `advanced Max plans peaks and shelves exactly like the stable Max`() {
        val bands = listOf(
            Band("a", FilterType.PEAK, 250.0, -2.0, 1.0), Band("b", FilterType.LOW_SHELF, 120.0, 3.0, 0.7),
            Band("c", FilterType.HIGH_SHELF, 8000.0, -3.0, 0.75),
        )
        assertIs<DevicePlan.Ready>(DeviceProtocol.MAX.plan(bands, -4.0))
        assertEquals(DeviceProtocol.MAX.plan(bands, -4.0), DeviceProtocol.MAX_PASS.plan(bands, -4.0))
        assertEquals(DeviceProtocol.MAX.plan(bands, null), DeviceProtocol.MAX_PASS.plan(bands, null))
        assertEquals(DeviceProtocol.MAX.flatPlan(), DeviceProtocol.MAX_PASS.flatPlan())
    }

    @Test fun `advanced Max and TRN plan, write, read back and import a high pass band`() {
        for (p in passTargets) {
            val plan = assertIs<DevicePlan.Ready>(p.plan(listOf(hp()), null), p.caps.name)
            val w = plan.bands[0]
            assertEquals(WalkPlay.TYPE_HP, w.typeCode)
            assertEquals(0.0, w.gainDb)
            val report = WalkPlay.bandWriteReport(w, 0)
            assertEquals(WalkPlay.TYPE_HP, report[34].toInt())
            assertEquals(0, report[32].toInt()); assertEquals(0, report[33].toInt())
            assertEquals(100, report[28].toInt() and 0xFF)
            assertContentEquals(q30(FilterType.HIGH_PASS, 100.0, 0.707), report.copyOfRange(8, 28))
            // The write sequence goes out untouched by the re-validation of catalog targets.
            val sent = ArrayList<ByteArray>()
            p.executeWrite(plan, 7, {}, { sent += it }, {})
            assertContentEquals(WalkPlay.bandWriteReport(w, 7), sent.first())
            // Read-back and import round trip.
            val reads = plan.bands.map { p.parseBand(reply(it), it.index) }
            assertEquals(WalkPlay.TYPE_HP, reads[0].registers.typeCode)
            assertEquals(FilterType.HIGH_PASS, reads[0].type)
            assertEquals(0, reads[0].registers.gain256)
            assertTrue(p.matches(plan, reads.map { it.registers }, plan.preampDb))
            assertTrue(p.matchesReadback(plan, DeviceProtocol.ReadState("0.2", 7, reads, plan.preampDb), 7))
            val imported = assertIs<DacImport.Ready>(p.importExact(reads, plan.preampDb), p.caps.name)
            assertEquals(1, imported.eq.bands.size)
            assertEquals(FilterType.HIGH_PASS, imported.eq.bands[0].type)
            assertEquals(100.0, imported.eq.bands[0].freqHz)
            assertEquals(0.0, imported.eq.bands[0].gainDb)
        }
    }

    @Test fun `advanced Max and TRN take a low pass band and refuse values outside the ranges`() {
        for (p in passTargets) {
            val lp = Band("lp", FilterType.LOW_PASS, 15000.0, 0.0, 0.5)
            val plan = assertIs<DevicePlan.Ready>(p.plan(listOf(lp), null))
            assertEquals(WalkPlay.TYPE_LP, plan.bands[0].typeCode)
            assertContentEquals(q30(FilterType.LOW_PASS, 15000.0, 0.5), WalkPlay.bandWriteReport(plan.bands[0], 0).copyOfRange(8, 28))
            assertIs<DevicePlan.Rejected>(p.plan(listOf(lp.copy(freqHz = 19.0)), null))
            assertIs<DevicePlan.Rejected>(p.plan(listOf(lp.copy(q = 0.05)), null))
        }
    }

    @Test fun `stable Max still refuses the pass types in plan and in a read-back`() {
        for (type in listOf(FilterType.LOW_PASS, FilterType.HIGH_PASS)) {
            assertIs<DevicePlan.Rejected>(DeviceProtocol.MAX.plan(listOf(Band("p", type, 100.0, 0.0, 0.707)), null))
            assertIs<DevicePlan.Rejected>(DeviceProtocol.MAX.plan(listOf(Band("p", type, 100.0, 3.0, 0.707)), null))
        }
        for (code in listOf(WalkPlay.TYPE_LP, WalkPlay.TYPE_HP)) {
            val w = WalkPlay.BandWrite(0, 100.0, 0.0, 0.707, code)
            assertFailsWith<IllegalArgumentException> { DeviceProtocol.MAX.parseBand(reply(w), 0) }
            assertEquals(code, DeviceProtocol.MAX_PASS.parseBand(reply(w), 0).registers.typeCode)
            val flat = DeviceProtocol.MAX.flatPlan().bands.toMutableList().also { it[0] = w }
                .map { DeviceProtocol.MAX_PASS.parseBand(reply(it), it.index) }
            assertIs<DacImport.Rejected>(DeviceProtocol.MAX.importExact(flat, 0))
        }
        // Unknown native codes stay refused everywhere.
        for (p in listOf(DeviceProtocol.MAX, DeviceProtocol.MAX_PASS, DeviceProtocol.TRN)) {
            val w = WalkPlay.BandWrite(0, 100.0, 0.0, 0.707, 6)
            assertFailsWith<IllegalArgumentException> { p.parseBand(reply(w), 0) }
        }
    }
}
