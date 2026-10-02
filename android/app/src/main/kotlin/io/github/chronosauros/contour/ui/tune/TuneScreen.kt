package io.github.chronosauros.contour.ui.tune

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
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
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.chronosauros.contour.core.FilterType
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.core.ProtocolMicro
import io.github.chronosauros.contour.model.AppModel
import io.github.chronosauros.contour.model.Sender
import io.github.chronosauros.contour.model.shownPreamp
import io.github.chronosauros.contour.ui.Grid
import io.github.chronosauros.contour.ui.Lift
import io.github.chronosauros.contour.ui.Type
import io.github.chronosauros.contour.ui.kit.LiftGuard
import io.github.chronosauros.contour.ui.kit.LocalHaptics
import io.github.chronosauros.contour.ui.kit.LocalPagerLock
import io.github.chronosauros.contour.ui.kit.detectHorizontalDragWithEnds
import io.github.chronosauros.contour.ui.kit.holdsPager
import io.github.chronosauros.contour.ui.kit.ProfileIcons
import io.github.chronosauros.contour.BuildConfig
import io.github.chronosauros.contour.ui.lift
import io.github.chronosauros.contour.ui.Radii
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

private val SIDE = Grid.SIDE
private val GAP = Grid.GAP
private val ROW_H = Grid.ROW
private val COMPACT_H = ROW_H - Grid.INSET * 2
private val ROW_SHAPE = RoundedCornerShape(Radii.L)
private val GRAPH_MIN = 200.dp

/** Tune: the current profile - graph, bands, the selected band's values, preamp, HOLD TO SEND. */
@Composable
fun TuneScreen(model: AppModel, device: DeviceController, sender: Sender, actions: TuneActions, top: Dp, bottom: Dp) {
    val p = model.current
    val bottomPad = bottom + (Grid.GROUP - GAP)
    val gestures = Modifier
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
    if (p == null) {
        Column(
            gestures.padding(top = top, bottom = bottomPad).padding(horizontal = SIDE),
            verticalArrangement = Arrangement.spacedBy(GAP),
        ) {
            Header(model, p, sender, actions)
            Box(Modifier.fillMaxWidth().weight(1f).testTag("tune_empty"), contentAlignment = Alignment.Center) {
                Text(
                    "NO PROFILE\nSwipe to the Library to choose or create one.",
                    textAlign = TextAlign.Center,
                    color = pal.textDim,
                )
            }
        }
        return
    }
    val bandsEnabled = !sender.bypassed && !sender.abBusy
    // The graph takes whatever height is left, but never less than GRAPH_MIN; on short screens
    // (FiiO JM21) the whole page scrolls instead of squashing the graph.
    BoxWithConstraints(gestures) {
        val viewport = constraints.maxHeight
        Layout(
            contents = listOf(
                { Header(model, p, sender, actions) },
                {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            ResponseGraph(model, p, Modifier.fillMaxSize(), bypassed = sender.bypassed, enabled = bandsEnabled)
                            if (sender.canAb(p)) AbButton(sender, p, Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp))
                        }
                        // Contour advBeta: the DAC's hardware volume, upright beside the graph
                        if (BuildConfig.ADVANCED) VolumeBar(device, Modifier.width(ROW_H).fillMaxHeight())
                    }
                },
                { TuneControls(model, p, device, sender, actions, bandsEnabled) },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = top, bottom = bottomPad)
                .padding(horizontal = SIDE),
        ) { (header, graph, controls), cs ->
            val gap = GAP.roundToPx()
            val loose = Constraints(maxWidth = cs.maxWidth)
            val h = header.first().measure(loose)
            val r = controls.first().measure(loose)
            val free = viewport - top.roundToPx() - bottomPad.roundToPx() - h.height - r.height - gap * 2
            val gh = maxOf(GRAPH_MIN.roundToPx(), free)
            val g = graph.first().measure(Constraints.fixed(cs.maxWidth, gh))
            layout(cs.maxWidth, h.height + gap + gh + gap + r.height) {
                h.place(0, 0)
                g.place(0, h.height + gap)
                r.place(0, h.height + gap + gh + gap)
            }
        }
    }
}

