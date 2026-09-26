package io.github.chronosauros.contour.ui.tune

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.chronosauros.contour.core.Band
import io.github.chronosauros.contour.core.Dsp
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.model.AppModel
import io.github.chronosauros.contour.ui.Type
import io.github.chronosauros.contour.ui.lift
import io.github.chronosauros.contour.ui.pal
import io.github.chronosauros.contour.ui.kit.LiftGuard
import io.github.chronosauros.contour.ui.kit.LocalHaptics
import io.github.chronosauros.contour.ui.kit.Scale
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln

/**
 * Plot mapping: log frequency 20 Hz - 20 kHz across, -12..+12 dB down, inside [PAD] on every side
 * (so the nodes at the edges stay whole). tools/ scripts use the same numbers to find nodes.
 */
class PlotMap(private val w: Float, private val h: Float, private val pad: Float) {
    private val span = ln(1000.0)
    fun x(f: Double): Float = (pad + (w - 2 * pad) * ln(f / 20.0) / span).toFloat()
    fun y(g: Double): Float = (pad + (h - 2 * pad) * (12.0 - g.coerceIn(-12.0, 12.0)) / 24.0).toFloat()
    fun f(x: Float): Double = 20.0 * exp(((x - pad) / (w - 2 * pad)).coerceIn(0f, 1f) * span)
    fun g(y: Float): Double = 12.0 - 24.0 * ((y - pad) / (h - 2 * pad)).coerceIn(0f, 1f)
}

private val PAD = 18.dp
private val NODE_RADIUS = 12.dp
private val GRAPH_SHAPE = RoundedCornerShape(18.dp)
private val GRID_F = doubleArrayOf(20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10_000.0, 20_000.0)
private val LABEL_F = listOf(100.0 to "100", 1000.0 to "1K", 10_000.0 to "10K")
private val GRID_DB = doubleArrayOf(-12.0, -6.0, 0.0, 6.0, 12.0)

/**
 * The response graph of the current profile. Every gesture that starts inside it belongs to it (the pager
 * never gets it): drag a node = frequency / gain, tap = select, double-tap = 0 dB, long-press on an empty
 * spot = new PEAK band, pinch = Q of the selected band. Lifting the finger off a node or out of a pinch keeps
 * the value it rested on ([LiftGuard]).
 */
