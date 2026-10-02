package io.github.chronosauros.contour.ui.kit

import android.util.Log
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import java.util.Locale

/**
 * Keeps a value dragged to an exact spot from jumping when the finger lifts off.
 *
 * A lifting finger's contact patch shrinks and rolls towards the fingertip, so the last move events before
 * the up drift - on the owner's thumb on the Pixel 9 Pro 1.3-3.2 dp in 6-31 ms (26.09), on a fine drag several
 * Hz or tenths of a dB. This is the take-off correction of touch sliders (Apple's jitter-reduction patent
 * US 2011/0074694 likewise leaves the object where it was on lift-off): the guard keeps the last few hundred ms
 * of the drag and, when the finger had rested (still within [REST_SLOP_DP] for [REST_MS]) and then drifted at
 * most [LIFT_SLOP_DP] in at most [LIFT_MS] before the up, gives back the value shown [LEAD_MS] before the drift
 * left the rest (so the drift's first small steps are not kept either). A slow roll inside the rest stays; a
 * finger still travelling when it lifts keeps what it reached. [settling] tells the drag to hold its haptic
 * ticks for the first [TICK_WAIT_MS] of a motion out of a rest, so lifting off a value does not tick.
 *
 * One gesture at a time: [start] at the touch-down, [move] after every applied event, [release] at the up.
 * Positions in px, times from the pointer events (uptimeMillis). Every release logs one line (tag [TAG]).
 */
class LiftGuard<T : Any>(private val density: Float, private val label: String) {
    private class Sample<T>(val t: Long, val p: Offset, val v: T)

    /** Samples up to [index] showed the finger still (within restSlop of trace[index]) during [start, end). */
    private class Rest(val index: Int, val start: Long, val end: Long, val jitter: Float)

    private val restSlop = REST_SLOP_DP * density
    private val liftSlop = LIFT_SLOP_DP * density
    private val trace = ArrayList<Sample<T>>()

    fun start(t: Long, p: Offset, v: T) {
        trace.clear()
        trace.add(Sample(t, p, v))
    }

    /** After an applied event: where the finger is and the value on screen now. */
    fun move(t: Long, p: Offset, v: T) {
        trace.add(Sample(t, p, v))
        // keep one sample at or before the oldest moment a rest check can reach
        val horizon = t - LIFT_MS - REST_MS - LEAD_MS
        var drop = 0
        while (drop + 1 < trace.size && trace[drop + 1].t <= horizon) drop++
        if (drop > 0) trace.subList(0, drop).clear()
    }

    /**
     * True for the first [TICK_WAIT_MS] of a motion out of a rest, while it may still be the lift itself: the
     * drag holds its ticks, so lifting off a value does not tick, and a real move ticks that much later.
     */
    fun settling(now: Long): Boolean {
        val r = rest(now) ?: return false
        return r.index < trace.size - 1 && now - r.end <= TICK_WAIT_MS &&
            (trace.last().p - trace[r.index].p).getDistance() <= liftSlop
    }

    /** At the up (time [now]): the value to put back, or null to keep the one on screen. */
    fun release(now: Long): T? {
        val last = trace.lastOrNull() ?: return null
        val r = rest(now)
        val back: T?
        when {
            r == null -> {
                back = null
                Log.d(TAG, "$label up: kept ${last.v} (no rest in the last $LIFT_MS ms)")
            }
            r.index == trace.size - 1 -> {
                back = null
                Log.d(TAG, "$label up: kept ${last.v} (still at the up, jitter ${dp(r.jitter)} dp)")
            }
            else -> {
                val drift = (last.p - trace[r.index].p).getDistance()
                val held = valueAt(r.end - LEAD_MS)
                back = held.takeIf { drift <= liftSlop && it != last.v }
                Log.d(
                    TAG,
                    "$label up: shown ${last.v}, held $held (rest ${r.end - r.start} ms, jitter ${dp(r.jitter)} dp), " +
                        "lift ${dp(drift)} dp in ${now - r.end} ms -> ${if (back != null) "back to $back" else "kept"}",
                )
            }
        }
        trace.clear()
        return back
    }

    /**
     * The latest moment in the last [LIFT_MS] before [now] at which the finger had been still for [REST_MS]
     * (every position shown in that time within [restSlop] of the one shown at its end).
     */
    private fun rest(now: Long): Rest? {
        val n = trace.size
        for (j in n - 1 downTo 0) {
            val end = if (j == n - 1) now else trace[j + 1].t
            if (end < now - LIFT_MS) return null
            val start = end - REST_MS
            val pj = trace[j].p
            var i = j
            var jitter = 0f
            var still = true
            while (true) {
                val d = (trace[i].p - pj).getDistance()
                if (d > restSlop) { still = false; break }
                if (d > jitter) jitter = d
                if (trace[i].t <= start) break // this sample was on screen when the rest began
                if (i == 0) { still = false; break } // the finger has not been down that long
                i--
            }
            if (still) return Rest(j, start, end, jitter)
        }
        return null
    }

    /** The value on screen at time [t] (the oldest kept one if [t] is before them all). */
    private fun valueAt(t: Long): T {
        for (i in trace.size - 1 downTo 0) if (trace[i].t <= t) return trace[i].v
        return trace[0].v
    }

    private fun dp(px: Float) = String.format(Locale.ROOT, "%.1f", px / density)

    companion object {
        const val TAG = "ContourLift"

        /** A rest: the finger stays this close to one spot... */
        const val REST_SLOP_DP = 1.5f

        /** ...for at least this long. */
        const val REST_MS = 70L

        /** A lift: the up comes at most this long after the rest... */
        const val LIFT_MS = 100L

        /** ...and the finger has drifted at most this far from it (a deliberate move goes further). */
        const val LIFT_SLOP_DP = 10f

        /** The value put back is the one shown this long before the drift left the rest (its first steps). */
        const val LEAD_MS = 20L

        /** Ticks wait this long at the start of a motion out of a rest (covers the lift, not felt as a lag). */
        const val TICK_WAIT_MS = 40L
    }
}

/**
 * detectHorizontalDragGestures plus both ends of the gesture: [onStart] gets the touch-down (the finger has
 * been on the value since then), [onDrag] every move (the first carries the part past the touch slop, like
 * detectHorizontalDragGestures), [onEnd] the time of the up, or null when the drag was cancelled.
 */
suspend fun PointerInputScope.detectHorizontalDragWithEnds(
    onStart: (down: PointerInputChange) -> Unit,
    onEnd: (upTime: Long?) -> Unit,
    onDrag: (change: PointerInputChange, dx: Float) -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    var over = 0f
    val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { ch, o ->
        ch.consume()
        over = o
    } ?: return@awaitEachGesture
    onStart(down)
    onDrag(drag, over)
    val up = horizontalDrag(drag.id) {
        onDrag(it, it.positionChange().x)
        it.consume()
    }
    onEnd(if (up) currentEvent.changes.firstOrNull()?.uptimeMillis else null)
}
