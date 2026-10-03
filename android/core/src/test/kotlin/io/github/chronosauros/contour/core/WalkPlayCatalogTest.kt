package io.github.chronosauros.contour.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure protocol tests; synthetic reports/descriptors are NOT hardware captures. */
class WalkPlayCatalogTest {
    // The Protocol Max recipe through the catalog name route; 3302:43CC itself is the stable MAX target (MaxRoutingTest).
    private fun target(vid: Int = 0x3302, pid: Int = 0xABCD, name: String = "Protocol Max"): DeviceProtocol =
        DeviceProtocol.find(vid, pid, true, name) ?: error("Missing exact target")
    private val pk = Band("pk", FilterType.PEAK, 6900.75, -2.3, 0.713)
    private fun reply(w: WalkPlay.BandWrite, slot: Int = 7): ByteArray = WalkPlay.bandWriteReport(w, slot).also {
        it[1] = WalkPlay.READ.toByte(); it[3] = 0
    }
    private fun hex(text: String): ByteArray = text.split(' ').map { it.toInt(16).toByte() }.toByteArray()
    private fun descriptor(count: Int = 63): ByteArray = hex("06 00 FF 09 01 A1 01 85 4B 75 08 95 ${count.toString(16)} 09 01 81 02 09 01 91 02 C0")

    @Test fun `all source identifiers and literal names preserved`() {
        assertEquals(367, WalkPlayCatalog.rows.size)
        assertEquals(366, WalkPlayCatalog.rows.count { it.valid })
        assertEquals(366, WalkPlayCatalog.rows.filter { it.valid }.map { it.vid to it.pid }.toSet().size)
        val bad = WalkPlayCatalog.rows.single { it.pidToken == "0x43H1" }
        assertFalse(bad.valid); assertNull(bad.pid); assertEquals("invalid_identifier_block", bad.decision)
        assertEquals(29, WalkPlayCatalog.literalNames.size)
        assertTrue("ES9039 " in WalkPlayCatalog.literalNames)
        assertTrue("TANCHJIM-FISSION  DSP" in WalkPlayCatalog.literalNames)
    }

    @Test fun `exact pairs captured pairs names vendor scope and stable gate`() {
        assertEquals(DeviceProtocol.MAX, DeviceProtocol.find(0x3302, 0x43CC, true))
        assertTrue(WalkPlayCatalog.resolve(0x3302, 0x43D4, null, true) is WalkPlayCatalog.Resolution.NeedsName)
        assertTrue(WalkPlayCatalog.candidate(0x3302, 0x4367, null, true))
        assertEquals(10, target(pid = 0x4367, name = "TANCHJIM-SPACE PRO").caps.bands)
        assertEquals(setOf(FilterType.PEAK), target(pid = 0x4367, name = "TANCHJIM-SPACE PRO").caps.types)
        assertEquals(10, target(pid = 0x39C3, name = "Octave").caps.bands)
        assertEquals(setOf(FilterType.PEAK), target(pid = 0x43D4, name = "TANCHJIM-STARGATE II").caps.types)
        assertNull(DeviceProtocol.find(0x262A, 0x43CC, true, "Protocol Max"))
        assertNull(DeviceProtocol.find(0x3302, 0xABCD, true, "Protocol MAX"))
        assertNull(DeviceProtocol.find(0x3302, 0xABCD, true, "ES9039"))
        assertTrue(target(pid = 0xABCD, name = "ES9039 ").upstreamExperimental)
        assertEquals(DeviceProtocol.MAX, DeviceProtocol.find(0x3302, 0x43CC, false, "Protocol Max"))
        assertNull(DeviceProtocol.find(0x3302, 0xABCD, false, "Protocol Max"))
        assertEquals(DeviceProtocol.MICRO, DeviceProtocol.find(0x3302, 0xC20F, false))
        assertEquals(DeviceProtocol.TRN, DeviceProtocol.find(0x3302, 0x43E8, true))
        assertFalse(WalkPlayCatalog.candidate(0x3302, 0xABCD, null, true))
    }

