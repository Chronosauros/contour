package io.github.chronosauros.contour.ui.dac

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.chronosauros.contour.core.native.DacSetting
import io.github.chronosauros.contour.core.native.SettingKind
import io.github.chronosauros.contour.core.native.SettingWriteOutcome
import io.github.chronosauros.contour.ui.Grid
import io.github.chronosauros.contour.ui.Lift
import io.github.chronosauros.contour.ui.Radii
import io.github.chronosauros.contour.ui.Type
import io.github.chronosauros.contour.ui.kit.LiftGuard
import io.github.chronosauros.contour.ui.kit.LocalHaptics
import io.github.chronosauros.contour.ui.kit.LocalPagerLock
import io.github.chronosauros.contour.ui.kit.detectHorizontalDragWithEnds
import io.github.chronosauros.contour.ui.kit.holdsPager
import io.github.chronosauros.contour.ui.lift
import io.github.chronosauros.contour.ui.pal
import io.github.chronosauros.contour.ui.sheets.ChoiceSheet
import io.github.chronosauros.contour.ui.sink
import io.github.chronosauros.contour.ui.tune.StackedValue
import io.github.chronosauros.contour.ui.tune.VALUE_W
import io.github.chronosauros.contour.usb.DeviceController
import io.github.chronosauros.contour.usb.Link
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the page can say about one setting. */
private enum class Shown { VALUE, NO_REPLY, PENDING, NONE }

private val CARD = RoundedCornerShape(Radii.L)

/** Height of the footer line's slot above the page bar. */
private val FOOTER_H = 22.dp

/**
 * DAC Settings: the settings of the connected DAC, left of EQ. The rows come from the device's own list
 * ([DeviceController.dacSettings]), so the page shows exactly what this DAC can do. Round 1 only READS: a tap or a drag on a real
 * DAC sends nothing, says so ([onNotSent]) and leaves the control on the value read. In the review preview
 * ([DeviceController.dacIsPreview]) the controls move locally and nothing touches USB.
 */
