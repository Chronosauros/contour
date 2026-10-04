package io.github.chronosauros.contour.ui.tune

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.semantics
import io.github.chronosauros.contour.core.DevicePlan
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.model.Sender
import io.github.chronosauros.contour.ui.Lift
import io.github.chronosauros.contour.ui.Type
import io.github.chronosauros.contour.ui.kit.LiftGuard
import io.github.chronosauros.contour.ui.kit.LocalHaptics
import io.github.chronosauros.contour.ui.kit.LocalPagerLock
import io.github.chronosauros.contour.ui.kit.Scale
import io.github.chronosauros.contour.ui.kit.detectHorizontalDragWithEnds
import io.github.chronosauros.contour.ui.kit.holdsPager
import io.github.chronosauros.contour.ui.lift
import io.github.chronosauros.contour.ui.Radii
import io.github.chronosauros.contour.ui.pal
import io.github.chronosauros.contour.ui.sink
import io.github.chronosauros.contour.usb.DeviceController
import io.github.chronosauros.contour.usb.Link
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val SPEED_FINE = 0.25f // dp per ms
private const val SPEED_FAST = 1.8f

/** PREAMP's speed ramp: 0 at or below [SPEED_FINE], 1 at or above [SPEED_FAST], smooth between. */
internal fun speedRamp(speedDp: Float): Float {
    val t = ((speedDp - SPEED_FINE) / (SPEED_FAST - SPEED_FINE)).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

/** From [a] at [s] = 0 to [b] at 1, evenly in ratio: each step of speed multiplies the gain by the same amount. */
internal fun ratioLerp(a: Float, b: Float, s: Float): Float = a * (b / a).pow(s)

/**
 * FREQ / GAIN / Q drag, ported from EQ Sweep's Bands table (owner 03.10: refined further there). The value moves
 * a fixed amount per dp of finger travel, whatever the slider's width - an octave per 60 dp (FREQ), 1 dB per
 * 10 dp (GAIN), a factor of 2 per 80 dp (Q) - times a multiplier by smoothed finger speed: a slow finger (at or
 * below 0.25 dp/ms) gets 0.1 (0.1 dB per 10 dp; FREQ at most [Scale.fineMax] Hz a dp, so the top octave stays
 * adjustable), easing gently into 1.25 at a normal 0.6 dp/ms (-10 to +10 dB in about 160 dp) and 1.5 at a quick
 * 1.2 dp/ms. Replaces 26.09's fill-relative gain (0.1 - 2.2 of the track over 0.25 - 1.8 dp/ms).
 */
internal class BandDrag(private val param: Param, initial: Double) {
    private val scale = param.scale!!
    private var raw = initial
    private var speed = 0f

    /** The last [move] pushed past an end of the scale. */
    var pastEnd = false
        private set

    val value: Double get() = scale.quantize(raw)

    fun move(dxDp: Float, dtMs: Long): Double {
        speed = 0.6f * speed + 0.4f * (abs(dxDp) / dtMs.coerceAtLeast(1L))
        val slow = scale.fineMax?.takeIf { param == Param.FREQ }
            ?.let { minOf(SLOW, it * 60.0 / (raw * ln(2.0))) } ?: SLOW
        val gain = if (speed <= 0.6f) {
            blend(slow, 1.25, ((speed - 0.25f) / (0.6f - 0.25f)).coerceIn(0f, 1f).pow(1.15f))
        } else {
            blend(1.25, 1.5, (speed - 0.6f) / (1.2f - 0.6f))
        }
        val dx = dxDp * gain
        val next = when (param) {
            Param.FREQ -> raw * 2.0.pow(dx / 60.0)
            Param.GAIN -> raw + dx / 10.0
            Param.Q -> raw * 2.0.pow(dx / 80.0)
            Param.PREAMP -> error("PREAMP has its own drag")
        }
        pastEnd = next < scale.min || next > scale.max
        raw = next.coerceIn(scale.min, scale.max)
        return value
    }

    private fun blend(from: Double, to: Double, progress: Float): Double {
        val t = progress.coerceIn(0f, 1f).toDouble()
        return from + (to - from) * t * t * (3 - 2 * t)
    }

    private companion object {
        const val SLOW = 0.1
    }
}

/**
 * A tall horizontal slider with RELATIVE drag: touch-down never moves the value, the drag moves it by the
 * distance travelled, scaled by finger speed ([BandDrag]). The value is the light fill in a pressed-in track;
 * at the minimum the fill is a square nub. Quantized; haptic ticks at the scale marks, a strong one at the
 * param's home value, a reject tick when pushed past an end. Lifting the finger off a value keeps that value
 * ([LiftGuard]). Horizontal drags inside it never reach the pager.
 */
@Composable
fun RelSlider(param: Param, value: Double, onChange: (Double) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, scale: Scale = param.scale!!) {
    val haptics = LocalHaptics.current
    val pagerLock = LocalPagerLock.current
    val p = pal
    val v = rememberUpdatedState(value)
    val change = rememberUpdatedState(onChange)
    val shape = RoundedCornerShape(Radii.M)
    Box(
        modifier
            .testTag("slider_${param.name.lowercase()}")
            .semantics {
                contentDescription = "${param.label} slider"
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), scale.min.toFloat()..scale.max.toFloat())
                if (!enabled) disabled()
                setProgress { requested ->
                    if (!enabled || !requested.isFinite()) false else {
                        val next = scale.quantize(requested.toDouble().coerceIn(scale.min, scale.max))
                        if (next != value) onChange(next)
                        true
                    }
                }
            }
            .then(if (enabled) Modifier.holdsPager(pagerLock) else Modifier)
            .pointerInput(param, enabled, scale.min, scale.max) {
                if (!enabled) return@pointerInput
                val guard = LiftGuard<Double>(density, param.name)
                var drag = BandDrag(param, v.value)
                var atEnd = false
                var ticked = 0.0 // the value the haptics last spoke for
                detectHorizontalDragWithEnds(
                    onStart = { down ->
                        drag = BandDrag(param, v.value)
                        atEnd = false
                        ticked = v.value
                        guard.start(down.uptimeMillis, down.position, v.value)
                    },
                    onEnd = { up -> if (up != null) guard.release(up)?.let { change.value(it) } },
                ) { ch, dx ->
                    ch.consume()
                    val next = drag.move(dx / density, ch.uptimeMillis - ch.previousUptimeMillis)
                    if (drag.pastEnd) {
                        if (!atEnd) haptics.reject()
                        atEnd = true
                    } else {
                        atEnd = false
                    }
                    if (next != v.value) change.value(next)
                    guard.move(ch.uptimeMillis, ch.position, next)
                    if (next != ticked && !guard.settling(ch.uptimeMillis)) {
                        val k = param.crossing(ticked, next)
                        if (k > 0) haptics.crossing(k)
                        ticked = next
                    }
                }
            }
            .clip(shape)
            .background(p.track)
            .sink(shape),
    ) {
        Box(
            Modifier
                .layout { m, c ->
                    val h = c.maxHeight
                    val w = (h + (c.maxWidth - h) * scale.toPos(v.value)).roundToInt().coerceIn(h, c.maxWidth)
                    val pl = m.measure(Constraints.fixed(w, h))
                    layout(w, h) { pl.place(0, 0) }
                }
                .lift(shape, Lift.RAISED)
                .background(p.fill, shape),
        )
    }
}