/** Everything under the graph: bands, the selected band's values, preamp, HOLD TO SEND. */
@Composable
private fun TuneControls(model: AppModel, p: Profile, device: DeviceController, sender: Sender, actions: TuneActions, bandsEnabled: Boolean) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GAP)) {
        Column(Modifier.alpha(if (sender.bypassed) 0.4f else 1f), verticalArrangement = Arrangement.spacedBy(GAP)) {
            BandStrip(model, p, actions, bandsEnabled)
            val i = model.selectedBand
            val b = p.bands.getOrNull(i)
            if (b != null) {
                TypeRow(model, b.type, bandsEnabled)
                for (param in listOf(Param.FREQ, Param.GAIN, Param.Q)) {
                    ParamRow(param, param.of(b), enabled = bandsEnabled, onTap = { actions.value(param) }) { v ->
                        if (!sender.bypassed && !sender.abBusy)
                            model.transformBandIfCurrent(p.id, i, b.id) { current -> param.set(current, v) }
                    }
                }
            }
        }
        PreampRow(model, p, sender, actions)
        Row(Modifier.padding(top = Grid.GROUP - GAP).fillMaxWidth().height(ROW_H), horizontalArrangement = Arrangement.spacedBy(GAP)) {
            if (model.canRevertToSent(p)) LastSentButton(model, sender)
            HoldToSend(p, device, sender, actions::sendDetails, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun Header(model: AppModel, p: Profile?, sender: Sender, actions: TuneActions) {
    val c = pal
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        if (p != null) {
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(Radii.M))
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
            HistoryButton(Icons.AutoMirrored.Rounded.Undo, "undo", model.canUndo(p)) { sender.leaveAb { model.undo() } }
            HistoryButton(Icons.AutoMirrored.Rounded.Redo, "redo", model.canRedo(p)) { sender.leaveAb { model.redo() } }
            Spacer(Modifier.width(4.dp))
        }
        val canClear = p != null && !model.isClear(p) && !sender.bypassed
        ClearEqButton(available = canClear, enabled = canClear && !sender.busy) {
            if (p != null && !sender.bypassed && !sender.busy) sender.leaveAb { restored ->
                if (restored && model.currentId == p.id) model.clearEq(p.id)
            }
        }
    }
}

/** Clear the current EQ in one undoable step; orange while there is an EQ to clear. */
@Composable
private fun ClearEqButton(available: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = pal
    val haptics = LocalHaptics.current
    val shape = RoundedCornerShape(Radii.M)
    // A brief USB operation blocks taps without flashing the colour; actual bypass/flat EQ dims the button.
    val fill by animateColorAsState(if (available) c.accent else c.surface, tween(220), label = "clear-eq-fill")
    val ink by animateColorAsState(if (available) c.onAccent else c.textMute, tween(220), label = "clear-eq-ink")
    Box(
        Modifier
            .height(COMPACT_H)
            .lift(shape, Lift.RAISED)
            .clip(shape)
            .background(fill)
            .clickable(enabled = enabled, role = Role.Button) { haptics.tap(); onClick() }
            .padding(horizontal = 16.dp)
            .testTag("clear_eq"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "CLEAR EQ",
            style = Type.label,
            color = ink,
            maxLines = 1,
        )
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
            .clip(RoundedCornerShape(Radii.M))
            .clickable { if (enabled) { haptics.tap(); onClick() } else haptics.reject() }
            .testTag("history_$name"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, name, tint = if (enabled) c.text else c.textMute, modifier = Modifier.size(26.dp))
    }
}

/** LAST SENT, beside HOLD TO SEND while the EQ differs from the one last verified on the DAC: tap = back to it. */
@Composable
private fun LastSentButton(model: AppModel, sender: Sender) {
    val c = pal
    val haptics = LocalHaptics.current
    val shape = RoundedCornerShape(Radii.L)
    Row(
        Modifier
            .fillMaxHeight()
            .lift(shape, Lift.RAISED)
            .clip(shape)
            .background(c.surface2)
            .clickable { haptics.tap(); sender.leaveAb { model.revertToSent() } }
            .padding(horizontal = Grid.TEXT)
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
private fun BandStrip(model: AppModel, p: Profile, actions: TuneActions, enabled: Boolean) {
    val c = pal
    val haptics = LocalHaptics.current
    val shape = RoundedCornerShape(Radii.M)
    Row(
        Modifier.fillMaxWidth().height(COMPACT_H).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(GAP),
    ) {
        p.bands.forEachIndexed { i, b ->
            val sel = i == model.selectedBand
            // enabled bands stand up from the page, a disabled one is pressed into it
            val chip = Modifier
                .widthIn(min = COMPACT_H)
                .fillMaxHeight()
                .let { if (b.enabled || sel) it.lift(shape, Lift.RAISED) else it }
                .clip(shape)
                .background(if (sel) c.accent else if (b.enabled) c.surface2 else c.track)
                .let { if (b.enabled || sel) it else it.sink(shape) }
            Box(
                chip
                    .combinedClickable(
                        enabled = enabled,
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
                    .widthIn(min = COMPACT_H)
                    .fillMaxHeight()
                    .lift(shape, Lift.RAISED)
                    .clip(shape)
                    .background(c.surface2)
                    .clickable(enabled = enabled) { haptics.tap(); model.addBand() }
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
private fun TypeRow(model: AppModel, type: FilterType, enabled: Boolean) {
    val c = pal
    val haptics = LocalHaptics.current
    val types = listOf(FilterType.PEAK to "PEAK", FilterType.LOW_SHELF to "LOW SHELF", FilterType.HIGH_SHELF to "HIGH SHELF")
        .filter { it.first in ProtocolMicro.CAPABILITIES.types }
    val well = RoundedCornerShape(Radii.L)
    val pill = RoundedCornerShape(Radii.M)
    val idx = types.indexOfFirst { it.first == type }.coerceAtLeast(0)
    val at by animateFloatAsState(idx.toFloat(), label = "type")
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(ROW_H)
            .clip(well)
            .background(c.track)
            .sink(well)
            .padding(Grid.INSET),
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
                        .clickable(enabled = enabled) { if (!sel) { haptics.segment(); model.setType(t) } }
                        .testTag("type_${t.name.lowercase()}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = Type.segment, color = if (sel) c.onAccent else c.text, maxLines = 1)
                }
            }
        }
    }
}

/** A small label over the value: the number big, its unit small and dim beside it. [unit] is empty for Q. */
@Composable
private fun StackedValue(label: String, number: String, unit: String, numberColor: Color, modifier: Modifier = Modifier) {
    val c = pal
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(label, style = Type.paramLabel, color = c.textDim, maxLines = 1)
        Spacer(Modifier.height(1.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = numberColor)) { append(number) }
                if (unit.isNotEmpty()) withStyle(SpanStyle(color = c.textDim, fontSize = Type.paramLabel.fontSize)) { append(" $unit") }
            },
            style = Type.value,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Width of the label-and-value column: FREQ, GAIN, Q and PREAMP line up on it. */
private val VALUE_W = 108.dp

/** One value row: the label over the value (tap = type it), and the slider as tall as the card allows. */
@Composable
private fun ParamRow(param: Param, value: Double, enabled: Boolean, onTap: () -> Unit, onChange: (Double) -> Unit) {
    val c = pal
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_H)
            .lift(ROW_SHAPE)
            .background(c.surface, ROW_SHAPE)
            .padding(start = Grid.TEXT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(VALUE_W - Grid.TEXT)
                .fillMaxHeight()
                .clip(RoundedCornerShape(Radii.S))
                .clickable(enabled = enabled, onClick = onTap)
                .testTag("value_${param.name.lowercase()}"),
            contentAlignment = Alignment.CenterStart,
        ) {
            StackedValue(param.label, param.number(value), param.unit, c.text)
        }
        RelSlider(param, value, onChange, Modifier.weight(1f).fillMaxHeight().padding(Grid.INSET), enabled = enabled)
    }
}

/** Manual preamp drag: dB per dp of travel for a fast finger (the 30 dB range in about a screen width)... */
private const val PREAMP_DB_PER_DP = 0.08

/** ...and the share of it for a slow finger (0.02 dB a dp: four times finer, like the sliders, owner 26.09). */
private const val PREAMP_SLOW = 0.25f

/**
 * PREAMP: the value, and one bar that is both the MANUAL / AUTO switch and the manual slider ([PreampBar]).
 * A tap on the value types it (manual only).
 */
@Composable
private fun PreampRow(model: AppModel, p: Profile, sender: Sender, actions: TuneActions) {
    val c = pal
    val auto = p.preampDb == null
    val autoDb = runCatching { shownPreamp(p.copy(preampDb = null)) }.getOrNull()
    val db = if (auto) autoDb else p.preampDb
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_H)
            .lift(ROW_SHAPE)
            .background(c.surface, ROW_SHAPE)
            .padding(start = Grid.TEXT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(VALUE_W - Grid.TEXT)
                .fillMaxHeight()
                .clip(RoundedCornerShape(Radii.S))
                .clickable(enabled = !auto && db != null) { actions.value(Param.PREAMP) }
                .testTag("value_preamp"),
            contentAlignment = Alignment.CenterStart,
        ) {
            // AUTO: a computed value, dimmed; manual: bright
            StackedValue(
                Param.PREAMP.label,
                db?.let { Param.PREAMP.number(it) } ?: "--",
                if (db != null) Param.PREAMP.unit else "",
                if (auto) c.textDim else c.text,
            )
        }
        PreampBar(model, p, db, auto, autoDb != null, sender, Modifier.weight(1f).fillMaxHeight().padding(Grid.INSET))
    }
}

/** MANUAL and AUTO side by side, both in [manual] and [auto] colour: drawn once under the pill and once, clipped to it, over it. */
@Composable
private fun PreampLabels(manual: Color, auto: Color, onManual: (() -> Unit)?, onAuto: (() -> Unit)?) {
    Row(Modifier.fillMaxSize()) {
        Box(
            Modifier.weight(1f).fillMaxHeight().then(if (onManual != null) Modifier.clickable(onClick = onManual) else Modifier),
            contentAlignment = Alignment.Center,
        ) { Text("MANUAL", style = Type.segment, color = manual, maxLines = 1) }
        Box(
            Modifier.weight(1f).fillMaxHeight().then(if (onAuto != null) Modifier.clickable(onClick = onAuto) else Modifier),
            contentAlignment = Alignment.Center,
        ) { Text("AUTO", style = Type.segment, color = auto, maxLines = 1) }
    }
}

/**
 * A pressed-in bar with a raised orange pill, the look of the PEAK / LOW SHELF row. AUTO: the pill sits under AUTO
 * and a tap on MANUAL switches. MANUAL: the pill stretches from the left edge to the value, like the fill of the
 * sliders above it, and a sideways drag adjusts (0.1 dB steps, a tick at every whole dB, a strong one at 0 dB, a
 * reject tick at the device limits; lifting the finger off a value keeps it, [LiftGuard]); a tap on AUTO switches back.
 */
@Composable
private fun PreampBar(model: AppModel, p: Profile, db: Double?, auto: Boolean, valid: Boolean, sender: Sender, modifier: Modifier) {
    val c = pal
    val haptics = LocalHaptics.current
    val pagerLock = LocalPagerLock.current
    val prof = rememberUpdatedState(p)
    val shape = RoundedCornerShape(Radii.M)
    val pill = shape // same corners as the slider fill: the pill is the whole bar height
    val adjustable = !auto && db != null && !sender.bypassed && !sender.abBusy
    val hs = ProtocolMicro.highShelfGainSum(p.bands)
    val lo = ProtocolMicro.PREAMP_MIN_DB - hs
    val hi = ProtocolMicro.PREAMP_MAX_DB - hs
    val pos = if (db == null || hi <= lo) 0f else ((db - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
    val manual by animateFloatAsState(if (auto) 0f else 1f, label = "preamp-mode")
    val switchTo = { toAuto: Boolean -> haptics.segment(); sender.leaveAb { model.setPreampAuto(toAuto) } }
    BoxWithConstraints(
        modifier
            .testTag("preamp_bar")
            .clip(shape)
            .background(c.track)
            .sink(shape)
            .then(if (!adjustable) Modifier else Modifier.holdsPager(pagerLock).pointerInput(p.id) {
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
    ) {
        if (!valid) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("INVALID EQ", style = Type.segment, color = c.textDim, maxLines = 1)
            }
            return@BoxWithConstraints
        }
        // the pill fills the bar's full height, as tall as the fill of the FREQ / GAIN / Q sliders above
        val w = maxWidth
        val half = w / 2
        val x = half * (1f - manual)
        val pillW = half + half * (pos * manual)
        Box(Modifier.fillMaxSize()) {
            PreampLabels(c.text, c.text, if (auto) ({ switchTo(false) }) else null, if (!auto) ({ switchTo(true) }) else null)
            Box(
                Modifier
                    .offset(x = x)
                    .width(pillW)
                    .fillMaxHeight()
                    .lift(pill, Lift.RAISED)
                    .background(c.accent, pill)
                    .clip(pill),
            ) {
                Box(Modifier.fillMaxHeight().wrapContentWidth(Alignment.Start, unbounded = true).width(w).offset(x = -x)) {
                    PreampLabels(c.onAccent, c.onAccent, null, null)
                }
            }
        }
    }
}

/** 40 dp visual pill inside a 48 dp target; fixed right edge, B grows only to the left. */
@Composable
private fun AbButton(sender: Sender, p: Profile, modifier: Modifier) {
    val c = pal
    val haptics = LocalHaptics.current
    val shape = RoundedCornerShape(Radii.M)
    val enabled = !sender.busy
    val bypassed = sender.bypassed
    Box(
        modifier.height(48.dp).widthIn(min = 72.dp)
            .clip(shape)
            .clickable(enabled = enabled) { haptics.step(); sender.toggleAb(p) }
            .padding(4.dp)
            .testTag("ab_toggle"),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.height(40.dp).widthIn(min = 64.dp)
                .lift(shape, Lift.RAISED)
                .background(if (bypassed) c.surface2 else c.accent, shape)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (bypassed) {
                Text("EQ OFF", style = Type.small, color = c.textDim, maxLines = 1, softWrap = false)
                Spacer(Modifier.width(10.dp))
                Text("PREAMP KEPT", style = Type.segment, color = c.text, maxLines = 1, softWrap = false)
            } else {
                Text("A/B", style = Type.segment, color = c.onAccent, maxLines = 1, softWrap = false)
            }
        }
    }
}
