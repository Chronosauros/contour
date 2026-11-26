package io.github.chronosauros.contour.ui.library

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.chronosauros.contour.ui.kit.Haptics
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * REORDER PROFILES (owner 16.11.2026, `_Projects/contour-refs/library-redesign-2026-11-16/` SPEC 9c-9g, prototype `prototype-src/core2.js`
 * class Reorder with physics and `style.css` D): the block comes apart top-down into cards that lift 3 dp with one settle (animation 3: an
 * overshoot and one damped wobble, then still, only the shadow breathes); a card held 150 ms follows the finger on a short spring, tilts while it
 * moves and stretches with speed; its neighbours make room on an overshooting curve with a small squash; the drop overshoots into the slot, lands
 * with a squash and sends a ripple through the cards around it. The numbers are the prototype's (CSS px = dp, ms), the owner judges the feel.
 * The entry settle plays once, when the mode starts - never on a list change (the owner rejected that "second jump", 16.11.2026).
 */

/** The motion of one active row in REORDER, written by [Reorder.tick] every frame and read by the row only in its layer, draw and layout lambdas. */
@Stable
internal class RowFx {
    /** 0 = glued into the block, 1 = a card of its own (8 dp gap, the Radii.L card corners, the RAISED shadow, 3 dp up). */
    var apart by mutableFloatStateOf(0f)
    /** Everything vertical, in px: the room made for the dragged card, the settle, a ripple, the lift, the dragged card's own travel. */
    var dy by mutableFloatStateOf(0f)
    /** Degrees. */
    var rot by mutableFloatStateOf(0f)
    var sx by mutableFloatStateOf(1f)
    var sy by mutableFloatStateOf(1f)
    /** Where across the card it turns (the finger of the dragged card), 0..1. */
    var pivotX by mutableFloatStateOf(0.5f)
    /** The card's shadow from RAISED (0) to HERO (1). */
    var shadow by mutableFloatStateOf(0f)
    /** The dragged card's lighter surface (surface2), 0..1. */
    var lifted by mutableFloatStateOf(0f)
    /** Above its neighbours while dragged or flying into its slot. */
    var z by mutableFloatStateOf(0f)

    // the room made for the dragged card: a tween that restarts from where it is when its target moves (a CSS transition)
    internal var dispFrom = 0f
    internal var dispTo = 0f
    internal var dispStart = 0L
    internal var dispMs = 360
    internal var dispEase: Easing = NEIGHBOUR
    // one keyframe reaction at a time (the prototype's bumpRc): a ripple (y) or a squash (scale)
    internal var bump = NONE
    internal var bumpStart = 0L
    internal var bumpAmp = 0f
    internal var slot = -1

    internal fun disp(now: Long): Float {
        val p = ((now - dispStart).toFloat() / dispMs).coerceIn(0f, 1f)
        return dispFrom + (dispTo - dispFrom) * dispEase.transform(p)
    }

    internal fun retarget(now: Long, to: Float, ms: Int = 360, ease: Easing = NEIGHBOUR) {
        if (to == dispTo && ms == dispMs) return
        dispFrom = disp(now)
        dispTo = to
        dispStart = now
        dispMs = ms
        dispEase = ease
    }

    internal fun rest() {
        dispFrom = 0f; dispTo = 0f; dispStart = 0L; bump = NONE; slot = -1
        apart = 0f; dy = 0f; rot = 0f; sx = 1f; sy = 1f; pivotX = 0.5f; shadow = 0f; lifted = 0f; z = 0f
    }

    companion object {
        const val NONE = 0
        const val RIPPLE = 1
        const val SQUASH = 2
        const val LAND = 3
    }
}

/**
 * The REORDER mode of the library block: [enter] / [leave] start the transitions, the row gestures call [lift], [move], [drop] and [cancel], and
 * [tick] runs every frame while [running]. [onMove] commits a drop to the library (the model's order); it is called inside a frame, in the same
 * frame as the room made for the card is taken back, so nothing jumps when the rows take their new places.
 */
