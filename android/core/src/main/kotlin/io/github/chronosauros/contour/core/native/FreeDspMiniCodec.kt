package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.Band as ProfileBand
import io.github.chronosauros.contour.core.FilterType
import io.github.chronosauros.contour.core.WalkPlay

/**
 * The Moondrop FreeDSP Mini (USB 35D8:98D4). Its READ half (the Query frames and the parsers; test 1): FreeDSP-Studio's Mini frames byte for byte and nothing else: version `80 0C 01 00`, one band query per band `80 09 03 00 i 00` (LEN 3, payload
 * `00 i 00`), pre-gain `80 23 00` (tools/app/src/dsp2.rs 483-499, 501-540, 625-640, MIT, upstream HEAD 1b42f63). Payloads EXCLUDE the report ID (0x4B), like every codec of this package.
 * The Dawn Pro 2's frames ([MoondropCodec]) are not used for the Mini: its band query has LEN 0x18, its reads include the EQ index (`80 0F 00`) and the global gain (`80 03`), which the Mini is never asked for.
 *
 * Reply layout (report ID excluded, dsp2.rs 47-58): the band index at 4, the coefficient block at 7, the frequency (u16 little-endian) at 27, Q (signed Q8.8) at 29, gain (signed Q8.8) at 31, the type code at 33
 * and the active EQ slot at 35 (`OFF_SLOT`). FreeDSP-Studio quotes every offset against the report without its ID, and so does [NativeHidReports.Shape.payload] (it strips byte 0), so the offsets are the indices
 * of the payload Contour's sessions get: the slot is `payload[35]`, the byte [MoondropCodec] calls the bank. The active slot comes from the band replies; nothing else is asked.
 *
 * Every parser here is lenient on purpose: it needs the fields it reads and nothing more (no exact length, no LEN byte, no value range, no type table), because a Mini's replies have not been seen by this app and a
 * band of a firmware voicing (slots 0-4) may hold any gain or type code. What a unit says is shown and logged as read, never rejected. The parsers build no frame; the only frames with OP 0x01 this object makes are the write half at its end (band, commit, save), and [isAllowed] is the list the Mini's port holds them to.
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

    // ---- the write half (tester build 1.8.3-freedsptest2): PEAK bands to slot 7 and nothing else ---------------------------------------------------------------------------------------------

    /** The one slot Contour writes on the Mini: the editable bank the firmware calls "Custom EQ". Any other slot is one of the cable's own voicings. */
    const val SLOT_CUSTOM = 7
    /** The only band type written: PEAK, native code 2 (record `peq.types`). */
    const val TYPE_PEAK = 2
    /** The pacing of FreeDSP-Studio's `write_bank` (dsp2.rs 35-69): 25 ms after a band, 50 ms after an op (the commit), 200 ms after a flash command (the save); the Dawn route's own longer delays win where it has them. */
    val DELAY_BAND_MS: Long = maxOf(25L, MoondropCodec.DELAY_BAND_MS)
    val DELAY_COMMIT_MS: Long = maxOf(50L, MoondropCodec.DELAY_OP_MS)
    val DELAY_SAVE_MS: Long = maxOf(200L, MoondropCodec.DELAY_FLASH_MS)

    /** The texts of the three refusals and the failure, as the owner worded them (28.11.2026): shown as written. */
    fun otherSlotText(slot: Int): String =
        "The FreeDSP Mini is on ${slotName(slot) ?: "an unnamed EQ"} (slot $slot). Switch it to Custom EQ in the Moondrop app first. Nothing was sent."
    /** The pre-gain is never written: a send whose profile asks for another pre-gain than the unit holds (both on the 0.1 dB grid) is refused. [unitDb] is the unit's, written as the DAC page writes it. */
    fun preGainRefusal(unitDb: Double): String = "%.1f".format(java.util.Locale.ROOT, tenths(unitDb) / 10.0).let { "This test build does not change the FreeDSP Mini's pre-gain ($it dB). Set PRE GAIN to $it dB and send again. Nothing was sent." }
    /** [db] in tenths of a dB, half away from zero, as the grid the DAC page shows it on. */
    fun tenths(db: Double): Int = WalkPlay.toInt32(WalkPlay.jsRound(db * 10))
    const val INCOMPLETE_READ_TEXT = "The FreeDSP Mini did not answer every band. Nothing was sent. Unplug and replug it, then try again."
    const val NOT_TAKEN_TEXT = "The FreeDSP Mini did not take the bands, so nothing was saved. Unplug and replug it to go back to its saved EQ."

    /**
     * One band as the maker's tool writes it for the Mini: `01 09 21 00 i 00 00` + the 20 coefficient bytes + frequency, Q and gain registers + type 02 ([MoondropCodec.bandPayload] with slot 7). The registers are rounded first
     * (the frequency to whole Hz, Q and gain as signed Q8.8) and the biquad is designed from THOSE values, at 96 kHz, Q30: the 20 bytes MOONDROP's app wrote into a tester's unit are reproduced to the last bit this way,
     * and not when the biquad is designed from the unrounded gain (a tester's -2.69921875 dB band, raw -691, differs by up to 1776 LSB from -2.7). The Dawn Pro 2's [MoondropCodec.bandWrite] designs from the values as set and is not used here.
     */
    fun bandWrite(index: Int, band: ProfileBand): MoondropCodec.BandWrite {
        require(band.type == FilterType.PEAK) { "${band.type} is not written to the FreeDSP Mini" }
        require(band.freqHz.isFinite() && band.gainDb.isFinite() && band.q.isFinite()) { "not a number" }
        return registerBand(index, WalkPlay.toInt32(WalkPlay.jsRound(band.freqHz)), WalkPlay.toInt32(WalkPlay.jsRound(band.q * 256)), WalkPlay.toInt32(WalkPlay.jsRound(band.gainDb * 256)))
    }

    /** A PEAK band from its registers: [freqHz] whole Hz, [qQ8] and [gainQ8] signed Q8.8; the biquad is designed from them. Throws when they do not make a band the unit takes (Q30). */
    fun registerBand(index: Int, freqHz: Int, qQ8: Int, gainQ8: Int): MoondropCodec.BandWrite =
        MoondropCodec.BandWrite(index, freqHz, qQ8, gainQ8, TYPE_PEAK, WalkPlay.computeIir(freqHz.toDouble(), gainQ8 / 256.0, qQ8 / 256.0, TYPE_PEAK))

    /** Why the enabled bands of [bands] cannot be written to the Mini (empty = they can): at most its eight, PEAK only, 20-20000 Hz, -12..+12 dB, Q 0.1-10, and the biquad fits signed Q30. A disabled band is not checked here. */
    fun issues(model: MoondropCatalog.Model, bands: List<ProfileBand>): List<String> {
        val found = ArrayList<String>()
        if (bands.size > model.bandCount) found += "${bands.size} bands; ${model.productName} has ${model.bandCount}, no truncation"
        bands.forEachIndexed { i, b ->
            if (!b.enabled) return@forEachIndexed
            val n = i + 1
            if (b.type != FilterType.PEAK) { found += "Band $n: ${b.type} is not offered for ${model.productName}"; return@forEachIndexed }
            if (!(b.freqHz.isFinite() && b.freqHz in model.minFrequencyHz..model.maxFrequencyHz)) { found += "Band $n: freq ${b.freqHz} Hz outside ${model.minFrequencyHz}-${model.maxFrequencyHz} Hz"; return@forEachIndexed }
            if (!(b.gainDb.isFinite() && b.gainDb in model.minGainDb..model.maxGainDb)) { found += "Band $n: gain ${b.gainDb} dB outside ${model.minGainDb}..${model.maxGainDb} dB"; return@forEachIndexed }
            if (!(b.q.isFinite() && b.q in model.minQ..model.maxQ)) { found += "Band $n: Q ${b.q} outside ${model.minQ}-${model.maxQ}"; return@forEachIndexed }
            runCatching { bandWrite(i, b) }.onFailure { found += "Band $n: ${it.message ?: "cannot be encoded"}" }
        }
        return found
    }

    /**
     * The eight band writes of [bands] on top of what the unit holds ([before], the eight bands of the read before): an enabled band as [bandWrite] makes it, a disabled or missing one as a PEAK at 0 dB at the frequency and Q
     * that band index has on the unit, so the bands the user did not use keep their frequency and Q. Throws IllegalArgumentException (the first problem) when a band is not writable or a fill band cannot be built from what the unit reported.
     */
    fun plan(model: MoondropCatalog.Model, bands: List<ProfileBand>, before: List<Band?>): List<MoondropCodec.BandWrite> {
        issues(model, bands).firstOrNull()?.let { throw IllegalArgumentException(it) }
        require(before.size == model.bandCount) { "the read before holds ${before.size} bands, not ${model.bandCount}" }
        return List(model.bandCount) { i ->
            val b = bands.getOrNull(i)
            if (b != null && b.enabled) bandWrite(i, b)
            else {
                val held = requireNotNull(before[i]) { "Band ${i + 1} was not read" }
                require(held.frequencyHz.toDouble() in model.minFrequencyHz..model.maxFrequencyHz && held.qQ8 / 256.0 in model.minQ..model.maxQ) {
                    "Band ${i + 1} on the unit holds ${held.frequencyHz} Hz, Q ${held.q}: not a band Contour writes over"
                }
                registerBand(i, held.frequencyHz, held.qQ8, 0)
            }
        }
    }

    /** The Mini's band write of [w]: [MoondropCodec.bandPayload] tagged with slot 7. */
    fun bandPayload(w: MoondropCodec.BandWrite): ByteArray = MoondropCodec.bandPayload(w, SLOT_CUSTOM)

    /** The first and last payload byte compared after a write: the 20 coefficient bytes, frequency, Q, gain, type, the zero after it and the slot byte (report ID excluded, as in a tester's log). */
    const val COMPARE_FROM = 7
    const val COMPARE_TO = OFF_SLOT

    /** The differences between the band frame [sent] and the band reply [reply] over bytes [COMPARE_FROM]..[COMPARE_TO], as `offset sent->got` pairs; empty = the unit holds exactly what was sent. */
    fun differences(sent: ByteArray, reply: ByteArray): List<String> {
        if (reply.size <= COMPARE_TO) return listOf("reply of ${reply.size} bytes")
        return (COMPARE_FROM..COMPARE_TO).filter { sent[it] != reply[it] }.map { "byte $it %02x->%02x".format(sent[it].toInt() and 255, reply[it].toInt() and 255) }
    }

    /**
     * The allow-list of the Mini's port: true for exactly the three read frames, a band write (OP 01, CMD 09, LEN 0x21, 37 bytes, band index 0-7, type byte 02, slot byte 07), the commit `01 0A 04 00 00 FF FF` and the EQ save `01 01 00`;
     * nothing else (not `01 0F`, `01 23`, `01 03`, `01 04`, `01 05`, `01 FF`, not `80 0F` or `80 03`).
     */
    fun isAllowed(p: ByteArray): Boolean {
        fun u(i: Int) = p[i].toInt() and 255
        if (p.isEmpty()) return false
        return when (u(0)) {
            READ -> p.contentEquals(versionQuery().payload) || p.contentEquals(preGainQuery().payload) ||
                (p.size == QUERY_PAYLOAD_BYTES && u(1) == CMD_BAND && u(2) == 3 && u(3) == 0 && u(4) in 0..7 && u(5) == 0)
            0x01 -> p.contentEquals(MoondropCodec.commitPayload()) || p.contentEquals(MoondropCodec.savePayload()) ||
                (p.size == MoondropCodec.BAND_PAYLOAD_BYTES && u(1) == MoondropCodec.CMD_EQ_UPDATE && u(2) == 0x21 && u(3) == 0 && u(4) in 0..7 && u(5) == 0 && u(6) == 0 &&
                    u(33) == TYPE_PEAK && u(34) == 0 && u(35) == SLOT_CUSTOM && u(36) == 0)
            else -> false
        }
    }
}
