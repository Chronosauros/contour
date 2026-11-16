package io.github.chronosauros.contour.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.chronosauros.contour.core.Band
import io.github.chronosauros.contour.core.Dsp
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.core.isPass
import io.github.chronosauros.contour.desktop.model.DesktopAppModel
import io.github.chronosauros.contour.desktop.ui.theme.ContourColors
import kotlin.math.*

/** Maps frequency (logarithmic) and gain (linear) to pixel coordinates. */
class DesktopPlotMap(
    val w: Float,
    val h: Float,
    val pad: Float = 36f,
    val gainLo: Double = -15.0,
    val gainHi: Double = 15.0
) {
    private val span = ln(1000.0)
    private val gainSpan = gainHi - gainLo

    fun x(f: Double): Float = (pad + (w - 2 * pad) * ln(f.coerceIn(20.0, 20000.0) / 20.0) / span).toFloat()
    fun y(g: Double): Float = (pad + (h - 2 * pad) * (gainHi - g.coerceIn(gainLo, gainHi)) / gainSpan).toFloat()

    fun f(px: Float): Double = 20.0 * exp(((px - pad) / (w - 2 * pad)).coerceIn(0f, 1f) * span)
    fun g(py: Float): Double = gainHi - gainSpan * ((py - pad) / (h - 2 * pad)).coerceIn(0f, 1f)
}