    @Test fun `catalog conflicts unknown schemes alternate routes and KT held`() {
        for ((v, p) in listOf(0x3302 to 0x4302, 0x0663 to 0x0880, 0x0666 to 0x0883, 0x373B to 0x129F,
            0x3302 to 0xEEEE, 0x3302 to 0x8901, 0x3302 to 0x8902, 0x3302 to 0x435C, 0x3302 to 0x43C0)) {
            assertNull(DeviceProtocol.find(v, p, true, "Unknown USB model"))
        }
        assertNull(DeviceProtocol.find(0x3302, 0x43D4, true, "DAWN PRO2"))
        assertNull(DeviceProtocol.find(0x3302, 0x43DF, true, "ddHiFi DSP IEM - Memory"))
        assertNull(DeviceProtocol.find(0x31B2, 0x0111, true, "Kiwi Ears-Allegro PRO"))
        assertEquals(8, target(pid = 0xABCD, name = "DAWN PRO 2").caps.bands)
        assertNull(DeviceProtocol.find(0x3302, 0x435C, true, "DT04"))
        assertEquals(8, target(pid = 0x43EB, name = "BGVP MX1").caps.bands)
    }

    @Test fun `scheme capacities native types complete spare slots`() {
        for (n in listOf(5, 6, 8, 10)) {
            val row = WalkPlayCatalog.rows.first { it.valid &&
                it.decision == "documented_exact_pair_beta_candidate_requires_name_and_readback" &&
                DeviceProtocol.find(it.vid!!, it.pid!!, true, "Unlisted USB name")?.caps?.bands == n }
            val p = target(row.vid!!, row.pid!!, "Unlisted USB name")
            val plan = p.plan(listOf(pk), -2.7) as DevicePlan.Ready
            assertEquals(n, plan.bands.size); assertEquals((0 until n).toList(), plan.bands.map { it.index })
            assertEquals(-3, plan.preampDb)
            assertTrue(plan.bands.drop(1).all { it.typeCode == 2 && it.gainDb == 0.0 && it.freq == 1000.0 })
            assertTrue(p.plan(List(n + 1) { pk }, -4.0) is DevicePlan.Rejected)
            assertFalse(p.supportsAb)
        }
        val cs = target(pid = 0x51C0, name = "CS43131 HiFi Audio DSP")
        assertEquals(setOf(FilterType.PEAK, FilterType.LOW_SHELF), cs.caps.types)
        assertTrue(cs.plan(listOf(pk.copy(type = FilterType.HIGH_SHELF)), -5.0) is DevicePlan.Rejected)
        val ola = target(pid = 0xABCD, name = "TANCHJIM-OLA II DSP")
        assertEquals(5.0, ola.caps.qMax)
        assertTrue(ola.plan(listOf(pk.copy(q = 5.1)), -4.0) is DevicePlan.Rejected)
        assertEquals(31, DeviceProtocol.OFFLINE.caps.bands)
    }

    @Test fun `raw metadata and coefficient domain consistent without Micro HS offset`() {
        val p = target()
        val w = (p.plan(listOf(pk), -2.7) as DevicePlan.Ready).bands[0]
        val r = w.registers()
        assertEquals(6900, r.freq); assertEquals(r.freq.toDouble(), w.freq)
        assertEquals(r.q256 / 256.0, w.q); assertEquals(r.gain256 / 256.0, w.gainDb)
        val hs = pk.copy(type = FilterType.HIGH_SHELF, gainDb = 3.0, q = 0.75)
        val plan = p.plan(listOf(hs), -4.3) as DevicePlan.Ready
        assertEquals(3, plan.bands[0].typeCode); assertEquals(-5, plan.preampDb); assertEquals(0.0, p.shelfOffset(listOf(hs)))
        assertEquals(1, (DeviceProtocol.MICRO.plan(listOf(hs), -4.3) as DevicePlan.Ready).bands[0].typeCode)
        assertEquals(3.0, DeviceProtocol.MICRO.shelfOffset(listOf(hs)))
    }

