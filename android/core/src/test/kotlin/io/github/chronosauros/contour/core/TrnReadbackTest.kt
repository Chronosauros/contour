package io.github.chronosauros.contour.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TRN Black Pearl read and send from a state the editor caps (+-10 dB) refuse: the eight band records that a tester's
 * Black Pearl returned on 11.10.2026 (adb logcat, advBeta 1.4.1-advbeta3). Band 8 holds 9300 Hz, Q 2.0, -10.4 dB;
 * before the fix every read ended in "Malformed TRN registers" there and HOLD TO SEND failed with it.
 * The records, the version reply and the slot byte are the capture; slots 9 and 10 (never reached by the old read)
 * and the preamp reply are SYNTHETIC here (neutral flat PK, preamp -4 dB), and so is the write echo of the fake DAC.
 */
class TrnReadbackTest {
    private class Rec(val index: Int, val freq: Int, val q256: Int, val gain256: Int, val type: Int, val hex: String)

    // RX 4b 80 09 ..., bytes 0..37 as logged (the rest of the 64-byte report is zero)
    private val captured = listOf(
        Rec(0, 67, 205, 589, 2, "4b 80 09 00 00 00 00 00 86 e5 0b 40 e0 cb 4e 80 0f 9f a5 3f 20 34 b1 7f 6a 7b 4e c0 43 00 cd 00 4d 02 02 00 65 00"),
        Rec(1, 180, 179, -1126, 2, "4b 80 09 00 00 01 00 00 17 2b ba 3f 14 a8 61 81 b7 6c e6 3e ec 57 9e 7e 32 68 5f c1 b4 00 b3 00 9a fb 02 00 65 00"),
        Rec(2, 770, 256, 282, 2, "4b 80 09 00 00 02 00 00 bd 16 33 40 00 74 1d 83 b2 19 d8 3c 00 8c e2 7c 91 cf f4 c2 02 03 00 01 1a 01 02 00 65 00"),
        Rec(3, 1300, 512, -307, 2, "4b 80 09 00 00 03 00 00 69 f1 d0 3f bc 42 4d 83 6f b2 55 3d 44 bd b2 7c 28 5c d9 c2 14 05 00 02 cd fe 02 00 65 00"),
        Rec(4, 2000, 307, -973, 2, "4b 80 09 00 00 04 00 00 aa f6 8f 3e 28 d6 23 89 6b c3 52 39 d8 29 dc 76 eb 45 1d c8 d0 07 33 01 33 fc 02 00 65 00"),
        Rec(5, 4500, 230, 1357, 2, "4b 80 09 00 00 05 00 00 6c 6e b7 45 10 3e 86 92 5d 68 af 2c f0 c1 79 6d 36 29 99 cd 94 11 e6 00 4d 05 02 00 65 00"),
        Rec(6, 6400, 512, 922, 2, "4b 80 09 00 00 06 00 00 73 5b 82 42 d4 3f fe 93 1d 10 b8 33 2c c0 01 6c 6f 94 c5 c9 00 19 00 02 9a 03 02 00 65 00"),
        Rec(7, 9300, 512, -2662, 2, "4b 80 09 00 00 07 00 00 39 4c c7 36 f2 65 aa ac 28 8f cc 2e 0e 9a 55 53 9f 24 6c da 54 24 00 02 9a f5 02 00 65 00"),
    )
    private val versionReply = report("4b 80 0c 00 30 2e 35")          // "0.5"
    private val synthPreamp = report("4b 80 03 02 00 fc")             // SYNTHETIC: -4 dB
    private val trn = DeviceProtocol.TRN

    private fun report(hex: String): ByteArray = hex.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray().copyOf(WalkPlay.REPORT_SIZE)
    private fun toReply(w: WalkPlay.BandWrite, slot: Int): ByteArray =
        WalkPlay.bandWriteReport(w, slot).also { it[1] = WalkPlay.READ.toByte(); it[3] = 0 }

