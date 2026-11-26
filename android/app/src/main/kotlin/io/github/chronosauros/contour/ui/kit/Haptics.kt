package io.github.chronosauros.contour.ui.kit

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import kotlin.math.pow

/**
 * Contour's haptics engine (owner 14.11.2026: the old ticks were "not refined and designed - just bland"). The owner tunes on a Pixel 9 Pro,
 * whose vibrator has composition primitives and amplitude control but no frequency control and no envelope effects, so every feeling is built
 * from primitives, their scales and the pauses between them. Design: `_Projects/contour-refs/haptics-research-2026-11-14/claude/haptics-report-and-design.md`
 * (round 1: this engine and [GainFeel]).
 *
 * Tiers, detected once: [Tier.P] primitives (Pixel), [Tier.F] vendor predefined effects only (a Nothing phone: no primitives, no
 * amplitude control), [Tier.V] the view's own constants.
 *
 * Nothing is queued and nothing is played after the fact: every effect plays now, in the touch handler, and a new `vibrate()` with the touch
 * usage cuts the one still playing. The drag layers ([dragGrain], [dragTooth], [dragClick], [grab]) have three rules: a grain is dropped if less than
 * [GRAIN_DROP_MS] passed since the start of the last effect of any kind, a tooth if less than [TOOTH_DROP_MS] passed since the last tooth, and for
 * [CLICK_QUIET_MS] after a click nothing of the drag layer plays, so the click is not cut. The one stage that is kept is HOLD TO SEND's (the build-up, [release], [land], [fail]): the drag layer never cuts it,
 * and [land] starts no earlier than the end of the release.
 *
 * Call only from gesture handlers or animation observers, when something changed - never from composition. Plain buttons
 * ([tap], [longPress], [reject], [confirm]) are the view's own constants.
 */
class Haptics(context: Context, private val view: View) {
    enum class Tier { P, F, V }

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    private val handler = Handler(Looper.getMainLooper())

    /** What this phone can do (see the class comment). */
    val tier: Tier = detectTier()

