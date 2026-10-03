package io.github.chronosauros.contour.core

import kotlin.math.*

/** New-target safety gate only. Keep the captured legacy encoder and its JS wrapping untouched.
 * Mirrors WalkPlay's pre-wrap RBJ values and asymmetric rounding; never clamps or encodes bytes. */
internal object WalkPlayQ30Preflight {
    fun requireRepresentable(freq: Double, gainDb: Double, q: Double, typeCode: Int) {
        val a = sqrt(10.0.pow(gainDb / 20))
        val w0 = freq * 6.283185307179586 / 96000
        val s = sin(w0)
        val c = cos(w0)
        val alpha = s / (2 * q)
        val b0: Double
        val b1: Double
        val b2: Double
        val a0: Double
        val a1: Double
        val a2: Double
        if (typeCode == WalkPlay.TYPE_LSQ || typeCode == WalkPlay.TYPE_HSQ) {
            val sa = (s / 2) * sqrt((a + 1 / a) * (1 / q - 1) + 2)
            val k = 2 * sqrt(a) * sa
            if (typeCode == WalkPlay.TYPE_LSQ) {
                b0 = a * ((a + 1) - (a - 1) * c + k); b1 = 2 * a * ((a - 1) - (a + 1) * c)
                b2 = a * ((a + 1) - (a - 1) * c - k); a0 = (a + 1) + (a - 1) * c + k
                a1 = -2 * ((a - 1) + (a + 1) * c); a2 = (a + 1) + (a - 1) * c - k
            } else {
                b0 = a * ((a + 1) + (a - 1) * c + k); b1 = -2 * a * ((a - 1) + (a + 1) * c)
                b2 = a * ((a + 1) + (a - 1) * c - k); a0 = (a + 1) - (a - 1) * c + k
                a1 = 2 * ((a - 1) - (a + 1) * c); a2 = (a + 1) - (a - 1) * c - k
            }
        } else {
            b0 = 1 + alpha * a; b1 = -2 * c; b2 = 1 - alpha * a
            a0 = 1 + alpha / a; a1 = -2 * c; a2 = 1 - alpha / a
        }
        val scale = 1073741824.0
        val values = doubleArrayOf(b0 / a0 * scale, b1 / a0 * scale, b2 / a0 * scale,
            -a1 / a0 * scale, -a2 / a0 * scale)
        values.forEachIndexed { i, value ->
            val rounded = if (i < 3) WalkPlay.jsRound(value) else -WalkPlay.jsRound(-value)
            require(rounded.isFinite() && rounded in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
                "Coefficient ${listOf("b0", "b1", "b2", "-a1", "-a2")[i]}=$rounded not representable as signed Q30"
            }
        }
    }
}