    /** The DAC of the capture: records 0-7 as logged, the two spare slots synthetic flat. */
    private fun capturedDac(): MutableMap<Int, ByteArray> {
        val dac = HashMap<Int, ByteArray>()
        captured.forEach { dac[it.index] = report(it.hex) }
        for (i in 8..9) dac[i] = toReply(trn.flatPlan().bands[i], 101)
        return dac
    }

    /** What the DAC answers to one request report. [dac]: band index to its reply; [preamp]: the preamp reply. */
    private fun answer(dac: Map<Int, ByteArray>, preamp: ByteArray, request: ByteArray): ByteArray = when (request[2].toInt()) {
        WalkPlay.CMD_VERSION -> versionReply
        WalkPlay.CMD_GLOBAL_GAIN -> preamp
        else -> dac.getValue(request[5].toInt())
    }

    private fun read(dac: Map<Int, ByteArray>, preamp: ByteArray): DeviceProtocol.ReadState =
        trn.readComplete({}) { r, _, _ -> answer(dac, preamp, r) }

    /** The check the read applied before the fix (the editor caps), kept here to show which field of which record it refused. */
    private fun refusedByCaps(b: WalkPlay.DeviceBand): String? {
        val caps = trn.caps
        val r = b.registers
        return when {
            r.freq.toDouble() !in caps.freqMinHz..caps.freqMaxHz -> "freq"
            r.q256 / 256.0 !in (caps.qMin - 1.0 / 256)..(caps.qMax + 1.0 / 256) -> "q"
            r.gain256 / 256.0 !in caps.gainMinDb..caps.gainMaxDb -> "gain"
            else -> null
        }
    }

    @Test
    fun `the captured Black Pearl records decode and only band 8 gain is outside the editor caps`() {
        val decoded = captured.map { trn.parseBand(report(it.hex), it.index) }
        captured.zip(decoded).forEach { (rec, b) ->
            assertEquals(WalkPlay.Registers(rec.index, rec.freq, rec.q256, rec.gain256, rec.type), b.registers, "record ${rec.index}")
            assertTrue(b.enabled)
        }
        assertEquals("0.5", trn.parseVersion(versionReply))
        assertEquals(101, trn.parseSlot(report(captured[0].hex)))
        assertTrue(captured.all { report(it.hex)[36].toInt() == 101 })
        // the old read: records 0-6 pass the caps, record 7 (index 7, band 8) fails on its gain, -2662/256 = -10.398 dB
        val refused = decoded.mapIndexedNotNull { i, b -> refusedByCaps(b)?.let { i to it } }
        assertEquals(listOf(7 to "gain"), refused)
        assertEquals(-10.3984375, decoded[7].registers.gain256 / 256.0)
    }

    @Test
    fun `a read accepts the band the DAC holds below the editor range, spare slots included`() {
        val state = read(capturedDac(), synthPreamp)
        assertEquals("0.5", state.firmware)
        assertEquals(101, state.slot)
        assertEquals(10, state.bands.size)
        assertEquals(-4, state.preampDb)
        assertEquals(-2662, state.bands[7].registers.gain256)
        // reading changes nothing about what may be sent: the same value is still refused by the planner
        val held = Band("held", FilterType.PEAK, 9300.0, -2662 / 256.0, 2.0)
        assertTrue(trn.plan(listOf(held), -4.0) is DevicePlan.Rejected)
    }

    @Test
    fun `a disabled spare slot is no read failure`() {
        val dac = capturedDac()
        dac[9] = report("4b 80 09 00 00 09 00 00").also { it[34] = WalkPlay.TYPE_PK.toByte(); it[36] = 101 } // freq 0, Q 0: disabled
        val state = read(dac, synthPreamp)
        assertFalse(state.bands[9].enabled)
        // slots 9-10 were never read in the capture: an erased slot (all 0xFF, type 255) or a zeroed one (type 0) reads
        // as disabled too, and FROM DAC leaves both out
        dac[8] = report("4b 80 09 00 00 08 00 00").also { for (k in 8..37) it[k] = 0xFF.toByte() }
        dac[9] = report("4b 80 09 00 00 09 00 00")
        val blank = read(dac, synthPreamp)
        assertFalse(blank.bands[8].enabled)
        assertFalse(blank.bands[9].enabled)
        val imported = trn.importExact(blank.bands, blank.preampDb) as DacImport.Ready
        assertEquals(8, imported.eq.bands.size)
        assertTrue(imported.warning!!.endsWith("2 disabled DAC slots left out."), imported.warning)
    }

