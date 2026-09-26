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
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.model.Sender
import io.github.chronosauros.contour.ui.Lift
import io.github.chronosauros.contour.ui.Type
import io.github.chronosauros.contour.ui.kit.LiftGuard
import io.github.chronosauros.contour.ui.kit.LocalHaptics
import io.github.chronosauros.contour.ui.kit.Scale
import io.github.chronosauros.contour.ui.kit.detectHorizontalDragWithEnds
import io.github.chronosauros.contour.ui.lift
import io.github.chronosauros.contour.ui.pal
import io.github.chronosauros.contour.ui.sink
import io.github.chronosauros.contour.usb.DeviceController
import io.github.chronosauros.contour.usb.Link
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Drag gain = fill travel per finger travel, by finger speed (pointer acceleration): at or below [SPEED_FINE] the
 * fill moves [SLOW] of the finger's travel (owner 26.09 chose four times finer than 1.0.3's 0.4: 0.1 dB in about
 * 8 dp, about 4.5 Hz a dp at 1 kHz), or less where the scale's [Scale.fineMax] caps it (FREQ: at most 5 Hz a dp,
 * so the top decade stays adjustable); from there it rises evenly in ratio to [FAST] at [SPEED_FAST], so one quick
 * swipe still covers the whole range (20 Hz - 20 kHz).
 *
 * Owner 26.09: the linear ramp tried first (0.12-0.15 -> 0.9 dp/ms) already sped up the owner's careful drags,
 * which run at about 0.3-0.6 dp/ms (log: mean gain 0.92 where the slow gain was 0.1) - "the whole thing is
 * definitely too fast". Now a careful drag stays near the slow gain (0.12 at 0.5 dp/ms, 0.32 at 0.9) and only a
 * real swipe is fast.
 */
private const val SLOW = 0.1f
private const val FAST = 2.2f
private const val SPEED_FINE = 0.25f // dp per ms
private const val SPEED_FAST = 1.8f

