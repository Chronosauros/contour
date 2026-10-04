package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.*
import java.security.MessageDigest
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure JVM tests. Original captures are byte-identical pinned upstream resources, not invented
 * device observations. All generated replies below are explicitly synthetic unit-test inputs.
 * No device, driver stub, Android API, private directory, or external resource dependency.
 */
class NativeFiioTest {
    private fun resource(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/fiio/$name")).use { it.readBytes() }
    private fun json(name: String) = Json.parseToJsonElement(resource(name).decodeToString())
    private fun bytes(data: JsonArray) = data.map { it.jsonPrimitive.int.also { value -> require(value in 0..255) }.toByte() }.toByteArray()
    private fun codec(name: String) = FiioCodec(FiioCatalog.rules.single { it.productName == name })
    private fun peak(n: Int = 0) = Band("b$n", FilterType.PEAK, 1000.0, 0.0, 0.71)
    private fun plan(c: FiioCodec, bands: List<Band> = listOf(peak()), preamp: Double? = 0.0, slot: Int = c.config.userSlots.first()) =
        c.planOnExplicitSend(bands, preamp, slot, explicitUserAction = true)
    // SYNTHETIC framing; never used as capture evidence.
    private fun reply(cmd: Int, data: List<Int>) = (listOf(0xBB, 0x0B, 0, 0, cmd, data.size) + data + listOf(0, 0xEE)).map { it.toByte() }.toByteArray()
    private fun pair(v: Int) = listOf((v ushr 8) and 255, v and 255)
    private fun bandReply(r: FiioRegisters) = reply(FiioCodec.BAND, listOf(r.index) + pair(r.gainTenths) + pair(r.frequencyHz) + pair(r.qHundredths) + listOf(r.typeCode))
    private fun snapshot(c: FiioCodec, s: FiioSnapshot): FiioSnapshot = c.snapshot(
        reply(FiioCodec.SLOT, listOf(s.activeSlot)), reply(FiioCodec.COUNT, listOf(s.count)),
        reply(FiioCodec.PREAMP, pair(s.preampTenths)), s.bands.map { bandReply(it) }, reply(FiioCodec.SLOT, listOf(s.activeSlot)))

    private fun capturePackets(name: String): List<JsonObject> {
        val obj = json(name).jsonObject
        return obj["sequence"]?.jsonArray?.map { it.jsonObject }
            ?: obj.getValue("exchanges").jsonArray.flatMap { it.jsonObject.getValue("responses").jsonArray }.map { it.jsonObject }
    }
    private fun captureLogical(c: FiioCodec, name: String): List<ByteArray> = capturePackets(name).map {
        val p = bytes(it.getValue("data").jsonArray)
        c.logicalReply(it.getValue("reportId").jsonPrimitive.int, p, p.size)
    }
    /** Recorded fixtures lack a second closing slot poll. Reusing the opening reply here tests
     * assembly only; it is NOT evidence that firmware was stable throughout a live read.
     * OAK has genuine duplicate asynchronous events: dedupe explicitly for the import oracle;
     * the production assembler rejects unfiltered duplicate indices.
     */
    private fun capturedSnapshot(c: FiioCodec, name: String): FiioSnapshot {
        val packets = captureLogical(c, name)
        val slot = packets.first { it[4].toInt() == FiioCodec.SLOT }
        val count = packets.first { it[4].toInt() == FiioCodec.COUNT }
        val pg = packets.first { it[4].toInt() == FiioCodec.PREAMP }
        val bands = packets.filter { it[4].toInt() == FiioCodec.BAND }.distinctBy { it[6] }
        return c.snapshot(slot, count, pg, bands, slot)
    }

    @Test fun `fixture provenance hashes and literal capture identity`() {
        val manifest = json("provenance.json").jsonObject
        assertEquals(FiioCatalog.PINNED_COMMIT, manifest.getValue("pinnedCommit").jsonPrimitive.content)
        val files = manifest.getValue("files").jsonArray
        assertEquals(6, files.size)
        for (file in files) {
            val f = file.jsonObject
            val name = f.getValue("name").jsonPrimitive.content
            val digest = MessageDigest.getInstance("SHA-256").digest(resource(name)).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(f.getValue("sha256").jsonPrimitive.content, digest)
            assertTrue(f.getValue("url").jsonPrimitive.content.contains(FiioCatalog.PINNED_COMMIT))
            val d = json(name).jsonObject.getValue("device").jsonObject
            val c = assertNotNull(FiioCatalog.capturedRoute(d.getValue("vendorId").jsonPrimitive.int,
                d.getValue("productId").jsonPrimitive.int, d.getValue("productName").jsonPrimitive.content))
            assertEquals(d.getValue("productName").jsonPrimitive.content, c.productName)
        }
    }
    @Test fun `catalog contains every 32 exact rules not a VID fallback`() {
        val oracle = json("catalog-oracle.json").jsonArray
        assertEquals(32, FiioCatalog.rules.size)
        assertEquals(32, FiioCatalog.rules.map { it.productName }.toSet().size)
        assertEquals(oracle.map { it.jsonObject.getValue("productName").jsonPrimitive.content }.toSet(), FiioCatalog.rules.map { it.productName }.toSet())
        for (row in oracle) {
            val r = row.jsonObject
            val name = r.getValue("productName").jsonPrimitive.content
            val c = FiioCatalog.rules.single { it.productName == name }
            assertEquals(r.getValue("vendorIds").jsonArray.map { it.jsonPrimitive.int }.toSet(), c.vendorIds)
            assertEquals(r.getValue("maxFilters").jsonPrimitive.int, c.maxFilters)
            assertEquals(r.getValue("minGain").jsonPrimitive.double, c.minGainDb)
            assertEquals(r.getValue("saveCommand").jsonPrimitive.int, c.saveCommand)
            assertEquals(r.getValue("userSlots").jsonArray.map { it.jsonPrimitive.int }.toSet(), c.userSlots)
            assertEquals(r.getValue("bestGuess").jsonPrimitive.boolean, c.bestGuessProductName)
            assertTrue(c.userSlots.intersect(c.stockSlots).isEmpty())
            assertTrue(c.sourceUrl.contains(FiioCatalog.PINNED_COMMIT))
            assertEquals("beta-unverified", c.status)
            assertEquals(setOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF), c.typeCodes.keys)
            FiioCodec(c) // Configuration invariants, including all 32 models.
        }
        assertEquals(2, FiioCatalog.rules.count { it.codecBlockers.isNotEmpty() })
        assertEquals(6, FiioCatalog.rules.count { it.capture != null })
        assertNull(FiioCatalog.sourceRule(0x2972, "fiio ka15"))
        assertNull(FiioCatalog.capturedRoute(0x0A12, 0x4005, "Qudelix-5K USB DAC 48KHz"))
        assertNull(FiioCatalog.capturedRoute(0x2972, 0xFFFF, "FIIO QX13"))
        assertNull(FiioCatalog.capturedRoute(0x2972, 0x0104, "Unknown"))
        assertEquals(7, assertNotNull(FiioCatalog.capturedRoute(0x2972, 0x0104, "FIIO KA15")).reportId)
        assertNull(FiioCatalog.capturedRoute(0x2972, 0x0104, "FIIO QX13"))
    }
    @Test fun `all six captures decode complete native snapshots with exact inverse import`() {
        for (c in FiioCatalog.rules.filter { it.capture != null }.map { FiioCodec(it) }) {
            val fixture = c.config.capture!!.fixturePath.substringAfterLast('/')
            val s = capturedSnapshot(c, fixture)
            assertTrue(s.count <= c.config.maxFilters)
            val imported = c.importExact(s)
            assertEquals(s.count, imported.bands.size)
            assertEquals(s.preampTenths / 10.0, imported.preampDb)
            // Reading a stock/ambiguous slot is permitted; re-saving to it is not.
            val target = c.config.userSlots.first()
            val p = plan(c, imported.bands, imported.preampDb, target)
            assertTrue(c.matches(p.expected, s.copy(activeSlot = target)))
        }
        assertEquals(8, capturedSnapshot(codec("FIIO OAK NANO"), "fiio_fiio_oak_nano.json").count)
        assertEquals(10, codec("FIIO OAK NANO").config.maxFilters)
        assertEquals(-4, capturedSnapshot(codec("FIIO KA17"), "fiio_fiio_ka17.json").preampTenths)
        assertEquals(-40, capturedSnapshot(codec("SNOWSKY Melody"), "fiio_snowsky_melody.json").preampTenths)
    }
    @Test fun `JA11 and QX13 full write bytes reproduce pinned native capture not a stub`() {
        for (name in listOf("fiio_jadeaudio_ja11.json", "fiio_fiio_qx13.json")) {
            val obj = json(name).jsonObject
            val c = codec(obj.getValue("device").jsonObject.getValue("productName").jsonPrimitive.content)
            val s = capturedSnapshot(c, name)
            val eq = c.importExact(s)
            val p = plan(c, eq.bands, eq.preampDb, s.activeSlot)
            val recorded = obj.getValue("exchanges").jsonArray.map { bytes(it.jsonObject.getValue("send").jsonObject.getValue("data").jsonArray) }
            val compiled = (p.writes + p.save).map { it.payload() }
            assertEquals(recorded.size, compiled.size)
            recorded.zip(compiled).forEach { (a, b) -> assertContentEquals(a, b) }
        }
    }
    @Test fun `exact requests frame directions effects and no hidden slot change on read`() {
        val c = codec("FIIO KA15")
        // Sequence byte and CRC-8/MAXIM exactly as captured from fiiocontrol.fiio.com (03.10.2026, frames seq 3 and 4).
        repeat(3) { c.queryPreamp() }
        assertEquals(listOf(0xBB, 0x0B, 0, 3, 0x18, 0, 0xF1, 0xEE), c.queryCount().bytes)
        assertEquals(listOf(0xBB, 0x0B, 0, 4, 0x16, 0, 0x57, 0xEE), c.querySlot().bytes)
        fun masked(f: FiioFrame) = f.bytes.toMutableList().also { it[3] = 0; it[it.size - 2] = 0 }
        assertEquals(listOf(187, 11, 0, 0, 23, 0, 0, 238), masked(c.queryPreamp()))
        assertEquals(listOf(187, 11, 0, 0, 21, 1, 9, 0, 238), masked(c.queryBand(9)))
        assertTrue(c.activeReadQueries(10).all { it.effect == FiioEffect.READ_ONLY && it.bytes.first() == 0xBB })
        val p = plan(c, slot = 9)
        assertEquals(listOf(170, 10, 0, 0, 22, 1, 9, 0, 238), masked(p.selection))
        assertEquals(FiioEffect.SELECT_ACTIVE_USER_BANK, p.selection.effect)
        assertTrue(p.writes.all { it.effect == FiioEffect.WRITE_ACTIVE_BANK && it.bytes.first() == 0xAA })
        assertEquals(FiioEffect.SAVE_USER_BANK, p.save.effect)
        assertTrue(p.selectionVerification.effect == FiioEffect.READ_ONLY)
        assertEquals(listOf(22, 24, 23), p.backupQueries.map { it.bytes[4] })
        assertEquals(FiioCodec.SLOT, p.verificationQueries.last().bytes[4])
    }
    @Test fun `wrong header direction command declared length terminator short report and index rejected`() {
        val c = codec("FIIO KA15")
        val good = bandReply(plan(c).expected.bands.single())
        for (bad in listOf(good.copyOf(15), good.copyOf(17), good.copyOf().also { it[0] = 0xAA.toByte() },
            good.copyOf().also { it[1] = 0x0A }, good.copyOf().also { it[4] = 0x17 },
            good.copyOf().also { it[5] = 7 }, good.copyOf().also { it[15] = 0 },
            good.copyOf().also { it[6] = 1 }, good.copyOf().also { it[13] = 3 })) {
            assertFailsWith<IllegalArgumentException> { c.parseBand(bad, 0, 1) }
        }
        assertFailsWith<IllegalArgumentException> { c.parseBand(good, -1, 1) }
        assertFailsWith<IllegalArgumentException> { c.parseBand(good, 0, 0) }
        assertFailsWith<IllegalArgumentException> { c.parseBand(good, 0, 11) }
        assertFailsWith<IllegalArgumentException> { c.logicalReply(2, good, 16) }
        assertFailsWith<IllegalArgumentException> { c.logicalReply(7, good, 63) }
        assertFailsWith<IllegalArgumentException> { c.logicalReply(7, good.copyOf(7), 7) }
        assertFailsWith<IllegalArgumentException> { c.parseSlot(c.querySlot().payload()) }
        assertFailsWith<IllegalArgumentException> { c.parseCount(reply(FiioCodec.COUNT, listOf(11))) }
        assertFailsWith<IllegalArgumentException> { c.queryBand(10) }
        assertFailsWith<IllegalArgumentException> { c.activeBandQueries(-1) }
    }
    @Test fun `opaque sequence checksum and stale nonzero tail are not mistaken for payload`() {
        val c = codec("FIIO KA15")
        val raw = bytes(capturePackets("fiio_fiio_ka15.json").first().getValue("data").jsonArray)
        assertTrue(raw.drop(9).any { it.toInt() != 0 })
        val logical = c.logicalReply(7, raw, raw.size)
        assertEquals(9, logical.size)
        assertEquals(9, c.parseSlot(logical))
        assertFailsWith<IllegalArgumentException> { c.parseSlot(raw) }
        val bad = raw.copyOf().also { it[8] = 0 }
        assertFailsWith<IllegalArgumentException> { c.logicalReply(7, bad, bad.size) }
    }
    @Test fun `snapshot rejects duplicate missing extra count slot drift and malformed preamp`() {
        val c = codec("FIIO KA15")
        val s = plan(c, listOf(peak(0), peak(1))).expected
        val slot = reply(FiioCodec.SLOT, listOf(s.activeSlot))
        val count = reply(FiioCodec.COUNT, listOf(2))
        val pg = reply(FiioCodec.PREAMP, pair(0))
        val r = s.bands.map { bandReply(it) }
        assertEquals(s, c.snapshot(slot, count, pg, r.reversed(), slot))
        for (bad in listOf(listOf(r[0], r[0]), r.dropLast(1), r + r[0], listOf(r[0], bandReply(s.bands[1].copy(index = 2))))) {
            assertFailsWith<IllegalArgumentException> { c.snapshot(slot, count, pg, bad, slot) }
        }
        assertFailsWith<IllegalArgumentException> { c.snapshot(slot, count, pg, r, reply(FiioCodec.SLOT, listOf(8))) }
        assertFailsWith<IllegalArgumentException> { c.snapshot(slot, count, byteArrayOf(), r, slot) }
        val oak = codec("FIIO OAK NANO")
        val packets = captureLogical(oak, "fiio_fiio_oak_nano.json")
        val bands = packets.filter { it[4].toInt() == FiioCodec.BAND }
        assertTrue(bands.size > 8) // actual duplicate events, not fabricated evidence
        assertFailsWith<IllegalArgumentException> { oak.snapshot(packets.first { it[4].toInt() == 22 }, packets.first { it[4].toInt() == 24 }, packets.first { it[4].toInt() == 23 }, bands, packets.first { it[4].toInt() == 22 }) }
    }
    @Test fun `native quantization negative pregain zero is written and disabled band is zero gain`() {
        val c = codec("FIIO KA15")
        val b = peak().copy(freqHz = 1000.9, gainDb = -3.26, q = 0.716)
        val p = plan(c, listOf(b), -4.35)
        assertEquals(FiioRegisters(0, 1000, -32, 72, 0), p.expected.bands.single())
        assertEquals(-43, p.expected.preampTenths)
        assertEquals(listOf(255, 213), p.writes.first().bytes.subList(6, 8))
        assertEquals(-43, c.parsePreamp(reply(FiioCodec.PREAMP, pair(-43))))
        val zero = plan(c, preamp = 0.0)
        assertEquals(listOf(0, 0), zero.writes.first().bytes.subList(6, 8))
        assertEquals(0, plan(c, listOf(b.copy(enabled = false))).expected.bands.single().gainTenths)
        assertEquals(1, plan(c, listOf(peak().copy(type = FilterType.LOW_SHELF))).expected.bands.single().typeCode)
        assertEquals(2, plan(c, listOf(peak().copy(type = FilterType.HIGH_SHELF))).expected.bands.single().typeCode)
    }
    @Test fun `each model preserves capacity range save command and denies every forbidden slot`() {
        for (c in FiioCatalog.rules.filter { it.codecBlockers.isEmpty() }.map { FiioCodec(it) }) {
            val bands = List(c.config.maxFilters) { peak(it) }
            val p = plan(c, bands, c.config.minGainDb)
            assertEquals(c.config.maxFilters, p.expected.count)
            assertEquals(c.config.maxFilters + 2, p.writes.size)
            assertEquals(c.config.saveCommand, p.save.bytes[4])
            assertEquals(c.config.maxFilters - 1, p.expected.bands.last().index)
            assertEquals(p.expected, snapshot(c, p.expected))
            assertFailsWith<IllegalArgumentException> { plan(c, bands + peak(), 0.0) }
            assertFailsWith<IllegalArgumentException> { plan(c, preamp = c.config.minGainDb - 0.1) }
            assertFailsWith<IllegalArgumentException> { plan(c, preamp = c.config.maxGainDb + 0.1) }
            for (slot in (0..255).filter { it !in c.config.userSlots }) {
                assertFailsWith<IllegalArgumentException> { plan(c, slot = slot) }
                assertFailsWith<IllegalArgumentException> { c.selectUserSlot(slot) }
            }
        }
        for (name in listOf("FIIO KA17", "FIIO Q7", "FIIO KA17 (MQA HID)", "FIIO BT11", "FIIO BT11 (UAC1.0)", "FIIO Air Link")) {
            assertEquals(setOf(8, 9), codec(name).config.userSlots)
        }
        for (c in FiioCatalog.rules.filter { it.codecBlockers.isNotEmpty() }.map { FiioCodec(it) }) {
            assertFailsWith<IllegalArgumentException> { c.planOnExplicitSend(listOf(peak()), 0.0, 3, true) }
        }
    }
    @Test fun `NaN infinity range unsupported type missing preamp and implicit action rejected atomically`() {
        val c = codec("FIIO KA15")
        for (x in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { plan(c, preamp = x) }
            for (b in listOf(peak().copy(freqHz = x), peak().copy(gainDb = x), peak().copy(q = x), peak().copy(gainDb = x, enabled = false))) {
                assertFailsWith<IllegalArgumentException> { plan(c, listOf(b)) }
            }
        }
        for (b in listOf(peak().copy(freqHz = 19.0), peak().copy(freqHz = 20001.0), peak().copy(gainDb = -12.1),
            peak().copy(gainDb = 12.1), peak().copy(q = 0.09), peak().copy(q = 10.01), peak().copy(q = 0.0),
            peak().copy(type = FilterType.LOW_PASS), peak().copy(type = FilterType.HIGH_PASS))) {
            assertFailsWith<IllegalArgumentException> { plan(c, listOf(peak(), b)) }
        }
        assertFailsWith<IllegalArgumentException> { plan(c, preamp = null) }
        assertFailsWith<IllegalArgumentException> { c.planOnExplicitSend(listOf(peak()), 0.0, 7, false) }
        val profile = Profile("p", "Original", bands = listOf(peak()), preampDb = null, createdAt = 0, updatedAt = 0)
        assertFailsWith<IllegalArgumentException> { c.planOnExplicitSend(profile, 7, true) }
        assertEquals(null, profile.preampDb)
        assertEquals(1, profile.bands.size)
    }
    @Test fun `31 bands v2 save tail mismatch and no automatic truncation`() {
        val c = codec("FIIO K19")
        val p = plan(c, List(31) { peak(it).copy(gainDb = -24.0) }, -24.0, 16)
        assertEquals(31, p.expected.count)
        assertEquals(0x21, p.save.bytes[4])
        assertEquals(31, c.importExact(snapshot(c, p.expected)).bands.size)
        assertTrue(c.matches(p.expected, p.expected))
        assertFalse(c.matches(p.expected, p.expected.copy(bands = p.expected.bands.map { if (it.index == 30) it.copy(gainTenths = -239) else it })))
        assertFalse(c.matches(p.expected, p.expected.copy(preampTenths = -239)))
        assertFalse(c.matches(p.expected, p.expected.copy(activeSlot = 15)))
        assertFailsWith<IllegalArgumentException> { c.matches(p.expected, p.expected.copy(bands = p.expected.bands.dropLast(1))) }
        assertFailsWith<IllegalArgumentException> { plan(c, List(32) { peak(it) }) }
        for (name in listOf("FIIO K17", "FIIO BTR17", "BTR17")) assertEquals(0x21, plan(codec(name)).save.bytes[4])
        assertEquals(0x19, plan(codec("FIIO KA15")).save.bytes[4])
        assertEquals(16, c.parseSaveSlot(reply(0x21, listOf(16)), 16))
        assertFailsWith<IllegalArgumentException> { c.parseSaveSlot(reply(0x19, listOf(16)), 16) }
        assertFailsWith<IllegalArgumentException> { c.parseSaveSlot(reply(0x21, listOf(15)), 16) }
    }
    @Test fun `source compensation inverse and impossible native Q or shelf fail closed`() {
        val c = codec("FIIO QX13")
        val p = plan(c, listOf(peak().copy(gainDb = 6.0, q = 1.0)))
        assertEquals(141, p.expected.bands.single().qHundredths)
        val eq = c.importExact(p.expected)
        assertTrue(c.matches(p.expected, plan(c, eq.bands, eq.preampDb).expected))
        assertFailsWith<IllegalArgumentException> { plan(c, listOf(peak().copy(gainDb = -24.0, q = 10.0))) }
        assertFailsWith<IllegalArgumentException> { plan(c, listOf(peak().copy(type = FilterType.LOW_SHELF, gainDb = -24.0, q = 10.0))) }
        val shelf = plan(c, listOf(peak().copy(type = FilterType.LOW_SHELF, gainDb = 6.0, q = 1.0)))
        assertEquals(71, shelf.expected.bands.single().qHundredths)
        val imported = c.importExact(shelf.expected)
        assertTrue(c.matches(shelf.expected, plan(c, imported.bands, imported.preampDb).expected))
    }
    @Test fun `send selection backup barrier post read and disconnect never fake success`() {
        val c = codec("JadeAudio JA11")
        val p = plan(c)
        val selected = reply(FiioCodec.SLOT, listOf(3))
        assertEquals(p.writes, c.writesAfterSelection(p, selected, p.expected))
        assertFailsWith<IllegalArgumentException> { c.writesAfterSelection(p, reply(FiioCodec.SLOT, listOf(0)), p.expected) }
        assertFailsWith<IllegalArgumentException> { c.writesAfterSelection(p, selected, p.expected.copy(activeSlot = 0)) }
        assertEquals(FiioSaveVerification.RECONNECT_AND_READ_REQUIRED, p.saveVerification)
        assertTrue(c.verification(p, null) is FiioVerification.Pending)
        assertTrue(c.verification(p, p.expected) is FiioVerification.Pending)
        assertEquals(FiioVerification.RegistersMatchPersistenceUnproven, c.verification(p, p.expected, reconnected = true))
        assertTrue(c.verification(p, p.expected.copy(preampTenths = 1), true) is FiioVerification.Mismatch)
        val ka = codec("FIIO KA15")
        val kp = plan(ka)
        assertEquals(FiioSaveVerification.POST_SAVE_READ_REQUIRED, kp.saveVerification)
        assertTrue(ka.verification(kp, null) is FiioVerification.Pending)
        assertEquals(FiioVerification.RegistersMatchPersistenceUnproven, ka.verification(kp, kp.expected))
    }
    @Test fun `raw register boundaries unsupported shapes and compensated exact edges`() {
        val c = codec("FIIO KA15")
        for (gain in listOf(-12.0, 12.0)) {
            val p = plan(c, listOf(peak().copy(freqHz = 20000.0, gainDb = gain, q = 10.0)), gain)
            assertEquals((gain * 10).toInt(), p.expected.bands.single().gainTenths)
            assertEquals(1000, p.expected.bands.single().qHundredths)
            assertEquals(p.expected, snapshot(c, p.expected))
        }
        val valid = plan(c).expected
        for (bad in listOf(valid.bands.single().copy(frequencyHz = 0), valid.bands.single().copy(frequencyHz = 20001),
            valid.bands.single().copy(gainTenths = -121), valid.bands.single().copy(gainTenths = 121),
            valid.bands.single().copy(qHundredths = 0), valid.bands.single().copy(qHundredths = 1001),
            valid.bands.single().copy(typeCode = 6))) {
            assertFailsWith<IllegalArgumentException> { c.parseBand(bandReply(bad), 0, 1) }
            assertFailsWith<IllegalArgumentException> { c.importExact(valid.copy(bands = listOf(bad))) }
        }
        assertFailsWith<IllegalArgumentException> { c.parsePreamp(reply(FiioCodec.PREAMP, pair(-121))) }
        assertFailsWith<IllegalArgumentException> { c.parseCount(reply(FiioCodec.COUNT, listOf(255))) }
        val qx = codec("FIIO QX13")
        for (gain in -240..120) for (q in listOf(10, 1000)) {
            val native = FiioSnapshot("FIIO QX13", 160, 1, 0, listOf(FiioRegisters(0, 1000, gain, q, 0)))
            val eq = qx.importExact(native)
            assertTrue(qx.matches(native, plan(qx, eq.bands, eq.preampDb, 160).expected))
        }
    }
    @Test fun `explicit zero count is not default capacity and active count eight reads fully`() {
        val c = codec("FIIO OAK NANO")
        val s = capturedSnapshot(c, "fiio_fiio_oak_nano.json")
        assertEquals((0..7).toList(), c.activeBandQueries(s.count).map { it.bytes[6] })
        assertEquals(8, plan(c, c.importExact(s).bands, 0.0).expected.count)
        val zero = plan(c, emptyList(), 0.0)
        assertEquals(0, zero.expected.count)
        assertEquals(2, zero.writes.size)
        assertEquals(emptyList(), c.activeBandQueries(0))
        assertEquals(zero.expected, snapshot(c, zero.expected))
        assertEquals(0, c.importExact(zero.expected).bands.size)
    }
    @Test fun `KA15 keeps ten filters so the tail is padded neutral and Q readback drift is tolerated`() {
        val ka = codec("FIIO KA15")
        val three = List(3) { peak(it).copy(gainDb = -3.0, q = 3.9) }
        val padded = ka.padToDeviceCount(three)
        assertEquals(10, padded.size)
        assertTrue(padded.drop(3).all { it.gainDb == 0.0 && it.type == FilterType.PEAK })
        val p = plan(ka, padded, -3.0)
        assertEquals(10, p.expected.count)
        assertEquals(10, p.writes.count { it.bytes[4] == FiioCodec.BAND })
        val drift = p.expected.copy(bands = p.expected.bands.map { if (it.index < 3) it.copy(qHundredths = it.qHundredths + 2) else it })
        assertTrue(ka.matches(p.expected, drift))
        assertFalse(ka.matches(p.expected, p.expected.copy(bands = p.expected.bands.map { if (it.index == 0) it.copy(qHundredths = it.qHundredths + 40) else it })))
        assertFalse(ka.matches(p.expected, p.expected.copy(bands = p.expected.bands.map { if (it.index == 0) it.copy(gainTenths = -29) else it })))
        // Every other FiiO config stays exact and unpadded.
        val c = codec("FIIO K13 R2R")
        assertEquals(three, c.padToDeviceCount(three))
        val q = plan(c, listOf(peak().copy(q = 3.9))).expected
        assertFalse(c.matches(q, q.copy(bands = q.bands.map { it.copy(qHundredths = it.qHundredths + 1) })))
    }
}
