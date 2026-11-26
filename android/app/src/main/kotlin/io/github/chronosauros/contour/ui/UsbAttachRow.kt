package io.github.chronosauros.contour.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.chronosauros.contour.R
import io.github.chronosauros.contour.ui.kit.Haptics
import io.github.chronosauros.contour.ui.kit.LocalHaptics
import io.github.chronosauros.contour.ui.kit.pressStart
import io.github.chronosauros.contour.ui.sheets.ChoiceSheet
import io.github.chronosauros.contour.usb.AttachOpen
import kotlin.math.ceil

/** The settings rows of the library's bottom (owner 26.11.2026, the DAC pages' kind of row): 64 dp like a profile row, the label from [Grid.TEXT], the control 48 dp with [Grid.GAP] air to the top, bottom and right edge. */
private val SETTING_ROW = 64.dp
private val CONTROL_H = 48.dp
/** The knob of a well slides like the prototype's (.2 s, cubic-bezier(.2, .8, .2, 1)). */
private val KNOB_EASE = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/**
 * The width of ONE option of every well of the library's foot (owner 26.11.2026: the ON knob as wide as the MEDIUM knob): the widest of OFF, ON, LOW, MEDIUM and HIGH in
 * [Type.segment] (so MEDIUM fits without shrinking, whatever the font scale) and [Grid.INSET] on each side. The OFF | ON well and the HAPTIC FEEDBACK menu well are two
 * of them and a well's [Grid.INSET] each side, so both end in the same short control.
 */
@Composable
internal fun rememberOptionWidth(): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val words = listOf(R.string.switch_off, R.string.switch_on, R.string.haptics_low, R.string.haptics_medium, R.string.haptics_high).map { stringResource(it) }
    return remember(words, density, measurer) {
        val px = words.maxOf { measurer.measure(it, style = Type.segment, maxLines = 1, softWrap = false).size.width }
        Dp(ceil(with(density) { px.toDp() }.value)) + Grid.INSET * 2
    }
}

/** The width of a well of [options] options of [optionW] each. */
internal fun wellWidth(optionW: Dp, options: Int): Dp = optionW * options + Grid.INSET * 2

/** One settings row: the [label] on the left in [Type.label] (up to three lines), the [control] of [wellW] x 48 dp on the right. */
@Composable
private fun SettingRow(label: String, wellW: Dp, modifier: Modifier, control: @Composable () -> Unit) {
    val c = pal
    Row(
        modifier.fillMaxWidth().height(SETTING_ROW).padding(start = Grid.TEXT, end = Grid.GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Type.label, color = c.textDim, maxLines = 3, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(Grid.GAP))
        Box(Modifier.width(wellW).height(CONTROL_H)) { control() }
    }
}

/**
 * OPEN CONTOUR WHEN A DAC IS PLUGGED IN ([AttachOpen]): the label on the left, an OFF | ON well on the right ([optionW] per option, [rememberOptionWidth]).
 * On by default: Android asks to open Contour, as it always did.
 */
@Composable
fun UsbAttachRow(optionW: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var on by remember { mutableStateOf(AttachOpen.isOn(context)) }
    SettingRow(stringResource(R.string.usb_attach_label), wellWidth(optionW, 2), modifier.testTag("usb_attach")) {
        OptionWell(
            listOf(stringResource(R.string.switch_off), stringResource(R.string.switch_on)), if (on) 1 else 0, "usb_attach",
        ) { i ->
            AttachOpen.set(context, i == 1)
            on = AttachOpen.isOn(context)
        }
    }
}

/**
 * HAPTIC FEEDBACK ([Haptics.level]): the label on the left, a menu like the DAC page's FILTER on the right (the level in a well with the menu sign, as wide as OFF | ON);
 * a tap opens the ChoiceSheet with OFF / LOW / MEDIUM / HIGH (MEDIUM = the strength of 14.11.2026, which was ON). A new level plays its own press at once, so the finger
 * feels the strength it picked.
 */
@Composable
fun HapticsRow(optionW: Dp, modifier: Modifier = Modifier) {
    val haptics = LocalHaptics.current
    var level by remember { mutableIntStateOf(haptics.level) }
    var choosing by remember { mutableStateOf(false) }
    val names = listOf(
        stringResource(R.string.switch_off), stringResource(R.string.haptics_low),
        stringResource(R.string.haptics_medium), stringResource(R.string.haptics_high),
    )
    val pick = { i: Int ->
        haptics.level = i
        level = haptics.level
        if (i != Haptics.OFF) haptics.segTap()
    }
    val shown = names.getOrElse(level) { "--" }
    SettingRow(stringResource(R.string.haptics_label), wellWidth(optionW, 2), modifier.testTag("haptics")) {
        ChoiceWell(shown) { choosing = true }
    }
    if (choosing) ChoiceSheet(stringResource(R.string.haptics_label), names, level, onPick = pick, onDone = { choosing = false })
}

/**
 * A copy of the look of the DAC page's ChoiceButton (private there): a pressed-in well, the current value in [Type.segment] with the
 * menu sign, [Grid.TEXT] in from the edges.
 */
@Composable
private fun ChoiceWell(value: String, onOpen: () -> Unit) {
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
            .testTag("haptics_choice"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(value, style = Type.segment, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = c.textDim)
    }
}

/**
 * A pressed-in well with a raised orange knob under the chosen option (the DAC Settings option well): M corners, the knob S corners, [Grid.INSET]
 * inside.
 */
@Composable
private fun OptionWell(options: List<String>, value: Int, tag: String, onPick: (Int) -> Unit) {
    val haptics = LocalHaptics.current
    val c = pal
    val well = RoundedCornerShape(Radii.M)
    val pill = RoundedCornerShape(Radii.S)
    val at by animateFloatAsState(value.toFloat(), tween(200, easing = KNOB_EASE), label = "$tag-well")
    BoxWithConstraints(Modifier.fillMaxSize().clip(well).background(c.track).sink(well).padding(Grid.INSET)) {
        val w = maxWidth / options.size
        Box(Modifier.offset(x = w * at).width(w).fillMaxHeight().lift(pill, Lift.RAISED).background(c.accent, pill))
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(pill)
                        .pressStart { if (i != value) haptics.segTap() }
                        .clickable { if (i != value) onPick(i) }
                        .semantics { role = Role.RadioButton; selected = i == value }
                        .testTag("${tag}_$i"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = Type.segment, color = if (i == value) c.onAccent else c.text, maxLines = 1)
                }
            }
        }
    }
}
