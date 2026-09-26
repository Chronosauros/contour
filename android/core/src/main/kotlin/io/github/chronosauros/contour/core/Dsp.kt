package io.github.chronosauros.contour.core

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Display DSP: RBJ Audio EQ Cookbook biquads (Q for peaks/pass filters, shelf slope S = band Q)
 * evaluated at [FS] with the user's own values, i.e. the intended curve, not what a device realises
 * after compensation.
 *
 * Cheap enough for every drag frame: a [FreqGrid] precomputes cos(w) and cos(2w) once, so one band
 * costs a handful of multiplications per point and no trigonometry.
 */
object Dsp {
    const val FS: Double = 96_000.0
    const val FMIN: Double = 20.0
    const val FMAX: Double = 20_000.0
    const val DISPLAY_POINTS: Int = 512

    /** Log-spaced frequencies with their cos(w), cos(2w) at [FS]. */
    class FreqGrid(val freqs: DoubleArray) {
        val size: Int get() = freqs.size
        internal val cosW = DoubleArray(freqs.size) { cos(2.0 * Math.PI * freqs[it] / FS) }
        internal val cos2W = DoubleArray(freqs.size) { cos(4.0 * Math.PI * freqs[it] / FS) }
    }

    fun logFreqs(n: Int, fMin: Double = FMIN, fMax: Double = FMAX): DoubleArray {
        require(n >= 2)
        val span = ln(fMax / fMin)
        return DoubleArray(n) { fMin * exp(span * it / (n - 1)) }
    }

    /** The 20 Hz - 20 kHz grid used for drawing. */
    val DISPLAY_GRID: FreqGrid by lazy { FreqGrid(logFreqs(DISPLAY_POINTS)) }

    /** Normalised biquad: b0, b1, b2, a1, a2 with a0 = 1. */
    data class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    fun biquad(band: Band, fs: Double = FS): Biquad {
        val w0 = 2.0 * Math.PI * band.freqHz / fs
        val c = cos(w0)
        val s = sin(w0)
        val alpha = s / (2.0 * band.q) // peak and pass filters only; shelves use slope S = Q
        val a = 10.0.pow(band.gainDb / 40.0)
        val shelfAlpha = if (band.type == FilterType.LOW_SHELF || band.type == FilterType.HIGH_SHELF) {
            val radicand = (a + 1.0 / a) * (1.0 / band.q - 1.0) + 2.0
            require(radicand.isFinite() && radicand >= 0.0) { "Shelf gain/Q has no real response" }
            (s / 2.0) * sqrt(radicand)
        }
        else 0.0
        val b0: Double
        val b1: Double
        val b2: Double
        val a0: Double
        val a1: Double
        val a2: Double
        when (band.type) {
            FilterType.PEAK -> {
                b0 = 1 + alpha * a; b1 = -2 * c; b2 = 1 - alpha * a
                a0 = 1 + alpha / a; a1 = -2 * c; a2 = 1 - alpha / a
            }
            FilterType.LOW_SHELF -> {
                val k = 2 * sqrt(a) * shelfAlpha
                b0 = a * ((a + 1) - (a - 1) * c + k); b1 = 2 * a * ((a - 1) - (a + 1) * c)
                b2 = a * ((a + 1) - (a - 1) * c - k); a0 = (a + 1) + (a - 1) * c + k
                a1 = -2 * ((a - 1) + (a + 1) * c); a2 = (a + 1) + (a - 1) * c - k
            }
            FilterType.HIGH_SHELF -> {
                val k = 2 * sqrt(a) * shelfAlpha
                b0 = a * ((a + 1) + (a - 1) * c + k); b1 = -2 * a * ((a - 1) + (a + 1) * c)
                b2 = a * ((a + 1) + (a - 1) * c - k); a0 = (a + 1) - (a - 1) * c + k
                a1 = 2 * ((a - 1) - (a + 1) * c); a2 = (a + 1) - (a - 1) * c - k
            }
            FilterType.LOW_PASS -> {
                b0 = (1 - c) / 2; b1 = 1 - c; b2 = (1 - c) / 2
                a0 = 1 + alpha; a1 = -2 * c; a2 = 1 - alpha
            }
            FilterType.HIGH_PASS -> {
                b0 = (1 + c) / 2; b1 = -(1 + c); b2 = (1 + c) / 2
                a0 = 1 + alpha; a1 = -2 * c; a2 = 1 - alpha
            }
        }
        val result = Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        require(listOf(result.b0, result.b1, result.b2, result.a1, result.a2).all { it.isFinite() }) {
            "Band has no finite response"
        }
        return result
    }

    /** Adds one band's response in dB to [out] (same size as [grid]). */
    fun addBandDb(band: Band, grid: FreqGrid, out: DoubleArray) {
        val q = biquad(band)
        val nk = q.b0 * q.b0 + q.b1 * q.b1 + q.b2 * q.b2
        val n1 = 2 * (q.b0 * q.b1 + q.b1 * q.b2)
        val n2 = 2 * q.b0 * q.b2
        val dk = 1 + q.a1 * q.a1 + q.a2 * q.a2
        val d1 = 2 * (q.a1 + q.a1 * q.a2)
        val d2 = 2 * q.a2
        val cw = grid.cosW
        val c2w = grid.cos2W
        for (i in out.indices) {
            val num = nk + n1 * cw[i] + n2 * c2w[i]
            val den = dk + d1 * cw[i] + d2 * c2w[i]
            out[i] += 10.0 * log10(num / den)
        }
    }

    /** Total response in dB of the enabled bands over [grid], written into [out] (allocation-free). */
    fun responseDb(bands: List<Band>, grid: FreqGrid, out: DoubleArray) {
        require(out.size == grid.size)
        out.fill(0.0)
        for (b in bands) if (b.enabled) addBandDb(b, grid, out)
        require(out.all { it.isFinite() }) { "Profile has no finite response" }
    }

    fun responseDb(bands: List<Band>, grid: FreqGrid = DISPLAY_GRID): DoubleArray =
        DoubleArray(grid.size).also { responseDb(bands, grid, it) }

    /** One band's own curve in dB (disabled bands included - the editor may still show them). */
    fun bandDb(band: Band, grid: FreqGrid = DISPLAY_GRID): DoubleArray =
        DoubleArray(grid.size).also { addBandDb(band, grid, it) }
}

/** Auto preamp: whole dB, never positive. */
object Preamp {
    private const val SCAN_POINTS = 1024
    private val SCAN_GRID: Dsp.FreqGrid by lazy { Dsp.FreqGrid(Dsp.logFreqs(SCAN_POINTS)) }

    /**
     * Max of the total response over 1 024 log points plus every enabled band's centre frequency;
     * `-ceil(max - 1e-6)` whole dB when max > 0, else 0. The Protocol Micro stores whole dB only, and
     * rounding up leaves a little extra headroom (owner's preference).
     */
    fun auto(bands: List<Band>): Int {
        val active = bands.filter { it.enabled }
        if (active.isEmpty()) return 0
        val centres = Dsp.FreqGrid(DoubleArray(active.size) { active[it].freqHz })
        val max = maxOf(Dsp.responseDb(active, SCAN_GRID).max(), Dsp.responseDb(active, centres).max())
        require(max.isFinite()) { "AUTO preamp needs a finite response" }
        return if (max > 0) -kotlin.math.ceil(max - 1e-6).toInt() else 0
    }
}
