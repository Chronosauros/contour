package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.FilterType

/**
 * The read half of the Moondrop FreeDSP Mini (USB 35D8:98D4), FreeDSP-Studio's Mini frames byte for byte and nothing else: version `80 0C 01 00`, one band query per band `80 09 03 00 i 00` (LEN 3, payload
 * `00 i 00`), pre-gain `80 23 00` (tools/app/src/dsp2.rs 483-499, 501-540, 625-640, MIT, upstream HEAD 1b42f63). Payloads EXCLUDE the report ID (0x4B), like every codec of this package.
 * The Dawn Pro 2's frames ([MoondropCodec]) are not used for the Mini: its band query has LEN 0x18, its reads include the EQ index (`80 0F 00`) and the global gain (`80 03`), which the Mini is never asked for.
 *
 * Reply layout (report ID excluded, dsp2.rs 47-58): the band index at 4, the coefficient block at 7, the frequency (u16 little-endian) at 27, Q (signed Q8.8) at 29, gain (signed Q8.8) at 31, the type code at 33
 * and the active EQ slot at 35 (`OFF_SLOT`). FreeDSP-Studio quotes every offset against the report without its ID, and so does [NativeHidReports.Shape.payload] (it strips byte 0), so the offsets are the indices
 * of the payload Contour's sessions get: the slot is `payload[35]`, the byte [MoondropCodec] calls the bank. The active slot comes from the band replies; nothing else is asked.
 *
 * Every parser here is lenient on purpose: it needs the fields it reads and nothing more (no exact length, no LEN byte, no value range, no type table), because a Mini's replies have not been seen by this app and a
 * band of a firmware voicing (slots 0-4) may hold any gain or type code. What a unit says is shown and logged as read, never rejected. This object builds no frame with OP 0x01.
 */
object FreeDspMiniCodec {
    const val REPORT_ID = MoondropCatalog.REPORT_ID
    private const val READ = 0x80
    const val CMD_BAND = 0x09
    const val CMD_VERSION = 0x0C
    const val CMD_PRE_GAIN = 0x23

    /** Reply field offsets, report ID excluded (FreeDSP-Studio dsp2.rs 47-58). */
    const val OFF_INDEX = 4
    const val OFF_FREQ = 27
    const val OFF_Q = 29
    const val OFF_GAIN = 31
    const val OFF_TYPE = 33
    const val OFF_SLOT = 35

    /** The least a band reply must hold: everything up to and including the slot byte. */
    const val MINIMUM_BAND_PAYLOAD_BYTES = OFF_SLOT + 1
    /** The longest query (the band query); the report descriptor's Output report must hold at least this many payload bytes. */
    const val QUERY_PAYLOAD_BYTES = 6

    /** The firmware names the cable's five voicings and its editable bank (FreeDSP-Studio dsp2.rs 238-250). */
    val SLOT_NAMES: Map<Int, String> = mapOf(0 to "Default", 1 to "Bass Enhance", 2 to "Bass Enhance+", 3 to "Space", 4 to "Treble Enhance", 7 to "Custom EQ")
    fun slotName(slot: Int): String? = SLOT_NAMES[slot]

    enum class Kind { VERSION, BAND, PRE_GAIN }

    /** One read query: the payload to send (after the report ID) and a short label for the log. */
    class Query internal constructor(val kind: Kind, val index: Int?, val label: String, payload: ByteArray) {
        private val bytes = payload.copyOf()
        val payload: ByteArray get() = bytes.copyOf()
    }

    fun versionQuery(): Query = Query(Kind.VERSION, null, "version", byteArrayOf(READ.toByte(), CMD_VERSION.toByte(), 0x01, 0x00))
    fun bandQuery(index: Int): Query {
        require(index in 0..255) { "band index $index" }
        return Query(Kind.BAND, index, "band $index", byteArrayOf(READ.toByte(), CMD_BAND.toByte(), 0x03, 0x00, index.toByte(), 0x00))
    }
    fun preGainQuery(): Query = Query(Kind.PRE_GAIN, null, "pre-gain", byteArrayOf(READ.toByte(), CMD_PRE_GAIN.toByte(), 0x00))

    /** The whole read, in order: the version, every band of [model], the pre-gain. */
    fun readQueries(model: MoondropCatalog.Model): List<Query> =
        listOf(versionQuery()) + (0 until model.bandCount).map { bandQuery(it) } + listOf(preGainQuery())

    /** The firmware string as FreeDSP-Studio reads it (the printable ASCII from the first data byte); [raw] is the whole reply payload. */
    data class Firmware(val text: String, val raw: List<Int>)

    /** One band as the unit reports it. [type] is null for a code Contour has no name for (shown as `--`); [slot] is the active EQ slot the reply carries. */
    data class Band(val index: Int, val frequencyHz: Int, val qQ8: Int, val gainQ8: Int, val nativeType: Int, val slot: Int) {
        val q: Double get() = qQ8 / 256.0
        val gainDb: Double get() = gainQ8 / 256.0
        val type: FilterType? get() = MoondropCatalog.TYPES[nativeType]
    }

    /**
     * What one read of the Mini holds. Every field is what a reply gave and null (or null in [bands]) where none came or it did not parse; [slot] is the active EQ slot of the first band reply that came;
     * [unanswered] names each query that left a field unknown.
     */
    class Read(val firmware: Firmware?, val slot: Int?, val bands: List<Band?>, val preGain: MoondropCodec.Gain?, val unanswered: List<String>)

    private fun u(p: ByteArray, i: Int) = p[i].toInt() and 255
    private fun u16(p: ByteArray, i: Int) = u(p, i) or (u(p, i + 1) shl 8)
    private fun s16(p: ByteArray, i: Int) = u16(p, i).toShort().toInt()
    private fun isAnswer(p: ByteArray, cmd: Int, least: Int) = p.size >= least && u(p, 0) == READ && u(p, 1) == cmd

    fun parseVersion(p: ByteArray): Firmware {
        require(isAnswer(p, CMD_VERSION, 4)) { "not a version reply" }
        val text = buildString { for (i in 3 until minOf(p.size, 3 + 16)) { val c = u(p, i); if (c !in 0x21..0x7E) break; append(c.toChar()) } }
        require(text.isNotEmpty()) { "the version reply holds no text" }
        return Firmware(text, p.map { it.toInt() and 255 })
    }

    fun parseBand(p: ByteArray, index: Int): Band {
        require(isAnswer(p, CMD_BAND, MINIMUM_BAND_PAYLOAD_BYTES)) { "not a band reply" }
        require(u(p, OFF_INDEX) == index) { "the reply is for band ${u(p, OFF_INDEX)}, not $index" }
        return Band(index, u16(p, OFF_FREQ), s16(p, OFF_Q), s16(p, OFF_GAIN), u(p, OFF_TYPE), u(p, OFF_SLOT))
    }

    /** The pre-gain, signed Q8.8 at 3 (dsp2.rs 625-632). */
    fun parsePreGain(p: ByteArray): MoondropCodec.Gain {
        require(isAnswer(p, CMD_PRE_GAIN, 5)) { "not a pre-gain reply" }
        return MoondropCodec.Gain(s16(p, 3))
    }
}