@Composable
fun DacScreen(device: DeviceController, top: Dp, bottom: Dp, onNotSent: (String) -> Unit) {
    val target = device.dacTarget ?: return // the DAC went away while the page was leaving
    val settings = target.settings
    val name = target.caps.name
    val preview = device.dacIsPreview
    val c = pal
    // Preview only: what the user moved, starting from the values this DAC showed on hardware (plus the header ids "rate" and "fw").
    val local = remember(preview, target) {
        mutableStateMapOf<String, Int>().also { m ->
            if (preview) {
                settings.filter { it.read != null }.forEach { m[it.id] = when (it.kind) {
                    SettingKind.TOGGLE -> 1
                    SettingKind.CHOICE -> 0
                    SettingKind.LEVEL -> it.min + (it.max - it.min) * 2 / 5
                    SettingKind.BALANCE -> 0
                } }
                m.putAll(target.settingsPreview)
            }
        }
    }
    // The list a long choice (FILTER, SCREEN OFF) has open; null = none.
    var choosing by remember(target) { mutableStateOf<String?>(null) }
    // A real DAC and a setting that is writeVerified: the value the user just set stays on the control until the write is answered
    // (the DAC's read-back then replaces it, or the control goes back to the value read). One write at a time; a drag sends only on release.
    val pending = remember(target) { mutableStateMapOf<String, Int>() }
    var writing by remember(target) { mutableStateOf(false) }
    fun valueOf(id: String): Int? = if (preview) local[id] else pending[id] ?: device.settingValues[id]?.value
    fun send(s: DacSetting, v: Int) {
        if (writing) { pending.remove(s.id); return }
        writing = true
        pending[s.id] = v
        device.writeSetting(s.id, v) { outcome ->
            pending.remove(s.id)
            writing = false
            when (outcome) {
                SettingWriteOutcome.Verified -> Unit
                is SettingWriteOutcome.Mismatch -> onNotSent("Not set - ${s.label} reads ${format(s, outcome.read)}")
                SettingWriteOutcome.NoReply -> onNotSent("Not set - no reply from $name")
                null -> onNotSent("Not set - $name is busy")
            }
        }
    }
    // The scroll area starts under the status bar (rows never draw behind the clock) and ends above the footer strip; a new DAC starts at the top.
    val scroll = remember(target) { ScrollState(0) }
    val bottomPad = bottom + FOOTER_H + Grid.GAP
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = top)
                .verticalScroll(scroll)
                .padding(bottom = bottomPad)
                .padding(horizontal = Grid.SIDE),
            verticalArrangement = Arrangement.spacedBy(Grid.GAP),
        ) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.padding(start = 6.dp).testTag("dac_title")) {
                    BasicText(
                        name.uppercase(),
                        style = Type.profileTitle.copy(color = c.text),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        autoSize = TextAutoSize.StepBased(minFontSize = 17.sp, maxFontSize = Type.profileTitle.fontSize, stepSize = 1.sp),
                    )
                    // DAC SETTINGS - 48 kHz - FW 1.2.0: a part the DAC did not answer is left out
                    val header = (listOf("DAC SETTINGS") + target.info.mapNotNull { i -> valueOf(i.id)?.let(i.format) }).joinToString(" · ")
                    Text(header, style = Type.small, color = c.textDim, maxLines = 1, modifier = Modifier.testTag("dac_header"))
                }
            }
            settings.forEachIndexed { i, s ->
                val shown: Shown
                val value: Int?
                if (preview) {
                    value = local[s.id]
                    shown = if (value != null) Shown.VALUE else Shown.NONE
                } else if (s.read == null) {
                    value = null; shown = Shown.NONE
                } else if (device.link != Link.CONNECTED) {
                    value = null; shown = Shown.PENDING
                } else {
                    val r = pending[s.id] ?: device.settingValues[s.id]?.value
                    value = r
                    shown = if (r != null) Shown.VALUE else if (device.settingsRead) Shown.NO_REPLY else Shown.PENDING
                }
                val dim = shown == Shown.NONE || (shown == Shown.PENDING && device.link != Link.CONNECTED)
                // the slider top may follow another setting (the Tiny A volume follows VOLUME STEPS); no reply there = the setting's own max
                val levelMax = s.maxFor { id -> valueOf(id) }
                // preview: the controls move locally; real DAC: only a setting that passed the write self-test (writeVerified) or, in a review build, any row with a write frame is sent
                val writable = !preview && s.writeEnabled && s.read != null && device.link == Link.CONNECTED
                val groupGap = if (i > 0 && s.group != settings[i - 1].group) Grid.GROUP - Grid.GAP else 0.dp
                // A section's small caps header sits above its first row, after the gap that separates the groups.
                val section = s.section
                if (section != null) Text(
                    section, style = Type.small, color = c.textDim, maxLines = 1,
                    modifier = Modifier.padding(start = 6.dp, top = groupGap).testTag("dac_section_${section.lowercase()}"),
                )
                SettingRow(
                    s, value, shown, dim, preview || writable, levelMax,
                    onMove = { v -> if (preview) local[s.id] = v else pending[s.id] = v },
                    onCommit = { v -> if (!preview) { if (v == device.settingValues[s.id]?.value) pending.remove(s.id) else send(s, v) } },
                    onPick = { v -> if (preview) local[s.id] = v else send(s, v) },
                    onBlocked = { onNotSent("Not sent - ${s.label} is not verified on $name yet") },
                    onOpen = { choosing = s.id },
                    modifier = Modifier.padding(top = if (section != null) 0.dp else groupGap),
                )
            }
            // SnowSky Melody and Tiny A, FiiO KA15 and the WalkPlay Micro, Echo-A and TRN: the factory reset. Dimmed (a tap only says why) until the reset is proven (FiioReset.VERIFIED,
            // WalkPlayReset.VERIFIED) or the build is a review build, which opens it and still says NOT VERIFIED; the sheet (ContourApp) runs the flow.
            if (device.resetOffered) ResetRow(
                enabled = device.resetEnabled && device.link == Link.CONNECTED,
                verified = device.resetEnabled,
                proven = device.resetProven,
                onTap = {
                    if (!device.resetEnabled) onNotSent("Not sent - FACTORY RESET is not verified on $name yet")
                    else device.askFactoryReset()
                },
                modifier = Modifier.padding(top = Grid.GROUP - Grid.GAP),
            )
        }
        choosing?.let { id -> settings.firstOrNull { it.id == id } }?.let { s ->
            val current = valueOf(s.id)
            ChoiceSheet(
                s.label, s.options, current,
                onPick = { i ->
                    if (preview) local[s.id] = i
                    else if (s.writeEnabled && device.link == Link.CONNECTED) { if (i != current) send(s, i) }
                    else if (i != current) onNotSent("Not sent - ${s.label} is not verified on $name yet")
                },
                onDone = { choosing = null },
            )
        }
        // The one small line at the bottom of the page.
        val line = when {
            device.writeTestLine != null -> device.writeTestLine
            preview -> "PREVIEW - NOTHING IS SENT"
            device.tapToConnect -> "TAP TO CONNECT"
            device.link == Link.CONNECTED && device.settingsReading -> "READING THE DAC"
            // a review build writes rows no unit has proven yet: the page keeps saying so
            device.link == Link.CONNECTED && settings.any { it.writeEnabled && !it.writeVerified } -> "WRITES NOT VERIFIED ON HARDWARE"
            else -> null
        }
        // A strip in the page colour from the screen bottom up past the line: rows that scroll under it end clean, the line never
        // sits on a row, and nothing shows through the page bar's rounded corners.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bottom + FOOTER_H).background(c.bg))
        if (line != null) Text(
            line,
            style = Type.small,
            color = c.textDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = Grid.SIDE)
                .padding(bottom = bottom + 2.dp)
                .then(if (device.tapToConnect && !preview) Modifier.clickable { device.connect() } else Modifier)
                .testTag("dac_footer"),
        )
    }
}

