package io.github.chronosauros.contour.ui.tune

import io.github.chronosauros.contour.core.Band
import io.github.chronosauros.contour.core.DeviceTarget
import io.github.chronosauros.contour.core.FilterType
import io.github.chronosauros.contour.core.Preamp
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.ui.kit.Scale
import io.github.chronosauros.contour.usb.DeviceController

/**
 * FIT TO DAC RANGE: a profile's [bands] and [preampDb] with every value outside the connected DAC's range pulled to the
 * nearest value it takes, on the sliders' grid; [values] counts the fields that moved. Filter types, band count, enabled
 * flags and an AUTO preamp are never touched, so a profile whose block is anything but a range is not fitted.
 */
class RangeFit(val bands: List<Band>, val preampDb: Double?, val values: Int) {
    companion object {
        /** The fit for [p] on [target], or null when [p] has no issue, or when pulling values into range does not clear every issue. */
        fun of(p: Profile, target: DeviceTarget): RangeFit? {
            if (target.issues(p).isEmpty()) return null
            if (p.bands.any { !it.freqHz.isFinite() || !it.gainDb.isFinite() || !it.q.isFinite() } || p.preampDb?.isFinite() == false) return null
            // Micro and Max check enabled bands only, the other targets every band: touch a disabled band only if that is what it takes.
            return fit(p, target, includeDisabled = false) ?: fit(p, target, includeDisabled = true)
        }

        /** The slider-grid value of [scale] nearest to [v] that lies inside [range]: a bound that is off the grid gives way inward (Q 8 on 0.07..7.0711 -> 7.07). */
        private fun grid(scale: Scale, v: Double, range: ClosedFloatingPointRange<Double>): Double {
            val q = scale.quantum
            val lo = kotlin.math.ceil(range.start / q - 1e-9).toLong()
            val hi = kotlin.math.floor(range.endInclusive / q + 1e-9).toLong()
            if (lo > hi) return v.coerceIn(range) // no grid point inside: the planner decides
            return Math.round(Math.round(v / q).coerceIn(lo, hi) * q * 1e6) / 1e6
        }

        /** [x] rounded UP to a multiple of [step] (toward the inside of a lower bound); the counterpart of [Preamp.floorTo]. */
        private fun ceilTo(x: Double, step: Double): Double =
            if (step == 0.1) kotlin.math.ceil(x * 10 - 1e-6) / 10.0 else kotlin.math.ceil(x / step - 1e-6) * step

        private fun fit(p: Profile, target: DeviceTarget, includeDisabled: Boolean): RangeFit? {
            val caps = target.caps
            val freq = Param.FREQ.scaleFor(caps)!!
            val gain = Param.GAIN.scaleFor(caps)!!
            var values = 0
            val bands = p.bands.map { b ->
                if (!b.enabled && !includeDisabled) return@map b
                val qr = target.qRange(b.type)
                val pass = b.type == FilterType.LOW_PASS || b.type == FilterType.HIGH_PASS
                val f = if (b.freqHz in freq.min..freq.max) b.freqHz else grid(freq, b.freqHz, freq.min..freq.max)
                val g = when { b.gainDb in gain.min..gain.max -> b.gainDb; pass -> 0.0; else -> grid(gain, b.gainDb, gain.min..gain.max) }
                val q = if (b.q in qr) b.q else grid(Scale.Q, b.q, qr)
                values += listOf(f != b.freqHz, g != b.gainDb, q != b.q).count { it }
                b.copy(freqHz = f, gainDb = g, q = q)
            }
            var preamp = p.preampDb
            // A DAC without a preamp range (KT, Fosi) has nothing to clamp to: 0 dB would be louder than asked.
            if (preamp != null && target.preampMin < target.preampMax && !target.preampFits(bands, preamp)) {
                val shelf = target.shelfOffset(bands)
                // inward at both bounds, so a valid value on the grid is never missed (a lower bound off the grid rounds up, an upper one down)
                val lo = target.preampMin - shelf
                val fitted = if (preamp < lo) ceilTo(lo, target.preampStep) else Preamp.floorTo(target.preampMax - shelf, target.preampStep)
                if (fitted != preamp) { preamp = fitted; values++ }
            }
            if (values == 0) return null
            return RangeFit(bands, preamp, values).takeIf { target.issues(p.copy(bands = bands, preampDb = preamp)).isEmpty() }
        }
    }
}

/** The fit HOLD TO SEND offers for [p], only when it clears the whole send block (a native DAC's codec plan included). */
fun rangeFitFor(p: Profile, device: DeviceController): RangeFit? {
    val fit = RangeFit.of(p, device.protocol) ?: return null
    return fit.takeIf { device.sendBlock(p.copy(bands = it.bands, preampDb = it.preampDb)) != DeviceController.SendBlock.EQ }
}
