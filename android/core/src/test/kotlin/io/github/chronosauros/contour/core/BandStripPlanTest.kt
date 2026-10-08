package io.github.chronosauros.contour.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Tune band strip: nothing is clipped when it all fits, and a scrolling strip is cut at about a chip's middle. */
class BandStripPlanTest {
    // fitDensity gives every screen narrower than 411 dp the 411 dp layout, so 360 dp and 412 dp phones both get a 379 dp row.
    private val rows = listOf(379.0, 450.0)

    @Test fun `table for 1 to 10 bands`() {
        for (w in rows) for (n in 1..10) {
            val plus = n < 10
            val p = BandStripPlan.plan(w, n, plus)
            val pinned = if (plus) 2 else 1
            val line = if (!p.scroll) {
                val k = n + pinned
                val used = k * p.chipDp + (k - 1) * p.gapDp
                assertTrue(used <= w + 1e-9, "$n bands at $w: $used > $w")
                assertEquals(BandStripPlan.TOUCH, p.chipDp)
                "fits, chip %.1f gap %.2f, used %.1f".format(java.util.Locale.ROOT, p.chipDp, p.gapDp, used)
            } else {
                assertEquals(w, p.areaDp + pinned * (BandStripPlan.TOUCH + BandStripPlan.GAP), 1e-9)
                val whole = ((p.areaDp + p.gapDp) / (p.chipDp + p.gapDp)).toInt()
                val cut = (p.areaDp - whole * (p.chipDp + p.gapDp)) / p.chipDp
                assertTrue(p.chipDp >= BandStripPlan.TOUCH, "touch size")
                "scrolls, chip %.1f, area %.1f, %d whole + %.0f%% of the next".format(java.util.Locale.ROOT, p.chipDp, p.areaDp, whole, cut * 100)
            }
            println("PLAN $w dp, $n bands${if (plus) " + plus" else ""}: $line")
        }
    }

    @Test fun `a very narrow row never gives a negative width`() {
        for (w in listOf(0.0, 50.0, 100.0, 111.0, 112.0)) for (n in 1..10) for (plus in listOf(true, false)) {
            val p = BandStripPlan.plan(w, n, plus)
            assertTrue(p.areaDp >= 0.0 && p.chipDp >= BandStripPlan.TOUCH && p.gapDp >= 0.0, "$w $n $plus $p")
        }
    }

    @Test fun `five bands plus and minus fit without clipping at 379 dp`() {
        val p = BandStripPlan.plan(379.0, 5, true)
        assertFalse(p.scroll)
        assertTrue(7 * p.chipDp + 6 * p.gapDp <= 379.0)
    }

    @Test fun `six bands scroll and the last visible chip is cut near its middle`() {
        val p = BandStripPlan.plan(379.0, 6, true)
        assertTrue(p.scroll)
        val whole = ((p.areaDp + p.gapDp) / (p.chipDp + p.gapDp)).toInt()
        val cut = (p.areaDp - whole * (p.chipDp + p.gapDp)) / p.chipDp
        assertEquals(0.5, cut, 0.01)
    }
}
