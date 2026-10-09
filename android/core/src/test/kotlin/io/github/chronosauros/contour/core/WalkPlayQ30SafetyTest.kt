package io.github.chronosauros.contour.core

import kotlin.test.*

/** Production planner/executor regressions, not hardware claims or a copied DSP oracle. */
class WalkPlayQ30SafetyTest {
    // Catalog name route of the Protocol Max recipe; 3302:43CC itself is the stable 1.3.0 MAX target.
    private val target = requireNotNull(DeviceProtocol.find(0x3302, 0xABCD, true, "Protocol Max"))
    private fun shelf(freq: Double = 1000.0, gain: Double = 10.0, q: Double = 0.75) =
        Band("hs", FilterType.HIGH_SHELF, freq, gain, q)

    @Test fun `new target rejects signed Q30 overflow in original and quantized domains`() {
        // The first edge is unsafe only before quantization; the second only after it.
        for (band in listOf(shelf(), shelf(1000.75, 0.4783625, 0.7481), shelf(6900.75, 3.81820625, 0.7481))) {
            val rejected = assertIs<DevicePlan.Rejected>(target.plan(listOf(band), -10.0))
            assertTrue(rejected.issues.any { "signed Q30" in it }, rejected.issues.toString())
        }
    }

    @Test fun `crafted ready overflow aborts executor before any callback`() {
        val flat = target.flatPlan()
        val bad = flat.copy(bands = flat.bands.dropLast(1) + flat.bands.last().copy(
            freq = 1000.0, gainDb = 10.0, q = 0.75, typeCode = WalkPlay.TYPE_HSQ))
        var sends = 0; var guards = 0; var delays = 0
        val failure = assertFailsWith<IllegalArgumentException> {
            target.executeWrite(bad, 7, { guards++ }, { sends++ }, { delays++ })
        }
        assertTrue(failure.message.orEmpty().contains("signed Q30"))
        assertEquals(0, sends); assertEquals(0, guards); assertEquals(0, delays)
    }

    @Test fun `representable native positive shelves retain actual production bytes`() {
        for (type in listOf(FilterType.LOW_SHELF, FilterType.HIGH_SHELF)) {
            val b = shelf(18000.75, 3.0).copy(type = type)
            val plan = assertIs<DevicePlan.Ready>(target.plan(listOf(b), -4.0))
            val expected = WalkPlay.BandWrite(0, 18000.0, 3.0, 0.75, WalkPlay.typeCode(type))
            val sent = ArrayList<ByteArray>()
            target.executeWrite(plan, 7, {}, { sent += it }, {})
            assertContentEquals(WalkPlay.bandWriteReport(expected, 7), sent.first())
            assertEquals(WalkPlay.typeCode(type), plan.bands.first().typeCode)
        }
        assertIs<DevicePlan.Ready>(target.plan(listOf(shelf().copy(type = FilterType.PEAK)), -10.0))
        assertIs<DevicePlan.Ready>(target.plan(listOf(shelf(gain = 0.0)), 0.0))
        assertIs<DevicePlan.Rejected>(target.plan(listOf(shelf(q = Double.NaN)), -10.0))
    }

    @Test fun `Micro Max and TRN refuse signed Q30 overflow and keep the bytes of bands that fit`() {
        val b = shelf()
        // Micro sends the emulated low shelf, which fits; Max and TRN send the native high shelf, which does not.
        assertIs<DevicePlan.Ready>(DeviceProtocol.MICRO.plan(listOf(b), -10.0))
        for (p in listOf(DeviceProtocol.MAX, DeviceProtocol.TRN)) {
            val rejected = assertIs<DevicePlan.Rejected>(p.plan(listOf(b), -10.0))
            assertTrue("cannot be represented safely" in rejected.issues.single(), rejected.issues.toString())
        }
        // A disabled band that would not fit is never sent, so it does not block the profile.
        assertIs<DevicePlan.Ready>(DeviceProtocol.TRN.plan(listOf(b.copy(enabled = false), b.copy(freqHz = 8000.0, gainDb = 3.0)), -10.0))
        // Representable bands keep the bytes of the legacy encoder.
        val ok = shelf(8000.0, 3.0)
        val trn = assertIs<DevicePlan.Ready>(DeviceProtocol.TRN.plan(listOf(ok), -10.0))
        val sent = ArrayList<ByteArray>()
        DeviceProtocol.TRN.executeWrite(trn, 7, {}, { sent += it }, {})
        assertContentEquals(WalkPlay.bandWriteReport(WalkPlay.bandWrite(0, ok), 7), sent.first())
        // A crafted ready plan that overflows aborts before any callback on every target.
        for (p in listOf(DeviceProtocol.MICRO, DeviceProtocol.MAX, DeviceProtocol.TRN)) {
            val flat = p.flatPlan()
            val bad = flat.copy(bands = flat.bands.dropLast(1) + flat.bands.last().copy(
                freq = 1000.0, gainDb = 10.0, q = 0.75, typeCode = WalkPlay.TYPE_HSQ))
            var calls = 0
            assertFailsWith<IllegalArgumentException> { p.executeWrite(bad, 7, { calls++ }, { calls++ }, { calls++ }) }
            assertEquals(0, calls)
        }
    }
}
