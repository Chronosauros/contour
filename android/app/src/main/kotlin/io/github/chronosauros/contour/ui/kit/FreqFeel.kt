package io.github.chronosauros.contour.ui.kit

/**
 * FREQ's feel (design: `_Projects/contour-refs/haptics-research-2026-11-14/claude/haptics-report-and-design.md`, "Projekt: FREQ", round 2): the hertz
 * moving under the finger. A grain on every change of the Hz readout with the colour of its register, a tooth at every vertical line of the
 * graph's grid ([GRID_F]), a wall at the ends of the slider's [scale] (the DAC's range). The trigger is the quantized value, never the finger's travel.
 * One per gesture. Per change from a to b at most one effect, the first that applies:
 * 1. wall: b at an end, or the drag pushing past it - once, until the value has left that end;
 * 2. line: a grid line lies in (a, b] going up or [b, a) going down (a frame that jumps over it counts too) - the strongest one;
 * 3. grain by the register of b: below 200 Hz two LOW_TICKs, 200 Hz - 2 kHz one LOW_TICK, above 2 kHz one TICK; the same strength all the way
 *    between two lines (owner 14.11.2026: "Równe", flat).
 */
class FreqFeel(private val haptics: Haptics, private val scale: Scale) {
    private var hz = 0.0
    private var wallDone = 0 // the end the wall has played at (-1 low, 1 high) and the value has not left yet, else 0

    /** A drag begins with FREQ at [value] Hz: changes are counted from there. */
    fun start(value: Double) {
        hz = value
        wallDone = 0
    }

    /**
     * The drag's event: FREQ on screen is [value] Hz, [pastEnd] when the drag pushed past an end, [speedDpPerMs] the finger's speed (BandDrag's).
     * Call it on every event that is not a lift-off settle, also when the value did not change (a push against an end).
     */
    fun move(value: Double, pastEnd: Boolean, speedDpPerMs: Float) {
        val a = hz
        val b = value
        hz = b
        val v = speedDpPerMs.coerceIn(0f, 1f)
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
        var line = 0
        for (f in GRID_F) if (reaches(f, a, b)) line = maxOf(line, if (isDecadeLine(f)) 2 else 1)
        if (line > 0) {
            haptics.dragTooth(lineScale(line == 2, v))
            return
        }
        val s = when {
            b < 200.0 -> grainLowScale(v)
            b <= 2000.0 -> grainMidScale(v)
            else -> grainHighScale(v)
        }
        when {
            b < 200.0 -> haptics.dragGrain(s, count = 2)
            b <= 2000.0 -> haptics.dragGrain(s)
            else -> haptics.dragGrain(s, crisp = true)
        }
    }

    companion object {
        private const val EPS = 1e-9

        /** The grain below 200 Hz (two LOW_TICKs): [v] is the finger's speed, dp/ms, at most 1. */
        fun grainLowScale(v: Float) = 0.12f + 0.03f * v

        /** The grain from 200 Hz to 2 kHz (one LOW_TICK). */
        fun grainMidScale(v: Float) = 0.12f + 0.03f * v

        /** The grain above 2 kHz (one TICK). */
        fun grainHighScale(v: Float) = 0.09f + 0.03f * v

        /** The tooth at a grid line; [decade]: 100 Hz, 1 kHz, 10 kHz. */
        fun lineScale(decade: Boolean, v: Float) = if (decade) (0.90f + 0.10f * v).coerceAtMost(1f) else 0.70f + 0.10f * v
    }
}
