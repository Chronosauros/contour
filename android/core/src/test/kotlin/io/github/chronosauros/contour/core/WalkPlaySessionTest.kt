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

/**
 * Frame parity of [WalkPlaySession] with what the app's DacClient sends for the WalkPlay DACs: the same reports in the same order
 * with the same pauses (the DacClient flow is written out below from the WalkPlay protocol functions it calls), and for the TRN
 * the frames of the PC write of 19.10.2026 (research/black-pearl-pc-2026-10-19: band frames, commit, preamp -12, `01 01 00`,
 * `01 04 00`; the same hex as TrnWriteTest). The DAC here is a SYNTHETIC register store: it checks the sequence, not firmware.
 */
class WalkPlaySessionTest {
    /** A WalkPlay DAC as a register store: answers reads the way the unit does (the write frame with the read bit), keeps writes. */
    private class FakeDac(val profile: WalkPlayProfile, var slot: Int = 0) : NativePort {
        override val deviceKey = "fake"
        /** "tx:<hex>" for every report the host sends (requests included), "pause:<ms>" for every pause, in order. */
        val events = ArrayList<String>()
        val replies = HashMap<Int, ByteArray>()
        var preamp = 0
        /** Band index -> number of requests for it that get no answer (a stale queue on the TRN). */
        val silent = HashMap<Int, Int>()
        val ignoredBands = HashSet<Int>()
        var firmware = "0.6"
        private val lenByte = if (profile.protocol == DeviceProtocol.TRN) WalkPlay.TRN_BAND_LEN else WalkPlay.BAND_LEN

        init {
            val flat = profile.protocol.flatPlan()
            flat.bands.forEach { store(it) }
            preamp = flat.preampDb
        }

        private fun store(w: WalkPlay.BandWrite) {
            replies[w.index] = WalkPlay.bandWriteReport(w, slot, lenByte).also { it[1] = WalkPlay.READ.toByte(); it[3] = 0 }
        }

        private fun full(id: Int, payload: ByteArray) = byteArrayOf(id.toByte()) + payload
        fun sentReports(): List<String> = events.filter { it.startsWith("tx:") }.map { it.removePrefix("tx:") }
        override fun guard() {}
        override fun pause(ms: Long) { events += "pause:$ms" }

        override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) {
            assertEquals(WalkPlay.REPORT_ID, id)
            assertEquals(NativeHidReports.Kind.OUTPUT, kind)
            assertEquals(63, payload.size)
            val b = full(id, payload)
            events += "tx:${hex(b)}"
            when ((b[2].toInt() and 0xFF)) {
                WalkPlay.CMD_PEQ -> if ((b[1].toInt() and 0xFF) == WalkPlay.WRITE) {
                    val index = b[5].toInt() and 0xFF
                    if (index !in ignoredBands) replies[index] = b.copyOf().also { it[1] = WalkPlay.READ.toByte(); it[3] = 0 }
                }
                WalkPlay.CMD_GLOBAL_GAIN -> preamp = b[5].toInt()
            }
        }

