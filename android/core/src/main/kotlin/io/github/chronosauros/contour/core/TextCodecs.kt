package io.github.chronosauros.contour.core

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Bands plus preamp as read from a file. [preampDb] = null: the file had none (or asked for auto).
 * [notes] are things the reader changed on the way in (a channel chosen, empty filters skipped); the importer
 * puts them in front of its own fitting notes in the one-line report.
 */
data class ImportedEq(val bands: List<Band>, val preampDb: Double?, val notes: List<String> = emptyList())

/**
 * AutoEQ / Equalizer APO / squig.link / graph.hangout.audio parametric text:
 * ```
 * Preamp: -6.1 dB
 * Filter 1: ON PK Fc 105 Hz Gain -2.5 dB Q 0.70
 * ```
 * Types PK/PEQ, LS/LSC/LSQ, HS/HSC/HSQ, LP/LPQ, HP/HPQ; OFF = disabled. Tolerant to case, spacing,
 * missing filter numbers and a decimal comma. Lines it does not understand are skipped.
 */
object ApoText {
    private const val NUM = """[-+]?\d+(?:[.,]\d+)?"""
    private val PREAMP = Regex("""^\s*preamp\s*:\s*($NUM)\s*(?:db)?\s*$""", RegexOption.IGNORE_CASE)
    private val FILTER = Regex(
        """^\s*filter\s*\d*\s*:\s*(on|off)\s+([a-z]+)\s*(.*)$""",
        RegexOption.IGNORE_CASE,
    )
    private val FC = Regex("""\bfc\s*($NUM)\s*(?:hz)?""", RegexOption.IGNORE_CASE)
    private val GAIN = Regex("""\bgain\s*($NUM)\s*(?:db)?""", RegexOption.IGNORE_CASE)
    private val Q = Regex("""\bq\s*($NUM)""", RegexOption.IGNORE_CASE)
    private val CHANNEL = Regex("""^\s*channel\s*:\s*(.*)$""", RegexOption.IGNORE_CASE)

    /** Q used when a shelf or pass filter line has none (Butterworth / APO's 0.71 default). */
    const val DEFAULT_Q: Double = 0.707

    fun typeOf(code: String): FilterType? = when (code.uppercase()) {
        "PK", "PEQ" -> FilterType.PEAK
        "LS", "LSC", "LSQ" -> FilterType.LOW_SHELF
        "HS", "HSC", "HSQ" -> FilterType.HIGH_SHELF
        "LP", "LPQ" -> FilterType.LOW_PASS
        "HP", "HPQ" -> FilterType.HIGH_PASS
        else -> null
    }

    fun codeOf(type: FilterType): String = when (type) {
        FilterType.PEAK -> "PK"
        FilterType.LOW_SHELF -> "LSC"
        FilterType.HIGH_SHELF -> "HSC"
        FilterType.LOW_PASS -> "LPQ"
        FilterType.HIGH_PASS -> "HPQ"
    }

    private fun num(s: String): Double = s.replace(',', '.').toDouble()

    private enum class Side { BOTH, LEFT, RIGHT, OTHER }

    private data class Raw(val type: FilterType, val freqHz: Double, val gainDb: Double, val q: Double, val enabled: Boolean)

    /** The lines under one `Channel:` header ([spec] is what follows it); the lines before the first header are a [Side.BOTH] block. */
    private class Block(val side: Side, val spec: String = "") {
        var preamp: Double? = null
        val filters = ArrayList<Raw>()
    }

    private fun sideOf(spec: String): Side {
        val words = spec.uppercase().split(Regex("""[\s,]+""")).filter { it.isNotEmpty() }
        // Equalizer APO also numbers channels: 1 = left, 2 = right
        val left = words.any { it == "L" || it == "FL" || it == "LEFT" || it == "1" }
        val right = words.any { it == "R" || it == "FR" || it == "RIGHT" || it == "2" }
        return when {
            "ALL" in words || (left && right) -> Side.BOTH
            left -> Side.LEFT
            right -> Side.RIGHT
            else -> Side.OTHER
        }
    }