    @Test
    fun `FROM DAC loads the captured Black Pearl with its real minus 10 point 4 dB band and FIT brings it into the send range`() {
        val state = read(capturedDac(), synthPreamp)
        val imported = trn.importExact(state.bands, state.preampDb) as DacImport.Ready
        assertEquals(8, imported.eq.bands.size) // the two flat spares are not bands
        assertEquals(-4.0, imported.eq.preampDb)
        assertEquals(captured.map { it.freq.toDouble() }, imported.eq.bands.map { it.freqHz })
        assertEquals(captured.map { it.gain256 / 256.0 }, imported.eq.bands.map { it.gainDb })
        assertEquals(-10.3984375, imported.eq.bands[7].gainDb) // the stored value, not clamped to -10
        assertTrue(imported.warning!!.startsWith("Band 8: outside TRN Black Pearl supported types/ranges."), imported.warning)
        // sending it as it is stays refused (the caps are unchanged); the same profile at -10.0 dB is sendable
        val asLoaded = trn.plan(imported.eq.bands, imported.eq.preampDb)
        assertEquals(listOf("Band 8: outside TRN Black Pearl supported types/ranges"), (asLoaded as DevicePlan.Rejected).issues)
        val fitted = imported.eq.bands.mapIndexed { i, b -> if (i == 7) b.copy(gainDb = -10.0) else b }
        assertTrue(trn.plan(fitted, imported.eq.preampDb) is DevicePlan.Ready)
    }

    @Test
    fun `FROM DAC leaves disabled slots out and still rejects what is not exact`() {
        // disabled spare at the end and one in the middle of the used range
        val dac = capturedDac()
        val off = { i: Int -> report("4b 80 09 00 00 0$i 00 00").also { it[34] = WalkPlay.TYPE_PK.toByte(); it[36] = 101 } }
        dac[3] = off(3); dac[9] = off(9)
        val state = read(dac, synthPreamp)
        val imported = trn.importExact(state.bands, state.preampDb) as DacImport.Ready
        assertEquals(7, imported.eq.bands.size)
        assertEquals(listOf(67.0, 180.0, 770.0, 2000.0, 4500.0, 6400.0, 9300.0), imported.eq.bands.map { it.freqHz })
        assertTrue(imported.warning!!.endsWith("2 disabled DAC slots left out."), imported.warning)
        // a state inside the caps loads without a warning about ranges
        val inRange = capturedDac().also { it[7] = toReply(WalkPlay.BandWrite(7, 9300.0, -10.0, 2.0, WalkPlay.TYPE_PK), 101) }
        val clean = trn.importExact(read(inRange, synthPreamp).bands, -4) as DacImport.Ready
        assertEquals(null, clean.warning)
        assertEquals(-10.0, clean.eq.bands[7].gainDb)
        // every slot disabled: nothing to load
        val none = HashMap<Int, ByteArray>().also { m -> for (i in 0..9) m[i] = off(i) }
        assertTrue(trn.importExact(read(none, synthPreamp).bands, -4) is DacImport.Rejected)
    }

    @Test
    fun `FROM DAC on the Protocol Max is untouched, a flat state still loads`() {
        val flat = DeviceProtocol.MAX.flatPlan().bands.map { DeviceProtocol.MAX.parseBand(toReply(it, 0), it.index) }
        assertTrue(DeviceProtocol.MAX.importExact(flat, 0) is DacImport.Ready)
    }

