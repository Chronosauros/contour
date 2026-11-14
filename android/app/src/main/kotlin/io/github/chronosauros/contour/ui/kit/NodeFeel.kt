package io.github.chronosauros.contour.ui.kit

import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.min

/**
 * The feel of a node dragged on the response graph (design: `_Projects/contour-refs/haptics-research-2026-11-14/claude/haptics-report-and-design.md`,
 * "Projekt: węzeł na wykresie", round 2): the node rides on paper with a grid. One per gesture, over the node's quantized values and the DAC's
 * ranges ([freqScale], [gainScale]); [gainLo]..[gainHi] is the graph's gain range (its horizontal lines, [gridDb]). Per event at most one effect:
 * 1. edge: f or g at an end of its scale - once per end, until the value has left it;
 * 2. 0 dB line: g reaches 0.0 or crosses zero - armed again only when |g| >= 0.5 dB (a drag that starts at 0.0 starts disarmed);
 * 3. a vertical [GRID_F] line crossed by f, or a horizontal line other than 0 crossed by g - the strongest;
 * 4. texture (owner 14.11.2026: "Z fakturą"): EQ Sweep's tape texture, a pack of [PACK_TICKS] LOW_TICKs every [PACK_STEP] of the graph's width
 *    of finger travel, never before the last pack has played out and never within [PACK_AFTER_MS] after a tooth or click.
 * The finger's speed is estimated here from the travel it is given ([step]).
 */
class NodeFeel(
    private val haptics: Haptics,
    private val freqScale: Scale,
    private val gainScale: Scale,
    gainLo: Double,
    gainHi: Double,
    private val widthPx: Float,
    private val density: Float,
) {
    private val lines = gridDb(gainLo, gainHi).filter { abs(it) > 1e-9 }
    private val limitDb = maxOf(abs(gainLo), abs(gainHi), 1.0)

    private var f = 0.0
    private var g = 0.0
    private var speed = 0f // dp/ms, smoothed like BandDrag (0.6 old + 0.4 new)
    private var zeroArmed = true
    private var fDone = 0
    private var gDone = 0
    private var travelPx = 0f
    private var packAt = 0L // no pack before this time

    /** The node is grabbed at ([hz], [db]): changes are counted from there. */
    fun start(hz: Double, db: Double) {
        f = hz
        g = db
        speed = 0f
        zeroArmed = Math.round(db * 10) != 0L
        fDone = 0
        gDone = 0
        travelPx = 0f
        packAt = 0L
    }

    /**
     * One event of the drag: the node is at ([hz], [db]) after quantization, the finger moved [movePx] px in [dtMs] ms, [pushedF] / [pushedG] when the
     * finger pushed past the plot's or the range's edge. [silent] while the finger lifts ([LiftGuard.settling]): the speed is still followed, nothing plays.
     */
    fun step(hz: Double, db: Double, pushedF: Boolean, pushedG: Boolean, movePx: Float, dtMs: Long, silent: Boolean) {
        speed = 0.6f * speed + 0.4f * (movePx / density / dtMs.coerceAtLeast(1L))
        if (silent) return
        val v = min(speed, 1f)
        val fa = f
        val ga = g
        f = hz
        g = db
        travelPx += movePx
        val now = SystemClock.uptimeMillis()
        val endF = when {
            hz <= freqScale.min + EPS -> -1
            hz >= freqScale.max - EPS -> 1
            else -> 0
        }
        val endG = when {
            db <= gainScale.min + EPS -> -1
            db >= gainScale.max - EPS -> 1
            else -> 0
        }
        var edge = false
        if (endF == 0) fDone = 0 else if (endF != fDone && (hz != fa || pushedF)) { fDone = endF; edge = true }
        if (endG == 0) gDone = 0 else if (endG != gDone && (db != ga || pushedG)) { gDone = endG; edge = true }
        val ga10 = Math.round(ga * 10)
        val g10 = Math.round(db * 10)
        val zero = zeroArmed && g10 != ga10 && (g10 == 0L || (ga10 > 0 && g10 < 0) || (ga10 < 0 && g10 > 0))
        if (zero && !edge) zeroArmed = false
        if (abs(g10) >= 5) zeroArmed = true
        when {
            edge -> { haptics.dragClick(GainFeel.wallScale(v)); played(now) }
            zero -> { haptics.dragClick(GainFeel.zeroScale(v)); played(now) }
            else -> {
                var tooth = 0f
                if (hz != fa) for (line in GRID_F) if (reaches(line, fa, hz)) tooth = maxOf(tooth, if (isDecadeLine(line)) TOOTH_DECADE else TOOTH_LINE)
                if (db != ga && lines.any { reaches(it, ga, db) }) tooth = maxOf(tooth, horizontalScale((abs(db) / limitDb).toFloat()))
                if (tooth > 0f) { haptics.dragTooth(tooth); played(now) } else packIfDue(now, v)
            }
        }
    }

    private fun played(now: Long) {
        travelPx = 0f
        packAt = now + PACK_AFTER_MS
    }

    private fun packIfDue(now: Long, v: Float) {
        if (travelPx < PACK_STEP * widthPx || now < packAt) return
        travelPx = 0f
        packAt = now + PACK_MS
        haptics.dragGrain(packScale(v), count = PACK_TICKS)
    }

    companion object {
        private const val EPS = 1e-9

        /** A pack's LOW_TICKs (EQ Sweep's DialHaptics: 5, back to back). */
        const val PACK_TICKS = 5

        /** The width of the graph's finger travel between two packs (DialHaptics: 1.5 % of the tape). */
        const val PACK_STEP = 0.015f

        private const val PACK_MS = 60L        // 5 LOW_TICKs of 12 ms: the next pack waits until this one has played out
        private const val PACK_AFTER_MS = 40L  // and waits this long after a tooth or a click
        const val TOOTH_DECADE = 0.95f         // 100 Hz, 1 kHz, 10 kHz
        const val TOOTH_LINE = 0.75f           // another vertical line

        /** The tooth at a horizontal 6 dB line; [p] = |dB| / the largest |dB| of the graph, 0..1. */
        fun horizontalScale(p: Float) = (0.75f + 0.20f * p.coerceIn(0f, 1f)).coerceAtMost(1f)

        /** A pack of the texture (DialHaptics' burst: 0.15 + 0.20 v squared); [v] is the finger's speed, dp/ms, at most 1. */
        fun packScale(v: Float) = 0.15f + 0.20f * v * v
    }
}