internal class Reorder(private val onMove: (id: String, to: Int) -> Unit) {
    /** px per dp. */
    var density = 1f
    /** The active profiles in the block's order (set by the composition on every pass). */
    var ids: List<String> = emptyList()
    /** Android's "remove animations": no settle, ripple, squash, tilt, stretch or breathing; the cards still part and make room. */
    var calm = false
    var haptics: Haptics? = null

    /** Whether the frame loop has anything to do. */
    var running by mutableStateOf(false)
        private set
    /** A finger holds a card (lifted, dragged, or flying into its slot). */
    var busy by mutableStateOf(false)
        private set

    private val fx = HashMap<String, RowFx>()
    fun fx(id: String): RowFx = fx.getOrPut(id) { RowFx() }

    private var now = 0L
    private var lastTick = 0L
    private var enterAt = -1L
    private var leaveAt = -1L
    private var enterPending = false
    private var leavePending = false

    // the dragged card
    private var dragId: String? = null
    private var from = 0
    private var target = 0
    private var x0 = 0f
    private var y0 = 0f
    private var fingerX = 0f
    private var fingerY = 0f
    private var lastFingerX = 0f
    private var grabbed = false
    private var ty = 0f
    private var cy = 0f
    private var vy = 0f
    private var fvx = 0f
    private var tilt = 0f
    private var scrolled = 0f
    private var grabFrac = 0.5f
    private var liftAt = 0L
    private var neighbourSlot = IntArray(0)
    // the drop
    private var flying = false
    private var flyFrom = 0f
    private var flyTo = 0f
    private var flyStart = 0L
    private var flyMs = 420
    private var flyEase: Easing = DROP
    private var flyCommit = false
    private var landTickAt = -1L

    private fun dp(v: Float) = v * density
    private val pitch get() = dp(ROW_DP + GAP_DP)

    fun enter() {
        enterPending = true
        leavePending = false
        running = true
    }

    fun leave() {
        if (!running && enterAt < 0) return
        if (dragId != null) cancel()
        leavePending = true
        enterPending = false
        running = true
    }

    // ---------------------------------------------------------------------------------------------- the finger

    /**
     * A card held for [HOLD_MS] at ([x], [y]) in window px; [left] and [width] are the card's in window px. False when it cannot come up (another card is
     * still held or flying into its slot): the gesture then leaves the touch to the list instead of holding it to its end (review 18.11.2026).
     */
    fun lift(id: String, x: Float, y: Float, left: Float, width: Float): Boolean {
        if (dragId != null || flying) return false
        val i = ids.indexOf(id)
        if (i < 0) return false
        dragId = id
        busy = true
        from = i
        target = i
        x0 = x; y0 = y; fingerX = x; fingerY = y; lastFingerX = x
        grabbed = false
        ty = 0f; cy = 0f; vy = 0f; fvx = 0f; tilt = 0f; scrolled = 0f
        grabFrac = if (width > 0f) ((x - left) / width).coerceIn(0f, 1f) else 0.5f
        liftAt = now
        neighbourSlot = IntArray(ids.size) { it }
        fx(id).also { it.z = 1f; it.pivotX = grabFrac; it.bump = RowFx.NONE }
        // the two neighbours flinch away as the card comes up
        if (!calm) {
            ids.getOrNull(i - 1)?.let { bump(it, RowFx.RIPPLE, -2.5f, now + 30) }
            ids.getOrNull(i + 1)?.let { bump(it, RowFx.RIPPLE, 2.5f, now + 30) }
        }
        haptics?.longPress()
        return true
    }

    fun move(x: Float, y: Float) {
        if (dragId == null || flying) return
        fingerX = x
        fingerY = y
        if (!grabbed && hypot(x - x0, y - y0) > dp(GRAB_SLOP_DP)) grabbed = true
        if (grabbed) follow()
    }

