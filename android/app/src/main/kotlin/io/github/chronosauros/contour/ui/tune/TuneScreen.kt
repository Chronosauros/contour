package io.github.chronosauros.contour.ui.tune

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.floor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.chronosauros.contour.core.FilterType
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.core.ProtocolMicro
import io.github.chronosauros.contour.model.AppModel
import io.github.chronosauros.contour.model.Sender
import io.github.chronosauros.contour.model.shownPreamp
import io.github.chronosauros.contour.ui.DeviceStatus
import io.github.chronosauros.contour.ui.Lift
import io.github.chronosauros.contour.ui.Type
import io.github.chronosauros.contour.ui.kit.LiftGuard
import io.github.chronosauros.contour.ui.kit.LocalHaptics
import io.github.chronosauros.contour.ui.kit.detectHorizontalDragWithEnds
import io.github.chronosauros.contour.ui.kit.ProfileIcons
import io.github.chronosauros.contour.ui.lift
import io.github.chronosauros.contour.ui.pal
import io.github.chronosauros.contour.ui.sink
import io.github.chronosauros.contour.usb.DeviceController

/** What Tune asks of the app shell. */
interface TuneActions {
    fun edit(id: String)
    fun value(param: Param)
    fun band(index: Int)
    fun sendDetails()
    fun service()
}

private val SIDE = 14.dp
private val GAP = 8.dp
private val ROW_H = 56.dp
private val ROW_SHAPE = RoundedCornerShape(16.dp)

