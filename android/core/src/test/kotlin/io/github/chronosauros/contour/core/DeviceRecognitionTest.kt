package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.FiioCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The stable app recognises exactly four USB identities; every other one is plain unrecognised (NO DAC). */
class DeviceRecognitionTest {
    private val k13 = 0x2972 to FiioCatalog.K13_R2R_PRODUCT_ID
    private val expected = mapOf(
        (0x3302 to 0xC20F) to DeviceTarget.MICRO, // CrinEar Protocol Micro
        (0x3302 to 0x43CC) to DeviceTarget.MAX,   // CrinEar Protocol Max
        (0x2972 to 0x0104) to DeviceTarget.KA15,  // FiiO KA15
        k13 to DeviceTarget.K13,                  // FiiO K13 R2R (product ID: FiioCatalog.K13_R2R_PRODUCT_ID, measured on hardware)
    )

    @Test fun `stable build recognises exactly Protocol Micro Protocol Max FiiO KA15 and FiiO K13 R2R`() {
        expected.forEach { (id, target) -> assertEquals(target, DeviceTarget.find(id.first, id.second)) }
        // Every PID of both vendors, both byte orders of the three IDs, and the advBeta-only identities.
        val vendors = listOf(0x3302, 0x2972, 0x0A12, 0x0BDA, 0x31B2, 0x152A, 0x35D8, 0x262A, 0x0000, 0xFFFF)
        val found = HashSet<Pair<Int, Int>>()
        for (vid in vendors) for (pid in 0..0xFFFF) if (DeviceTarget.find(vid, pid) != null) found += vid to pid
        for (pid in listOf(0xC20F, 0x43CC, 0x0104, 0x0F2C, 0xCC43, 0x0401, k13.second)) for (vid in 0..0xFFFF)
            if (DeviceTarget.find(vid, pid) != null) found += vid to pid
        assertEquals(expected.keys, found)
        // Near misses: TRN Black Pearl, other FiiO captures, KT Micro, Fosi DS3, Qudelix, swapped bytes.
        for ((vid, pid) in listOf(0x3302 to 0x43E8, 0x2972 to 0x0128, 0x2972 to 0x0126, 0x2972 to 0x0105,
                0x31B2 to 0x0111, 0x152A to 0x88DB, 0x0A12 to 0x4005, 0x0233 to 0x0F2C, 0x7229 to 0x0401).filter { it != k13 })
            assertNull(DeviceTarget.find(vid, pid), "%04X:%04X".format(vid, pid))
        // Structure behind "exactly": two WalkPlay entries and two native FiiO rules.
        assertEquals(listOf(DeviceProtocol.MICRO, DeviceProtocol.MAX), DeviceProtocol.entries.toList())
        assertEquals(listOf("FIIO KA15", "FIIO K13 R2R"), FiioCatalog.rules.map { it.productName })
        assertEquals(10610 to 260, FiioCatalog.KA15.capture!!.let { it.vendorId to it.productId })
        assertNull(FiioCatalog.K13.capture) // no capture: its identity is the product ID constant
    }

    /** The USB attach prompt (res/xml/device_filter.xml) lists exactly the same identities, in decimal. */
    @Test fun `device filter lists exactly the recognised identities`() {
        val xml = java.io.File("../app/src/main/res/xml/device_filter.xml").readText()
        val listed = Regex("""<usb-device\s+vendor-id="(\d+)"\s+product-id="(\d+)"""").findAll(xml)
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toSet()
        assertEquals(expected.keys, listed)
    }
}