    private fun follow() {
        val n = ids.size
        ty = (fingerY - y0 + scrolled).coerceIn(-from * pitch, (n - 1 - from) * pitch)
        val t = (from + (ty / pitch).roundToInt()).coerceIn(0, n - 1)
        if (t != target) {
            target = t
            makeRoom()
            haptics?.dragTooth(0.45f)
        }
    }

    /** Every other card takes the slot it will have once the dragged card lands at [target]. */
    private fun makeRoom() {
        ids.forEachIndexed { i, id ->
            if (i == from) return@forEachIndexed
            val ni = shifted(i)
            val f = fx(id)
            f.retarget(now, (ni - i) * pitch)
            if (neighbourSlot.getOrNull(i) != ni) {
                if (i < neighbourSlot.size) neighbourSlot[i] = ni
                if (!calm) bump(id, RowFx.SQUASH, 0f, now)
            }
        }
    }

    private fun shifted(i: Int): Int = when {
        from < target && i > from && i <= target -> i - 1
        from > target && i >= target && i < from -> i + 1
        else -> i
    }

    /** The finger let go: the card flies into its slot (or back into its own), lands, the cards around it ripple; the order is saved when it arrives. */
    fun drop() {
        val id = dragId ?: return
        if (flying) return
        flying = true
        flyFrom = cy
        flyTo = (target - from) * pitch
        flyStart = now
        flyMs = 420
        flyEase = DROP
        flyCommit = true
        haptics?.dragClick(0.8f)
        if (!calm) {
            bump(id, RowFx.LAND, 0f, now + 150)
            // the cards that will stand next to it after the move
            listOf(Triple(target - 1, -3f, 170L), Triple(target + 1, 3f, 170L), Triple(target - 2, -1.4f, 220L), Triple(target + 2, 1.4f, 220L))
                .forEach { (slot, amp, delay) -> rowAt(slot)?.let { bump(it, RowFx.RIPPLE, amp, now + delay) } }
        }
        landTickAt = now + 170
    }

    /** The card in flight is in its slot: a drop's move goes to the library and the room made for the card is taken back. */
    private fun land() {
        val arrived = dragId ?: return
        if (flyCommit && target != from) {
            onMove(arrived, target)
            ids = ids.toMutableList().apply { add(target, removeAt(from)) }
        }
        ids.forEach { val o = fx(it); o.dispFrom = 0f; o.dispTo = 0f; o.dispStart = 0L }
        dragId = null
        flying = false
        busy = false
    }

    /**
     * A card still flying into its slot lands now, its move saved: the app goes to the background (frames stop, and the state written on the way out
     * would have the old order - a process killed there lost the move) or the library leaves the screen (review 18.11.2026).
     */
    fun landNow() {
        if (dragId != null && flying) land()
    }

    /** The drag ends without a move (back, leaving the mode): everything slides home. */
    fun cancel() {
        val id = dragId ?: return
        if (flying) return
        ids.forEachIndexed { i, other -> if (i != from) fx(other).retarget(now, 0f, 180, STANDARD) }
        flying = true
        flyFrom = cy
        flyTo = 0f
        flyStart = now
        flyMs = 180
        flyEase = STANDARD
        flyCommit = false
        fx(id).bump = RowFx.NONE
    }

    /** The id of the card that stands at [slot] once the dragged one is in [target]. */
    private fun rowAt(slot: Int): String? {
        if (slot < 0 || slot >= ids.size) return null
        ids.forEachIndexed { i, id -> if (i != from && shifted(i) == slot) return id }
        return null
    }

    private fun bump(id: String, kind: Int, amp: Float, at: Long) {
        val f = fx(id)
        f.bump = kind
        f.bumpStart = at
        f.bumpAmp = amp
    }

    // ---------------------------------------------------------------------------------------------- every frame

