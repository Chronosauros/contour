package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.*
import kotlin.test.*

/**
 * JCally JM12 (KT Micro KT02H20). Fixtures are the raw read replies jm12.py saved on a contributor's JM12
 * (research/jm12-pc-2026-10-20, factory-state.json and f1-crin-state.json): the factory state and the state after
 * writing F1-Crin.txt with the stored frequency / 2.
 */
class KtMicroTest {
    private fun hex(s: String) = s.split(' ').map { it.toInt(16).toByte() }.toByteArray()
    private fun state(lines: List<String>) = KtMicroCodec.snapshot(lines.map(::hex).associateBy { it[0].toInt() and 255 })

    private val factory = state(listOf(
        "24 00 00 00 52 00 02 00 00 00",
        "26 00 00 00 52 00 00 00 e8 03", "27 00 00 00 52 00 c3 02 00 00",
        "28 00 00 00 52 00 00 00 d0 07", "29 00 00 00 52 00 c3 02 00 00",
        "2a 00 00 00 52 00 00 00 88 13", "2b 00 00 00 52 00 c3 02 00 00",
        "2c 00 00 00 52 00 00 00 40 1f", "2d 00 00 00 52 00 c3 02 00 00",
        "2e 00 00 00 52 00 00 00 10 27", "2f 00 00 00 52 00 c3 02 00 00",
    ))
    private val f1Crin = state(listOf(
        "24 00 00 00 52 00 03 00 00 00",
        "26 00 00 00 52 00 14 00 1e 00", "27 00 00 00 52 00 bc 02 00 00",
        "28 00 00 00 52 00 14 00 c8 00", "29 00 00 00 52 00 78 05 00 00",
        "2a 00 00 00 52 00 ec ff 20 03", "2b 00 00 00 52 00 78 05 00 00",
        "2c 00 00 00 52 00 1e 00 d6 06", "2d 00 00 00 52 00 78 05 00 00",
        "2e 00 00 00 52 00 b0 ff de 0d", "2f 00 00 00 52 00 78 05 00 00",
    ))
    /** F1-Crin.txt: Preamp -2 dB and five PK bands. */
    private fun f1(preamp: Double? = -2.0) = Profile("f1", "F1-Crin", bands = listOf(
        Band("1", FilterType.PEAK, 60.0, 2.0, 0.7), Band("2", FilterType.PEAK, 400.0, 2.0, 1.4),
        Band("3", FilterType.PEAK, 1600.0, -2.0, 1.4), Band("4", FilterType.PEAK, 3500.0, 3.0, 1.4),
        Band("5", FilterType.PEAK, 7100.0, -8.0, 1.4),
    ), preampDb = preamp, createdAt = 0, updatedAt = 0)

    @Test fun `factory state reads as EQ off and is never ON DAC or imported`() {
        assertEquals(KtMicroCodec.SLOT_OFF, factory.slot)
        assertFalse(KtMicroCodec.matches(f1(), factory))
        assertFalse(KtMicroCodec.matches(Profile("flat", "flat", bands = emptyList(), createdAt = 0, updatedAt = 0), factory))
        assertFailsWith<IllegalArgumentException> { KtMicroCodec.importExact(factory) }
    }

    @Test fun `F1-Crin compiles to exactly the registers the JM12 read back`() {
        assertEquals(f1Crin.registers, KtMicroCodec.compile(f1()))
        // From EQ off: enable Custom, the ten band writes in register order; the commit is its own frame.
        val frames = KtMicroCodec.writes(factory, KtMicroCodec.compile(f1())).map { it.joinToString(" ") { b -> "%02x".format(b) } }
        assertEquals(listOf(
            "24 00 00 00 57 00 03 00 00 00",
            "26 00 00 00 57 00 14 00 1e 00", "27 00 00 00 57 00 bc 02 00 00",
            "28 00 00 00 57 00 14 00 c8 00", "29 00 00 00 57 00 78 05 00 00",
            "2a 00 00 00 57 00 ec ff 20 03", "2b 00 00 00 57 00 78 05 00 00",
            "2c 00 00 00 57 00 1e 00 d6 06", "2d 00 00 00 57 00 78 05 00 00",
            "2e 00 00 00 57 00 b0 ff de 0d", "2f 00 00 00 57 00 78 05 00 00",
        ), frames)
        assertEquals(10, KtMicroCodec.writes(f1Crin, KtMicroCodec.compile(f1())).size) // EQ already on: no slot write
        assertContentEquals(hex("00 00 00 00 53 00 00 00 00 00"), KtMicroCodec.COMMIT_FRAME)
    }

