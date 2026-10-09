package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

/** Explicitly SYNTHETIC fake USB state machines exercise the SAME session Android invokes.
 * They verify sequencing and fail-closed behaviour, not physical firmware or audio support. */
class NativeIntegrationTest {
    private fun profile(gain: Double = -1.0, preamp: Double? = 0.0, count: Int = 1) =
        Profile("id", "test", bands = List(count) { Band("b$it", FilterType.PEAK, 1000.0, gain, 1.0) },
            preampDb = preamp, createdAt = 0, updatedAt = 0)
    private fun fiio(name: String = "FIIO KA15"): DeviceTarget {
        val c = FiioCatalog.rules.single { it.productName == name }; val i = requireNotNull(c.capture)
        return requireNotNull(DeviceTarget.find(i.vendorId, i.productId, true, name))
    }
    private fun kt(name: String = "TANCHJIM-ONE DSP"): DeviceTarget =
        requireNotNull(DeviceTarget.find(0x31B2, 0x0111, true, name))
    private fun fosi() = requireNotNull(DeviceTarget.find(0x152A, 0x88DB, true, "Fosi Audio DS3"))
    private abstract class FakePort : NativePort {
        override var deviceKey = "generation-1"
        override fun payloadSize(id: Int, kind: NativeHidReports.Kind): Int = 63
        val events = ArrayList<String>(); var live = true; var requests = 0
        var detachAfterRequest: Int? = null
        var detachAfterMutation = false; var detachOnPause = false
        fun mutationFinished() { if (detachAfterMutation) live = false }
        override fun guard() { check(live) { "SESSION CHANGED" } }
        override fun pause(ms: Long) { events += "pause:$ms"; if (detachOnPause) live = false }
        override fun feature(id: Int): ByteArray = error("Feature not supported")
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
    private class KtPort(val target: DeviceTarget, slot: Int = 3) : FakePort() {
        val registers = HashMap<Int, List<Int>>(); var selectionWorks = true; var saveMismatch = false; var detachOnSave = false
        init {
            registers[0x24] = listOf(slot, 0, 0, 0); registers[0x66] = listOf(0, 0, 0, 0)
            target.kt!!.registers.bands.forEach { registers[it.gainFrequency] = listOf(0, 0, 232, 3); registers[it.qType] = listOf(232, 3, 0, 0) }
        }
        override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) {
            val reg = payload[0].toInt() and 255; val cmd = payload[4].toInt() and 255
            events += "write:$reg:$cmd"
            require(reg != 0x66 && cmd != 0x43)
            if (cmd == 0x57 && (reg != 0x24 || selectionWorks)) registers[reg] = payload.drop(6).take(4).map { it.toInt() and 255 }
            if (cmd == 0x53 && saveMismatch) registers[0x26] = listOf(1, 0, 232, 3)
            mutationFinished()
            if (cmd == 0x53 && detachOnSave) live = false
        }
        override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
            val cmd = payload[4].toInt() and 255; val reg = payload[0].toInt() and 255
            if (cmd == 0x57) send(id, NativeHidReports.Kind.OUTPUT, payload)
            else events += "read:$reg"
            val reply = payload.copyOf().also { registers.getValue(reg).forEachIndexed { i, n -> it[i + 6] = n.toByte() } }
            return finishedRequest(reply, matches)
        }
    }
    private class FosiPort : FakePort() {
        var enabled = true; var mode = 7; var rate = 48000
        // SYNTHETIC 32-band user layout. Factory can still expose just eight.
        var userBankCount = 8; var firmware: String? = null
        val extraUserBands = (8 until 32).associateWith { index ->
            ByteArray(63).also { p ->
                p[0] = 0x77; p[1] = 0x8E.toByte(); p[2] = 7; p[3] = index.toByte(); p[4] = 2
                ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).apply {
                    putFloat(5, 1000f); putFloat(9, 1f); putFloat(17, if (index == 8) 6f else 0f)
                }
            }
        }
        var selectWorks = true; var corruptAck = false; var corruptSavedBand = false
        private var last = ByteArray(63)
        val bands = ArrayList<FosiCodec.RawBand>()
        init { repeat(8) { bands += FosiCodec.RawBand(7, it, 2, 1000f.toRawBits(), 1f.toRawBits(), 0f.toRawBits(), 0f.toRawBits()) } }
        override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) {
            assertEquals(1, id); last = payload.copyOf()
            val cmd = payload[1].toInt() and 255; events += "set:$cmd:$kind"
            fun u(i: Int) = payload[i].toInt() and 255
            when (cmd) {
                0x8A -> { assertEquals(NativeHidReports.Kind.FEATURE, kind); if (selectWorks) mode = u(2) }
                0x8D -> {
                    assertEquals(NativeHidReports.Kind.FEATURE, kind)
                    val p = payload.copyOf().also { it[1] = 0x8E.toByte() }
                    bands[u(3)] = FosiCodec.parseBand(FosiCodec.Reply(deviceKey, 1, FosiCodec.ReportKind.FEATURE, p), deviceKey, mode, u(3))
                }
                0x9D -> { assertEquals(NativeHidReports.Kind.OUTPUT, kind); enabled = u(2) == 1 }
                0x92 -> if (corruptSavedBand) bands[7] = bands[7].copy(gainBits = (-1f).toRawBits())
            }
            if (cmd in setOf(0x91, 0x8A, 0x8D, 0x92, 0x9D)) mutationFinished()
        }
        override fun feature(id: Int): ByteArray {
            val cmd = last[1].toInt() and 255; events += "get:$cmd"
            val p = ByteArray(63).also { it[0] = 0x77; it[1] = cmd.toByte() }
            when (cmd) {
                0x9E -> { p[2] = if (enabled) 1 else 0; p[3] = mode.toByte() }
                0x9F -> ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putInt(2, rate)
                0x8E -> {
                    val index = last[3].toInt() and 255; val bank = last[2].toInt() and 255
                    if (bank == 7 && index >= 8 && userBankCount == 32) extraUserBands.getValue(index).copyInto(p)
                    else FosiCodec.encodeRawBand(bands[index].copy(preset = bank)).copyInto(p).also { p[1] = 0x8E.toByte() }
                }
                0x9D -> { p[3] = if (enabled) 1 else 0; p[4] = mode.toByte() }
                else -> if (corruptAck) p[1] = 0
            }
            requests++; if (requests == detachAfterRequest) live = false
            return byteArrayOf(1) + p
        }
        override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray = error("DS3 must GET_FEATURE, not interrupt")
    }
    private fun descriptor(id: Int, input: Int = 63, output: Int = 63, feature: Int? = null): ByteArray {
        val bytes = mutableListOf(0x06, 0x00, 0xFF, 0x09, 1, 0xA1, 1, 0x85, id, 0x75, 8)
        listOf(0x81 to input, 0x91 to output).forEach { (kind, count) -> bytes += listOf(0x95, count, kind, 2) }
        feature?.let { bytes += listOf(0x95, it, 0xB1, 2) }; bytes += 0xC0
        return bytes.map { it.toByte() }.toByteArray()
    }
    private fun fosiMutations(port: FosiPort) = port.events.filter { event ->
        setOf(0x91, 0x8A, 0x8D, 0x92, 0x9D).any { event.startsWith("set:$it:") }
    }
    private fun assertFosiReadOnly(port: FosiPort) {
        val tail = port.extraUserBands.getValue(8).copyOf()
        assertEquals(6f, ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN).getFloat(17))
        val session = NativeSession(fosi(), port)
        val diagnostic = assertIs<NativeState.Fosi>(session.read())
        assertFails { session.write(profile(gain = 0.0), true) }
        assertTrue(fosiMutations(port).isEmpty(), port.events.toString())
        assertFalse(diagnostic.matches(profile(gain = 0.0)))
        assertFails { diagnostic.importExact() }
        assertNull(diagnostic.preamp)
        assertTrue(diagnostic.describe().contains("UNKNOWN"))
        assertContentEquals(tail, port.extraUserBands.getValue(8))
        assertNotNull(fosi().writeBlocker)
    }
    @Test fun `Fosi unknown firmware never qualifies writes`() = assertFosiReadOnly(FosiPort())
    @Test fun `Fosi 32 user bands with active tail never qualify writes`() = assertFosiReadOnly(
        FosiPort().also { it.userBankCount = 32; it.firmware = "1.4.15" })
    @Test fun `Fosi factory eight reads cannot qualify Custom1 user bank`() = assertFosiReadOnly(
        FosiPort().also { it.userBankCount = 32; it.mode = 4; it.firmware = "1.4.15" })
    @Test fun `six exact FiiO capture identities route native including KA15 override`() {
        val identities = FiioCatalog.rules.mapNotNull { it.capture }
        assertEquals(6, identities.size)
        identities.forEach { assertNotNull(DeviceTarget.find(it.vendorId, it.productId, true, it.productName)?.fiio) }
        assertNotNull(fiio().fiio); assertNull(fiio().walkplay)
        assertNull(DeviceTarget.find(0x2972, 0x0104, false, "FIIO KA15"))
    }
    @Test fun `source names without PID are runtime candidates never guessed identity writers`() {
        val c = FiioCatalog.rules.first { it.capture == null && !it.bestGuessProductName && it.codecBlockers.isEmpty() }
        val t = assertNotNull(DeviceTarget.find(c.vendorIds.first(), 0xFFFF, true, c.productName))
        assertTrue(t.runtimeCandidate); assertNotNull(t.fiio)
        assertNull(DeviceTarget.find(c.vendorIds.first(), 0xFFFF, true, c.productName.lowercase()))
        val best = FiioCatalog.rules.first { it.bestGuessProductName }
        assertNotNull(DeviceTarget.find(best.vendorIds.first(), 0xFFFF, true, best.productName)?.writeBlocker)
    }
    @Test fun `blocked KT and wrong DS3 names cannot select native driver`() {
        assertNull(DeviceTarget.find(0x31B2, 0x1119, true, "TANCHJIM-FISSION  DSP")?.kt)
        assertNull(DeviceTarget.find(0x31B2, 0x1132, true, "CDSP")?.kt)
        assertNull(DeviceTarget.find(0x152A, 0x88DB, true, "Fosi Audio DS3 ")?.takeIf { it.fosi })
        assertTrue(DeviceTarget.find(0x31B2, 0x0200, true, "CDSP")!!.runtimeCandidate)
    }
    @Test fun `descriptor separates feature output and input with exact one ID`() {
        val s = NativeHidReports.parse(descriptor(1, input = 8, feature = 63))
        assertEquals(9, s.rawSize(1, NativeHidReports.Kind.INPUT))
        assertEquals(64, s.rawSize(1, NativeHidReports.Kind.FEATURE))
        val w = s.wire(1, NativeHidReports.Kind.FEATURE, byteArrayOf(0x77, 0x9E.toByte()))
        assertEquals(64, w.size); assertEquals(1, w[0].toInt()); assertEquals(0x77, w[1].toInt())
        assertFails { s.payload(1, NativeHidReports.Kind.FEATURE, w.copyOf(63)) }
        assertFails { s.wire(1, NativeHidReports.Kind.INPUT, ByteArray(9)) }
    }
    @Test fun `KT 10 byte logical report pads to descriptor not Micro fixed size`() {
        val s = NativeHidReports.parse(descriptor(0x4B, 10, 10))
        val wire = s.wire(0x4B, NativeHidReports.Kind.OUTPUT, KtMicroCodec.readRegister(0x26).payload)
        assertEquals(11, wire.size); assertEquals(0x4B, wire[0].toInt()); assertEquals(0x26, wire[1].toInt())
    }
    @Test fun `native reads never mutate and DS3 remains a partial diagnostic`() {
        val f = FiioPort(fiio()); val k = KtPort(kt()); val d = FosiPort()
        assertIs<NativeState.Fiio>(NativeSession(fiio(), f).read()); assertTrue(f.events.none { it.startsWith("write") })
        assertIs<NativeState.Kt>(NativeSession(kt(), k).read()); assertTrue(k.events.none { it.startsWith("write") })
        val diagnostic = assertIs<NativeState.Fosi>(NativeSession(fosi(), d).read())
        assertEquals(FosiCodec.Layout.UNKNOWN, diagnostic.raw.layout)
        assertEquals(8, diagnostic.raw.bands.size)
        assertTrue(fosiMutations(d).isEmpty())
    }
    @Test fun `FiiO selection then full backup precedes parameters and full post save matches`() {
        val t = fiio(); val port = FiioPort(t); port.state = port.state.copy(activeSlot = 0)
        val p = profile(preamp = -2.3)
        val result = NativeSession(t, port).write(p, true)
        assertTrue(result.verified); assertFalse(result.pending); assertTrue(result.readback!!.matches(p))
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
        assertFalse(r.verified); assertFalse(r.pending); assertFalse(r.readback!!.matches(profile()))
    }
    @Test fun `JA11 save is pending not success and cannot report ON DAC`() {
        val t = fiio("JadeAudio JA11"); val port = FiioPort(t)
        val r = NativeSession(t, port).write(profile(), true)
        assertFalse(r.verified); assertTrue(r.pending); assertNull(r.readback)
        assertFalse(NativeSession(t, port).read().matches(profile()))
    }
    @Test fun `KT slot ack is mandatory before bands and complete raw readback verifies`() {
        val t = kt(); val port = KtPort(t, slot = 2)
        val r = NativeSession(t, port).write(profile(), true)
        assertTrue(r.verified); assertTrue(r.readback!!.matches(profile()))
        val selection = port.events.indexOf("write:36:87")
        assertTrue(selection >= 0); assertTrue(port.events.indexOf("write:38:87") > selection)
        assertTrue(port.events.none { it.startsWith("write:102:") })
    }
    @Test fun `KT failed ack and nonzero pregain block band writes`() {
        val t = kt(); val port = KtPort(t, 2); port.selectionWorks = false
        assertFails { NativeSession(t, port).write(profile(), true) }
        assertEquals(listOf("write:36:87"), port.events.filter { it.startsWith("write") })
        val other = KtPort(t); other.registers[0x66] = listOf(255, 0, 0, 0)
        assertFails { NativeSession(t, other).write(profile(), true) }; assertTrue(other.events.none { it.startsWith("write") })
    }
    @Test fun `KT Allegro save remains pending until reconnect proof`() {
        val t = kt("Kiwi Ears-Allegro Mini"); val port = KtPort(t)
        val r = NativeSession(t, port).write(profile(), true)
        assertTrue(r.pending); assertFalse(r.verified); assertNull(r.readback)
        assertFalse(NativeSession(t, port).read().matches(profile()))
    }
    @Test fun `Fosi feature sequence reads first eight without claiming complete state`() {
        val port = FosiPort(); val session = NativeSession(fosi(), port)
        val state = assertIs<NativeState.Fosi>(session.read())
        assertEquals(8, state.raw.bands.size)
        assertTrue(port.events.contains("get:158")); assertTrue(port.events.contains("get:159"))
        assertFalse(state.matches(profile(gain = 0.0)))
        assertFails { state.importExact() }
        assertFails { session.write(profile(), true) }
        assertTrue(fosiMutations(port).isEmpty())
    }
    @Test fun `Fosi write is blocked before selection ack or save can matter`() {
        val selection = FosiPort(); selection.mode = 0; selection.selectWorks = false
        assertFails { NativeSession(fosi(), selection).write(profile(), true) }
        assertTrue(fosiMutations(selection).isEmpty())
        val ack = FosiPort(); ack.corruptAck = true
        assertFails { NativeSession(fosi(), ack).write(profile(), true) }; assertTrue(fosiMutations(ack).isEmpty())
        val late = FosiPort(); late.corruptSavedBand = true
        assertFails { NativeSession(fosi(), late).write(profile(), true) }; assertTrue(fosiMutations(late).isEmpty())
    }
    @Test fun `all families reject invalid last band over capacity and missing HOLD before mutation`() {
        listOf(fiio() to FiioPort(fiio()), kt() to KtPort(kt()), fosi() to FosiPort()).forEach { (t, port) ->
            assertFails { NativeSession(t, port).write(profile(), false) }
            assertFails { NativeSession(t, port).write(profile(count = 31), true) }
            assertFails { NativeSession(t, port).write(profile().copy(bands = profile().bands + Band("bad", FilterType.LOW_PASS, 1000.0, 0.0, 1.0)), true) }
            assertTrue(port.events.none { it.startsWith("write") || it.startsWith("set") })
        }
    }
    @Test fun `KT and Fosi cannot silently discard manual or AUTO attenuation`() {
        listOf(kt() to KtPort(kt()), fosi() to FosiPort()).forEach { (t, port) ->
            assertFails { NativeSession(t, port).write(profile(preamp = -0.1), true) }
            assertFails { NativeSession(t, port).write(profile(gain = 2.0, preamp = null), true) }
            assertTrue(port.events.isEmpty())
        }
    }
    @Test fun `generation guard aborts after every read before snapshot or mutation`() {
        listOf(fiio() to FiioPort(fiio()), kt() to KtPort(kt()), fosi() to FosiPort()).forEach { (t, port) ->
            port.detachAfterRequest = 1
            assertFails { NativeSession(t, port).write(profile(), true) }
            assertFalse(port.events.any { it.startsWith("write") || it.startsWith("set:138:") || it.startsWith("set:141:") })
        }
    }
    @Test fun `native imports retain fractional preamp and reproduce raw matcher`() {
        val t = fiio(); val port = FiioPort(t)
        val p = profile(preamp = -2.3); NativeSession(t, port).write(p, true)
        val state = NativeSession(t, port).read(); val eq = state.importExact()
        assertEquals(-2.3, eq.preampDb); assertTrue(state.matches(p.copy(bands = eq.bands, preampDb = eq.preampDb)))
        val k = NativeSession(kt(), KtPort(kt())).read(); assertEquals(5, k.importExact().bands.size)
        val d = NativeSession(fosi(), FosiPort()).read(); assertFails { d.importExact() }; assertFalse(d.matches(profile()))
    }
    @Test fun `generation change after mutation prevents every following report and publication`() {
        listOf(fiio() to FiioPort(fiio()), kt() to KtPort(kt(), 2), fosi() to FosiPort()).forEach { (t, port) ->
            port.detachAfterMutation = true
            assertFails { NativeSession(t, port).write(profile(), true) }
            assertEquals(if (t.fosi) 0 else 1, port.events.count { it.startsWith("write:") || it.startsWith("set:145:") })
            assertFalse(port.events.contains("set:141:FEATURE"))
        }
    }
    @Test fun `generation change during pacing cannot return a verified result`() {
        listOf(fiio() to FiioPort(fiio()), kt() to KtPort(kt()), fosi() to FosiPort()).forEach { (t, port) ->
            port.detachOnPause = true
            assertFails { NativeSession(t, port).write(profile(), true) }
            if (t.fosi) { assertTrue(port.live); assertTrue(port.events.isEmpty()) } else assertFalse(port.live)
        }
    }
    @Test fun `offline has 31 slots and FiiO wide pregain capabilities remain fractional`() {
        assertEquals(31, DeviceTarget.OFFLINE.caps.bands)
        val t = fiio("FIIO QX13"); assertEquals(-24.0, t.preampMin); assertEquals(0.1, t.preampStep)
        assertEquals(-23.7, t.shownPreamp(profile(preamp = -23.7)))
        // KA15: intended range = register -12..+12 dB minus the measured 12 dB offset (04.10.2026).
        val ka = fiio(); assertEquals(-24.0, ka.preampMin); assertEquals(0.0, ka.preampMax)
        assertTrue(ka.issues(profile(preamp = -24.0)).isEmpty()); assertTrue(ka.issues(profile(preamp = 0.0)).isEmpty())
        assertTrue(ka.issues(profile(preamp = -24.1)).isNotEmpty()); assertTrue(ka.issues(profile(preamp = 0.1)).isNotEmpty())
        assertFalse(t.supportsAb); assertFalse(kt().supportsAb); assertFalse(fosi().supportsAb)
    }
    private fun receipt(t: DeviceTarget, port: FakePort, p: Profile = profile(), serial: String? = null): NativePendingReceipt {
        var expected: NativePendingExpected? = null
        val r = NativeSession(t, port).write(p, true) { expected = it }
        assertTrue(r.pending); assertFalse(r.verified); assertNull(r.readback)
        val i = t.fiio?.capture
        val identity = NativeUsbIdentity(i?.vendorId ?: 0x31B2, i?.productId ?: 0x0111,
            t.fiio?.productName ?: t.kt!!.productName, serial)
        return NativePendingReceipt(identity, t, p, 1, assertNotNull(expected))
    }
    private fun verify(r: NativePendingReceipt, state: NativeState?, p: Profile? = r.profile,
                       identity: NativeUsbIdentity = r.identity, read: Long = 2, current: Long = 2,
                       explicit: Boolean = true) = r.verify(identity, r.target, p, state, read, current, explicit)

    @Test fun `JA11 explicit reconnect read resolves original immutable quantized save without writes`() {
        val t = fiio("JadeAudio JA11"); val port = FiioPort(t); val p = profile(preamp = -2.34)
        val r = receipt(t, port, p); val newPort = FiioPort(t).also { it.state = port.state; it.deviceKey = "generation-2" }
        val raw = NativeSession(t, newPort).read()
        assertFalse(raw.matches(p))
        val proof = verify(r, raw)
        assertTrue(proof.verified); assertTrue(assertNotNull(proof.state).matches(p))
        assertEquals(-2.4, proof.state!!.preamp) // pregain is rounded down (quieter): -2.34 -> -2.4 (it was -2.3 with nearest rounding)
        assertTrue(newPort.events.none { it.startsWith("write") }); assertNull((raw as NativeState.Fiio).receiptProfile)
        assertFalse(proof.state!!.matches(p.copy(updatedAt = 1)))
    }
    @Test fun `Allegro full reconnect proof covers preserved slot and pregain reserved bytes`() {
        val t = kt("Kiwi Ears-Allegro Mini"); val port = KtPort(t)
        port.registers[0x24] = listOf(3, 6, 7, 8); port.registers[0x66] = listOf(0, 12, 13, 14)
        val r = receipt(t, port)
        port.deviceKey = "generation-2"; port.events.clear()
        val state = NativeSession(t, port).read(); val proof = verify(r, state)
        assertTrue(proof.verified); assertTrue(proof.state!!.matches(r.profile))
        assertTrue(port.events.none { it.startsWith("write") })
        port.registers[0x66] = listOf(0, 12, 13, 15)
        assertFalse(verify(r, NativeSession(t, port).read()).verified)
        port.registers[0x66] = listOf(0, 12, 13, 14); port.registers[0x24] = listOf(3, 6, 7, 9)
        assertFalse(verify(r, NativeSession(t, port).read()).verified)
        port.registers[0x24] = listOf(3, 6, 7, 8)
        val qt = t.kt!!.registers.bands.first().qType
        val old = port.registers.getValue(qt)
        port.registers[qt] = old.dropLast(1) + 1
        assertFalse(verify(r, NativeSession(t, port).read()).verified)
        port.registers[qt] = old
        val gf = t.kt!!.registers.bands.first().gainFrequency
        port.registers[gf] = port.registers.getValue(gf).mapIndexed { i, v -> if (i == 0) v xor 1 else v }
        assertFalse(verify(r, NativeSession(t, port).read()).verified)
    }
    @Test fun `pending receipt refuses wrong exact PID VID name family and serial`() {
        val t = fiio("JadeAudio JA11"); val port = FiioPort(t); val r = receipt(t, port, serial = "synthetic-unit-A")
        val state = NativeSession(t, port).read()
        for (i in listOf(r.identity.copy(vendorId = 0xFFFF), r.identity.copy(productId = 0xFFFF),
            r.identity.copy(productName = r.identity.productName + " "), r.identity.copy(serial = "synthetic-unit-B"), r.identity.copy(serial = null))) {
            assertFalse(verify(r, state, identity = i).verified)
        }
        assertFalse(r.verify(r.identity, fiio(), r.profile, state, 2, 2, true).verified)
        assertFalse(verify(r, NativeSession(kt(), KtPort(kt())).read()).verified)
    }
    @Test fun `missing serial requires explicit user verification even after matching reconnect read`() {
        val t = fiio("JadeAudio JA11"); val port = FiioPort(t); val r = receipt(t, port)
        val state = NativeSession(t, port).read()
        assertTrue(r.identityWarning.contains("cannot prove")); assertTrue(r.pendingReason.contains("restart"))
        val auto = verify(r, state, explicit = false)
        assertFalse(auto.verified); assertNull(auto.state); assertTrue(auto.reason.contains("explicitly"))
        val user = verify(r, state); assertTrue(user.verified); assertTrue(user.reason.contains("user-confirmed"))
    }
    @Test fun `serial proven automatic reconnect read never sends retries or mutations`() {
        val t = kt("Kiwi Ears-Allegro Mini"); val port = KtPort(t); val r = receipt(t, port, serial = "synthetic-unit")
        port.events.clear(); port.deviceKey = "generation-2"
        val state = NativeSession(t, port).read()
        assertTrue(verify(r, state, explicit = false).verified)
        assertFalse(verify(r, state, read = 1, current = 1, explicit = false).verified)
        assertTrue(port.events.all { it.startsWith("read") })
    }
    @Test fun `changed removed reordered or disabled original profile cannot mark sent`() {
        val t = fiio("JadeAudio JA11"); val port = FiioPort(t)
        val p = profile(count = 2); val r = receipt(t, port, p); val state = NativeSession(t, port).read()
        for (changed in listOf<Profile?>(null, p.copy(updatedAt = 1), p.copy(id = "replacement"),
            p.copy(preampDb = -0.1), p.copy(bands = p.bands.reversed()),
            p.copy(bands = p.bands.map { it.copy(enabled = false) }))) {
            assertFalse(verify(r, state, p = changed).verified)
        }
        assertTrue(verify(r, state).verified)
    }
    @Test fun `one native byte count bank and fractional pregain mismatches remain unverified`() {
        val t = fiio("JadeAudio JA11"); val port = FiioPort(t); val r = receipt(t, port)
        val state = assertIs<NativeState.Fiio>(NativeSession(t, port).read())
        for (raw in listOf(state.raw.copy(preampTenths = state.raw.preampTenths - 1),
            state.raw.copy(activeSlot = 0), state.raw.copy(count = state.raw.count + 1),
            state.raw.copy(bands = state.raw.bands.map { it.copy(qHundredths = it.qHundredths + 1) }))) {
            val proof = verify(r, state.copy(raw = raw)); assertFalse(proof.verified); assertNull(proof.state)
        }
    }
    @Test fun `partial native snapshots and incomplete reconnect reads never resolve pending`() {
        val t = fiio("JadeAudio JA11"); val port = FiioPort(t); val r = receipt(t, port)
        val state = assertIs<NativeState.Fiio>(NativeSession(t, port).read())
        assertFalse(verify(r, null).verified)
        assertFalse(verify(r, state.copy(raw = state.raw.copy(bands = emptyList()))).verified)
        val k = kt("Kiwi Ears-Allegro Mini"); val kp = KtPort(k); val kr = receipt(k, kp)
        val ks = assertIs<NativeState.Kt>(NativeSession(k, kp).read())
        assertFalse(verify(kr, ks.copy(raw = KtMicroCodec.Snapshot(ks.raw.model, "generation-2", ks.raw.bands,
            ks.raw.registers.dropLast(1), ks.slot, ks.raw.pregainDb))).verified)
    }
    @Test fun `new current generation guard rejects queued old reads before publishing proof`() {
        val t = fiio("JadeAudio JA11"); val port = FiioPort(t); val r = receipt(t, port)
        val state = NativeSession(t, port).read()
        assertFalse(verify(r, state, read = 2, current = 3).verified)
        assertFalse(verify(r, state, read = 0, current = 0).verified)
        assertTrue(verify(r, state, read = 3, current = 3).verified)
    }
    @Test fun `SAVE triggered detach retains expectation but permits no report or success afterwards`() {
        for ((t, port) in listOf(fiio("JadeAudio JA11") to FiioPort(fiio("JadeAudio JA11")).also { it.detachOnSave = true },
            kt("Kiwi Ears-Allegro Mini") to KtPort(kt("Kiwi Ears-Allegro Mini")).also { it.detachOnSave = true })) {
            var expected: NativePendingExpected? = null
            assertFails { NativeSession(t, port).write(profile(), true) { expected = it } }
            assertNotNull(expected); assertFalse(port.live)
            assertTrue(port.events.last().startsWith("write:"))
            val before = port.events.toList(); assertFails { NativeSession(t, port).read() }
            assertEquals(before, port.events)
        }
    }
    @Test fun `pre SAVE detach creates no receipt and cannot be retried automatically`() {
        val t = fiio("JadeAudio JA11"); val port = FiioPort(t).also { it.detachAfterMutation = true }
        var expected: NativePendingExpected? = null
        assertFails { NativeSession(t, port).write(profile(), true) { expected = it } }
        assertNull(expected); assertEquals(1, port.events.count { it.startsWith("write:") })
    }
    @Test fun `pending expectation owns immutable copies of plan and originating profile`() {
        val t = fiio("JadeAudio JA11"); val p = profile()
        val c = FiioCodec(t.fiio!!); val bands = c.planOnExplicitSend(p, t.destinationSlot!!, true).expected.bands.toMutableList()
        val expected = FiioSnapshot(t.fiio!!.productName, t.destinationSlot!!, bands.size, 0, bands)
        val expectation = NativePendingExpected.Fiio(t.fiio!!, expected)
        val sourceBands = p.bands.toMutableList(); val original = p.copy(bands = sourceBands)
        val r = NativePendingReceipt(NativeUsbIdentity(0x2972, 0xFFFF, t.fiio!!.productName), t, original, 1, expectation)
        bands.clear(); sourceBands.clear()
        val state = NativeState.Fiio(c, c.planOnExplicitSend(p, t.destinationSlot!!, true).expected)
        assertTrue(verify(r, state, p = p).verified); assertEquals(p, r.profile)
    }
}
