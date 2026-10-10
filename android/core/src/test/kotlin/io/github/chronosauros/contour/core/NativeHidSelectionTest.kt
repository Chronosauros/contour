package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.*
import io.github.chronosauros.contour.core.native.NativeHidSelection.Candidate
import io.github.chronosauros.contour.core.native.NativeHidSelection.Endpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FiiO HID interface selection (advBeta, K13 R2R "No unambiguous descriptor-proven family HID interface").
 * The descriptors below are SYNTHETIC shapes built from the KA15 codec's needs (report ID 7, 63-byte payload, vendor
 * page) and from what FiiO's web app and devicePEQ say about desktop DACs (several HID interfaces, a consumer-control one
 * next to the vendor one). The one real descriptor is the K13 R2R's (k13Real, from the owner's unit on 10.10.2026); more
 * arrive with testers' USB logs ("HID if N report descriptor ...").
 */
class NativeHidSelectionTest {
    private fun b(vararg v: Int) = v.map { it.toByte() }.toByteArray()

    /** Vendor-page application collection with report [id]: Input [inBytes] and Output [outBytes] payload bytes. */
    private fun vendor(id: Int = 7, inBytes: Int = 63, outBytes: Int = 63, page: Int = 0xFF00): ByteArray =
        b(0x06, page and 255, page shr 8, 0x09, 0x01, 0xA1, 0x01, 0x85, id, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08) +
            (if (inBytes > 0) b(0x95, inBytes, 0x09, 0x01, 0x81, 0x02) else byteArrayOf()) +
            (if (outBytes > 0) b(0x95, outBytes, 0x09, 0x01, 0x91, 0x02) else byteArrayOf()) + b(0xC0)

    /** Consumer-control knob interface: report ID 1, eight 1-bit usages. */
    private val consumer = b(0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, 0x01, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x09, 0xE9, 0x81, 0x02, 0xC0)

    /** Report 7 Input in one top-level collection and Output in another. */
    private val splitCollections = vendor(inBytes = 63, outBytes = 0) + vendor(inBytes = 0, outBytes = 63)

    private fun inEp(address: Int = 0x84, mps: Int = 64) = Endpoint(address, true, mps)
    private fun outEp(address: Int = 0x04, mps: Int = 64) = Endpoint(address, false, mps)
    private fun cand(id: Int, d: ByteArray?, vararg ep: Endpoint, failure: String? = null) = Candidate(id, d, failure, ep.toList())
    private fun select(vararg c: Candidate) = NativeHidSelection.selectFiio(7, 17, c.toList())

    @Test fun `KA15 shape - one vendor interface with 64 byte report and both interrupt endpoints is chosen strictly`() {
        val r = select(cand(3, vendor(), inEp(), outEp()))
        val c = assertNotNull(r.choice)
        assertEquals(3, c.interfaceId)
        assertFalse(c.relaxed)
        assertEquals(0x84, c.input.address)
        assertEquals(0x04, c.output?.address)
        assertEquals(64, c.shape.rawSize(7, NativeHidReports.Kind.OUTPUT))
        assertNull(r.failure)
    }

    @Test fun `K13 shape - consumer knob interface next to the vendor interface chooses the vendor one and explains the other`() {
        val r = select(cand(3, consumer, inEp(0x83, 8)), cand(4, vendor(), inEp(0x84), outEp(0x04)))
        assertEquals(4, assertNotNull(r.choice).interfaceId)
        val knob = r.verdicts.single { it.interfaceId == 3 }
        assertFalse(knob.accepted)
        assertTrue("no report 7" in knob.reason && "1:in2*" in knob.reason, knob.reason)
    }

    /** Real FiiO K13 R2R HID interface 3 (owner's unit, 10.10.2026): report 7 (32 B in/out) in a Generic Desktop collection
     * with Usage Undefined, then a consumer-control report 2. Endpoints IN 0x83 / OUT 0x02, 64 B each. */
    private val k13Real = b(0x05, 0x01, 0x09, 0x00, 0xa1, 0x01, 0x85, 0x07, 0x15, 0x00, 0x25, 0xff, 0x19, 0x01, 0x29, 0x08,
        0x95, 0x20, 0x75, 0x08, 0x81, 0x02, 0x19, 0x01, 0x29, 0x08, 0x91, 0x02, 0xc0, 0x05, 0x0c, 0x09, 0x01, 0xa1, 0x01, 0x85,
        0x02, 0x15, 0x00, 0x25, 0x01, 0x09, 0xcd, 0x09, 0xb5, 0x09, 0xb6, 0x09, 0xe9, 0x09, 0xea, 0x09, 0xe2, 0x75, 0x01, 0x95,
        0x06, 0x81, 0x02, 0x95, 0x02, 0x81, 0x01, 0xc0)

    @Test fun `real K13 R2R descriptor - report 7 on Generic Desktop Undefined is chosen with its interrupt OUT`() {
        val r = select(cand(3, k13Real, inEp(0x83, 64), outEp(0x02, 64)))
        val c = assertNotNull(r.choice, r.reasons)
        assertEquals(3, c.interfaceId)
        assertFalse(c.relaxed)
        assertEquals(0x02, c.output?.address)
        assertEquals(33, c.shape.rawSize(7, NativeHidReports.Kind.OUTPUT))
        assertEquals(33, c.shape.rawSize(7, NativeHidReports.Kind.INPUT))
        // the consumer report 2 stays out of the Shape, and other families keep the strict vendor-page rule
        assertNull(c.shape.rawSizes[NativeHidReports.Key(2, NativeHidReports.Kind.INPUT)])
        assertTrue(NativeHidReports.parse(k13Real).rawSizes.isEmpty())
    }

