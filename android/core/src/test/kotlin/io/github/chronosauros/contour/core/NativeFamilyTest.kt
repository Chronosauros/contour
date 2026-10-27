package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.*
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFails
import kotlin.test.assertSame
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun hexBytes(s: String) = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
private fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it.toInt() and 255) }
private fun resource(name: String) = requireNotNull(NativeFamilyTest::class.java.getResourceAsStream("/bench/$name")) { name }.use { it.readBytes() }.decodeToString()

/**
 * The KT Micro (Kiwi Ears Allegro Mini) and native Moondrop (Dawn Pro 2) sessions.
 *
 * REAL: the two read logs of the PC measuring rig (DacLab, `runs/2026-10-20-kiwi-ears-allegro-mini-05/raw/s2-read-232520.log` and
 * `runs/2026-10-21-moondrop-dawn-pro-2-01/raw/s2-read-103434.log`, copied to test resources `bench/`) and the two HID report
 * descriptors of those units (`t01-descriptor-*.hex`) are replayed through the sessions: every frame the session sends must be the
 * log's TX byte for byte, and what it reads is what the unit answered. SYNTHETIC: the fake DACs of the write tests (they check the
 * frames and the order, not firmware behaviour; no write was ever sent to either unit).
 */
class NativeFamilyTest {
    /** The Dawn Pro 2 and the Allegro Mini write only in a review build (or once proven): the tests of their write path run as one ([Proven]). */
    private fun review(block: () -> Unit) = Proven.asChannel(BuildChannel.PERF, block)

