package io.github.chronosauros.contour.core

/** Signed Q30 safety gate for every WalkPlay target (Micro, Max, TRN and the catalog): never clamps or encodes bytes.
 * One implementation with the encoder: [WalkPlay.q30Violation] mirrors computeIir's pre-wrap values and its asymmetric
 * rounding, so what passes here is encoded exactly as before and what does not is refused instead of wrapped. */
internal object WalkPlayQ30Preflight {
    fun requireRepresentable(freq: Double, gainDb: Double, q: Double, typeCode: Int) {
        WalkPlay.q30Violation(freq, gainDb, q, typeCode)?.let { throw IllegalArgumentException(it) }
    }
}
