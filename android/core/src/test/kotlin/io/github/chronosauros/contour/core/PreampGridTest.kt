package io.github.chronosauros.contour.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The editor keeps what the DAC cannot hold off the screen: a whole-dB preamp on the SnowSky DACs and the gain of an emulated HIGH SHELF on the Micro and the Echo-A. */
class PreampGridTest {
    @Test fun `a manual preamp moves in whole dB exactly where the DAC keeps whole dB, and 0,1 dB elsewhere`() {
        for (t in listOf(DeviceTarget.MELODY, DeviceTarget.TINY_A)) {
            assertEquals(1.0, t.preampStep, t.caps.name)
            assertEquals(-4.0, t.heldPreamp(-3.5), t.caps.name) // the number the DAC holds after a send (Melody and Tiny A phone runs 25.10.2026: -3.5 written as -4)
            assertEquals(-3.0, t.heldPreamp(-3.0), t.caps.name)
            assertEquals(2.0, t.heldPreamp(2.9), t.caps.name)
        }
        for (t in listOf(DeviceTarget.KA15, DeviceTarget.K13)) {
            assertEquals(0.1, t.preampStep, t.caps.name)
            assertEquals(-3.5, t.heldPreamp(-3.5), t.caps.name)
        }
    }

    @Test fun `an emulated HIGH SHELF keeps its gain on the preamp grid, so the level is exact`() {
        assertEquals(1.0, DeviceTarget.MICRO.highShelfStepDb)
        for (t in listOf(DeviceTarget.MAX, DeviceTarget.TRN, DeviceTarget.KA15, DeviceTarget.K13, DeviceTarget.MELODY, DeviceTarget.TINY_A)) assertNull(t.highShelfStepDb, t.caps.name) // native shelves
        assertEquals(1.0, DeviceTarget.ECHO_A.highShelfStepDb) // the Echo-A writes in a release since 25.10.2026, with the same whole-dB rule
        assertNull(DeviceTarget.forEcho(DeviceTarget.ECHO_A.echo!!.copy(hardwareVerified = false)).takeIf { !Proven.on(false) }?.highShelfStepDb) // read only: nothing is sent, nothing to keep on a grid
        // Echo-A phone run 25.10.2026: HS -1.5 dB with a -3 dB preamp wrote register -5 (floor(-4.5)), 0.5 dB low; on the whole-dB grid the register is exactly preamp + shelf gain.
        fun register(g: Double) = ProtocolMicro.devicePreamp(listOf(Band("hs", FilterType.HIGH_SHELF, 8000.0, g, 0.7)), -3.0).toDouble()
        assertEquals(-5.0, register(-1.5))
        for (g in listOf(-2.0, -1.0, 0.0, -4.0)) assertEquals(-3.0 + g, register(g), "HS $g dB")
    }
}
