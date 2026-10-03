package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.MoondropCatalog
import io.github.chronosauros.contour.core.native.MoondropCodec
import kotlinx.serialization.json.*
import kotlin.test.*

/** Pinned upstream captures test protocol CONFLICTS, not native Moondrop parity.
 * All accepted native response bytes below are explicitly SYNTHETIC; no hardware claim. */
class NativeMoondropTest {
    private val key = "connection-generation-1"
    private fun model(name: String = "FreeDSP Mini") = MoondropCatalog.models.single { it.productName == name }
    private fun fixture(name: String) = Json.parseToJsonElement(requireNotNull(
        javaClass.getResourceAsStream("/native/moondrop/$name")).bufferedReader().use { it.readText() }).jsonObject
    private fun bytes(value: JsonElement) = value.jsonArray.map {
        val n = it.jsonPrimitive.int; require(n in 0..255); n.toByte()
    }.toByteArray()
    private fun reply(p: ByteArray) = MoondropCodec.Reply(key, 0x4B, p)
    // Synthetic descriptor payload size 63, never inferred as a hardware descriptor.
    private fun response(frame: MoondropCodec.Frame, slot: Int = 7): MoondropCodec.Reply {
        val p = ByteArray(63)
        frame.payload.copyInto(p)
        when (frame.kind) {
            MoondropCodec.Kind.SLOT -> p[3] = slot.toByte()
            MoondropCodec.Kind.VERSION -> { p[3] = 1; p[4] = 2; p[5] = 3 }
            MoondropCodec.Kind.PREGAIN -> { p[2] = 2; p[3] = 0x80.toByte(); p[4] = 0xFE.toByte() }
            MoondropCodec.Kind.BAND -> {
                p[7] = 0x12; p[8] = 0x34; p[11] = 0xFF.toByte(); p[14] = 0x80.toByte()
                p[27] = 0xE8.toByte(); p[28] = 3 // 1000 Hz
                p[29] = 0x80.toByte(); p[30] = 1 // Q=1.5
                p[31] = 0x80.toByte(); p[32] = 0xFF.toByte() // -0.5 dB
                p[33] = 2; p[35] = slot.toByte()
            }
            MoondropCodec.Kind.OLD_REGISTER -> Unit
        }
        return reply(p)
    }
    private fun replies() = MoondropCodec.readQueries(model()).map { response(it) }

