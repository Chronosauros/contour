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

private fun hx(b: ByteArray) = b.joinToString(" ") { "%02x".format(it.toInt() and 255) }
private fun bytesOf(s: String) = s.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

/**
 * The FreeDSP Mini's PEAK write to Custom EQ (slot 7) of the tester build 1.8.3-freedsptest2: [MoondropSession.writeMini] and the port allow-list in front of it.
 *
 * REAL: [TESTER_RX], the eight band replies a tester's unit gave on 28.11.2026 (issue 2, test 1 log, MOONDROP's app had written them: firmware 0.7, slot 7, PEAK 31/62/125/200/500/1000/3000/10000 Hz),
 * bytes 0-35 of each reply, report ID excluded. SYNTHETIC: [MiniUnit], a fake unit built on them; it checks the frames and their order and holds a RAM bank and a flash bank, it is not firmware.
 */
class FreeDspMiniWriteTest {
    companion object {
        val TESTER_RX = listOf(
            "80 09 03 00 00 00 00 00 00 00 40 98 54 2c 80 a7 bc d3 3f 68 ab d3 7f 59 43 2c c0 1f 00 c0 00 00 00 02 00 07",
            "80 09 03 00 01 00 00 00 00 00 40 f6 ac 58 80 eb 97 a7 3f 0a 53 a7 7f 15 68 58 c0 3e 00 c0 00 00 00 02 00 07",
            "80 09 03 00 02 00 00 00 00 00 40 47 d7 b2 80 ef 3f 4e 3f b9 28 4d 7f 11 c0 b1 c0 7d 00 c0 00 00 00 02 00 07",
            "80 09 03 00 03 00 00 24 b3 b6 3f 3c ba f8 81 4a 56 53 3e c4 45 07 7e 92 f6 f5 c1 c8 00 80 00 00 fd 02 00 07",
            "80 09 03 00 04 00 00 3a f0 ad 3f 35 a8 77 82 b7 9e eb 3d cb 57 88 7d 0f 71 66 c2 f4 01 00 01 4d fd 02 00 07",
            "80 09 03 00 05 00 00 0d e1 a6 3e 66 80 7c 89 ca b7 1d 38 9a 7f 83 76 2a 67 3b c9 e8 03 80 00 00 fd 02 00 07",
            "80 09 03 00 06 00 00 ab 59 7b 3c 64 ce 16 9a 07 f6 6c 2b 9c 31 e9 65 4e b0 17 d8 b8 0b 80 00 00 fd 02 00 07",
            "80 09 03 00 07 00 00 1f 4c 27 38 d9 83 14 c5 f5 04 1d 12 27 7c eb 3a ec ae bb f5 10 27 80 00 00 fd 02 00 07",
        )
        /** Freq Hz, Q Q8.8, gain Q8.8 of each tester band, read from the reply bytes 27-32. */
        val TUPLES: List<Triple<Int, Int, Int>> = TESTER_RX.map { bytesOf(it).let { p ->
            Triple((p[27].toInt() and 255) or ((p[28].toInt() and 255) shl 8), ((p[29].toInt() and 255) or (p[30].toInt() shl 8)).toShort().toInt(), ((p[31].toInt() and 255) or (p[32].toInt() shl 8)).toShort().toInt())
        } }
        const val VERSION = "80 0c 01 30 2e 37"
        const val COMMIT = "01 0a 04 00 00 ff ff"
        const val SAVE = "01 01 00"
        /** The frame the maker's tool sends for band [i] holding the reply [rx]: `01 09 21 00 i 00 00`, reply bytes 7-35, `00`. */
        fun frameFor(i: Int, rx: String): String = "01 09 21 00 0$i 00 00 " + bytesOf(rx).copyOfRange(7, 36).let { hx(it) } + " 00"
    }

    private val mini = requireNotNull(MoondropCatalog.find(0x35D8, 0x98D4, null))
    private val dawn = MoondropCatalog.dawnPro2
    private val readFrames = listOf("80 0c 01 00") + (0..7).map { "80 09 03 00 0$it 00" } + listOf("80 23 00")
    private val bandReads = (0..7).map { "80 09 03 00 0$it 00" }