    @Test fun `saved state imports with preamp 0 and matches F1-Crin whatever its preamp`() {
        val eq = KtMicroCodec.importExact(f1Crin)
        assertEquals(0.0, eq.preampDb)
        assertEquals(f1().bands.map { Triple(it.freqHz, it.gainDb, it.q) }, eq.bands.map { Triple(it.freqHz, it.gainDb, it.q) })
        for (preamp in listOf(-2.0, 0.0, null, -30.0)) assertTrue(KtMicroCodec.matches(f1(preamp), f1Crin))
        assertFalse(KtMicroCodec.matches(f1().copy(bands = f1().bands.dropLast(1)), f1Crin))
        // Unused bands are factory flat, and an import leaves them out.
        val one = f1().copy(bands = f1().bands.take(1))
        val oneState = KtSnapshot(KtMicroCodec.SLOT_CUSTOM, KtMicroCodec.compile(one))
        assertEquals(factory.registers.filterKeys { it >= 0x28 }, oneState.registers.filterKeys { it >= 0x28 })
        assertEquals(1, KtMicroCodec.importExact(oneState).bands.size)
    }

    @Test fun `gain rounds down, frequency is stored halved, a disabled band keeps its shape at 0 dB`() {
        val p = f1().copy(bands = listOf(Band("a", FilterType.PEAK, 61.0, -2.05, 0.7005), Band("b", FilterType.PEAK, 400.0, 5.0, 1.4, enabled = false)))
        val r = KtMicroCodec.compile(p)
        assertEquals(listOf(0xEB, 0xFF, 31, 0), r[0x26]) // -2.05 -> -21 tenths, 61 Hz -> 31 (half up)
        assertEquals(listOf(0xBD, 0x02, 0, 0), r[0x27])  // 0.7005 -> 701
        assertEquals(listOf(0, 0, 200, 0), r[0x28])
    }

    @Test fun `only the registers and commands in PROTOCOL md, never 0x66, CLEAR or report 0x54`() {
        val frames = KtMicroCodec.REGISTERS.map(KtMicroCodec::read) +
            KtMicroCodec.writes(factory, KtMicroCodec.compile(f1())) + KtMicroCodec.COMMIT_FRAME
        val allowed = setOf(0x24) + (0x26..0x2F)
        for (f in frames) {
            val reg = f[0].toInt() and 255; val cmd = f[4].toInt() and 255
            assertEquals(KtMicroCodec.PAYLOAD_BYTES, f.size)
            when (cmd) {
                KtMicroCodec.READ, KtMicroCodec.WRITE -> assertTrue(reg in allowed, "register 0x%02x".format(reg))
                KtMicroCodec.COMMIT -> assertEquals(0, reg)
                else -> fail("command 0x%02x".format(cmd))
            }
        }
        assertEquals(0x4B, KtMicroCodec.REPORT_ID)
    }

    @Test fun `what the JM12 cannot hold blocks the send with the editor's texts and the preamp never does`() {
        val t = DeviceTarget.JM12
        fun issues(vararg b: Band, preamp: Double? = 0.0) = t.issues(f1(preamp).copy(bands = b.toList()))
        val ok = Band("x", FilterType.PEAK, 1000.0, 0.0, 1.0)
        assertEquals("6 BANDS - MAX 5", DeviceTarget.blockLabel(issues(*Array(6) { ok })))
        assertEquals(listOf("Band 1: type unsupported"), issues(ok.copy(type = FilterType.LOW_SHELF)))
        assertEquals(listOf("Band 1: gain 10.1 dB outside -10..10 dB"), issues(ok.copy(gainDb = 10.1)))
        assertEquals(listOf("Band 1: Q 5.01 outside 0.10-5.00"), issues(ok.copy(q = 5.01)))
        assertEquals(listOf("Band 1: freq 25000 Hz outside 20-20000 Hz"), issues(ok.copy(freqHz = 25_000.0)))
        for (preamp in listOf(-2.0, null, 3.0, -40.0)) assertEquals(emptyList(), t.issues(f1(preamp)))
    }