        override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
            val b = full(id, payload)
            events += "tx:${hex(b)}"
            val reply = when ((b[2].toInt() and 0xFF)) {
                WalkPlay.CMD_VERSION -> report("4b 80 0c 00").also { firmware.toByteArray().copyInto(it, 4) }
                WalkPlay.CMD_GLOBAL_GAIN -> report("4b 80 03 02 00").also { it[5] = preamp.toByte() }
                WalkPlay.CMD_PEQ -> {
                    val index = b[5].toInt() and 0xFF // the bulk slot request is band 0's, byte for byte (zero padded)
                    val left = silent[index] ?: 0
                    if (left > 0) { silent[index] = left - 1; throw IOException("Native request timed out or returned invalid state") }
                    replies.getValue(index).also { it[36] = slot.toByte() }
                }
                else -> error("unexpected request ${hex(b)}")
            }
            return reply.copyOfRange(1, reply.size).also { assertTrue(matches(it)) }
        }
    }

    private fun eq(preamp: Double? = -4.0) = Profile("id", "t", bands = listOf(
        Band("a", FilterType.LOW_SHELF, 105.0, 6.0, 0.707),
        Band("b", FilterType.PEAK, 250.0, 10.0, 1.0),
        Band("c", FilterType.PEAK, 1000.0, -3.0, 2.0)), preampDb = preamp, createdAt = 0, updatedAt = 0)

    /** DacClient.read as a list of request reports. */
    private fun readFrames(p: DeviceProtocol): List<String> = listOf(WalkPlay.versionRequest(), WalkPlay.slotRequest()).map(::hex) +
        (0 until p.caps.bands).map { hex(WalkPlay.bandRequest(it)) } + hex(WalkPlay.preampRequest())

    /** DacClient.writePlan + write for [p]: the slot first (Micro: version and slot; Max, TRN: the whole state), the sequence with its pauses, the read-back. */
    private fun appEvents(p: DeviceProtocol, plan: DevicePlan.Ready, slot: Int): List<String> {
        val out = ArrayList<String>()
        out += if (p.experimental) readFrames(p).map { "tx:$it" } else listOf(WalkPlay.versionRequest(), WalkPlay.slotRequest()).map { "tx:${hex(it)}" }
        for (step in p.writeSequence(plan, slot)) {
            out += "tx:${hex(step.report)}"
            if (step.delayAfterMs > 0) out += "pause:${step.delayAfterMs}"
        }
        out += readFrames(p).map { "tx:$it" }
        return out
    }

    private fun parity(profile: WalkPlayProfile, slot: Int) {
        val port = FakeDac(profile, slot)
        val session = WalkPlaySession(profile, port)
        val profileEq = eq()
        val plan = session.plan(profileEq) as DevicePlan.Ready
        val r = session.write(profileEq, explicitHold = true)
        assertEquals(appEvents(profile.protocol, plan, slot), port.events, profile.id)
        assertTrue(r.verified, r.reason)
        assertFalse(r.pending)
        assertNull(r.reason)
        assertEquals(plan.preampDb, r.readback!!.preampDb)
        assertEquals(slot, r.readback!!.slot)
        assertEquals(plan, session.lastPlan)
        assertEquals(slot, session.lastSlot)
    }

    @Test fun `Micro, Max and TRN send exactly the frames and pauses of the app's DacClient`() {
        parity(WalkPlayProfiles.micro, 101)
        parity(WalkPlayProfiles.max, 101)
        parity(WalkPlayProfiles.trn, 0)
    }

    @Test fun `Echo-A is the Micro's SchemeNo11 code with 8 bands, PK LS HS LP and HP, and its write flag is on since the desk run of 25 October 2026`() {
        val p = WalkPlayProfiles.echoA
        assertEquals(8, p.bands)
        assertTrue(WalkPlayProfiles.ECHO_A_HARDWARE_VERIFIED)
        assertTrue(p.hardwareVerified)
        assertEquals(setOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF, FilterType.LOW_PASS, FilterType.HIGH_PASS), p.types)
        assertEquals(p.types, p.caps.types)
        assertEquals(p, WalkPlayProfiles.find(0x3302, 0x12C5))
        // the stable app does not know it: the profile is the bench's, DeviceTarget (and so the app) stays as it was
        assertNull(DeviceTarget.find(0x3302, 0x12C5))
        parity(p, 101)
        val s = WalkPlaySession(p, FakeDac(p))
        for (type in listOf(FilterType.BAND_PASS, FilterType.ALL_PASS)) {
            val refused = s.plan(eq().copy(bands = listOf(Band("x", type, 8000.0, 0.0, 0.707)))) as DevicePlan.Rejected
            assertTrue(refused.issues.single().contains("$type is not supported by Moondrop Echo-A"), refused.issues.toString())
        }
        assertTrue(s.plan(eq()) is DevicePlan.Ready)
    }

    /** The same EQ through the Micro and the Echo-A: every report, request and pause is the same. */
    @Test fun `the Echo-A's frames are the Micro's byte for byte for the same EQ, HS emulation and preamp included`() {
        fun rich(preamp: Double?) = Profile("id", "t", bands = listOf(
            Band("a", FilterType.LOW_SHELF, 105.0, 6.0, 0.707),
            Band("h", FilterType.HIGH_SHELF, 8000.0, -4.0, 0.707),
            Band("b", FilterType.PEAK, 250.0, 10.0, 1.0),
            Band("c", FilterType.PEAK, 1000.0, -3.0, 2.0)), preampDb = preamp, createdAt = 0, updatedAt = 0)
        for (preamp in listOf(-6.0, 0.0, null)) {
            val e = rich(preamp)
            val micro = FakeDac(WalkPlayProfiles.micro, 101)
            val echo = FakeDac(WalkPlayProfiles.echoA, 101)
            val microSession = WalkPlaySession(WalkPlayProfiles.micro, micro)
            val echoSession = WalkPlaySession(WalkPlayProfiles.echoA, echo)
            val microPlan = microSession.plan(e) as DevicePlan.Ready
            assertEquals(microPlan, echoSession.plan(e), "plan, preamp $preamp")
            val m = microSession.write(e, explicitHold = true)
            val a = echoSession.write(e, explicitHold = true)
            assertTrue(m.verified && a.verified, "${m.reason} / ${a.reason}")
            assertEquals(micro.events, echo.events, "frames, pauses and requests, preamp $preamp")
            assertEquals(micro.sentReports(), echo.sentReports())
        }
        // the HS band went out as a LOW SHELF with the negated gain, its gain in the preamp (the Micro's rule, not the Echo-A's own)
        val e = rich(-6.0)
        val plan = WalkPlaySession(WalkPlayProfiles.echoA, FakeDac(WalkPlayProfiles.echoA)).plan(e) as DevicePlan.Ready
        assertEquals(WalkPlay.TYPE_LSQ, plan.bands[1].typeCode)
        assertEquals(4.0, plan.bands[1].gainDb)
        assertEquals(-10, plan.preampDb)
    }

    @Test fun `the Echo-A's limits are the record's, gain 10 dB, Q 0_1 to 10, 20 to 20000 Hz, 8 bands, preamp -30 to 0 whole dB`() {
        val p = WalkPlayProfiles.echoA
        val session = WalkPlaySession(p, FakeDac(p))
        fun plan(vararg bands: Band, preamp: Double? = -4.0) = session.plan(Profile("id", "t", bands = bands.toList(), preampDb = preamp, createdAt = 0, updatedAt = 0))
        fun pk(f: Double = 1000.0, g: Double = 3.0, q: Double = 1.0) = Band("b", FilterType.PEAK, f, g, q)
        assertEquals(-10.0..10.0, p.caps.gainMinDb..p.caps.gainMaxDb)
        assertEquals(0.1..10.0, p.caps.qMin..p.caps.qMax)
        assertEquals(20.0..20000.0, p.caps.freqMinHz..p.caps.freqMaxHz)
        // the edges are accepted
        for (b in listOf(pk(g = 10.0), pk(g = -10.0), pk(q = 0.1), pk(q = 10.0), pk(f = 20.0), pk(f = 20000.0))) assertTrue(plan(b) is DevicePlan.Ready, b.toString())
        assertTrue(plan(*Array(8) { pk(f = 100.0 * (it + 1), g = 0.0) }) is DevicePlan.Ready)
        assertTrue(plan(pk(), preamp = 0.0) is DevicePlan.Ready); assertTrue(plan(pk(), preamp = -30.0) is DevicePlan.Ready)
        // one step outside is refused with the numbers, by name, and nothing is clamped into a different filter
        val outside = mapOf(
            "10.1 dB" to pk(g = 10.1), "-10.1 dB" to pk(g = -10.1), "Q 0.05" to pk(q = 0.05), "Q 10.5" to pk(q = 10.5),
            "19 Hz" to pk(f = 19.0), "20001 Hz" to pk(f = 20001.0), "NaN gain" to pk(g = Double.NaN))
        for ((what, b) in outside) {
            val r = plan(b) as? DevicePlan.Rejected
            assertNotNull(r, what)
            assertTrue(r.issues.isNotEmpty(), what)
        }
        assertTrue((plan(pk(g = 10.1)) as DevicePlan.Rejected).issues.single().contains("outside -10..10 dB"))
        assertTrue((plan(pk(q = 0.05)) as DevicePlan.Rejected).issues.single().contains("Q 0.05 outside 0.1-10"))
        assertTrue((plan(pk(f = 19.0)) as DevicePlan.Rejected).issues.single().contains("19 Hz outside 20-20000 Hz"))
        assertTrue(plan(*Array(9) { pk(f = 100.0 * (it + 1), g = 0.0) }) is DevicePlan.Rejected)
        assertTrue(plan(pk(), preamp = 1.0) is DevicePlan.Rejected)
        assertTrue(plan(pk(), preamp = -31.0) is DevicePlan.Rejected)
        assertTrue(plan(pk(), preamp = Double.NaN) is DevicePlan.Rejected)
    }

    @Test fun `TRN frames are the ones of the PC write that its unit accepted`() {
        val port = FakeDac(WalkPlayProfiles.trn, 0)
        WalkPlaySession(WalkPlayProfiles.trn, port).write(eq(preamp = -12.0), explicitHold = true)
        val tx = port.sentReports()
        // TrnWriteTest.matrix[0] (LSQ 105 Hz +6 Q 0.707) and [1] (PK 250 Hz +10 Q 1), then the commit, preamp -12, EQ flash and offset flash of that write; the shelf's Q register is
        // now intended Q x sqrt2 = 256 (bytes 00 01, the unit played the register / sqrt2 in the acoustic run of 25.10.2026), everything else of its frame is the accepted one
        val band0 = hex(report("4b 01 09 21 00 00 00 00 12 21 21 40 41 c5 a0 80 1e 30 3f 3f 31 80 5f 7f 42 f4 9f c0 69 00 00 01 00 06 01 00 00"))
        val band1 = hex(report("4b 01 09 21 00 01 00 00 78 3b a2 40 54 6c 9a 80 fa b5 c7 3e ac 93 65 7f 8e 0e 96 c0 fa 00 00 01 00 0a 02 00 00"))
        val tail = listOf("4b 01 0a 04 00 00 ff ff", "4b 01 03 02 00 f4", "4b 01 01 00", "4b 01 04 00").map { hex(report(it)) }
        val first = tx.indexOf(band0)
        assertTrue(first > 0, "band 1 frame not sent")
        assertEquals(band1, tx[first + 1])
        val commit = tx.indexOf(tail[0])
        assertEquals(first + 10, commit) // ten band frames, then the commit
        assertEquals(tail, tx.subList(commit, commit + 4))
        // the write ends with these four, then only the read-back requests follow
        assertEquals(readFrames(DeviceProtocol.TRN), tx.drop(commit + 4))
        assertEquals("pause:25", port.events[port.events.indexOf("tx:$band0") + 1]) // the proven 25 ms after each band frame
    }

    @Test fun `a missing band is asked for again on the TRN after three attempts, on the Micro the read fails`() {
        val trn = FakeDac(WalkPlayProfiles.trn).also { it.silent[1] = 3 }
        val s = WalkPlaySession(WalkPlayProfiles.trn, trn).read()
        assertEquals(10, s.bands.size)
        assertEquals(4, trn.sentReports().count { it == hex(WalkPlay.bandRequest(1)) })
        val micro = FakeDac(WalkPlayProfiles.micro).also { it.silent[1] = 3 }
        val e = assertFailsWith<IOException> { WalkPlaySession(WalkPlayProfiles.micro, micro).read() }
        assertEquals("the DAC did not answer band 1", e.message)
        assertEquals(3, micro.sentReports().count { it == hex(WalkPlay.bandRequest(1)) })
    }

    @Test fun `the frames planned for a progress bar are the frames a write sends, for every protocol`() {
        for ((profile, slot) in listOf(WalkPlayProfiles.micro to 101, WalkPlayProfiles.max to 101, WalkPlayProfiles.trn to 0)) {
            val port = FakeDac(profile, slot)
            val session = WalkPlaySession(profile, port)
            val plan = session.plan(eq()) as DevicePlan.Ready
            session.write(eq(), explicitHold = true)
            assertEquals(session.plannedFrames(plan), port.sentReports().size, profile.id)
            assertEquals(profile, WalkPlayProfiles.of(profile.protocol))
        }
    }

    @Test fun `A-B writes only the supplied bands and TEMP_WRITE through the session, on the Micro, and nowhere else`() {
        val bands = listOf(WalkPlay.BandWrite(1, 250.0, -3.0, 1.0, 2), WalkPlay.BandWrite(4, 1000.0, 2.0, 1.4, 2))
        val micro = FakeDac(WalkPlayProfiles.micro, 101)
        WalkPlaySession(WalkPlayProfiles.micro, micro).writeTemporary(bands, 101, explicitHold = true)
        val want = WalkPlay.temporaryBandSequence(bands, 101).flatMap { listOf("tx:${hex(it.report)}") + if (it.delayAfterMs > 0) listOf("pause:${it.delayAfterMs}") else emptyList() }
        assertEquals(want, micro.events)
        val max = FakeDac(WalkPlayProfiles.max)
        assertFailsWith<IllegalArgumentException> { WalkPlaySession(WalkPlayProfiles.max, max).writeTemporary(bands, 0, explicitHold = true) }
        assertFailsWith<IllegalArgumentException> { WalkPlaySession(WalkPlayProfiles.micro, micro).writeTemporary(bands, 101, explicitHold = false) }
        assertTrue(max.events.isEmpty())
    }

    @Test fun `the probe's request gets the reply of its command, or null when the DAC stays silent after three attempts`() {
        val port = FakeDac(WalkPlayProfiles.micro, 101).also { it.silent[1] = 3 }
        val session = WalkPlaySession(WalkPlayProfiles.micro, port)
        val answered = session.probeRequest(WalkPlay.report(WalkPlay.READ, WalkPlay.CMD_VERSION, WalkPlay.END), WalkPlay.CMD_VERSION)
        assertNotNull(answered)
        assertNull(session.probeRequest(WalkPlay.bandRequest(1), WalkPlay.CMD_PEQ))
        assertEquals(3, port.sentReports().count { it == hex(WalkPlay.bandRequest(1)) })
    }

    @Test fun `a band the DAC did not keep is a mismatch naming it, and nothing is written without HOLD TO SEND`() {
        val port = FakeDac(WalkPlayProfiles.max, 101).also { it.ignoredBands += 2 }
        val r = WalkPlaySession(WalkPlayProfiles.max, port).write(eq(), explicitHold = true)
        assertFalse(r.verified)
        assertTrue(r.reason!!.startsWith("band 3: wrote "), r.reason)
        val quiet = FakeDac(WalkPlayProfiles.max)
        assertFailsWith<IllegalArgumentException> { WalkPlaySession(WalkPlayProfiles.max, quiet).write(eq(), explicitHold = false) }
        assertTrue(quiet.events.isEmpty())
        // a plan the protocol refuses throws before the first report
        val refused = WalkPlaySession(WalkPlayProfiles.max, quiet)
        assertFailsWith<IOException> { refused.write(eq(preamp = 3.0), explicitHold = true) }
        assertTrue(quiet.events.isEmpty())
    }

    /** Counts the output reports that leave the host through [send]; a read is a request and never one. */
    private class CountingPort(val inner: NativePort) : NativePort by inner {
        var sends = 0
        override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) { sends++; inner.send(id, kind, payload) }
    }

    @Test fun `the Echo-A is read through the session with nothing sent to it, its state is read only, and the target has no write path`() {
        val dac = FakeDac(WalkPlayProfiles.echoA, slot = 101)
        val port = CountingPort(dac)
        val read = FamilyDevices.readEchoA(port) // the only Echo-A call the app has
        val ro = WalkPlayProfiles.echoA.copy(hardwareVerified = false) // the same read shown by an Echo-A whose write flag is off
        val state = FamilyState.EchoA(ro, read.snapshot)
        assertEquals(0, port.sends)               // firmware, slot, bands and preamp are all requests: no output report left the host
        assertTrue(dac.sentReports().isNotEmpty())
        assertEquals(101, state.slot)
        assertTrue(state.snapshot.firmware.isNotEmpty())
        assertEquals(8, state.snapshot.bands.size)
        assertFalse(state.importable)
        assertTrue(state.readings().isEmpty())
        assertTrue(state.describe().startsWith("Moondrop Echo-A: READ ONLY - not verified on hardware"), state.describe())
        assertFalse(state.holds(eq())); assertFalse(state.matches(eq()))
        assertFailsWith<IllegalStateException> { state.importExact() }
        // the target: no session of any family is behind it, every profile is refused, HOLD TO SEND says READ ONLY - NOT VERIFIED
        val t = DeviceTarget.forEcho(ro)
        assertTrue(t.readOnly && t.isFamily)
        assertNull(t.walkplay); assertNull(t.fiio); assertNull(t.moondrop); assertNull(t.ktMicro)
        val refusal = listOf(DeviceTarget.READ_ONLY_ISSUE.format(t.caps.name))
        assertEquals(refusal, t.issues(eq()))
        assertEquals(refusal, FamilyDevices.stateIssues(t, state))
        assertEquals(refusal, FamilyDevices.stateIssues(t, null))
        assertEquals("READ ONLY - NOT VERIFIED", FamilyDevices.READ_ONLY_LABEL)
        assertTrue(t.settings.isEmpty())
    }

    /** The signed Q30 values of a biquad write ([WalkPlay.computeIir]): b0, b1, b2, -a1, -a2. */
    private fun q30(b: ByteArray) = IntArray(5) { i -> (0..3).fold(0) { acc, k -> acc or ((b[i * 4 + k].toInt() and 0xFF) shl (8 * k)) } }

    /** Plain RBJ low-pass / high-pass at the 96 kHz design rate, as the same five Q30 numbers. */
    private fun rbj(code: Int, f: Double, q: Double): IntArray {
        val w0 = 2 * Math.PI * f / WalkPlay.DESIGN_FS
        val c = Math.cos(w0)
        val alpha = Math.sin(w0) / (2 * q)
        val b0 = if (code == WalkPlay.TYPE_LP) (1 - c) / 2 else (1 + c) / 2
        val b1 = if (code == WalkPlay.TYPE_LP) 1 - c else -(1 + c)
        val a0 = 1 + alpha
        val v = doubleArrayOf(b0 / a0, b1 / a0, b0 / a0, 2 * c / a0, -(1 - alpha) / a0)
        return IntArray(5) { Math.round(v[it] * (1 shl 30)).toInt() }
    }

    private fun magnitudeDb(k: IntArray, f: Double): Double {
        val w = 2 * Math.PI * f / WalkPlay.DESIGN_FS
        val s = 1.0 / (1 shl 30)
        val b = DoubleArray(5) { k[it] * s }
        val nr = b[0] + b[1] * Math.cos(w) + b[2] * Math.cos(2 * w); val ni = -(b[1] * Math.sin(w) + b[2] * Math.sin(2 * w))
        val dr = 1 - b[3] * Math.cos(w) - b[4] * Math.cos(2 * w); val di = b[3] * Math.sin(w) + b[4] * Math.sin(2 * w)
        return 10 * Math.log10((nr * nr + ni * ni) / (dr * dr + di * di))
    }

    @Test fun `the Echo-A takes LOW PASS and HIGH PASS as plain RBJ filters with no gain, the Micro takes them too`() {
        val echo = WalkPlayProfiles.echoA
        val micro = WalkPlayProfiles.micro
        for ((type, code) in listOf(FilterType.LOW_PASS to WalkPlay.TYPE_LP, FilterType.HIGH_PASS to WalkPlay.TYPE_HP)) {
            // a pass filter has no gain: the stored 12 dB is not checked against the +-10 dB range and 0 goes out
            val e = Profile("id", "t", bands = listOf(Band("p", type, 1000.0, 12.0, 0.7071)), preampDb = 0.0, createdAt = 0, updatedAt = 0)
            val plan = WalkPlaySession(echo, FakeDac(echo)).plan(e) as DevicePlan.Ready
            val w = plan.bands[0]
            assertEquals(code, w.typeCode); assertEquals(0.0, w.gainDb)
            val got = q30(WalkPlay.computeIir(w.freq, w.gainDb, w.q, w.typeCode))
            val want = rbj(code, 1000.0, 0.7071)
            for (i in 0..4) assertTrue(Math.abs(got[i] - want[i]) <= 1, "$type coefficient $i: ${got[i]} vs ${want[i]}")
            // the curve: 3 dB down at the corner for Q 0.7071, flat in the pass band, 12 dB per octave beyond
            val low = type == FilterType.LOW_PASS
            assertEquals(-3.01, magnitudeDb(got, 1000.0), 0.1)
            assertEquals(0.0, magnitudeDb(got, if (low) 100.0 else 10000.0), 0.1)
            assertTrue(magnitudeDb(got, if (low) 10000.0 else 100.0) < -35.0)
            // the Micro takes them too since the desk run of 25.10.2026 (PASS_FILTERS_VERIFIED); the Max does not
            assertTrue(WalkPlaySession(micro, FakeDac(micro)).plan(e) is DevicePlan.Ready)
            // write, read back, import: the band comes back as the same pass filter at 0 dB
            val dac = FakeDac(echo, 101)
            val session = WalkPlaySession(echo, dac)
            val r = session.write(e, explicitHold = true)
            assertTrue(r.verified, r.reason)
            val imported = session.importExact(r.readback!!) as DacImport.Ready
            assertEquals(type, imported.eq.bands.single().type)
            assertEquals(0.0, imported.eq.bands.single().gainDb)
            assertTrue(WalkPlaySession(micro, FakeDac(micro)).importExact(r.readback!!) is DacImport.Ready)
        }
    }

    @Test fun `with the flag on the Echo-A target plans, compares and imports like the Micro with its own limits, with it off it is read only`() {
        val roTarget = DeviceTarget.forEcho(WalkPlayProfiles.echoA.copy(hardwareVerified = false))
        assertTrue(roTarget.readOnly)
        val on = WalkPlayProfiles.echoA.copy(hardwareVerified = true)
        val t = DeviceTarget.forEcho(on)
        assertFalse(t.readOnly); assertTrue(t.isFamily); assertNull(t.walkplay)
        assertEquals(emptyList(), t.issues(eq()))
        assertTrue(t.issues(eq().copy(bands = listOf(Band("x", FilterType.PEAK, 1000.0, 10.5, 1.0)))).single().contains("10.5 dB outside"))
        assertTrue(t.issues(eq().copy(bands = listOf(Band("x", FilterType.BAND_PASS, 1000.0, 0.0, 1.0)))).single().contains("BAND_PASS"))
        // the preamp rules are the Micro's (a HIGH SHELF boost moves into the preamp), the read-only target keeps the plain range
        val hs4 = listOf(Band("h", FilterType.HIGH_SHELF, 8000.0, 4.0, 0.707))
        assertTrue(roTarget.preampFits(hs4, 0.0)); assertFalse(t.preampFits(hs4, 0.0)); assertTrue(t.preampFits(hs4, -6.0))
        assertEquals(0.0, roTarget.shelfOffset(hs4)); assertEquals(4.0, t.shelfOffset(hs4))
        // a write, the read-back, and the state that read says
        val dac = FakeDac(on, 101)
        val session = WalkPlaySession(on, dac)
        val r = session.write(eq(), explicitHold = true)
        assertTrue(r.verified, r.reason)
        val state = FamilyState.EchoA(on, r.readback!!)
        assertTrue(state.importable)
        assertTrue(state.matches(eq())); assertTrue(state.holds(eq()))
        assertFalse(state.matches(eq(preamp = -8.0))); assertTrue(state.holds(eq(preamp = -8.0)))
        assertFalse(state.holds(eq().copy(bands = eq().bands.drop(1))))
        assertEquals(3, state.importExact().bands.size)
        assertEquals(emptyList(), FamilyDevices.stateIssues(t, state))
        assertEquals(listOf(FamilyDevices.READ_FIRST), FamilyDevices.stateIssues(t, null))
        assertTrue(state.describe().startsWith("Moondrop Echo-A: write enabled\n"), state.describe())
        // and the same state of the read-only profile never matches, holds or opens
        val offProfile = WalkPlayProfiles.echoA.copy(hardwareVerified = false)
        val off = FamilyState.EchoA(offProfile, r.readback!!)
        assertFalse(off.importable); assertFalse(off.matches(eq())); assertFalse(off.holds(eq()))
        assertFailsWith<IllegalStateException> { off.importExact() }
        assertEquals(listOf(DeviceTarget.READ_ONLY_ISSUE.format("Moondrop Echo-A")), FamilyDevices.stateIssues(DeviceTarget.forEcho(offProfile), off))
    }

    /** A bench log (`TX <report>` then its `RX <report>`) as a port that only answers requests: the host must send exactly the logged requests. */
    private class LogReplay(text: String) : NativePort {
        class Frame(val tx: ByteArray, val rx: MutableList<ByteArray> = ArrayList())
        override val deviceKey = "echo"
        val frames = ArrayList<Frame>()
        var next = 0
        init {
            text.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.forEach { line ->
                val body = line.substringAfter(' ').split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()
                when (line.substringBefore(' ')) {
                    "TX" -> frames += Frame(body)
                    "RX" -> frames.last().rx += body
                    else -> error("Not a bench log line: $line")
                }
            }
        }
        override fun guard() {}
        override fun pause(ms: Long) {}
        override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) = error("a read sends no output report: ${hex(payload)}")
        override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
            assertTrue(next < frames.size, "the session sends more than the log holds: ${hex(payload)}")
            val f = frames[next++]
            val sent = byteArrayOf(id.toByte()) + payload
            assertTrue(f.tx.size >= sent.size && sent.indices.all { f.tx[it] == sent[it] } && (sent.size until f.tx.size).all { f.tx[it] == 0.toByte() },
                "frame $next: the session sends ${hex(sent)}, the log has ${hex(f.tx)}")
            return f.rx.map { it.copyOfRange(1, it.size) }.first(matches)
        }
    }

    /** REAL: the PC read of the unit on 21.10.2026 (DacLab run 2026-10-21-moondrop-echo-a-02, `read-plan`): the factory flat state, identical to the Micro's. */
    @Test fun `the real Echo-A read log replays frame for frame and reads as the Micro's factory flat state`() {
        val text = requireNotNull(WalkPlaySessionTest::class.java.getResourceAsStream("/bench/echo-a-read-195126.log")) { "echo-a-read-195126.log" }.use { it.readBytes() }.decodeToString()
        val port = LogReplay(text)
        val snapshot = WalkPlaySession(WalkPlayProfiles.echoA, port).read()
        assertEquals(port.frames.size, port.next) // version, band 0 (the slot), bands 0-7, preamp: nothing left over
        assertEquals(11, port.frames.size)
        assertEquals("0.1", snapshot.firmware)
        assertEquals(8, snapshot.bands.size)
        val first = snapshot.bands[0].registers
        assertEquals(31, first.freq); assertEquals(192, first.q256); assertEquals(0, first.gain256); assertEquals(WalkPlay.TYPE_PK, first.typeCode)
        assertEquals(0, snapshot.preampDb)
        val regs = snapshot.bands.map { it.registers }
        assertTrue(ProtocolMicro.isFlat(regs, snapshot.preampDb))
        // the flat state opens as one flat band; the state says the flat profile is on the DAC when the write is enabled, and shows it read only when not
        val on = WalkPlayProfiles.echoA.copy(hardwareVerified = true)
        val imported = WalkPlaySession(on, port).importExact(snapshot) as DacImport.Ready
        assertEquals(1, imported.eq.bands.size)
        assertEquals(FilterType.PEAK, imported.eq.bands[0].type); assertEquals(0.0, imported.eq.bands[0].gainDb)
        val flat = Profile("f", "flat", bands = listOf(Band("a", FilterType.PEAK, 31.0, 0.0, 0.75)), preampDb = 0.0, createdAt = 0, updatedAt = 0)
        assertTrue(FamilyState.EchoA(on, snapshot).matches(flat))
        assertFalse(FamilyState.EchoA(WalkPlayProfiles.echoA.copy(hardwareVerified = false), snapshot).matches(flat))
    }
}