    /**
     * Reads the file as the user means it. A file with `Channel:` blocks (squig.link writes `Channel: L` and
     * `Channel: R`, each with its own Preamp) is read stereo-linked: a block for both channels (`Channel: all`,
     * `L R`, or no header at all) wins; otherwise the left block is taken and, when the right one differs, a note
     * says so. Filters are kept exactly as written - see [parseImport] for the importer's clean-up.
     */
    fun parse(text: String, newId: (Int) -> String = { "b${it + 1}" }): ImportedEq {
        val blocks = ArrayList<Block>()
        var block = Block(Side.BOTH).also { blocks += it }
        for (line in text.lineSequence()) {
            val ch = CHANNEL.matchEntire(line)
            if (ch != null) {
                block = Block(sideOf(ch.groupValues[1]), ch.groupValues[1]).also { blocks += it }
                continue
            }
            val p = PREAMP.matchEntire(line)
            if (p != null) {
                block.preamp = num(p.groupValues[1])
                continue
            }
            val m = FILTER.matchEntire(line) ?: continue
            val type = typeOf(m.groupValues[2]) ?: continue
            val rest = m.groupValues[3]
            val fc = FC.find(rest)?.groupValues?.get(1)?.let(::num) ?: continue
            val gain = GAIN.find(rest)?.groupValues?.get(1)?.let(::num) ?: 0.0
            val q = Q.find(rest)?.groupValues?.get(1)?.let(::num) ?: DEFAULT_Q
            block.filters += Raw(type, fc, gain, q, m.groupValues[1].equals("on", ignoreCase = true))
        }

        val notes = ArrayList<String>()
        val global = blocks.first()
        val both = blocks.filter { it.side == Side.BOTH }
        val left = blocks.filter { it.side == Side.LEFT && it.filters.isNotEmpty() }
        val right = blocks.filter { it.side == Side.RIGHT && it.filters.isNotEmpty() }
        val other = blocks.filter { it.side == Side.OTHER && it.filters.isNotEmpty() }
        val chosen: List<Block>
        if (both.any { it.filters.isNotEmpty() }) {
            chosen = both
            if (left.isNotEmpty() || right.isNotEmpty()) notes += "left/right filters skipped (the file also has filters for both channels)"
        } else if (left.isNotEmpty()) {
            chosen = left
            if (right.isNotEmpty() && !sameEq(left, right, global.preamp)) notes += "left channel imported (file has separate L/R)"
        } else if (right.isNotEmpty()) {
            chosen = right
        } else if (other.isNotEmpty()) {
            // only blocks for other channels (C, SL, LFE ...): better than nothing, taken as both
            chosen = other
            notes += "channel ${other.joinToString(", ") { it.spec.trim() }} imported (the file has no L or R block)"
        } else {
            chosen = listOf(global)
        }
        val preamp = chosen.lastOrNull { it.preamp != null }?.preamp ?: global.preamp
        val bands = chosen.flatMap { it.filters }.mapIndexed { i, r -> Band(newId(i), r.type, r.freqHz, r.gainDb, r.q, r.enabled) }
        return ImportedEq(bands, preamp, notes)
    }

    private fun preampOf(blocks: List<Block>, global: Double?): Double? = blocks.lastOrNull { it.preamp != null }?.preamp ?: global

    private fun sameEq(a: List<Block>, b: List<Block>, globalPreamp: Double?): Boolean =
        a.flatMap { it.filters } == b.flatMap { it.filters } && preampOf(a, globalPreamp) == preampOf(b, globalPreamp)

    /** A filter that does nothing: a peak or shelf at 0 dB (squig.link and hangout files often carry all 10 slots). */
    private fun doesNothing(b: Band) =
        (b.type == FilterType.PEAK || b.type == FilterType.LOW_SHELF || b.type == FilterType.HIGH_SHELF) && abs(b.gainDb) < 1e-9

    /**
     * [parse] for importing a file into a profile: filters at 0 dB are dropped and counted in the notes so they do
     * not eat device bands (disabled filters that have a gain stay, as disabled bands). A file made only of such
     * filters keeps its first one, so an exported flat profile imports as a flat profile.
     */
    fun parseImport(text: String, newId: (Int) -> String = { "b${it + 1}" }): ImportedEq {
        val eq = parse(text, newId)
        val kept = eq.bands.filterNot(::doesNothing).ifEmpty { eq.bands.take(1) }
        val skipped = eq.bands.size - kept.size
        if (skipped == 0) return eq
        val note = "$skipped empty filter${if (skipped == 1) "" else "s"} skipped"
        return ImportedEq(kept.mapIndexed { i, b -> b.copy(id = newId(i)) }, eq.preampDb, eq.notes + note)
    }

    /** Shortest exact decimal with at least [minDecimals] and at most [maxDecimals] places. */
    private fun fmt(x: Double, minDecimals: Int, maxDecimals: Int): String {
        var d = BigDecimal.valueOf(x).setScale(maxDecimals, RoundingMode.HALF_UP).stripTrailingZeros()
        if (d.scale() < minDecimals) d = d.setScale(minDecimals)
        return d.toPlainString()
    }

