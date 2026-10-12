package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * SNOWSKY Melody, tester's log of 11.10.2026 (advBeta 1.4.1-advbeta2): what HOLD TO SEND does when the DAC does not stay on
 * the USER slot it was told to select. The reads answer with the captured band, count and preamp replies; the slot behaviour is
 * the one the log shows (SYNTHETIC replay, not a firmware model). Both tests pin the safe outcome: nothing but the selection
 * frame is ever written.
 */
class NativeMelodyLogTest {
    private val target = requireNotNull(DeviceTarget.find(10610, 294, true, "SNOWSKY Melody"))
    private val profile = Profile("id", "test", bands = listOf(Band("b0", FilterType.PEAK, 1000.0, -1.0, 1.0)),
        preampDb = 0.0, createdAt = 0, updatedAt = 0)

    // log lines 8-32: reply data of cmd 0x16 (slot), 0x18 (count), 0x17 (preamp) and 0x15 band 0-9, as the DAC held them on preset 9
    private val count = listOf(0x0a)
    private val preamp = listOf(0xff, 0xe2)
    private val bands = listOf(
        "00 ff e6 00 28 00 46 01", "01 ff f3 00 9b 00 78 00", "02 00 04 01 d0 00 96 00", "03 00 0e 02 e1 00 6e 00",
        "04 ff f9 05 5f 00 b4 00", "05 ff e2 07 57 00 c8 00", "06 00 1e 0d bf 01 4a 00", "07 ff f4 0e bc 02 28 00",
        "08 00 28 16 65 00 50 00", "09 ff b5 27 10 00 28 02").map { row -> row.split(" ").map { it.toInt(16) } }

    private class MelodyPort(private val bands: List<List<Int>>, private val count: List<Int>, private val preamp: List<Int>) : NativePort {
        override val deviceKey = "melody"
        override fun payloadSize(id: Int, kind: NativeHidReports.Kind) = 63
        override fun guard() {}
        override fun pause(ms: Long) { events += "pause:$ms" }
        override fun feature(id: Int): ByteArray = error("no feature reports")
        val events = ArrayList<String>()
        /** Answers to 0x16 queries, in order, once the selection frame has been sent; the last one repeats. */
        var slotAnswersAfterSelect = listOf<Int>()
        var silentAfterSelect = false
        private var slot = 9
        private var selected = false
        private var slotQueriesAfterSelect = 0
        private fun reply(cmd: Int, data: List<Int>) =
            (listOf(0xBB, 0x0B, 0, 0, cmd, data.size) + data + listOf(0, 0xEE)).map { it.toByte() }.toByteArray().copyOf(63)
        override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) {
            val cmd = payload[4].toInt() and 255
            events += "write:$cmd"
            if (cmd == FiioCodec.SLOT) selected = true
        }
        override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
            if ((payload[0].toInt() and 255) == 0xAA) { send(id, NativeHidReports.Kind.OUTPUT, payload); return payload.copyOf(63) }
            val cmd = payload[4].toInt() and 255
            events += "read:$cmd"
            if (selected && silentAfterSelect) throw java.io.IOException("Native request timed out or returned invalid state")
            val data = when (cmd) {
                FiioCodec.SLOT -> if (!selected) listOf(slot) else listOf(slotAnswersAfterSelect.getOrElse(slotQueriesAfterSelect++) { slotAnswersAfterSelect.last() })
                FiioCodec.COUNT -> count
                FiioCodec.PREAMP -> preamp
                FiioCodec.BAND -> bands[payload[6].toInt() and 255]
                else -> error("Unexpected read $cmd")
            }
            return reply(cmd, data).also { require(matches(it)) }
        }
    }

    @Test
    fun `the DAC confirms USER1 and is back on preset 9 when the backup is read - nothing is written`() {
        // log 97-157: select 0xa0 -> verification read a0 -> backup opens on a0 -> closing slot query 09 -> re-read, all 09
        val port = MelodyPort(bands, count, preamp)
        port.slotAnswersAfterSelect = listOf(0xa0, 0xa0, 0x09)
        val e = assertFailsWith<IllegalArgumentException> { NativeSession(target, port).write(profile, explicitHold = true) }
        assertEquals("Backup is not the selected destination", e.message)
        assertEquals(listOf("write:22"), port.events.filter { it.startsWith("write") })
        // the drift is what made the backup a second read: one 500 ms pause between the two
        assertEquals(1, port.events.count { it == "pause:500" })
    }

    @Test
    fun `a DAC that goes silent after the selection ends the send with a timeout and nothing else is written`() {
        // log 225-234: select 0xa0 gets no echo, then eight slot queries get no reply
        val port = MelodyPort(bands, count, preamp)
        port.silentAfterSelect = true
        val e = assertFailsWith<java.io.IOException> { NativeSession(target, port).write(profile, explicitHold = true) }
        assertTrue(e.message!!.startsWith("Native request timed out"))
        assertEquals(listOf("write:22"), port.events.filter { it.startsWith("write") })
        assertEquals(8, port.events.count { it == "read:22" } - 2) // minus the opening and closing slot query of the session's first read
    }
}
