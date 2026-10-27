package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.DacWrite
import io.github.chronosauros.contour.core.native.FiioReset
import io.github.chronosauros.contour.core.native.SnowSkyMelody
import io.github.chronosauros.contour.core.native.WalkPlayProfiles
import io.github.chronosauros.contour.core.native.WalkPlayReset
import io.github.chronosauros.contour.core.native.WalkPlaySettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gate ([Proven]): one decision, per-feature flags as the only source of what is proven. Everything unproven is off in a release and on in a review build; a flag that turns true
 * opens its feature in a release without any other change.
 */
class ProvenTest {
    private fun <T> release(block: () -> T) = Proven.asChannel(BuildChannel.RELEASE, block)
    private fun <T> review(block: () -> T) = Proven.asChannel(BuildChannel.PERF, block)

    private fun profile(vararg b: Band, preamp: Double = 0.0) = Profile("id", "t", bands = b.toList(), preampDb = preamp, createdAt = 0, updatedAt = 0)
    private val pk = Band("a", FilterType.PEAK, 1000.0, 3.0, 1.0)

    @Test fun `the decision is proven or a review build, and nothing else`() {
        assertTrue(Proven.allows(true, BuildChannel.RELEASE)); assertTrue(Proven.allows(true, BuildChannel.PERF))
        assertFalse(Proven.allows(false, BuildChannel.RELEASE)); assertTrue(Proven.allows(false, BuildChannel.PERF))
        release { assertTrue(Proven.on(true)); assertFalse(Proven.on(false)) }
        review { assertTrue(Proven.on(true)); assertTrue(Proven.on(false)) }
    }

    @Test fun `only perf and debug are review builds, an unknown build type is a release`() {
        assertEquals(BuildChannel.PERF, Proven.channelFor("perf")); assertEquals(BuildChannel.PERF, Proven.channelFor("debug"))
        for (t in listOf("release", "beta", "", "Perf", "staging")) assertEquals(BuildChannel.RELEASE, Proven.channelFor(t), t)
        assertEquals(BuildChannel.RELEASE, Proven.channel) // the default of every process that never sets it (the bench, the tests)
    }

    @Test fun `the Dawn Pro 2, the Allegro Mini and the Echo-A write in every build since the desk and phone runs of 25 October 2026`() {
        for (t in DeviceTarget.FAMILIES) {
            release { assertFalse(t.readOnly, t.caps.name); assertEquals(emptyList(), t.issues(profile(pk)), t.caps.name) }
            review { assertFalse(t.readOnly, t.caps.name); assertEquals(emptyList(), t.issues(profile(pk)), t.caps.name) }
        }
        // the flag is the single source: an Echo-A without it is read only in a release and writes in a review build
        val unproven = WalkPlayProfiles.echoA.copy(hardwareVerified = false)
        release {
            assertTrue(DeviceTarget.forEcho(unproven).readOnly); assertFalse(unproven.writeOn)
            assertTrue(DeviceTarget.forEcho(unproven).issues(profile(pk)).single().endsWith("is read only in Contour")) // a release names no gate
            assertTrue(WalkPlayProfiles.echoA.writeOn)
        }
        review { assertTrue(unproven.writeOn); assertTrue(DeviceTarget.READ_ONLY_ISSUE.contains("not verified on hardware")) }
    }

