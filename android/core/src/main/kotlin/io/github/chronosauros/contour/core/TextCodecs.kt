package io.github.chronosauros.contour.core

import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Bands plus preamp as read from a file. [preampDb] = null: the file had none (or asked for auto). */
data class ImportedEq(val bands: List<Band>, val preampDb: Double?)

/**
 * AutoEQ / Equalizer APO parametric text:
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

    fun parse(text: String, newId: (Int) -> String = { "b${it + 1}" }): ImportedEq {
        var preamp: Double? = null
        val bands = ArrayList<Band>()
        for (line in text.lineSequence()) {
            val p = PREAMP.matchEntire(line)
            if (p != null) {
                preamp = num(p.groupValues[1])
                continue
            }
            val m = FILTER.matchEntire(line) ?: continue
            val type = typeOf(m.groupValues[2]) ?: continue
            val rest = m.groupValues[3]
            val fc = FC.find(rest)?.groupValues?.get(1)?.let(::num) ?: continue
            val gain = GAIN.find(rest)?.groupValues?.get(1)?.let(::num) ?: 0.0
            val q = Q.find(rest)?.groupValues?.get(1)?.let(::num) ?: DEFAULT_Q
            bands += Band(
                id = newId(bands.size),
                type = type,
                freqHz = fc,
                gainDb = gain,
                q = q,
                enabled = m.groupValues[1].equals("on", ignoreCase = true),
            )
        }
        return ImportedEq(bands, preamp)
    }

    /** Shortest exact decimal with at least [minDecimals] and at most [maxDecimals] places. */
    private fun fmt(x: Double, minDecimals: Int, maxDecimals: Int): String {
        var d = BigDecimal.valueOf(x).setScale(maxDecimals, RoundingMode.HALF_UP).stripTrailingZeros()
        if (d.scale() < minDecimals) d = d.setScale(minDecimals)
        return d.toPlainString()
    }

    /** APO text. Lossless for values with up to 2 decimals (Fc, gain) and 3 (Q). */
    fun format(bands: List<Band>, preampDb: Double): String = buildString {
        append("Preamp: ").append(fmt(preampDb, 1, 2)).append(" dB\n")
        bands.forEachIndexed { i, b ->
            append("Filter ").append(i + 1).append(": ").append(if (b.enabled) "ON" else "OFF")
            append(' ').append(codeOf(b.type)).append(" Fc ").append(fmt(b.freqHz, 0, 2)).append(" Hz")
            if (b.type != FilterType.LOW_PASS && b.type != FilterType.HIGH_PASS) {
                append(" Gain ").append(fmt(b.gainDb, 1, 2)).append(" dB")
            }
            append(" Q ").append(fmt(b.q, 2, 3)).append('\n')
        }
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