    @Test fun `full profile rejects ranges nonfinite types and bad last without writes`() {
        val p = target()
        val bad = listOf(pk.copy(freqHz = 19.0), pk.copy(freqHz = 20001.0), pk.copy(q = 0.09), pk.copy(q = 10.1),
            pk.copy(gainDb = -10.1), pk.copy(gainDb = 10.1), pk.copy(freqHz = Double.NaN), pk.copy(q = Double.POSITIVE_INFINITY),
            pk.copy(type = FilterType.LOW_PASS), pk.copy(type = FilterType.HIGH_PASS),
            pk.copy(type = FilterType.LOW_SHELF, q = 10.0, gainDb = 10.0), pk.copy(enabled = false, gainDb = Double.NaN))
        var writes = 0
        for (b in bad) {
            val plan = p.plan(List(9) { pk } + b, -4.0)
            assertTrue(plan is DevicePlan.Rejected, "$b")
            if (plan is DevicePlan.Ready) p.executeWrite(plan, 0, {}, { writes++ }, {})
        }
        assertEquals(0, writes)
        for (gain in listOf(Double.NaN, Double.NEGATIVE_INFINITY, -30.1, 0.1))
            assertTrue(p.plan(listOf(pk), gain) is DevicePlan.Rejected)
        val valid = p.flatPlan()
        val invalidLast = valid.copy(bands = valid.bands.dropLast(1) + valid.bands.last().copy(q = Double.NaN))
        assertFailsWith<IllegalArgumentException> { p.executeWrite(invalidLast, 0, {}, { writes++ }, {}) }
        assertEquals(0, writes)
    }

    @Test fun `descriptor driven sizes reject malformed absent wrong usage and ambiguous owners`() {
        assertEquals(WalkPlayCatalog.Reports(64, 64), WalkPlayCatalog.reports(descriptor()))
        assertEquals(WalkPlayCatalog.Reports(38, 38), WalkPlayCatalog.reports(descriptor(37)))
        for (bad in listOf(descriptor().dropLast(1).toByteArray(), descriptor().also { it[2] = 0 },
            descriptor(36), descriptor().also { it[8] = 0x4A }, descriptor() + descriptor(), hex("FE 01 02 00"))) {
            assertTrue(runCatching { WalkPlayCatalog.reports(bad) }.isFailure)
        }
    }

    @Test fun `strict parser no partial unknown type or wrong reply acceptance`() {
        val p = target(); val good = reply(p.flatPlan().bands[9])
        assertEquals(9, p.parseBand(good, 9).registers.index)
        for (bad in listOf(good.copyOf(36), good.copyOf().also { it[1] = 1 }, good.copyOf().also { it[0] = 7 },
            good.copyOf().also { it[34] = 99 }, good.copyOf().also { it[34] = 4 }, good.copyOf().also { it[28] = 0; it[29] = 0 })) {
            assertFailsWith<IllegalArgumentException> { p.parseBand(bad, 9) }
        }
        assertFailsWith<IllegalArgumentException> { p.parseBand(good, 8) }
        assertFailsWith<IllegalArgumentException> { p.parseSlot(good) }
        assertEquals(7, p.parseSlot(reply(p.flatPlan().bands[0])))
        assertFailsWith<IllegalArgumentException> { p.parsePreamp(WalkPlay.report(0x80, 3, 2, 0, 127)) }
    }

    @Test fun `readback requires every slot and native exact import preserves fractions`() {
        val p = target(); val plan = p.plan(List(10) { pk.copy(id = "$it") }, -3.7) as DevicePlan.Ready
        val reads = plan.bands.map { p.parseBand(reply(it), it.index) }
        val regs = reads.map { it.registers }
        assertTrue(p.matches(plan, regs, plan.preampDb))
        assertFalse(p.matches(plan, regs.dropLast(1), plan.preampDb))
        assertFalse(p.matches(plan, regs.dropLast(1) + regs.last().copy(q256 = regs.last().q256 + 1), plan.preampDb))
        assertFalse(p.matches(plan, regs, plan.preampDb + 1))
        val imported = p.importExact(reads, plan.preampDb) as DacImport.Ready
        assertEquals(regs[0].q256 / 256.0, imported.eq.bands[0].q)
        assertTrue(p.matches(p.plan(imported.eq.bands, imported.eq.preampDb) as DevicePlan.Ready, regs, plan.preampDb))
    }