    private fun detectTier(): Tier {
        val v = vibrator?.takeIf { it.hasVibrator() } ?: return Tier.V
        if (Build.VERSION.SDK_INT >= 31 && v.areAllPrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_TICK,
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
            )
        ) return Tier.P
        if (Build.VERSION.SDK_INT >= 30 && v.areAllEffectsSupported(VibrationEffect.EFFECT_TICK) == Vibrator.VIBRATION_EFFECT_SUPPORT_YES) return Tier.F
        return Tier.V
    }

    private val touchAttributes: VibrationAttributes? =
        if (Build.VERSION.SDK_INT >= 33) VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH) else null

    @Suppress("DEPRECATION")
    private val touchAudio: AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build()

    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    /**
     * HAPTIC FEEDBACK at the foot of the library: [OFF] / [LOW] / [MEDIUM] / [HIGH] (owner 16.11.2026: "Haptic Feedback powinien mieć opcję mocy
     * Off, Low, Medium, High"; before, 14.11.2026, a plain ON / OFF switch - ON is MEDIUM, today's strength, byte for byte). OFF = Contour plays no
     * haptics at all: every effect leaves through [vibrate] or [feedback], so those two are the gate. LOW and HIGH move every effect one step on the
     * strength ladder: on [Tier.P] the scale of every primitive ([LEVEL_SCALE]), on [Tier.F] - a phone without amplitude control (the Nothing A065)
     * - the next softer or firmer predefined effect (TICK < CLICK < HEAVY_CLICK), and the view's constants become predefined effects of that ladder
     * too. [Tier.V] has nothing to scale: LOW and HIGH feel like MEDIUM there. Turning it off stops what is playing.
     */
    // clamped as the setter clamps: the initializer skips the setter, and a value out of range indexed past LEVEL_SCALE (review 18.11.2026)
    var level: Int = prefs.getInt(KEY_LEVEL, if (prefs.getBoolean(KEY_ENABLED, true)) MEDIUM else OFF).coerceIn(OFF, HIGH)
        set(value) {
            val v = value.coerceIn(OFF, HIGH)
            field = v
            prefs.edit().putInt(KEY_LEVEL, v).putBoolean(KEY_ENABLED, v != OFF).apply()
            if (v == OFF) vibrator?.cancel()
        }

    /** Whether Contour plays any haptics ([level] above [OFF]): the gate of Compose's own haptic feedback ([rememberGatedHapticFeedback]). */
    val enabled: Boolean get() = level != OFF

    /** [Tier.P]: a primitive's raw scale at the current [level]; MEDIUM leaves it as it is. */
    private fun lv(scale: Float): Float = if (level == MEDIUM) scale else (scale * LEVEL_SCALE[level]).coerceIn(0f, 1f)

    /**
     * [Tier.F]: a predefined effect one step softer (LOW) or firmer (HIGH) on the ladder TICK < CLICK < HEAVY_CLICK; MEDIUM leaves it as it is.
     * DOUBLE_CLICK keeps its two clicks at LOW (it is the failure pattern of a send).
     */
    private fun lvEffect(id: Int): Int = when (level) {
        LOW -> when (id) {
            VibrationEffect.EFFECT_HEAVY_CLICK, EFFECT_THUD_ID -> VibrationEffect.EFFECT_CLICK
            VibrationEffect.EFFECT_CLICK -> VibrationEffect.EFFECT_TICK
            else -> id
        }
        HIGH -> when (id) {
            VibrationEffect.EFFECT_TICK -> VibrationEffect.EFFECT_CLICK
            VibrationEffect.EFFECT_CLICK -> VibrationEffect.EFFECT_HEAVY_CLICK
            else -> id
        }
        else -> id
    }.let { if (it == id || effectSupported(it)) it else id }

    /**
     * A view constant at LOW or HIGH, where the phone has predefined effects: the effect of the same weight on the ladder, moved one step. Null =
     * play the constant itself: at MEDIUM, on a phone without these effects, a tick-class constant at LOW (the view's own tick is already the
     * softest there is - a predefined TICK would come out firmer than MEDIUM), and the drag grain at HIGH (it can fire every 30 ms; a CLICK there
     * would buzz).
     */
    private fun lvConstant(constant: Int): Int? {
        if (level == MEDIUM || tier == Tier.V) return null
        val base = when (constant) {
            HapticFeedbackConstants.LONG_PRESS, viewReject -> VibrationEffect.EFFECT_HEAVY_CLICK
            HapticFeedbackConstants.VIRTUAL_KEY, viewConfirm -> VibrationEffect.EFFECT_CLICK
            else -> VibrationEffect.EFFECT_TICK
        }
        if (base == VibrationEffect.EFFECT_TICK && (level == LOW || constant == viewGrain)) return null
        if (!effectSupported(base)) return null
        return lvEffect(base)
    }

    // ---------------------------------------------------------------------------------------------- what plays when

    /** Start of the last effect of any kind (uptime ms): a grain is dropped within [GRAIN_DROP_MS] of it. */
    private var lastStart = 0L

    /** Start of the last click: the drag layer is quiet for [CLICK_QUIET_MS] after it. */
    private var lastClick = 0L

    /** HOLD TO SEND's build-up, release, land and fail play out until this time: the drag layer never cuts them. */
    private var stageUntil = 0L

    /** When the release of HOLD TO SEND has played out: [land] never starts before it. */
    private var releaseEnd = 0L

    /** When the snap of a library swipe has played out: [commit] never starts before it. */
    private var snapEnd = 0L

    /** A segmented control's press armed the end of its indicator's travel until this time ([SegmentTravel]). */
    private var segArmUntil = 0L

    /** When the last start TICK of the pair played (uptime ms). */
    private var segStartAt = 0L

    /** Notes that an effect starts now; [stageMs] > 0 makes it a stage: started ([LATENCY_MS]), played and rung out ([RING_MS]) before the drag layer may speak again. */
    private fun began(click: Boolean = false, stageMs: Int = 0) {
        val now = SystemClock.uptimeMillis()
        lastStart = now
        if (click) lastClick = now
        if (stageMs > 0) stageUntil = now + LATENCY_MS + stageMs + RING_MS
    }

    /** Start of the last tooth: a tooth is dropped within [TOOTH_DROP_MS] of it. */
    private var lastTooth = 0L

    /**
     * The drag layer's rules and the stage (see the class comment); true when the effect may play, and notes its start. A tooth younger than
     * [TOOTH_DROP_MS] after the previous tooth is dropped: a real finger moves the value every touch frame (8-17 ms on the Pixel 9 Pro, trace
     * 14.11.2026 18:03), and each new `vibrate()` cancelled the tooth before it started (`cancelled_superseded`).
     */
    private fun dragAdmit(grain: Boolean, click: Boolean): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now < stageUntil || now - lastClick < CLICK_QUIET_MS) return false
        if (grain && now - lastStart < GRAIN_DROP_MS) return false
        val tooth = !grain && !click
        if (tooth && now - lastTooth < TOOTH_DROP_MS) return false
        began(click)
        if (tooth) lastTooth = now
        return true
    }

    // ---------------------------------------------------------------------------------------------- engine, tier P

    /** One primitive of a composition: [pause] is the silence after the END of the previous one. */
    private class Prim(val id: Int, val scale: Float, val pause: Int = 0)

    /** One primitive on a timeline: [start] is its start in ms from the start of the effect. */
    private class Item(val id: Int, val scale: Float, val start: Int)

    private val supportedCache = HashMap<Int, Boolean>()
    private val durationCache = HashMap<Int, Int>()

    /** Whether this vibrator plays [id]; one primitive it does not play silences the whole composition, so every id is checked (cached). */
    private fun supported(id: Int): Boolean = supportedCache.getOrPut(id) {
        Build.VERSION.SDK_INT >= 30 && tier == Tier.P && vibrator?.areAllPrimitivesSupported(id) == true
    }

    private fun duration(id: Int): Int = durationCache.getOrPut(id) {
        val read = if (Build.VERSION.SDK_INT >= 31) vibrator?.getPrimitiveDurations(id)?.firstOrNull() ?: 0 else 0
        if (read > 0) read else when (id) {
            VibrationEffect.Composition.PRIMITIVE_CLICK -> 29
            VibrationEffect.Composition.PRIMITIVE_TICK -> 5
            VibrationEffect.Composition.PRIMITIVE_LOW_TICK -> 12
            VibrationEffect.Composition.PRIMITIVE_THUD -> 300
            VibrationEffect.Composition.PRIMITIVE_QUICK_FALL -> 100
            VibrationEffect.Composition.PRIMITIVE_SLOW_RISE -> 500
            VibrationEffect.Composition.PRIMITIVE_QUICK_RISE -> 150
            else -> 20
        }
    }

    /** What stands in for [id] on this phone: THUD becomes CLICK; the falls and rises are left out (null). */
    private fun resolve(id: Int): Int? = when {
        supported(id) -> id
        id == VibrationEffect.Composition.PRIMITIVE_THUD && supported(VibrationEffect.Composition.PRIMITIVE_CLICK) -> VibrationEffect.Composition.PRIMITIVE_CLICK
        else -> null
    }

    private fun vibrate(effect: VibrationEffect) {
        if (!enabled) return
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= 33) {
            v.vibrate(effect, touchAttributes!!)
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, touchAudio)
        }
    }

    /** Plays [prims] as one composition now; [stage] makes it one of HOLD TO SEND's. False when nothing could be played. */
    private fun composeP(prims: List<Prim>, stage: Boolean = false): Boolean {
        if (Build.VERSION.SDK_INT < 31 || vibrator == null) return false
        val c = VibrationEffect.startComposition()
        var length = 0
        var count = 0
        for (p in prims) {
            val id = resolve(p.id) ?: continue
            val pause = p.pause.coerceAtLeast(0)
            c.addPrimitive(id, felt(lv(p.scale)), pause)
            length += pause + duration(id)
            count++
        }
        if (count == 0) return false
        began(click = prims.any { it.id == CLICK }, stageMs = if (stage) length else 0)
        vibrate(c.compose())
        return true
    }

    /** A timeline (primitive, scale, start) as one composition: pauses from the starts, a primitive that would overlap the previous one is dropped. */
    private fun timelineP(items: List<Item>, stage: Boolean = false): Boolean {
        val prims = ArrayList<Prim>(items.size)
        var end = 0
        for (item in items.sortedBy { it.start }) {
            val id = resolve(item.id) ?: continue
            val pause = item.start - end
            if (pause < 0) continue
            prims += Prim(id, item.scale, pause)
            end = item.start + duration(id)
        }
        return composeP(prims, stage)
    }

    // ---------------------------------------------------------------------------------------------- engine, tiers F and V

    private val effectCache = HashMap<Int, Boolean>()

    private fun effectSupported(id: Int): Boolean = effectCache.getOrPut(id) {
        Build.VERSION.SDK_INT >= 30 && vibrator?.areAllEffectsSupported(id) == Vibrator.VIBRATION_EFFECT_SUPPORT_YES
    }

    /** Tier F: the first predefined effect of [ids] this vibrator has, else the view's [fallback] constant (null: nothing). [stage]: one of HOLD TO SEND's. */
    private fun predefined(nominalMs: Int, ids: IntArray, fallback: Int?, stage: Boolean = false) {
        val id = ids.firstOrNull { effectSupported(it) }
        if (id == null) {
            if (fallback != null) {
                began(stageMs = if (stage) VIEW_MS else 0)
                feedback(fallback)
            }
            return
        }
        began(stageMs = if (stage) nominalMs else 0)
        vibrate(VibrationEffect.createPredefined(lvEffect(id)))
    }

    private fun feedback(constant: Int) {
        if (!enabled) return
        val effect = lvConstant(constant)
        if (effect != null) vibrate(VibrationEffect.createPredefined(effect)) else view.performHapticFeedback(constant)
    }

    /** Tier V: one view constant (null: nothing). */
    private fun viewOnly(constant: Int?, stage: Boolean = false) {
        if (constant == null) return
        began(stageMs = if (stage) VIEW_MS else 0)
        feedback(constant)
    }

    private val viewStep: Int = if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
    private val viewGrain: Int? = if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else null
    private val viewConfirm: Int = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
    private val viewReject: Int = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS

    // ---------------------------------------------------------------------------------------------- the drag layers

    /**
     * The grain of a drag, the softest layer (LOW_TICK): every 0.1 dB of GAIN, every Hz of FREQ. [scale] is raw: [felt] is applied here. Dropped
     * when less than [GRAIN_DROP_MS] passed since the start of the last effect of any kind. [crisp] makes it a TICK (FREQ above 2 kHz), [count]
     * plays it that many times back to back as one composition (FREQ below 200 Hz: 2; the node texture's pack: 5). Tier F has no grain; tier V a
     * view constant where the phone has one.
     */
    fun dragGrain(scale: Float, crisp: Boolean = false, count: Int = 1) {
        when (tier) {
            Tier.P -> dragP(if (crisp) TICK else LOW_TICK, scale, grain = true, click = false, count = count)
            Tier.F, Tier.V -> if (viewGrain != null && dragAdmit(grain = true, click = false)) feedback(viewGrain)
        }
    }

    /** The tooth of a drag, the middle layer (TICK; F: EFFECT_TICK, V: a view tick): every whole dB of GAIN. Plays at once, also over a grain or a tooth. */
    fun dragTooth(scale: Float) {
        when (tier) {
            Tier.P -> dragP(TICK, scale, grain = false, click = false)
            Tier.F -> dragPredefined(VibrationEffect.EFFECT_TICK, viewStep, click = false)
            Tier.V -> if (dragAdmit(grain = false, click = false)) feedback(viewStep)
        }
    }

    /** The click of a drag, the firmest layer (CLICK; F: EFFECT_CLICK, V: the key click): home and the end of a range. Nothing of the drag layer plays for [CLICK_QUIET_MS] after it. */
    fun dragClick(scale: Float) {
        when (tier) {
            Tier.P -> dragP(CLICK, scale, grain = false, click = true)
            Tier.F -> dragPredefined(VibrationEffect.EFFECT_CLICK, HapticFeedbackConstants.VIRTUAL_KEY, click = true)
            Tier.V -> if (dragAdmit(grain = false, click = true)) feedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    /** The finger lands on a slider: the system's key click, once per touch (as EQ Sweep's PREAMP does). Counts as a click for the drag layer. */
    fun grab() {
        if (dragAdmit(grain = false, click = true)) feedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun dragP(id: Int, scale: Float, grain: Boolean, click: Boolean, count: Int = 1) {
        if (Build.VERSION.SDK_INT < 31 || vibrator == null) return
        val rid = resolve(id) ?: return
        if (!dragAdmit(grain, click)) return
        val c = VibrationEffect.startComposition()
        repeat(count.coerceAtLeast(1)) { c.addPrimitive(rid, felt(lv(scale))) }
        vibrate(c.compose())
    }

    private fun dragPredefined(id: Int, fallback: Int, click: Boolean) {
        if (!dragAdmit(grain = false, click = click)) return
        if (effectSupported(id)) vibrate(VibrationEffect.createPredefined(lvEffect(id))) else feedback(fallback)
    }

    // ---------------------------------------------------------------------------------------------- tokens: swipes and segmented controls

    /** The home value outside a drag (the response graph's double tap to 0 dB): one click, the scale of GAIN's zero click at rest. */
    fun centre() = when (tier) {
        Tier.P -> { composeP(listOf(Prim(CLICK, CENTRE_SCALE))); Unit }
        Tier.F -> predefined(35, intArrayOf(VibrationEffect.EFFECT_CLICK), HapticFeedbackConstants.VIRTUAL_KEY)
        Tier.V -> viewOnly(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /**
     * A library swipe or a page drag goes back across its threshold (LOW_TICK 0.40; round 5b: the way up is [swipeSnap] for a swipe, [segStart] for a page
     * drag, so [on] is only for the lab).
     */
    fun threshold(on: Boolean) {
        if (!on) segArmUntil = 0L // a page drag that went back: no page will settle for this start
        when (tier) {
            Tier.P -> composeP(listOf(if (on) Prim(TICK, THRESHOLD_ON) else Prim(LOW_TICK, THRESHOLD_OFF)))
            Tier.F -> if (on) predefined(12, intArrayOf(VibrationEffect.EFFECT_TICK), null)
            Tier.V -> viewOnly(
                if (Build.VERSION.SDK_INT >= 34) {
                    if (on) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE
                } else if (on) HapticFeedbackConstants.CLOCK_TICK else null,
            )
        }
    }

    // The pair of every segmented control, tab and page (owner 14.11.2026: "starts and ends on a haptic"): a start TICK at the press, the same end
    // CLICK when the indicator or the page arrives, for every control and every direction.

    private val playEnd = Runnable {
        when (tier) {
            Tier.P -> composeP(listOf(Prim(CLICK, SEG_END)))
            Tier.F -> predefined(25, intArrayOf(VibrationEffect.EFFECT_CLICK), HapticFeedbackConstants.VIRTUAL_KEY)
            Tier.V -> viewOnly(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    /** Plays the start TICK and notes when: [segEnd] never comes sooner than [SEG_MIN_MS] after it. Drops a pending end of an earlier pair. */
    private fun playStart() {
        handler.removeCallbacks(playEnd)
        segStartAt = SystemClock.uptimeMillis()
        when (tier) {
            Tier.P -> composeP(listOf(Prim(TICK, SEG_START)))
            Tier.F -> predefined(12, intArrayOf(VibrationEffect.EFFECT_TICK), HapticFeedbackConstants.CLOCK_TICK)
            Tier.V -> viewOnly(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    /**
     * The press that picks a new cell or tab, or a page drag that crosses its threshold: the start of the pair. Arms the end for the indicator or the
     * page that is about to move ([SegmentTravel], the pager's settle).
     */
    fun segStart() {
        segArmUntil = SystemClock.uptimeMillis() + SEG_ARM_MS
        playStart()
    }

    /** Whether a start armed the end of a moving indicator or page and it has not arrived yet. */
    internal fun segArmed(): Boolean = SystemClock.uptimeMillis() < segArmUntil

    /** The indicator or the page has arrived: the end CLICK, waiting for [SEG_MIN_MS] after the start when the move was shorter (the one allowed delay). */
    internal fun segEnd() {
        segArmUntil = 0L
        handler.removeCallbacks(playEnd)
        val wait = segStartAt + SEG_MIN_MS - SystemClock.uptimeMillis()
        if (wait > 0) handler.postDelayed(playEnd, wait) else playEnd.run()
    }

    /** A control with no sliding indicator (the USB plug-in switch, AUTO, A/B, a DAC option picker): the start at the press, the end [SEG_END_DELAY_MS] later. */
    fun segTap() {
        playStart()
        handler.postDelayed(playEnd, SEG_END_DELAY_MS)
    }

    /**
     * A library row's full swipe arms (round 5b): the snap of the bowstring - CLICK and a fall, the same as [release] of HOLD TO SEND. Nothing from the
     * drag layer for [SNAP_QUIET_MS] after it ([SwipeFeel]'s tension), and [commit] never starts before it has played out.
     */
    fun swipeSnap() {
        when (tier) {
            Tier.P -> composeP(listOf(Prim(CLICK, SNAP_CLICK), Prim(QUICK_FALL, SNAP_FALL)), stage = true)
            Tier.F -> predefined(35, intArrayOf(VibrationEffect.EFFECT_HEAVY_CLICK, VibrationEffect.EFFECT_CLICK), viewConfirm, stage = true)
            Tier.V -> viewOnly(viewConfirm, stage = true)
        }
        snapEnd = stageUntil
        stageUntil = minOf(stageUntil, SystemClock.uptimeMillis() + SNAP_QUIET_MS)
    }

    private val playCommit = Runnable {
        when (tier) {
            Tier.P -> composeP(listOf(Prim(THUD, LAND)))
            Tier.F -> predefined(50, intArrayOf(EFFECT_THUD_ID, VibrationEffect.EFFECT_CLICK), viewConfirm)
            Tier.V -> viewOnly(viewConfirm)
        }
    }

    /** A library swipe fires its action on release (round 5b): the thud of the landing, the same as [land], after the snap has played out. */
    fun commit() {
        handler.removeCallbacks(playCommit)
        val wait = snapEnd - SystemClock.uptimeMillis()
        if (wait > 0) handler.postDelayed(playCommit, wait) else playCommit.run()
    }

    // ---------------------------------------------------------------------------------------------- tokens: HOLD TO SEND

    private var holdTicks: Runnable? = null

    private fun stopHoldTicks() {
        holdTicks?.let { handler.removeCallbacks(it) }
        holdTicks = null
    }

    /**
     * Touch-down on HOLD TO SEND accepted: one grab tick, then the draw of the bowstring as ONE composition built now (a later `vibrate()` would
     * cut it): one slow rise and a creak at full draw, silent from [holdMs] - 60 so the release has the stage to itself. Where SLOW_RISE is missing
     * only the grab and the creak play. On F and V: a tick, then a tick every 120 ms from half the hold.
     */
    fun holdStart(holdMs: Int) {
        stopHoldTicks()
        when (tier) {
            Tier.P -> {
                val items = ArrayList<Item>()
                items += Item(TICK, HOLD_GRAB, 0)
                if (supported(SLOW_RISE)) items += Item(SLOW_RISE, 0.55f, 80)
                items += Item(TICK, 0.6f, holdMs - 100) // the creak at full draw, just after the rise ends
                timelineP(items, stage = true)
            }
            Tier.F, Tier.V -> {
                val tick = {
                    if (tier == Tier.F) predefined(12, intArrayOf(VibrationEffect.EFFECT_TICK), HapticFeedbackConstants.CLOCK_TICK, stage = true)
                    else viewOnly(HapticFeedbackConstants.CLOCK_TICK, stage = true)
                }
                tick()
                val until = SystemClock.uptimeMillis() + holdMs + 200
                val every = object : Runnable {
                    override fun run() {
                        if (SystemClock.uptimeMillis() > until) return
                        tick()
                        handler.postDelayed(this, HOLD_TICK_MS)
                    }
                }
                holdTicks = every
                handler.postDelayed(every, holdMs / 2L)
            }
        }
    }

    /** The hold ended without a send (lifted early, slid out): the build-up stops; after more than 30 % of the hold the bow slackens ([progress] 0..1). */
    fun holdCancel(progress: Float) {
        stopHoldTicks()
        if (tier == Tier.V) return
        vibrator?.cancel()
        stageUntil = 0L
        if (tier == Tier.P && progress > 0.3f) composeP(listOf(Prim(QUICK_FALL, SLACK)))
    }

    /** The hold completed and the send goes: the shot - a click, then the fall. Holds the stage for its whole length. */
    fun release() {
        stopHoldTicks()
        when (tier) {
            Tier.P -> composeP(listOf(Prim(CLICK, SNAP_CLICK), Prim(QUICK_FALL, SNAP_FALL)), stage = true)
            Tier.F -> predefined(35, intArrayOf(VibrationEffect.EFFECT_HEAVY_CLICK, VibrationEffect.EFFECT_CLICK), viewConfirm, stage = true)
            Tier.V -> viewOnly(viewConfirm, stage = true)
        }
        releaseEnd = stageUntil
    }

    private val playLand = Runnable {
        when (tier) {
            Tier.P -> composeP(listOf(Prim(THUD, LAND)), stage = true)
            Tier.F -> predefined(50, intArrayOf(EFFECT_THUD_ID, VibrationEffect.EFFECT_CLICK), viewConfirm, stage = true)
            Tier.V -> viewOnly(viewConfirm, stage = true)
        }
    }

    /** The DAC confirmed the send: a thud, started no earlier than the end of the release (later when the DAC answered sooner). */
    fun land() {
        stopHoldTicks()
        handler.removeCallbacks(playLand)
        val wait = releaseEnd - SystemClock.uptimeMillis()
        if (wait > 0) handler.postDelayed(playLand, wait) else playLand.run()
    }

    /** The send did not go, or failed: "no-no-no", the system's failure pattern. */
    fun fail() {
        stopHoldTicks()
        handler.removeCallbacks(playLand)
        when (tier) {
            Tier.P -> composeP(
                listOf(Prim(CLICK, FAIL), Prim(CLICK, FAIL, FAIL_PAUSE), Prim(CLICK, FAIL, FAIL_PAUSE)),
                stage = true,
            )
            Tier.F -> predefined(100, intArrayOf(VibrationEffect.EFFECT_DOUBLE_CLICK, VibrationEffect.EFFECT_CLICK), viewReject, stage = true)
            Tier.V -> viewOnly(viewReject, stage = true)
        }
    }

    // ---------------------------------------------------------------------------------------------- plain buttons: the view's constants

    fun tap() {
        began(click = true)
        feedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    fun longPress() {
        began()
        feedback(HapticFeedbackConstants.LONG_PRESS)
    }

    fun reject() {
        began()
        feedback(viewReject)
    }

    fun confirm() {
        began()
        feedback(viewConfirm)
    }

    // ---------------------------------------------------------------------------------------------- the haptics lab (service screen)

    /** Lines for the haptics lab: the tier, the vibrator's primitives with their durations, its resonant frequency and Q factor. */
    fun describe(): List<String> {
        val lines = ArrayList<String>()
        lines += "tier ${tier.name}: " + when (tier) {
            Tier.P -> "composition primitives"
            Tier.F -> "predefined effects only"
            Tier.V -> "view constants"
        }
        val v = vibrator
        if (v == null || !v.hasVibrator()) {
            lines += "no vibrator"
            return lines
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val ids = listOf(
                "CLICK" to VibrationEffect.Composition.PRIMITIVE_CLICK, "TICK" to VibrationEffect.Composition.PRIMITIVE_TICK,
                "LOW_TICK" to VibrationEffect.Composition.PRIMITIVE_LOW_TICK, "THUD" to VibrationEffect.Composition.PRIMITIVE_THUD,
                "SPIN" to VibrationEffect.Composition.PRIMITIVE_SPIN, "QUICK_RISE" to VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
                "SLOW_RISE" to VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, "QUICK_FALL" to VibrationEffect.Composition.PRIMITIVE_QUICK_FALL,
            )
            val ok = runCatching { v.arePrimitivesSupported(*ids.map { it.second }.toIntArray()) }.getOrNull()
            val ms = runCatching { v.getPrimitiveDurations(*ids.map { it.second }.toIntArray()) }.getOrNull()
            lines += "primitives: " + ids.indices.joinToString(", ") { i ->
                if (ok?.getOrNull(i) == true) "${ids[i].first} ${ms?.getOrNull(i) ?: "?"} ms" else "${ids[i].first} -"
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            lines += "amplitude control: ${v.hasAmplitudeControl()}"
            val hz = runCatching { v.resonantFrequency }.getOrNull()
            val q = runCatching { v.qFactor }.getOrNull()
            lines += "resonant frequency: " + (hz?.takeIf { !it.isNaN() }?.let { "%.1f Hz".format(it) } ?: "unknown") +
                ", Q factor: " + (q?.takeIf { !it.isNaN() }?.let { "%.2f".format(it) } ?: "unknown")
        }
        return lines
    }

    companion object {
        // Every constant of the engine in one block (owner 14.11.2026, design: see the class comment).
        const val CENTRE_SCALE = 0.70f         // CLICK of the double tap to 0 dB: GAIN's zero click at rest ([GainFeel.zeroScale] at v = 0)
        const val THRESHOLD_ON = 0.70f         // TICK when a library swipe crosses its threshold (= SEG_START, the page drag's start)
        const val THRESHOLD_OFF = 0.40f        // LOW_TICK when a swipe or a page drag goes back across it
        const val SEG_START = 0.70f            // TICK at the press of a new cell or tab, or when a page drag crosses its threshold
        const val SEG_END = 0.60f              // CLICK when the indicator or the page arrives: always the same
        const val HOLD_GRAB = 0.5f             // TICK at touch-down on HOLD TO SEND
        const val SNAP_CLICK = 1.0f            // release: CLICK, then QUICK_FALL right after (pause 0)
        const val SNAP_FALL = 0.6f
        const val SLACK = 0.25f                // QUICK_FALL when the finger lifts early after more than 30 % of the hold
        const val LAND = 0.7f                  // THUD when the DAC confirms the send
        const val FAIL = 0.9f                  // CLICK x3 ("no-no-no", the system's failure pattern)
        const val FAIL_PAUSE = 90

        private const val GRAIN_DROP_MS = 30L  // a grain is dropped if less than this passed since the start of the last effect of any kind
        private const val CLICK_QUIET_MS = 40L // for this long after a click nothing of the drag layer plays
        private const val TOOTH_DROP_MS = 22L  // a tooth is dropped if less than this passed since the start of the last tooth (never queued)
        private const val KEY_ENABLED = "hapticsOn" // HAPTIC FEEDBACK switch until 16.11.2026, prefs "ui": still written (level != OFF), read once as the start level
        private const val KEY_LEVEL = "hapticsLevel" // HAPTIC FEEDBACK OFF / LOW / MEDIUM / HIGH, prefs "ui"
        const val OFF = 0
        const val LOW = 1
        const val MEDIUM = 2
        const val HIGH = 3
        /** [Tier.P]: the multiplier of every primitive's scale per [level]; LOW and HIGH sit a ratio of 1.5 from MEDIUM (the spec: >= 1.4 to be felt). */
        private val LEVEL_SCALE = floatArrayOf(0f, 0.66f, 1f, 1.5f)
        private const val LATENCY_MS = 25      // vibrate() to the motor's start, measured 20-30 ms on the Pixel 9 Pro (14.11.2026)
        private const val RING_MS = 25         // a stage holds the motor this long after its end
        private const val VIEW_MS = 30         // nominal length of a view constant
        private const val SEG_ARM_MS = 1500L
        private const val SNAP_QUIET_MS = 120L // the drag layer is quiet this long after a swipe's snap
        private const val SEG_MIN_MS = 60L     // the end never comes sooner than this after the start
        private const val SEG_END_DELAY_MS = 120L
        private const val HOLD_TICK_MS = 120L

        /** VibrationEffect.EFFECT_THUD is not in the public SDK; createPredefined takes the id (3). */
        private const val EFFECT_THUD_ID = 3

        private const val CLICK = VibrationEffect.Composition.PRIMITIVE_CLICK
        private const val TICK = VibrationEffect.Composition.PRIMITIVE_TICK
        private const val LOW_TICK = VibrationEffect.Composition.PRIMITIVE_LOW_TICK
        private const val THUD = VibrationEffect.Composition.PRIMITIVE_THUD
        private const val QUICK_FALL = VibrationEffect.Composition.PRIMITIVE_QUICK_FALL
        private const val SLOW_RISE = VibrationEffect.Composition.PRIMITIVE_SLOW_RISE

        /** SystemUI's compensation for how a vibrator's strength is felt (also in EQ Sweep); applied once, to every scale of a composition. */
        fun felt(s: Float): Float = s.coerceIn(0f, 1f).pow(1f / 0.89f)
    }
}

val LocalHaptics = staticCompositionLocalOf<Haptics> { error("no Haptics") }

@Composable
fun rememberHaptics(): Haptics {
    val context = LocalContext.current
    val view = LocalView.current
    return remember(view) { Haptics(context, view) }
}

/**
 * Compose's own haptics (the long press of `combinedClickable`: the DAC status chip, the band and slot buttons) through the HAPTIC
 * FEEDBACK switch: with the switch off they were the one vibration left (Nothing A065, 14.11.2026). With it on, nothing changes.
 */
@Composable
fun rememberGatedHapticFeedback(haptics: Haptics): HapticFeedback {
    val base = LocalHapticFeedback.current
    return remember(base, haptics) {
        object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                if (haptics.enabled) base.performHapticFeedback(hapticFeedbackType)
            }
        }
    }
}
