package io.github.chronosauros.contour.ui.kit

import kotlin.math.abs

/**
 * Q's feel (design: `_Projects/contour-refs/haptics-research-2026-11-14/claude/haptics-report-and-design.md`, "Projekt: Q i szczypanie", round 4): a rubber
 * band - narrowing (Q up) tightens, widening loosens; [Scale.reset] (0.71) is home. Used by the Q slider and by the pinch on the response graph. The
 * trigger is the quantized Q (0.01, what the readout shows), never the finger's travel. One per gesture over [scale]. Per change from a to b, at most
 * one effect:
 * 1. wall: b at an end of the scale, or the drag pushing past it - once, until the value has left that end;
 * 2. home: b reaches 0.71 or passes through it - once, armed again only when |b - 0.71| >= [HOME_REARM]; a drag that starts at 0.71 starts disarmed;
 * 3. doubling: 0.5, 1, 2, 4, 8 (16) lies in (a, b] going up or [b, a) going down (a jump over it counts too) - a tooth;
 * 4. otherwise a grain, firmer the narrower the Q.
 */
class QFeel(private val haptics: Haptics, private val scale: Scale) {
    private val home = scale.reset?.takeIf { it in scale.min..scale.max }
    private val doublings = DOUBLINGS.filter { it in scale.min..scale.max }

    private var q = 0.0
    private var wallDone = 0 // the end the wall has played at (-1 low, 1 high) and the value has not left yet, else 0
    private var homeArmed = true

    /** A drag or a pinch begins with Q at [value]. */
    fun start(value: Double) {
        q = value
        wallDone = 0
        homeArmed = home == null || abs(value - home) > EPS
    }

    /**
     * The event: Q on screen is [value], [pastEnd] when the drag pushed past an end, [speedDpPerMs] the finger's speed (the pinch: the rate of change of the
     * distance between the fingers). Call it on every event that is not a lift-off settle, also when the value did not change.
     */
    fun move(value: Double, pastEnd: Boolean, speedDpPerMs: Float) {
        val a = q
        val b = value
        q = b
        play(a, b, pastEnd, speedDpPerMs.coerceIn(0f, 1f))
        if (home != null && abs(b - home) >= HOME_REARM - EPS) homeArmed = true
    }

    private fun play(a: Double, b: Double, pastEnd: Boolean, v: Float) {
        val end = when {
            b <= scale.min + EPS -> -1
            b >= scale.max - EPS -> 1
            else -> 0
        }
        if (end == 0) wallDone = 0
        else if (end != wallDone && (a != b || pastEnd)) {
            wallDone = end
            haptics.dragClick(GainFeel.wallScale(v))
            return
        }
        if (b == a) return
        if (home != null && homeArmed && reaches(home, a, b)) {
            homeArmed = false
            haptics.dragClick(homeScale(v))
            return
        }
        val qp = scale.toPos(b)
        if (doublings.any { reaches(it, a, b) }) haptics.dragTooth(toothScale(qp, v)) else haptics.dragGrain(grainScale(qp, v))
    }

    companion object {
        private const val EPS = 1e-9
        private val DOUBLINGS = doubleArrayOf(0.5, 1.0, 2.0, 4.0, 8.0, 16.0)

        /** Q's distance from home at which the home click is armed again. */
        const val HOME_REARM = 0.10

        /** The grain (LOW_TICK): [qp] is the log position of Q in the scale, 0..1; [v] the finger's speed, dp/ms, at most 1. */
        fun grainScale(qp: Float, v: Float) = 0.11f + 0.16f * qp + 0.06f * v

        /** The tooth at a doubling (TICK). */
        fun toothScale(qp: Float, v: Float) = (0.55f + 0.30f * qp + 0.10f * v).coerceAtMost(1f)

        /** The click at home, 0.71 (CLICK). */
        fun homeScale(v: Float) = (0.70f + 0.30f * v).coerceAtMost(1f)
    }
}
