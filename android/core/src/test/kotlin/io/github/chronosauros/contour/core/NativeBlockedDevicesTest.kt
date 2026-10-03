package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.BlockedUsbCatalog
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Identity fixtures are pinned source/catalog observations, not new hardware qualification. */
class NativeBlockedDevicesTest {
    @Test fun `captured Qudelix identity cannot fall through shared FiiO VID`() {
        assertTrue(BlockedUsbCatalog.candidate(0x0A12, 0x4005, "Qudelix-5K USB DAC 48KHz"))
        assertTrue(assertNotNull(BlockedUsbCatalog.reason(0x0A12, 0x4005, "Qudelix-5K USB DAC 48KHz")).contains("Qudelix"))
    }

    @Test fun `source Topping identity cannot fall through shared Fosi VID`() {
        assertTrue(BlockedUsbCatalog.candidate(0x152A, 0x8750, "DX1II"))
        assertTrue(assertNotNull(BlockedUsbCatalog.reason(0x152A, 0x8750, "DX1II")).contains("Topping"))
    }

    @Test fun `captured Conexant identities cannot fall through WalkPlay`() {
        assertTrue(assertNotNull(BlockedUsbCatalog.reason(0x35D8, 0x1496, "Moondrop FreeDSP")).contains("Conexant"))
        assertTrue(assertNotNull(BlockedUsbCatalog.reason(0x35D8, 0x149B, "Moondrop ECHO-B")).contains("Conexant"))
    }

    @Test fun `known exact pairs remain blocked without readable product descriptor`() {
        for ((vid, pid) in listOf(0x0A12 to 0x4005, 0x152A to 0x8750, 0x35D8 to 0x1496,
            0x35D8 to 0x149B, 0x152A to 0x88FA, 0x1A86 to 0x55D3)) {
            assertNotNull(BlockedUsbCatalog.reason(vid, pid, null))
        }
    }

    @Test fun `source USB serial identities do not acquire a HID writer`() {
        assertTrue(assertNotNull(BlockedUsbCatalog.reason(0x1A86, 0x55D3, "FiiO Audio DSP")).contains("serial"))
        assertTrue(assertNotNull(BlockedUsbCatalog.reason(0x152A, 0x88FA, "Element IV")).contains("serial"))
    }

    @Test fun `unproven exact named exclusions are explicitly informational`() {
        // Zero here is a synthetic unknown PID, never claimed as an observed device PID.
        for ((vid, name) in listOf(0x35D8 to "FreeDSP", 0x35D8 to "ECHO-B",
            0x2972 to "FIIO DM15 R2R", 0x0A12 to "FIIO DM15 R2R", 0x31B2 to "Space Gaming IEM")) {
            assertTrue(BlockedUsbCatalog.candidate(vid, 0, name))
            assertTrue(assertNotNull(BlockedUsbCatalog.reason(vid, 0, name)).contains("Informational"))
        }
    }

    @Test fun `legitimate devices sharing vendor IDs are not denied`() {
        for ((vid, pid, name) in listOf(
            Triple(0x2972, 0x0128, "FIIO QX13"),
            Triple(0x0A12, 0x0128, "FIIO QX13"),
            Triple(0x2972, 0x0104, "FIIO KA15"),
            Triple(0x152A, 0x88DB, "Fosi Audio DS3"),
            Triple(0x35D8, 0x0123, "WalkPlay device"),
            Triple(0x35D8, 0, "FreeDSP Pro"),
            Triple(0x35D8, 0, "FreeDSP Mini"),
            Triple(0x31B2, 0x0113, "Chu2 DSP"),
        )) {
            assertNull(BlockedUsbCatalog.reason(vid, pid, name), name)
            assertFalse(BlockedUsbCatalog.candidate(vid, pid, name), name)
        }
    }

