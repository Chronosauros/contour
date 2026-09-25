package io.github.chronosauros.contour.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.dp
import io.github.chronosauros.contour.core.Band
import io.github.chronosauros.contour.core.Dsp
import io.github.chronosauros.contour.ui.kit.LocalHaptics
import io.github.chronosauros.contour.usb.DeviceController
import io.github.chronosauros.contour.usb.Link

/**
 * Device status (a pill: label + LED), the same on both pages: tap = USB permission, long-press = service screen.
 * The pill is 36 dp tall inside a 48 dp touch target.
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
    Box(
        modifier
            .heightIn(min = 48.dp)
            .combinedClickable(
                indication = null,
                interactionSource = null,
                onClick = { device.requestPermission() },
                onLongClick = { haptics.longPress(); onService() },
            )
            .testTag("device_status"),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .lift(CircleShape)
                .background(p.surface, CircleShape)
                .padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(label, style = Type.label, color = p.textDim)
            Canvas(Modifier.size(8.dp)) {
                val r = size.minDimension / 2
                if (on) drawCircle(p.text, r)
                else drawCircle(p.textMute, r - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

/** A small real-response thumbnail of [bands] (enabled bands, -12..+12 dB). */
@Composable
fun ResponseThumb(bands: List<Band>, color: Color, modifier: Modifier = Modifier) {
    val db = remember(bands) { Dsp.responseDb(bands, THUMB_GRID) }
    Canvas(modifier) {
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
