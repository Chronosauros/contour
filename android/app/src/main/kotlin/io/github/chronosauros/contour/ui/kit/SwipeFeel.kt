package io.github.chronosauros.contour.ui.kit

import kotlin.math.abs

/**
 * The tension of a library row's swipe (round 5b; language of HOLD TO SEND's accepted bowstring, driven by the finger's travel instead of time): the
 * card drawn like a bowstring - tension that grows as the card travels towards the full-action point, a moment of silence just before it, then the
 * snap ([Haptics.swipeSnap], played by the row when its full swipe arms) and, on release while armed, the landing ([Haptics.commit]). One per gesture.
 *
 * p = how far the card is towards the full-action point, 0..1. The card's travel, in either direction (pulling back eases the tension), plays one grain
 * every d px, d = the full-action distance x (0.08 - 0.055 p), so every grain answers a movement and nothing plays once the finger stops:
 * - p < 0.7: LOW_TICK, 0.12 + 0.40 p;
 * - 0.7 <= p < 0.92: TICK, 0.35 + 1.2 (p - 0.7);
 * - from 0.92, and while armed: silence, the held breath before the snap.
 */
class SwipeFeel(private val haptics: Haptics) {
    private var last = 0f
    private var travel = 0f

    /** A drag begins with the card [shownPx] px out (towards the strip that is showing). */
    fun start(shownPx: Float) {
        last = shownPx
        travel = 0f
    }

    /** The card is [shownPx] px out after an event, the full-action point at [fullPx] px; [armed] when the full swipe is armed. */
    fun move(shownPx: Float, fullPx: Float, armed: Boolean) {
        travel += abs(shownPx - last)
        last = shownPx
        if (fullPx <= 0f) return
        val p = (shownPx / fullPx).coerceIn(0f, 1f)
        if (armed || p >= SILENT_FROM) {
            travel = 0f
            return
        }
        if (travel < fullPx * (0.08f - 0.055f * p)) return
        travel = 0f
        if (p < TOOTH_FROM) haptics.dragGrain(grainScale(p)) else haptics.dragTooth(toothScale(p))
    }

    companion object {
        private const val TOOTH_FROM = 0.7f
        private const val SILENT_FROM = 0.92f

        /** The grain of the tension before p = 0.7 (LOW_TICK). */
        fun grainScale(p: Float) = 0.12f + 0.40f * p

        /** The tooth of the tension from p = 0.7 to 0.92 (TICK, 0.35 -> about 0.61). */
        fun toothScale(p: Float) = 0.35f + 1.2f * (p - TOOTH_FROM)
    }
}
