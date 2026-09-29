package io.github.chronosauros.contour.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.chronosauros.contour.core.Band
import io.github.chronosauros.contour.core.Dsp
import io.github.chronosauros.contour.ui.kit.LocalHaptics
import io.github.chronosauros.contour.usb.DeviceController
import io.github.chronosauros.contour.usb.Link

/** The status LED when the DAC is connected. */
private val LED_ON = Color(0xFF46D17A)

/**
 * Device status in the Library (a pill: label + LED): tap = USB permission, long-press = service screen.
 * Matches CLEAR EQ's 48 dp height and M corners; NO DAC also matches its text-based width.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeviceStatus(device: DeviceController, onService: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHaptics.current
    val p = pal
    val label = when {
        device.busy -> "BUSY"
        device.link == Link.NO_DAC -> "NO DAC"
        device.link == Link.NEEDS_PERMISSION -> "TAP TO CONNECT"
        else -> "CONNECTED"
    }
    val on = device.link == Link.CONNECTED
    val shape = RoundedCornerShape(Radii.M)
    val textMeasurer = rememberTextMeasurer()
    val compactWidth = with(LocalDensity.current) {
        textMeasurer.measure("CLEAR EQ", style = Type.label).size.width.toDp()
    } + 32.dp
    Box(
        modifier
            .height(Grid.ROW - Grid.INSET * 2)
            .then(if (label == "NO DAC") Modifier.width(compactWidth) else Modifier.widthIn(min = compactWidth))
            .lift(shape, Lift.RAISED)
            .clip(shape)
            .background(p.surface)
            .combinedClickable(
                indication = null,
                interactionSource = null,
                onClick = { device.connect() },
                onLongClick = { haptics.longPress(); onService() },
            )
            .testTag("device_status"),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(label, style = Type.label, color = p.textDim, maxLines = 1, softWrap = false)
            Canvas(Modifier.size(8.dp)) {
                val r = size.minDimension / 2
                if (on) drawCircle(LED_ON, r)
                else drawCircle(p.textMute, r - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

/** A small real-response thumbnail of [bands] (enabled bands, -12..+12 dB). */
@Composable
fun ResponseThumb(bands: List<Band>, color: Color, modifier: Modifier = Modifier) {
    val db = remember(bands) { runCatching { Dsp.responseDb(bands, THUMB_GRID) }.getOrNull() }
    Canvas(modifier) {
        if (db == null) return@Canvas // Invalid response must never be drawn as a flat or NaN curve.
        val h = size.height
        val path = Path()
        for (i in db.indices) {
            val x = size.width * i / (db.size - 1)
            val y = (h / 2 - (db[i].coerceIn(-12.0, 12.0) / 12.0 * h / 2)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private val THUMB_GRID = Dsp.FreqGrid(Dsp.logFreqs(64))