/** Tune: the current profile - graph, bands, the selected band's values, preamp, HOLD TO SEND. */
@Composable
fun TuneScreen(model: AppModel, device: DeviceController, sender: Sender, actions: TuneActions, top: Dp, bottom: Dp) {
    val p = model.current
    Column(
        Modifier
            .fillMaxSize()
            // One touch = one UNDO step: opened before any child sees the first finger, closed after every
            // child (a drag's lift-off value included) has handled the last finger going up.
            .pointerInput(model) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.changes.any { it.changedToDown() }) model.beginGesture()
                    }
                }
            }
            .pointerInput(model) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Final)
                        if (e.changes.none { it.pressed }) model.endGesture()
                    }
                }
            }
            .padding(top = top, bottom = bottom)
            .padding(horizontal = SIDE),
        verticalArrangement = Arrangement.spacedBy(GAP),
    ) {
        Header(model, p, device, actions)
        if (p == null) {
            Box(Modifier.fillMaxWidth().weight(1f).testTag("tune_empty"), contentAlignment = Alignment.Center) {
                Text(
                    "NO PROFILE\nSwipe to the Library to choose or create one.",
                    textAlign = TextAlign.Center,
                    color = pal.textDim,
                )
            }
            return@Column
        }
        ResponseGraph(model, p, Modifier.fillMaxWidth().weight(1f))
        BandStrip(model, p, actions)
        val i = model.selectedBand
        val b = p.bands.getOrNull(i)
        if (b != null) {
            TypeRow(model, b.type)
            for (param in listOf(Param.FREQ, Param.GAIN, Param.Q)) {
                ParamRow(param, param.of(b), onTap = { actions.value(param) }) { v ->
                    model.transformBandIfCurrent(p.id, i, b.id) { current -> param.set(current, v) }
                }
            }
        }
        PreampRow(model, p, actions)
        Row(Modifier.fillMaxWidth().height(58.dp), horizontalArrangement = Arrangement.spacedBy(GAP)) {
            if (model.canRevertToSent(p)) LastSentButton(model)
            HoldToSend(p, device, sender, actions::sendDetails, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun Header(model: AppModel, p: Profile?, device: DeviceController, actions: TuneActions) {
    val c = pal
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        if (p != null) {
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { actions.edit(p.id) }
                    .padding(start = 6.dp, end = 8.dp)
                    .testTag("tune_name"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(ProfileIcons.of(p.icon), null, tint = c.text, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(14.dp))
                // a long name steps down from the title size before it is cut (the header shares its row with UNDO/REDO)
                BasicText(
                    p.name,
                    style = Type.profileTitle.copy(color = c.text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(minFontSize = 17.sp, maxFontSize = Type.profileTitle.fontSize, stepSize = 1.sp),
                )
            }
        } else {
            Text("TUNE", style = Type.profileTitle, color = c.text, modifier = Modifier.weight(1f).padding(start = 6.dp))
        }
        if (p != null) {
            HistoryButton(Icons.AutoMirrored.Rounded.Undo, "undo", model.canUndo(p), model::undo)
            HistoryButton(Icons.AutoMirrored.Rounded.Redo, "redo", model.canRedo(p), model::redo)
            Spacer(Modifier.width(4.dp))
        }
        DeviceStatus(device, actions::service)
    }
}

/** UNDO / REDO in the header: bright when there is a step to take, dimmed (a reject tick) when not. */
@Composable
private fun HistoryButton(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, enabled: Boolean, onClick: () -> Unit) {
    val c = pal
    val haptics = LocalHaptics.current
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { if (enabled) { haptics.tap(); onClick() } else haptics.reject() }
            .testTag("history_$name"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, name, tint = if (enabled) c.text else c.textMute, modifier = Modifier.size(26.dp))
    }
}

/** LAST SENT, beside HOLD TO SEND while the EQ differs from the one last verified on the DAC: tap = back to it. */
@Composable
private fun LastSentButton(model: AppModel) {
    val c = pal
    val haptics = LocalHaptics.current
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier
            .fillMaxHeight()
            .lift(shape, Lift.RAISED)
            .clip(shape)
            .background(c.surface2)
            .clickable { haptics.tap(); model.revertToSent() }
            .padding(horizontal = 18.dp)
            .testTag("last_sent"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.History, null, tint = c.text, modifier = Modifier.size(22.dp))
        Text("LAST SENT", style = Type.label, color = c.text, maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BandStrip(model: AppModel, p: Profile, actions: TuneActions) {
    val c = pal
    val haptics = LocalHaptics.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().height(50.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        p.bands.forEachIndexed { i, b ->
            val sel = i == model.selectedBand
            // enabled bands stand up from the page, a disabled one is pressed into it
            val chip = Modifier
                .widthIn(min = 48.dp)
                .fillMaxHeight()
                .let { if (b.enabled || sel) it.lift(shape, Lift.RAISED) else it }
                .clip(shape)
                .background(if (sel) c.accent else if (b.enabled) c.surface2 else c.sunken)
                .let { if (b.enabled || sel) it else it.sink(shape) }
            Box(
                chip
                    .combinedClickable(
                        onClick = { haptics.tap(); model.selectBand(i) },
                        onLongClick = { haptics.longPress(); model.selectBand(i); actions.band(i) },
                    )
                    .testTag("chip_${i + 1}"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${i + 1}",
                    style = Type.chip,
                    color = if (sel) c.onAccent else if (b.enabled) c.text else c.textMute,
                )
            }
        }
        if (p.bands.size < AppModel.MAX_BANDS) {
            Box(
                Modifier
                    .widthIn(min = 48.dp)
                    .fillMaxHeight()
                    .lift(shape, Lift.RAISED)
                    .clip(shape)
                    .background(c.surface2)
                    .clickable { haptics.tap(); model.addBand() }
                    .testTag("chip_add"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Add, "add band", tint = c.text, modifier = Modifier.size(26.dp))
            }
        }

    }
}

/** Filter type: a pressed-in well with a raised orange pill that slides to the chosen type. */
@Composable
private fun TypeRow(model: AppModel, type: FilterType) {
    val c = pal
    val haptics = LocalHaptics.current
    val types = listOf(FilterType.PEAK to "PEAK", FilterType.LOW_SHELF to "LOW SHELF", FilterType.HIGH_SHELF to "HIGH SHELF")
        .filter { it.first in ProtocolMicro.CAPABILITIES.types }
    val well = RoundedCornerShape(26.dp)
    val pill = RoundedCornerShape(22.dp)
    val idx = types.indexOfFirst { it.first == type }.coerceAtLeast(0)
    val at by animateFloatAsState(idx.toFloat(), label = "type")
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(well)
            .background(c.surface)
            .sink(well)
            .padding(4.dp),
    ) {
        val w = maxWidth / types.size
        Box(
            Modifier
                .offset(x = w * at)
                .width(w)
                .fillMaxHeight()
                .lift(pill, Lift.RAISED)
                .background(c.accent, pill),
        )
        Row(Modifier.fillMaxSize()) {
            types.forEach { (t, label) ->
                val sel = t == type
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(pill)
                        .clickable { if (!sel) { haptics.segment(); model.setType(t) } }
                        .testTag("type_${t.name.lowercase()}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = Type.segment, color = if (sel) c.onAccent else c.text, maxLines = 1)
                }
            }
        }
    }
}

/** One value row: label, the value (tap = type it), and the slider as tall as the card allows. */
@Composable
private fun ParamRow(param: Param, value: Double, onTap: () -> Unit, onChange: (Double) -> Unit) {
    val c = pal
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_H)
            .lift(ROW_SHAPE)
            .background(c.surface, ROW_SHAPE)
            .padding(start = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(param.label, style = Type.label, color = c.textDim, modifier = Modifier.width(48.dp))
        Box(
            Modifier
                .width(128.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onTap)
                .testTag("value_${param.name.lowercase()}"),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(param.text(value), style = Type.value, color = c.text, maxLines = 1, softWrap = false)
        }
        RelSlider(param, value, onChange, Modifier.weight(1f).fillMaxHeight().padding(end = 5.dp))
    }
}

/** Manual preamp drag: dB per dp of travel for a fast finger (the 30 dB range in about a screen width)... */
private const val PREAMP_DB_PER_DP = 0.08

/** ...and the share of it for a slow finger (0.02 dB a dp: four times finer, like the sliders, owner 26.09). */
private const val PREAMP_SLOW = 0.25f

/**
 * PREAMP: the value and AUTO. With AUTO off the value is manual: tap = type it, drag sideways = adjust it
 * (0.1 dB steps, a tick at every whole dB, a strong one at 0 dB, a reject tick at the device limits; lifting
 * the finger off a value keeps it, [LiftGuard]).
 */
@Composable
private fun PreampRow(model: AppModel, p: Profile, actions: TuneActions) {
    val c = pal
    val haptics = LocalHaptics.current
    val auto = p.preampDb == null
    val autoDb = runCatching { shownPreamp(p.copy(preampDb = null)) }.getOrNull()
    val db = if (auto) autoDb else p.preampDb
    val prof = rememberUpdatedState(p)
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_H)
            .lift(ROW_SHAPE)
            .background(c.surface, ROW_SHAPE)
            .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("PREAMP", style = Type.label, color = c.textDim)
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .then(if (auto || db == null) Modifier else Modifier.pointerInput(p.id) {
                    val guard = LiftGuard<Double>(density, "PREAMP")
                    var acc = 0.0
                    var atEnd = false
                    var speed = 0f
                    var ticked = 0.0 // the value the haptics last spoke for
                    detectHorizontalDragWithEnds(
                        onStart = { down ->
                            prof.value.preampDb?.let { start ->
                                acc = start
                                atEnd = false
                                speed = 0f
                                ticked = acc
                                guard.start(down.uptimeMillis, down.position, acc)
                            }
                        },
                        onEnd = { up -> if (up != null) guard.release(up)?.let { model.setPreamp(it) } },
                    ) { ch, dx ->
                        ch.consume()
                        val now = prof.value.preampDb ?: return@detectHorizontalDragWithEnds
                        val hs = ProtocolMicro.highShelfGainSum(prof.value.bands)
                        val lo = ProtocolMicro.PREAMP_MIN_DB - hs
                        val hi = ProtocolMicro.PREAMP_MAX_DB - hs
                        val dt = (ch.uptimeMillis - ch.previousUptimeMillis).coerceAtLeast(1L).toFloat()
                        speed = 0.6f * speed + 0.4f * (kotlin.math.abs(dx) / density / dt)
                        val rate = PREAMP_DB_PER_DP * ratioLerp(PREAMP_SLOW, 1f, speedRamp(speed))
                        val raw = acc + dx.toDp().value * rate
                        if (raw < lo || raw > hi) {
                            if (!atEnd) haptics.reject()
                            atEnd = true
                        } else {
                            atEnd = false
                        }
                        acc = raw.coerceIn(lo, hi)
                        val next = Math.round(acc * 10) / 10.0
                        if (next != now) model.setPreamp(next)
                        guard.move(ch.uptimeMillis, ch.position, next)
                        if (next != ticked && !guard.settling(ch.uptimeMillis)) {
                            if (floor(next) != floor(ticked)) haptics.crossing(if (next == 0.0) 2 else 1)
                            ticked = next
                        }
                    }
                })
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = !auto && db != null) { actions.value(Param.PREAMP) }
                .testTag("value_preamp"),
            contentAlignment = Alignment.CenterStart,
        ) {
            // AUTO: a computed value, dimmed; manual: bright, and it can be dragged
            Text(db?.let { Param.PREAMP.text(it) } ?: "INVALID EQ", style = Type.value, color = if (auto) c.textDim else c.text, maxLines = 1, softWrap = false)
        }
        Text("AUTO", style = Type.label, color = c.textDim)
        if (autoDb == null) Text("INVALID EQ", style = Type.label, color = c.textDim)
        else Toggle(auto, { model.setPreampAuto(it) }, Modifier.testTag("preamp_auto"))
    }
}
