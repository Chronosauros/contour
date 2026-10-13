package io.github.chronosauros.contour.ui.tune

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.ceil
import kotlin.math.floor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
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
import io.github.chronosauros.contour.core.BandStripPlan
import io.github.chronosauros.contour.core.FilterType
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.model.AppModel
import io.github.chronosauros.contour.model.Sender
import io.github.chronosauros.contour.ui.AppButton
import io.github.chronosauros.contour.ui.Grid
import io.github.chronosauros.contour.ui.Lift
import io.github.chronosauros.contour.ui.Type
import io.github.chronosauros.contour.ui.kit.LiftGuard
import io.github.chronosauros.contour.ui.kit.LocalHaptics
import io.github.chronosauros.contour.ui.kit.LocalPagerLock
import io.github.chronosauros.contour.ui.kit.detectHorizontalDragWithEnds
import io.github.chronosauros.contour.ui.kit.holdsPager
import io.github.chronosauros.contour.ui.kit.ProfileIcons
import io.github.chronosauros.contour.ui.lift
import io.github.chronosauros.contour.ui.Radii
import io.github.chronosauros.contour.ui.pal
import io.github.chronosauros.contour.ui.sink
import io.github.chronosauros.contour.usb.DeviceController
import io.github.chronosauros.contour.usb.Link
import io.github.chronosauros.contour.core.native.FiioCodec
import io.github.chronosauros.contour.core.native.NativeState

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
                    Box {
                        ResponseGraph(model, p, Modifier.fillMaxSize(), bypassed = sender.bypassed, enabled = bandsEnabled)
                        if (sender.canAb(p)) AbButton(sender, p, Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp))
                        GraphNotice(model, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp, start = 12.dp, end = 12.dp))
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
                    ParamRow(param, param.of(b), enabled = bandsEnabled, scale = param.scaleFor(model.protocol.caps)!!, onTap = { actions.value(param) }) { v ->
                        if (!sender.bypassed && !sender.abBusy)
                            model.transformBandIfCurrent(p.id, i, b.id) { current -> param.set(current, v) }
                    }
                }
            }
        }
        PreampRow(model, p, sender, actions)
        // AUTO that needs more than the device takes: it stays AUTO (MANUAL does not swap in -30) and says why.
        if (p.preampDb == null) model.protocol.walkplay?.autoRefusal(p.bands)?.let {
            Text("AUTO PREAMP: $it", color = pal.textDim, style = Type.small)
        }
        p.preampDb?.let { db ->
            if (!model.protocol.preampFits(p.bands, db)) {
                Text("PREAMP ${Param.PREAMP.number(db)} dB is outside the device range. Tap the number to type a new value, or switch to AUTO.", color = pal.textDim, style = Type.small)
            }
        }
        // FiiO KA15 (read, USER1-3 writes, slot names and rename tested on a Pixel 04.10.2026): why a send is blocked,
        // a note or failure from the DAC, and the USER slot row right above HOLD TO SEND (one group, so the page fits).
        val slots = device.slotPicker && device.link == Link.CONNECTED
        // Micro/Max too: a band that does not fit the DAC's filter format, or an AUTO preamp below its floor, says why.
        if (device.link == Link.CONNECTED) {
            // KA15, K13 R2R, Melody and Tiny A: read, write and persistence confirmed on hardware (FiioConfig.hardwareVerified)
            if (device.protocol.native && !device.hardwareVerified) Text("Native beta recipe: not hardware tested. Readback verifies registers, not audio/persistence.", color = pal.textDim, style = Type.small)
            (device.snapshot?.nativeState as? io.github.chronosauros.contour.core.native.NativeState.Fiio)?.slotDescription?.let {
                Text("DAC: $it", color = pal.textDim, style = Type.small)
            }
            device.sendIssues(p).firstOrNull()?.let { Text("SEND BLOCKED: $it (saved values unchanged)", color = pal.textDim, style = Type.small) }
            if (device.protocol.native) device.error?.let { Text(it, color = pal.textDim, style = Type.small) }
        }
        if (slots) SlotRow(device, Modifier.padding(top = Grid.GROUP - GAP))
        // One column grid on every DAC: READ is a third (under USER1 of a three-slot well), HOLD TO SEND the other two.
        BoxWithConstraints(Modifier.padding(top = if (slots) 0.dp else Grid.GROUP - GAP).fillMaxWidth().height(ROW_H)) {
            val third = (maxWidth - Grid.INSET * 2) / 3
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                if (device.link == Link.CONNECTED) ReadButton(model, device, Modifier.width(third).fillMaxHeight())
                HoldToSend(p, device, sender, actions::sendDetails, Modifier.weight(1f).fillMaxHeight())
            }
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
            style = Type.segment,
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
            .size(48.dp)
            .clip(RoundedCornerShape(Radii.M))
            .clickable { if (enabled) { haptics.tap(); onClick() } else haptics.reject() }
            .testTag("history_$name"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, name, tint = if (enabled) c.text else c.textMute, modifier = Modifier.size(26.dp))
    }
}

