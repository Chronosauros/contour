package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.test.*

/** Real pinned capture bytes + explicitly synthetic adversarial/state frames. No hardware claims. */
class NativeKtFosiTest {
    private val session = "connection-generation-1"
    private fun fixture(path: String): JsonObject = Json.parseToJsonElement(
        requireNotNull(javaClass.getResourceAsStream("/$path")) { "Missing fixture $path" }
            .bufferedReader().use { it.readText() }).jsonObject
    private fun bytes(o: JsonObject): ByteArray = o.getValue("data").jsonArray.map {
        val n = it.jsonPrimitive.int; require(n in 0..255); n.toByte()
    }.toByteArray()
    private val ktFiles = listOf("chu2_dsp", "kiwi_ears_allegro_mini", "kiwi_ears_allegro_pro",
        "kiwi_ears_chorus", "kiwi_ears_slink", "kt02h20_hifi_audio", "tanchjim_bunny_dsp",
        "tanchjim_fission_dsp", "tanchjim_one_dsp")
    private fun ktReplies(name: String): List<KtMicroCodec.Reply> =
        fixture("ktmicro/ktmicro_$name.json").getValue("exchanges").jsonArray.flatMap { e ->
            val x = e.jsonObject; val tx = bytes(x.getValue("send").jsonObject)
            if (u(tx[4]) != KtMicroCodec.READ) emptyList() else x.getValue("responses").jsonArray.map { r ->
                val o = r.jsonObject
                KtMicroCodec.Reply(session, o.getValue("reportId").jsonPrimitive.int, bytes(o))
            }
        }
    private fun ktModel(name: String = "Kiwi Ears-Allegro Mini") = KtMicroCatalog.models.single { it.productName == name }
    private fun ktBefore() = KtMicroCodec.snapshot(ktModel(), session, ktReplies("kiwi_ears_allegro_mini"))
    private fun peak(gain: Double = 0.0, q: Double = 1.0, freq: Double = 1000.0) = Band("p", FilterType.PEAK, freq, gain, q)
    private fun profile(bands: List<Band> = listOf(peak()), preamp: Double? = 0.0) =
        Profile("id", "test", bands = bands, preampDb = preamp, createdAt = 0, updatedAt = 0)
    private fun ktResponse(r: KtMicroCodec.RegisterValue) = KtMicroCodec.Reply(session, 0x4B,
        byteArrayOf(r.register.toByte(), 0, 0, 0, 0x52, 0) + r.value.map { it.toByte() }.toByteArray())
    // Synthetic 9E/9F state frames: DS3 fixture has no actual enable/sample-format capture.
    private fun fosiResponse(cmd: Int, data: List<Int>): FosiCodec.Reply = FosiCodec.Reply(session, 1,
        FosiCodec.ReportKind.FEATURE, ByteArray(63).also { p ->
            p[0] = 0x77; p[1] = cmd.toByte(); data.forEachIndexed { i, n -> p[i + 2] = n.toByte() }
        })
    private fun state(enabled: Boolean = true, mode: Int = 7) = fosiResponse(0x9E, listOf(if (enabled) 1 else 0, mode))
    private fun sample(rate: Int = 48000, dsd: Int = 0) = fosiResponse(0x9F,
        (0..3).map { (rate ushr (8 * it)) and 255 } + dsd)
    private fun fosiCaptured(): List<FosiCodec.Reply> =
        fixture("fosi/fosi_audio_ds3.json").getValue("exchanges").jsonArray.flatMap { e ->
            val x = e.jsonObject; val tx = bytes(x.getValue("send").jsonObject)
            if (u(tx[1]) != 0x8E || u(tx[3]) >= 8) emptyList() else x.getValue("responses").jsonArray.map { r ->
                FosiCodec.replyFromRaw(session, FosiCodec.ReportKind.FEATURE, bytes(r.jsonObject))
            }
        }
    // Captured packets evidence eight individual bands, NOT physical firmware or full bank layout.
    // Qualify ONLY the isolated SYNTHETIC eight-band codec fixture; never NativeSession.
    private val synthetic8 = FosiCodec.Layout.SYNTHETIC_EIGHT_BAND_CUSTOM1
    private fun fosiBefore() = FosiCodec.snapshot(session, state(), sample(), fosiCaptured(), synthetic8)
    private fun fosiDiagnostic() = FosiCodec.snapshot(session, state(), sample(), fosiCaptured())
    @Test fun `DS3 eight captured bands without layout evidence cannot compile`() {
        assertFailsWith<IllegalArgumentException> { FosiCodec.compile(profile(), fosiDiagnostic()) }
    }
    @Test fun `DS3 eight captured bands without layout evidence cannot import`() {
        assertFailsWith<IllegalArgumentException> { fosiDiagnostic().importProfile("i", "n", 0) }
    }
    @Test fun `DS3 eight captured bands without layout evidence cannot verify crafted plan`() {
        val before = fosiDiagnostic()
        val plan = FosiCodec.Plan(session, emptyList(), before.bands, before.state, before.sampleFormat, emptyList())
        assertFailsWith<IllegalArgumentException> { FosiCodec.verify(plan, state(), sample(), fosiCaptured()) }
    }
    private fun fosiBandResponse(b: FosiCodec.RawBand) = FosiCodec.Reply(session, 1, FosiCodec.ReportKind.FEATURE,
        FosiCodec.encodeRawBand(b).also { it[1] = 0x8E.toByte() })
    private fun u(b: Byte) = b.toInt() and 255