    @Test fun `31 to 5 assessment preserves serialized library intent disabled order and fractional preamp`() {
        val bands = List(31) { i -> pk.copy(id = "b$i", enabled = i % 3 != 0) }.toMutableList()
        bands[30] = bands[30].copy(type = FilterType.LOW_PASS, gainDb = 12.0)
        val profile = Profile("saved", "Stored", "", "headphones", bands.toList(), -3.7, 0L, 0L)
        val before = kotlinx.serialization.json.Json.encodeToString(Profile.serializer(), profile)
        val row = WalkPlayCatalog.rows.first { it.valid && it.scheme == 17 &&
            it.decision == "documented_exact_pair_beta_candidate_requires_name_and_readback" }
        val p = target(row.vid!!, row.pid!!, "Unlisted USB name")
        assertEquals(5, p.caps.bands)
        assertTrue(p.plan(profile) is DevicePlan.Rejected)
        assertEquals(before, kotlinx.serialization.json.Json.encodeToString(Profile.serializer(), profile))
        assertEquals(31, profile.bands.size); assertEquals(-3.7, profile.preampDb)
        assertEquals(FilterType.LOW_PASS, profile.bands.last().type)
    }

    private fun fakeRead(p: DeviceProtocol, plan: DevicePlan.Ready, what: String): ByteArray = when (what) {
        "version" -> WalkPlay.report(0x80, 0x0C, 0, '0'.code, '.'.code, '2'.code)
        "slot" -> reply(plan.bands[0], 7)
        "preamp" -> WalkPlay.report(0x80, 3, 2, 0, plan.preampDb)
        else -> reply(plan.bands[what.substringAfter("band ").toInt()], 7)
    }

    @Test fun `fresh complete read before any mutation and no partial snapshot`() {
        val p = target(); val plan = p.flatPlan(); var writes = 0
        val labels = ArrayList<String>()
        val state = p.readComplete({}) { _, what, _ -> labels += what; fakeRead(p, plan, what) }
        assertEquals(listOf("version", "slot") + (0 until p.caps.bands).map { "band $it" } + "preamp", labels)
        assertEquals(p.caps.bands, state.bands.size)
        assertEquals(7, state.slot); assertEquals(0, state.preampDb)
        for (badField in listOf("band 9", "preamp", "slot", "version")) {
            assertFailsWith<IllegalArgumentException> {
                val before = p.readComplete({}) { _, what, _ -> if (what == badField) byteArrayOf() else fakeRead(p, plan, what) }
                p.executeWrite(plan, before.slot, {}, { writes++ }, {})
            }
        }
        assertEquals(0, writes)
        assertFailsWith<IllegalArgumentException> {
            p.readComplete({}) { _, what, _ -> if (what == "band 9") fakeRead(p, plan, "band 8") else fakeRead(p, plan, what) }
        }
        var alive = true
        assertFailsWith<IllegalStateException> {
            p.readComplete({ check(alive) }) { _, what, _ ->
                if (what == "band 9") alive = false
                fakeRead(p, plan, what)
            }
        }
    }

    @Test fun `post readback slot coefficients missing and one bit differences fail`() {
        val p = target(); val plan = p.flatPlan()
        val state = p.readComplete({}) { _, what, _ -> fakeRead(p, plan, what) }
        assertTrue(p.matchesReadback(plan, state, 7))
        assertFalse(p.matchesReadback(plan, state.copy(slot = 101), 7))
        assertFalse(p.matchesReadback(plan, state.copy(bands = state.bands.dropLast(1)), 7))
        assertFalse(p.matchesReadback(plan, state.copy(preampDb = -1), 7))
        val changed = state.bands.last().copy(biquad = state.bands.last().biquad.copyOf().also { it[19] = (it[19].toInt() xor 1).toByte() })
        assertFalse(p.matchesReadback(plan, state.copy(bands = state.bands.dropLast(1) + changed), 7))
    }

    @Test fun `transaction session gate prevents queued writes across replacement or delay`() {
        val p = target(); val plan = p.flatPlan()
        var alive = false; var writes = 0
        val guard = { check(alive) { "Replaced session" }; Unit }
        assertFailsWith<IllegalStateException> { p.executeWrite(plan, 0, guard, { writes++ }, {}) }
        assertEquals(0, writes)
        alive = true
        assertFailsWith<IllegalStateException> { p.executeWrite(plan, 0, guard, { writes++ }, { alive = false }) }
        assertEquals(1, writes)
        alive = true; writes = 0
        p.executeWrite(plan, 7, guard, { writes++ }, {})
        assertEquals(p.caps.bands + 5, writes)
    }
}