    /**
     * One frame at [frameMs]. [scrollBy] scrolls the list by px and returns what it scrolled; [top] and [bottom] are the window y of the list's
     * visible band (under the status bar, over the page bar): a dragged card near them scrolls the list.
     */
    fun tick(frameMs: Long, scrollBy: (Float) -> Float, top: Float, bottom: Float) {
        now = frameMs
        val dt = if (lastTick == 0L) 16.7f else (frameMs - lastTick).toFloat().coerceIn(1f, 64f)
        lastTick = frameMs
        if (enterPending) {
            enterPending = false
            // entering again while the cards were still gluing back: go on from where they are
            enterAt = if (leaveAt >= 0) frameMs - partedFor(frameMs) else frameMs
            leaveAt = -1L
        }
        if (leavePending) {
            leavePending = false
            leaveAt = if (enterAt >= 0) frameMs - (SEPARATE_MS - minOf(SEPARATE_MS.toLong(), frameMs - enterAt)).coerceAtLeast(0) else frameMs
        }
        // the dropped card has arrived: the order is saved and the room made for it is taken back before this frame's rows are placed, so the
        // rows stand in their new places with nothing left to move - no jump
        if (dragId != null && flying && frameMs - flyStart >= flyMs) land()
        val dragged = dragId
        if (dragged != null && !flying) {
            if (grabbed) {
                // the card follows the finger on a short spring; it tilts with the hand's sideways speed and with its own vertical speed around the
                // finger's place on it, and stretches with speed
                val k = 1f - exp(-dt / FOLLOW_MS)
                val ny = cy + (ty - cy) * k
                val v = (ny - cy) / dt / density
                vy += (v - vy) * 0.35f
                cy = ny
                val hv = (fingerX - lastFingerX) / dt / density
                fvx += (hv - fvx) * 0.3f
                val off = (grabFrac - 0.5f).coerceIn(-0.5f, 0.5f)
                val rt = (fvx * 1.6f - vy * off * 3.2f).coerceIn(-MAX_TILT, MAX_TILT)
                tilt += (rt - tilt) * (1f - exp(-dt / TILT_MS))
                // the edges of the list scroll it
                val edge = dp(EDGE_DP)
                var s = when {
                    fingerY < top + edge -> -dp(SCROLL_DP) * min(1f, (top + edge - fingerY) / edge)
                    fingerY > bottom - edge -> dp(SCROLL_DP) * min(1f, (fingerY - (bottom - edge)) / edge)
                    else -> 0f
                }
                s *= dt / 16.7f
                if (s != 0f) {
                    scrolled += scrollBy(s)
                    follow()
                }
            }
            lastFingerX = fingerX
        }
        if (landTickAt in 1..frameMs) {
            landTickAt = -1L
            haptics?.dragTooth(0.6f)
        }

        val n = ids.size
        var settled = leaveAt >= 0
        ids.forEachIndexed { i, id ->
            val f = fx(id)
            val a = parted(frameMs, i)
            if (a > 0f) settled = false
            f.apart = a
            // the lift and the entry settle (y in dp, degrees)
            var y = -3f * a
            var rot = 0f
            val sp = (frameMs - enterAt - SETTLE_STAGGER * i).toFloat() / SETTLE_MS
            if (!calm && enterAt >= 0 && leaveAt < 0 && sp < 1f) {
                y = SETTLE_Y.at(sp)
                rot = SETTLE_R.at(sp)
            }
            // the reactions
            var sx = 1f
            var sy = 1f
            if (!calm && f.bump != RowFx.NONE) {
                val bt = frameMs - f.bumpStart
                when (f.bump) {
                    RowFx.RIPPLE -> if (bt in 0 until RIPPLE_MS) y += RIPPLE.at(bt.toFloat() / RIPPLE_MS) * f.bumpAmp
                    RowFx.SQUASH -> if (bt in 0 until SQUASH_MS) { val p = bt.toFloat() / SQUASH_MS; sx = SQUASH_X.at(p); sy = SQUASH_Y.at(p) }
                    RowFx.LAND -> if (bt in 0 until LAND_MS) { val p = bt.toFloat() / LAND_MS; sx = LAND_X.at(p); sy = LAND_Y.at(p) }
                }
                if (bt >= BUMP_MAX_MS) f.bump = RowFx.NONE
            }
            // the shadow breathes once the card has settled
            var shadow = 0f
            if (!calm && enterAt >= 0 && leaveAt < 0) {
                val bp = (frameMs - enterAt - SETTLE_MS - SETTLE_STAGGER * i).toFloat() / BREATHE_MS
                if (bp > 0f) {
                    val c = floor(bp).toInt()
                    val fr = bp - c
                    shadow = BREATHE.transform(if (c % 2 == 0) fr else 1f - fr)
                }
            }
            if (id == dragged) {
                val lp = ((frameMs - liftAt).toFloat() / PICK_MS).coerceIn(0f, 1f)
                var scale = 1f + 0.03f * if (calm) 1f else SPRINGY.transform(lp)
                var stretch = 0f
                var lifted = 1f
                var cardY = cy
                if (flying) {
                    val fp = ((frameMs - flyStart).toFloat() / flyMs).coerceIn(0f, 1f)
                    cardY = flyFrom + (flyTo - flyFrom) * flyEase.transform(fp)
                    val sinceDrop = frameMs - flyStart
                    scale = 1f + 0.03f * (1f - if (calm) 1f else SPRINGY.transform((sinceDrop.toFloat() / PICK_MS).coerceIn(0f, 1f)))
                    lifted = 1f - (sinceDrop.toFloat() / 300f).coerceIn(0f, 1f)
                    tilt *= 0.8f
                    if (f.bump == RowFx.LAND && frameMs >= f.bumpStart) scale = 1f
                } else if (grabbed && !calm) {
                    stretch = (abs(vy) * 0.03f).coerceIn(0f, 0.022f)
                }
                f.dy = cardY + dp(y)
                f.rot = if (calm) 0f else tilt
                f.sx = scale * sx * (1f - stretch * 0.45f)
                f.sy = scale * sy * (1f + stretch)
                f.shadow = lifted.coerceAtLeast(shadow)
                f.lifted = lifted
                f.z = 1f
            } else {
                f.dy = f.disp(frameMs) + dp(y)
                f.rot = rot
                f.sx = sx
                f.sy = sy
                f.shadow = shadow
                f.lifted = 0f
                f.z = 0f
            }
        }

        if (settled && dragId == null && frameMs - leaveAt > SEPARATE_MS + SEPARATE_STAGGER * n + 40) {
            fx.values.forEach { it.rest() }
            enterAt = -1L
            leaveAt = -1L
            lastTick = 0L
            running = false
        }
    }

