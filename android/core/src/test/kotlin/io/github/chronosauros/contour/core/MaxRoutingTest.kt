package io.github.chronosauros.contour.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The advanced beta is a superset of stable 1.3.0: the Protocol Max routes to the same MAX target in every build. */
class MaxRoutingTest {
    private val names = listOf(null, "", "Protocol Max", "CrinEar Protocol Max", "TBD")

    @Test
    fun `43CC resolves to the MAX target in release and advanced builds, with or without a USB name`() {
        for (advanced in listOf(false, true)) for (name in names) {
            val expected = if (advanced) DeviceProtocol.MAX_PASS else DeviceProtocol.MAX
            assertEquals(expected, DeviceProtocol.find(0x3302, 0x43CC, advanced, name), "advanced=$advanced name=$name")
            assertTrue(DeviceProtocol.find(0x3302, 0x43CC, advanced, name)!!.isMax)
            assertEquals(DeviceTarget.of(expected), DeviceTarget.find(0x3302, 0x43CC, advanced, name), "advanced=$advanced name=$name")
            assertTrue(DeviceTarget.find(0x3302, 0x43CC, advanced, name)!!.stable)
            assertTrue(DeviceTarget.candidate(0x3302, 0x43CC, name, advanced))
        }
        val max = DeviceTarget.find(0x3302, 0x43CC, false, null)!!
        assertEquals(DeviceProtocol.MAX, max.walkplay)
        assertTrue(!max.runtimeCandidate && !max.native && !max.supportsAb && max.writeBlocker == null)
        assertEquals(10, DeviceProtocol.MAX.caps.bands)
        assertEquals(16, DeviceProtocol.MAX.scheme)
    }

    @Test
    fun `C20F is MICRO everywhere and 43E8 is TRN only in advanced builds`() {
        for (advanced in listOf(false, true)) {
            assertEquals(DeviceProtocol.MICRO, DeviceProtocol.find(0x3302, 0xC20F, advanced, null))
            assertEquals(DeviceTarget.MICRO, DeviceTarget.find(0x3302, 0xC20F, advanced, null))
        }
        assertNull(DeviceProtocol.find(0x3302, 0x43E8, false, null))
        assertNull(DeviceTarget.find(0x3302, 0x43E8, false, null))
        assertEquals(DeviceProtocol.TRN, DeviceProtocol.find(0x3302, 0x43E8, true, null))
        assertEquals(DeviceProtocol.TRN, DeviceTarget.find(0x3302, 0x43E8, true, null)?.walkplay)
        // The stable-build lookup knows exactly two DACs.
        assertEquals(DeviceProtocol.MAX, DeviceProtocol.find(0x3302, 0x43CC))
        assertEquals(DeviceProtocol.MICRO, DeviceProtocol.find(0x3302, 0xC20F))
        assertNull(DeviceProtocol.find(0x3302, 0x43E8))
    }

    @Test
    fun `MAX is not TRN and does not take the catalog receive path`() {
        assertTrue(DeviceProtocol.MAX != DeviceProtocol.TRN && DeviceProtocol.MAX != DeviceProtocol.MICRO)
        // The Max parser is the stable 1.3.0 one: its own neutral spare slot reads back.
        val off = WalkPlay.bandWriteReport(DeviceProtocol.MAX.flatPlan().bands[3], 0).also {
            it[1] = WalkPlay.READ.toByte(); it[3] = 0
        }
        assertEquals(3, DeviceProtocol.MAX.parseBand(off, 3).registers.index)
    }
}
