package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.*
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
private fun report(hex: String): ByteArray = hex.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray().copyOf(WalkPlay.REPORT_SIZE)
private fun payload(vararg b: Int): ByteArray = ByteArray(63).also { p -> b.forEachIndexed { i, v -> p[i] = v.toByte() } }

/**
 * Round 2 of the WalkPlay work (walkplay-plan.md): the Micro's flags (LP and HP, a preamp above 0 dB), a writable Echo-A in the target rules, the never-send guard, the settings rows
 * and the factory reset. The DAC here is a SYNTHETIC register store: it checks frames and order against the plan's literals, not firmware. Nothing in it has been seen on a unit.
 */
class WalkPlayExtrasTest {
    private val micro = WalkPlayProfiles.micro
    private val trn = WalkPlayProfiles.trn
    private val echo = WalkPlayProfiles.echoA

    /** A WalkPlay DAC as a register store: bands, preamp and the settings rows; [silent] commands time out like the Micro's unanswered ones. */
    private class Dac(val profile: WalkPlayProfile, var slot: Int = 101) : NativePort {
        override val deviceKey = "fake"
        val events = ArrayList<String>()
        val bandFrames = HashMap<Int, ByteArray>()
        var preamp = 0
        var mic = 0
        var filter = 0
        var work = 0
        var gain = 0
        var balanceR0 = 0
        var balanceR1 = 0
        val silent = HashSet<Int>()
        val ignored = HashSet<Int>()
        var resetClearsSettings = true
        /** The DAC takes no notice of band writes (a reset that changes nothing). */
        var ignoreBands = false
        /** A band write sets the slot byte the DAC reads back (the tag byte of the frame), as the real unit echoes what it was written. */
        var slotFollowsWrites = false
        private var resetSteps = 0
        private val lenByte = if (profile.protocol == DeviceProtocol.TRN) WalkPlay.TRN_BAND_LEN else WalkPlay.BAND_LEN

        init {
            val flat = profile.protocol.flatPlan()
            flat.bands.forEach { w -> bandFrames[w.index] = WalkPlay.bandWriteReport(w, slot, lenByte).also { it[1] = WalkPlay.READ.toByte(); it[3] = 0 } }
            preamp = flat.preampDb
        }

        fun sent(): List<String> = events.filter { it.startsWith("tx:") }.map { it.removePrefix("tx:") }
        /** Only the write reports (`4b 01 ...`). */
        fun writes(): List<String> = sent().filter { it.startsWith("4b 01") }
        override fun guard() {}
        override fun pause(ms: Long) { events += "pause:$ms" }

        override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) {
            val b = byteArrayOf(id.toByte()) + payload
            events += "tx:${hex(b)}"
            val v = b[5].toInt()          // preamp and mic: `01 03 02 00 g`, `01 02 02 80 e`
            val one = b[4].toInt() and 255 // the one-byte rows: `01 11 01 t`, `01 1D 01 t`, `01 19 01 m`
            when (b[2].toInt() and 0xFF) {
                WalkPlay.CMD_PEQ -> if (!ignoreBands) {
                    bandFrames[b[5].toInt() and 0xFF] = b.copyOf().also { it[1] = WalkPlay.READ.toByte(); it[3] = 0 }
                    if (slotFollowsWrites) slot = b[36].toInt() and 0xFF
                }
                WalkPlay.CMD_GLOBAL_GAIN -> preamp = v
                WalkPlaySettings.CMD_MIC -> if (WalkPlaySettings.CMD_MIC !in ignored) mic = v
                WalkPlaySettings.CMD_DAC_FILTER -> if (WalkPlaySettings.CMD_DAC_FILTER !in ignored) filter = one - 1
                WalkPlaySettings.CMD_WORK_MODE -> if (WalkPlaySettings.CMD_WORK_MODE !in ignored) work = one
                WalkPlaySettings.CMD_GAIN_MODE -> if (WalkPlaySettings.CMD_GAIN_MODE !in ignored) gain = one
                0x05 -> if ((b[3].toInt() and 255) in 1..4) resetSteps++
                0x17 -> if (resetSteps == 4 && resetClearsSettings) { filter = 0; work = 0; gain = 0; mic = 0 }
            }
        }

