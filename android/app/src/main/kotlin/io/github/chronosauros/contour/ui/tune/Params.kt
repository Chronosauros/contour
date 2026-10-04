package io.github.chronosauros.contour.ui.tune

import io.github.chronosauros.contour.core.Band
import io.github.chronosauros.contour.core.DeviceCapabilities
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

    /** [text] without the unit: the number the stacked value shows big, [unit] small beside it. */
    fun number(v: Double): String = when (this) {
        FREQ -> Fmt.freq(v)
        GAIN, PREAMP -> Fmt.gain(v)
        Q -> Fmt.q(v)
    }

    /** Plain number for the text field. */
    fun edit(v: Double): String = when (this) {
        FREQ -> Math.round(v).toString()
        GAIN -> Fmt.gain(v).removePrefix("+")
        Q -> Fmt.q(v)
        PREAMP -> Fmt.gain(v).removePrefix("+")
    }

    /** The slider scale for the connected DAC's ranges (Micro/Max: [scale] itself; KA15: gain -12..+12 dB). */
    fun scaleFor(caps: DeviceCapabilities): Scale? = when (this) {
        FREQ -> Scale.FREQ.bounded(caps.freqMinHz, caps.freqMaxHz)
        GAIN -> Scale.GAIN.bounded(caps.gainMinDb, caps.gainMaxDb)
        Q -> Scale.Q.bounded(caps.qMin, caps.qMax)
        PREAMP -> null
    }

    /** Typed text -> the stored value (clamped and quantized to the device range), null if not a number. */
    fun parse(text: String, caps: DeviceCapabilities? = null): Double? {
        val v = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        return when (this) {
            PREAMP -> Math.round(v * 10) / 10.0 // clamped by AppModel.setPreamp (device range minus HS gains)
            else -> {
                val s = if (caps == null) scale!! else scaleFor(caps)!!
                s.quantize(v.coerceIn(s.min, s.max))
            }
        }
    }

    /** Preserve the scale's strong marks (including [home]) instead of downgrading them to a step. */
    fun crossing(a: Double, b: Double): Int = scale?.crossing(a, b) ?: 0
}
