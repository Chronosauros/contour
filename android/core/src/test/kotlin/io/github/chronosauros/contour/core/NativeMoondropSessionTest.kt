package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.*
import kotlin.test.*

/** SYNTHETIC replies/descriptor sizes, exercising production session/qualification, not hardware parity. */
class NativeMoondropSessionTest {
    private val target = requireNotNull(DeviceTarget.find(0x35D8, 0xFFFF, true, "FreeDSP Mini"))
    private fun profile() = Profile("p", "local", bands = listOf(Band("b", FilterType.PEAK, 1000.0, -1.0, 1.0)),
        preampDb = -2.3, createdAt = 0, updatedAt = 1)
    private class Port : NativePort {
        override val deviceKey = "synthetic-generation-1"
        var inputBytes = 48; var outputBytes = 6; var live = true; var detachAt = -1
        var alter: (Int, ByteArray) -> ByteArray = { _, p -> p }
        val queries = mutableListOf<List<Int>>()
        override fun payloadSize(id: Int, kind: NativeHidReports.Kind) = if (kind == NativeHidReports.Kind.INPUT) inputBytes else outputBytes
        override fun guard() { check(live) { "Detached generation" } }
        override fun pause(ms: Long) = error("No query pacing mutation")
        override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) = error("Mutation forbidden")
        override fun feature(id: Int): ByteArray = error("Feature forbidden")
        override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
            assertEquals(0x4B, id); assertEquals(0x80, payload[0].toInt() and 255)
            queries += payload.map { it.toInt() and 255 }
            val p = ByteArray(inputBytes); payload.copyInto(p)
            when (payload[1].toInt() and 255) {
                15 -> p[3] = 101
                9 -> { p[27] = 0xE8.toByte(); p[28] = 3; p[29] = 0; p[30] = 1; p[31] = 128.toByte(); p[32] = 255.toByte(); p[33] = 2; p[35] = 101 }
                3 -> { p[3] = 128.toByte(); p[4] = 254.toByte() }
                else -> error("Unexpected query")
            }
            val reply = alter(queries.size, p)
            require(matches(reply)) { "Uncorrelated reply" }
            if (queries.size == detachAt) live = false
            return reply
        }
    }
    private fun shape(input: Int = 36, output: Int = 6, id: Int = 0x4B, vendor: Boolean = true): NativeHidReports.Shape {
        val bytes = listOf(0x06, 0, if (vendor) 0xFF else 0, 9, 1, 0xA1, 1, 0x85, id, 0x75, 8,
            0x95, input, 0x81, 2, 0x95, output, 0x91, 2, 0xC0)
        return NativeHidReports.parse(bytes.map { it.toByte() }.toByteArray())
    }
    @Test fun `actual native session reads all ordered fields without any mutation or fabricated preamp`() {
        val port = Port(); val state = assertIs<NativeState.Moondrop>(NativeSession(target, port).read())
        assertEquals(11, port.queries.size); assertEquals(101, state.slot); assertNull(state.preamp)
        assertEquals(8, state.raw.bands.size); assertEquals(11, state.raw.registers.size)
        assertTrue(state.raw.registers.all { it.bytes.size == 48 })
        assertEquals(-1.5, state.raw.pregain.observedDb)
        assertEquals(MoondropCodec.PreampStatus.UNKNOWN_EFFECTIVE, state.raw.pregain.status)
        assertFalse(state.matches(profile())); assertFailsWith<IllegalStateException> { state.importExact() }
        assertTrue(state.describe().contains("UNKNOWN")); assertTrue(state.describe().contains("index=7"))
    }
    @Test fun `descriptor proof needs complete metadata report ID vendor usage and one owner`() {
        shape().qualifyMoondrop(target.moondrop!!)
        assertFails { shape(input = 10).qualifyMoondrop(target.moondrop!!) }
        assertFails { shape(output = 5).qualifyMoondrop(target.moondrop!!) }
        assertFails { shape(id = 1).qualifyMoondrop(target.moondrop!!) }
        assertFails { shape(vendor = false).qualifyMoondrop(target.moondrop!!) }
        val s = shape()
        assertFails { s.copy(owners = s.owners + (NativeHidReports.Key(0x4B, NativeHidReports.Kind.INPUT) to 2)).qualifyMoondrop(target.moondrop!!) }
        assertFails { s.payload(0x4B, NativeHidReports.Kind.INPUT, byteArrayOf(1) + ByteArray(36)) }
    }
    @Test fun `native session refuses KT sized descriptor before any query`() {
        val p = Port().also { it.inputBytes = 10 }
        assertFails { NativeSession(target, p).read() }; assertTrue(p.queries.isEmpty())
        val q = Port().also { it.outputBytes = 5 }
        assertFails { NativeSession(target, q).read() }; assertTrue(q.queries.isEmpty())
    }
    @Test fun `wrong native ID echo index bank and length cannot publish a snapshot`() {
        for ((offset, value) in listOf(0 to 0, 1 to 8, 2 to 0, 4 to 7, 35 to 7)) {
            val p = Port().also { it.alter = { i, bytes -> bytes.also { if (i == 2) it[offset] = value.toByte() } } }
            assertFails { NativeSession(target, p).read() }; assertEquals(2, p.queries.size)
        }
        val short = Port().also { it.alter = { i, bytes -> if (i == 2) bytes.copyOf(35) else bytes } }
        assertFails { NativeSession(target, short).read() }
        val long = Port().also { it.alter = { i, bytes -> if (i == 2) bytes + 0 else bytes } }
        assertFails { NativeSession(target, long).read() }
    }
    @Test fun `bank drift and missing final reply fail closed`() {
        val drift = Port().also { it.alter = { i, bytes -> bytes.also { if (i == 11) it[3] = 7 } } }
        assertFails { NativeSession(target, drift).read() }
        val missing = Port().also { it.alter = { i, bytes -> if (i == 11) error("Timeout") else bytes } }
        assertFails { NativeSession(target, missing).read() }
    }
    @Test fun `detach after last reply cannot return a stale native snapshot`() {
        val p = Port().also { it.detachAt = 11 }
        assertFails { NativeSession(target, p).read() }; assertEquals(11, p.queries.size)
    }
    @Test fun `Moondrop write and import remain unavailable even with HOLD and valid EQ`() {
        val p = Port()
        assertFails { NativeSession(target, p).write(profile(), true) }; assertTrue(p.queries.isEmpty())
        assertNotNull(target.writeBlocker); assertNull(target.destinationSlot); assertFalse(target.supportsAb)
        assertEquals(8, target.caps.bands); assertTrue(target.runtimeCandidate); assertEquals(-2.3, target.shownPreamp(profile()))
        assertFalse(NativeSession(target, p).read().matches(profile()))
        val micro = DeviceTarget.MICRO.caps
        val nativeOverride = assertNotNull(DeviceTarget.find(micro.vendorId, micro.productId, true, "FreeDSP Mini"))
        assertNotNull(nativeOverride.moondrop); assertNull(nativeOverride.walkplay); assertFalse(nativeOverride.supportsAb)
    }
    @Test fun `source names are exact beta candidates not stable routes or PID guesses`() {
        for (m in MoondropCatalog.models.filter { it.readOnlyCandidate }) {
            val t = assertNotNull(DeviceTarget.find(m.vendorIds.first(), 0xFFFF, true, m.productName))
            assertEquals(m, t.moondrop); assertTrue(t.runtimeCandidate); assertNotNull(t.writeBlocker)
            assertNull(DeviceTarget.find(m.vendorIds.first(), 0xFFFF, false, m.productName))
            assertNull(DeviceTarget.find(m.vendorIds.first(), 0xFFFF, true, m.productName.lowercase()))
            assertNull(DeviceTarget.find(m.vendorIds.first(), 0xFFFF, true, m.productName + " "))
        }
        assertNull(DeviceTarget.find(0x35D8, 0xFFFF, true, null))
        assertTrue(DeviceTarget.candidate(0x35D8, 0xFFFF, null, true)) // permission-only diagnostic
        assertFalse(DeviceTarget.candidate(0x35D8, 0xFFFF, null, false))
    }
}