/** USER slots, a well like the filter type row: the slot HOLD TO SEND writes sits on the orange pill; by default the
 * slot playing now, so a plain HOLD never switches presets. KA15 shows the names read from the DAC (the slot number is
 * in the rename dialog); more than five unnamed slots (K13 R2R: USER1-10) show numbers in one row, the chosen one with
 * a small USER under its number. Long press = rename the slot (only where the DAC stores names). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SlotRow(device: DeviceController, modifier: Modifier = Modifier) {
    val c = pal
    val haptics = LocalHaptics.current
    val fiio = device.protocol.fiio ?: return
    val names = (device.snapshot?.nativeState as? io.github.chronosauros.contour.core.native.NativeState.Fiio)?.names.orEmpty()
    val target = device.destinationSlot()
    var renaming by remember { mutableStateOf<Int?>(null) }
    renaming?.let { slot -> RenameSlotDialog(fiio.slotLabels[slot] ?: "SLOT $slot", names[slot].orEmpty(), { renaming = null }) { device.renameSlot(slot, it) } }
    val named = device.slotNames
    val slots = fiio.userSlots.sorted()
    val numbers = !named && slots.size > 5
    val well = RoundedCornerShape(Radii.L)
    val pill = RoundedCornerShape(Radii.M)
    val idx = slots.indexOf(target)
    val at by animateFloatAsState(idx.coerceAtLeast(0).toFloat(), label = "slot")
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(ROW_H)
            .clip(well)
            .background(c.track)
            .sink(well)
            .padding(Grid.INSET),
    ) {
        val w = maxWidth / slots.size
        if (idx >= 0) Box(
            Modifier
                .offset(x = w * at)
                .width(w)
                .fillMaxHeight()
                .lift(pill, Lift.RAISED)
                .background(c.accent, pill),
        )
        Row(Modifier.fillMaxSize()) {
            slots.forEach { slot ->
                val sel = slot == target
                val label = fiio.slotLabels[slot] ?: "SLOT $slot"
                val text = when {
                    named -> names[slot]?.takeIf { it.isNotBlank() } ?: label
                    numbers -> label.removePrefix("USER")
                    else -> label
                }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(pill)
                        .combinedClickable(
                            enabled = !device.busy,
                            onClick = { if (!sel) haptics.segment(); device.selectSlot(slot) },
                            onLongClick = if (named) ({ haptics.longPress(); renaming = slot }) else null,
                        )
                        .testTag("slot_$slot"),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text, style = Type.segment, color = if (sel) c.onAccent else c.text, maxLines = 1)
                    if (numbers && sel) Text("USER", style = Type.small, color = c.onAccent, maxLines = 1)
                }
            }
        }
    }
}

/** New USER slot name: A-Z and 0-9, at most 7 (all the KA15 takes). Only the name changes, not the EQ. */
@Composable
private fun RenameSlotDialog(label: String, current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(Radii.L),
        containerColor = pal.surface2,
        titleContentColor = pal.text,
        textContentColor = pal.textDim,
        title = { Text("RENAME $label", style = Type.rowName) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = FiioCodec.slotName(it) },
                label = { Text("NAME, UP TO 7: A-Z 0-9") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                shape = RoundedCornerShape(Radii.M),
                modifier = Modifier.fillMaxWidth().testTag("slot_name"),
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = name.isNotEmpty() && name != current,
                onClick = { onDismiss(); onRename(name) },
                modifier = Modifier.testTag("slot_rename_confirm"),
            ) { Text("RENAME", style = Type.segment, color = if (name.isNotEmpty() && name != current) pal.accent else pal.textMute) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("CANCEL", style = Type.segment, color = pal.textDim) }
        },
    )
}

