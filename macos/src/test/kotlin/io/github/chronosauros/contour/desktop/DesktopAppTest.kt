package io.github.chronosauros.contour.desktop

import io.github.chronosauros.contour.core.*
import io.github.chronosauros.contour.desktop.model.DesktopAppModel
import io.github.chronosauros.contour.desktop.model.DesktopProfileStore
import io.github.chronosauros.contour.desktop.usb.MacDeviceController
import org.hid4java.HidManager
import org.hid4java.HidServicesSpecification
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class DesktopAppTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `store seeds default profiles when empty`() {
        val store = DesktopProfileStore(tempDir)
        val profiles = store.loadAll()
        assertEquals(4, profiles.size)
        assertTrue(profiles.any { it.name == "BASS BOOST" })
        assertTrue(profiles.any { it.name == "SOFTER VOCALS" })
        assertTrue(profiles.any { it.name == "TREBLE BOOST" })
        assertTrue(profiles.any { it.name == "FLAT / NEUTRAL" })
    }

    @Test
    fun `store saves and reloads customized profile`() {
        val store = DesktopProfileStore(tempDir)
        store.loadAll()

        val now = System.currentTimeMillis()
        val custom = Profile(
            id = "custom-1",
            name = "MY IEM TUNE",
            subtitle = "Custom tuning",
            bands = listOf(
                Band("b1", FilterType.LOW_SHELF, 80.0, 3.5, 0.71, true),
                Band("b2", FilterType.PEAK, 3000.0, -2.0, 1.4, true)
            ),
            preampDb = -3.5,
            createdAt = now,
            updatedAt = now
        )
        store.saveProfile(custom)

        val reloaded = store.loadProfile("custom-1")
        assertNotNull(reloaded)
        assertEquals("MY IEM TUNE", reloaded.name)
        assertEquals(2, reloaded.bands.size)
        assertEquals(3.5, reloaded.bands[0].gainDb)
        assertEquals(-2.0, reloaded.bands[1].gainDb)
    }

    @Test
    fun `model manipulates bands and computes auto preamp correctly`() {
        val store = DesktopProfileStore(tempDir)
        val spec = HidServicesSpecification().apply { isAutoStart = false }
        val services = HidManager.getHidServices(spec)
        val controller = MacDeviceController(services)
        val model = DesktopAppModel(store, controller)

        val profile = model.createProfile("TEST PROFILE")
        assertEquals("TEST PROFILE", model.currentProfile?.name)

        // remove a band to allow adding one below maxBands
        model.removeBand(0)
        val countAfterRemoval = model.currentProfile?.bands?.size ?: 0

        // add band
        model.addBand(FilterType.PEAK, 4500.0, 3.0, 1.2)
        assertEquals(countAfterRemoval + 1, model.currentProfile?.bands?.size)

        // modify band
        val lastIdx = model.currentProfile!!.bands.size - 1
        val lastBand = model.currentProfile!!.bands[lastIdx]
        model.updateBand(lastIdx, lastBand.copy(gainDb = 4.5))
        assertEquals(4.5, model.currentProfile!!.bands[lastIdx].gainDb)

        // auto preamp compensates for positive peak gain to prevent clipping
        val autoPreamp = model.calculateAutoPreamp()
        assertTrue(autoPreamp <= 0.0, "Auto preamp must be negative or zero to prevent clipping")

        // roundtrip APO text import and export
        val apoText = model.exportApoText(model.currentProfile!!)
        assertTrue(apoText.contains("Filter"))
        val imported = model.importApoText(apoText, "IMPORTED COPY")
        assertNotNull(imported)
        assertEquals(model.currentProfile!!.bands.size, imported.bands.size)

        controller.close()
        services.shutdown()
    }

    @Test
    fun `device targets recognise protocol micro and supported DACs`() {
        // CrinEar Protocol Micro (VID 0x3302, PID 0xC20F)
        val micro = DeviceTarget.findDevice(0x3302, 0xc20f, "Protocol Micro")
        assertNotNull(micro)
        assertEquals("CrinEar Protocol Micro", micro.caps.name)
        assertEquals(8, micro.caps.bands)
        assertEquals(DeviceTarget.Family.WALKPLAY, micro.family)

        // FiiO KA15 (VID 0x2972, PID 0x0104)
        val ka15 = DeviceTarget.findDevice(0x2972, 0x0104, "FiiO KA15")
        assertNotNull(ka15)
        assertEquals(DeviceTarget.Family.FIIO, ka15.family)
        assertEquals(10, ka15.caps.bands)

        // TRN Black Pearl (VID 0x3302, PID 0x43E8)
        val trn = DeviceTarget.findDevice(0x3302, 0x43e8, "TE-C")
        assertNotNull(trn)
        assertEquals("TRN Black Pearl", trn.caps.name)
    }
}