/** What HOLD TO SEND shows for [p] now. */
fun holdLabel(p: Profile, device: DeviceController, sender: Sender): String = when {
    sender.sendingId == p.id -> "SENDING"
    device.link == Link.NO_DAC -> "NO DAC"
    device.link == Link.NEEDS_PERMISSION -> "TAP TO CONNECT"
    device.sendIssues(p).isNotEmpty() -> "INVALID EQ - EDIT BAND"
    sender.onDacId == p.id && device.destinationActive -> "ON DAC"
    sender.failedFor(p) -> "FAILED - HOLD TO RETRY"
    else -> "HOLD TO SEND"
}

private const val HOLD_MS = 700

/**
 * HOLD TO SEND: hold 700 ms, a shade fills left to right with a rising haptic texture and the button sinks
 * towards the page, a click at full, then send and verify. The only place in the app (besides the service
 * screen) that writes to the DAC. Orange while it can send; grey for ON DAC / FAILED / TAP TO CONNECT;
 * flat grey (no shadow) for NO DAC.
 */
@Composable
fun HoldToSend(p: Profile, device: DeviceController, sender: Sender, onDetails: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHaptics.current
    val c = pal
    val scope = rememberCoroutineScope()
    val fill = remember { Animatable(0f) }
    val label = holdLabel(p, device, sender)
    val labelNow = rememberUpdatedState(label)
    val profile = rememberUpdatedState(p)
    val details = rememberUpdatedState(onDetails)
    val orange = label == "HOLD TO SEND" || label == "SENDING"
    val flat = label == "NO DAC"
    val shape = RoundedCornerShape(Radii.L)
    Box(
        modifier
            .fillMaxWidth()
            .then(
                if (flat) Modifier
                else Modifier.dropShadow(shape) {
                    // sinks while held: the shadow tightens as the fill runs
                    val press = fill.value
                    radius = (14f * (1f - 0.6f * press)).dp.toPx()
                    offset = Offset(0f, (5f * (1f - 0.7f * press)).dp.toPx())
                    color = c.shadow
                    alpha = 0.55f * c.shadowAlpha
                },
            )
            .clip(shape)
            .background(if (orange) c.accent else c.surface2)
            .testTag("hold_to_send")
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    when (labelNow.value) {
                        "SENDING" -> return@awaitEachGesture
                        "NO DAC" -> {
                            if (waitUp() && !device.connect()) haptics.reject()
                            return@awaitEachGesture
                        }
                        "INVALID EQ - EDIT BAND" -> { haptics.reject(); return@awaitEachGesture }
                        "TAP TO CONNECT" -> {
                            val up = waitUp()
                            if (up) device.requestPermission()
                            return@awaitEachGesture
                        }
                    }
                    val failed = labelNow.value.startsWith("FAILED")
                    val job: Job = scope.launch {
                        fill.snapTo(0f)
                        var lastTick = 0f
                        fill.animateTo(1f, tween(HOLD_MS, easing = LinearEasing)) {
                            if (value - lastTick >= 0.08f) {
                                lastTick = value
                                haptics.texture(0.15f + 0.5f * value)
                            }
                        }
                    }
                    var finished = false
                    try {
                        val done = withTimeoutOrNull(HOLD_MS.toLong() + 20) {
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                                val inside = ch.position.x >= 0f && ch.position.x < size.width &&
                                    ch.position.y >= 0f && ch.position.y < size.height
                                if (ch.isConsumed || !inside) return@withTimeoutOrNull false
                                val released = ch.changedToUp()
                                ch.consume()
                                if (released) return@withTimeoutOrNull true
                                if (!ch.pressed) return@withTimeoutOrNull false
                            }
                            @Suppress("UNREACHABLE_CODE")
                            false
                        }
                        if (done == null) {
                            job.cancel()
                            haptics.click()
                            sender.send(profile.value)
                            scope.launch { fill.animateTo(0f, tween(250)) }
                            waitUp()
                        } else {
                            val quick = (fill.value < 0.3f)
                            job.cancel()
                            scope.launch { fill.animateTo(0f, tween(150)) }
                            if (quick && failed && done == true) details.value()
                        }
                        finished = true
                    } finally {
                        job.cancel()
                        if (!finished) scope.launch { fill.snapTo(0f) }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(fill.value)
                .background(if (orange) c.onAccent.copy(alpha = 0.18f) else c.accent),
        )
        Text(
            label,
            style = Type.button,
            color = when {
                orange -> c.onAccent
                flat -> c.textMute
                else -> c.text
            },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

/** Consumes everything until the pointer is up; true if it went up normally. */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.waitUp(): Boolean {
    while (true) {
        val ev = awaitPointerEvent()
        ev.changes.forEach { it.consume() }
        if (ev.changes.none { it.pressed }) return true
    }
}