    /** A fake FreeDSP Mini built on the tester's replies, with a RAM bank (what reads return), a flash bank, staged band frames and the faults the tests ask for. */
    private class MiniUnit(
        slot: Int = 7,
        val silentBands: Set<Int> = emptySet(),
        /** Band frames the unit drops (it does not stage them). */
        val ignoreBandFrames: Set<Int> = emptySet(),
        /** Bands whose first read after the commit gets no reply (a lost reply). */
        val loseFirstReadAfterCommit: Set<Int> = emptySet(),
        /** Bands that never answer a read after the commit. */
        val silentAfterCommit: Set<Int> = emptySet(),
        /** The pre-gain query gets no reply. */
        val silentPre: Boolean = false,
    ) : NativePort {
        override val deviceKey = "mini"
        val ram = TESTER_RX.map { bytesOf(it).copyOf(63).also { r -> r[35] = slot.toByte() } }.toMutableList()
        val flash = ram.map { it.copyOf() }.toMutableList()
        private val staged = arrayOfNulls<ByteArray>(8)
        val sent = ArrayList<ByteArray>()
        val events = ArrayList<String>()
        val notes = ArrayList<String>()
        var guards = 0
        private var committed = false
        private val lost = HashSet<Int>()
        override fun guard() { guards++ }
        override fun pause(ms: Long) { events += "pause $ms" }
        override fun log(message: String) { notes += message }
        override fun payloadSize(id: Int, kind: NativeHidReports.Kind) = 63
        val writeFrames: List<String> get() = sent.filter { (it[0].toInt() and 255) == 0x01 }.map { hx(it) }
        fun replug() { for (i in 0 until 8) ram[i] = flash[i].copyOf(); for (i in 0 until 8) staged[i] = null }

        override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) {
            sent += payload.copyOf(); events += "tx ${hx(payload)}"
            val h = hx(payload)
            when {
                h == COMMIT -> { for (i in 0 until 8) staged[i]?.let { ram[i] = it }; staged.fill(null); committed = true }
                h == SAVE -> for (i in 0 until 8) flash[i] = ram[i].copyOf()
                payload.size == 37 && payload[0].toInt() == 1 && payload[1].toInt() == 9 && payload[2].toInt() == 0x21 && payload[33].toInt() == 2 && payload[35].toInt() == 7 -> {
                    val i = payload[4].toInt()
                    if (i !in ignoreBandFrames) staged[i] = ByteArray(63).also { r -> payload.copyInto(r, 7, 7, 36); r[0] = 0x80.toByte(); r[1] = 9; r[2] = 3; r[4] = i.toByte() }
                }
                else -> error("a frame the FreeDSP Mini must never be sent: $h")
            }
        }