    /** 0..1: how far row [i] has come apart, with the stagger down the list. */
    private fun parted(frameMs: Long, i: Int): Float = when {
        leaveAt >= 0 -> 1f - SEPARATE.transform(((frameMs - leaveAt - SEPARATE_STAGGER * i).toFloat() / SEPARATE_MS).coerceIn(0f, 1f))
        enterAt >= 0 -> SEPARATE.transform(((frameMs - enterAt - SEPARATE_STAGGER * i).toFloat() / SEPARATE_MS).coerceIn(0f, 1f))
        else -> 0f
    }

    /** How long the first row has been apart when the mode is entered again during the glue-back. */
    private fun partedFor(frameMs: Long): Long = (SEPARATE_MS - (frameMs - leaveAt)).coerceIn(0, SEPARATE_MS.toLong())

    /** The id of the active card at ([x], [y]) in window px, from the rows' window bounds. */
    fun cardAt(at: (String) -> androidx.compose.ui.geometry.Rect?, x: Float, y: Float): String? =
        ids.firstOrNull { id -> at(id)?.let { y >= it.top && y < it.bottom && x >= it.left && x < it.right } == true }

    companion object {
        const val HOLD_MS = 150L
        const val ROW_DP = 64f
        const val GAP_DP = 8f
        private const val GRAB_SLOP_DP = 4f
        private const val FOLLOW_MS = 42f
        private const val TILT_MS = 90f
        private const val MAX_TILT = 1.4f
        private const val EDGE_DP = 72f
        private const val SCROLL_DP = 16f
        const val SEPARATE_MS = 300
        const val SEPARATE_STAGGER = 22L
        private const val SETTLE_MS = 700L
        private const val SETTLE_STAGGER = 35L
        private const val BREATHE_MS = 2800f
        private const val PICK_MS = 340f
        private const val RIPPLE_MS = 480L
        private const val SQUASH_MS = 400L
        private const val LAND_MS = 440L
        private const val BUMP_MAX_MS = 480L

        /** The curves of the prototype (CSS cubic-bezier). */
        val SEPARATE = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
        private val STANDARD = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
        private val SPRINGY = CubicBezierEasing(0.34f, 1.6f, 0.64f, 1f)
        private val DROP = CubicBezierEasing(0.34f, 1.38f, 0.64f, 1f)
        private val SETTLE_EASE = CubicBezierEasing(0.3f, 0.7f, 0.3f, 1f)
        private val EASE_OUT = CubicBezierEasing(0f, 0f, 0.58f, 1f)
        private val BREATHE = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

        private val SETTLE_Y = Keys(floatArrayOf(0f, 0.35f, 0.6f, 0.8f, 1f), floatArrayOf(0f, -7f, -2f, -3.5f, -3f), SETTLE_EASE)
        private val SETTLE_R = Keys(floatArrayOf(0f, 0.35f, 0.6f, 0.8f, 1f), floatArrayOf(0f, -0.35f, 0.25f, -0.1f, 0f), SETTLE_EASE)
        private val RIPPLE = Keys(floatArrayOf(0f, 0.3f, 0.65f, 1f), floatArrayOf(0f, 1f, -0.35f, 0f), SETTLE_EASE)
        private val SQUASH_X = Keys(floatArrayOf(0f, 0.35f, 0.7f, 1f), floatArrayOf(1f, 1.006f, 0.998f, 1f), EASE_OUT)
        private val SQUASH_Y = Keys(floatArrayOf(0f, 0.35f, 0.7f, 1f), floatArrayOf(1f, 0.975f, 1.008f, 1f), EASE_OUT)
        private val LAND_X = Keys(floatArrayOf(0f, 0.4f, 0.72f, 1f), floatArrayOf(1.03f, 1.012f, 0.996f, 1f), EASE_OUT)
        private val LAND_Y = Keys(floatArrayOf(0f, 0.4f, 0.72f, 1f), floatArrayOf(1.03f, 0.972f, 1.01f, 1f), EASE_OUT)
    }
}

/** The room the neighbours make, as the prototype's transition (360 ms, cubic-bezier(.34, 1.42, .64, 1)). */
private val NEIGHBOUR = CubicBezierEasing(0.34f, 1.42f, 0.64f, 1f)

/** CSS keyframes: [values] at [stops] (0..1), [ease] inside every segment, as a CSS animation-timing-function. */
private class Keys(private val stops: FloatArray, private val values: FloatArray, private val ease: Easing) {
    fun at(p: Float): Float {
        if (p <= 0f) return values[0]
        if (p >= 1f) return values[values.size - 1]
        var i = 0
        while (i < stops.size - 2 && p >= stops[i + 1]) i++
        val local = (p - stops[i]) / (stops[i + 1] - stops[i])
        return values[i] + (values[i + 1] - values[i]) * ease.transform(local)
    }
}
