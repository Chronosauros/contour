package io.github.chronosauros.contour.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.chronosauros.contour.ui.kit.LocalHaptics
import io.github.chronosauros.contour.ui.kit.pressStart
import io.github.chronosauros.contour.usb.AttachOpen

private val CARD = RoundedCornerShape(Radii.L)
private val WELL_W = 132.dp
private val OPTIONS = listOf("OFF", "ON")

/**
 * OPEN CONTOUR WHEN A DAC IS PLUGGED IN ([AttachOpen]): a card in the language of the DAC Settings rows - the label over the value on
 * the left, OFF | ON in a pressed-in well with the raised orange pill on the right. No line of text under it (owner 14.11.2026: "wywalcie
 * ten tekst opisowy"). On by default: Android asks to open Contour, as it always did. It stands at the foot of the library, above ABOUT
 * (owner 13.11.2026).
 */
@Composable
fun UsbAttachRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var on by remember { mutableStateOf(AttachOpen.isOn(context)) }
    OnOffRow(modifier, "OPEN CONTOUR WHEN A DAC IS PLUGGED IN", on, tag = "usb_attach") { want ->
        AttachOpen.set(context, want)
        on = AttachOpen.isOn(context)
    }
}

/**
 * HAPTIC FEEDBACK ([io.github.chronosauros.contour.ui.kit.Haptics.enabled]): the same card right under OPEN CONTOUR WHEN A DAC IS PLUGGED IN
 * (owner 14.11.2026: a way to turn the haptics off, in the menu next to that switch). On by default.
 */
@Composable
fun HapticsRow(modifier: Modifier = Modifier) {
    val haptics = LocalHaptics.current
    var on by remember { mutableStateOf(haptics.enabled) }
    OnOffRow(modifier, "HAPTIC FEEDBACK", on, tag = "haptics") { want ->
        haptics.enabled = want
        on = haptics.enabled
    }
}

/** One switch card: the label over the value on the left, OFF | ON in a pressed-in well on the right; nothing under it. */
@Composable
private fun OnOffRow(modifier: Modifier, label: String, on: Boolean, tag: String, onSet: (Boolean) -> Unit) {
    val c = pal
    Row(
        modifier
            .fillMaxWidth()
            .testTag(tag)
            .height(Grid.ROW)
            .lift(CARD)
            .background(c.surface, CARD)
            .padding(start = Grid.TEXT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                label, style = Type.paramLabel, color = c.textDim,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            )
            Text(if (on) "ON" else "OFF", style = Type.value, color = c.text, maxLines = 1)
        }
        Box(Modifier.width(WELL_W).fillMaxHeight().padding(Grid.INSET)) {
            OnOffWell(if (on) 1 else 0, tag) { i ->
                val want = i == 1
                if (want != on) onSet(want)
            }
        }
    }
}

/** OFF | ON: the DAC Settings option well (a pressed-in well, the raised orange pill on the chosen one). */
@Composable
private fun OnOffWell(value: Int, tag: String, onPick: (Int) -> Unit) {
    val haptics = LocalHaptics.current
    val c = pal
    val well = RoundedCornerShape(Radii.M)
    val pill = RoundedCornerShape(Radii.S)
    val at by animateFloatAsState(value.toFloat(), label = "$tag-well")
    BoxWithConstraints(Modifier.fillMaxSize().clip(well).background(c.track).sink(well).padding(Grid.INSET)) {
        val w = maxWidth / OPTIONS.size
        Box(Modifier.offset(x = w * at).width(w).fillMaxHeight().lift(pill, Lift.RAISED).background(c.accent, pill))
        Row(Modifier.fillMaxSize()) {
            OPTIONS.forEachIndexed { i, label ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(pill).pressStart { if (i != value) haptics.segTap() }.clickable { onPick(i) }.testTag("${tag}_$i"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = Type.segment, color = if (i == value) c.onAccent else c.text, maxLines = 1)
                }
            }
        }
    }
}