        override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
            assertEquals(0x4B, id)
            sent += payload.copyOf(); events += "tx ${hx(payload)}"
            check((payload[0].toInt() and 255) == 0x80) { "not a read: ${hx(payload)}" }
            val timeout = IOException("Native request timed out or returned invalid state")
            val out = when (payload[1].toInt()) {
                0x0c -> bytesOf(VERSION).copyOf(63)
                0x23 -> if (silentPre) throw timeout else bytesOf("80 23 02 00 fe").copyOf(63)
                0x09 -> {
                    check(payload.size == 6 && payload[2].toInt() == 3) { "not FreeDSP-Studio's band query: ${hx(payload)}" }
                    val i = payload[4].toInt()
                    if (i in silentBands) throw timeout
                    if (committed && i in silentAfterCommit) throw timeout
                    if (committed && i in loseFirstReadAfterCommit && lost.add(i)) throw timeout
                    ram[i].copyOf()
                }
                else -> error("the FreeDSP Mini is not asked this: ${hx(payload)}")
            }
            if (!matches(out)) throw timeout
            return out
        }
    }

    private fun session(unit: NativePort) = MoondropSession(mini, unit)
    /** A read of [unit] (it goes on the unit's `sent` list like any read). */
    private fun readOf(unit: MiniUnit) = MoondropSession(mini, unit).readMini()
    /** The eight bands of a fresh tester unit as the editor holds them after FROM DAC. */
    private fun editorBands(): List<Band> = FamilyState.FreeDspMini(mini, readOf(MiniUnit())).importExact().bands
    private fun profile(bands: List<Band>) = Profile("id", "t", bands = bands, preampDb = 0.0, createdAt = 0, updatedAt = 0)

    // ---- the golden: Contour's Mini band = the bytes MOONDROP's app wrote -----------------------------------------------------------------

    @Test fun `the Mini band frame designed from the rounded registers equals the tester's reply bytes 7-35 for all eight bands`() {
        assertEquals(listOf(31, 62, 125, 200, 500, 1000, 3000, 10000), TUPLES.map { it.first })
        assertEquals(-691, TUPLES[4].third)
        TUPLES.forEachIndexed { i, (f, q8, g8) ->
            val w = FreeDspMiniCodec.registerBand(i, f, q8, g8)
            val payload = FreeDspMiniCodec.bandPayload(w)
            assertEquals(37, payload.size)
            val rx = bytesOf(TESTER_RX[i])
            assertEquals(hx(rx.copyOfRange(7, 36)), hx(payload.copyOfRange(7, 36)), "band $i: coefficients, freq, Q, gain, type, 00, slot")
            assertEquals(frameFor(i, TESTER_RX[i]), hx(payload), "the whole frame, from the log")
            assertEquals("01 09 21 00 0$i 00 00", hx(payload.copyOfRange(0, 7)))
            // through the editor's values: the register values as doubles give the same band
            val viaBand = FreeDspMiniCodec.bandPayload(FreeDspMiniCodec.bandWrite(i, Band("b", FilterType.PEAK, f.toDouble(), g8 / 256.0, q8 / 256.0)))
            assertEquals(hx(payload), hx(viaBand))
        }
        // what an editor shows for raw -691 is -2.7 dB: typing it back gives the same register and the same bytes (the biquad is designed from the register, not from -2.7)
        assertEquals(frameFor(4, TESTER_RX[4]), hx(FreeDspMiniCodec.bandPayload(FreeDspMiniCodec.bandWrite(4, Band("b", FilterType.PEAK, 500.0, -2.7, 1.0)))))
        // the Dawn Pro 2's own design from the values as set is another thing: -2.7 dB designed unrounded differs, and it is not what the Mini gets
        val dawnBand = MoondropCodec.bandPayload(MoondropCodec.bandWrite(4, Band("b", FilterType.PEAK, 500.0, -2.7, 1.0)), 7)
        assertTrue(hx(dawnBand.copyOfRange(7, 27)) != hx(bytesOf(TESTER_RX[4]).copyOfRange(7, 27)), "the Dawn's bandWrite is not the Mini's")
    }

    @Test fun `the Dawn Pro 2 band write is untouched by the Mini path`() {
        val b = Band("b", FilterType.PEAK, 500.0, -2.7, 1.0)
        val w = MoondropCodec.bandWrite(4, b)
        assertEquals(-691, w.gainQ8)
        assertEquals(hx(WalkPlay.computeIir(500.0, -2.7, 1.0, 2)), hx(w.coefficients), "designed from the values as set")
    }

    // ---- write back unchanged, a change, the faults ----------------------------------------------------------------------------------

    @Test fun `writing back what the unit holds sends the eight band frames, the commit and the save, in this order, and is verified`() {
        val unit = MiniUnit()
        val bands = editorBands()
        assertEquals(8, bands.size)
        val s = session(unit)
        val r = s.writeMini(bands, explicitHold = true)
        assertTrue(r.verified, r.reason); assertNull(r.reason)
        val expectedWrites = (0..7).map { frameFor(it, TESTER_RX[it]) } + listOf(COMMIT, SAVE)
        assertEquals(expectedWrites, unit.writeFrames)
        // the whole TX list: the read before, the eight bands, the commit, the eight reads of them, the save, the read after
        assertEquals(readFrames + (0..7).map { frameFor(it, TESTER_RX[it]) } + listOf(COMMIT) + bandReads + listOf(SAVE) + readFrames, unit.sent.map { hx(it) })
        // the pacing: 25 ms or more after each band, 50 after the commit, 200 after the save
        val ev = unit.events
        for (i in 0..7) { val at = ev.indexOf("tx ${frameFor(i, TESTER_RX[i])}"); assertTrue(ev[at + 1].removePrefix("pause ").toLong() >= 25, "after band $i") }
        assertTrue(ev[ev.indexOf("tx $COMMIT") + 1].removePrefix("pause ").toLong() >= 50)
        assertTrue(ev[ev.indexOf("tx $SAVE") + 1].removePrefix("pause ").toLong() >= 200)
        // the unit holds the same bytes in RAM and flash, and the read-back is what a read gives
        assertEquals(unit.ram.map { hx(it) }, unit.flash.map { hx(it) })
        assertEquals(TUPLES, r.readback!!.bands.map { Triple(it!!.frequencyHz, it.qQ8, it.gainQ8) })
        assertEquals(7, r.readback!!.slot)
        assertNotNull(s.lastMiniBefore); assertEquals(8, s.lastMiniExpected!!.size)
        assertTrue(unit.notes.any { it.startsWith("FreeDSP Mini write: slot found 7") }, unit.notes.toString())
        assertTrue(unit.notes.any { it.contains("save sent") }); assertEquals(8, unit.notes.count { it.contains("read back = sent") })
        assertTrue(unit.notes.any { it.contains("read after the save: the eight bands equal what was sent") })
        // none of the frames the Mini must never see
        assertTrue(unit.sent.none { hx(it).let { h -> h.startsWith("01 0f") || h.startsWith("01 23") || h.startsWith("01 03") || h.startsWith("01 04") || h.startsWith("01 05") || h.startsWith("01 ff") || h.startsWith("80 0f") || h.startsWith("80 03") } })
    }

    @Test fun `1000 Hz at -1 dB on one band is verified, the read-back shows it, and only that band's bytes changed`() {
        val unit = MiniUnit()
        val bands = editorBands().toMutableList()
        assertEquals(1000.0, bands[5].freqHz)
        bands[5] = bands[5].copy(gainDb = -1.0)
        val r = session(unit).writeMini(bands, explicitHold = true)
        assertTrue(r.verified, r.reason)
        val w = FreeDspMiniCodec.registerBand(5, 1000, TUPLES[5].second, -256)
        assertEquals(hx(FreeDspMiniCodec.bandPayload(w)), unit.writeFrames[5])
        assertEquals(listOf(1000 to -256), listOf(r.readback!!.bands[5]!!.let { it.frequencyHz to it.gainQ8 }))
        assertEquals(-1.0, r.readback!!.bands[5]!!.gainDb)
        for (i in listOf(0, 1, 2, 3, 4, 6, 7)) assertEquals(frameFor(i, TESTER_RX[i]), unit.writeFrames[i], "band $i is written back as it was")
        assertEquals(unit.ram.map { hx(it) }, unit.flash.map { hx(it) }, "saved")
        assertTrue(unit.writeFrames.last() == SAVE)
        // a replug brings back what was saved: the new band 5
        unit.replug(); assertEquals(-256, readOf(unit).bands[5]!!.gainQ8)
    }

    @Test fun `a unit that ignores one band frame reads back different, nothing is saved and the outcome says so`() {
        val unit = MiniUnit(ignoreBandFrames = setOf(2))
        val bands = editorBands().toMutableList()
        bands[2] = bands[2].copy(gainDb = 2.0)
        val r = session(unit).writeMini(bands, explicitHold = true)
        assertFalse(r.verified)
        assertEquals("The FreeDSP Mini did not take the bands, so nothing was saved. Unplug and replug it to go back to its saved EQ.", r.reason)
        assertEquals(0, unit.writeFrames.count { it == SAVE }, "no save frame")
        assertEquals(8, unit.writeFrames.count { it.startsWith("01 09 21") }); assertEquals(1, unit.writeFrames.count { it == COMMIT })
        assertTrue(unit.notes.any { it.startsWith("FreeDSP Mini write: band 2 DIFFERS") }, unit.notes.toString())
        assertTrue(unit.notes.any { it.startsWith("FreeDSP Mini write: no save: band 2") })
        assertEquals(0, r.readback!!.bands[2]!!.gainQ8, "the read-back is what the unit holds")
        // the flash still holds the old EQ: a replug brings it back
        unit.replug(); assertEquals(TUPLES, readOf(unit).bands.map { Triple(it!!.frequencyHz, it.qQ8, it.gainQ8) })
    }

    @Test fun `a band whose read-back reply is lost is asked again once and the write still verifies`() {
        val unit = MiniUnit(loseFirstReadAfterCommit = setOf(3))
        val r = session(unit).writeMini(editorBands(), explicitHold = true)
        assertTrue(r.verified, r.reason)
        // the query of band 3: once in the read before, twice after the commit (the first reply was lost), once in the read after the save
        assertEquals(4, unit.sent.count { hx(it) == "80 09 03 00 03 00" })
        assertEquals(1, unit.writeFrames.count { it == SAVE })
    }

    @Test fun `a band that never answers after the write leaves the unit unsaved`() {
        // band 6 answers before the write and never again: the read-back cannot prove it
        val unit = MiniUnit(silentAfterCommit = setOf(6))
        val r = session(unit).writeMini(editorBands(), explicitHold = true)
        assertFalse(r.verified)
        assertEquals(FreeDspMiniCodec.NOT_TAKEN_TEXT, r.reason)
        assertEquals(0, unit.writeFrames.count { it == SAVE })
        assertTrue(unit.notes.any { it.contains("band 6: no reply to the read-back") }); assertTrue(unit.notes.any { it.startsWith("FreeDSP Mini write: no save: band 6 not read back") })
        assertNull(r.readback!!.bands[6])
        assertEquals(listOf("band 6"), r.readback!!.unanswered)
    }

    @Test fun `a unit on a firmware voicing is refused with the slot's name, nothing with OP 0x01 is sent and no slot switch is tried`() {
        val unit = MiniUnit(slot = 1)
        val e = assertFailsWith<WriteRefused> { session(unit).writeMini(editorBandsFor7(), explicitHold = true) }
        assertEquals("The FreeDSP Mini is on Bass Enhance (slot 1). Switch it to Custom EQ in the Moondrop app first. Nothing was sent.", e.message)
        assertEquals(emptyList(), unit.writeFrames)
        assertEquals(readFrames, unit.sent.map { hx(it) }, "only the read of the Mini went out")
        // Space (3) and a slot with no name
        assertEquals("The FreeDSP Mini is on Space (slot 3). Switch it to Custom EQ in the Moondrop app first. Nothing was sent.",
            assertFailsWith<IllegalStateException> { session(MiniUnit(slot = 3)).writeMini(editorBandsFor7(), explicitHold = true) }.message)
        assertTrue(assertFailsWith<IllegalStateException> { session(MiniUnit(slot = 5)).writeMini(editorBandsFor7(), explicitHold = true) }.message!!.endsWith("(slot 5). Switch it to Custom EQ in the Moondrop app first. Nothing was sent."))
        // the page does not open the voicing's bands in the editor
        assertFalse(FamilyState.FreeDspMini(mini, readOf(unit)).importable)
    }
    private fun editorBandsFor7() = editorBands()

    @Test fun `a read before that misses a band, or shows mixed slots, sends nothing with OP 0x01`() {
        val missing = MiniUnit(silentBands = setOf(4))
        val e = assertFailsWith<WriteRefused> { session(missing).writeMini(editorBands(), explicitHold = true) }
        assertEquals("The FreeDSP Mini did not answer every band. Nothing was sent. Unplug and replug it, then try again.", e.message)
        assertEquals(emptyList(), missing.writeFrames)
        assertEquals(2, missing.sent.count { hx(it) == "80 09 03 00 04 00" }, "asked twice, as every read of the Mini")
        // all bands silent: no slot known
        val dead = MiniUnit(silentBands = (0..7).toSet())
        assertEquals(FreeDspMiniCodec.INCOMPLETE_READ_TEXT, assertFailsWith<IllegalStateException> { session(dead).writeMini(editorBands(), explicitHold = true) }.message)
        assertEquals(emptyList(), dead.writeFrames)
        // two slots in one read: not a unit Contour writes
        val mixed = MiniUnit().also { it.ram[6][35] = 1 }
        assertEquals(FreeDspMiniCodec.INCOMPLETE_READ_TEXT, assertFailsWith<IllegalStateException> { session(mixed).writeMini(editorBands(), explicitHold = true) }.message)
        assertEquals(emptyList(), mixed.writeFrames)
    }

    @Test fun `the preflight refuses a bad band before any I-O at all`() {
        val ok = editorBands()
        val bad = listOf(
            ok.toMutableList().also { it[0] = it[0].copy(type = FilterType.LOW_SHELF) },
            ok.toMutableList().also { it[1] = it[1].copy(freqHz = 25000.0) },
            ok.toMutableList().also { it[1] = it[1].copy(freqHz = 19.0) },
            ok.toMutableList().also { it[2] = it[2].copy(gainDb = 12.5) },
            ok.toMutableList().also { it[2] = it[2].copy(gainDb = -12.5) },
            ok.toMutableList().also { it[3] = it[3].copy(q = 10.5) },
            ok.toMutableList().also { it[3] = it[3].copy(q = 0.05) },
            ok.toMutableList().also { it[3] = it[3].copy(q = Double.NaN) },
            ok + Band("nine", FilterType.PEAK, 1000.0, 0.0, 1.0),
        )
        for ((i, bands) in bad.withIndex()) {
            val unit = MiniUnit()
            assertFailsWith<IllegalArgumentException>("case $i") { session(unit).writeMini(bands, explicitHold = true) }
            assertEquals(emptyList(), unit.sent.map { hx(it) }, "case $i: not a single frame, not even the read")
            assertEquals(0, unit.guards, "case $i: the port was not touched")
        }
        val unit = MiniUnit()
        assertFailsWith<IllegalArgumentException> { session(unit).writeMini(ok, explicitHold = false) }
        assertEquals(emptyList(), unit.sent.map { hx(it) })
        // a disabled band is not checked, it is filled
        val off = ok.toMutableList().also { it[3] = it[3].copy(enabled = false, freqHz = 5.0, q = 99.0) }
        assertTrue(session(MiniUnit()).writeMini(off, explicitHold = true).verified)
        // the corners of the ranges: a corner is either writable or refused for Q30 (a +12 dB, Q 0.1 band at 20 kHz has b0 above 2^31 in Q30), never half-written
        for (f in listOf(20.0, 20000.0)) for (g in listOf(-12.0, 12.0)) for (q in listOf(0.1, 10.0))
            FreeDspMiniCodec.issues(mini, listOf(Band("e", FilterType.PEAK, f, g, q))).let { i -> assertTrue(i.isEmpty() || i.single().endsWith("not representable as signed Q30"), "$f Hz $g dB Q $q: $i") }
        val wide = ok.toMutableList().also { it[0] = it[0].copy(freqHz = 20000.0, gainDb = 12.0, q = 0.1) }
        assertTrue(FreeDspMiniCodec.issues(mini, wide).single().startsWith("Band 1: Coefficient b0="))
        val unit2 = MiniUnit()
        assertFailsWith<IllegalArgumentException> { session(unit2).writeMini(wide, explicitHold = true) }
        assertEquals(emptyList(), unit2.sent.map { hx(it) })
        assertTrue(DeviceTarget.FREEDSP_MINI.issues(profile(wide)).isNotEmpty(), "HOLD TO SEND says so before a send")
    }

    @Test fun `a disabled or missing band goes out as a PEAK at 0 dB at the frequency and Q the unit had, and the unit keeps them`() {
        val unit = MiniUnit()
        val ok = editorBands()
        // band 4 (index 3, 200 Hz -3 dB Q 0.5) switched off, bands 7 and 8 missing
        val bands = ok.take(6).toMutableList().also { it[3] = it[3].copy(enabled = false) }
        val r = session(unit).writeMini(bands, explicitHold = true)
        assertTrue(r.verified, r.reason)
        val sent = unit.writeFrames
        assertEquals(hx(FreeDspMiniCodec.bandPayload(FreeDspMiniCodec.registerBand(3, 200, 128, 0))), sent[3])
        assertEquals(hx(FreeDspMiniCodec.bandPayload(FreeDspMiniCodec.registerBand(6, 3000, 128, 0))), sent[6])
        assertEquals(hx(FreeDspMiniCodec.bandPayload(FreeDspMiniCodec.registerBand(7, 10000, 128, 0))), sent[7])
        assertEquals(listOf(200, 3000, 10000), listOf(3, 6, 7).map { r.readback!!.bands[it]!!.frequencyHz })
        assertEquals(listOf(128, 128, 128), listOf(3, 6, 7).map { r.readback!!.bands[it]!!.qQ8 })
        assertEquals(listOf(0, 0, 0), listOf(3, 6, 7).map { r.readback!!.bands[it]!!.gainQ8 })
        assertEquals(listOf(2, 2, 2), listOf(3, 6, 7).map { r.readback!!.bands[it]!!.nativeType })
    }


    // ---- the pre-gain is never written: a different one in the profile is refused ----------------------------------------------------

    @Test fun `a profile whose pre-gain differs from the unit's is refused before any frame with OP 0x01, an equal one still sends`() {
        val text = "This test build does not change the FreeDSP Mini's pre-gain (-2.0 dB). Set PRE GAIN to -2.0 dB and send again. Nothing was sent."
        assertEquals(text, FreeDspMiniCodec.preGainRefusal(-2.0))
        val unit = MiniUnit() // reads -2.0 dB
        val e = assertFailsWith<WriteRefused> { session(unit).writeMini(editorBands(), explicitHold = true, preGainDb = -1.0) }
        assertEquals(text, e.message)
        assertEquals(emptyList(), unit.writeFrames, "no frame with OP 0x01")
        assertEquals(readFrames, unit.sent.map { hx(it) }, "only the read of the Mini went out")
        assertTrue(unit.notes.any { it.contains("no write, the profile's pre-gain") })
        // 0.1 dB grid: -1.96 and -2.04 are the unit's -2.0; -2.1 is not
        for (ok in listOf(-2.0, -1.96, -2.04)) { val u = MiniUnit(); assertTrue(session(u).writeMini(editorBands(), explicitHold = true, preGainDb = ok).verified, "$ok") }
        assertFailsWith<WriteRefused> { session(MiniUnit()).writeMini(editorBands(), explicitHold = true, preGainDb = -2.1) }
        // no pre-gain given, or none read from the unit: nothing to compare, the bands send
        assertTrue(session(MiniUnit()).writeMini(editorBands(), explicitHold = true).verified)
        val silent = MiniUnit(silentPre = true)
        assertTrue(session(silent).writeMini(editorBands(), explicitHold = true, preGainDb = -1.0).verified)
        // the wording follows the unit's value
        assertEquals("This test build does not change the FreeDSP Mini's pre-gain (0.0 dB). Set PRE GAIN to 0.0 dB and send again. Nothing was sent.", FreeDspMiniCodec.preGainRefusal(0.0))
        // FROM DAC keeps the unit's pre-gain as a manual value (AUTO off); AUTO on the imported cuts is 0 dB, which is not the unit's -2.0, so a send with AUTO on is refused too
        val imported = FamilyState.FreeDspMini(mini, readOf(MiniUnit())).importExact()
        assertEquals(-2.0, imported.preampDb)
        val autoProfile = Profile("i", "t", bands = imported.bands, preampDb = null, createdAt = 0, updatedAt = 0)
        assertEquals(0.0, autoProfile.effectivePreampDb(mini.shelfForm))
        assertFailsWith<WriteRefused> { session(MiniUnit()).writeMini(autoProfile.bands, explicitHold = true, preGainDb = autoProfile.effectivePreampDb(mini.shelfForm)) }
    }

    // ---- the port allow-list --------------------------------------------------------------------------------------------------------

    private class Recorder : NativePort {
        override val deviceKey = "r"
        val seen = ArrayList<String>()
        override fun guard() {}
        override fun pause(ms: Long) {}
        override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) { seen += hx(payload) }
        override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray { seen += hx(payload); return ByteArray(63) }
    }

    private fun band(slot: Int = 7, type: Int = 2, len: Int = 0x21, size: Int = 37, index: Int = 0): ByteArray =
        FreeDspMiniCodec.bandPayload(FreeDspMiniCodec.registerBand(index, 1000, 256, 0)).copyOf(size).also { it[2] = len.toByte(); it[33] = type.toByte(); if (size > 35) it[35] = slot.toByte() }

    @Test fun `the Mini's port lets through exactly the three reads, a slot 7 PEAK band frame, the commit and the save, and throws before I-O on anything else`() {
        val inner = Recorder()
        val port = MiniAllowListPort(inner)
        val good = listOf(bytesOf("80 0c 01 00"), bytesOf("80 23 00"), bytesOf("80 09 03 00 00 00"), bytesOf("80 09 03 00 07 00"), band(), band(index = 7), bytesOf(COMMIT), bytesOf(SAVE))
        for (p in good) { port.send(0x4B, NativeHidReports.Kind.OUTPUT, p); port.request(0x4B, p) { true } }
        assertEquals(good.map { hx(it) } .flatMap { listOf(it, it) }, inner.seen)
        inner.seen.clear()
        val refused = listOf(
            "01 0f 01 07", "01 0f 01 00", "01 23 02 00 00", "01 03 02 00 00", "01 03 ff ff ff", "01 04 00", "01 05 01 04", "01 ff", "01 ff 00 00",
            "01 0a 04 01 00 ff ff", "01 0a 04 00 00 ff", "01 01 01", "01 01 00 00", "01", "00", "",
            "80 0f 00", "80 03", "80 03 00", "80 09 18 00 00 00", "80 09 03 00 08 00", "80 09 03 01 00 00", "80 0c 00", "80 0c 01 00 00", "80 23 02",
        ).map { if (it.isEmpty()) ByteArray(0) else bytesOf(it) } + listOf(
            band(slot = 9), band(slot = 0), band(slot = 1), band(type = 1), band(type = 3), band(type = 0), band(len = 0x18), band(len = 0x20), band(size = 36), band(size = 38).also { it[37] = 0 },
            band().also { it[0] = 0x02 }, band().also { it[1] = 0x0a }, band().also { it[4] = 8 }, band().also { it[34] = 1 }, band().also { it[36] = 1 },
        )
        for ((i, p) in refused.withIndex()) {
            assertFailsWith<IllegalStateException>("send $i ${hx(p)}") { port.send(0x4B, NativeHidReports.Kind.OUTPUT, p) }
            assertFailsWith<IllegalStateException>("request $i ${hx(p)}") { port.request(0x4B, p) { true } }
        }
        assertEquals(emptyList(), inner.seen, "the real port was not touched")
        // the switch off (test 1): every frame with OP 0x01 is refused with the read-only text, the reads still go
        val off = MiniAllowListPort(inner, writesOn = false)
        for (p in listOf(band(), bytesOf(COMMIT), bytesOf(SAVE))) {
            assertEquals(MoondropCatalog.FREEDSP_MINI_READ_ONLY_TEXT, assertFailsWith<IllegalStateException> { off.send(0x4B, NativeHidReports.Kind.OUTPUT, p) }.message)
            assertEquals(MoondropCatalog.FREEDSP_MINI_READ_ONLY_TEXT, assertFailsWith<IllegalStateException> { off.request(0x4B, p) { true } }.message)
        }
        off.request(0x4B, bytesOf("80 0c 01 00")) { true }
        assertEquals(listOf("80 0c 01 00"), inner.seen)
    }

    @Test fun `a session of the Mini cannot be made to send a frame outside the allow-list, whatever the port under it is`() {
        val rec = Recorder()
        val s = session(rec)
        // the session's port is the allow-list: the only way to the Dawn's frames is through the Dawn methods, and they refuse
        assertFailsWith<IllegalStateException> { s.selectIndex(7, explicitUserAction = true) }
        assertFailsWith<IllegalStateException> { s.writeGain(MoondropCodec.GainRegister.PRE, -1.0, explicitHold = true) }
        assertEquals(emptyList(), rec.seen)
        // the Dawn Pro 2's session is not behind it
        assertFalse(dawn.freedspMini)
    }

    // ---- FROM DAC and ON DAC ------------------------------------------------------------------------------------------------------

    @Test fun `the Mini's Custom EQ opens in the editor with its eight bands and is ON DAC until a band changes`() {
        val state = FamilyState.FreeDspMini(mini, readOf(MiniUnit()))
        assertTrue(state.onCustomEq); assertTrue(state.importable)
        val imported = state.importExact()
        assertEquals(8, imported.bands.size)
        assertEquals(listOf(31.0, 62.0, 125.0, 200.0, 500.0, 1000.0, 3000.0, 10000.0), imported.bands.map { it.freqHz })
        assertEquals(-691 / 256.0, imported.bands[4].gainDb)
        assertEquals(-2.0, imported.preampDb)
        assertTrue(imported.bands.all { it.type == FilterType.PEAK && it.enabled })
        val p = profile(imported.bands)
        assertTrue(state.holds(p)); assertTrue(state.matches(p))
        assertEquals(emptyList(), DeviceTarget.FREEDSP_MINI.issues(p))
        // one band changed: not on the DAC
        assertFalse(state.matches(profile(imported.bands.toMutableList().also { it[5] = it[5].copy(gainDb = -1.0) })))
        // the editor's -2.7 for band 5 is the same register: still on the DAC
        assertTrue(state.matches(profile(imported.bands.toMutableList().also { it[4] = it[4].copy(gainDb = -2.7) })))
        // a unit on another slot, or with a band missing, is only shown
        assertFalse(FamilyState.FreeDspMini(mini, readOf(MiniUnit(slot = 2))).importable)
        assertFalse(FamilyState.FreeDspMini(mini, MoondropSession(mini, MiniUnit(silentBands = setOf(1))).readMini()).importable)
        assertFailsWith<IllegalArgumentException> { FamilyState.FreeDspMini(mini, readOf(MiniUnit(slot = 2))).importExact() }
        // a shelf on slot 7 (set in another tool) is shown, not opened
        val shelf = MiniUnit().also { it.ram[0][33] = 1 }
        assertFalse(FamilyState.FreeDspMini(mini, readOf(shelf)).importable)
    }

    // ---- a Custom EQ that is not the tester's all-PEAK bank -------------------------------------------------------------------------

    private fun oddUnit() = MiniUnit().also { it.ram[1][33] = 1; it.ram[2][33] = 9; it.ram[6][31] = 0; it.ram[6][32] = 0x0f } // LOW SHELF, unknown type 9, PEAK +15 dB

    @Test fun `a Custom EQ with a shelf, an unknown type and a +15 dB peak reads and shows as read, is not opened in the editor and is not written over by a profile that does not fit`() {
        val unit = oddUnit()
        val read = readOf(unit)
        assertEquals(8, read.bands.count { it != null }); assertEquals(7, read.slot)
        val state = FamilyState.FreeDspMini(mini, read)
        assertTrue(state.onCustomEq)
        assertFalse(state.importable, "a shelf and an unknown type are shown, not opened")
        assertFailsWith<IllegalArgumentException> { state.importExact() } // the app takes it as DacImport.Rejected (DacSnapshot.importExact wraps it)
        assertFalse(state.holds(profile(editorBands()))); assertFalse(state.matches(profile(editorBands())))
        val lines = state.pageLines()
        assertEquals("2  LS  62 Hz  +0.0 dB  Q 0.75", lines[4]); assertEquals("3  code 9  125 Hz  +0.0 dB  Q 0.75", lines[5]); assertEquals("7  PK  3000 Hz  +15.0 dB  Q 0.50", lines[9])
        assertTrue(state.describe().contains("code 9"))
        // a profile of the user's own that fits is still written over it (the unit is on Custom EQ and every band answered); the shelf and the unknown band are replaced by PEAK bands
        val ok = MiniUnit().also { it.ram[1][33] = 1; it.ram[2][33] = 9 }
        assertTrue(session(ok).writeMini(editorBands(), explicitHold = true).verified)
        // a bank whose only oddity is the +15 dB peak opens, and the profile is refused by its own range before any I/O: HOLD TO SEND shows the issue
        val loud = MiniUnit().also { it.ram[6][31] = 0; it.ram[6][32] = 0x0f }
        val loudState = FamilyState.FreeDspMini(mini, readOf(loud))
        assertTrue(loudState.importable)
        val p = profile(loudState.importExact().bands)
        assertEquals(15.0, p.bands[6].gainDb)
        assertEquals(listOf("Band 7: gain 15 dB outside -12..12 dB"), DeviceTarget.FREEDSP_MINI.issues(p))
        val fresh = MiniUnit().also { it.ram[6][31] = 0; it.ram[6][32] = 0x0f }
        val e = assertFailsWith<IllegalArgumentException> { session(fresh).writeMini(p.bands, explicitHold = true) }
        assertEquals("Band 7: gain 15.0 dB outside -12.0..12.0 dB", e.message)
        assertEquals(emptyList(), fresh.sent.map { hx(it) }, "refused before any I/O")
    }
}