/** Interactive logarithmic frequency response curve (20 Hz - 20 kHz) with draggable band handles. */
@Composable
fun ResponseGraph(
    model: DesktopAppModel,
    profile: Profile,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val curve = remember { DoubleArray(Dsp.DISPLAY_POINTS) }
    val path = remember { Path() }
    val fillPath = remember { Path() }
    val dashedEffect = remember { PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f) }

    val caps = model.currentTarget.caps
    val gainLo = minOf(-12.0, caps.gainMinDb)
    val gainHi = maxOf(12.0, caps.gainMaxDb)

    var draggedBandIndex by remember { mutableStateOf<Int?>(null) }
    var plotMapState by remember { mutableStateOf<DesktopPlotMap?>(null) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(290.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ContourColors.Surface)
            .border(1.dp, ContourColors.SurfaceBorderGlow.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .pointerInput(profile, model.selectedBandIndex) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        // double tap on a node resets its gain to 0 dB
                        val pm = plotMapState ?: return@detectTapGestures
                        val closest = profile.bands.indices.minByOrNull { i ->
                            val b = profile.bands[i]
                            val bx = pm.x(b.freqHz)
                            val by = pm.y(if (b.enabled && !b.type.isPass) b.gainDb else 0.0)
                            hypot(bx - offset.x, by - offset.y)
                        }
                        if (closest != null) {
                            val b = profile.bands[closest]
                            val bx = pm.x(b.freqHz)
                            val by = pm.y(if (b.enabled && !b.type.isPass) b.gainDb else 0.0)
                            if (hypot(bx - offset.x, by - offset.y) < 32f) {
                                model.updateBand(closest, b.copy(gainDb = 0.0))
                            }
                        }
                    },
                    onTap = { offset ->
                        // single tap selects the tapped band
                        val pm = plotMapState ?: return@detectTapGestures
                        val closest = profile.bands.indices.minByOrNull { i ->
                            val b = profile.bands[i]
                            val bx = pm.x(b.freqHz)
                            val by = pm.y(if (b.enabled && !b.type.isPass) b.gainDb else 0.0)
                            hypot(bx - offset.x, by - offset.y)
                        }
                        if (closest != null) {
                            val b = profile.bands[closest]
                            val bx = pm.x(b.freqHz)
                            val by = pm.y(if (b.enabled && !b.type.isPass) b.gainDb else 0.0)
                            if (hypot(bx - offset.x, by - offset.y) < 36f) {
                                model.selectBand(closest)
                            }
                        }
                    }
                )
            }
            .pointerInput(profile) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val pm = plotMapState ?: return@detectDragGestures
                        val closest = profile.bands.indices.minByOrNull { i ->
                            val b = profile.bands[i]
                            val bx = pm.x(b.freqHz)
                            val by = pm.y(if (b.enabled && !b.type.isPass) b.gainDb else 0.0)
                            hypot(bx - offset.x, by - offset.y)
                        }
                        if (closest != null) {
                            val b = profile.bands[closest]
                            val bx = pm.x(b.freqHz)
                            val by = pm.y(if (b.enabled && !b.type.isPass) b.gainDb else 0.0)
                            if (hypot(bx - offset.x, by - offset.y) < 40f) {
                                draggedBandIndex = closest
                                model.selectBand(closest)
                            }
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val idx = draggedBandIndex ?: return@detectDragGestures
                        val pm = plotMapState ?: return@detectDragGestures
                        val b = profile.bands.getOrNull(idx) ?: return@detectDragGestures
                        val newFreq = pm.f(change.position.x).coerceIn(caps.freqMinHz, caps.freqMaxHz)
                        val newGain = if (b.type.isPass) 0.0 else pm.g(change.position.y).coerceIn(caps.gainMinDb, caps.gainMaxDb)
                        val roundedGain = round(newGain * 10.0) / 10.0
                        val roundedFreq = round(newFreq)
                        model.updateBand(idx, b.copy(freqHz = roundedFreq, gainDb = roundedGain))
                    },
                    onDragEnd = { draggedBandIndex = null },
                    onDragCancel = { draggedBandIndex = null }
                )
            }
    ) {
        val w = size.width
        val h = size.height
        val plotMap = DesktopPlotMap(w, h, pad = 36f, gainLo = gainLo, gainHi = gainHi)
        plotMapState = plotMap

        // frequency grid lines: 20 Hz to 20 kHz log-spaced lines
        val freqGrid = doubleArrayOf(20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0)
        val freqLabels = mapOf(100.0 to "100", 1000.0 to "1k", 10000.0 to "10k")

        for (f in freqGrid) {
            val x = plotMap.x(f)
            val isMajor = f in freqLabels.keys
            drawLine(
                color = if (isMajor) ContourColors.GridLineMajor else ContourColors.GridLine,
                start = Offset(x, plotMap.pad),
                end = Offset(x, h - plotMap.pad),
                strokeWidth = if (isMajor) 1.5f else 1f
            )
            val label = freqLabels[f]
            if (label != null) {
                drawText(
                    textMeasurer = textMeasurer,
                    text = label,
                    topLeft = Offset(x - 10f, h - plotMap.pad + 6f),
                    style = TextStyle(color = ContourColors.TextMuted, fontSize = 10.sp)
                )
            }
        }

        // amplitude grid lines: +/- 12 dB reference lines with highlighted zero axis
        val dbSteps = listOf(-12.0, -6.0, 0.0, 6.0, 12.0)
        for (g in dbSteps) {
            if (g in gainLo..gainHi) {
                val y = plotMap.y(g)
                val isZero = g == 0.0
                drawLine(
                    color = if (isZero) ContourColors.ZeroLine else ContourColors.GridLine,
                    start = Offset(plotMap.pad, y),
                    end = Offset(w - plotMap.pad, y),
                    strokeWidth = if (isZero) 1.8f else 1f
                )
                val txt = if (g > 0) "+${g.toInt()}" else "${g.toInt()}"
                drawText(
                    textMeasurer = textMeasurer,
                    text = "$txt dB",
                    topLeft = Offset(4f, y - 8f),
                    style = TextStyle(color = if (isZero) ContourColors.NeonPink else ContourColors.TextMuted, fontSize = 9.sp)
                )
            }
        }

        // preamp line: dashed guide showing the active attenuation
        val preamp = profile.preampDb ?: 0.0
        if (abs(preamp) > 0.01 && preamp in gainLo..gainHi) {
            val py = plotMap.y(preamp)
            drawLine(
                color = ContourColors.PreampLine.copy(alpha = 0.8f),
                start = Offset(plotMap.pad, py),
                end = Offset(w - plotMap.pad, py),
                strokeWidth = 1.5f,
                pathEffect = dashedEffect
            )
            drawText(
                textMeasurer = textMeasurer,
                text = "PRE ${preamp.roundToInt()} dB",
                topLeft = Offset(w - plotMap.pad - 65f, py - 14f),
                style = TextStyle(color = ContourColors.PreampLine, fontSize = 9.sp)
            )
        }

        // composite biquad response: evaluated at 512 points across 20 Hz - 20 kHz
        Dsp.responseDb(profile.bands, Dsp.DISPLAY_GRID, curve)
        path.reset()
        fillPath.reset()

        val y0 = plotMap.y(0.0)
        fillPath.moveTo(plotMap.x(Dsp.DISPLAY_GRID.freqs[0]), y0)

        for (i in 0 until Dsp.DISPLAY_POINTS) {
            val f = Dsp.DISPLAY_GRID.freqs[i]
            val totalDb = curve[i]
            val px = plotMap.x(f)
            val py = plotMap.y(totalDb)
            if (i == 0) {
                path.moveTo(px, py)
                fillPath.lineTo(px, py)
            } else {
                path.lineTo(px, py)
                fillPath.lineTo(px, py)
            }
        }
        fillPath.lineTo(plotMap.x(Dsp.DISPLAY_GRID.freqs[Dsp.DISPLAY_POINTS - 1]), y0)
        fillPath.close()

        // under-curve gradient fill
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(ContourColors.CurveFillTop, ContourColors.CurveFillMid, ContourColors.CurveFillBottom),
                startY = plotMap.y(gainHi),
                endY = plotMap.y(gainLo)
            )
        )

        // curve stroke: cyan to magenta gradient with soft underglow
        val curveGradient = Brush.horizontalGradient(
            colors = listOf(ContourColors.NeonCyan, ContourColors.NeonPurple, ContourColors.NeonPink),
            startX = plotMap.pad,
            endX = w - plotMap.pad
        )

        drawPath(
            path = path,
            brush = curveGradient,
            style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            alpha = 0.38f
        )

        drawPath(
            path = path,
            brush = curveGradient,
            style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // interactive band handles: node circles and selection halo
        for (i in profile.bands.indices) {
            val b = profile.bands[i]
            val bx = plotMap.x(b.freqHz)
            val by = plotMap.y(if (b.enabled && !b.type.isPass) b.gainDb else 0.0)
            val isSelected = i == model.selectedBandIndex

            if (isSelected) {
                drawCircle(
                    color = ContourColors.NodeSelected.copy(alpha = 0.45f),
                    radius = 18f,
                    center = Offset(bx, by)
                )
            }

            drawCircle(
                color = ContourColors.NodeFill,
                radius = 11f,
                center = Offset(bx, by)
            )

            drawCircle(
                color = if (!b.enabled) ContourColors.TextMuted else if (isSelected) ContourColors.NodeSelected else ContourColors.NodeNormal,
                radius = 11f,
                center = Offset(bx, by),
                style = Stroke(width = if (isSelected) 2.8f else 2.0f)
            )

            val numStr = "${i + 1}"
            val numMeasure = textMeasurer.measure(
                numStr,
                TextStyle(fontSize = 10.sp, color = if (isSelected) ContourColors.NodeSelected else ContourColors.TextPrimary)
            )
            drawText(
                textLayoutResult = numMeasure,
                topLeft = Offset(bx - numMeasure.size.width / 2f, by - numMeasure.size.height / 2f)
            )
        }
    }
}
