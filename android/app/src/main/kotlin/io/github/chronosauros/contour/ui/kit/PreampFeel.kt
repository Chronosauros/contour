package io.github.chronosauros.contour.ui.kit

import kotlin.math.abs

/**
 * The MANUAL preamp drag's feel (design: `_Projects/contour-refs/haptics-research-2026-11-14/claude/haptics-report-and-design.md`, "Projekt: PREAMP",
 * round 3): steps like EQ Sweep's LEVEL cells - only teeth, stronger the louder (closer to 0 dB), a wall at the ends of the drag's range. The trigger
 * is the value the readout shows (after the step's floor), never the finger's travel. One per gesture. Per change from a to b, at most one effect:
 * 1. end: b at the bottom or the top of the range, or the drag pushing past it - once, until the value has left that end;
 * 2. otherwise one tooth, also when the value jumped several steps.
 */
class PreampFeel(private val haptics: Haptics) {
    private var db = 0.0
    private var wallDone = 0 // the end the wall has played at (-1 bottom, 1 top) and the value has not left yet, else 0

    /** A drag begins with the preamp at [value] dB. */
    fun start(value: Double) {
        db = value
        wallDone = 0
    }

    /**
     * The drag's event: the preamp on screen is [value] dB over [lo]..[hi] (this drag's range), [pastEnd] when the finger pushed past an end,
     * [speedDpPerMs] the finger's speed. Call it on every event that is not a lift-off settle, also when the value did not change.
     */
    fun move(value: Double, lo: Double, hi: Double, pastEnd: Boolean, speedDpPerMs: Float) {
        val a = db
        val b = value
        db = b
        val v = speedDpPerMs.coerceIn(0f, 1f)
        val end = when {
            b <= lo + EPS -> -1
            b >= hi - EPS -> 1
            else -> 0
        }
        if (end == 0) wallDone = 0
        else if (end != wallDone && (a != b || pastEnd)) {
            wallDone = end
            haptics.dragClick(GainFeel.wallScale(v))
            return
        }
        if (b == a) return
        val g = if (lo < 0.0) (1.0 - abs(b) / abs(lo)).toFloat() else 1f
        haptics.dragTooth(toothScale(g, v))
    }

    companion object {
        private const val EPS = 1e-9

        /** The tooth of a step: [g] = 1 - |dB| / |the bottom of the range|, 0 at the bottom, 1 at 0 dB; [v] the finger's speed, dp/ms, at most 1. */
        fun toothScale(g: Float, v: Float) = (0.60f + 0.30f * g.coerceIn(0f, 1f) + 0.10f * v).coerceAtMost(1f)
    }
}