    @Test fun `the Micro's pass filters and positive preamp follow their own flags and the channel`() {
        release {
            assertTrue(ProtocolMicro.passFiltersOn); assertFalse(ProtocolMicro.microPositivePreampOn) // LP and HP proven at 48 kHz on 25.10.2026, the positive preamp not (+3 does not raise the level)
            assertTrue(FilterType.LOW_PASS in DeviceProtocol.MICRO.caps.types); assertTrue(FilterType.HIGH_PASS in DeviceTarget.MICRO.caps.types)
            assertEquals(0, ProtocolMicro.PREAMP_MAX_EFFECTIVE_DB); assertEquals(0, DeviceProtocol.MICRO.preampMax); assertEquals(0.0, DeviceTarget.MICRO.preampMax)
            assertEquals(0, WalkPlayProfiles.micro.preampMaxDb); assertTrue(FilterType.LOW_PASS in WalkPlayProfiles.micro.caps.types)
            assertFalse(DeviceTarget.MICRO.preampFits(listOf(pk), 3.0))
        }
        review {
            assertTrue(ProtocolMicro.passFiltersOn); assertTrue(ProtocolMicro.microPositivePreampOn)
            assertTrue(FilterType.LOW_PASS in DeviceProtocol.MICRO.caps.types); assertTrue(FilterType.HIGH_PASS in DeviceTarget.MICRO.caps.types)
            assertEquals(ProtocolMicro.VENDOR_PREAMP_MAX_DB, ProtocolMicro.PREAMP_MAX_EFFECTIVE_DB); assertEquals(ProtocolMicro.VENDOR_PREAMP_MAX_DB, DeviceProtocol.MICRO.preampMax)
            assertEquals(ProtocolMicro.VENDOR_PREAMP_MAX_DB, WalkPlayProfiles.micro.preampMaxDb); assertTrue(FilterType.LOW_PASS in WalkPlayProfiles.micro.caps.types)
            assertTrue(DeviceTarget.MICRO.preampFits(listOf(pk), 3.0))
        }
        // the Echo-A runs the Micro's code but has its own positive-preamp flag (split 25.10.2026): same gate, own evidence
        release {
            assertFalse(ProtocolMicro.echoAPositivePreampOn); assertEquals(0, ProtocolMicro.ECHO_A_PREAMP_MAX_EFFECTIVE_DB); assertEquals(0, WalkPlayProfiles.echoA.preampMaxDb)
        }
        review {
            assertTrue(ProtocolMicro.echoAPositivePreampOn); assertEquals(ProtocolMicro.VENDOR_PREAMP_MAX_DB, ProtocolMicro.ECHO_A_PREAMP_MAX_EFFECTIVE_DB)
            assertEquals(ProtocolMicro.VENDOR_PREAMP_MAX_DB, WalkPlayProfiles.echoA.preampMaxDb)
        }
        // the Max keeps both off in every build, and the TRN its preamp at 0 dB: they are not the Micro's features (the TRN has its own pass-filter flag: TrnDawnPassFilterTest)
        review {
            assertFalse(FilterType.LOW_PASS in DeviceProtocol.MAX.caps.types); assertEquals(0, DeviceProtocol.TRN.preampMax); assertEquals(0, DeviceProtocol.MAX.preampMax)
        }
    }

    @Test fun `the Melody's pass filters are proven on the phone, so every build has them`() {
        assertTrue(io.github.chronosauros.contour.core.native.SnowSkyMelody.PASS_FILTERS_VERIFIED)
        for (ch in listOf(BuildChannel.RELEASE, BuildChannel.PERF)) Proven.asChannel(ch) {
            assertTrue(SnowSkyMelody.passFiltersOn, "$ch")
            assertTrue(setOf(FilterType.LOW_PASS, FilterType.HIGH_PASS, FilterType.BAND_PASS, FilterType.ALL_PASS).all { it in DeviceTarget.MELODY.caps.types }, "$ch")
        }
        // the Tiny A and the K13 R2R have the seven in every build: not the Melody's feature (the KA15's flag has its own test)
        for (ch in listOf(BuildChannel.RELEASE, BuildChannel.PERF)) Proven.asChannel(ch) {
            assertEquals(7, DeviceTarget.TINY_A.caps.types.size); assertEquals(7, DeviceTarget.K13.caps.types.size)
        }
    }

    @Test fun `the KA15's pass filters are proven on the phone, so every build has them`() {
        // Nothing Phone 25.10.2026 (run 2026-10-25-contour16-r7-phone-nothing): LP, HP, BP, AP written as 4, 5, 3, 6 and read back VERIFIED
        assertTrue(io.github.chronosauros.contour.core.native.FiioKa15.PASS_FILTERS_VERIFIED)
        for (ch in listOf(BuildChannel.RELEASE, BuildChannel.PERF)) Proven.asChannel(ch) {
            assertTrue(io.github.chronosauros.contour.core.native.FiioKa15.passFiltersOn, "$ch")
            assertEquals(FilterType.entries.toSet(), DeviceTarget.KA15.caps.types, "$ch")
            assertEquals(FilterType.entries.toSet(), DeviceTarget.find(DeviceTarget.KA15.fiio!!.capture!!.vendorId, DeviceTarget.KA15.fiio!!.capture!!.productId)!!.caps.types, "$ch")
        }
        // the bench target has them whatever the flag and the build say
        assertEquals(FilterType.entries.toSet(), DeviceTarget.ka15(true).caps.types)
        assertEquals(DeviceTarget.KA15, DeviceTarget.ka15(false))
    }