    @Test fun `unknown devices and fuzzy names remain untouched`() {
        for (name in listOf<String?>(null, "", "freedsp", " FreeDSP", "FreeDSP ", "MOONDROP FreeDSP",
            "Moondrop FreeDSP", "FIIO DM15", "FIIO DM15 R2R extra", "fiio dm15 r2r", "Qudelix-5K")) {
            assertNull(BlockedUsbCatalog.reason(0x35D8, 0, name), name)
            assertFalse(BlockedUsbCatalog.candidate(0x35D8, 0, name), name)
        }
        assertNull(BlockedUsbCatalog.reason(0xFFFF, 0xFFFF, "FreeDSP"))
        assertNull(BlockedUsbCatalog.reason(0x2972, 0, "fiio dm15 r2r"))
        assertNull(BlockedUsbCatalog.reason(-1, 0x4005, "Qudelix-5K USB DAC 48KHz"))
        assertNull(BlockedUsbCatalog.reason(0x0A12, 0x10000, "FIIO DM15 R2R"))
    }
    @Test fun `actual route exclusion precedes native and WalkPlay regardless of product descriptor`() {
        for ((vid, pid) in listOf(0x0A12 to 0x4005, 0x152A to 0x8750, 0x35D8 to 0x1496,
            0x35D8 to 0x149B, 0x1A86 to 0x55D3, 0x152A to 0x88FA, 0x35D8 to 0x011C)) {
            for (name in listOf(null, "FIIO KA15", "Fosi Audio DS3", "FreeDSP Mini", "MOONDROP Marigold")) {
                assertNotNull(DeviceTarget.blockedReason(vid, pid, name))
                assertNull(DeviceTarget.find(vid, pid, true, name)); assertNull(DeviceTarget.find(vid, pid, false, name))
                assertTrue(DeviceTarget.candidate(vid, pid, name, true)) // diagnostic, NOT authorization
            }
        }
    }
    @Test fun `actual route exclusion includes named source ambiguities before any fallback`() {
        for ((vid, name) in listOf(0x35D8 to "Old Fashioned", 0x35D8 to "MOONDROP Marigold",
            0x35D8 to "FreeDSP", 0x35D8 to "ECHO-B", 0x2972 to "FIIO DM15 R2R", 0x31B2 to "Space Gaming IEM")) {
            assertNotNull(DeviceTarget.blockedReason(vid, 0xFFFF, name))
            assertNull(DeviceTarget.find(vid, 0xFFFF, true, name))
            assertTrue(DeviceTarget.candidate(vid, 0xFFFF, name, true))
        }
    }
    @Test fun `same VID legitimate native routes and Micro remain functional`() {
        assertNotNull(DeviceTarget.find(0x0A12, 0x0128, true, "FIIO QX13")?.fiio)
        assertNotNull(DeviceTarget.find(0x2972, 0x0104, true, "FIIO KA15")?.fiio)
        assertTrue(DeviceTarget.find(0x152A, 0x88DB, true, "Fosi Audio DS3")!!.fosi)
        assertNotNull(DeviceTarget.find(0x35D8, 0xFFFF, true, "FreeDSP Mini")?.moondrop)
        val c = DeviceTarget.MICRO.caps
        assertTrue(DeviceTarget.find(c.vendorId, c.productId, true, c.name) == DeviceTarget.MICRO)
        assertTrue(DeviceTarget.find(c.vendorId, c.productId, false, null) == DeviceTarget.MICRO)
    }
    @Test fun `stable gate admits only Micro and never diagnostic native routes`() {
        for ((vid, pid, name) in listOf(Triple(0x2972, 0x0104, "FIIO KA15"), Triple(0x152A, 0x88DB, "Fosi Audio DS3"),
            Triple(0x31B2, 0x0111, "TANCHJIM-ONE DSP"), Triple(0x35D8, 0xFFFF, "FreeDSP Mini"))) {
            assertNull(DeviceTarget.find(vid, pid, false, name)); assertFalse(DeviceTarget.candidate(vid, pid, name, false))
        }
    }
}
