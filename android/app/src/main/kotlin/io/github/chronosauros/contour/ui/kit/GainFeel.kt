package io.github.chronosauros.contour.ui.kit

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * GAIN's feel (design: `_Projects/contour-refs/haptics-research-2026-11-14/claude/haptics-report-and-design.md`, "Projekt: GAIN"): the finger
 * counts decimal places. Every 0.1 dB change of the readout is a grain, every whole dB the readout shows a clear tooth, zero is a click "at home",
 * the end of the DAC's range a wall; the further from 0, the tighter. The trigger is the value after quantization (the one the readout shows),
 * never the finger's travel. One per gesture over the slider's own [scale] (the DAC's range).
 *
 * Per change of the value from a to b (tenths of a dB) at most one effect plays, the first that applies:
 * 1. wall: b is at an end of the range, or the drag pushes past it - once, until the value has left that end;
 * 2. zero: b is 0 or the value crossed zero - once, armed again only when |value| >= 0.5 dB (a drag that starts at 0.0 starts disarmed);
 * 3. whole dB: a whole dB other than 0 lies in (a, b] going up or [b, a) going down (a frame that jumps over it counts too);
 * 4. otherwise a grain.
 */
class GainFeel(private val haptics: Haptics, private val scale: Scale) {
    private val loT = (scale.min * 10).roundToInt()
    private val hiT = (scale.max * 10).roundToInt()
    private val limitT = maxOf(abs(loT), abs(hiT)).coerceAtLeast(1)

    private var tenths = 0
    private var zeroArmed = true
    private var wallDone = 0 // the end the wall has played at (-1 low, 1 high) and the value has not left yet, else 0

    /** A drag begins with the gain at [db]: changes are counted from there. */
    fun start(db: Double) {
        tenths = (db * 10).roundToInt()
        zeroArmed = tenths != 0
        wallDone = 0
    }

    /**
     * The drag's event: the gain on screen is [db], [pastEnd] when the drag pushed past an end of the range, [speedDpPerMs] the finger's speed
     * (BandDrag's). Call it on every event that is not a lift-off settle, also when the value did not change (a push against an end).
     */
    fun move(db: Double, pastEnd: Boolean, speedDpPerMs: Float) {
        val b = (db * 10).roundToInt()
        val a = tenths
        tenths = b
        play(a, b, pastEnd, speedDpPerMs.coerceIn(0f, 1f))
        if (abs(b) >= ZERO_REARM_T) zeroArmed = true
    }

    private fun play(a: Int, b: Int, pastEnd: Boolean, v: Float) {
        val end = when {
            b <= loT -> -1
            b >= hiT -> 1
            else -> 0
        }
        if (end == 0) wallDone = 0
        else if (end != wallDone && (a != b || pastEnd)) {
            wallDone = end
            haptics.dragClick(wallScale(v))
            return
        }
        if (b == a) return
        if (zeroArmed && (b == 0 || (a > 0 && b < 0) || (a < 0 && b > 0))) {
            zeroArmed = false
            haptics.dragClick(zeroScale(v))
            return
        }
        val p = abs(b) / limitT.toFloat()
        if (wholeDbBetween(a, b)) haptics.dragTooth(toothScale(p, v)) else haptics.dragGrain(grainScale(p, v))
    }

    companion object {
        /** |value| in tenths from which the zero click is armed again (0.5 dB). */
        private const val ZERO_REARM_T = 5

        /** A whole dB other than 0 in (a, b] going up or [b, a) going down; a and b in tenths. */
        internal fun wholeDbBetween(a: Int, b: Int): Boolean {
            if (b > a) {
                var k = (Math.floorDiv(a, 10) + 1) * 10
                while (k <= b) {
                    if (k != 0) return true
                    k += 10
                }
            } else {
                var k = -Math.floorDiv(-b, 10) * 10
                while (k < a) {
                    if (k != 0) return true
                    k += 10
                }
            }
            return false
        }

        /** The grain (LOW_TICK): [p] = |dB| / the largest |dB| of the range, 0..1; [v] the finger's speed, dp/ms, at most 1. */
        fun grainScale(p: Float, v: Float) = 0.11f + 0.15f * p + 0.08f * v // owner 14.11.2026 after round 1: "trochę mocniejszy" (was 0.08 + 0.14 p)

        /** The tooth at a whole dB (TICK). */
        fun toothScale(p: Float, v: Float) = (0.55f + 0.30f * p + 0.10f * v).coerceAtMost(1f) // was 0.45 + 0.35 p

        /** The click at 0 dB (CLICK). */
        fun zeroScale(v: Float) = (0.70f + 0.30f * v).coerceAtMost(1f) // was 0.60

        /** The click at an end of the range (CLICK). */
        fun wallScale(v: Float) = 0.60f + 0.20f * v // was 0.50
    }
}