    @Test fun `a settings row is written when it is proven or in a review build, and USB AUDIO never`() {
        val rows = (listOf(DeviceTarget.KA15, DeviceTarget.K13, DeviceTarget.MELODY, DeviceTarget.TINY_A).flatMap { it.settings } +
            DeviceTarget.DAWN_PRO_2.settings + DeviceTarget.ALLEGRO_MINI.settings)
        assertTrue(rows.any { it.writeVerified } && rows.any { !it.writeVerified && it.writeCommand != null })
        for (s in rows) {
            val frame = s.writeCommand != null && s.writeCommand !in DacWrite.READ_ONLY
            release { assertEquals(frame && s.writeVerified, s.writeEnabled, s.id) }
            review { assertEquals(frame, s.writeEnabled, s.id) }
        }
        val uac = rows.first { it.writeCommand in DacWrite.READ_ONLY }
        review { assertFalse(uac.writeEnabled) }
        // no frame, no write, whatever the flag says
        val readOnly = rows.first { it.writeCommand == null }
        review { assertFalse(readOnly.writeEnabled); assertFalse(readOnly.copy(writeVerified = true).writeEnabled) }
        // one flag turning true opens a row in a release
        val unproven = rows.first { !it.writeVerified && it.writeCommand != null && it.writeCommand !in DacWrite.READ_ONLY }
        release { assertFalse(unproven.writeEnabled); assertTrue(unproven.copy(writeVerified = true).writeEnabled) }
    }

    @Test fun `the Allegro EQ switch and Global Gain are proven on the phone, the Dawn Pre Gain is review only and its Global Gain is never written`() {
        val allegro = DeviceTarget.ALLEGRO_MINI.settings.associateBy { it.id }
        val dawn = DeviceTarget.DAWN_PRO_2.settings.associateBy { it.id }
        release {
            // passed the PC bench (runs/2026-10-21-kiwi-ears-allegro-mini-02 T14) and the Pixel run 2026-10-24-kiwi-ears-allegro-mini-04-phone (EQ OFF/ON, Global Gain 1/15/13 VERIFIED): on in a release
            assertTrue(allegro.getValue("eq-switch").writeEnabled); assertTrue(allegro.getValue("global-gain").writeEnabled)
            assertTrue(dawn.values.none { it.writeEnabled })
        }
        review {
            assertTrue(allegro.getValue("eq-switch").writeEnabled); assertTrue(allegro.getValue("global-gain").writeEnabled)
            assertFalse(dawn.getValue("global-gain").writeEnabled); assertTrue(dawn.getValue("pre-gain").writeEnabled); assertFalse(dawn.getValue("eq-index").writeEnabled)
        }
    }

    @Test fun `no WalkPlay settings row is released (the TRN DAC filter waits for a vendor capture), and in a review build the DAC filter writes while the gain mode, the balance and the mics are read only`() {
        val trn = WalkPlayProfiles.trn
        release {
            for (p in WalkPlayProfiles.all) assertEquals(emptyList(), WalkPlaySettings.offered(p).map { it.id }, p.id)
            assertTrue(DeviceTarget.TRN.settings.isEmpty()); assertTrue(WalkPlaySettings.row(trn, "dac-filter").let { !it.writeVerified && !it.writeEnabled })   // the read stays in the row, the write is off
            assertTrue(DeviceTarget.MICRO.settings.isEmpty() && DeviceTarget.ECHO_A.settings.isEmpty() && DeviceTarget.MAX.settings.isEmpty())
        }
        review {
            assertEquals(listOf("dac-filter", "work-mode", "gain-mode", "balance", "mic"), WalkPlaySettings.offered(trn).map { it.id })
            assertEquals(listOf("mic"), WalkPlaySettings.offered(WalkPlayProfiles.micro).map { it.id })
            assertEquals(listOf("mic"), WalkPlaySettings.offered(WalkPlayProfiles.echoA).map { it.id })
            assertEquals(emptyList(), WalkPlaySettings.offered(WalkPlayProfiles.max))
            assertEquals(WalkPlaySettings.offered(trn), DeviceTarget.TRN.settings)
            assertTrue(DeviceTarget.TRN.settings.single { it.id == "dac-filter" }.writeEnabled)
            val byId = DeviceTarget.TRN.settings.associateBy { it.id }
            assertTrue(byId.getValue("dac-filter").writeEnabled && byId.getValue("work-mode").writeEnabled)
            assertFalse(byId.getValue("gain-mode").writeEnabled); assertFalse(byId.getValue("balance").writeEnabled)
            // the TRN ignores the mic write (runs/2026-10-21-trn-black-pearl-01 and -02 T14): the row is read only in every build
            assertFalse(byId.getValue("mic").writeEnabled); assertEquals(null, byId.getValue("mic").writeCommand)
            // the Micro and the Echo-A take the mic write for some values only (RUN-MIC2, RUN-EA4 and the phone runs): the row is shown and offers no write, review build included
            for (t in listOf(DeviceTarget.MICRO, DeviceTarget.ECHO_A)) review {
                val mic = t.settings.single()
                assertEquals("mic", mic.id); assertEquals(null, mic.writeCommand); assertFalse(mic.writeEnabled, t.caps.name); assertFalse(mic.copy(writeVerified = true).writeEnabled, t.caps.name)
            }
            // the session's own row keeps its write frame: the bench writes the gain mode with --low-volume
            assertEquals(WalkPlaySettings.GAIN_MODE, WalkPlaySettings.row(trn, "gain-mode").id); assertTrue(WalkPlaySettings.row(trn, "gain-mode").writeCommand != null)
        }
    }