    @Test fun `two qualifying interfaces no longer end in ambiguity - report 7 both ways, then interrupt OUT, then lowest number`() {
        // old rule: two qualifying interfaces = "No unambiguous ..."; FiiO's own web app takes the first with report 7 both ways
        val r = select(cand(5, vendor(), inEp(0x85), outEp(0x05)), cand(2, vendor(), inEp(0x82), outEp(0x02)))
        val c = assertNotNull(r.choice)
        assertEquals(2, c.interfaceId)
        assertTrue("2 interfaces qualified" in c.note, c.note)
        assertEquals(listOf(2, 5), r.verdicts.map { it.interfaceId })
        assertEquals(listOf(true, false), r.verdicts.map { it.accepted })
        // an interface with an interrupt OUT beats one that would write through SET_REPORT
        val withOut = select(cand(2, vendor(), inEp(0x82)), cand(6, vendor(), inEp(0x86), outEp(0x06)))
        assertEquals(6, assertNotNull(withOut.choice).interfaceId)
        assertEquals(0x06, withOut.choice.output?.address)
    }

    @Test fun `a strictly qualifying interface beats a relaxed one even with a higher number`() {
        val r = select(cand(2, vendor(), inEp(0x82, 32), outEp(0x02)), cand(7, vendor(), inEp(0x87, 64)))
        val c = assertNotNull(r.choice)
        assertEquals(7, c.interfaceId)
        assertFalse(c.relaxed)
        assertNull(c.output) // no interrupt OUT: SET_REPORT
    }

    @Test fun `interrupt IN smaller than the report is accepted the WebHID way and says so`() {
        val r = select(cand(1, vendor(), inEp(0x81, 32), outEp(0x01, 32)))
        val c = assertNotNull(r.choice)
        assertTrue(c.relaxed)
        assertEquals(0x81, c.input.address)
        assertNull(c.output) // OUT smaller than the 64 B report: control SET_REPORT as before
        assertTrue("IN smaller than the report" in c.note, c.note)
    }

    @Test fun `two interrupt IN endpoints big enough - the first is used and the choice says so`() {
        val r = select(cand(1, vendor(), inEp(0x81), inEp(0x82), outEp()))
        val c = assertNotNull(r.choice)
        assertTrue(c.relaxed)
        assertEquals(0x81, c.input.address)
        assertTrue("2 interrupt IN" in c.note, c.note)
    }

    @Test fun `sizes are never relaxed - a descriptor smaller than the codec frame is rejected with the numbers`() {
        val small = select(cand(3, vendor(inBytes = 63, outBytes = 15), inEp(), outEp()))
        assertNull(small.choice)
        assertTrue("report 7 output 16 B < 17" in small.reasons, small.reasons)
        val smallIn = select(cand(3, vendor(inBytes = 7, outBytes = 63), inEp(), outEp()))
        assertTrue("report 7 input 8 B < 17" in smallIn.reasons, smallIn.reasons)
        // exactly the minimum (16 payload bytes + ID) still qualifies
        assertNotNull(select(cand(3, vendor(inBytes = 16, outBytes = 16), inEp(), outEp())).choice)
    }

    @Test fun `rejection reasons are short and per interface - what a tester screenshot must show`() {
        val r = select(
            cand(3, vendor(outBytes = 15), inEp(), outEp()),
            cand(4, consumer, inEp(0x84, 8)),
            cand(5, vendor(), failure = "claimInterface failed before descriptor read"),
            cand(6, vendor(page = 0x0001), inEp(0x86), outEp(0x06)),
            cand(7, vendor(inBytes = 0), inEp(0x87)),
            cand(8, splitCollections, inEp(0x88), outEp(0x08)),
            cand(9, vendor(), outEp(0x09)),
        )
        assertNull(r.choice)
        val reasons = r.verdicts.associate { it.interfaceId to it.reason }
        assertEquals("report 7 output 16 B < 17", reasons[3])
        assertTrue(reasons.getValue(4).startsWith("no report 7 (declares 1:in2*"), reasons[4])
        assertEquals("claimInterface failed before descriptor read", reasons[5])
        assertEquals("report 7 output not on a vendor usage page", reasons[6]) // Generic Desktop page, not vendor
        assertEquals("report 7 has no input", reasons[7])
        assertEquals("report 7 input and output in different collections", reasons[8])
        assertEquals("no interrupt IN endpoint", reasons[9])
        val line = assertNotNull(r.failure)
        assertTrue(line.startsWith("No unambiguous descriptor-proven family HID interface (if 3: report 7 output 16 B < 17; if 4: no report 7"), line)
        assertTrue("; if 9: no interrupt IN endpoint)" in line, line)
    }

    @Test fun `no HID interface and an unparsable descriptor are explained too`() {
        assertEquals("No unambiguous descriptor-proven family HID interface (no HID interface)", select().failure)
        val bad = select(cand(2, b(0x06, 0x00, 0xFF, 0xA1, 0x01), inEp(), outEp())) // collection never closed
        assertTrue("descriptor: Unclosed descriptor state" in bad.reasons, bad.reasons)
        val missing = select(cand(2, null, inEp()))
        assertTrue("no report descriptor" in missing.reasons)
    }

    @Test fun `descriptor inventory lists every report even off the vendor page and stays out of the Shape`() {
        val inv = NativeHidReports.inventory(consumer + vendor(id = 9))
        assertEquals(listOf(1, 9, 9), inv.map { it.id })
        assertEquals(listOf(false, true, true), inv.map { it.vendor })
        assertEquals(listOf(2, 64, 64), inv.map { it.bytes })
        assertEquals(setOf(9), NativeHidReports.parse(consumer + vendor(id = 9)).rawSizes.keys.map { it.id }.toSet())
    }
}
