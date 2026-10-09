package io.github.chronosauros.contour.ui.tune

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.chronosauros.contour.ui.Grid
import io.github.chronosauros.contour.ui.Lift
import io.github.chronosauros.contour.ui.Radii
import io.github.chronosauros.contour.ui.Type
import io.github.chronosauros.contour.ui.kit.LiftGuard
import io.github.chronosauros.contour.ui.kit.LocalHaptics
import io.github.chronosauros.contour.ui.kit.Scale
import io.github.chronosauros.contour.ui.kit.Fmt
import io.github.chronosauros.contour.ui.kit.detectVerticalDragWithEnds
import io.github.chronosauros.contour.ui.lift
import io.github.chronosauros.contour.ui.pal
import io.github.chronosauros.contour.ui.sink
import io.github.chronosauros.contour.usb.DeviceController
import io.github.chronosauros.contour.usb.Link

/** Slow-finger share of the full-speed drag rate, as in the sliders below the graph. */
private const val VOLUME_SLOW = 0.35f

/**
 * Contour advBeta: the DAC's hardware volume (USB Audio Class), beside the graph. A pressed-in vertical track with
 * a white fill from the bottom, like the PREAMP bar turned upright; HARDWARE VOLUME is written along it and changes colour
 * where the fill covers it (drawn once over the track and once, clipped, over the fill). Relative vertical drag,
 * 0.5 dB steps, -60..0 dB; the DAC gets the newest value while the finger moves.
 *
 * Never read automatically: the read claims the USB Audio Control interface when the kernel refuses a shared
 * request, which can drop the phone's audio until the DAC is replugged (freestyler7, 03.10.2026). Until the user
 * taps the bar once, it shows TAP TO READ; that tap reads, later drags set.
 */
@Composable
fun VolumeBar(device: DeviceController, modifier: Modifier = Modifier) {
    val c = pal
    val haptics = LocalHaptics.current
    val scale = Scale.VOLUME
    val shape = RoundedCornerShape(Radii.M)
    val connected = device.link == Link.CONNECTED

    val dacDb = device.volume?.current256?.maxOrNull()?.div(256.0)
    var shown by remember(connected) { mutableStateOf<Double?>(null) } // under the finger, ahead of the read-back
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(dacDb, dragging) { if (!dragging && dacDb != null && dacDb == shown) shown = null }
    val value = shown ?: dacDb
    val active = connected && value != null
    val current = rememberUpdatedState(value ?: 0.0)
    val set = { db: Double -> shown = db; device.dragVolume(db) }

    Box(
        modifier
            .lift(RoundedCornerShape(Radii.L))
            .background(c.surface, RoundedCornerShape(Radii.L))
            .padding(Grid.INSET),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .testTag("volume_bar")
                .semantics {
                    contentDescription = "DAC hardware volume" + if (value == null) ", tap to read. Some phones lose audio until replug when hardware volume is used." else ""
                    if (value != null) progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), scale.min.toFloat()..scale.max.toFloat())
                }
                .clip(shape)
                .background(c.track)
                .sink(shape)
                .then(
                    if (active) Modifier.pointerInput(Unit) {
                        val guard = LiftGuard<Double>(density, "VOLUME")
                        var pos = 0f
                        var atEnd = false
                        var speed = 0f
                        var ticked = 0.0
                        detectVerticalDragWithEnds(
                            onStart = { down ->
                                dragging = true
                                pos = scale.toPos(current.value)
                                atEnd = false
                                speed = 0f
                                ticked = current.value
                                guard.start(down.uptimeMillis, down.position, current.value)
                            },
                            onEnd = { up ->
                                if (up != null) guard.release(up)?.let { set(it) }
                                dragging = false
                            },
                        ) { ch, dy ->
                            ch.consume()
                            val dt = (ch.uptimeMillis - ch.previousUptimeMillis).coerceAtLeast(1L).toFloat()
                            speed = 0.6f * speed + 0.4f * (kotlin.math.abs(dy) / density / dt)
                            val travel = (size.height - size.width).toFloat().coerceAtLeast(1f)
                            val raw = pos - dy * ratioLerp(VOLUME_SLOW, 1f, speedRamp(speed)) / travel
                            if (raw < 0f || raw > 1f) {
                                if (!atEnd) haptics.reject()
                                atEnd = true
                            } else {
                                atEnd = false
                            }
                            pos = raw.coerceIn(0f, 1f)
                            val next = scale.quantize(scale.fromPos(pos))
                            if (next != current.value) set(next)
                            guard.move(ch.uptimeMillis, ch.position, next)
                            if (next != ticked && !guard.settling(ch.uptimeMillis)) {
                                val k = scale.crossing(ticked, next)
                                if (k > 0) haptics.crossing(k)
                                ticked = next
                            }
                        }
                    } else Modifier.clickable {
                        // no DAC: look for it / ask for permission; connected but unread: the explicit first read
                        if (connected) device.readVolume() else device.connect()
                    },
                ),
        ) {
            val h = maxHeight
            val w = maxWidth
            val fillH = if (value == null) 0.dp else w + (h - w) * scale.toPos(value)
            val text = if (active) c.textDim else c.textMute
            VolumeLabels(value, text, tapToRead = connected && value == null)
            if (value != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(fillH)
                        .lift(shape, Lift.RAISED)
                        .background(c.text, shape)
                        .clip(shape),
                ) {
                    // the same labels at the bar's full height, pinned to the bottom: only the covered part shows
                    Box(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Bottom, unbounded = true).height(h)) {
                        VolumeLabels(value, c.bg, tapToRead = false)
                    }
                }
            }
        }
    }
}

/** The dB value at the top and HARDWARE VOLUME up the middle, both in [color]. */
@Composable
private fun VolumeLabels(value: Double?, color: Color, tapToRead: Boolean) {
    Box(Modifier.fillMaxSize()) {
        Text(
            if (value == null) "-" else Fmt.gain(value),
            style = Type.small,
            color = color,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
        )
        Text(
            if (tapToRead) "TAP TO READ" else "HARDWARE VOLUME",
            style = Type.segment,
            color = color,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.align(Alignment.Center).upright().rotate(-90f),
        )
    }
}

/** Lays the text out with width and height swapped, so the -90 degree rotation fits the narrow bar. */
private fun Modifier.upright() = layout { m, _ ->
    val p = m.measure(Constraints())
    layout(p.height, p.width) { p.place(-(p.width - p.height) / 2, (p.width - p.height) / 2) }
}