/** 0 for a finger at or below [SPEED_FINE], 1 at or above [SPEED_FAST], smooth between (also PREAMP's drag). */
internal fun speedRamp(speedDp: Float): Float {
    val t = ((speedDp - SPEED_FINE) / (SPEED_FAST - SPEED_FINE)).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

/** From [a] at [s] = 0 to [b] at 1, evenly in ratio: each step of speed multiplies the gain by the same amount. */
internal fun ratioLerp(a: Float, b: Float, s: Float): Float = a * (b / a).pow(s)

/** The slow finger's gain at value [v] on a fill [travelDp] long: [SLOW], capped by [Scale.fineMax]. */
private fun slowGain(scale: Scale, v: Double, travelDp: Float): Float {
    val cap = scale.fineMax ?: return SLOW
    return minOf(SLOW, (cap * travelDp / scale.perPos(v)).toFloat())
}

private fun dragGain(speedDp: Float, slow: Float): Float = ratioLerp(slow, FAST, speedRamp(speedDp))

/**
 * A tall horizontal slider with RELATIVE drag: touch-down never moves the value, the drag moves it by the
 * distance travelled, scaled by finger speed ([dragGain]). The value is the light fill in a pressed-in track;
 * at the minimum the fill is a square nub. Quantized; haptic ticks at the scale marks, a strong one at the
 * param's home value, a reject tick when pushed past an end. Lifting the finger off a value keeps that value
 * ([LiftGuard]). Horizontal drags inside it never reach the pager.
 */
@Composable
fun RelSlider(param: Param, value: Double, onChange: (Double) -> Unit, modifier: Modifier = Modifier) {
    val scale = param.scale!!
    val haptics = LocalHaptics.current
    val p = pal
    val v = rememberUpdatedState(value)
    val change = rememberUpdatedState(onChange)
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier
            .testTag("slider_${param.name.lowercase()}")
            .pointerInput(param) {
                val guard = LiftGuard<Double>(density, param.name)
                var pos = 0f
                var atEnd = false
                var speed = 0f
                var ticked = 0.0 // the value the haptics last spoke for
                detectHorizontalDragWithEnds(
                    onStart = { down ->
                        pos = scale.toPos(v.value)
                        atEnd = false
                        speed = 0f
                        ticked = v.value
                        guard.start(down.uptimeMillis, down.position, v.value)
                    },
                    onEnd = { up -> if (up != null) guard.release(up)?.let { change.value(it) } },
                ) { ch, dx ->
                    ch.consume()
                    val dt = (ch.uptimeMillis - ch.previousUptimeMillis).coerceAtLeast(1L).toFloat()
                    speed = 0.6f * speed + 0.4f * (kotlin.math.abs(dx) / density / dt)
                    val travel = (size.width - size.height).toFloat().coerceAtLeast(1f)
                    val slow = slowGain(scale, scale.fromPos(pos), travel / density)
                    val raw = pos + dx * dragGain(speed, slow) / travel
                    if (raw < 0f || raw > 1f) {
                        if (!atEnd) haptics.reject()
                        atEnd = true
                    } else {
                        atEnd = false
                    }
                    pos = raw.coerceIn(0f, 1f)
                    val next = scale.quantize(scale.fromPos(pos))
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

/** A switch: 52 x 32 track (accent when on, pressed in), a raised 26 dp knob; 48 dp tall touch target. */
@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val p = pal
    val haptics = LocalHaptics.current
    val x by animateDpAsState(if (checked) 23.dp else 3.dp, label = "knob")
    val track = RoundedCornerShape(16.dp)
    Box(
        modifier
            .size(60.dp, 48.dp)
            .toggleable(checked, interactionSource = null, indication = null, role = Role.Switch) { haptics.segment(); onChange(it) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(52.dp, 32.dp)
                .clip(track)
                .background(if (checked) p.accent else p.track)
                .sink(track),
        ) {
            Box(
                Modifier
                    .offset(x = x)
                    .align(Alignment.CenterStart)
                    .size(26.dp)
                    .lift(CircleShape, Lift.RAISED)
                    .background(if (checked) p.text else p.textDim, CircleShape),
            )
        }
    }
}

/** What HOLD TO SEND shows for [p] now. */
fun holdLabel(p: Profile, device: DeviceController, sender: Sender): String = when {
    sender.sendingId == p.id -> "SENDING"
    device.link == Link.NO_DAC -> "NO DAC"
    device.link == Link.NEEDS_PERMISSION -> "TAP TO CONNECT"
    sender.onDacId == p.id -> "ON DAC"
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
    val shape = RoundedCornerShape(24.dp)
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
                        "NO DAC" -> { haptics.reject(); return@awaitEachGesture }
                        "TAP TO CONNECT" -> {
                            val up = waitUp()
                            if (up) device.requestPermission()
                            return@awaitEachGesture
                        }
                    }
                    val failed = labelNow.value.startsWith("FAILED")
                    var job: Job? = null
                    job = scope.launch {
                        fill.snapTo(0f)
                        var lastTick = 0f
                        fill.animateTo(1f, tween(HOLD_MS, easing = LinearEasing)) {
                            if (value - lastTick >= 0.08f) {
                                lastTick = value
                                haptics.texture(0.15f + 0.5f * value)
                            }
                        }
                    }
                    val done = withTimeoutOrNull(HOLD_MS.toLong() + 20) {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                            ch.consume()
                            if (ch.changedToUp() || !ch.pressed) return@withTimeoutOrNull false
                        }
                        @Suppress("UNREACHABLE_CODE")
                        false
                    }
                    if (done == null && fill.value >= 0.97f) {
                        haptics.click()
                        sender.send(profile.value)
                        scope.launch { fill.animateTo(0f, tween(250)) }
                        waitUp()
                    } else {
                        val quick = (fill.value < 0.3f)
                        job.cancel()
                        scope.launch { fill.animateTo(0f, tween(150)) }
                        if (done == null) waitUp()
                        else if (quick && failed) details.value()
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