    @Test fun `KT read payload does not contain report ID`() {
        val f = KtMicroCodec.readRegister(0x26)
        assertEquals(0x4B, f.reportId)
        assertContentEquals(byteArrayOf(0x26, 0, 0, 0, 0x52, 0, 0, 0, 0, 0), f.payload)
        assertFalse(f.mutating); assertEquals(10, f.minimumDescriptorPayloadBytes)
        assertContentEquals(byteArrayOf(0x24, 0, 0, 0, 0x52, 0, 3, 0, 0, 0), KtMicroCodec.readRegister(0x24).payload)
    }
    @Test fun `catalog contains ten literal names and three maps without broad writer fallback`() {
        assertEquals(10, KtMicroCatalog.models.size)
        assertEquals(7, KtMicroCatalog.models.count { it.admitted })
        assertEquals(3, KtMicroCatalog.pidRegisterMaps.size)
        assertNull(KtMicroCatalog.find(0x31B2, 0x0111, null))
        assertNull(KtMicroCatalog.find(0x31B2, 0x0111, "kiwi ears-allegro mini"))
        assertNull(KtMicroCatalog.find(0x31B2, 0x0112, "Kiwi Ears-Allegro Mini"))
        assertNull(KtMicroCatalog.find(0x31B3, 0x0111, "Kiwi Ears-Allegro Mini"))
        assertNull(KtMicroCatalog.find(0x31B2, 0x0001, "CDSP"))
        assertNotNull(KtMicroCatalog.find(0x31B2, 0x0111, "Kiwi Ears Allegro Mini"))
        assertTrue(KtMicroCatalog.models.single { it.productName == "TANCHJIM-FISSION  DSP" }.blockedReason != null)
    }
    @Test fun `ninety real KT register values roundtrip without signedness or map loss`() {
        var registers = 0
        ktFiles.forEach { name ->
            val map = when (name) {
                "kiwi_ears_chorus" -> KtMicroCatalog.kt1132
                "kiwi_ears_slink" -> KtMicroCatalog.kt3016
                else -> KtMicroCatalog.kt0211
            }
            val replies = ktReplies(name)
            map.bands.forEachIndexed { index, pair ->
                val gf = KtMicroCodec.parseRegister(replies.single { u(it.payload[0]) == pair.gainFrequency }, session, pair.gainFrequency)
                val qt = KtMicroCodec.parseRegister(replies.single { u(it.payload[0]) == pair.qType }, session, pair.qType)
                val raw = KtMicroCodec.decodeBand(index, map, gf, qt)
                assertEquals(listOf(gf, qt), KtMicroCodec.encodeRawBand(map, raw)); registers += 2
            }
        }
        assertEquals(90, registers)
    }
    @Test fun `KT actual Mini and JCally write packets match captured signed register bytes`() {
        listOf("kiwi_ears_allegro_mini" to "Kiwi Ears-Allegro Mini", "kt02h20_hifi_audio" to "KT02H20 HIFI Audio").forEach { (file, modelName) ->
            val before = KtMicroCodec.snapshot(ktModel(modelName), session, ktReplies(file))
            val plan = KtMicroCodec.compile(before.importProfile("i", "n", 0), before)
            val captured = fixture("ktmicro/ktmicro_$file.json").getValue("exchanges").jsonArray.map { e ->
                bytes(e.jsonObject.getValue("send").jsonObject)
            }.filter { u(it[4]) == 0x57 && u(it[0]) in 0x26..0x2F }
            assertEquals(10, captured.size)
            captured.zip(plan.frames.take(10)).forEach { (expected, frame) -> assertContentEquals(expected, frame.payload) }
        }
    }
    @Test fun `KT out of policy captures remain readable but cannot expand writer numeric limits`() {
        listOf("kiwi_ears_allegro_pro" to "Kiwi Ears-Allegro PRO", "tanchjim_one_dsp" to "TANCHJIM-ONE DSP", "chu2_dsp" to "Chu2 DSP").forEach { (file, modelName) ->
            val before = KtMicroCodec.snapshot(ktModel(modelName), session, ktReplies(file))
            val imported = before.importProfile("i", "n", 0)
            assertEquals(5, imported.bands.size)
            assertFailsWith<IllegalArgumentException> { KtMicroCodec.compile(imported, before) }
        }
    }
    @Test fun `KT 3016 read mapping retains logical order and never phantom 3E`() {
        assertEquals(listOf(0x37, 0x3B, 0x3D, 0x39, 0x35), KtMicroCatalog.kt3016.bands.map { it.gainFrequency })
        assertEquals(0x3F, KtMicroCatalog.kt3016.bands[2].qType)
        assertTrue(KtMicroCatalog.pidRegisterMaps.flatMap { it.bands }.none { it.qType == 0x3E })
        assertNotNull(KtMicroCatalog.kt1132.blockedReason); assertNotNull(KtMicroCatalog.kt3016.blockedReason)
        assertNull(KtMicroCatalog.find(0x31B2, 0x3016, "CDSP"))
    }
    @Test fun `KT unknown native types are rejected instead of coerced to peak`() {
        val pair = KtMicroCatalog.kt0211.bands[0]
        val gf = KtMicroCodec.RegisterValue(pair.gainFrequency, listOf(0, 0, 100, 0))
        val qt = KtMicroCodec.RegisterValue(pair.qType, listOf(232, 3, 2, 0))
        assertFailsWith<IllegalArgumentException> { KtMicroCodec.decodeBand(0, KtMicroCatalog.kt0211, gf, qt) }
    }
    @Test fun `KT replies must match connection ID register command header and minimum length`() {
        val good = ktReplies("kiwi_ears_allegro_mini")[1]
        assertFailsWith<IllegalArgumentException> { KtMicroCodec.parseRegister(good, "other", 0x26) }
        assertFailsWith<IllegalArgumentException> { KtMicroCodec.parseRegister(good.copy(reportId = 1), session, 0x26) }
        assertFailsWith<IllegalArgumentException> { KtMicroCodec.parseRegister(good, session, 0x28) }
        assertFailsWith<IllegalArgumentException> { KtMicroCodec.parseRegister(good, session, 0x26, 0x57) }
        assertFailsWith<IllegalArgumentException> { KtMicroCodec.parseRegister(good.copy(payload = good.payload.copyOf(9)), session, 0x26) }
        assertFailsWith<IllegalArgumentException> { KtMicroCodec.parseRegister(good.copy(payload = good.payload.copyOf().also { it[1] = 1 }), session, 0x26) }
        assertEquals(0x26, KtMicroCodec.parseRegister(good.copy(payload = good.payload.copyOf(64)), session, 0x26).register)
    }
    @Test fun `KT snapshot requires every register once including read only pregain`() {
        val replies = ktReplies("kiwi_ears_allegro_mini")
        assertFailsWith<IllegalArgumentException> { KtMicroCodec.snapshot(ktModel(), session, replies.dropLast(1)) }
        assertFailsWith<IllegalArgumentException> { KtMicroCodec.snapshot(ktModel(), session, replies.dropLast(1) + replies.first()) }
        val before = ktBefore(); assertEquals(5, before.bands.size); assertEquals(12, before.registers.size)
        assertEquals(listOf(3, 0, 0, 1), before.registers.single { it.register == 0x24 }.value)
        assertEquals(0, before.pregainDb)
    }
    @Test fun `KT ASCII year and Fission slot one are not custom state`() {
        listOf("kiwi_ears_chorus", "kiwi_ears_slink", "tanchjim_fission_dsp").forEach { name ->
            val r = ktReplies(name).first()
            assertFailsWith<IllegalArgumentException> { KtMicroCodec.parseSlot(KtMicroCodec.parseRegister(r, session, 0x24)) }
        }
    }
    @Test fun `KT import is exact uncompensated register interpretation`() {
        val imported = ktBefore().importProfile("copy", "captured", 42)
        assertEquals(100.0, imported.bands[0].freqHz)
        assertEquals(-1.0, imported.bands[1].gainDb)
        assertEquals(FilterType.LOW_SHELF, imported.bands[1].type)
        assertEquals(0.707, imported.bands[1].q)
        assertEquals(0.0, imported.preampDb); assertEquals(42L, imported.createdAt)
    }
    @Test fun `KT 2X is only JCally and inverse imports without compounding`() {
        val model = ktModel("KT02H20 HIFI Audio")
        assertTrue(model.compensate2X); assertTrue(model.peakOnly)
        assertEquals(listOf(model), KtMicroCatalog.models.filter { it.compensate2X })
        val before = KtMicroCodec.snapshot(model, session, ktReplies("kt02h20_hifi_audio"))
        assertEquals(300.0, before.importProfile("i", "n", 0).bands[0].freqHz)
        val plan = KtMicroCodec.compile(profile(listOf(peak(freq = 301.0))), before)
        assertEquals(151, plan.expectedBands[0].storedFrequencyHz)
        assertFailsWith<IllegalArgumentException> { KtMicroCodec.compile(profile(listOf(peak().copy(type = FilterType.LOW_SHELF))), before) }
    }
    @Test fun `KT compiles ten writes save and complete expected state before returning`() {
        val plan = KtMicroCodec.compile(profile(listOf(peak(gain = -2.25))), ktBefore())
        assertEquals(11, plan.frames.size); assertEquals(10, plan.frames.count { u(it.payload[4]) == 0x57 })
        assertEquals(-23, plan.expectedBands.first().gainTenths) // -2.25 rounds DOWN (a cut never gets shallower); it was -22 (nearest)
        for (k in -100..100) assertEquals(k, KtMicroCodec.compile(profile(listOf(peak(gain = k / 10.0))), ktBefore()).expectedBands.first().gainTenths)
        val rnd = java.util.Random(11)
        repeat(1000) {
            val g = Math.round((rnd.nextDouble() * 20 - 10) * 10000) / 10000.0
            val t = KtMicroCodec.compile(profile(listOf(peak(gain = g))), ktBefore()).expectedBands.first().gainTenths
            assertTrue(t / 10.0 <= g + 1e-6 && t / 10.0 > g - 0.1 - 1e-6, "KT gain $g sent as ${t / 10.0}")
        }
        assertEquals(0x53, u(plan.frames.last().payload[4])); assertEquals(1000, plan.frames.last().delayAfterMs)
        assertFalse(plan.frames.last().expectsReply); assertTrue(plan.reconnectAfterSave)
        assertTrue(plan.frames.all { it.mutating && it.payload.size == 10 })
        assertTrue(plan.frames.none { u(it.payload[0]) == 0x66 || u(it.payload[4]) == 0x43 })
        assertEquals(12, plan.verificationQueries.size); assertTrue(plan.verificationQueries.none { it.mutating })
        assertEquals(100, plan.expectedBands[4].storedFrequencyHz); assertEquals(0, plan.expectedBands[4].gainTenths)
        assertEquals(ktBefore().registers.single { it.register == 0x24 }, plan.expectedRegisters.single { it.register == 0x24 })
    }
    @Test fun `KT enabled only by compile when validated slot is off`() {
        val replies = ktReplies("kiwi_ears_allegro_mini").map {
            if (u(it.payload[0]) == 0x24) it.copy(payload = it.payload.copyOf().also { p -> p[6] = 2; p[9] = 0 }) else it
        }
        val before = KtMicroCodec.snapshot(ktModel(), session, replies)
        assertTrue(KtMicroCodec.readQueries(ktModel()).none { it.mutating })
        val plan = KtMicroCodec.compile(profile(), before)
        assertEquals(12, plan.frames.size); assertEquals(0x24, u(plan.frames[0].payload[0]))
        assertEquals(3, u(plan.frames[0].payload[6])); assertTrue(plan.frames[0].expectsReply)
    }
    @Test fun `KT pregain read is signed but no pregain writes are authorized`() {
        assertEquals(-6, KtMicroCodec.parsePregain(KtMicroCodec.RegisterValue(0x66, listOf(250, 0, 0, 0))))
        val nonzero = ktReplies("kiwi_ears_allegro_mini").map {
            if (u(it.payload[0]) == 0x66) it.copy(payload = it.payload.copyOf().also { p -> p[6] = 250.toByte() }) else it
        }
        val before = KtMicroCodec.snapshot(ktModel(), session, nonzero)
        assertEquals(-6.0, before.importProfile("i", "n", 0).preampDb)
        assertFailsWith<IllegalArgumentException> { KtMicroCodec.compile(profile(), before) }
        assertFailsWith<IllegalArgumentException> { KtMicroCodec.compile(profile(preamp = -1.0), ktBefore()) }
        assertFailsWith<IllegalArgumentException> { KtMicroCodec.compile(profile(listOf(peak(gain = 3.0)), null), ktBefore()) }
        assertEquals(11, KtMicroCodec.compile(profile(listOf(peak(gain = 3.0)), 0.0), ktBefore()).frames.size)
    }
    @Test fun `KT numeric constraints reject misleading twelve dB and Q above five`() {
        val before = ktBefore()
        listOf(peak(gain = 10.01), peak(gain = -12.0), peak(q = 5.01), peak(q = 0.099),
            peak(freq = 65536.0), peak(freq = Double.NaN), peak(gain = Double.POSITIVE_INFINITY),
            peak().copy(type = FilterType.LOW_PASS)).forEach { b ->
            assertFailsWith<IllegalArgumentException> { KtMicroCodec.compile(profile(listOf(b)), before) }
        }
        assertFailsWith<IllegalArgumentException> { KtMicroCodec.compile(profile(List(6) { peak() }), before) }
        assertFailsWith<IllegalArgumentException> { KtMicroCodec.compile(profile(List(4) { peak() } + peak(q = Double.NaN)), before) }
        assertFailsWith<IllegalArgumentException> { KtMicroCodec.compile(profile(), before, 2) }
        assertEquals(5000, KtMicroCodec.compile(profile(listOf(peak(q = 5.0, gain = 10.0))), before).expectedBands[0].qMilli)
    }
    @Test fun `KT verify compares complete raw state not just band count`() {
        val plan = KtMicroCodec.compile(profile(), ktBefore())
        val replies = plan.expectedRegisters.map { ktResponse(it) }
        assertEquals(5, KtMicroCodec.verify(plan, replies).bands.size)
        val changed = replies.map { if (u(it.payload[0]) == 0x66) it.copy(payload = it.payload.copyOf().also { p -> p[7] = 3 }) else it }
        assertFailsWith<IllegalArgumentException> { KtMicroCodec.verify(plan, changed) }
    }
    @Test fun `KT blocked Bunny cannot compile even with valid slot and zero pregain`() {
        val replies = ktReplies("tanchjim_bunny_dsp").map {
            if (u(it.payload[0]) == 0x66) it.copy(payload = it.payload.copyOf().also { p -> p[6] = 0 }) else it
        }
        val before = KtMicroCodec.snapshot(ktModel("TANCHJIM BUNNY DSP"), session, replies)
        assertFailsWith<IllegalArgumentException> { KtMicroCodec.compile(profile(), before) }
    }
    @Test fun `DS3 matches exact VID PID and name never shared Topping vendor`() {
        assertTrue(FosiCodec.matches(0x152A, 0x88DB, "Fosi Audio DS3"))
        assertFalse(FosiCodec.matches(0x152A, 0x8750, "Fosi Audio DS3"))
        assertFalse(FosiCodec.matches(0x152A, 0x88DB, null))
        assertFalse(FosiCodec.matches(0x152A, 0x88DB, "fosi audio ds3"))
    }
    @Test fun `DS3 captured eight float register packets roundtrip byte exact`() {
        val capture = fixture("fosi/fosi_audio_ds3.json")
        val writes = capture.getValue("exchanges").jsonArray.map { it.jsonObject }.filter {
            val p = bytes(it.getValue("send").jsonObject); u(p[1]) == 0x8D && u(p[3]) < 8
        }.map { bytes(it.getValue("send").jsonObject) }
        val replies = fosiCaptured(); assertEquals(8, replies.size)
        replies.forEachIndexed { i, reply ->
            val b = FosiCodec.parseBand(reply, session, 7, i)
            assertContentEquals(writes[i], FosiCodec.encodeRawBand(b))
        }
        val band0 = FosiCodec.parseBand(replies[0], session, 7, 0)
        assertEquals(20.0, band0.frequencyHz); assertEquals(1.0, band0.q)
        assertTrue(abs(band0.gainDb - 9.7) < 0.00001)
    }
    @Test fun `DS3 Feature and Output are distinct and read never changes active bank`() {
        val queries = FosiCodec.readQueries(FosiCodec.State(false, 4))
        assertTrue(queries.none { it.mutating })
        assertTrue(queries.none { it.payload.size > 1 && u(it.payload[1]) == 0x8A })
        val output = queries.filter { it.kind == FosiCodec.ReportKind.OUTPUT }
        assertEquals(listOf(0x9E, 0x9F), output.map { u(it.payload[1]) })
        val bandReads = queries.filter { it.payload.size > 1 && u(it.payload[1]) == 0x8E }
        assertEquals(8, bandReads.size); assertTrue(bandReads.all { it.kind == FosiCodec.ReportKind.FEATURE && u(it.payload[2]) == 4 })
        assertTrue(queries.all { it.requiredDescriptorPayloadBytes == 63 && it.reportId == 1 })
    }
    @Test fun `DS3 true enable and mode read is not constant seven`() {
        assertEquals(FosiCodec.State(false, 4), FosiCodec.parseState(state(false, 4), session))
        assertFailsWith<IllegalArgumentException> { FosiCodec.parseState(fosiResponse(0x9E, listOf(2, 7)), session) }
        assertFailsWith<IllegalArgumentException> { FosiCodec.parseState(state().copy(kind = FosiCodec.ReportKind.OUTPUT), session) }
        assertFailsWith<IllegalArgumentException> { FosiCodec.parseState(state(), "other") }
    }
    @Test fun `DS3 report normalization strips exactly one prefix`() {
        val reply = fosiCaptured().first()
        assertContentEquals(reply.payload, FosiCodec.replyFromRaw(session, FosiCodec.ReportKind.FEATURE,
            byteArrayOf(1) + reply.payload).payload)
        assertFailsWith<IllegalArgumentException> { FosiCodec.replyFromRaw(session, FosiCodec.ReportKind.FEATURE, reply.payload) }
        assertFailsWith<IllegalArgumentException> { FosiCodec.replyFromRaw(session, FosiCodec.ReportKind.FEATURE, byteArrayOf(2) + reply.payload) }
        assertFailsWith<IllegalArgumentException> { FosiCodec.parseBand(reply.copy(payload = byteArrayOf(1) + reply.payload), session, 7, 0) }
        assertFailsWith<IllegalArgumentException> { FosiCodec.parseBand(reply.copy(reportId = 2), session, 7, 0) }
    }
    @Test fun `DS3 rejects command bank index unknown type NaN and short float frames`() {
        val r = fosiCaptured().first()
        listOf(1 to 0x8D, 2 to 8, 3 to 1, 4 to 8).forEach { (at, value) ->
            assertFailsWith<IllegalArgumentException> { FosiCodec.parseBand(r.copy(payload = r.payload.copyOf().also { it[at] = value.toByte() }), session, 7, 0) }
        }
        assertFailsWith<IllegalArgumentException> { FosiCodec.parseBand(r.copy(payload = r.payload.copyOf(21)), session, 7, 0) }
        assertFailsWith<IllegalArgumentException> { FosiCodec.parseBand(r.copy(payload = r.payload.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putFloat(9, Float.NaN)
        }), session, 7, 0) }
        assertFailsWith<IllegalArgumentException> { FosiCodec.bandReadQueries(7, 8) }
    }
    @Test fun `DS3 incomplete duplicate and wrong preset snapshots never fill flat`() {
        val replies = fosiCaptured()
        assertFailsWith<IllegalArgumentException> { FosiCodec.snapshot(session, state(), sample(), replies.dropLast(1)) }
        assertFailsWith<IllegalArgumentException> { FosiCodec.snapshot(session, state(), sample(), replies.dropLast(1) + replies[0]) }
        assertFailsWith<IllegalArgumentException> { FosiCodec.snapshot(session, state(mode = 4), sample(), replies) }
        assertEquals(8, fosiBefore().bands.size)
    }
    @Test fun `DS3 exact import inverts Q and preserves order without applying it twice`() {
        val imported = fosiBefore().importProfile("copy", "DS3", 1)
        assertEquals(listOf(20.0, 88.0, 300.0, 390.0, 580.0, 980.0, 1700.0, 3500.0), imported.bands.map { it.freqHz })
        val plan = FosiCodec.compile(imported, fosiBefore())
        assertEquals(fosiBefore().bands, plan.expectedBands)
        assertTrue(imported.bands[0].q < 1.0); assertEquals(0.0, imported.preampDb)
    }
    @Test fun `DS3 compile preflights eight deterministic bands and kind tagged complete transaction`() {
        val plan = FosiCodec.compile(profile(), fosiBefore())
        val writes = plan.frames.filter { it.payload.size > 1 && u(it.payload[1]) == 0x8D }
        assertEquals(8, writes.size); assertTrue(writes.all { it.kind == FosiCodec.ReportKind.FEATURE && it.mutating })
        val commits = plan.frames.filter { it.payload.size > 1 && u(it.payload[1]) == 0x8E }
        assertEquals(8, commits.size); assertTrue(commits.all { it.kind == FosiCodec.ReportKind.OUTPUT && it.mutating })
        assertEquals(0x91, u(plan.frames.first().payload[1]))
        assertEquals(listOf(0x92, 0x9D), plan.frames.filter { it.mutating && it.payload.size > 1 }.takeLast(2).map { u(it.payload[1]) })
        assertTrue(plan.expectedBands.drop(1).all { it.frequencyHz == 1000.0 && it.q == 1.0 && it.gainDb == 0.0 && it.nativeType == 2 })
        assertTrue(plan.verificationQueries.none { it.mutating })
        val f = writes.first(); val copy = f.payload; copy[0] = 0; assertEquals(0x77, u(f.payload[0]))
    }
    @Test fun `DS3 native PK LS HS gain and compensated Q use correct LE floats`() {
        val before = fosiBefore()
        listOf(FilterType.PEAK to 2, FilterType.LOW_SHELF to 9, FilterType.HIGH_SHELF to 10).forEach { (type, code) ->
            val plan = FosiCodec.compile(profile(listOf(peak(gain = -6.0).copy(type = type))), before)
            val raw = plan.expectedBands[0]; assertEquals(code, raw.nativeType); assertEquals(-6.0, raw.gainDb)
            if (type == FilterType.PEAK) assertTrue(abs(raw.q - 1.4125375) < 0.000001) else assertEquals(1.0, raw.q)
            val imported = FosiCodec.verify(plan, state(), sample(), plan.expectedBands.map { fosiBandResponse(it) }, synthetic8)
                .importProfile("i", "n", 0)
            assertTrue(abs(imported.bands[0].freqHz - 1000.0) < 0.001)
            assertTrue(abs(imported.bands[0].q - 1.0) < 0.000001)
        }
    }
    @Test fun `DS3 assumed slots 32 bands manual pregain overflow and bad last band reject before writes`() {
        val before = fosiBefore()
        listOf(0, 1, 2, 3, 4, 5, 6, 8, 9, 10, 11).forEach { slot ->
            assertFailsWith<IllegalArgumentException> { FosiCodec.compile(profile(), before, slot) }
        }
        assertFailsWith<IllegalArgumentException> { FosiCodec.compile(profile(List(9) { peak() }), before) }
        assertFailsWith<IllegalArgumentException> { FosiCodec.compile(profile(List(32) { peak() }), before) }
        assertFailsWith<IllegalArgumentException> { FosiCodec.compile(profile(preamp = -1.0), before) }
        assertFailsWith<IllegalArgumentException> { FosiCodec.compile(profile(listOf(peak(gain = 6.0)), null), before) }
        assertFailsWith<IllegalArgumentException> { FosiCodec.compile(profile(List(7) { peak() } + peak(q = Double.NaN)), before) }
        assertFailsWith<IllegalArgumentException> { FosiCodec.compile(profile(listOf(peak(gain = 6.0, q = 10.0))), before) }
        assertFailsWith<IllegalArgumentException> { FosiCodec.compile(profile(listOf(peak().copy(type = FilterType.HIGH_PASS))), before) }
        assertFailsWith<IllegalArgumentException> { FosiCodec.compile(profile(listOf(peak(gain = 12.1))), before) }
        assertFailsWith<IllegalArgumentException> { FosiCodec.compile(profile(preamp = Double.NaN), before) }
    }
    @Test fun `DS3 sample rate required and shelves fail unrepresentable Nyquist or native limit`() {
        assertFailsWith<IllegalArgumentException> { FosiCodec.parseSampleFormat(sample(0), session) }
        assertFailsWith<IllegalArgumentException> { FosiCodec.parseSampleFormat(sample(800000), session) }
        val lowRate = FosiCodec.snapshot(session, state(), sample(8000), fosiCaptured(), synthetic8)
        assertFailsWith<IllegalArgumentException> { FosiCodec.compile(profile(listOf(peak(freq = 5000.0))), lowRate) }
        assertFailsWith<IllegalArgumentException> { FosiCodec.compile(profile(listOf(peak(freq = 20.0, gain = 12.0).copy(type = FilterType.LOW_SHELF))), fosiBefore()) }
        assertFailsWith<IllegalArgumentException> { FosiCodec.snapshot(session, state(), sample(dsd = 1), fosiCaptured()) }
    }
    @Test fun `DS3 enable ACK requires success and exact state and mode`() {
        FosiCodec.validateEnableAck(fosiResponse(0x9D, listOf(0, 1, 7)), session, FosiCodec.State(true, 7))
        listOf(listOf(1, 1, 7), listOf(0, 0, 7), listOf(0, 1, 8)).forEach { data ->
            assertFailsWith<IllegalArgumentException> { FosiCodec.validateEnableAck(fosiResponse(0x9D, data), session, FosiCodec.State(true, 7)) }
        }
        assertFailsWith<IllegalArgumentException> { FosiCodec.validateAck(fosiResponse(0x8A, listOf(7)), session, 0x92) }
    }
    @Test fun `DS3 verify catches raw float bandwidth state and rate mismatches`() {
        val plan = FosiCodec.compile(profile(), fosiBefore())
        val replies = plan.expectedBands.map { fosiBandResponse(it) }
        assertEquals(8, FosiCodec.verify(plan, state(), sample(), replies, synthetic8).bands.size)
        val altered = replies.toMutableList().also { list -> list[7] = list[7].copy(payload = list[7].payload.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putFloat(13, 0.1f)
        }) }
        assertFailsWith<IllegalArgumentException> { FosiCodec.verify(plan, state(), sample(), altered, synthetic8) }
        assertFailsWith<IllegalArgumentException> { FosiCodec.verify(plan, state(false), sample(), replies, synthetic8) }
        assertFailsWith<IllegalArgumentException> { FosiCodec.verify(plan, state(), sample(44100), replies, synthetic8) }
    }
    @Test fun `DS3 step validation prevents writes after failed selection or band response`() {
        val plan = FosiCodec.compile(profile(), fosiBefore())
        val selection = plan.frames.single { it.response == FosiCodec.Response.STATE }
        FosiCodec.validateResponse(plan, selection, state(mode = 7))
        assertFailsWith<IllegalArgumentException> { FosiCodec.validateResponse(plan, selection, state(mode = 4)) }
        val bandStep = plan.frames.first { it.response == FosiCodec.Response.BAND }
        FosiCodec.validateResponse(plan, bandStep, fosiBandResponse(plan.expectedBands[0]))
        assertFailsWith<IllegalArgumentException> { FosiCodec.validateResponse(plan, bandStep, fosiBandResponse(plan.expectedBands[1])) }
        val ack = plan.frames.first { it.response == FosiCodec.Response.ACK }
        FosiCodec.validateResponse(plan, ack, fosiResponse(0x91, emptyList()))
        assertFailsWith<IllegalArgumentException> { FosiCodec.validateResponse(plan, ack, fosiResponse(0x92, emptyList())) }
        val enable = plan.frames.single { it.response == FosiCodec.Response.ENABLE_ACK }
        assertFailsWith<IllegalArgumentException> { FosiCodec.validateResponse(plan, enable, fosiResponse(0x9D, listOf(1, 1, 7))) }
    }
    @Test fun `DS3 nonzero raw bandwidth cannot silently disappear on Profile import`() {
        val replies = fosiCaptured().toMutableList()
        replies[0] = replies[0].copy(payload = replies[0].payload.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putFloat(13, 0.5f)
        })
        val before = FosiCodec.snapshot(session, state(), sample(), replies, synthetic8)
        assertEquals(0.5, before.bands[0].bandwidth)
        assertFailsWith<IllegalArgumentException> { before.importProfile("i", "n", 0) }
    }
    @Test fun `DS3 bypass read retains explicit disabled native state without inventing peak`() {
        val replies = fosiCaptured().toMutableList()
        replies[7] = replies[7].copy(payload = replies[7].payload.copyOf().also { p ->
            p[4] = 0; (5..20).forEach { p[it] = 0 }
        })
        val before = FosiCodec.snapshot(session, state(false), sample(), replies, synthetic8)
        assertEquals(0, before.bands[7].nativeType)
        assertFalse(before.importProfile("i", "n", 0).bands[7].enabled)
        val neutralized = FosiCodec.compile(before.importProfile("i", "n", 0), before)
        assertEquals(0.0, neutralized.expectedBands[7].gainDb)
        assertEquals(1000.0, neutralized.expectedBands[7].frequencyHz)
        assertFalse(before.state.enabled)
    }
}
