package io.github.chronosauros.contour.ui.tune

import io.github.chronosauros.contour.core.Band
import io.github.chronosauros.contour.core.DeviceCapabilities
import io.github.chronosauros.contour.ui.kit.Fmt
import io.github.chronosauros.contour.ui.kit.Scale
import io.github.chronosauros.contour.core.FilterType
import io.github.chronosauros.contour.core.isShelf

/** The typed / slid band values. [home] is the value with the stronger haptic tick (1 kHz, 0 dB, Q 1). */
enum class Param(val label: String, val unit: String, val scale: Scale?, val home: Double) {
    FREQ("FREQ", "Hz", Scale.FREQ, 1000.0),
    GAIN("GAIN", "dB", Scale.GAIN, 0.0),
    Q("Q", "", Scale.Q, 1.0),
    PREAMP("PREAMP", "dB", null, 0.0);

    fun of(b: Band): Double = when (this) {
        FREQ -> b.freqHz
        GAIN -> b.gainDb
        Q -> b.q
        PREAMP -> 0.0
    }

    fun set(b: Band, v: Double): Band = when (this) {
        FREQ -> b.copy(freqHz = v)
        GAIN -> b.copy(gainDb = v)
        Q -> b.copy(q = v)
        PREAMP -> b
    }

    /**
     * `20 000 Hz`, `-10.0 dB`, `10.00` - one line, never wrapped. [wholeDb]: the DAC keeps a whole-dB preamp only
     * (DeviceTarget.preampStep 1), so a whole preamp reads `-4 dB`, not `-4.0 dB` that invites typing tenths (user report 11.11.2026).
     */
    fun text(v: Double, wholeDb: Boolean = false): String = when (this) {
        FREQ -> "${Fmt.freq(v)} Hz"
        GAIN -> "${Fmt.gain(v)} dB"
        Q -> Fmt.q(v)
        PREAMP -> "${number(v, wholeDb)} dB"
    }

    /** [text] without the unit: the number the stacked value shows big, [unit] small beside it. */
    fun number(v: Double, wholeDb: Boolean = false): String = when (this) {
        FREQ -> Fmt.freq(v)
        GAIN -> Fmt.gain(v)
        PREAMP -> (fullDb(v) ?: wholeText(v, wholeDb))?.let { if (v > 0) "+$it" else it } ?: Fmt.gain(v)
        Q -> Fmt.q(v)
    }

    /** Plain number for the text field. */
    fun edit(v: Double, wholeDb: Boolean = false): String = when (this) {
        FREQ -> Math.round(v).toString()
        GAIN -> Fmt.gain(v).removePrefix("+")
        Q -> Fmt.q(v)
        PREAMP -> fullDb(v) ?: wholeText(v, wholeDb) ?: Fmt.gain(v).removePrefix("+")
    }

    /** The slider scale for the connected DAC's ranges (Micro/Max: [scale] itself; KA15: gain -12..+12 dB). */
    fun scaleFor(caps: DeviceCapabilities, type: FilterType? = null): Scale? = when (this) {
        FREQ -> Scale.FREQ.bounded(caps.freqMinHz, caps.freqMaxHz)
        // a shelf of a DAC that was measured narrower (caps.shelfLimits) gets its own range; every other band and DAC keeps the device range
        GAIN -> if (type != null && type.isShelf && caps.shelfLimits != null) caps.gainRange(type).let { Scale.GAIN.bounded(it.start, it.endInclusive) } else Scale.GAIN.bounded(caps.gainMinDb, caps.gainMaxDb)
        Q -> if (type != null && type.isShelf && caps.shelfLimits != null) caps.qRange(type).let { Scale.Q.bounded(it.start, it.endInclusive) } else Scale.Q.bounded(caps.qMin, caps.qMax)
        PREAMP -> null
    }

    /**
     * Typed text -> the stored value, null if not a number. FREQ and GAIN are clamped and quantized to the device range; a typed Q is only clamped:
     * it keeps the precision the user typed (0.707 stays 0.707) until the family codec builds the register value.
     */
    fun parse(text: String, caps: DeviceCapabilities? = null, type: FilterType? = null): Double? {
        val v = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        return when (this) {
            PREAMP -> io.github.chronosauros.contour.core.Preamp.floorTo(v) // range-checked by AppModel.setPreamp (device range minus HS gains), never clamped
            else -> {
                val s = if (caps == null) scale!! else scaleFor(caps, type)!!
                if (this == Q) v.coerceIn(s.min, s.max) else s.quantize(v.coerceIn(s.min, s.max))
            }
        }
    }

    /** Preserve the scale's strong marks (including [home]) instead of downgrading them to a step. */
    fun crossing(a: Double, b: Double): Int = scale?.crossing(a, b) ?: 0
}

/** A stored preamp that is not on a 0.1 dB step (a preserved import) in full, so the row, the editor and a refusal show one number; null otherwise. */
private fun fullDb(v: Double): String? =
    if (v.isFinite() && v != Math.round(v * 10) / 10.0) java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString() else null

/** A whole preamp on a whole-dB DAC without the decimal (`-4`, `0`; the caller adds `+`); null otherwise (`-4.0` stays on a 0.1 dB DAC). */
private fun wholeText(v: Double, wholeDb: Boolean): String? =
    if (wholeDb && v.isFinite() && kotlin.math.abs(v) < 1e9 && v == Math.rint(v)) Math.rint(v).toLong().toString() else null