    /** A JM12 whose registers are RAM until the commit; it answers reads, ACKs writes and leaves the bus on the commit. */
    private class KtPort(start: KtSnapshot) : NativePort {
        override val deviceKey = "jm12"
        val events = ArrayList<String>()
        var slot = start.slot
        val regs = start.registers.toMutableMap()
        var ack = true
        var dropWriteTo: Int? = null
        var gone = false
        override fun guard() { check(!gone) { "DAC left the bus" } }
        override fun pause(ms: Long) {}
        override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) = fail("plain send")
        override fun sendFinal(id: Int, payload: ByteArray) {
            assertEquals(KtMicroCodec.REPORT_ID, id)
            assertContentEquals(KtMicroCodec.COMMIT_FRAME, payload)
            events += "commit"; gone = true
        }
        override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
            assertEquals(KtMicroCodec.REPORT_ID, id)
            val reg = payload[0].toInt() and 255
            val d = (6..9).map { payload[it].toInt() and 255 }
            val reply = when (payload[4].toInt() and 255) {
                KtMicroCodec.READ -> { events += "read:%02x".format(reg); listOf(reg, 0, 0, 0, 0x52, 0) + (if (reg == KtMicroCodec.SLOT) listOf(slot, 0, 0, 0) else regs.getValue(reg)) }
                KtMicroCodec.WRITE -> {
                    events += "write:%02x".format(reg)
                    if (reg == KtMicroCodec.SLOT) slot = d[0] else if (reg != dropWriteTo) regs[reg] = d
                    if (!ack) throw java.io.IOException("Native request timed out or returned invalid state")
                    listOf(reg, 0, 0, 0, 0x57, 0, 3, 0, 0, 0)
                }
                else -> fail("command ${payload[4]}")
            }
            return reply.map { it.toByte() }.toByteArray().also { assertTrue(matches(it)) }
        }
    }

    @Test fun `session reads, enables, writes, reads RAM back and commits last`() {
        val port = KtPort(factory)
        val r = NativeSession(DeviceTarget.JM12, port).write(f1(), explicitHold = true)
        assertTrue(r.verified); assertTrue(r.restarting)
        assertTrue(r.readback!!.matches(f1()))
        val reads = KtMicroCodec.REGISTERS.map { "read:%02x".format(it) }
        assertEquals(reads + "write:24" + (0x26..0x2F).map { "write:%02x".format(it) } + reads + "commit", port.events)
    }

    @Test fun `writes without ACK still verify by the read-back`() {
        val port = KtPort(f1Crin).apply { ack = false }
        val r = NativeSession(DeviceTarget.JM12, port).write(f1().copy(bands = f1().bands.take(2)), explicitHold = true)
        assertTrue(r.verified); assertEquals("commit", port.events.last())
    }

    @Test fun `a RAM read-back mismatch is never committed`() {
        val port = KtPort(factory).apply { dropWriteTo = 0x2E }
        val r = NativeSession(DeviceTarget.JM12, port).write(f1(), explicitHold = true)
        assertFalse(r.verified); assertFalse(r.restarting)
        assertFalse("commit" in port.events)
    }

    @Test fun `a blocked profile or no HOLD writes nothing`() {
        val port = KtPort(factory)
        assertFailsWith<IllegalArgumentException> { NativeSession(DeviceTarget.JM12, port).write(f1(), explicitHold = false) }
        assertFailsWith<IllegalArgumentException> {
            NativeSession(DeviceTarget.JM12, port).write(f1().copy(bands = f1().bands + f1().bands.first()), explicitHold = true)
        }
        assertEquals(emptyList(), port.events)
    }
}