/** The text of a value: OFF / ON, the option, the raw number, CENTER / L n / R n. */
private fun format(s: DacSetting, v: Int): String = when (s.kind) {
    SettingKind.TOGGLE -> s.options.getOrElse(v) { "--" }
    SettingKind.CHOICE -> s.shortOption(v)
    SettingKind.LEVEL -> s.valueText?.invoke(v) ?: if (s.scale > 1) "%.1f".format(java.util.Locale.ROOT, v.toDouble() / s.scale) else v.toString() // dB rows hold tenths (scale 10); a row with valueText words its own value
    SettingKind.BALANCE -> if (v == 0) "CENTER" else if (v < 0) "L ${-v}" else "R $v" // the side the sound moves to, like FiiO Control
}

/** A row in the language of TuneScreen's ParamRow: the label over the value on the left, the control in a well on the right. */
@Composable
private fun SettingRow(
    s: DacSetting, value: Int?, shown: Shown, dim: Boolean, live: Boolean, max: Int,
    onMove: (Int) -> Unit, onCommit: (Int) -> Unit, onPick: (Int) -> Unit, onBlocked: () -> Unit, onOpen: () -> Unit, modifier: Modifier,
) {
    val c = pal
    Row(
        modifier
            .fillMaxWidth()
            .height(Grid.ROW)
            .lift(CARD)
            .background(c.surface, CARD)
            .padding(start = Grid.TEXT)
            .testTag("dac_row_${s.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(VALUE_W - Grid.TEXT).fillMaxHeight().testTag("dac_value_${s.id}"), contentAlignment = Alignment.CenterStart) {
            when (shown) {
                Shown.VALUE -> StackedValue(s.label, format(s, requireNotNull(value)), if (s.kind == SettingKind.LEVEL) s.unit else "", c.text, fit = s.kind == SettingKind.CHOICE)
                else -> androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.Center) {
                    Text(s.label, style = Type.paramLabel, color = c.textDim, maxLines = 1)
                    Text(
                        when (shown) { Shown.NO_REPLY -> "NO REPLY"; Shown.PENDING -> "..."; else -> "--" },
                        style = if (shown == Shown.NO_REPLY) Type.segment else Type.value, color = c.textDim, maxLines = 1, softWrap = false,
                    )
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight().padding(Grid.INSET).alpha(if (dim) 0.4f else 1f)) {
            if (s.readOnly) ReadOnlyWell(s)
            else when (s.kind) {
                SettingKind.TOGGLE, SettingKind.CHOICE ->
                    if (s.longChoice) ChoiceButton(s, value, onOpen) else OptionWell(s, value, live, onPick, onBlocked)
                SettingKind.LEVEL -> DacSlider(s, value, s.min, max, false, live, onMove, onCommit, onBlocked)
                SettingKind.BALANCE -> DacSlider(s, value, -s.max, s.max, true, live, onMove, onCommit, onBlocked)
            }
        }
    }
}

/** FACTORY RESET: a row in the page's card language; the label over what the tap does, the whole row is the button. */
@Composable
private fun ResetRow(enabled: Boolean, verified: Boolean, proven: Boolean, onTap: () -> Unit, modifier: Modifier) {
    val c = pal
    val haptics = LocalHaptics.current
    Row(
        modifier
            .fillMaxWidth()
            .height(Grid.ROW)
            .lift(CARD)
            .background(c.surface, CARD)
            .clip(CARD)
            .clickable {
                if (!verified) { haptics.reject(); onTap() } // dimmed: the tap only says why
                else if (enabled) { haptics.tap(); onTap() }
            }
            .padding(horizontal = Grid.TEXT)
            .alpha(if (verified) 1f else 0.4f)
            .testTag("dac_row_factory_reset"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text("FACTORY RESET", style = Type.paramLabel, color = c.textDim, maxLines = 1)
            Text(if (!verified) "NOT VERIFIED YET" else if (!proven) "NOT VERIFIED · SAVES A COPY" else "SAVES A COPY, THEN RESETS", style = Type.segment, color = c.text, maxLines = 1, softWrap = false)
        }
    }
}

/** A choice with more options than fit side by side: a pressed-in well with the current option in full; a tap opens the list. */
@Composable
private fun ChoiceButton(s: DacSetting, value: Int?, onOpen: () -> Unit) {
    val c = pal
    val haptics = LocalHaptics.current
    val well = RoundedCornerShape(Radii.M)
    Row(
        Modifier
            .fillMaxSize()
            .clip(well)
            .background(c.track)
            .sink(well)
            .clickable { haptics.tap(); onOpen() }
            .padding(horizontal = Grid.TEXT)
            .testTag("dac_choice_${s.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            value?.let { s.options.getOrElse(it) { "--" } } ?: "--",
            style = Type.segment, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = c.textDim)
    }
}

/** A row no build can write (USB AUDIO: [DacSetting.readOnly]): the value stays on the left; the right side is a still well that says so, with nothing to tap and no list behind it. */
@Composable
private fun ReadOnlyWell(s: DacSetting) {
    val c = pal
    val well = RoundedCornerShape(Radii.M)
    Box(Modifier.fillMaxSize().clip(well).background(c.track).testTag("dac_readonly_${s.id}"), contentAlignment = Alignment.Center) {
        Text("READ ONLY", style = Type.small, color = c.textDim, maxLines = 1, softWrap = false)
    }
}

/** OFF | ON, or the 2-3 options: a pressed-in well with a raised orange pill on the chosen one (the filter type row's language). */
@Composable
private fun OptionWell(s: DacSetting, value: Int?, live: Boolean, onPick: (Int) -> Unit, onBlocked: () -> Unit) {
    val c = pal
    val haptics = LocalHaptics.current
    val well = RoundedCornerShape(Radii.M)
    val pill = RoundedCornerShape(Radii.S)
    val at by animateFloatAsState((value ?: 0).toFloat(), label = "dac-option")
    BoxWithConstraints(
        Modifier.fillMaxSize().clip(well).background(c.track).sink(well).padding(Grid.INSET).testTag("dac_well_${s.id}"),
    ) {
        val w = maxWidth / s.options.size
        if (value != null) Box(
            Modifier.offset(x = w * at).width(w).fillMaxHeight().lift(pill, Lift.RAISED).background(c.accent, pill),
        )
        Row(Modifier.fillMaxSize()) {
            s.options.forEachIndexed { i, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(pill)
                        .clickable {
                            if (live) { if (i != value) { haptics.segment(); onPick(i) } }
                            else if (i != value) { haptics.reject(); onBlocked() }
                        }
                        .testTag("dac_option_${s.id}_$i"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = Type.segment, color = if (i == value) c.onAccent else c.text, maxLines = 1)
                }
            }
        }
    }
}