    @Test
    fun `values no TRN register can hold are still a malformed read`() {
        fun withRegs(freq: Int, q256: Int, gain256: Int): ByteArray = toReply(WalkPlay.BandWrite(3, 1000.0, 0.0, 1.0, WalkPlay.TYPE_PK), 101).also {
            it[28] = freq.toByte(); it[29] = (freq shr 8).toByte(); it[30] = q256.toByte(); it[31] = (q256 shr 8).toByte()
            it[32] = gain256.toByte(); it[33] = (gain256 shr 8).toByte()
        }
        assertEquals(-2662, trn.parseBand(withRegs(9300, 512, -2662), 3).registers.gain256)
        assertEquals(60 * 256, trn.parseBand(withRegs(48000, 512, 60 * 256), 3).registers.gain256)
        for (bad in listOf(withRegs(48001, 512, 0), withRegs(1000, 512, 60 * 256 + 1), withRegs(1000, 512, -60 * 256 - 1)))
            assertEquals("Malformed TRN registers", assertFailsWith<IllegalArgumentException> { trn.parseBand(bad, 3) }.message)
        // a wrong type code or index is refused as before
        assertFailsWith<IllegalArgumentException> { trn.parseBand(withRegs(1000, 512, 0).also { it[34] = 9 }, 3) }
        assertFailsWith<IllegalArgumentException> { trn.parseBand(withRegs(1000, 512, 0), 4) }
    }

    @Test
    fun `the Protocol Max read keeps its strict ranges`() {
        val rec = toReply(WalkPlay.BandWrite(7, 9300.0, -2662 / 256.0, 2.0, WalkPlay.TYPE_PK), 0)
        for (max in listOf(DeviceProtocol.MAX, DeviceProtocol.MAX_PASS))
            assertEquals("Malformed ${max.caps.name} registers", assertFailsWith<IllegalArgumentException> { max.parseBand(rec, 7) }.message)
    }

    @Test
    fun `from the captured state an in-range profile is written, read back and verified`() {
        val dac = capturedDac()
        val before = read(dac, synthPreamp)
        // the editor profile of that state, band 8 brought to the DAC range as FIT TO DAC RANGE does
        val bands = before.bands.take(8).map { b ->
            val r = b.registers
            Band("b${r.index}", FilterType.PEAK, r.freq.toDouble(), r.gain256 / 256.0, r.q256 / 256.0)
        }.mapIndexed { i, b -> if (i == 7) b.copy(gainDb = trn.caps.gainMinDb) else b }
        val plan = trn.plan(bands, -4.0) as DevicePlan.Ready
        assertEquals(10, plan.bands.size)

        val sent = ArrayList<ByteArray>()
        trn.executeWrite(plan, before.slot, {}, { sent += it.copyOf() }, {})
        val bandReports = sent.filter { it[1].toInt() == WalkPlay.WRITE && it[2].toInt() == WalkPlay.CMD_PEQ }
        assertEquals(10, bandReports.size)
        assertTrue(bandReports.all { it[36].toInt() == 101 }, "every band write carries the slot byte that was read")
        assertEquals(listOf(-2560), listOf(WalkPlay.parseBand(bandReports[7]).registers.gain256))

        // the fake DAC takes the band and preamp writes as they were sent, then answers the read-back
        bandReports.forEach { w -> dac[w[5].toInt()] = w.copyOf().also { it[1] = WalkPlay.READ.toByte(); it[3] = 0 } }
        val preampWrite = sent.single { it[1].toInt() == WalkPlay.WRITE && it[2].toInt() == WalkPlay.CMD_GLOBAL_GAIN }
        val after = read(dac, preampWrite.copyOf().also { it[1] = WalkPlay.READ.toByte() })
        assertEquals(-4, after.preampDb)
        assertTrue(trn.matchesReadback(plan, after, before.slot))
        assertEquals(-2560, after.bands[7].registers.gain256)
        // and a read-back that still holds the old band does not verify
        dac[7] = report(captured[7].hex)
        assertFalse(trn.matchesReadback(plan, read(dac, synthPreamp.copyOf().also { it[5] = (-4).toByte() }), before.slot))
    }
}