        override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
            val b = byteArrayOf(id.toByte()) + payload
            events += "tx:${hex(b)}"
            val cmd = b[2].toInt() and 0xFF
            if (cmd in silent) throw IOException("Native request timed out or returned invalid state")
            val reply = when (cmd) {
                WalkPlay.CMD_VERSION -> report("4b 80 0c 00").also { "0.6".toByteArray().copyInto(it, 4) }
                WalkPlay.CMD_GLOBAL_GAIN -> report("4b 80 03 02 00").also { it[5] = preamp.toByte() }
                WalkPlay.CMD_PEQ -> bandFrames.getValue(b[5].toInt() and 0xFF).copyOf().also { it[36] = slot.toByte() }
                WalkPlaySettings.CMD_MIC -> report("4b 80 02 02 00").also { it[5] = mic.toByte() }
                WalkPlaySettings.CMD_DAC_FILTER -> report("4b 80 11 01 00").also { it[4] = (filter + 1).toByte() }
                WalkPlaySettings.CMD_WORK_MODE -> report("4b 80 1d 01 00").also { it[4] = work.toByte() }
                WalkPlaySettings.CMD_GAIN_MODE -> report("4b 80 19 01 00").also { it[4] = gain.toByte() }
                WalkPlaySettings.CMD_BALANCE -> report("4b 80 16 02 00 00 00").also { it[4] = b[4]; it[6] = (if (b[4].toInt() == 0) balanceR0 else balanceR1).toByte() }
                else -> error("unexpected request ${hex(b)}")
            }
            val back = reply.copyOfRange(1, reply.size)
            assertTrue(matches(back), "the session's own predicate refused ${hex(reply)}")
            return back
        }
    }

    private fun eq(preamp: Double? = -4.0) = Profile("id", "t", bands = listOf(
        Band("a", FilterType.LOW_SHELF, 105.0, 6.0, 0.707),
        Band("b", FilterType.PEAK, 250.0, 10.0, 1.0),
        Band("c", FilterType.PEAK, 1000.0, -3.0, 2.0)), preampDb = preamp, createdAt = 0, updatedAt = 0)

    private fun profile(bands: List<Band>, preamp: Double?) = Profile("id", "t", bands = bands, preampDb = preamp, createdAt = 0, updatedAt = 0)
    private val pk = Band("p", FilterType.PEAK, 1000.0, 2.0, 1.0)
    private val hs = Band("h", FilterType.HIGH_SHELF, 8000.0, -4.0, 0.707)

    // ---- the Micro's flags -------------------------------------------------------------------------------------------------------------------------------

    @Test fun `the Micro's pass filters are on, its positive preamp flags are off - released limits, preamp top 0 dB`() {
        assertTrue(ProtocolMicro.PASS_FILTERS_VERIFIED)
        assertFalse(ProtocolMicro.MICRO_POSITIVE_PREAMP_VERIFIED); assertFalse(ProtocolMicro.ECHO_A_POSITIVE_PREAMP_VERIFIED)
        assertEquals(6, ProtocolMicro.VENDOR_PREAMP_MAX_DB)
        assertEquals(0, ProtocolMicro.PREAMP_MAX_EFFECTIVE_DB)
        assertEquals(0, DeviceProtocol.MICRO.preampMax)
        assertEquals(0, micro.preampMaxDb); assertEquals(0, echo.preampMaxDb)
        assertTrue(FilterType.LOW_PASS in ProtocolMicro.CAPABILITIES.types && FilterType.HIGH_PASS in ProtocolMicro.CAPABILITIES.types)
        assertTrue(FilterType.LOW_PASS in DeviceProtocol.MICRO.caps.types)
        assertEquals(ProtocolMicro.CAPABILITIES, ProtocolMicro.capabilities(true))
        assertTrue(setOf(FilterType.LOW_PASS, FilterType.HIGH_PASS).all { it in ProtocolMicro.capabilities(true).types })
        // the lower bound stays what was released
        assertEquals(-30, ProtocolMicro.PREAMP_MIN_DB)
    }

    @Test fun `with the preamp flag on the Micro plans up to +6 dB, HS fold included, with it off +1 dB is refused`() {
        assertTrue(ProtocolMicro.plan(profile(listOf(pk), 1.0)) is DevicePlan.Rejected)
        val on = ProtocolMicro.plan(profile(listOf(pk), 6.0), ProtocolMicro.CAPABILITIES, 6) as DevicePlan.Ready
        assertEquals(6, on.preampDb)
        assertTrue(ProtocolMicro.plan(profile(listOf(pk), 7.0), ProtocolMicro.CAPABILITIES, 6) is DevicePlan.Rejected)
        assertTrue(ProtocolMicro.plan(profile(listOf(pk), -30.0), ProtocolMicro.CAPABILITIES, 6) is DevicePlan.Ready)
        // the HS gain goes into the device preamp: 6 dB asked, -4 dB of HS, register 2
        val folded = ProtocolMicro.plan(profile(listOf(hs, pk), 6.0), ProtocolMicro.CAPABILITIES, 6) as DevicePlan.Ready
        assertEquals(2, folded.preampDb)
        assertTrue(ProtocolMicro.preampFits(listOf(pk), 6.0, 6)); assertFalse(ProtocolMicro.preampFits(listOf(pk), 6.0))
        assertTrue(ProtocolMicro.preampRefusal(listOf(pk), 7.0, 6)!!.contains("-30..6 dB"), ProtocolMicro.preampRefusal(listOf(pk), 7.0, 6))
        assertTrue(ProtocolMicro.preampRefusal(listOf(pk), 1.0)!!.contains("-30..0 dB"))
        // through the session, the profile's own top is what counts
        val on6 = WalkPlaySession(micro.copy(preampMaxOverride = 6), Dac(micro))
        assertEquals(5, (on6.plan(profile(listOf(pk), 5.0)) as DevicePlan.Ready).preampDb)
        assertTrue(WalkPlaySession(micro, Dac(micro)).plan(profile(listOf(pk), 5.0)) is DevicePlan.Rejected)
    }

    @Test fun `a device preamp of +5 dB is imported only when the profile's top allows it`() {
        val dac = Dac(micro).also { it.preamp = 5 }
        val snap = WalkPlaySession(micro, dac).read()
        assertTrue(WalkPlaySession(micro, dac).importExact(snap) is DacImport.Rejected)
        val ready = WalkPlaySession(micro.copy(preampMaxOverride = 6), dac).importExact(snap) as DacImport.Ready
        assertEquals(5.0, ready.eq.preampDb)
    }

    @Test fun `with the pass flag on the Micro plans LP and HP like the Echo-A, off it refuses them`() {
        val lp = Band("l", FilterType.LOW_PASS, 9000.0, 0.0, 0.707)
        val hp = Band("h", FilterType.HIGH_PASS, 30.0, 0.0, 0.707)
        assertTrue(ProtocolMicro.plan(profile(listOf(lp), 0.0), ProtocolMicro.capabilities(false)) is DevicePlan.Rejected)
        assertTrue(ProtocolMicro.plan(profile(listOf(hp), 0.0), ProtocolMicro.capabilities(false)) is DevicePlan.Rejected)
        val on = ProtocolMicro.plan(profile(listOf(lp, hp, pk), -3.0), ProtocolMicro.capabilities(true)) as DevicePlan.Ready
        assertEquals(8, on.bands.size)                                                                  // the rest of the bank is the factory flat
        assertEquals(setOf(WalkPlay.TYPE_LP, WalkPlay.TYPE_HP, WalkPlay.TYPE_PK), on.bands.take(3).map { it.typeCode }.toSet())
        val echoPlan = WalkPlaySession(echo, Dac(echo)).plan(profile(listOf(lp, hp, pk), -3.0)) as DevicePlan.Ready
        assertEquals(echoPlan.bands, on.bands)
        // nothing outside the vendor's list gets in
        assertTrue(ProtocolMicro.plan(profile(listOf(Band("b", FilterType.BAND_PASS, 1000.0, 0.0, 1.0)), 0.0), ProtocolMicro.capabilities(true)) is DevicePlan.Rejected)
        assertTrue(ProtocolMicro.plan(profile(listOf(Band("a", FilterType.ALL_PASS, 1000.0, 0.0, 1.0)), 0.0), ProtocolMicro.capabilities(true)) is DevicePlan.Rejected)
    }

    // ---- the target rules of a writable Echo-A (flag on) -----------------------------------------------------------------------------------------------------

    @Test fun `a writable Echo-A follows the Micro's preamp rules, a read-only one keeps the plain range`() {
        val ro = DeviceTarget.forEcho(echo.copy(hardwareVerified = false))
        val rw = DeviceTarget.forEcho(echo.copy(hardwareVerified = true))
        assertFalse(ro.usesWalkPlayRules); assertTrue(rw.usesWalkPlayRules)
        // -27 dB with a -4 dB high shelf is a device preamp of -31: outside the register, though -27 is inside the plain range
        assertTrue(ro.preampFits(listOf(hs), -27.0)); assertFalse(rw.preampFits(listOf(hs), -27.0))
        assertNotNull(rw.preampRefusal(listOf(hs), -27.0)); assertNull(ro.preampRefusal(listOf(hs), -27.0))
        assertTrue(rw.preampFits(listOf(hs), -20.0))
        // AUTO and the manual preamp that equals it: the WalkPlay planner's own, only where its rules apply
        assertNull(ro.manualForAuto(listOf(pk))); assertNotNull(rw.manualForAuto(listOf(pk)))
        assertNull(ro.autoRefusal(listOf(pk))); assertNull(rw.autoRefusal(listOf(pk)))
        // the send checks: the same planner as the session
        assertTrue(ro.issues(eq()).single().contains("read only"))
        assertTrue(rw.issues(eq()).isEmpty(), rw.issues(eq()).toString())
        assertTrue(rw.issues(profile(listOf(pk), 1.0)).isNotEmpty())
    }

    @Test fun `a writable Echo-A with the preamp flag on takes up to +6 dB in the control, the import and the send checks`() {
        val up = DeviceTarget.forEcho(echo.copy(hardwareVerified = true, preampMaxOverride = 6))
        val off = DeviceTarget.forEcho(echo.copy(hardwareVerified = true))
        assertEquals(6.0, up.preampMax); assertEquals(0.0, off.preampMax)
        assertTrue(up.preampFits(listOf(pk), 6.0)); assertFalse(up.preampFits(listOf(pk), 7.0)); assertFalse(off.preampFits(listOf(pk), 1.0))
        assertTrue(up.preampFits(listOf(hs), 6.0))     // 6 - 4 = 2
        assertTrue(up.preampRefusal(listOf(pk), 7.0)!!.contains("-30..6 dB"))
        assertEquals(5.9, up.fitImportedPreamp(listOf(pk), 5.96))
        assertEquals(3.0, off.fitImportedPreamp(listOf(pk), 3.0))      // nothing is capped: the send check says no
        assertTrue(up.issues(profile(listOf(pk), 6.0)).isEmpty())
        assertTrue(off.issues(profile(listOf(pk), 6.0)).isNotEmpty())
        assertEquals(6.0, up.shownPreamp(profile(listOf(pk), 6.0)))
    }

    // ---- the never-send guard ---------------------------------------------------------------------------------------------------------------------------

    private fun refusal(p: ByteArray, profile: WalkPlayProfile = micro, context: WalkPlayGuard.Context = WalkPlayGuard.Context.NORMAL) = WalkPlayGuard.refusal(p, profile, context)

    @Test fun `the reset candidates of other classes are refused in every context for every profile`() {
        val candidates = listOf(payload(0x05, 0x01, 0x02, 0x00, 0xFF), payload(0x05, 0x01, 0x01, 0x00, 0xFF), payload(0x02, 0x00, 0x00, 0x01, 0xFF), payload(0x80, 0x00, 0x00, 0x00, 0xFF))
        for (profile in listOf(micro, trn, echo, WalkPlayProfiles.max)) for (ctx in WalkPlayGuard.Context.values()) for (c in candidates) {
            assertNotNull(refusal(c, profile, ctx), "${profile.id} $ctx ${hex(c.copyOf(5))}")
        }
    }

    @Test fun `the CB1300D frames, the firmware update and the noise reduction are refused, the plain reads and writes pass`() {
        for (cmd in listOf(0x60, 0x22, 0xD3, 0x24, 0xFF, 0x1B)) for (profile in listOf(micro, trn, echo)) for (ctx in WalkPlayGuard.Context.values())
            assertNotNull(refusal(payload(0x01, cmd, 0x01, 0x01), profile, ctx), "01 ${"%02x".format(cmd)} ${profile.id} $ctx")
        for (cmd in listOf(0xDA, 0x22, 0xD3, 0x24, 0x26, 0x25)) assertNotNull(refusal(payload(0x80, cmd, 0x00)), "80 ${"%02x".format(cmd)}")
        for (ok in listOf(payload(0x80, 0x0C, 0x00), payload(0x80, 0x09, 0x00, 0x00, 0x03), payload(0x80, 0x03, 0x00), payload(0x80, 0x11, 0x00), payload(0x80, 0x02, 0x00)))
            assertNull(refusal(ok), hex(ok.copyOf(5)))
        assertNull(refusal(payload(0x01, 0x0A, 0x04, 0x00, 0x00, 0xFF, 0xFF)))
        assertNull(refusal(payload(0x01, 0x01, 0x00)))
        assertNull(refusal(payload(0x01, 0x01, 0x01)))
    }

    @Test fun `01 05 01 to 04 go out only inside a reset, 01 05 00 and 01 17 00 stay the Micro's commit`() {
        for (profile in listOf(micro, trn, echo)) {
            for (n in 1..4) {
                assertNotNull(refusal(payload(0x01, 0x05, n), profile), "${profile.id} 01 05 0$n outside a reset")
                assertNull(refusal(payload(0x01, 0x05, n), profile, WalkPlayGuard.Context.RESET), "${profile.id} 01 05 0$n inside a reset")
            }
            assertNotNull(refusal(payload(0x01, 0x05, 0x05), profile, WalkPlayGuard.Context.RESET), "01 05 05 is nobody's step")
            assertNotNull(refusal(payload(0x01, 0x17, 0x01), profile, WalkPlayGuard.Context.RESET), "01 17 01 is nobody's step")
        }
        // the released Micro commit (devicePEQ: 01 05 00, 01 17 00, TEMP_WRITE, 01 01 01) is not touched
        for (profile in listOf(micro, echo, WalkPlayProfiles.max)) {
            assertNull(refusal(payload(0x01, 0x05, 0x00), profile)); assertNull(refusal(payload(0x01, 0x17, 0x00), profile))
        }
        // the TRN's own write never sends them: outside a reset they are refused, inside one 01 17 00 is the reset's last step
        assertNotNull(refusal(payload(0x01, 0x05, 0x00), trn)); assertNotNull(refusal(payload(0x01, 0x17, 0x00), trn))
        assertNull(refusal(payload(0x01, 0x17, 0x00), trn, WalkPlayGuard.Context.RESET))
    }

    @Test fun `a preamp above 0 dB is refused unless the profile's top allows it`() {
        val plus5 = payload(0x01, 0x03, 0x02, 0x00, 5)
        for (profile in listOf(micro, echo, trn)) assertNotNull(refusal(plus5, profile), profile.id)
        assertNull(refusal(plus5, micro.copy(preampMaxOverride = 6)))
        assertNull(refusal(plus5, echo.copy(preampMaxOverride = 6)))
        assertNull(refusal(payload(0x01, 0x03, 0x02, 0x00, 0), micro))
        assertNull(refusal(payload(0x01, 0x03, 0x02, 0x00, 0xE2), micro))   // -30 dB
        // a session never builds one for a profile that stays at 0 dB: the guard is in front of the port as well
        val dac = Dac(micro)
        assertFailsWith<IllegalArgumentException> { WalkPlaySession(micro, dac).probeRequest(WalkPlay.report(WalkPlay.WRITE, 0x03, 0x02, 0x00, 5), 0x03) }
        assertTrue(dac.events.isEmpty())
    }

    @Test fun `the settings writes follow the tabs of the scheme - the DAC filter, gain mode and work mode on the TRN only, the mic on the three`() {
        val filter = payload(0x01, 0x11, 0x01, 0x03); val work = payload(0x01, 0x1D, 0x01, 0x01); val gain = payload(0x01, 0x19, 0x01, 0x02)
        for (f in listOf(filter, work, gain)) {
            assertNull(refusal(f, trn), hex(f.copyOf(5)))
            assertNotNull(refusal(f, micro)); assertNotNull(refusal(f, echo)); assertNotNull(refusal(f, WalkPlayProfiles.max))
        }
        val mic = payload(0x01, 0x02, 0x02, 0x80, 0xF1)
        for (profile in listOf(micro, echo, trn)) assertNull(refusal(mic, profile), profile.id)
        assertNotNull(refusal(mic, WalkPlayProfiles.max))
        // the balance is read, never written
        assertNotNull(refusal(payload(0x01, 0x16, 0x02, 0x00, 0x00), trn)); assertNotNull(refusal(payload(0x01, 0x16, 0x02, 0x00, 0x00), micro))
        assertNull(refusal(payload(0x80, 0x16, 0x02, 0x00, 0x00), trn))
    }

    @Test fun `the shelf Q ceiling is where the shelf formula's square root turns negative, so the table's Q 10 box overstates a shelf at +-10 dB`() {
        assertEquals(6.87, WalkPlay.shelfQCeiling(10.0))
        assertEquals(6.87, WalkPlay.shelfQCeiling(-10.0))
        assertNull(WalkPlay.shelfQCeiling(0.0))
        assertTrue(WalkPlay.shelfQCeiling(5.0)!! > WalkPlay.shelfQCeiling(10.0)!!)
        val a = Math.sqrt(Math.pow(10.0, 10.0 / 20)); val spread = a + 1 / a
        fun root(q: Double) = spread * (1 / q - 1) + 2
        assertTrue(root(6.87) >= 0 && root(6.88) < 0)                               // the next Q up has no real answer
    }

    // ---- the settings rows ------------------------------------------------------------------------------------------------------------------------------

    @Test fun `the rows of each DAC - five on the TRN, the mic on the Micro and the Echo-A, none on the Max - and none is verified`() {
        assertEquals(listOf("dac-filter", "work-mode", "gain-mode", "balance", "mic"), WalkPlaySettings.of(trn).map { it.id })
        assertEquals(listOf("mic"), WalkPlaySettings.of(micro).map { it.id })
        assertEquals(listOf("mic"), WalkPlaySettings.of(echo).map { it.id })
        assertTrue(WalkPlaySettings.of(WalkPlayProfiles.max).isEmpty())
        for (p in listOf(trn, micro, echo)) for (s in WalkPlaySettings.of(p)) assertFalse(s.writeVerified, "${p.id} ${s.id}")   // the TRN DAC filter too: owner decision 25.10.2026, the vendor tool has not been seen offering it
        assertNull(WalkPlaySettings.row(trn, WalkPlaySettings.BALANCE).writeCommand)         // read only: unit and step unknown
        // the TRN ignores the mic write (runs/2026-10-21-trn-black-pearl-01 and -02 T14); the Micro and the Echo-A take it for some values only (Echo-A phone: 0 and +1 accepted, -1 ignored; Micro phone:
        // -3 accepted, -1 ignored): read only on all three, in every build
        for (p in listOf(trn, micro, echo)) assertNull(WalkPlaySettings.row(p, WalkPlaySettings.MIC).writeCommand, p.id)
        assertFailsWith<IllegalArgumentException> { WalkPlaySettings.row(micro, WalkPlaySettings.DAC_FILTER) }
        // the DAC Settings page of the app shows the proven rows only: none for a WalkPlay DAC in a release
        assertTrue(DeviceTarget.MICRO.settings.isEmpty() && DeviceTarget.MAX.settings.isEmpty() && DeviceTarget.TRN.settings.isEmpty())
    }

    @Test fun `every settings write is the plan's frame, byte for byte, and out-of-range values leave no frame`() {
        fun frame(id: String, v: Int) = hex(WalkPlaySettings.writeReports(WalkPlaySettings.row(trn, id), v).single())
        // walkplay-plan.md: 01 11 01 t (t 1..5), 01 1D 01 t (0 Class-H, 1 Class-AB), 01 19 01 m-1 (m 1..3), 01 02 02 80 e (e int8)
        for (i in 0..4) assertEquals(hex(report("4b 01 11 01 0${i + 1}")), frame("dac-filter", i))
        assertEquals(hex(report("4b 01 1d 01 00")), frame("work-mode", 0)); assertEquals(hex(report("4b 01 1d 01 01")), frame("work-mode", 1))
        for (m in 0..2) assertEquals(hex(report("4b 01 19 01 0$m")), frame("gain-mode", m))
        // the mic is a write nowhere: no row has a write command, so no frame comes out of it for any DAC
        for (p in listOf(micro, echo)) for (v in listOf(-15, 0, 15)) assertFailsWith<IllegalArgumentException>("${p.id} mic $v") { WalkPlaySettings.writeReports(WalkPlaySettings.row(p, "mic"), v) }
        assertEquals(hex(report("4b 01 01 00")), hex(WalkPlaySettings.saveReport()))
        for ((id, v) in listOf("dac-filter" to 5, "dac-filter" to -1, "work-mode" to 2, "gain-mode" to 3))
            assertFailsWith<IllegalArgumentException>("$id $v") { WalkPlaySettings.writeReports(WalkPlaySettings.row(trn, id), v) }
        for (v in listOf(16, -16)) assertFailsWith<IllegalArgumentException>("mic $v") { WalkPlaySettings.writeReports(WalkPlaySettings.row(micro, "mic"), v) }
        assertFailsWith<IllegalArgumentException> { WalkPlaySettings.writeReports(WalkPlaySettings.row(trn, "balance"), 0) }
        assertFailsWith<IllegalArgumentException> { WalkPlaySettings.writeReports(WalkPlaySettings.row(trn, "mic"), 0) }          // the TRN ignores it: no frame
        // every frame the table builds passes the guard on the TRN
        for (s in WalkPlaySettings.of(trn).filter { it.writeCommand != null }) {
            val range = if (s.options.isNotEmpty()) s.options.indices else s.min..s.max
            for (v in range) assertNull(refusal(WalkPlaySettings.writeReports(s, v).single().let { it.copyOfRange(1, it.size) }, trn), "${s.id} $v")
        }
    }

    @Test fun `the read requests and decoders are the plan's - filter minus 1, work mode, gain mode as is, mic as int8, balance per channel`() {
        val rows = WalkPlaySettings.of(trn).associateBy { it.id }
        assertEquals(hex(report("4b 80 11 00")), hex(WalkPlaySettings.readRequests(rows.getValue("dac-filter")).single()))
        assertEquals(hex(report("4b 80 02 00")), hex(WalkPlaySettings.readRequests(rows.getValue("mic")).single()))
        assertEquals(listOf(hex(report("4b 80 16 02 00 00")), hex(report("4b 80 16 02 01 00"))), WalkPlaySettings.readRequests(rows.getValue("balance")).map(::hex))
        fun decode(id: String, reply: String) = WalkPlaySettings.decode(rows.getValue(id), report(reply))
        assertEquals(0, decode("dac-filter", "4b 80 11 01 01")); assertEquals(4, decode("dac-filter", "4b 80 11 01 05"))
        assertNull(decode("dac-filter", "4b 80 11 01 00")); assertNull(decode("dac-filter", "4b 80 11 01 06"))
        assertEquals(1, decode("work-mode", "4b 80 1d 01 01")); assertNull(decode("work-mode", "4b 80 1d 01 02"))
        assertEquals(2, decode("gain-mode", "4b 80 19 01 02")); assertNull(decode("gain-mode", "4b 80 19 01 03"))
        assertEquals(0, decode("mic", "4b 80 02 02 00"))                  // the Micro's CAP reply: most likely mic 0
        assertEquals(-15, decode("mic", "4b 80 02 02 00 f1")); assertEquals(15, decode("mic", "4b 80 02 02 00 0f"))
        // the balance: r != 0 is attenuation 256 - r; channel 1 minus channel 0
        val c0 = report("4b 80 16 02 00 00 00"); val c1 = report("4b 80 16 02 01 00 fa")
        assertEquals(6, WalkPlaySettings.balanceValue(c0, c1)); assertEquals(0, WalkPlaySettings.balanceChannel(c0)); assertEquals(1, WalkPlaySettings.balanceChannel(c1))
        // a reply is only a full read report of the row's command (a write echo or a short report is not)
        val filter = rows.getValue("dac-filter")
        assertTrue(WalkPlaySettings.isReply(filter, report("4b 80 11 01 02")))
        assertFalse(WalkPlaySettings.isReply(filter, report("4b 01 11 01 02"))); assertFalse(WalkPlaySettings.isReply(filter, report("4b 80 19 01 02")))
        assertFalse(WalkPlaySettings.isReply(filter, report("4b 80 11 01 02").copyOf(10)))
        val balance = rows.getValue("balance")
        assertTrue(WalkPlaySettings.isReply(balance, c1, 1)); assertFalse(WalkPlaySettings.isReply(balance, c1, 0))
    }

    // ---- the session: settings read and write ---------------------------------------------------------------------------------------------------------------

    @Test fun `readSettings asks each row the plan's way, and a silent DAC is a null value, never an error`() {
        val dac = Dac(trn).also { it.filter = 3; it.work = 1; it.gain = 2; it.mic = -4; it.balanceR1 = 250 }
        val got = WalkPlaySession(trn, dac).readSettings().associate { it.id to it.value }
        assertEquals(mapOf("dac-filter" to 3, "work-mode" to 1, "gain-mode" to 2, "balance" to 6, "mic" to -4), got)
        assertTrue(dac.writes().isEmpty())
        // the Micro answers none of the TRN's rows: three tries each, then null, and its mic is still read
        val quiet = Dac(micro).also { it.silent += listOf(0x11, 0x1D, 0x19, 0x16); it.mic = 2 }
        val asked = WalkPlaySession(micro, quiet).readSettings(WalkPlaySettings.of(trn)).associate { it.id to it.value }
        assertEquals(mapOf("dac-filter" to null, "work-mode" to null, "gain-mode" to null, "balance" to null, "mic" to 2), asked)
        assertEquals(3, quiet.sent().count { it == hex(report("4b 80 11 00")) })
        assertTrue(quiet.writes().isEmpty())
    }

    @Test fun `writeSetting reads before, writes the one frame, reads after and keeps the value the DAC held`() {
        val dac = Dac(trn).also { it.filter = 1 }
        val s = WalkPlaySession(trn, dac)
        val r = s.writeSetting("dac-filter", 3, explicitHold = true)
        assertTrue(r.verified, r.reason); assertEquals(3, r.readback); assertEquals(1, s.lastSettingBefore)
        assertEquals(listOf(hex(report("4b 01 11 01 04"))), dac.writes())
        assertEquals(listOf(hex(report("4b 80 11 00")), hex(report("4b 01 11 01 04")), hex(report("4b 80 11 00"))), dac.sent())
        // the vendor's flash save only when asked for, right after the write
        val saved = Dac(trn)
        WalkPlaySession(trn, saved).writeSetting("dac-filter", 2, explicitHold = true, save = true)
        assertEquals(listOf(hex(report("4b 01 11 01 03")), hex(report("4b 01 01 00"))), saved.writes())
        assertEquals(2, saved.filter)
        // the TRN ignores the mic write: the row is read only there and no frame goes out
        val noMic = Dac(trn)
        assertFailsWith<IllegalArgumentException> { WalkPlaySession(trn, noMic).writeSetting("mic", 3, explicitHold = true) }
        assertTrue(noMic.writes().isEmpty())
        // the Micro and the Echo-A: the mic row is read only too (the DACs take it for some values only), so no frame goes out
        for (p in listOf(micro, echo)) {
            val d = Dac(p)
            assertFailsWith<IllegalArgumentException> { WalkPlaySession(p, d).writeSetting("mic", 7, explicitHold = true) }
            assertTrue(d.writes().isEmpty(), p.id)
        }
    }

    @Test fun `a setting write that does not stick is reported with both numbers, a refused one leaves no frame`() {
        val dac = Dac(trn).also { it.ignored += WalkPlaySettings.CMD_WORK_MODE }
        val r = WalkPlaySession(trn, dac).writeSetting("work-mode", 1, explicitHold = true)
        assertFalse(r.verified); assertEquals("the DAC reads 0, wrote 1", r.reason)
        // refused before the first frame: no HOLD TO SEND, a row the DAC lacks, the read only balance, a value outside the row
        val none = Dac(micro)
        val s = WalkPlaySession(micro, none)
        assertFailsWith<IllegalArgumentException> { s.writeSetting("mic", 1, explicitHold = false) }
        assertFailsWith<IllegalArgumentException> { s.writeSetting("dac-filter", 1, explicitHold = true) }
        assertFailsWith<IllegalArgumentException> { s.writeSetting("mic", 16, explicitHold = true) }
        assertFailsWith<IllegalArgumentException> { WalkPlaySession(trn, Dac(trn)).writeSetting("balance", 0, explicitHold = true) }
        assertTrue(none.events.isEmpty())
    }

    // ---- the factory reset --------------------------------------------------------------------------------------------------------------------------------------

    /** The vendor's eight band frames of `resetDevice` as the decoded web tool holds them (WPW 1217101-1218102), after the 4b report id. */
    private val vendorBands = listOf(
        "01 09 18 00 00 00 00 00 00 00 40 0d 82 47 80 c5 aa b8 3f f3 7d b8 7f 3b 55 47 c0 32 00 c0 00 00 00 02 00 01",
        "01 09 18 00 01 00 00 00 00 00 40 83 41 1e 81 f1 86 e4 3e 7d be e1 7e 0f 79 1b c1 c8 00 c0 00 00 00 02 00 01",
        "01 09 18 00 02 00 00 00 00 00 40 cf ab cc 82 ae 7f 44 3d 31 54 33 7d 52 80 bb c2 f4 01 c0 00 00 00 02 00 01",
        "01 09 18 00 03 00 00 00 00 00 40 70 49 9c 85 d1 f0 a6 3a 90 b6 63 7a 2f 0f 59 c5 e8 03 c0 00 00 00 02 00 01",
        "01 09 18 00 04 00 00 00 00 00 40 48 b7 14 8e e0 14 76 33 b8 48 eb 71 20 eb 89 cc c4 09 c0 00 00 00 02 00 01",
        "01 09 18 00 05 00 00 00 00 00 40 77 da 2e 9c 18 40 69 29 89 25 d1 63 e8 bf 96 d6 88 13 c0 00 00 00 02 00 01",
        "01 09 18 00 06 00 00 00 00 00 40 fe 25 c4 b7 04 78 0c 1b 02 da 3b 48 fc 87 f3 e4 10 27 c0 00 00 00 02 00 01",
        "01 09 18 00 07 00 00 00 00 00 40 7b 7a 3f d2 59 fb 59 12 85 85 c0 2d a7 04 a6 ed 98 3a c0 00 00 00 02 00 01")

    /** The whole sequence in the plan's order: preamp 0, eight bands, TEMP_WRITE, flash, 01 05 01..04, 01 17 00. */
    private val planSequence: List<String> =
        listOf("4b 01 03 02 00 00") + vendorBands.map { "4b $it" } + listOf("4b 01 0a 04 00 00 ff ff", "4b 01 01 00", "4b 01 05 01", "4b 01 05 02", "4b 01 05 03", "4b 01 05 04", "4b 01 17 00")

    private fun ticketFor(profile: WalkPlayProfile, session: WalkPlaySession): Pair<WalkPlayResetBackup, WalkPlayResetTicket> {
        val flow = WalkPlayResetFlow(profile)
        val backup = session.captureForReset(true, 1_000L)
        flow.begin(backup)
        return backup to flow.confirm(ownerConsent = true)
    }

    @Test fun `the reset sequence is the plan's six steps in its order, the vendor's eight band frames byte for byte, on all three DACs`() {
        for (p in listOf(micro, echo, trn)) {
            val dac = Dac(p)
            val session = WalkPlaySession(p, dac)
            val (_, ticket) = ticketFor(p, session)
            dac.events.clear()
            val sent = session.factoryReset(ticket)
            assertEquals(planSequence.map { hex(report(it)) }, dac.sent(), p.id)
            assertEquals(WalkPlayResetSent(planSequence.size, null), sent)
            // nothing is asked of the DAC for the settle time after the last frame
            assertEquals("pause:${WalkPlayReset.SETTLE_MS}", dac.events.last(), p.id)
            // the TRN's own write sends 0x21 for 10 bands, the vendor's reset sends 0x18 for 8: the reset keeps the vendor's
            assertTrue(dac.sent().drop(1).take(8).all { it.startsWith("4b 01 09 18") }, p.id)
        }
        // the sequence builder is what the narrator and the bench call: the same reports
        val flowSession = WalkPlaySession(micro, Dac(micro))
        val (_, ticket) = ticketFor(micro, flowSession)
        assertEquals(planSequence.map { hex(report(it)) }, WalkPlayReset.sequence(micro, ticket).map { hex(it.report) })
    }

    @Test fun `a ticket is good for one reset of its own DAC, and the guard is closed again after the reset`() {
        val dac = Dac(micro)
        val s = WalkPlaySession(micro, dac)
        val (_, ticket) = ticketFor(micro, s)
        s.factoryReset(ticket)
        val after = dac.events.size
        assertFailsWith<IllegalArgumentException> { s.factoryReset(ticket) }
        assertEquals(after, dac.events.size)
        // 01 05 01 is refused again once the reset is over
        assertFailsWith<IllegalArgumentException> { s.probeRequest(WalkPlay.report(WalkPlay.WRITE, 0x05, 0x01), 0x05) }
        assertEquals(after, dac.events.size)
        // a ticket of another DAC builds nothing
        val (_, microTicket) = ticketFor(micro, WalkPlaySession(micro, Dac(micro)))
        assertFailsWith<IllegalArgumentException> { WalkPlayReset.sequence(trn, microTicket) }
        // the Max has no reset in Contour
        assertFailsWith<IllegalArgumentException> { WalkPlaySession(WalkPlayProfiles.max, Dac(WalkPlayProfiles.max)).captureForReset(true, 1L) }
    }

    @Test fun `a reset needs a complete backup, the owner's yes and the right order, and the app has none verified`() {
        assertTrue(WalkPlayReset.VERIFIED.isEmpty())
        assertTrue(WalkPlayReset.offered(micro) && WalkPlayReset.offered(echo) && WalkPlayReset.offered(trn) && !WalkPlayReset.offered(WalkPlayProfiles.max))
        for (p in listOf(micro, echo, trn)) assertFalse(WalkPlayReset.enabled(p), p.id)
        val dac = Dac(micro)
        val s = WalkPlaySession(micro, dac)
        assertFailsWith<IllegalArgumentException> { s.captureForReset(false, 1L) }       // an explicit user action
        val backup = s.captureForReset(true, 1L)
        val flow = WalkPlayResetFlow(micro)
        assertFailsWith<IllegalArgumentException> { flow.confirm(true) }                 // not backed up
        assertFailsWith<IllegalArgumentException> { flow.begin(backup.copy(bands = backup.bands.drop(1))) }
        assertFailsWith<IllegalArgumentException> { flow.begin(backup.copy(profileId = "trn")) }
        assertFailsWith<IllegalArgumentException> { flow.begin(backup.copy(preampDb = 3)) }          // above the top Contour writes
        assertFailsWith<IllegalArgumentException> { flow.begin(backup.copy(bands = backup.bands.map { it.copy(typeCode = 7) })) }
        flow.begin(backup)
        assertFailsWith<IllegalArgumentException> { flow.begin(backup) }
        assertFailsWith<IllegalArgumentException> { flow.confirm(false) }
        assertFailsWith<IllegalArgumentException> { flow.sent() }
        flow.confirm(true); flow.sent()
        assertFailsWith<IllegalArgumentException> { flow.restored() }
        flow.compared(backup); flow.restored()
        assertEquals(WalkPlayResetFlow.Step.RESTORED, flow.step)
        assertTrue(dac.writes().isEmpty(), "nothing was written: the backup is a read")
    }

    @Test fun `the backup is plain text that reads back exactly, and anything else is refused with the line`() {
        val dac = Dac(trn).also { it.filter = 3; it.work = 1; it.gain = 2; it.mic = -4; it.balanceR1 = 250; it.silent += 0x19 }
        val backup = WalkPlaySession(trn, dac).captureForReset(true, 1_234L)
        assertEquals(10, backup.bands.size)
        assertEquals(mapOf("dac-filter" to 3, "work-mode" to 1, "balance" to 6, "mic" to -4), backup.settings)
        assertEquals(listOf("gain-mode"), backup.unreadSettings)
        assertEquals(backup, WalkPlayResetBackup.parse(backup.toText()))
        assertTrue(backup.problems(trn).isEmpty())
        assertTrue(backup.problems(micro).single().startsWith("The backup is of trn"))
        assertFailsWith<IllegalArgumentException> { WalkPlayResetBackup.parse("something else\n") }
        assertFailsWith<IllegalArgumentException> { WalkPlayResetBackup.parse(backup.toText() + "bogus 1\n") }
        assertFailsWith<IllegalArgumentException> { WalkPlayResetBackup.parse(backup.toText().replace("preamp 0", "preamp x")) }
        assertFailsWith<IllegalArgumentException> { WalkPlayResetBackup.parse(backup.toText() + "setting mic 3\n") }   // a setting twice
        assertTrue(backup.copy(bands = backup.bands.dropLast(2)).problems(trn).single().startsWith("Expected all 10 bands"))
    }

    @Test fun `reset, read after, compare and restore bring the TRN back to what it held, bands 9 and 10 included`() {
        val dac = Dac(trn, slot = 0).also { it.filter = 3; it.work = 1; it.gain = 2; it.mic = -4; it.balanceR1 = 250 }
        val s = WalkPlaySession(trn, dac)
        // a real EQ on the DAC first: three bands and the preamp, and bands 9 and 10 with a peak the reset's eight frames do not cover
        s.write(profile(eq().bands + listOf(
            Band("x", FilterType.PEAK, 3000.0, 2.0, 1.0), Band("y", FilterType.PEAK, 4000.0, 1.0, 1.0), Band("z", FilterType.PEAK, 5000.0, 1.0, 1.0),
            Band("u", FilterType.PEAK, 6000.0, 1.0, 1.0), Band("v", FilterType.PEAK, 7000.0, 1.0, 1.0), Band("w", FilterType.PEAK, 8000.0, 1.0, 1.0),
            Band("q", FilterType.PEAK, 9000.0, 1.0, 1.0)), -6.0), explicitHold = true)
        val before = s.read()
        val flow = WalkPlayResetFlow(trn)
        val taken = s.captureForReset(true, 5L)
        flow.begin(taken)
        val sent = s.factoryReset(flow.confirm(true))
        flow.sent()
        assertNull(sent.note)
        val afterReset = s.captureForReset(true, 5L)
        val changes = flow.compared(afterReset)
        assertTrue(changes.any { it.startsWith("band 1:") } && changes.any { it.startsWith("preamp:") }, changes.toString())
        assertEquals(0, afterReset.preampDb)
        assertEquals(0, dac.filter); assertEquals(0, dac.mic)                         // the fake's reset cleared the settings
        // the vendor wrote bands 0-7 only: 9 and 10 are what they were (the bench must read them after a reset)
        assertEquals(taken.bands.drop(8), afterReset.bands.drop(8))
        val restore = s.restoreAfterReset(taken)
        flow.restored()
        assertTrue(restore.ok, restore.lines.toString())
        assertTrue(restore.lines.any { it.contains("restored") })
        assertTrue(restore.lines.any { it.contains("read only") })                     // the balance is a line, never written
        val now = s.read()
        assertEquals(before.bands.map { it.registers }, now.bands.map { it.registers })
        assertEquals(before.preampDb, now.preampDb)
        assertEquals(listOf(3, 1, 2, 0), listOf(dac.filter, dac.work, dac.gain, dac.mic))    // the TRN mic is read only: the reset left 0 and the restore does not write it
        assertEquals(10, now.bands.size)
    }

    @Test fun `the restore writes the slot byte of its backup into every band row, not the one the DAC reads after the reset`() {
        for (p in listOf(micro, trn)) {
            val backupSlot = if (p == trn) 0 else 101
            val dac = Dac(p, slot = backupSlot).also { it.slotFollowsWrites = true }
            val s = WalkPlaySession(p, dac)
            val flow = WalkPlayResetFlow(p)
            val taken = s.captureForReset(true, 5L)
            assertEquals(backupSlot, taken.slot)
            flow.begin(taken)
            s.factoryReset(flow.confirm(true)); flow.sent()
            dac.slot = 77                                         // the slot byte the DAC reads after the reset is another one
            val after = s.captureForReset(true, 5L)
            assertEquals(77, after.slot)
            flow.compared(after)
            val mark = dac.sent().size
            val restore = s.restoreAfterReset(taken)
            assertTrue(restore.ok, "${p.id} ${restore.lines}")
            val len = if (p == trn) WalkPlay.TRN_BAND_LEN else WalkPlay.BAND_LEN
            val rows = dac.sent().drop(mark).filter { it.startsWith("4b 01 09") }
            val want = taken.writes(p.caps.coefficientRateHz).map { hex(WalkPlay.bandWriteReport(it, taken.slot, len)) }
            val other = taken.writes(p.caps.coefficientRateHz).map { hex(WalkPlay.bandWriteReport(it, 77, len)) }
            assertEquals(want, rows, p.id)
            assertTrue(want.zip(other).all { (a, b) -> a != b }, "the two slot bytes make different rows, so this test can tell them apart")
            assertTrue(rows.all { it.split(" ")[36].toInt(16) == backupSlot }, p.id)
            assertEquals(backupSlot, dac.slot, p.id)
        }
    }

    @Test fun `a reset that changed nothing is reported as no change, and a setting that the restore cannot write is a line not a skip`() {
        val dac = Dac(micro).also { it.resetClearsSettings = false; it.ignoreBands = true; it.mic = 3 }
        val s = WalkPlaySession(micro, dac)
        val flow = WalkPlayResetFlow(micro)
        val taken = s.captureForReset(true, 9L)
        flow.begin(taken)
        s.factoryReset(flow.confirm(true)); flow.sent()
        // the DAC took no notice of the frames: the read after it shows nothing different, so the bench restores nothing
        val changes = flow.compared(s.captureForReset(true, 9L))
        assertTrue(changes.isEmpty(), changes.toString())
        // the mic row is read only on the Micro: the restore names it and writes nothing
        dac.ignoreBands = false
        val bumped = taken.copy(settings = mapOf("mic" to 5))
        val r = s.restoreAfterReset(bumped)
        assertTrue(r.lines.any { it.startsWith("MIC GAIN: read only, not written back") }, r.lines.toString())
        assertEquals(3, dac.mic)
        // a restore with a setting whose write the DAC ignores: NOT restored, named (the TRN's DAC filter is a row that writes)
        val tdac = Dac(trn).also { it.resetClearsSettings = false }
        val ts = WalkPlaySession(trn, tdac)
        val ttaken = ts.captureForReset(true, 9L)
        tdac.ignored += WalkPlaySettings.CMD_DAC_FILTER
        val tr = ts.restoreAfterReset(ttaken.copy(settings = mapOf("dac-filter" to 4)))
        assertFalse(tr.ok)
        assertTrue(tr.lines.any { it.startsWith("FILTER: NOT restored") }, tr.lines.toString())
    }

    @Test fun `the app's restore passes the rows it offers, so the gain mode is a line and never written back while the other settings are`() {
        Proven.asChannel(BuildChannel.PERF) {
            val dac = Dac(trn, slot = 0).also { it.filter = 3; it.work = 1; it.gain = 2; it.mic = -4 }
            val s = WalkPlaySession(trn, dac)
            val flow = WalkPlayResetFlow(trn)
            val taken = s.captureForReset(true, 5L)
            flow.begin(taken)
            s.factoryReset(flow.confirm(true)); flow.sent()
            flow.compared(s.captureForReset(true, 5L))
            val gainAfterReset = dac.gain
            val r = s.restoreAfterReset(taken, WalkPlaySettings.offered(trn))
            assertTrue(r.ok, r.lines.toString())
            assertTrue(r.lines.any { it.startsWith("GAIN: read only") }, r.lines.toString())
            assertEquals(gainAfterReset, dac.gain)                                         // the gain mode was not written back
            assertEquals(listOf(3, 1, 0), listOf(dac.filter, dac.work, dac.mic))          // the TRN mic is read only: not written back
        }
    }
}
