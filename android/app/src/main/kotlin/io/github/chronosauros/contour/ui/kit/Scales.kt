package io.github.chronosauros.contour.ui.kit

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.roundToLong

/**
 * A slider scale: value <-> position 0..1, quantization, the tick marks and labels drawn above the slot,
 * and the haptic marks (a light tick when the value crosses a [stepMarks] value, a strong one at [strongMarks]).
 * [fineMax]: the most a slow finger moves the value per dp, where the scale is coarser than that (FREQ's top
 * decade), or null.
 */
class Scale(
    val min: Double,
    val max: Double,
    val log: Boolean,
    val quantum: Double,
    val labels: List<Pair<Double, String>>,
    val ticks: List<Double>,
    val stepMarks: DoubleArray,
    val strongMarks: DoubleArray,
    val reset: Double?,
    val fineMax: Double? = null,
) {
    /** How much the value moves per 1 of position at [v] (the scale's slope). */
    fun perPos(v: Double): Double = if (log) v.coerceIn(min, max) * ln(max / min) else max - min

    fun toPos(v: Double): Float {
        val c = v.coerceIn(min, max)
        return (if (log) ln(c / min) / ln(max / min) else (c - min) / (max - min)).toFloat()
    }

    fun fromPos(p: Float): Double {
        val c = p.coerceIn(0f, 1f).toDouble()
        return if (log) min * exp(c * ln(max / min)) else min + c * (max - min)
    }

    fun quantize(v: Double): Double = ((v / quantum).roundToLong() * quantum).coerceIn(min, max).let { roundTo(it) }

    private fun roundTo(v: Double): Double {
        val digits = when {
            quantum >= 1 -> 0
            quantum >= 0.1 -> 1
            else -> 2
        }
        val p = Math.pow(10.0, digits.toDouble())
        return Math.round(v * p) / p
    }

    /** 0 = nothing, 1 = light tick, 2 = strong tick, for a change from [a] to [b]. */
    fun crossing(a: Double, b: Double): Int {
        if (a == b) return 0
        val lo = minOf(a, b)
        val hi = maxOf(a, b)
        fun hit(m: Double) = if (b > a) m > lo + EPS && m <= hi + EPS else m >= lo - EPS && m < hi - EPS
        if (strongMarks.any(::hit)) return 2
        if (stepMarks.any(::hit)) return 1
        return 0
    }

    companion object {
        private const val EPS = 1e-9

        /** ISO 1/3-octave centres 20 Hz - 20 kHz. */
        val ISO_THIRDS = doubleArrayOf(
            20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0, 400.0, 500.0,
            630.0, 800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0,
            12500.0, 16000.0, 20000.0,
        )

        val FREQ = Scale(
            20.0, 20_000.0, log = true, quantum = 1.0,
            labels = listOf(20.0 to "20", 100.0 to "100", 1000.0 to "1K", 10_000.0 to "10K", 20_000.0 to "20K"),
            ticks = ISO_THIRDS.toList(),
            stepMarks = ISO_THIRDS,
            strongMarks = doubleArrayOf(20.0, 100.0, 1000.0, 10_000.0, 20_000.0),
            reset = null,
            // owner 26.09: 15 kHz and up is 4 % of the log bar; a slow finger there moved about 73 Hz a dp
            fineMax = 5.0,
        )

        val GAIN = Scale(
            -10.0, 10.0, log = false, quantum = 0.1,
            labels = listOf(-10.0 to "-10", -5.0 to "-5", 0.0 to "0", 5.0 to "+5", 10.0 to "+10"),
            ticks = (-10..10).map { it.toDouble() },
            stepMarks = DoubleArray(41) { -10.0 + it * 0.5 },
            strongMarks = doubleArrayOf(-10.0, -5.0, 0.0, 5.0, 10.0),
            reset = 0.0,
        )

        val Q = Scale(
            0.1, 10.0, log = true, quantum = 0.01,
            labels = listOf(0.1 to "0.1", 0.3 to "0.3", 1.0 to "1", 3.0 to "3", 10.0 to "10"),
            ticks = listOf(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0),
            stepMarks = doubleArrayOf(0.5, 0.71, 1.0, 1.41, 2.0, 3.0, 4.0, 6.0, 8.0, 10.0),
            strongMarks = doubleArrayOf(0.1, 0.3, 1.0, 3.0, 10.0),
            reset = 0.71,
        )
    }
}

/** Readout formats: `85 Hz`, `12 450 Hz` (thin space), `-4.5 dB`, `+3.0 dB`, `0.0 dB`, `6.05`. */
object Fmt {
    const val THIN = ' '

    fun freq(hz: Double): String {
        val n = Math.round(hz)
        return if (n >= 1000) "${n / 1000}$THIN${String.format(Locale.ROOT, "%03d", n % 1000)}" else n.toString()
    }

    fun gain(db: Double): String {
        val r = Math.round(db * 10) / 10.0
        return when {
            abs(r) < 0.05 -> "0.0"
            r > 0 -> String.format(Locale.ROOT, "+%.1f", r)
            else -> String.format(Locale.ROOT, "-%.1f", -r)
        }
    }

    fun q(q: Double): String = String.format(Locale.ROOT, "%.2f", q)

    fun preamp(db: Int): String = if (db == 0) "0" else if (db > 0) "+$db" else "-${-db}"
}
