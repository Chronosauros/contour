package io.github.chronosauros.contour.core

/**
 * How the Tune band strip (chips 1..N, then "+" when a band can still be added, then "-") uses a row of [widthDp].
 * Pure arithmetic in dp so the table can be tested.
 *
 * - Everything fits: chips keep the [TOUCH] size and only the gap tightens (never below [MIN_GAP]); nothing is cut.
 * - Otherwise the numbered chips scroll in an area of [areaDp] and "+" / "-" stay pinned after it. The chip size is
 *   chosen so the last visible chip is cut at about its middle (m whole chips and one half), so the cut reads as
 *   "more to the right"; chips never get smaller than [TOUCH].
 */
object BandStripPlan {
    const val TOUCH = 48.0
    const val GAP = 8.0
    const val MIN_GAP = 4.0
    /** Upper bound of the chip size in the scrolling layout (very wide screens just show a different cut). */
    const val MAX_CHIP = 60.0

    data class Plan(val scroll: Boolean, val chipDp: Double, val gapDp: Double, val areaDp: Double)

    fun plan(widthDp: Double, bands: Int, showPlus: Boolean): Plan {
        val pinned = if (showPlus) 2 else 1
        val k = bands + pinned
        val gapFit = (widthDp - k * TOUCH) / (k - 1)
        if (gapFit >= MIN_GAP) return Plan(false, TOUCH, minOf(GAP, gapFit), 0.0)
        val area = (widthDp - pinned * (TOUCH + GAP)).coerceAtLeast(0.0)
        var chip = TOUCH
        for (m in (bands - 1) downTo 1) {
            val c = (area - GAP * m) / (m + 0.5)
            if (c >= TOUCH) { chip = c; break }
        }
        return Plan(true, minOf(chip, MAX_CHIP), GAP, area)
    }
}