/** [AppModel.graphNotice] on the graph: 1.5 s fully visible, then a light fade; touches pass through to the graph. */
@Composable
private fun GraphNotice(model: AppModel, modifier: Modifier) {
    val notice = model.graphNotice ?: return
    val alpha = remember(notice) { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(notice) {
        kotlinx.coroutines.delay(1500)
        alpha.animateTo(0f, tween(450))
        if (model.graphNotice === notice) model.graphNotice = null
    }
    Text(
        notice.first,
        style = Type.label,
        color = pal.text,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .alpha(alpha.value)
            .clip(RoundedCornerShape(Radii.L))
            .background(pal.surface2.copy(alpha = 0.85f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("graph_notice"),
    )
}

/** READ, beside HOLD TO SEND whenever a DAC is connected: reads the chosen USER slot (the DAC switches to it) or,
 * without slots, what plays now, and opens it as a new profile (FROM DAC). Nothing on the DAC is written. */
@Composable
private fun ReadButton(model: AppModel, device: DeviceController, modifier: Modifier) {
    val enabled = !device.busy && !device.abBypassed
    AppButton(
        if (device.reading) "READING" else "READ",
        onClick = {
            device.readToImport { s, slot ->
                when (val r = s.importExact()) {
                    is io.github.chronosauros.contour.core.DacImport.Ready -> {
                        val fiio = device.protocol.fiio
                        val names = (s.nativeState as? io.github.chronosauros.contour.core.native.NativeState.Fiio)?.names.orEmpty()
                        val name = slot?.let { names[it]?.takeIf(String::isNotBlank) ?: fiio?.slotLabels?.get(it) }
                        model.create(r.eq.bands, r.eq.preampDb, name = name, sub = "FROM DAC")
                        val n = r.eq.bands.size
                        model.showOnGraph("READ ${name ?: "DAC"}: $n FILTER${if (n == 1) "" else "S"}" + (r.warning?.let { " - $it" } ?: ""))
                    }
                    is io.github.chronosauros.contour.core.DacImport.Rejected -> model.notice = "READ: ${r.issues.joinToString("; ")}"
                }
            }
        },
        modifier = modifier.testTag("read_dac"),
        enabled = enabled,
    )
}

/**
 * The bands 1..N and then "+" and "-" in one well like PEAK / USER: the chosen band on the orange pill, 20 sp numbers,
 * 36 dp cells that scroll when they do not fit, "+" and "-" pinned at the well's right end so they stay in reach
 * whatever the band count. Tap = select; tap on the selected band, or a long press on any, = BYPASS / ENABLE / DELETE
 * sheet; "-" = delete the selected band (UNDO brings it back). A bypassed band is an outline (accent when chosen) -
 * like its ring on the graph.
 */
@Composable
private fun BandStrip(model: AppModel, p: Profile, actions: TuneActions, enabled: Boolean) {
    val c = pal
    val haptics = LocalHaptics.current
    val well = RoundedCornerShape(Radii.L)
    val showPlus = p.bands.size < model.maxBands
    // "-" is muted while it is the only band and while sending or A/B is busy (when "+" is dead too)
    val canDelete = enabled && p.bands.size > 1
    BoxWithConstraints(Modifier.fillMaxWidth().height(ROW_H)) {
        val plan = BandStripPlan.plan(maxWidth.value.toDouble(), p.bands.size, showPlus)
        val numbers = plan.numbersDp.dp
        Row(Modifier.fillMaxSize().clip(well).background(c.track).sink(well)) {
            BandNumbers(model, p, actions, enabled, plan.cellDp.dp, plan.scroll, numbers, Modifier.width(numbers))
            if (showPlus) PmCell(Icons.Rounded.Add, "add band", enabled, "chip_add") { haptics.tap(); model.addBand() }
            PmCell(Icons.Rounded.Remove, "delete selected band", canDelete, "chip_remove") { haptics.tap(); model.deleteBand(model.selectedBand) }
        }
    }
}

/**
 * The scrolling numbers of [BandStrip] inside their well. The well's inset is padding inside the scrolled content, so a
 * band scrolled into view keeps the inset around it and its pill (with its shadow) never looks cut at the edge.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BandNumbers(model: AppModel, p: Profile, actions: TuneActions, enabled: Boolean, cellW: Dp, scrolls: Boolean, view: Dp, modifier: Modifier) {
    val c = pal
    val haptics = LocalHaptics.current
    val density = LocalDensity.current
    val pill = RoundedCornerShape(Radii.M)
    val scroll = rememberScrollState()
    // the selected band stays in view with the inset on both sides: a band just added at the end, or one picked on the graph
    LaunchedEffect(model.selectedBand, p.bands.size, cellW, scrolls, view) {
        if (!scrolls) return@LaunchedEffect
        val start = with(density) { (cellW * model.selectedBand).toPx() }
        val end = with(density) { (Grid.INSET * 2 + cellW * (model.selectedBand + 1)).toPx() }
        val width = with(density) { view.toPx() }
        if (start < scroll.value) scroll.animateScrollTo(start.toInt())
        else if (end > scroll.value + width) scroll.animateScrollTo(ceil(end - width).toInt())
    }
    val at by animateFloatAsState(model.selectedBand.toFloat(), label = "band")
    val selEnabled = p.bands.getOrNull(model.selectedBand)?.enabled == true
    Box(
        modifier
            .fillMaxHeight()
            .then(if (scrolls) Modifier.horizontalScroll(scroll) else Modifier)
            .padding(Grid.INSET),
    ) {
        // the pill rides in the scrolled content, so it stays under its band
        if (selEnabled) Box(
            Modifier
                .offset(x = cellW * at)
                .width(cellW)
                .fillMaxHeight()
                .lift(pill, Lift.RAISED)
                .background(c.accent, pill),
        )
        Row(Modifier.fillMaxHeight()) {
            p.bands.forEachIndexed { i, b ->
                val sel = i == model.selectedBand
                Box(
                    Modifier
                        .width(cellW)
                        .fillMaxHeight()
                        .clip(pill)
                        .let { if (b.enabled) it else it.border(2.dp, if (sel) c.accent else c.textMute, pill) }
                        .combinedClickable(
                            enabled = enabled,
                            // a first tap selects; a tap on the band that is already selected opens its sheet
                            onClick = { haptics.tap(); if (sel) actions.band(i) else model.selectBand(i) },
                            onLongClick = { haptics.longPress(); model.selectBand(i); actions.band(i) },
                        )
                        .testTag("chip_${i + 1}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${i + 1}",
                        style = Type.chip,
                        color = if (b.enabled) (if (sel) c.onAccent else c.text) else (if (sel) c.accent else c.textMute),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** "+" or "-": a cell at the right end of the band well; [live] = the action would do something (muted icon when not). */
@Composable
private fun PmCell(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, live: Boolean, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .width(BandStripPlan.BUTTON.dp)
            .fillMaxHeight()
            .padding(vertical = Grid.INSET)
            .clip(RoundedCornerShape(Radii.M))
            .clickable(enabled = live, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, name, tint = if (live) pal.text else pal.textMute, modifier = Modifier.size(26.dp))
    }
}

/** Filter type: a pressed-in well with a raised orange pill that slides to the chosen type. */
@Composable
private fun TypeRow(model: AppModel, type: FilterType, enabled: Boolean) {
    val c = pal
    val haptics = LocalHaptics.current
    val types = listOf(FilterType.PEAK to "PEAK", FilterType.LOW_SHELF to "LOW SHELF", FilterType.HIGH_SHELF to "HIGH SHELF")
        .filter { it.first in model.protocol.caps.types }
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
                    Text(label, style = Type.segment, color = if (sel && t in model.protocol.caps.types) c.onAccent else c.text, maxLines = 1)
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
private fun ParamRow(param: Param, value: Double, enabled: Boolean, scale: io.github.chronosauros.contour.ui.kit.Scale, onTap: () -> Unit, onChange: (Double) -> Unit) {
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
        RelSlider(param, value, onChange, Modifier.weight(1f).fillMaxHeight().padding(Grid.INSET), enabled = enabled, scale = scale)
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
    val autoDb = runCatching { model.shownPreamp(p.copy(preampDb = null)) }.getOrNull()
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
 * and a tap on MANUAL switches. MANUAL: the bar becomes a slider like FREQ / GAIN / Q (a light fill from the left edge
 * to the value, MANUAL bright, AUTO dimmed), and a sideways drag adjusts (0.1 dB steps, a tick at every whole dB, a
 * strong one at 0 dB, a reject tick at the device limits; lifting the finger off a value keeps it, [LiftGuard]);
 * a tap on AUTO switches back.
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
    val hs = model.protocol.shelfOffset(p.bands)
    val lo = model.protocol.preampMin - hs
    val hi = model.protocol.preampMax - hs
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
                    // A stored value the device would not take (a preserved import, or a HIGH SHELF edit that pushed it out)
                    // is never touched by a drag: the slider would snap it into range and unblock the send.
                    var blocked = false
                    detectHorizontalDragWithEnds(
                        onStart = { down ->
                            prof.value.preampDb?.let { start ->
                                blocked = !model.protocol.preampFits(prof.value.bands, start)
                                if (blocked) { haptics.reject(); return@let }
                                acc = start
                                atEnd = false
                                speed = 0f
                                ticked = acc
                                guard.start(down.uptimeMillis, down.position, acc)
                            }
                        },
                        onEnd = { up -> if (up != null && !blocked) guard.release(up)?.let { model.setPreamp(it) } },
                    ) { ch, dx ->
                        ch.consume()
                        if (blocked) return@detectHorizontalDragWithEnds
                        val now = prof.value.preampDb ?: return@detectHorizontalDragWithEnds
                        val hs = model.protocol.shelfOffset(prof.value.bands)
                        val lo = model.protocol.preampMin - hs
                        val hi = model.protocol.preampMax - hs
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
                        val next = io.github.chronosauros.contour.core.Preamp.floorTo(acc) // down: never louder than the finger
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
        if (!auto) {
            // MANUAL is a slider like FREQ / GAIN / Q (light fill from the left edge to the value); MANUAL reads at
            // full strength, AUTO dimmed - a tap on AUTO switches back
            val h = maxHeight
            val fillW = h + (w - h) * pos
            Box(Modifier.fillMaxSize()) {
                PreampLabels(c.text, c.textMute, null, { switchTo(true) })
                Box(
                    Modifier
                        .width(fillW)
                        .fillMaxHeight()
                        .lift(pill, Lift.RAISED)
                        .background(c.fill, pill)
                        .clip(pill),
                ) {
                    Box(Modifier.fillMaxHeight().wrapContentWidth(Alignment.Start, unbounded = true).width(w)) {
                        PreampLabels(c.bg, c.bg.copy(alpha = 0.45f), null, null)
                    }
                }
            }
            return@BoxWithConstraints
        }
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
