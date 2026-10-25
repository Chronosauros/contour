package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.devices.GeneratedDevices
import io.github.chronosauros.contour.core.native.FiioCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The stable app recognises exactly seven USB identities; every other one is plain unrecognised (NO DAC). */
class DeviceRecognitionTest {
    private val k13 = 0x2972 to FiioCatalog.K13_R2R_PRODUCT_ID
    private val melody = 0x2972 to FiioCatalog.MELODY_PRODUCT_ID
    private val tinyA = 0x2972 to FiioCatalog.TINY_A_PRODUCT_ID
    private val expected = mapOf(
        (0x3302 to 0xC20F) to DeviceTarget.MICRO, // CrinEar Protocol Micro
        (0x3302 to 0x43CC) to DeviceTarget.MAX,   // CrinEar Protocol Max
        (0x3302 to 0x43E8) to DeviceTarget.TRN,   // TRN Black Pearl (own write sequence, factory-flat spares)
        (0x2972 to 0x0104) to DeviceTarget.KA15,  // FiiO KA15
        k13 to DeviceTarget.K13,                  // FiiO K13 R2R (product ID: FiioCatalog.K13_R2R_PRODUCT_ID, measured on hardware)
        melody to DeviceTarget.MELODY,            // SnowSky Melody (measured on hardware)
        tinyA to DeviceTarget.TINY_A,             // SnowSky Tiny A (measured on hardware)
    )

    @Test fun `stable build recognises exactly Protocol Micro Protocol Max TRN Black Pearl FiiO KA15 FiiO K13 R2R SnowSky Melody and Tiny A`() {
        expected.forEach { (id, target) -> assertEquals(target, DeviceTarget.find(id.first, id.second)) }
        // Every PID of both vendors, both byte orders of the three IDs, and the advBeta-only identities.
        val vendors = listOf(0x3302, 0x2972, 0x0A12, 0x0BDA, 0x31B2, 0x152A, 0x35D8, 0x262A, 0x0000, 0xFFFF)
        val found = HashSet<Pair<Int, Int>>()
        for (vid in vendors) for (pid in 0..0xFFFF) if (DeviceTarget.find(vid, pid) != null) found += vid to pid
        for (pid in listOf(0xC20F, 0x43CC, 0x43E8, 0x0104, 0x0F2C, 0xCC43, 0x0401, k13.second, melody.second, tinyA.second)) for (vid in 0..0xFFFF)
            if (DeviceTarget.find(vid, pid) != null) found += vid to pid
        assertEquals(expected.keys, found)
        // Near misses: the PID next to the TRN Black Pearl, other FiiO captures, KT Micro, Fosi DS3, Qudelix, swapped bytes.
        for ((vid, pid) in listOf(0x3302 to 0x43E9, 0x2972 to 0x0128, 0x2972 to 0x0105, 0x2972 to 0x0127,
                0x31B2 to 0x0111, 0x152A to 0x88DB, 0x0A12 to 0x4005, 0x0233 to 0x0F2C, 0x7229 to 0x0401).filter { it != k13 && it != melody && it != tinyA })
            assertNull(DeviceTarget.find(vid, pid), "%04X:%04X".format(vid, pid))
        // Structure behind "exactly": three WalkPlay entries and four native FiiO rules.
        assertEquals(listOf(DeviceProtocol.MICRO, DeviceProtocol.MAX, DeviceProtocol.TRN), DeviceProtocol.entries.toList())
        assertEquals(listOf("FIIO KA15", "FIIO K13 R2R", "SNOWSKY Melody", "SNOWSKY TINY A"), FiioCatalog.rules.map { it.productName })
        assertEquals(10610 to 260, FiioCatalog.KA15.capture!!.let { it.vendorId to it.productId })
        assertNull(FiioCatalog.K13.capture) // no capture: its identity is the product ID constant
        assertNull(FiioCatalog.MELODY.capture); assertNull(FiioCatalog.TINY_A.capture)
    }

    /**
     * The USB attach prompt (the generated device_filter.xml) lists the seven 1.5 identities in their old order, then the three 1.6 ones, in decimal;
     * the list is the same as `GeneratedDevices.USB_IDS`, and every one of those identities is a DeviceTarget.
     */
    @Test fun `device filter lists exactly the recognised identities`() {
        val xml = java.io.File(requireNotNull(System.getProperty("contour.usbFilterXml"))).readText()
        val listed = Regex("""<usb-device\s+vendor-id="(\d+)"\s+product-id="(\d+)"""").findAll(xml)
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toList()
        val added = listOf(0x35D8 to 0x011D, 0x31B2 to 0x0111, 0x3302 to 0x12C5) // Dawn Pro 2, Allegro Mini, Echo-A
        assertEquals(expected.keys.toList() + added, listed)
        assertEquals(GeneratedDevices.USB_IDS.map { it.vendorId to it.productId }, listed)
        for ((vid, pid) in added) assertNull(DeviceTarget.find(vid, pid), "%04X:%04X is not in the 1.5 list".format(vid, pid))
    }

    /** `findDevice` knows all ten identities; the Dawn Pro 2 and the Allegro Mini need their exact USB product name, the Echo-A does not. */
    @Test fun `findDevice recognises the ten identities of the device records`() {
        expected.forEach { (id, target) -> assertEquals(target, DeviceTarget.findDevice(id.first, id.second, null)) }
        assertEquals(DeviceTarget.DAWN_PRO_2, DeviceTarget.findDevice(0x35D8, 0x011D, GeneratedDevices.MOONDROP_DAWN_PRO_2.productName))
        assertEquals(DeviceTarget.ALLEGRO_MINI, DeviceTarget.findDevice(0x31B2, 0x0111, GeneratedDevices.KIWI_EARS_ALLEGRO_MINI.productName))
        assertEquals(DeviceTarget.ECHO_A, DeviceTarget.findDevice(0x3302, 0x12C5, null))
        assertNull(DeviceTarget.findDevice(0x35D8, 0x011D, null))
        assertNull(DeviceTarget.findDevice(0x31B2, 0x0111, null))
        // Every identity of the records resolves to its own target, and no two records resolve to one target.
        val targets = GeneratedDevices.USB_IDS.map { u -> DeviceTarget.findDevice(u.vendorId, u.productId, u.productName) ?: error("${u.recordId} is not recognised") }
        assertEquals(10, targets.toSet().size)
    }
}