    /**
     * A gain or preamp as written to a file: a value with at most two decimals exactly as before, any other value with
     * the shortest decimal that parses back to the same Double - never a rounded string (rounding up could make it
     * louder, and could change what the planner decides about it).
     */
    private fun fmtGain(x: Double): String {
        val d = BigDecimal.valueOf(x).stripTrailingZeros()
        return if (d.scale() <= 2) fmt(x, 1, 2) else d.toPlainString()
    }

    /**
     * APO text. Lossless for values with up to 2 decimals (Fc, gain) and 3 (Q); gains and preamp are exact for any value.
     * The shape is squig.link's export:
     * its import regex `Filter\s*\d+:\s*(\S+)\s*(\S+)\s*Fc\s*(\S+)\s*Hz\s*Gain\s*(\S+)\s*dB(\s*Q\s*(\S+))?`
     * matches every PK / LSC / HSC line. Low- and high-pass lines have no Gain: Equalizer APO reads them,
     * squig.link skips them.
     */
    fun format(bands: List<Band>, preampDb: Double): String = buildString {
        append("Preamp: ").append(fmtGain(preampDb)).append(" dB\n")
        bands.forEachIndexed { i, b ->
            append("Filter ").append(i + 1).append(": ").append(if (b.enabled) "ON" else "OFF")
            append(' ').append(codeOf(b.type)).append(" Fc ").append(fmt(b.freqHz, 0, 2)).append(" Hz")
            if (b.type != FilterType.LOW_PASS && b.type != FilterType.HIGH_PASS) {
                append(" Gain ").append(fmtGain(b.gainDb)).append(" dB")
            }
            append(" Q ").append(fmt(b.q, 2, 3)).append('\n')
        }
    }

    /**
     * The text of a profile as the user means it (stored bands, stored or auto preamp) for SHARE and SAVE .TXT.
     * Never the device domain: the FiiO KA15 correction (shelf Q scaled, register preamp offset) is applied only
     * when sending, inside the codec, and is not part of the [Profile].
     */
    fun formatProfile(p: Profile): String = format(p.bands, p.effectivePreampDb())

    /** The text of a `.txt` file: CRLF like squig.link's export (every reader here also takes plain LF). */
    fun fileText(p: Profile): String = formatProfile(p).replace("\n", "\r\n")

    /**
     * Profile name from a file name: without the extension and without squig.link's trailing " Filters"
     * (`<model> Filters.txt`), upper case, at most [max] characters. Null when nothing is left.
     */
    fun nameFromFile(fileName: String?, max: Int): String? {
        var n = fileName?.substringAfterLast('/')?.substringAfterLast('\\')?.trim() ?: return null
        if (n.endsWith(".txt", ignoreCase = true)) n = n.dropLast(4)
        n = n.trim()
        if (n.endsWith(" filters", ignoreCase = true)) n = n.dropLast(8)
        return n.trim().uppercase().take(max).trim().ifEmpty { null }
    }

    /** `<NAME> Filters.txt`, squig.link's convention; characters that file systems refuse become spaces. */
    fun fileNameFor(profileName: String): String {
        val clean = profileName.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), " ").trim().replace(Regex("""\s+"""), " ")
        return clean.ifEmpty { "Profile" } + " Filters.txt"
    }
}

/** EQ by Ear session JSON (the .json files in eq-library, one folder per IEM): parse only. */
object EqByEarJson {
    @Serializable
    private data class FileBand(
        val type: String,
        val fc: Double,
        val gain: Double = 0.0,
        val q: Double = ApoText.DEFAULT_Q,
        val enabled: Boolean = true,
    )

    @Serializable
    private data class FileSession(
        val bands: List<FileBand> = emptyList(),
        val preampDb: Double? = null,
        val preampAuto: Boolean = false,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Unknown filter types are skipped. `preampAuto: true` gives preampDb = null (auto). */
    fun parse(text: String, newId: (Int) -> String = { "b${it + 1}" }): ImportedEq {
        val s = json.decodeFromString(FileSession.serializer(), text)
        val bands = ArrayList<Band>()
        for (b in s.bands) {
            val type = ApoText.typeOf(b.type) ?: continue
            bands += Band(newId(bands.size), type, b.fc, b.gain, b.q, b.enabled)
        }
        return ImportedEq(bands, if (s.preampAuto) null else s.preampDb)
    }
}
