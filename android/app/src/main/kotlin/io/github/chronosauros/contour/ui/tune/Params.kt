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
        PREAMP -> "${Fmt.gain(v)} dB"
    }

    /** Plain number for the text field. */
    fun edit(v: Double): String = when (this) {
        FREQ -> Math.round(v).toString()
        GAIN -> Fmt.gain(v).removePrefix("+")
        Q -> Fmt.q(v)
        PREAMP -> Fmt.gain(v).removePrefix("+")
    }

    /** Typed text -> the stored value (clamped and quantized to the device range), null if not a number. */
    fun parse(text: String): Double? {
        val t = text.replace(',', '.').filter { it.isDigit() || it == '.' || it == '-' }
        val v = t.toDoubleOrNull() ?: return null
        return when (this) {
            PREAMP -> Math.round(v * 10) / 10.0 // clamped by AppModel.setPreamp (device range minus HS gains)
            else -> scale!!.quantize(v.coerceIn(scale.min, scale.max))
        }
    }

    /** 0 = none, 1 = step, 2 = strong: the haptic for a change from [a] to [b]. */
    fun crossing(a: Double, b: Double): Int {
        val s = scale ?: return 0
        if (a == b) return 0
        val lo = minOf(a, b)
        val hi = maxOf(a, b)
        if ((home > lo && home <= hi && b > a) || (home >= lo && home < hi && b < a)) return 2
        return if (s.crossing(a, b) > 0) 1 else 0
    }
}
