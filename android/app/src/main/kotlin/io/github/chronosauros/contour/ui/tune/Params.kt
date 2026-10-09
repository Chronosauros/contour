package io.github.chronosauros.contour.ui.tune

import io.github.chronosauros.contour.core.Band
import io.github.chronosauros.contour.ui.kit.Fmt
import io.github.chronosauros.contour.ui.kit.Scale

/** The typed / slid band values. [home] is the value with the stronger haptic tick (1 kHz, 0 dB, Q 1). */
enum class Param(val label: String, val unit: String, val scale: Scale?, val home: Double) {
    FREQ("FREQ", "Hz", Scale.FREQ, 1000.0),
    GAIN("GAIN", "dB", Scale.GAIN, 0.0),
    Q("Q", "", Scale.Q, 1.0),
    PREAMP("PREAMP", "dB", null, 0.0);

    fun scaleFor(caps: io.github.chronosauros.contour.core.DeviceCapabilities): Scale? = when (this) {
        FREQ -> Scale.FREQ.bounded(caps.freqMinHz, caps.freqMaxHz)
        GAIN -> Scale.GAIN.bounded(caps.gainMinDb, caps.gainMaxDb)
        Q -> Scale.Q.bounded(caps.qMin, caps.qMax)
        PREAMP -> null
    }

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

    /** `20 000 Hz`, `-10.0 dB`, `10.00` - one line, never wrapped. */
    fun text(v: Double): String = when (this) {
        FREQ -> "${Fmt.freq(v)} Hz"
        GAIN -> "${Fmt.gain(v)} dB"
        Q -> Fmt.q(v)
        PREAMP -> "${number(v)} dB"
    }

    /** [text] without the unit: the number the stacked value shows big, [unit] small beside it. */
    fun number(v: Double): String = when (this) {
        FREQ -> Fmt.freq(v)
        GAIN -> Fmt.gain(v)
        PREAMP -> fullDb(v)?.let { if (v > 0) "+$it" else it } ?: Fmt.gain(v)
        Q -> Fmt.q(v)
    }

    /** Plain number for the text field. */
    fun edit(v: Double): String = when (this) {
        FREQ -> Math.round(v).toString()
        GAIN -> Fmt.gain(v).removePrefix("+")
        Q -> Fmt.q(v)
        PREAMP -> fullDb(v) ?: Fmt.gain(v).removePrefix("+")
    }

    /** Typed text -> the stored value (clamped and quantized to the device range), null if not a number. */
    fun parse(text: String, caps: io.github.chronosauros.contour.core.DeviceCapabilities? = null): Double? {
        val v = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        return when (this) {
            PREAMP -> io.github.chronosauros.contour.core.Preamp.floorTo(v) // range-checked by AppModel.setPreamp (device range minus HS gains), never clamped
            else -> {
                val s = if (caps == null) scale!! else scaleFor(caps)!!
                if (v !in s.min..s.max) null else s.quantize(v)
            }
        }
    }

    /** Preserve the scale's strong marks (including [home]) instead of downgrading them to a step. */
    fun crossing(a: Double, b: Double): Int = scale?.crossing(a, b) ?: 0
}

/** A stored preamp that is not on a 0.1 dB step (a preserved import) in full, so the row, the editor and a refusal show one number; null otherwise. */
private fun fullDb(v: Double): String? =
    if (v.isFinite() && v != Math.round(v * 10) / 10.0) java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString() else null