/**
 * A tall slider in the language of RelSlider: a pressed-in track, the value as the light fill, a relative drag (touch-down never
 * moves it), the finger holds the pager ([holdsPager]) and lifting it keeps the value ([LiftGuard]). [centered]: the fill grows
 * from the middle (balance). On a real DAC ([preview] false) the drag never changes the value: it reports [onBlocked] and the
 * control stays on what was read. One finger travel across the track is the whole range.
 */
@Composable
private fun DacSlider(s: DacSetting, value: Int?, min: Int, max: Int, centered: Boolean, live: Boolean, onMove: (Int) -> Unit, onCommit: (Int) -> Unit, onBlocked: () -> Unit) {
    val p = pal
    val haptics = LocalHaptics.current
    val pagerLock = LocalPagerLock.current
    val shape = RoundedCornerShape(Radii.M)
    val v = rememberUpdatedState(value)
    val move = rememberUpdatedState(onMove)
    val commit = rememberUpdatedState(onCommit)
    val blocked = rememberUpdatedState(onBlocked)
    val applies = rememberUpdatedState(live)
    val start = if (centered) 0 else (min + max) / 2
    val pos = if (value == null) 0f else ((value - min).toFloat() / (max - min)).coerceIn(0f, 1f)
    val signed = if (value == null) 0f else (value.toFloat() / max).coerceIn(-1f, 1f)
    Box(
        Modifier
            .fillMaxSize()
            .testTag("dac_slider_${s.id}")
            .semantics { contentDescription = "${s.label} slider" }
            // a drag that starts at the screen edge (the volume slider reaches it) must not become the system back gesture (hardware 21.10.2026: start at x > 1200 of 1280)
            .systemGestureExclusion()
            .holdsPager(pagerLock)
            .pointerInput(Unit) { detectTapGestures(onTap = { if (!applies.value) blocked.value() }) }
            .pointerInput(min, max) {
                val guard = LiftGuard<Int>(density, s.label)
                var raw = 0.0
                var ticked = 0
                var atEnd = false
                detectHorizontalDragWithEnds(
                    onStart = { down ->
                        raw = (v.value ?: start).toDouble()
                        ticked = raw.roundToInt()
                        atEnd = false
                        guard.start(down.uptimeMillis, down.position, ticked)
                        if (!applies.value) blocked.value()
                    },
                    // lifting the finger is the one moment a value is sent (a real DAC); the LiftGuard may take the last jitter back first
                    onEnd = { up ->
                        if (up != null && applies.value) {
                            val settled = guard.release(up)?.also { move.value(it) }
                            (settled ?: v.value)?.let { commit.value(it) }
                        }
                    },
                ) { ch, dx ->
                    ch.consume()
                    if (!applies.value) return@detectHorizontalDragWithEnds
                    raw += dx.toDouble() * (max - min) / size.width.coerceAtLeast(1)
                    if (raw < min || raw > max) {
                        if (!atEnd) haptics.reject()
                        atEnd = true
                    } else atEnd = false
                    raw = raw.coerceIn(min.toDouble(), max.toDouble())
                    val next = raw.roundToInt()
                    if (next != v.value) move.value(next)
                    guard.move(ch.uptimeMillis, ch.position, next)
                    if (next != ticked && !guard.settling(ch.uptimeMillis)) {
                        if (centered && next == 0) haptics.crossing(2) else if (next % 10 == 0) haptics.crossing(1)
                        ticked = next
                    }
                }
            }
            .clip(shape)
            .background(p.track)
            .sink(shape),
    ) {
        if (centered) Box(Modifier.align(Alignment.Center).width(2.dp).fillMaxHeight(0.5f).background(p.textMute))
        if (value != null) Box(
            Modifier
                .layout { m, cs ->
                    val h = cs.maxHeight
                    if (centered) {
                        val half = cs.maxWidth / 2
                        val w = (abs(signed) * half).roundToInt()
                        val pl = m.measure(Constraints.fixed(w, h))
                        layout(cs.maxWidth, h) { if (w > 0) pl.place(if (signed < 0) half - w else half, 0) }
                    } else {
                        val w = (h + (cs.maxWidth - h) * pos).roundToInt().coerceIn(h, cs.maxWidth)
                        val pl = m.measure(Constraints.fixed(w, h))
                        layout(w, h) { pl.place(0, 0) }
                    }
                }
                .lift(shape, Lift.RAISED)
                .background(p.fill, shape),
        )
    }
}