    /** A grouped bench log (`TX <report>` then its `RX <report>` lines) as a port: the frames must match the log, a request gets the first accepted RX. */
    private class LogPort(text: String, private val reportId: Int, private val shape: NativeHidReports.Shape?, private val expectedKey: String) : NativePort {
        class Frame(val tx: ByteArray, val rx: MutableList<ByteArray> = ArrayList())
        override val deviceKey = expectedKey
        val frames = ArrayList<Frame>()
        var next = 0
        val pauses = ArrayList<Long>()
        val notes = ArrayList<String>()
        init {
            text.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.forEach { line ->
                val body = line.substringAfter(' ').split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()
                when (line.substringBefore(' ')) {
                    "TX" -> frames += Frame(body)
                    "RX" -> frames.last().rx += body
                    else -> error("Not a bench log line: $line")
                }
            }
        }
        override fun guard() {}
        override fun pause(ms: Long) { pauses += ms }
        override fun log(message: String) { notes += message }
        override fun payloadSize(id: Int, kind: NativeHidReports.Kind) = requireNotNull(shape) { "no descriptor" }.payloadSize(id, kind)
        private fun take(id: Int, payload: ByteArray): Frame {
            assertEquals(reportId, id)
            assertTrue(next < frames.size, "the session sends more than the log holds: ${hex(payload)}")
            val f = frames[next++]
            val sent = byteArrayOf(id.toByte()) + payload
            assertTrue(f.tx.size >= sent.size && sent.indices.all { f.tx[it] == sent[it] } && (sent.size until f.tx.size).all { f.tx[it] == 0.toByte() },
                "frame $next: the session sends ${hex(sent)}, the log has ${hex(f.tx)}")
            return f
        }
        override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) { take(id, payload) }
        override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray =
            take(id, payload).rx.filter { it[0].toInt() == id }.map { it.copyOfRange(1, it.size) }.firstOrNull { matches(it) }
                ?: throw IOException("Native request timed out or returned invalid state")
    }

    // ---- KT Micro: Kiwi Ears Allegro Mini ------------------------------------------------------------------------------------

    private val allegro = requireNotNull(KtMicroCatalog.find(0x31B2, 0x0111, "Kiwi Ears-Allegro Mini"))

    @Test fun `the real Allegro Mini read log replays frame for frame and decodes to the unit's neutral 5-band EQ, EQ off`() {
        val port = LogPort(resource("allegro-mini-read-232520.log"), 0x4B, null, "k")
        val s = KtMicroSession(allegro, port).read()
        assertEquals(port.frames.size, port.next) // all 14 frames, nothing left over
        assertEquals(14, port.frames.size)
        assertEquals(2, s.eqControl)
        assertFalse(s.eqEnabled)
        assertEquals(5, s.bands.size)
        assertEquals(listOf(1000, 2000, 5000, 8000, 10000), s.bands.map { it.storedFrequencyHz })
        assertTrue(s.bands.all { it.gainTenths == 0 && it.qMilli == 707 && it.nativeType == 0 })
        assertEquals(0.0, s.digitalGain!!.leftDb)
        assertNull(s.digitalGain!!.rightDb) // channel flags say a single DAC
        assertTrue(s.hasZeroDigitalGain)
        assertNull(s.writeBlocker)
    }

    @Test fun `the Allegro Mini only knows its model by the exact USB name, PK bands, no preamp`() {
        assertNotNull(KtMicroCatalog.find(0x31B2, 0x0111, "Kiwi Ears Allegro Mini"))
        assertNull(KtMicroCatalog.find(0x31B2, 0x0111, "Kiwi Ears-Allegro PRO"))
        assertNull(KtMicroCatalog.find(0x31B2, 0x0112, "Kiwi Ears-Allegro Mini"))
        val port = LogPort(resource("allegro-mini-read-232520.log"), 0x4B, null, "k")
        val before = KtMicroSession(allegro, port).read()
        val ok = Profile("id", "t", bands = listOf(Band("a", FilterType.PEAK, 1000.0, 3.0, 1.0)), preampDb = 0.0, createdAt = 0, updatedAt = 0)
        KtMicroCodec.compile(ok, before)
        assertFailsWith<IllegalArgumentException> { KtMicroCodec.compile(ok.copy(preampDb = -3.0), before) } // no preamp
        assertFailsWith<IllegalArgumentException> { KtMicroCodec.compile(ok.copy(bands = listOf(Band("a", FilterType.LOW_SHELF, 100.0, 3.0, 1.0))), before) }
        assertFailsWith<IllegalArgumentException> { KtMicroCodec.compile(ok.copy(bands = listOf(Band("a", FilterType.PEAK, 1000.0, 12.5, 1.0))), before) }
        assertFailsWith<IllegalArgumentException> { KtMicroCodec.compile(ok.copy(bands = List(6) { Band("a$it", FilterType.PEAK, 1000.0, 1.0, 1.0) }), before) }
    }

    /** A KT Micro as a register store seeded from the log; SAVE drops it off the bus like the real unit does. */
    private class FakeKt(seed: LogPort) : NativePort {
        override val deviceKey = "k"
        val regs = HashMap<Int, ByteArray>()
        val events = ArrayList<String>()
        var gone = false
        init { seed.frames.forEach { f -> f.rx.forEach { r -> regs[r[1].toInt() and 255] = r.copyOfRange(7, 11) } } }
        override fun guard() { check(!gone) { "device gone" } }
        override fun pause(ms: Long) { events += "pause:$ms" }
        override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) {
            events += "tx:${hex(payload)}"
            if ((payload[4].toInt() and 255) == KtMicroCodec.SAVE) { gone = true; events += "disconnect" }
        }
        override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
            events += "tx:${hex(payload)}"
            val reg = payload[0].toInt() and 255
            val cmd = payload[4].toInt() and 255
            val out = payload.copyOf()
            if (cmd == KtMicroCodec.WRITE) { regs[reg] = payload.copyOfRange(6, 10); byteArrayOf(3, 0, 0, 0).copyInto(out, 6) }
            else regs.getValue(reg).copyInto(out, 6)
            return out.also { assertTrue(matches(it)) }
        }
    }

    @Test fun `an Allegro write is the EQ switch, ten acknowledged registers and SAVE, ends there and says the DAC reconnects`() {
        val dac = FakeKt(LogPort(resource("allegro-mini-read-232520.log"), 0x4B, null, "k"))
        val session = KtMicroSession(allegro, dac)
        val eq = Profile("id", "t", bands = listOf(Band("a", FilterType.PEAK, 1000.0, 3.0, 1.0), Band("b", FilterType.PEAK, 3000.0, -2.5, 2.0)),
            preampDb = 0.0, createdAt = 0, updatedAt = 0)
        val r = session.write(eq, explicitHold = true)
        assertTrue(r.pending)
        assertFalse(r.verified)
        assertNull(r.readback)
        assertTrue(r.reason!!.contains("restarts")) // a release says it plainly
        assertTrue(Proven.asChannel(BuildChannel.PERF) { KtMicroSession(allegro, FakeKt(LogPort(resource("allegro-mini-read-232520.log"), 0x4B, null, "k"))).write(eq, explicitHold = true).reason!!.contains("reconnects") }) // a review build keeps the technical wording
        val tx = dac.events.filter { it.startsWith("tx:") }.map { it.removePrefix("tx:") }
        // 14 reads (13 + the flags), then 0x24 = 3 (EQ on), the five bands (gain/freq, Q/type), SAVE
        assertEquals(14, tx.count { it.split(" ")[4] == "52" })
        val writes = tx.filter { it.split(" ")[4] == "57" }
        assertEquals("24 00 00 00 57 00 03 00 00 00", writes.first())
        assertEquals("26 00 00 00 57 00 1e 00 f4 01", writes[1])            // band 1: +3.0 dB, 1000 Hz intended = register 500 (x 24000 / 48000)
        assertEquals("27 00 00 00 57 00 e8 03 00 00", writes[2])            // Q 1.0, PK
        assertEquals("28 00 00 00 57 00 e7 ff dc 05", writes[3])            // band 2: -2.5 dB rounded down to -2.5 -> tenths -25 (0xffe7), 3000 Hz intended = register 1500
        assertEquals("29 00 00 00 57 00 d0 07 00 00", writes[4])            // Q 2.0
        assertEquals("2a 00 00 00 57 00 00 00 64 00", writes[5])            // unused bands: 0 dB, 100 Hz, Q 1.0, PK
        assertEquals(11, writes.size) // the EQ switch and ten band registers; SAVE is 0x53
        assertEquals("00 00 00 00 53 00 00 00 00 00", tx.last())              // SAVE, the last report
        assertEquals(listOf("tx:00 00 00 00 53 00 00 00 00 00", "disconnect"), dac.events.takeLast(2))
        assertFalse(dac.events.any { it == "pause:1000" }, "the session must not wait on a DAC that drops off the bus")
        // the plan the next read must show: the registers as written, EQ on
        val plan = session.lastPlan!!
        assertEquals(3, plan.expectedEqControl)
        assertEquals(listOf(3, 0, 0, 0), plan.expectedRegisters.single { it.register == 0x24 }.value)
        assertEquals(listOf(0x1e, 0, 0xf4, 1), plan.expectedRegisters.single { it.register == 0x26 }.value)
        assertTrue(plan.reconnectAfterSave)
        // a refused HOLD nor a refused profile sends anything
        val quiet = FakeKt(LogPort(resource("allegro-mini-read-232520.log"), 0x4B, null, "k"))
        assertFailsWith<IllegalArgumentException> { KtMicroSession(allegro, quiet).write(eq, explicitHold = false) }
        assertFailsWith<IllegalArgumentException> { KtMicroSession(allegro, quiet).write(eq.copy(preampDb = -2.0), explicitHold = true) }
        assertTrue(quiet.events.none { it.startsWith("tx:") && it.split(" ")[4] == "57" || it.contains("53 00 00 00 00 00") })
    }

    @Test fun `an Allegro send with eqEnabled null (the app's send) leaves the EQ switch as it found it - OFF stays OFF, ON stays ON, register 0x24 is never written`() {
        val eq = Profile("id", "t", bands = listOf(Band("a", FilterType.PEAK, 1000.0, 3.0, 1.0), Band("b", FilterType.PEAK, 3000.0, -2.5, 2.0)),
            preampDb = 0.0, createdAt = 0, updatedAt = 0)
        for (found in listOf(2, 3)) { // 2 = EQ OFF, 3 = EQ ON, as the DAC page switch leaves it
            val dac = FakeKt(allegroSeed())
            dac.regs[KtMicroCodec.EQ_ENABLE_REGISTER] = dac.regs.getValue(KtMicroCodec.EQ_ENABLE_REGISTER).also { it[0] = found.toByte() }
            val session = KtMicroSession(allegro, dac)
            val r = session.write(eq, explicitHold = true, eqEnabled = null)
            assertTrue(r.pending && !r.verified)
            val tx = dac.events.filter { it.startsWith("tx:") }.map { it.removePrefix("tx:") }
            val writes = tx.filter { it.split(" ")[4] == "57" }
            assertTrue(writes.none { it.startsWith("24 ") }, "EQ found $found: $writes") // no register 0x24 write
            assertEquals(10, writes.size, "EQ found $found") // the ten band registers, nothing else
            assertEquals("00 00 00 00 53 00 00 00 00 00", tx.last()) // SAVE
            assertEquals(found, dac.regs.getValue(KtMicroCodec.EQ_ENABLE_REGISTER)[0].toInt(), "EQ found $found")
            val plan = session.lastPlan!!
            assertEquals(found, plan.expectedEqControl)
            // the DAC comes back with what it saved: the bands as sent and the switch where it was
            val back = FakeKt(allegroSeed()).also { it.regs.putAll(dac.regs) }
            val state = FamilyState.Allegro(allegro, KtMicroSession(allegro, back).read())
            assertEquals(emptyList(), KtMicroCodec.mismatches(plan, state.snapshot), "EQ found $found")
            assertEquals(found == 3, state.eqEnabled)
            assertTrue(state.holds(eq))
            assertEquals(found == 3, state.matches(eq)) // ON DAC only while the switch is on
        }
    }

    @Test fun `FROM DAC on an Allegro Mini keeps its 0 dB bands - five bands in, the same five registers out`() = review {
        val port = LogPort(resource("allegro-mini-read-232520.log"), 0x4B, null, "k")
        val state = FamilyState.Allegro(allegro, KtMicroSession(allegro, port).read())
        val eq = state.importExact()
        assertEquals(5, eq.bands.size)                                                   // before: the unit's neutral 0 dB bands came in as one
        assertTrue(eq.bands.all { it.gainDb == 0.0 && it.q == 0.707 })
        val plan = KtMicroCodec.compile(Profile("id", "t", bands = eq.bands, preampDb = 0.0, createdAt = 0, updatedAt = 0), state.snapshot, null)
        assertEquals(state.snapshot.bands, plan.expectedBands)                           // frequency, Q, gain and type of every band as read
        assertTrue(plan.frames.none { it.payload[0].toInt() == KtMicroCodec.EQ_ENABLE_REGISTER })
    }

    /** The DSP designs its biquads for 24 000 Hz and runs them at the stream rate (runs 2026-10-25-kiwi-ears-allegro-mini-07 and -08): Contour assumes 48 kHz and writes register = intended x 24000 / 48000. */
    @Test fun `the Allegro frequency register is the intended frequency x 24000 over 48000, read back doubled, and the intended range starts at 40 Hz`() {
        val port = LogPort(resource("allegro-mini-read-232520.log"), 0x4B, null, "k")
        val before = KtMicroSession(allegro, port).read()
        fun eq(f: Double) = Profile("id", "t", bands = listOf(Band("a", FilterType.PEAK, f, 3.0, 1.0)), preampDb = 0.0, createdAt = 0, updatedAt = 0)
        fun register(f: Double) = KtMicroCodec.compile(eq(f), before).expectedBands[0].storedFrequencyHz
        assertEquals(500, register(1000.0))
        assertEquals(21, register(41.0))          // 20.5 rounds up (JS Math.round)
        assertEquals(20, register(40.0))
        assertEquals(10000, register(20000.0))
        assertEquals(1000.0, allegro.intendedFrequency(500))
        assertEquals(42.0, allegro.intendedFrequency(21))
        assertEquals(40.0, DeviceTarget.ALLEGRO_MINI.caps.freqMinHz); assertEquals(20000.0, DeviceTarget.ALLEGRO_MINI.caps.freqMaxHz)
        for (f in listOf(39.0, 39.9, 20.0, 20000.5, 25000.0)) assertFailsWith<IllegalArgumentException>(" Hz") { KtMicroCodec.compile(eq(f), before) }
        assertTrue(KtMicroCodec.issues(allegro, eq(39.0)).single().contains("outside 40.0-20000.0 Hz"))
        assertEquals(emptyList(), KtMicroCodec.issues(allegro, eq(40.0)))
        // register -> intended -> register is exact for every register the vendor range holds (20..20000 intended / 2)
        for (r in 20..10000) assertEquals(r, Math.floor(allegro.registerFrequency(allegro.intendedFrequency(r)) + 0.5).toInt(), "register ")
        assertEquals(24000.0, allegro.designRateHz); assertEquals(48000.0, allegro.assumedStreamHz)
        // the registers a read holds come back in the intended domain (FROM DAC), and compile writes them back unchanged
        val state = FamilyState.Allegro(allegro, before)
        val opened = state.importExact()
        opened.bands.forEachIndexed { i, b -> assertEquals(before.bands[i].storedFrequencyHz * 2.0, b.freqHz, "band ${i + 1}") }
    }

    @Test fun `the Allegro codec never builds the flash clear or an unknown write`() {
        val port = LogPort(resource("allegro-mini-read-232520.log"), 0x4B, null, "k")
        val before = KtMicroSession(allegro, port).read()
        val eq = Profile("id", "t", bands = listOf(Band("a", FilterType.PEAK, 1000.0, 3.0, 1.0)), preampDb = 0.0, createdAt = 0, updatedAt = 0)
        val frames = KtMicroCodec.compile(eq, before).frames
        assertTrue(frames.all { it.payload[4].toInt() == 0x57 || it.payload[4].toInt() == 0x53 })
        assertTrue(frames.none { it.payload[4].toInt() == 0x43 }, "0x43 is the vendor's clear flash")
        assertTrue(frames.dropLast(1).all { it.payload[0].toInt() in 0x24..0x2F })
    }

    // ---- native Moondrop: Dawn Pro 2 -----------------------------------------------------------------------------------------

    private val dawn = requireNotNull(MoondropCatalog.find(0x35D8, 0x011D, "DAWN PRO2"))
    private fun dawnShape() = NativeHidReports.parse(hexBytes(resource("dawn-pro-2-descriptor.hex")))

    @Test fun `the real Dawn Pro 2 read log replays frame for frame, bank 9 is PEQ mode`() {
        val port = LogPort(resource("dawn-pro-2-read-103434.log"), 0x4B, dawnShape(), "d")
        val s = MoondropSession(dawn, port).read()
        assertEquals(port.frames.size, port.next)
        assertEquals(11, port.frames.size)
        assertEquals(9, s.activeIndex)
        assertTrue(s.peqMode) // the unit was on bank 9; Contour's banks are 7 (the vendor catalog's PEQ index) and 9 (the owner's unit takes the Hub's write there)
        assertEquals(8, s.bands.size)
        assertEquals(0.0, s.globalGain.db) // 0x03 is the global gain, not a preamp
        assertNull(s.preGain)              // 0x23 was not read
        assertTrue(s.bands.all { it.bank == 9 })
        val b0 = s.bands[0]
        assertEquals(20, b0.frequencyHz); assertEquals(FilterType.PEAK, b0.type); assertEquals(181, b0.qQ8); assertEquals(0, b0.gainQ8)
        val b4 = s.bands[4]
        assertEquals(1400, b4.frequencyHz); assertEquals(205, b4.qQ8); assertEquals(-768, b4.gainQ8); assertEquals(-3.0, b4.gainDb)
        assertEquals(63, port.payloadSize(0x4B, NativeHidReports.Kind.INPUT)) // the descriptor: 64 bytes with the ID, both directions
        assertEquals(63, port.payloadSize(0x4B, NativeHidReports.Kind.OUTPUT))
    }

    @Test fun `the Dawn Pro 2 read needs the descriptor and refuses a short one`() {
        val port = LogPort(resource("dawn-pro-2-read-103434.log"), 0x4B, null, "d")
        assertFailsWith<IllegalArgumentException> { MoondropSession(dawn, port).read() }
        assertEquals(0, port.next) // nothing was sent
        assertNull(MoondropCatalog.find(0x35D8, 0x011D, "DAWN PRO 2")) // another, WalkPlay-routed name
    }

    /** SYNTHETIC Dawn Pro 2: banks of 8 band replies, the active EQ index, the global gain; keeps what it is sent. By default a band lands in the bank its tag names and
     * the index switch takes (the model the first tests were written against); [storesIntoActive] is the owner's unit (firmware 1.5, 24.10.2026): the band frames land in the
     * bank the unit is ON, whatever their tag says, and with [failSelect] it ignores the switch. [ignoreBands] is a unit that stores nothing. */
    private class FakeDawn(seed: LogPort, var index: Int) : NativePort {
        override val deviceKey = "d"
        val events = ArrayList<String>()
        val sent = ArrayList<ByteArray>()
        val banks = HashMap<Int, Array<ByteArray>>()
        var preGainQ8 = 0
        var failSelect = false
        var storesIntoActive = false
        var ignoreBands = false
        var globalQ8 = 0
        init {
            val bands = Array(8) { i -> seed.frames[1 + i].rx.single().copyOfRange(1, 64) }
            banks[9] = bands
            banks[7] = Array(8) { i -> bands[i].copyOf().also { it[35] = 7 } }
            banks[3] = Array(8) { i -> bands[i].copyOf().also { it[35] = 3 } } // one of the unit's own presets
        }
        private val shape = NativeHidReports.parse(hexBytes(resource("dawn-pro-2-descriptor.hex")))
        override fun guard() {}
        override fun pause(ms: Long) { events += "pause:$ms" }
        override fun payloadSize(id: Int, kind: NativeHidReports.Kind) = shape.payloadSize(id, kind)
        override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) {
            sent += payload; events += "tx"
            val p = payload.copyOf(63)
            when {
                p[0].toInt() == 1 && p[1].toInt() == 0x09 -> if (!ignoreBands) {
                    val bank = if (storesIntoActive) index else p[35].toInt() and 255
                    banks.getOrPut(bank) { Array(8) { ByteArray(63) } }[p[4].toInt()] = p.copyOf().also { it[0] = 0x80.toByte(); it[2] = 0x18; it[35] = bank.toByte() }
                }
                p[0].toInt() == 1 && p[1].toInt() == 0x03 -> globalQ8 = ((p[3].toInt() and 255) or (p[4].toInt() shl 8)).toShort().toInt()
                p[0].toInt() == 1 && p[1].toInt() == 0x0F -> if (!failSelect) index = p[3].toInt() and 255
                p[0].toInt() == 1 && p[1].toInt() == 0x23 -> preGainQ8 = ((p[3].toInt() and 255) or (p[4].toInt() shl 8)).toShort().toInt()
            }
        }
        override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
            sent += payload; events += "tx"
            val out = ByteArray(63)
            when (payload[1].toInt()) {
                0x0F -> { out[0] = 0x80.toByte(); out[1] = 0x0F; out[3] = index.toByte() }
                0x09 -> banks.getValue(index)[payload[4].toInt()].copyInto(out)
                0x03 -> { out[0] = 0x80.toByte(); out[1] = 0x03; out[2] = 2; out[3] = globalQ8.toByte(); out[4] = (globalQ8 shr 8).toByte() }
                0x23 -> { out[0] = 0x80.toByte(); out[1] = 0x23; out[2] = 2; out[3] = preGainQ8.toByte(); out[4] = (preGainQ8 shr 8).toByte() } // SYNTHETIC layout (the real reply is unproven)
                else -> error("unexpected request")
            }
            return out.also { assertTrue(matches(it)) }
        }
    }

    private fun tonePk(i: Int, f: Double, g: Double, q: Double) = MoondropCodec.bandWrite(i, Band("t$i", FilterType.PEAK, f, g, q))

    @Test fun `a Dawn Pro 2 write tags its bands with bank 7, never sends flash clear, firmware or the offset save, and a test can restore the bank and the found index`() {
        val seed = LogPort(resource("dawn-pro-2-read-103434.log"), 0x4B, dawnShape(), "d")
        val dac = FakeDawn(seed, index = 9)
        val session = MoondropSession(dawn, dac)
        val found = session.read()
        assertEquals(9, found.activeIndex)
        val backup = session.readWritableBank(explicitUserAction = true) // switches to 7, no save
        assertEquals(7, backup.activeIndex)
        assertTrue(backup.peqMode)
        dac.sent.clear()
        val bands = listOf(tonePk(0, 100.0, 4.0, 1.0), tonePk(1, 1000.0, -6.0, 2.0))
        val r = session.write(bands, preGainDb = -3.0, explicitHold = true)
        assertTrue(r.verified, r.reason)
        assertFalse(r.pending)
        val readback = r.readback!!
        assertEquals(7, readback.activeIndex)
        assertEquals(100, readback.bands[0].frequencyHz)
        assertEquals(-6 * 256, readback.bands[1].gainQ8)
        // the frames: no flash clear (01 05 01 04), firmware entry (01 ff), offset save (01 04 00)
        val first = dac.sent.map { it.copyOf(4).toList().map { b -> b.toInt() and 255 } }
        assertTrue(first.none { it[0] == 1 && (it[1] == 0x05 || it[1] == 0xFF || it[1] == 0x04) }, first.toString())
        val writes = dac.sent.filter { it[0].toInt() == 1 }
        // the Hub's order, even on bank 7 already: the index switch, 8 bands, commit, pre-gain -3 dB (0xfd00), the global gain as read, save
        assertEquals(1 + 8 + 4, writes.size)
        assertEquals("01 0f 01 07", hex(writes[0].copyOf(4)))
        writes.drop(1).take(8).forEachIndexed { i, w -> assertEquals(i, w[4].toInt()); assertEquals(7, w[35].toInt()) }
        assertEquals("01 0a 04 00 00 ff ff", hex(writes[9].copyOf(7)))
        assertEquals("01 23 02 00 fd", hex(writes[10].copyOf(5)))
        assertEquals("01 03 02 00 00", hex(writes[11].copyOf(5)))
        assertEquals("01 01 00", hex(writes[12].copyOf(3)))
        // restore: the backup bands back byte for byte, then the found index (9) and its save
        val restore = backup.bands.map { b ->
            MoondropCodec.BandWrite(b.index, b.frequencyHz, b.qQ8, b.gainQ8, b.nativeType, b.coefficientBytes.map { it.toByte() }.toByteArray())
        }
        val back = session.write(restore, preGainDb = null, explicitHold = true)
        assertTrue(back.verified, back.reason)
        assertEquals(9, session.selectIndex(9, explicitUserAction = true))
        assertEquals(9, dac.index)
        assertEquals("01 0f 01 09", hex(dac.sent.filter { it[0].toInt() == 1 && it[1].toInt() == 0x0F }.last().copyOf(4)))
        assertEquals(backup.bands.map { it.coefficientBytes }, dac.banks.getValue(7).map { b -> (7..26).map { b[it].toInt() and 255 } })
        // bank 9 was never touched
        assertEquals((0 until 8).map { seed.frames[1 + it].rx.single().copyOfRange(1, 64).toList() }, dac.banks.getValue(9).map { it.toList() })
    }

    /** The frames of MOONDROP Hub Beta 1.6.5 "Write Cfg" with a flat EQ on the owner's unit (capture 24.10.2026, runs/2026-10-24-moondrop-dawn-pro-2-04-mac, TX frames in order). */
    private val hubFlatWriteCfg = listOf(
        "01 0f 01 07",
        "01 09 21 00 00 00 00 00 00 00 40 eb 34 1e 80 43 d2 e1 3f 15 cb e1 7f bd 2d 1e c0 14 00 b6 00 00 00 02 00 07",
        "01 09 21 00 01 00 00 00 00 00 40 0c 9f 5a 80 75 a1 a5 3f f4 60 a5 7f 8b 5e 5a c0 3c 00 b6 00 00 00 02 00 07",
        "01 09 21 00 02 00 00 00 00 00 40 39 14 2e 81 e3 b3 d4 3e c7 eb d1 7e 1d 4c 2b c1 c8 00 b6 00 00 00 02 00 07",
        "01 09 21 00 03 00 00 00 00 00 40 cb 2b 8a 83 86 69 8e 3c 35 d4 75 7c 7a 96 71 c3 58 02 b6 00 00 00 02 00 07",
        "01 09 21 00 04 00 00 00 00 00 40 20 9c 6e 87 0d 6b 13 39 e0 63 91 78 f3 94 ec c6 78 05 cd 00 00 00 02 00 07",
        "01 09 21 00 05 00 00 00 00 00 40 01 a3 79 91 22 6a ce 35 ff 5c 86 6e de 95 31 ca 18 15 00 02 00 00 02 00 07",
        "01 09 21 00 06 00 00 00 00 00 40 40 b4 f9 be fd 87 d0 2a c0 4b 06 41 03 78 2f d5 b0 36 00 02 00 00 02 00 07",
        "01 09 21 00 07 00 00 00 00 00 40 4c 7c 48 ec 2a 19 2e 0c b4 83 b7 13 d6 e6 d1 f3 20 4e b6 00 00 00 02 00 07",
        "01 0a 04 00 00 ff ff",
        "01 23 02 00 00",
        "01 03 02 00 00",
        "01 01 00",
    )
    /** The Hub's flat bands as the unit stored them: the registers of its frames, with the Hub's own coefficient bytes (Q 182/256 and the defaults of bands 4 to 6). */
    private fun hubFlatBands() = hubFlatWriteCfg.drop(1).take(8).mapIndexed { i, h ->
        val b = hexBytes(h)
        fun u16(o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)
        MoondropCodec.BandWrite(i, u16(27), u16(29), u16(31).toShort().toInt(), b[33].toInt() and 255, b.copyOfRange(7, 27))
    }
    private fun trimZeros(h: String) = h.replace(Regex("( 00)+$"), "")

    @Test fun `a Dawn Pro 2 write sends the frames of the Hub's Write Cfg in its order and pacing, the unit may ignore the first and stay on index 9`() {
        val seed = LogPort(resource("dawn-pro-2-read-103434.log"), 0x4B, dawnShape(), "d")
        val dac = FakeDawn(seed, index = 9).also { it.failSelect = true; it.storesIntoActive = true }
        val r = MoondropSession(dawn, dac).write(hubFlatBands(), preGainDb = 0.0, explicitHold = true)
        assertTrue(r.verified, r.reason)
        // the capture's TX frames, byte for byte and in order (a band frame is 37 bytes, the others are shorter; trailing zeros do not count)
        val writes = dac.sent.filter { it[0].toInt() == 1 }
        assertEquals(hubFlatWriteCfg.map { trimZeros(it) }, writes.map { trimZeros(hex(it)) })
        // the frame allow-list holds for every one of them
        writes.forEach { MoondropCodec.requireSendable(it) }
        // the pacing: the Hub leaves 160 ms after the switch, about 35 ms between bands, 60 after the commit, 65 after each gain; the model's own delays are the floor
        assertTrue(dac.events.contains("pause:160") && dac.events.count { it == "pause:35" } == 8 && dac.events.contains("pause:60") && dac.events.count { it == "pause:65" } == 2 && dac.events.contains("pause:200"), dac.events.toString())
        // the unit stayed where it was, and the bands of its active bank took the write
        assertEquals(9, dac.index)
        assertEquals(9, r.readback!!.activeIndex)
        assertEquals(9, MoondropSession(dawn, dac).read().activeIndex)
        assertEquals(hubFlatBands().map { it.coefficients.toList() }, dac.banks.getValue(9).map { b -> (7..26).map { b[it] } })
        // the pre-gain read back, and no flash clear, firmware entry or offset save in all that
        assertTrue(writes.none { it[1].toInt() == 0x05 || it[1].toInt() == 0xFF || it[1].toInt() == 0x04 })
    }

    @Test fun `a Dawn Pro 2 write is decided by reading the bands back, whatever index the unit reports, and a unit that stores nothing is not verified and gets nothing more`() {
        val seed = LogPort(resource("dawn-pro-2-read-103434.log"), 0x4B, dawnShape(), "d")
        val bands = listOf(tonePk(0, 100.0, 4.0, 1.0))
        // a unit that follows the switch and stores into bank 7 -> verified at index 7; one that stays on 9 and stores into 9 -> verified at index 9
        val follows = FakeDawn(seed, index = 9)
        assertTrue(MoondropSession(dawn, follows).write(bands, null, explicitHold = true).also { assertEquals(7, it.readback!!.activeIndex) }.verified)
        val sticky = FakeDawn(seed, index = 9).also { it.failSelect = true; it.storesIntoActive = true }
        val ok = MoondropSession(dawn, sticky).write(bands, null, explicitHold = true)
        assertTrue(ok.verified, ok.reason)
        assertEquals(9, ok.readback!!.activeIndex)
        // a unit that ignores the switch and puts the tagged bands in bank 7 while it plays 9: the read shows bank 9 unchanged -> not verified
        val wrongBank = FakeDawn(seed, index = 9).also { it.failSelect = true }
        val bad = MoondropSession(dawn, wrongBank).write(bands, null, explicitHold = true)
        assertFalse(bad.verified)
        assertTrue(bad.reason!!.contains("band 0"), bad.reason)
        assertTrue(bad.reason!!.contains("EQ index 9 -> 9"), bad.reason)
        // a unit that stores nothing: not verified, and after the one Hub sequence only reads follow (nothing more is sent)
        val frozen = FakeDawn(seed, index = 9).also { it.failSelect = true; it.storesIntoActive = true; it.ignoreBands = true }
        val session = MoondropSession(dawn, frozen)
        val r = session.write(bands, null, explicitHold = true)
        assertFalse(r.verified); assertFalse(r.pending)
        val lastWrite = frozen.sent.indexOfLast { it[0].toInt() == 1 }
        assertEquals("01 01 00", hex(frozen.sent[lastWrite].copyOf(3)))
        assertTrue(frozen.sent.drop(lastWrite + 1).all { it[0].toInt() and 255 == 0x80 }, "only reads after the save")
        assertEquals(1 + 8 + 3, frozen.sent.count { it[0].toInt() == 1 }) // switch, 8 bands, commit, global gain, save (no pre-gain asked)
        // no HOLD, no frame
        val quiet = FakeDawn(seed, index = 7)
        assertFailsWith<IllegalArgumentException> { MoondropSession(dawn, quiet).write(bands, null, explicitHold = false) }
        assertTrue(quiet.sent.isEmpty())
        assertFailsWith<IllegalArgumentException> { MoondropSession(dawn, quiet).selectIndex(7, explicitUserAction = false) }
    }

    @Test fun `a Dawn Pro 2 write puts the global gain it read just before back unchanged, 00 00 for a unit at 0 dB`() {
        val seed = LogPort(resource("dawn-pro-2-read-103434.log"), 0x4B, dawnShape(), "d")
        val dac = FakeDawn(seed, index = 7).also { it.globalQ8 = -6 * 256 + 128 } // -5.5 dB
        val r = MoondropSession(dawn, dac).write(listOf(tonePk(0, 100.0, 4.0, 1.0)), preGainDb = -3.0, explicitHold = true)
        assertTrue(r.verified, r.reason)
        val gain = dac.sent.filter { it[0].toInt() == 1 && it[1].toInt() == 0x03 }
        assertEquals(listOf("01 03 02 80 fa"), gain.map { hex(it.copyOf(5)) })
        assertEquals(-6 * 256 + 128, dac.globalQ8) // unchanged by the write
        assertEquals(-6 * 256 + 128, r.readback!!.globalGain.rawQ8)
        // the gain frame follows the pre-gain frame and precedes the save
        val order = dac.sent.filter { it[0].toInt() == 1 }.map { it[1].toInt() }
        assertEquals(listOf(0x0F) + List(8) { 0x09 } + listOf(0x0A, 0x23, 0x03, 0x01), order)
    }

    @Test fun `Dawn band frames are the WalkPlay band report with length byte 0x21, in bank 7`() {
        val cases = listOf(
            Band("pk", FilterType.PEAK, 250.0, 10.0, 1.0), Band("ls", FilterType.LOW_SHELF, 105.0, 6.0, 0.707),
            Band("hs", FilterType.HIGH_SHELF, 8000.0, 4.0, 0.707), Band("pk2", FilterType.PEAK, 1000.0, -10.0, 10.0))
        cases.forEachIndexed { i, b ->
            val mine = MoondropCodec.bandPayload(MoondropCodec.bandWrite(i, b), 7)
            val reference = WalkPlay.bandWriteReport(WalkPlay.bandWrite(i, b), 7, 0x21).copyOfRange(1, WalkPlay.REPORT_SIZE)
            assertEquals(hex(reference), hex(mine.copyOf(63)), "case $i (${b.type})")
        }
        // the writable bank is the model's, not a parameter of the session
        assertEquals(7, dawn.writableIndex)
        assertEquals("01 0f 01 07", hex(MoondropCodec.selectPayload(7)))
        assertEquals("01 01 00", hex(MoondropCodec.savePayload()))
        assertEquals("01 0a 04 00 00 ff ff", hex(MoondropCodec.commitPayload()))
        assertEquals("01 23 02 00 fd", hex(MoondropCodec.preGainPayload(dawn, -3.0)))
        assertFailsWith<IllegalArgumentException> { MoondropCodec.preGainPayload(dawn, 1.0) }  // pre-gain is -128..0 dB
        assertFailsWith<IllegalArgumentException> { MoondropCodec.preGainPayload(dawn, -129.0) }
        assertFailsWith<IllegalArgumentException> { MoondropCodec.bandWrite(0, Band("bp", FilterType.PEAK, 1000.0, 3.0, 1.0)).let { MoondropCodec.writeSteps(dawn, listOf(it), null) } } // all 8 bands
    }

    // ---- Contour 1.6 app layer: recognition, settings rows, the state the app shows and the ON DAC rule ----------------------------------------

    @Test fun `the app finds the three 1_6 families by VID PID and name, and the 1_5 list of find stays as it was`() {
        assertSame(DeviceTarget.DAWN_PRO_2, DeviceTarget.findDevice(0x35D8, 0x011D, "DAWN PRO2"))
        assertNull(DeviceTarget.findDevice(0x35D8, 0x011D, "DAWN PRO 2"))   // another, WalkPlay-routed name
        assertNull(DeviceTarget.findDevice(0x35D8, 0x011D, null))           // no name, no model
        assertSame(DeviceTarget.ALLEGRO_MINI, DeviceTarget.findDevice(0x31B2, 0x0111, "Kiwi Ears-Allegro Mini"))
        assertSame(DeviceTarget.ALLEGRO_MINI, DeviceTarget.findDevice(0x31B2, 0x0111, "Kiwi Ears Allegro Mini"))
        assertNull(DeviceTarget.findDevice(0x31B2, 0x0111, "Kiwi Ears-Allegro PRO"))
        assertNull(DeviceTarget.findDevice(0x31B2, 0x0112, "Kiwi Ears-Allegro Mini"))
        assertSame(DeviceTarget.ECHO_A, DeviceTarget.findDevice(0x3302, 0x12C5, "ECHO-A"))
        assertSame(DeviceTarget.ECHO_A, DeviceTarget.findDevice(0x3302, 0x12C5, null))   // by VID:PID
        // 1.5 is untouched: find knows neither family, and the other 1.5 identities still resolve
        assertNull(DeviceTarget.find(0x35D8, 0x011D)); assertNull(DeviceTarget.find(0x31B2, 0x0111)); assertNull(DeviceTarget.find(0x3302, 0x12C5))
        assertSame(DeviceTarget.MICRO, DeviceTarget.findDevice(0x3302, 0xC20F, "Protocol Micro"))
        assertEquals(setOf(DeviceTarget.Family.MOONDROP, DeviceTarget.Family.KT_MICRO, DeviceTarget.Family.WALKPLAY_READ_ONLY), DeviceTarget.FAMILIES.map { it.family }.toSet())
        assertTrue(DeviceTarget.FAMILIES.all { it.isFamily && !it.supportsAb })
        assertTrue(listOf(DeviceTarget.MICRO, DeviceTarget.KA15).none { it.isFamily })
    }

    @Test fun `Tune limits of the families come from their catalogs, and the Dawn offers PEAK, LS, HS, LP and HP behind its flags`() = review {
        val d = DeviceTarget.DAWN_PRO_2.caps
        assertEquals(dawn.bandCount, d.bands); assertEquals(dawn.minGainDb, d.gainMinDb); assertEquals(dawn.maxGainDb, d.gainMaxDb)
        assertEquals(dawn.minQ, d.qMin); assertEquals(dawn.maxQ, d.qMax)
        val five = setOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF, FilterType.LOW_PASS, FilterType.HIGH_PASS)
        assertEquals(five, d.types) // the pass filters and the shelves behind MoondropCatalog.PASS_FILTERS_VERIFIED and SHELVES_VERIFIED (both true since 25.10.2026)
        assertEquals(five, Proven.asChannel(BuildChannel.RELEASE) { DeviceTarget.DAWN_PRO_2.caps.types })
        assertEquals(kotlin.math.sqrt(2.0), d.shelfQScale); assertEquals(1.0, d.peakQScale)
        assertEquals(dawn.preGainMinDb, DeviceTarget.DAWN_PRO_2.preampMin); assertEquals(dawn.preGainMaxDb, DeviceTarget.DAWN_PRO_2.preampMax)
        assertEquals("PRE GAIN", DeviceTarget.DAWN_PRO_2.preampLabel)
        val a = DeviceTarget.ALLEGRO_MINI.caps
        assertEquals(5, a.bands); assertEquals(-12.0, a.gainMinDb); assertEquals(12.0, a.gainMaxDb); assertEquals(0.1, a.qMin); assertEquals(10.0, a.qMax)
        assertEquals(40.0, a.freqMinHz); assertEquals(20000.0, a.freqMaxHz); assertEquals(setOf(FilterType.PEAK), a.types) // the intended range: the register range 20-20000 Hz x 2, capped at 20000
        assertFalse(DeviceTarget.ALLEGRO_MINI.hasPreamp)
        val pk = Band("a", FilterType.PEAK, 1000.0, 3.0, 1.0)
        fun profile(vararg b: Band, preamp: Double? = 0.0) = Profile("id", "t", bands = b.toList(), preampDb = preamp, createdAt = 0, updatedAt = 0)
        assertEquals(emptyList(), DeviceTarget.DAWN_PRO_2.issues(profile(pk, preamp = -3.0)))
        assertEquals(emptyList(), DeviceTarget.DAWN_PRO_2.issues(profile(Band("a", FilterType.LOW_SHELF, 100.0, 3.0, 0.7))))
        assertEquals(emptyList(), DeviceTarget.DAWN_PRO_2.issues(profile(Band("a", FilterType.LOW_PASS, 100.0, 0.0, 0.7), Band("b", FilterType.HIGH_PASS, 10000.0, 0.0, 0.7), preamp = -3.0)))
        assertTrue(DeviceTarget.DAWN_PRO_2.issues(profile(Band("a", FilterType.BAND_PASS, 100.0, 0.0, 0.7))).isNotEmpty())
        assertTrue(DeviceTarget.DAWN_PRO_2.issues(profile(pk.copy(gainDb = 12.5))).isNotEmpty())
        assertTrue(DeviceTarget.DAWN_PRO_2.issues(profile(*Array(9) { pk })).isNotEmpty()) // 9 bands, no truncation
        assertTrue(DeviceTarget.DAWN_PRO_2.issues(profile(pk, preamp = 1.0)).isNotEmpty())  // pre-gain is -128..0
        assertEquals(emptyList(), DeviceTarget.ALLEGRO_MINI.issues(profile(pk)))
        assertTrue(DeviceTarget.ALLEGRO_MINI.issues(profile(pk.copy(gainDb = 12.5))).isNotEmpty())
        assertTrue(DeviceTarget.ALLEGRO_MINI.issues(profile(*Array(6) { pk })).isNotEmpty())
        assertTrue(DeviceTarget.ALLEGRO_MINI.issues(profile(Band("a", FilterType.HIGH_SHELF, 8000.0, 3.0, 0.7))).isNotEmpty())
    }

    @Test fun `the DAC Settings rows of the families - the Dawn reads three, the Allegro EQ switch and Global Gain are proven on the phone, no Dawn write is enabled in the app until a unit proves it`() {
        val dawnRows = DeviceTarget.DAWN_PRO_2.settings
        assertEquals(listOf(FamilySettings.EQ_INDEX, FamilySettings.GLOBAL_GAIN, FamilySettings.PRE_GAIN), dawnRows.map { it.id })
        assertTrue(dawnRows.all { !it.writeVerified && it.read != null })       // read-only display: writeVerified is the one flag the bench result flips
        assertNull(dawnRows.single { it.id == FamilySettings.EQ_INDEX }.writeCommand)
        assertNull(dawnRows.single { it.id == FamilySettings.GLOBAL_GAIN }.writeCommand) // the unit ignores 01 03 02 (RUN-DP3): read only in every build
        assertEquals(MoondropCodec.CMD_PRE_GAIN, dawnRows.single { it.id == FamilySettings.PRE_GAIN }.writeCommand)
        assertEquals("GLOBAL GAIN", dawnRows.single { it.id == FamilySettings.GLOBAL_GAIN }.label) // 0x03 under its own name, not Pre Gain
        assertEquals("PRE GAIN", dawnRows.single { it.id == FamilySettings.PRE_GAIN }.label)
        assertTrue(dawnRows.filter { it.id != FamilySettings.EQ_INDEX }.all { it.scale == 10 && it.unit == "dB" })
        val rows = DeviceTarget.ALLEGRO_MINI.settings
        assertEquals(listOf(FamilySettings.EQ_SWITCH, FamilySettings.GLOBAL_GAIN), rows.map { it.id })
        val sw = rows.single { it.id == FamilySettings.EQ_SWITCH }
        assertTrue(sw.writeVerified); assertEquals(0x24, sw.writeCommand) // PC bench and the phone (Pixel run 2026-10-24-kiwi-ears-allegro-mini-04-phone): on in every build
        val gg = rows.single { it.id == FamilySettings.GLOBAL_GAIN }
        assertTrue(gg.writeVerified); assertEquals(KtMicroCodec.GLOBAL_GAIN_REGISTER, gg.writeCommand) // PC runs/2026-10-21-kiwi-ears-allegro-mini-02 T14; phone run 2026-10-24-kiwi-ears-allegro-mini-04-phone
        assertEquals(1, gg.min); assertEquals(15, gg.max)
        assertTrue(DeviceTarget.ECHO_A.settings.isEmpty()) // no DAC Settings page for the Echo-A in a release (its mic row is unproven: a review build offers it)
    }

    @Test fun `a Dawn Pro 2 on a preset EQ index is shown, not opened, never ON DAC, nothing is written to it, and the notice says how to get to PEQ mode`() {
        val seed = LogPort(resource("dawn-pro-2-read-103434.log"), 0x4B, dawnShape(), "d")
        val dac = FakeDawn(seed, index = 3)
        val state = FamilyState.Dawn(dawn, MoondropSession(dawn, dac).read(withPreGain = false))
        assertFalse(state.onWritableBank); assertFalse(state.importable)
        assertEquals(3, state.slot)
        val notice = state.bankNotice!!
        assertTrue(notice.contains("EQ index 3") && notice.contains("index 7 or 9") && notice.contains("PEQ mode") && notice.contains("HOLD TO SEND"), notice)
        assertEquals(listOf(SettingReading(FamilySettings.EQ_INDEX, 3, null), SettingReading(FamilySettings.GLOBAL_GAIN, 0, null)), state.readings())
        val flat = Profile("id", "t", bands = listOf(Band("a", FilterType.PEAK, 1000.0, 0.0, 1.0)), preampDb = 0.0, createdAt = 0, updatedAt = 0)
        assertFalse(state.holds(flat)); assertFalse(state.matches(flat))
        // even the bands of that preset do not make it ON DAC
        val preset = Profile("id", "t", bands = listOf(Band("a", FilterType.PEAK, 100.0, 4.0, 1.0)), preampDb = 0.0, createdAt = 0, updatedAt = 0)
        assertFalse(state.holds(preset)); assertFalse(state.matches(preset))
        assertFailsWith<IllegalArgumentException> { state.importExact() }
        // no read, no send; with a read the profile's own limits decide
        assertEquals(listOf(FamilyDevices.READ_FIRST), FamilyDevices.stateIssues(DeviceTarget.DAWN_PRO_2, null))
        assertEquals(emptyList(), FamilyDevices.stateIssues(DeviceTarget.DAWN_PRO_2, state))
    }

    @Test fun `a Dawn Pro 2 write on a preset EQ index sends nothing and says to switch to PEQ mode, on 7 and 9 it goes out`() {
        val seed = LogPort(resource("dawn-pro-2-read-103434.log"), 0x4B, dawnShape(), "d")
        val bands = listOf(tonePk(0, 100.0, 4.0, 1.0))
        val preset = FakeDawn(seed, index = 3)
        val e = assertFailsWith<IllegalArgumentException> { MoondropSession(dawn, preset).write(bands, -3.0, explicitHold = true) }
        assertTrue(e.message!!.contains("EQ index 3") && e.message!!.contains("PEQ mode") && e.message!!.contains("nothing written"), e.message)
        assertTrue(preset.sent.all { it[0].toInt() and 255 == 0x80 }, "reads only") // not even the index switch
        assertEquals(3, preset.index)
        assertEquals(3, preset.banks.getValue(3)[0][35].toInt())
        for (index in listOf(7, 9)) {
            val dac = FakeDawn(seed, index = index).also { it.failSelect = true; it.storesIntoActive = true }
            val r = MoondropSession(dawn, dac).write(bands, -3.0, explicitHold = true)
            assertTrue(r.verified, "index $index: ${r.reason}")
            assertEquals(index, r.readback!!.activeIndex)
            assertTrue(dac.sent.any { it[0].toInt() == 1 && it[1].toInt() == 0x09 })
        }
    }

    @Test fun `a Dawn Pro 2 on EQ index 9 is Contour's bank - ON DAC when the bands match, FROM DAC opens it, and the bank tag of the read rows is not what is compared`() = review {
        val seed = LogPort(resource("dawn-pro-2-read-103434.log"), 0x4B, dawnShape(), "d")
        // the owner's unit: stays on 9, the Hub-style write (tagged 07) lands in bank 9
        val dac = FakeDawn(seed, index = 9).also { it.failSelect = true; it.storesIntoActive = true }
        val eq = Profile("id", "t", bands = listOf(Band("a", FilterType.PEAK, 100.0, 4.0, 1.0), Band("b", FilterType.PEAK, 1000.0, -6.0, 2.0)), preampDb = -3.0, createdAt = 0, updatedAt = 0)
        val planned = MoondropCodec.planFor(dawn, eq.bands, eq.effectivePreampDb())
        val session = MoondropSession(dawn, dac)
        val before = FamilyState.Dawn(dawn, session.read(withPreGain = true))
        assertTrue(before.onWritableBank && before.importable); assertNull(before.bankNotice)   // the factory bands of bank 9 are shown and can be opened
        assertFalse(before.matches(eq))
        before.importExact()
        val r = session.write(planned.bands, planned.preGainDb, explicitHold = true)
        assertTrue(r.verified, r.reason)
        assertEquals(9, dac.index)
        assertTrue(dac.banks.getValue(9).all { it[35].toInt() == 9 })                            // the frames were tagged 07, the rows read back carry the bank of the active index
        val state = FamilyState.Dawn(dawn, r.readback!!)
        assertTrue(state.onWritableBank && state.importable); assertNull(state.bankNotice)
        assertTrue(state.holds(eq)); assertTrue(state.matches(eq))
        assertFalse(state.matches(eq.copy(preampDb = -4.0))); assertTrue(state.holds(eq.copy(preampDb = -4.0)))
        assertFalse(state.matches(eq.copy(bands = eq.bands.take(1))))
        val opened = state.importExact()
        assertEquals(listOf(100.0, 1000.0), opened.bands.take(2).map { it.freqHz })
        assertEquals(listOf(4.0, -6.0), opened.bands.take(2).map { it.gainDb })
        assertEquals(-3.0, opened.preampDb)
        assertTrue(state.describe().contains("(Contour's bank)"))
    }

    @Test fun `FROM DAC on a Dawn Pro 2 keeps its 0 dB bands, so the send that follows writes the same freq, Q, gain and type for all 8 bands as were read`() = review {
        val seed = LogPort(resource("dawn-pro-2-read-103434.log"), 0x4B, dawnShape(), "d")
        val dac = FakeDawn(seed, index = 9).also { it.failSelect = true; it.storesIntoActive = true }
        // the unit as the phone run of 25.10.2026 found it: the last two bands are PK 0 dB with Q 182 (0.711), not the neutral 181 (0.707) a send writes for a missing band
        for (i in 6..7) { dac.banks.getValue(9)[i][29] = 182.toByte(); dac.banks.getValue(9)[i][30] = 0 }
        val session = MoondropSession(dawn, dac)
        val read = session.read(withPreGain = true)
        fun registers(s: MoondropCodec.Snapshot) = s.bands.sortedBy { it.index }.map { listOf(it.nativeType, it.frequencyHz, it.qQ8, it.gainQ8) }
        val state = FamilyState.Dawn(dawn, read)
        val eq = state.importExact()
        assertEquals(8, eq.bands.size)                                                   // before: the trailing 0 dB bands were dropped (7, or 4 after a Contour send)
        assertEquals(182 / 256.0, eq.bands[7].q)                                         // the Q as the DAC holds it, not rounded to 0.71
        val planned = MoondropCodec.planFor(dawn, eq.bands, eq.preampDb)
        assertEquals(emptyList(), planned.issues)
        val r = session.write(planned.bands, planned.preGainDb, explicitHold = true)
        assertTrue(r.verified, r.reason)
        assertEquals(registers(read), registers(r.readback!!))                           // all 8 bands: type, freq, Q, gain as they were read
        // a band that is exactly the filler of a missing band is still left out: a profile written by Contour comes back with the bands it had
        val few = MoondropCodec.planFor(dawn, listOf(Band("a", FilterType.PEAK, 100.0, 4.0, 1.0)), 0.0)
        session.write(few.bands, few.preGainDb, explicitHold = true)
        assertEquals(1, FamilyState.Dawn(dawn, session.read(withPreGain = true)).importExact().bands.size)
    }

    @Test fun `the app's Dawn write - from index 9 a unit that follows the Hub's switch ends on 7 and the found index is reported, ON DAC needs the bands and Pre Gain read back`() = review {
        val seed = LogPort(resource("dawn-pro-2-read-103434.log"), 0x4B, dawnShape(), "d")
        val dac = FakeDawn(seed, index = 9)
        val session = MoondropSession(dawn, dac)
        val eq = Profile("id", "t", bands = listOf(Band("a", FilterType.PEAK, 100.0, 4.0, 1.0), Band("b", FilterType.PEAK, 1000.0, -6.0, 2.0)),
            preampDb = -3.0, createdAt = 0, updatedAt = 0)
        assertEquals(emptyList(), DeviceTarget.DAWN_PRO_2.issues(eq))
        val planned = MoondropCodec.planFor(dawn, eq.bands, eq.effectivePreampDb())
        assertEquals(emptyList(), planned.issues)
        val r = session.write(planned.bands, planned.preGainDb, explicitHold = true)
        assertTrue(r.verified, r.reason)
        assertEquals(9, session.lastBefore!!.activeIndex)   // what DacClient reports as WriteResult.previousIndex: the app offers to go back to it
        assertEquals(7, dac.index)
        val state = FamilyState.Dawn(dawn, r.readback!!)
        assertTrue(state.onWritableBank && state.importable); assertNull(state.bankNotice)
        assertTrue(state.holds(eq)); assertTrue(state.matches(eq))
        assertFalse(state.matches(eq.copy(preampDb = -4.0)))   // the Pre Gain read back is not the profile's
        assertTrue(state.holds(eq.copy(preampDb = -4.0)))       // the editor still finds the profile for these bands
        assertFalse(state.matches(eq.copy(bands = eq.bands.take(1))))
        // the way back is one explicit action: 01 0f 01 09 and the save, confirmed by one read
        dac.sent.clear()
        assertEquals(9, session.selectIndex(9, explicitUserAction = true))
        assertEquals(listOf("01 0f 01 09", "01 01 00"), dac.sent.filter { it[0].toInt() == 1 }.map { hex(it.copyOf(if (it[1].toInt() == 0x0F) 4 else 3)) })
    }

    @Test fun `the app's Dawn write on a unit that ignores the switch stays on index 9, is verified by the bands, is ON DAC and offers no way back`() = review {
        val seed = LogPort(resource("dawn-pro-2-read-103434.log"), 0x4B, dawnShape(), "d")
        val sticky = FakeDawn(seed, index = 9).also { it.failSelect = true; it.storesIntoActive = true }
        val eq = Profile("id", "t", bands = listOf(Band("a", FilterType.PEAK, 100.0, 4.0, 1.0)), preampDb = -3.0, createdAt = 0, updatedAt = 0)
        val planned = MoondropCodec.planFor(dawn, eq.bands, eq.effectivePreampDb())
        val session = MoondropSession(dawn, sticky)
        val r = session.write(planned.bands, planned.preGainDb, explicitHold = true)
        assertTrue(r.verified, r.reason)
        assertEquals(9, sticky.index)
        assertEquals(9, session.lastBefore!!.activeIndex)
        assertEquals(9, r.readback!!.activeIndex) // DacClient reports previousIndex only when these two differ
        // the frames the Dawn is never sent: flash clear, firmware entry, offset save
        assertTrue(sticky.sent.none { it[0].toInt() == 1 && (it[1].toInt() == 0x05 || it[1].toInt() == 0xFF || it[1].toInt() == 0x04) })
        // index 9 is one of Contour's banks: the Tune page shows ON DAC for what was written
        val state = FamilyState.Dawn(dawn, r.readback!!)
        assertTrue(state.onWritableBank); assertTrue(state.holds(eq)); assertTrue(state.matches(eq))
    }

    // ---- Allegro Mini: reconnect, then verify ----------------------------------------------------------------------------------------------

    private fun allegroSeed() = LogPort(resource("allegro-mini-read-232520.log"), 0x4B, null, "k")

    @Test fun `an Allegro write is not verified when SAVE is sent, it is verified only by the read after the DAC is back`() = review {
        val dac = FakeKt(allegroSeed())
        val session = KtMicroSession(allegro, dac)
        val eq = Profile("id", "t", bands = listOf(Band("a", FilterType.PEAK, 1000.0, 3.0, 1.0), Band("b", FilterType.PEAK, 3000.0, -2.5, 2.0)),
            preampDb = 0.0, createdAt = 0, updatedAt = 0)
        assertEquals(emptyList(), DeviceTarget.ALLEGRO_MINI.issues(eq))
        val r = session.write(eq, explicitHold = true)
        assertTrue(r.pending); assertFalse(r.verified); assertNull(r.readback) // what the app shows: SAVING TO DAC, not ON DAC
        assertTrue(dac.gone)                                                  // the DAC is off the bus
        assertFails { KtMicroSession(allegro, dac).read() } // nothing can be read from the old connection
        val plan = session.lastPlan!!
        // the DAC comes back with what it saved: a new connection, a new session, one read
        val back = FakeKt(allegroSeed()).also { it.regs.putAll(dac.regs) }
        val state = FamilyState.Allegro(allegro, KtMicroSession(allegro, back).read())
        assertEquals(emptyList(), KtMicroCodec.mismatches(plan, state.snapshot))
        assertTrue(state.eqEnabled && state.holds(eq) && state.matches(eq)) // ON DAC only now
        // a DAC that came back without the write (SAVE did not take) is a mismatch that names the register, not a verified send
        val stale = FamilyState.Allegro(allegro, KtMicroSession(allegro, FakeKt(allegroSeed())).read())
        val mismatches = KtMicroCodec.mismatches(plan, stale.snapshot)
        assertTrue(mismatches.any { it.startsWith("register 0x26") }, mismatches.toString())
        assertTrue(mismatches.any { it.startsWith("EQ switch") }, mismatches.toString())
        assertFalse(stale.matches(eq))
    }

    @Test fun `the Allegro EQ switch row is the switch and SAVE only, with the same reconnect-then-verify, and clear flash is never built`() {
        val dac = FakeKt(allegroSeed())
        val session = KtMicroSession(allegro, dac)
        val before = session.read()
        assertFalse(before.eqEnabled)
        val r = session.writeSwitch(eqEnabled = true, explicitHold = true)
        assertTrue(r.pending && !r.verified)
        val tx = dac.events.filter { it.startsWith("tx:") }.map { it.removePrefix("tx:") }
        val writes = tx.filter { it.split(" ")[4] == "57" }
        assertEquals(listOf("24 00 00 00 57 00 03 00 00 00"), writes)       // register 0x24 = 3 and nothing else; no band register
        assertEquals("00 00 00 00 53 00 00 00 00 00", tx.last())            // SAVE
        assertTrue(tx.none { it.split(" ")[4] == "43" })                    // 0x43 is the vendor's clear flash
        val plan = session.lastPlan!!
        val back = FakeKt(allegroSeed()).also { it.regs.putAll(dac.regs) }
        val state = FamilyState.Allegro(allegro, KtMicroSession(allegro, back).read())
        assertTrue(state.eqEnabled)
        assertEquals(emptyList(), KtMicroCodec.mismatches(plan, state.snapshot))
        assertEquals(listOf(SettingReading(FamilySettings.EQ_SWITCH, 1, null)), state.readings()) // Global Gain has no reading without a valid 0x3B
        // a switch that is already there, or no HOLD, sends nothing
        val quiet = FakeKt(allegroSeed())
        assertFailsWith<IllegalArgumentException> { KtMicroSession(allegro, quiet).writeSwitch(eqEnabled = false, explicitHold = true) }
        assertFailsWith<IllegalArgumentException> { KtMicroSession(allegro, quiet).writeSwitch(eqEnabled = true, explicitHold = false) }
        assertTrue(quiet.events.none { it.startsWith("tx:") && (it.split(" ")[4] == "57" || it.split(" ")[4] == "53") })
    }

    @Test fun `the Allegro Global Gain register 0x3B is read, shown under its own name, and no EQ write or EQ switch frame ever touches it`() {
        val dac = FakeKt(allegroSeed())
        dac.regs[KtMicroCodec.GLOBAL_GAIN_REGISTER] = byteArrayOf(13, 0, 0, 0)
        val session = KtMicroSession(allegro, dac)
        val gg = session.readGlobalGain()!!
        assertEquals(listOf(13, 0, 0, 0), gg.value)
        val state = FamilyState.Allegro(allegro, session.read(), gg)
        assertEquals(13, state.globalGainStep)
        assertEquals(SettingReading(FamilySettings.GLOBAL_GAIN, 13, null), state.readings().single { it.id == FamilySettings.GLOBAL_GAIN })
        assertTrue(dac.events.none { it.startsWith("tx:") && it.split(" ")[4] == "57" }) // only reads so far
        // a write of every kind the app can make touches only the EQ registers 0x24..0x2F and SAVE
        val eq = Profile("id", "t", bands = listOf(Band("a", FilterType.PEAK, 1000.0, 3.0, 1.0)), preampDb = 0.0, createdAt = 0, updatedAt = 0)
        val frames = KtMicroCodec.compile(eq, state.snapshot).frames + KtMicroCodec.compileSwitch(state.snapshot, true).frames
        assertTrue(frames.none { it.payload[0].toInt() == KtMicroCodec.GLOBAL_GAIN_REGISTER && it.payload[4].toInt() == 0x57 })
        assertTrue(frames.none { it.payload[4].toInt() == 0x43 })
    }
}
