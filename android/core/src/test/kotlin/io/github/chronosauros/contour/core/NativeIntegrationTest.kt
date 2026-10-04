package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.*
import kotlin.test.*

/** Explicitly SYNTHETIC fake USB state machines exercise the SAME session Android invokes.
 * They verify sequencing and fail-closed behaviour, not physical firmware or audio support. */
class NativeIntegrationTest {
    private fun profile(gain: Double = -1.0, preamp: Double? = 0.0, count: Int = 1) =
        Profile("id", "test", bands = List(count) { Band("b$it", FilterType.PEAK, 1000.0, gain, 1.0) },
            preampDb = preamp, createdAt = 0, updatedAt = 0)
    private fun fiio(): DeviceTarget = requireNotNull(DeviceTarget.find(0x2972, 0x0104))
    private abstract class FakePort : NativePort {
        override var deviceKey = "generation-1"
        val events = ArrayList<String>(); var live = true; var requests = 0
        var detachAfterRequest: Int? = null
        var detachAfterMutation = false; var detachOnPause = false
        fun mutationFinished() { if (detachAfterMutation) live = false }
        override fun guard() { check(live) { "SESSION CHANGED" } }
        override fun pause(ms: Long) { events += "pause:$ms"; if (detachOnPause) live = false }
        fun finishedRequest(p: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
            requests++; require(matches(p)) { "No correlated reply" }
            if (requests == detachAfterRequest) live = false
            return p
        }
    }
    private class FiioPort(val target: DeviceTarget) : FakePort() {
        private val c = FiioCodec(requireNotNull(target.fiio))
        var state = c.planOnExplicitSend(profileStatic(), target.destinationSlot!!, true).expected
        var selectionWorks = true; var brokenBackup = false; var savedMismatch = false; var selected = false; var saved = false; var detachOnSave = false
        val names = mutableMapOf(0 to "TEST1", 1 to "FF5", 2 to "FH3")
        private fun word(v: Int) = listOf((v ushr 8) and 255, v and 255)
        // KA15 name replies say LEN 8 but carry IDX + a 9-byte name field (Pixel, 04.10.2026).
        private fun reply(cmd: Int, data: List<Int>) =
            (listOf(0xBB, 0x0B, 0, 0, cmd, if (cmd == FiioCodec.NAME) 8 else data.size) + data + listOf(0, 0xEE)).map { it.toByte() }.toByteArray().copyOf(63)
        override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) {
            assertEquals(c.config.reportId, id); assertEquals(NativeHidReports.Kind.OUTPUT, kind)
            val cmd = payload[4].toInt() and 255; events += "write:$cmd"
            fun u(i: Int) = payload[i].toInt() and 255
            fun v(i: Int) = (u(i) shl 8) or u(i + 1)
            when (cmd) {
                FiioCodec.SLOT -> { selected = true; if (selectionWorks) state = state.copy(activeSlot = u(6)) }
                FiioCodec.COUNT -> state = state.copy(count = u(6), bands = List(u(6)) { i -> state.bands.getOrNull(i) ?: FiioRegisters(i, 1000, 0, 100, 0) })
                FiioCodec.PREAMP -> state = state.copy(preampTenths = v(6).toShort().toInt())
                FiioCodec.BAND -> state = state.copy(bands = state.bands.map { if (it.index == u(6)) FiioRegisters(u(6), v(9), v(7).toShort().toInt(), v(11), u(13)) else it })
                c.config.saveCommand -> { saved = true; if (savedMismatch) state = state.copy(preampTenths = state.preampTenths - 1) }
                FiioCodec.NAME -> names[u(6)] = (7 until 7 + u(5) - 1).map { u(it) }.takeWhile { it != 0 }.map { it.toChar() }.joinToString("")
                else -> error("Unexpected mutation")
            }
            mutationFinished()
            if (saved && detachOnSave) live = false
        }
        override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
            if ((payload[0].toInt() and 255) == 0xAA) {
                // write with its AA echo (devices that must have every echo consumed)
                send(id, NativeHidReports.Kind.OUTPUT, payload)
                return finishedRequest(payload.copyOf(63), matches)
            }
            assertEquals(0xBB, payload[0].toInt() and 255)
            val cmd = payload[4].toInt() and 255; events += "read:$cmd"
            if (brokenBackup && selected && cmd == FiioCodec.BAND) error("Incomplete destination backup")
            val data = when (cmd) {
                FiioCodec.SLOT -> listOf(state.activeSlot)
                FiioCodec.COUNT -> listOf(state.count)
                FiioCodec.PREAMP -> word(state.preampTenths)
                FiioCodec.BAND -> state.bands[payload[6].toInt() and 255].let { listOf(it.index) + word(it.gainTenths) + word(it.frequencyHz) + word(it.qHundredths) + it.typeCode }
                FiioCodec.NAME -> (payload[6].toInt() and 255).let { i -> listOf(i) + names.getValue(i).map { it.code }.plus(List(9) { 0 }).take(9) }
                else -> error("Unexpected read")
            }
            return finishedRequest(reply(cmd, data), matches)
        }
        companion object { fun profileStatic() = Profile("id", "fixture", bands = listOf(Band("b", FilterType.PEAK, 1000.0, 0.0, 1.0)), preampDb = 0.0, createdAt = 0, updatedAt = 0) }
    }
    @Test fun `FiiO selection then full backup precedes parameters and full post save matches`() {
        val t = fiio(); val port = FiioPort(t); port.state = port.state.copy(activeSlot = 0)
        val p = profile(preamp = -2.3)
        val result = NativeSession(t, port).write(p, true)
        assertTrue(result.verified); assertTrue(result.readback!!.matches(p))
        assertEquals(-2.3, result.readback!!.preamp)
        val select = port.events.indexOf("write:22"); val pregain = port.events.indexOf("write:23")
        assertTrue(select >= 0 && pregain > select)
        assertEquals(listOf("pause:300", "read:22", "read:48", "read:48", "read:48", "read:22", "read:24", "read:23", "read:21", "read:22"), port.events.subList(select + 1, pregain))
    }
    @Test fun `KA15 save to another USER slot switches to it and names it after the profile`() {
        val t = fiio(); val port = FiioPort(t)
        val p = profile().copy(name = "Nightfall Łąka")
        val r = NativeSession(t, port).write(p, true, targetSlot = 8)
        assertTrue(r.verified); assertNull(r.reason)
        assertEquals(8, port.state.activeSlot)
        assertEquals("NIGHTFA", port.names[1]); assertEquals("TEST1", port.names[0])
        assertEquals("NIGHTFA", (r.readback as NativeState.Fiio).names[8])
        assertTrue(port.events.indexOf("write:22") < port.events.indexOf("write:25"))
        assertTrue(port.events.indexOf("write:25") < port.events.indexOf("write:48"))
    }
    @Test fun `KA15 name reply from the Pixel parses with its 9-byte name field`() {
        val c = FiioCodec(requireNotNull(fiio().fiio))
        val rx = "bb 0b 00 00 30 08 00 54 45 53 54 31 00 00 00 00 ac ee".split(" ").map { it.toInt(16).toByte() }.toByteArray().copyOf(63)
        assertEquals("TEST1", c.parseName(c.logicalReply(7, rx, 63), 7))
        // Pixel 04.10 00:38:59: the field's last two bytes held the tail of the previous frame
        val stale = "bb 0b 00 00 30 08 01 4e 49 47 48 54 46 41 ab ee c8 ee".split(" ").map { it.toInt(16).toByte() }.toByteArray().copyOf(63)
        assertEquals("NIGHTFA", c.parseName(c.logicalReply(7, stale, 63), 8))
    }
    @Test fun `FiiO failed selection blocks band mutations`() {
        val t = fiio(); val port = FiioPort(t); port.state = port.state.copy(activeSlot = 0); port.selectionWorks = false
        assertFails { NativeSession(t, port).write(profile(), true) }
        assertEquals(listOf("write:22"), port.events.filter { it.startsWith("write") })
    }
    @Test fun `FiiO incomplete backup blocks all parameter writes`() {
        val t = fiio(); val port = FiioPort(t); port.state = port.state.copy(activeSlot = 0); port.brokenBackup = true
        assertFails { NativeSession(t, port).write(profile(), true) }
        assertEquals(listOf("write:22"), port.events.filter { it.startsWith("write") })
    }
    @Test fun `FiiO destination already active is written without a preset switch`() {
        val t = fiio(); val port = FiioPort(t)
        val r = NativeSession(t, port).write(profile(), true)
        assertTrue(r.verified)
        assertTrue(port.events.none { it == "write:22" })
    }
    @Test fun `FiiO save mismatch cannot verify or mark ON DAC`() {
        val t = fiio(); val port = FiioPort(t); port.savedMismatch = true
        val r = NativeSession(t, port).write(profile(), true)
        assertFalse(r.verified); assertFalse(r.readback!!.matches(profile()))
    }
    @Test fun `KA15 routes native by its exact VID PID`() {
        assertNotNull(fiio().fiio); assertNull(fiio().walkplay)
        assertEquals("FIIO KA15", fiio().caps.name)
        assertEquals(setOf(7, 8, 9), fiio().fiio!!.userSlots)
    }
    @Test fun `descriptor separates input and output with exact one ID`() {
        val bytes = listOf(0x06, 0x00, 0xFF, 0x09, 1, 0xA1, 1, 0x85, 7, 0x75, 8, 0x95, 63, 0x81, 2, 0x95, 63, 0x91, 2, 0xC0)
        val s = NativeHidReports.parse(bytes.map { it.toByte() }.toByteArray())
        assertEquals(64, s.rawSize(7, NativeHidReports.Kind.INPUT))
        assertEquals(64, s.rawSize(7, NativeHidReports.Kind.OUTPUT))
        s.requireSameOwner(7, NativeHidReports.Kind.INPUT, NativeHidReports.Kind.OUTPUT)
        val w = s.wire(7, NativeHidReports.Kind.OUTPUT, byteArrayOf(0xBB.toByte(), 0x0B))
        assertEquals(64, w.size); assertEquals(7, w[0].toInt()); assertEquals(0xBB.toByte(), w[1])
        assertFails { s.payload(7, NativeHidReports.Kind.INPUT, w.copyOf(63)) }
        assertFails { s.rawSize(7, NativeHidReports.Kind.FEATURE) }
    }
    @Test fun `native reads never mutate`() {
        val f = FiioPort(fiio())
        assertIs<NativeState.Fiio>(NativeSession(fiio(), f).read()); assertTrue(f.events.none { it.startsWith("write") })
    }
    @Test fun `invalid last band over capacity and missing HOLD are rejected before mutation`() {
        val t = fiio(); val port = FiioPort(t)
        assertFails { NativeSession(t, port).write(profile(), false) }
        assertFails { NativeSession(t, port).write(profile(count = 11), true) }
        assertFails { NativeSession(t, port).write(profile().copy(bands = profile().bands + Band("bad", FilterType.LOW_PASS, 1000.0, 0.0, 1.0)), true) }
        assertFails { NativeSession(t, port).write(profile(preamp = -12.1), true) }
        assertTrue(port.events.none { it.startsWith("write") })
    }
    @Test fun `generation guard aborts after every read before snapshot or mutation`() {
        val t = fiio(); val port = FiioPort(t); port.detachAfterRequest = 1
        assertFails { NativeSession(t, port).write(profile(), true) }
        assertFalse(port.events.any { it.startsWith("write") })
    }
    @Test fun `native imports retain fractional preamp and reproduce raw matcher`() {
        val t = fiio(); val port = FiioPort(t)
        val p = profile(preamp = -2.3); NativeSession(t, port).write(p, true)
        val state = NativeSession(t, port).read(); val eq = state.importExact()
        assertEquals(-2.3, eq.preampDb); assertTrue(state.matches(p.copy(bands = eq.bands, preampDb = eq.preampDb)))
    }
    @Test fun `generation change after mutation prevents every following report and publication`() {
        val t = fiio(); val port = FiioPort(t); port.detachAfterMutation = true
        assertFails { NativeSession(t, port).write(profile(), true) }
        assertEquals(1, port.events.count { it.startsWith("write:") })
    }
    @Test fun `generation change during pacing cannot return a verified result`() {
        val t = fiio(); val port = FiioPort(t); port.detachOnPause = true
        assertFails { NativeSession(t, port).write(profile(), true) }
        assertFalse(port.live)
    }
    @Test fun `KA15 pregain is fractional within its own range and A B stays Micro only`() {
        val t = fiio(); assertEquals(-12.0, t.preampMin); assertEquals(12.0, t.preampMax); assertEquals(0.1, t.preampStep)
        assertEquals(-11.7, t.shownPreamp(profile(preamp = -11.7)))
        assertFalse(t.supportsAb); assertTrue(t.native); assertFalse(t.stable)
        assertEquals(10, t.caps.bands); assertEquals(-12.0, t.caps.gainMinDb); assertEquals(12.0, t.caps.gainMaxDb)
        assertTrue(t.issues(profile(preamp = -12.1)).isNotEmpty())
        assertTrue(t.issues(profile(count = 11)).isNotEmpty())
        assertTrue(t.issues(profile(count = 10)).isEmpty())
    }
}