    @Test fun `fourteen literal rules preserve upstream vendor and constraint data`() {
        val rows = fixture("source-evidence.json").getValue("models").jsonArray
        assertEquals(14, MoondropCatalog.models.size)
        assertEquals(rows.map { it.jsonObject.getValue("productName").jsonPrimitive.content },
            MoondropCatalog.models.map { it.productName })
        rows.forEach { row ->
            val o = row.jsonObject; val m = model(o.getValue("productName").jsonPrimitive.content)
            assertEquals(o.getValue("vendorIds").jsonArray.map { it.jsonPrimitive.int }.toSet(), m.vendorIds)
            assertEquals(o.getValue("bandCount").jsonPrimitive.int, m.bandCount)
            assertEquals(o.getValue("nativeTypes").jsonArray.map { it.jsonPrimitive.int }.toSet(), m.nativeTypes)
        }
        assertEquals(12, MoondropCatalog.models.count { it.readOnlyCandidate })
        assertEquals(2, MoondropCatalog.models.count { !it.readOnlyCandidate })
        assertTrue(MoondropCatalog.models.all { it.reasonReadOnly.isNotBlank() && it.reportId == 75 })
    }
    @Test fun `identity lookup never normalizes names or falls back by vendor`() {
        assertTrue(MoondropCatalog.find(0x35D8, 0xFFFF, "FreeDSP Mini").readOnlyCandidate)
        listOf(null, "freedsp mini", "FreeDSP Mini ", "FreeDSP", "Moondrop FreeDSP", "DAWN PRO 2", "ECHO-B").forEach {
            val found = MoondropCatalog.find(0x35D8, 0xFFFF, it)
            assertFalse(found.readOnlyCandidate); assertTrue(found.reason.isNotBlank())
        }
        assertFalse(MoondropCatalog.find(0xFFFF, 1, "FreeDSP Mini").readOnlyCandidate)
        assertFalse(MoondropCatalog.find(0x35D8, -1, "FreeDSP Mini").readOnlyCandidate)
        assertFalse(MoondropCatalog.find(0x35D8, 0x1496, "FreeDSP Mini").readOnlyCandidate)
        assertFalse(MoondropCatalog.find(0x35D8, 0x011C, "Marigold").readOnlyCandidate)
        assertTrue(MoondropCatalog.find(0x35D8, 0xFFFF, "DAWN PRO2").readOnlyCandidate)
        assertEquals(setOf(0x011C), model("MOONDROP Marigold").provenProductIds)
        assertTrue(MoondropCatalog.models.filter { it.productName != "MOONDROP Marigold" }.all { it.provenProductIds.isEmpty() })
    }
    @Test fun `real captured Marigold uses conflicting WalkPlay reads not native layout`() {
        val f = fixture("walkplay_default_moondrop_marigold.json")
        assertEquals("MOONDROP Marigold", f.getValue("device").jsonObject.getValue("productName").jsonPrimitive.content)
        val exchanges = f.getValue("exchanges").jsonArray.map { it.jsonObject }
        val bands = exchanges.filter { it.getValue("description").jsonPrimitive.content.startsWith("Read band") }
        assertEquals(8, bands.size)
        bands.forEachIndexed { i, exchange ->
            val tx = bytes(exchange.getValue("send").jsonObject.getValue("data"))
            assertContentEquals(byteArrayOf(0x80.toByte(), 9, 0, 0, i.toByte(), 0), tx)
            val rx = exchange.getValue("responses").jsonArray.single().jsonObject
            assertFailsWith<IllegalArgumentException> {
                MoondropCodec.parseBand(MoondropCodec.Reply(key, rx.getValue("reportId").jsonPrimitive.int,
                    bytes(rx.getValue("data"))), key, model(), i, 7, 63)
            }
        }
        assertFalse(MoondropCatalog.find(0x35D8, 0x011C, "MOONDROP Marigold").readOnlyCandidate)
        assertFailsWith<IllegalArgumentException> { MoondropCodec.readQueries(model("MOONDROP Marigold")) }
    }
    @Test fun `real Conexant captures are not native Moondrop evidence`() {
        listOf("moondrop_freedsp_conexant.json", "moondrop_echob_conexant.json").forEach { name ->
            val f = fixture(name)
            val d = f.getValue("device").jsonObject
            assertFalse(MoondropCatalog.find(d.getValue("vendorId").jsonPrimitive.int,
                d.getValue("productId").jsonPrimitive.int, d.getValue("productName").jsonPrimitive.content).readOnlyCandidate)
            val sends = f.getValue("exchanges").jsonArray.map { it.jsonObject.getValue("send").jsonObject }
            assertTrue(sends.all { it.getValue("reportId").jsonPrimitive.int == 1 })
            // Preserve upstream invalid integers; never coerce these into purported byte-exact captures.
            assertTrue(sends.any { it.getValue("data").jsonArray.any { n -> n.jsonPrimitive.int !in 0..255 } })
        }
    }
    @Test fun `only nonmutating native query bytes are exposed`() {
        val q = MoondropCodec.readQueries(model())
        assertEquals(11, q.size)
        assertContentEquals(byteArrayOf(0x80.toByte(), 15, 0), q.first().payload)
        assertContentEquals(q.first().payload, q.last().payload)
        (0..7).forEach { i -> assertContentEquals(byteArrayOf(0x80.toByte(), 9, 24, 0, i.toByte(), 0), q[i + 1].payload) }
        assertContentEquals(byteArrayOf(0x80.toByte(), 3), q[9].payload)
        assertContentEquals(byteArrayOf(0x80.toByte(), 12), MoondropCodec.versionQuery().payload)
        assertTrue(q.all { !it.mutating && it.reportId == 75 && it.expectsReply })
        q[1].payload[0] = 1
        assertEquals(0x80, q[1].payload[0].toInt() and 255)
    }
    @Test fun `synthetic complete raw snapshot preserves ordered coefficients and fractional offset`() {
        val s = MoondropCodec.snapshot(model(), key, replies(), 63)
        assertEquals(8, s.bands.size); assertEquals(11, s.registers.size); assertEquals(7, s.slot)
        assertEquals(-1.5, s.pregain.observedDb)
        assertEquals(MoondropCodec.PreampStatus.UNKNOWN_EFFECTIVE, s.pregain.status)
        assertEquals(1000, s.bands[0].frequencyHz); assertEquals(1.5, s.bands[0].q)
        assertEquals(-0.5, s.bands[0].gainDb)
        assertEquals(listOf(0x3412, Int.MIN_VALUE or 255, 0, 0, 0), s.bands[0].coefficientsQ30)
        assertEquals(20, s.bands[0].coefficientBytes.size)
        assertTrue(s.importBlockedReason.isNotBlank())
        assertFailsWith<IllegalStateException> { s.importProfile("id", "name", 0) }
    }
    @Test fun `wrong connection report ID opcode header index bank and size are rejected`() {
        val valid = response(MoondropCodec.readQueries(model())[1])
        fun check(r: MoondropCodec.Reply) = assertFailsWith<IllegalArgumentException> {
            MoondropCodec.parseBand(r, key, model(), 0, 7, 63)
        }
        check(valid.copy(deviceKey = "other")); check(valid.copy(reportId = 1))
        for ((offset, value) in listOf(0 to 1, 1 to 10, 2 to 0, 3 to 1, 4 to 1, 5 to 1, 6 to 1, 35 to 101)) {
            check(valid.copy(payload = valid.payload.copyOf().also { it[offset] = value.toByte() }))
        }
        check(valid.copy(payload = valid.payload.copyOf(35))); check(valid.copy(payload = valid.payload + 0))
        assertFailsWith<IllegalArgumentException> { MoondropCodec.parseBand(valid, key, model(), 8, 7, 63) }
    }
    @Test fun `unknown and model-disallowed native types invalid frequency and signed Q never flatten`() {
        val valid = response(MoondropCodec.readQueries(model())[1])
        fun bad(p: ByteArray, m: MoondropCatalog.Model = model()) = assertFailsWith<IllegalArgumentException> {
            MoondropCodec.parseBand(reply(p), key, m, 0, 7, 63)
        }
        bad(valid.payload.copyOf().also { it[33] = 99 })
        bad(valid.payload.copyOf().also { it[33] = 1 }, model("Rays"))
        bad(valid.payload.copyOf().also { it[27] = 0; it[28] = 0 })
        bad(valid.payload.copyOf().also { it[29] = 0; it[30] = 0 })
        bad(valid.payload.copyOf().also { it[30] = 0x80.toByte() })
        assertFailsWith<IllegalArgumentException> { MoondropCodec.requireFinite(Double.NaN, "synthetic NaN") }
        assertFailsWith<IllegalArgumentException> { MoondropCodec.requireFinite(Double.POSITIVE_INFINITY, "synthetic infinity") }
    }
    @Test fun `synthetic shelf raw metadata retained but no unsupported exact import`() {
        for (type in listOf(1, 3)) {
            val q = MoondropCodec.readQueries(model()); val rx = q.map { response(it) }.toMutableList()
            rx[1] = rx[1].copy(payload = rx[1].payload.copyOf().also { it[33] = type.toByte() })
            val s = MoondropCodec.snapshot(model(), key, rx, 63)
            assertEquals(type, s.bands[0].nativeType)
            assertFailsWith<IllegalStateException> { s.importProfile("id", "shelf", 0) }
        }
    }
    @Test fun `missing duplicate shuffled replies and changed slot block complete snapshots`() {
        val valid = replies()
        for (bad in listOf(valid.dropLast(1), valid + valid[1], valid.toMutableList().also { it[2] = it[1] },
            valid.toMutableList().also { val t = it[1]; it[1] = it[2]; it[2] = t },
            valid.toMutableList().also { it[it.lastIndex] = response(MoondropCodec.slotQuery(), 101) })) {
            assertFailsWith<IllegalArgumentException> { MoondropCodec.snapshot(model(), key, bad, 63) }
        }
    }
    @Test fun `pregain and numeric version preserve source values without claiming effective preamp`() {
        val p = MoondropCodec.parsePregain(response(MoondropCodec.pregainQuery()), key, 63)
        assertEquals(-384, p.rawQ8); assertEquals(-1.5, p.observedDb)
        val v = MoondropCodec.parseVersion(response(MoondropCodec.versionQuery()), key, 63)
        assertEquals(listOf(1, 2, 3), v.components)
        val bad = response(MoondropCodec.pregainQuery()).payload.copyOf().also { it[1] = 0x23 }
        assertFailsWith<IllegalArgumentException> { MoondropCodec.parsePregain(reply(bad), key, 63) }
        assertFailsWith<IllegalArgumentException> { MoondropCodec.parseVersion(reply(ByteArray(5)), key, 5) }
    }
    @Test fun `complete Old Fashioned diagnostic snapshot preserves ten correlated raw registers and unknown preamp`() {
        val rx = (38..47).map { reg ->
            val p = MoondropCodec.oldRegisterQuery(reg).payload
            if (reg % 2 == 0) { p[6] = 0xFB.toByte(); p[8] = 0xE8.toByte(); p[9] = 3 }
            else { p[6] = 0xDC.toByte(); p[7] = 5 }
            reply(p)
        }
        val s = MoondropCodec.oldDiagnosticSnapshot(key, rx, 10)
        assertEquals(10, s.registers.size); assertEquals(5, s.bands.size)
        assertNull(s.observedPregainDb); assertNull(s.observedSlot)
        assertEquals(MoondropCodec.PreampStatus.UNKNOWN_EFFECTIVE, s.preampStatus)
        assertFailsWith<IllegalStateException> { s.importProfile("id", "old", 0) }
        assertFailsWith<IllegalArgumentException> { MoondropCodec.oldDiagnosticSnapshot(key, rx.dropLast(1), 10) }
        assertFailsWith<IllegalArgumentException> { MoondropCodec.oldDiagnosticSnapshot(key,
            rx.toMutableList().also { it[1] = it[0] }, 10) }
        assertFailsWith<IllegalArgumentException> { MoondropCodec.oldDiagnosticSnapshot(key, rx.reversed(), 10) }
    }
    @Test fun `Old Fashioned read packet source parity is separate from unproven response echo`() {
        val m = model("Old Fashioned")
        assertFalse(m.readOnlyCandidate); assertNotNull(m.blockedReason)
        assertFailsWith<IllegalArgumentException> { MoondropCodec.readQueries(m) }
        val f = MoondropCodec.oldRegisterQuery(38)
        assertContentEquals(byteArrayOf(38, 0, 0, 0, 82, 0, 0, 0, 0, 0), f.payload)
        assertFalse(f.mutating); assertEquals(100, f.delayAfterMs)
        val raw = reply(f.payload.also { it[6] = 0xFB.toByte(); it[8] = 0xE8.toByte(); it[9] = 3 })
        val reg = MoondropCodec.parseOldRegister(raw, key, 38, 10)
        val qr = MoondropCodec.oldRegisterQuery(39).payload.also { it[6] = 0xDC.toByte(); it[7] = 5 }
        val b = MoondropCodec.decodeOldBand(0, reg, MoondropCodec.parseOldRegister(reply(qr), key, 39, 10))
        assertEquals(-0.5, b.gainDb); assertEquals(1000, b.frequencyHz); assertEquals(1.5, b.q)
        assertFailsWith<IllegalArgumentException> { MoondropCodec.parseOldRegister(raw, key, 39, 10) }
        assertFailsWith<IllegalArgumentException> { MoondropCodec.parseOldRegister(raw.copy(reportId = 1), key, 38, 10) }
        assertFailsWith<IllegalArgumentException> { MoondropCodec.parseOldRegister(raw.copy(deviceKey = "other"), key, 38, 10) }
        assertFailsWith<IllegalArgumentException> { MoondropCodec.parseOldRegister(reply(raw.payload.copyOf(9)), key, 38, 10) }
        assertFailsWith<IllegalArgumentException> { MoondropCodec.parseOldRegister(reply(raw.payload.copyOf().also { it[4] = 87 }), key, 38, 10) }
        assertFailsWith<IllegalArgumentException> { MoondropCodec.oldRegisterQuery(37) }
    }
}