@Composable
fun ResponseGraph(model: AppModel, profile: Profile, modifier: Modifier = Modifier) {
    val c = pal
    val haptics = LocalHaptics.current
    val nodePaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    val measurer = rememberTextMeasurer()
    val curve = remember { DoubleArray(Dsp.DISPLAY_POINTS) }
    val path = remember { Path() }
    val prof = rememberUpdatedState(profile)
    val selected = model.selectedBand
    val labelStyle = Type.graph.copy(color = c.textMute)
    val numberStyle = Type.node
    val lastTap = remember { longArrayOf(0L, -1L) } // time, band index

    Canvas(
        modifier
            .lift(GRAPH_SHAPE)
            .clip(GRAPH_SHAPE)
            .background(c.surface)
            .testTag("graph")
            // a node drag that starts near a screen edge must not become the system back gesture
            .systemGestureExclusion()
            .pointerInput(Unit) {
                val pad = PAD.toPx()
                val hitR = 24.dp.toPx()
                val nodeGuard = LiftGuard<Pair<Double, Double>>(density, "NODE")
                val pinchGuard = LiftGuard<Double>(density, "PINCH")
                awaitEachGesture {
                    val map = PlotMap(size.width.toFloat(), size.height.toFloat(), pad)
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val bands0 = prof.value.bands
                    val hit = bands0.indices.minByOrNull { i ->
                        hypot(map.x(bands0[i].freqHz) - down.position.x, map.y(bands0[i].gainDb) - down.position.y)
                    }?.takeIf { i ->
                        hypot(map.x(bands0[i].freqHz) - down.position.x, map.y(bands0[i].gainDb) - down.position.y) <= hitR
                    }
                    val slop = viewConfiguration.touchSlop
                    val longMs = viewConfiguration.longPressTimeoutMillis
                    val t0 = down.uptimeMillis
                    var mode = 0 // 0 = undecided, 1 = node drag, 2 = pinch, 3 = moved on empty space / done
                    var grab = Offset.Zero
                    var pinchD0 = 0f
                    var pinchQ0 = 1.0
                    var total = Offset.Zero
                    var lastUptime = t0
                    var pinching = false
                    var ticked = 0.0 to 0.0 // the node's (Hz, dB) the haptics last spoke for
                    var qTicked = 1.0
                    if (hit != null) {
                        val b = bands0[hit]
                        ticked = b.freqHz to b.gainDb
                        nodeGuard.start(t0, down.position, ticked)
                    }
                    while (true) {
                        val waitLong = mode == 0 && hit == null
                        val ev = if (waitLong) {
                            val left = longMs - (lastUptime - t0)
                            withTimeoutOrNull(left.coerceAtLeast(1)) { awaitPointerEvent() }
                        } else {
                            awaitPointerEvent()
                        }
                        if (ev == null) { // long press on an empty spot
                            mode = 3
                            val p = prof.value
                            if (p.bands.size >= AppModel.MAX_BANDS) haptics.reject()
                            else {
                                haptics.longPress()
                                model.addBand(map.f(down.position.x), map.g(down.position.y).coerceIn(-10.0, 10.0))
                            }
                            continue
                        }
                        ev.changes.forEach { it.consume() } // the graph owns every gesture (moves read with IgnoreConsumed)
                        lastUptime = ev.changes.first().uptimeMillis
                        val pressed = ev.changes.filter { it.pressed }
                        if (pinching && pressed.size < 2) { // a pinch ends when a finger lifts
                            pinching = false
                            val i = model.selectedBand
                            pinchGuard.release(lastUptime)?.let { q ->
                                prof.value.bands.getOrNull(i)?.let { model.setBand(i, it.copy(q = q)) }
                            }
                        }
                        if (pressed.isEmpty()) {
                            if (mode == 1) {
                                nodeGuard.release(lastUptime)?.let { (f, g) ->
                                    prof.value.bands.getOrNull(hit!!)?.let { model.setBand(hit, it.copy(freqHz = f, gainDb = g)) }
                                }
                            }
                            if (mode == 0 && hit != null) {
                                val now = ev.changes.first().uptimeMillis
                                if (lastTap[1] == hit.toLong() && now - lastTap[0] < viewConfiguration.doubleTapTimeoutMillis) {
                                    val b = prof.value.bands.getOrNull(hit)
                                    if (b != null) {
                                        haptics.detent()
                                        model.setBand(hit, b.copy(gainDb = 0.0))
                                    }
                                    lastTap[1] = -1
                                } else {
                                    haptics.tap()
                                    model.selectBand(hit)
                                    lastTap[0] = now
                                    lastTap[1] = hit.toLong()
                                }
                            }
                            break
                        }
                        if (pressed.size >= 2) {
                            val d = (pressed[0].position - pressed[1].position).getDistance()
                            if (!pinching) { // a new pinch, also when a second finger comes back
                                mode = 2
                                pinching = true
                                pinchD0 = d.coerceAtLeast(1f)
                                pinchQ0 = prof.value.bands.getOrNull(model.selectedBand)?.q ?: 1.0
                                qTicked = pinchQ0
                                pinchGuard.start(lastUptime, Offset(d, 0f), pinchQ0)
                            } else {
                                val i = model.selectedBand
                                val b = prof.value.bands.getOrNull(i) ?: continue
                                val q = Scale.Q.quantize(pinchQ0 * pinchD0 / d.coerceAtLeast(1f))
                                if (q != b.q) model.setBand(i, b.copy(q = q))
                                pinchGuard.move(lastUptime, Offset(d, 0f), q)
                                if (q != qTicked && !pinchGuard.settling(lastUptime)) {
                                    val k = Param.Q.crossing(qTicked, q)
                                    if (k > 0) haptics.crossing(k)
                                    qTicked = q
                                }
                            }
                            continue
                        }
                        val ch = pressed[0]
                        when (mode) {
                            0 -> {
                                total += ch.positionChangeIgnoreConsumed()
                                if (total.getDistance() > slop) {
                                    if (hit != null) {
                                        mode = 1
                                        model.selectBand(hit)
                                        val b = prof.value.bands[hit]
                                        grab = Offset(map.x(b.freqHz), map.y(b.gainDb)) - ch.position
                                        nodeGuard.move(ch.uptimeMillis, ch.position, b.freqHz to b.gainDb)
                                    } else {
                                        mode = 3
                                    }
                                }
                            }
                            1 -> {
                                val b = prof.value.bands.getOrNull(hit!!) ?: continue
                                val pos = ch.position + grab
                                val f = Scale.FREQ.quantize(map.f(pos.x))
                                val g = Scale.GAIN.quantize(map.g(pos.y).coerceIn(-10.0, 10.0))
                                if (f != b.freqHz || g != b.gainDb) model.setBand(hit, b.copy(freqHz = f, gainDb = g))
                                nodeGuard.move(ch.uptimeMillis, ch.position, f to g)
                                if ((f to g) != ticked && !nodeGuard.settling(ch.uptimeMillis)) {
                                    val k = maxOf(Param.FREQ.crossing(ticked.first, f), Param.GAIN.crossing(ticked.second, g))
                                    if (k > 0) haptics.crossing(k)
                                    ticked = f to g
                                }
                            }
                        }
                    }
                }
            },
    ) {
        val pad = PAD.toPx()
        val map = PlotMap(size.width, size.height, pad)
        for (f in GRID_F) {
            val x = map.x(f)
            drawLine(c.grid, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
        }
        for (g in GRID_DB) {
            val y = map.y(g)
            drawLine(if (g == 0.0) c.track else c.grid, Offset(0f, y), Offset(size.width, y), if (g == 0.0) 1.5.dp.toPx() else 1.dp.toPx())
        }
        for ((f, s) in LABEL_F) {
            drawText(measurer, s, Offset(map.x(f) + 4.dp.toPx(), size.height - pad - 12.dp.toPx()), labelStyle)
        }
        for (g in doubleArrayOf(6.0, -6.0)) {
            drawText(measurer, if (g > 0) "+6" else "-6", Offset(pad - 6.dp.toPx(), map.y(g) - 17.dp.toPx()), labelStyle)
        }

        val bands: List<Band> = profile.bands
        Dsp.responseDb(bands, Dsp.DISPLAY_GRID, curve)
        path.reset()
        val freqs = Dsp.DISPLAY_GRID.freqs
        for (i in curve.indices) {
            val x = map.x(freqs[i])
            val y = map.y(curve[i])
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, c.text, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        val r = NODE_RADIUS.toPx()
        // nodes stand a little above the plot: a small soft shadow under each enabled node
        val shadowA = 0.5f * c.shadowAlpha
        bands.forEachIndexed { i, b ->
            val o = Offset(map.x(b.freqHz), map.y(b.gainDb))
            val sel = i == selected
            val fill = if (sel) c.accent else c.fill
            if (b.enabled) {
                nodePaint.color = fill.toArgb()
                nodePaint.setShadowLayer(4.dp.toPx(), 0f, 1.5.dp.toPx(), c.shadow.copy(alpha = shadowA).toArgb())
                drawIntoCanvas { it.nativeCanvas.drawCircle(o.x, o.y, r, nodePaint) }
            } else {
                drawCircle(c.surface, r, o)
                drawCircle(fill, r - 1.dp.toPx(), o, style = Stroke(2.dp.toPx()))
            }
            val t = measurer.measure("${i + 1}", numberStyle.copy(color = if (!b.enabled) fill else if (sel) c.onAccent else c.bg))
            drawText(t, topLeft = Offset(o.x - t.size.width / 2f, o.y - t.size.height / 2f))
        }
    }
}