    @Test fun `the factory resets are offered where the vendor tool has them and open only in a review build until a unit has proven them`() {
        val fiio = listOf(DeviceTarget.KA15, DeviceTarget.MELODY, DeviceTarget.TINY_A, DeviceTarget.K13).map { it.fiio }
        for (c in fiio) {
            // the KA15 (runs/2026-10-22-fiio-ka15-05-phone) and the Melody (PC runs/2026-10-22-snowsky-melody-03, phone runs/2026-10-22-snowsky-melody-01-phone) resets passed on hardware: open in a release build too; the others open only in a review build
            val proven = c === DeviceTarget.KA15.fiio || c === DeviceTarget.MELODY.fiio
            release { assertEquals(proven, FiioReset.enabled(c), c!!.productName) }
            review { assertEquals(FiioReset.offered(c), FiioReset.enabled(c), c!!.productName) }
            assertEquals(proven, FiioReset.proven(c), c!!.productName)
        }
        assertFalse(FiioReset.offered(DeviceTarget.K13.fiio)); assertTrue(FiioReset.offered(DeviceTarget.KA15.fiio))
        // the Tiny A ignores the reset frame (runs 2026-10-23-snowsky-tiny-a-02): the bench still offers it, the app never does, in either channel
        assertTrue(FiioReset.offered(DeviceTarget.TINY_A.fiio))
        for (b in listOf(BuildChannel.RELEASE, BuildChannel.PERF)) Proven.asChannel(b) {
            assertFalse(FiioReset.inApp(DeviceTarget.TINY_A.fiio)); assertFalse(FiioReset.enabledInApp(DeviceTarget.TINY_A.fiio))
            assertTrue(FiioReset.inApp(DeviceTarget.MELODY.fiio) && FiioReset.inApp(DeviceTarget.KA15.fiio))
        }
        // the Melody's reset is proven: the app runs it in a release build too (Tiny A: never, above)
        release { assertTrue(FiioReset.proven(DeviceTarget.MELODY.fiio) && FiioReset.enabledInApp(DeviceTarget.MELODY.fiio)) }
        review { assertTrue(FiioReset.enabledInApp(DeviceTarget.MELODY.fiio)) }
        for (p in listOf(WalkPlayProfiles.micro, WalkPlayProfiles.echoA, WalkPlayProfiles.trn)) {
            release { assertFalse(WalkPlayReset.enabled(p), p.id) }
            review { assertTrue(WalkPlayReset.enabled(p), p.id) }
            assertFalse(WalkPlayReset.proven(p))
        }
        for (b in listOf(BuildChannel.RELEASE, BuildChannel.PERF)) Proven.asChannel(b) { assertFalse(WalkPlayReset.enabled(WalkPlayProfiles.max), "the Max has no reset") }
    }

    @Test fun `asChannel puts the channel back even when the block throws`() {
        runCatching { Proven.asChannel(BuildChannel.PERF) { error("x") } }
        assertEquals(BuildChannel.RELEASE, Proven.channel)
    }
}
