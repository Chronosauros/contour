package io.github.chronosauros.contour.ui.kit

import kotlin.math.abs

/**
 * A DAC settings slider's feel (design: `_Projects/contour-refs/haptics-research-2026-11-14/claude/haptics-report-and-design.md`, "Projekt: suwaki
 * ustawień DAC", round 3): a tooth per step, stronger towards "more", the ends once, a home at 0 for a [centred] slider (balance). Haptics only - it
 * never decides what the slider writes. One per gesture over [min]..[max]. Per change of the integer step from a to b, at most one effect:
 * 1. end: b at min or max, or the drag pushing past it - once, until the value has left that end;
 * 2. home (centred): b is 0 or the value crossed 0 - armed again only when |b| >= [HOME_REARM] steps; a drag that starts at 0 starts disarmed;
 * 3. otherwise one tooth.
 */
class DacFeel(private val haptics: Haptics, private val min: Int, private val max: Int, private val centred: Boolean) {
    private var step = 0
    private var wallDone = 0 // the end the wall has played at (-1 min, 1 max) and the value has not left yet, else 0
    private var homeArmed = true
    private val span = (max - min).coerceAtLeast(1)
    private val reach = maxOf(abs(min), abs(max)).coerceAtLeast(1)

    /** A drag begins with the slider at [value]. */
    fun start(value: Int) {
        step = value
        wallDone = 0
        homeArmed = value != 0
    }

    /**
     * The drag's event: the slider shows [value], [pastEnd] when the finger pushed past an end, [speedDpPerMs] the finger's speed. Call it on every
     * event that is not a lift-off settle, also when the value did not change.
     */
    fun move(value: Int, pastEnd: Boolean, speedDpPerMs: Float) {
        val a = step
        val b = value
        step = b
        play(a, b, pastEnd, speedDpPerMs.coerceIn(0f, 1f))
        if (abs(b) >= HOME_REARM) homeArmed = true
    }

    private fun play(a: Int, b: Int, pastEnd: Boolean, v: Float) {
        val end = when {
            b <= min -> -1
            b >= max -> 1
            else -> 0
        }
        if (end == 0) wallDone = 0
        else if (end != wallDone && (a != b || pastEnd)) {
            wallDone = end
            haptics.dragClick(GainFeel.wallScale(v))
            return
        }
        if (b == a) return
        if (centred && homeArmed && (b == 0 || (a > 0 && b < 0) || (a < 0 && b > 0))) {
            homeArmed = false
            haptics.dragClick(homeScale())
            return
        }
        val pos = if (centred) abs(b) / reach.toFloat() else (b - min) / span.toFloat()
        haptics.dragTooth(stepScale(pos, v))
    }

    companion object {
        /** |step| from which the home click is armed again. */
        const val HOME_REARM = 2

        /** The tooth of a step: [pos] is the position towards "more", 0..1 (a centred slider: |step| / max); [v] the finger's speed, dp/ms, at most 1. */
        fun stepScale(pos: Float, v: Float) = (0.55f + 0.30f * pos.coerceIn(0f, 1f) + 0.10f * v).coerceAtMost(1f)

        /** The click at home (0 of a centred slider). */
        fun homeScale() = 0.70f
    }
}
