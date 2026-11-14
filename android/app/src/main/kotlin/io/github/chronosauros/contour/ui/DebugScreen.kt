package io.github.chronosauros.contour.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import io.github.chronosauros.contour.ui.kit.DacFeel
import io.github.chronosauros.contour.ui.kit.FreqFeel
import io.github.chronosauros.contour.ui.kit.GainFeel
import io.github.chronosauros.contour.ui.kit.LocalHaptics
import io.github.chronosauros.contour.ui.kit.NodeFeel
import io.github.chronosauros.contour.ui.kit.PreampFeel
import io.github.chronosauros.contour.ui.kit.QFeel
import io.github.chronosauros.contour.usb.DeviceController
import io.github.chronosauros.contour.usb.Link
import io.github.chronosauros.contour.usb.UsbLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Service screen: the v0.1 USB debug screen (log, Read, Restore flat, Send test), reached by long-pressing the device
 * status. Its Licences button opens the open-source licences.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DebugScreen(device: DeviceController, onClose: () -> Unit) {
    var licences by remember { mutableStateOf(false) }
    var hapticsLab by remember { mutableStateOf(false) }
    if (licences) {
        LicencesScreen { licences = false }
        return
    }
    if (hapticsLab) {
        HapticsLab { hapticsLab = false }
        return
    }
    BackHandler(onBack = onClose)
    val snapshot = device.snapshot
    val lastWrite = device.lastWrite
    val onRead = device::read
    val onTestWrite = device::serviceTestWrite
    val onRestoreFlat = device::serviceRestoreFlat
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val lines by UsbLog.lines.collectAsState()
    val connected = device.link == Link.CONNECTED && !device.busy

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Contour - service screen (USB)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { hapticsLab = true }) { Text("Haptics") }
                    TextButton(onClick = { licences = true }) { Text("Licences") }
                }
                Text(
                    when {
                        device.busy -> "DAC: busy"
                        device.link == Link.NO_DAC -> "DAC: not connected"
                        device.link == Link.NEEDS_PERMISSION -> "DAC: needs permission"
                        else -> "DAC: connected" + (snapshot?.firmware?.let { ", firmware $it" } ?: "")
                    } + (device.error?.let { " - error: $it" } ?: ""),
                )
                if (device.protocol.isFamily) {
                    val verified = device.protocol.run { moondrop?.hardwareVerified == true || ktMicro?.hardwareVerified == true || gen1?.hardwareVerified == true || echo?.hardwareVerified == true } // Dawn Pro 2 and Allegro Mini since 25.10.2026
                    Text(device.protocol.caps.name + if (device.protocol.readOnly) " - READ ONLY, not verified on hardware" else if (verified) "" else " - not verified on hardware")
                } else if (device.protocol.experimental) {
                    Text(device.protocol.caps.name)
                    Text(
                        "${device.protocol.caps.bands}-band PEQ with native shelves. A/B is unavailable on the ${device.protocol.caps.name}.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (device.link == Link.NEEDS_PERMISSION) Button(onClick = device::requestPermission) { Text("Ask for USB permission") }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRead, enabled = connected) { Text("Read from DAC") }
                    Button(
                        onClick = { confirm = "Write band 1 gain -0.5 dB to the DAC and save it to its flash?" to onTestWrite },
                        enabled = connected && !device.protocol.experimental,
                    ) { Text("Send test: band 1 -0.5 dB") }
                    Button(
                        onClick = {
                            confirm = (if (device.protocol.experimental) "Write the flat EQ (${device.protocol.caps.bands} x 0 dB, preamp 0) to the DAC and save it to its flash?"
                                else "Write the factory flat EQ (8 x 0 dB, preamp 0) to the DAC and save it to its flash?") to onRestoreFlat
                        },
                        enabled = connected && !device.protocol.native,
                    ) { Text("Restore flat") }
                }
                snapshot?.let { s ->
                    Text(
                        "slot ${s.slot}, preamp ${s.preampDb ?: s.shownPreamp} dB, read ${s.readMs} ms\n" + (s.familyState?.describe() ?: s.nativeState?.describe() ?: s.bands.joinToString("\n") { b ->
                            val r = b.registers
                            "%d  %s %7.1f Hz %6.2f dB Q %.3f   raw f=%d q=%d g=%d t=%d".format(
                                r.index + 1, b.type.name.take(2), b.freqHz, b.gainDb, b.q, r.freq, r.q256, r.gain256, r.typeCode,
                            )
                        }),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
                lastWrite?.let { w ->
                    Text(
                        "last write: verified ${if (w.verified) "YES" else if (w.pending != null) "PENDING" else "NO"}, write ${w.writeMs} ms, read-back ${w.readBackMs} ms" +
                            w.mismatches.joinToString("") { "\n  $it" },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
                HorizontalDivider()
                val listState = rememberLazyListState()
                LaunchedEffect(lines.lastOrNull()) { if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex) }
                LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState) {
                    items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 9.sp, lineHeight = 11.sp) }
                }
            }
        }
    }

    confirm?.let { (question, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Write to the DAC?") },
            text = { Text(question) },
            confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text("Write") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

/**
 * The haptics lab (owner 14.11.2026, perf build's service screen): the tier, what the vibrator can do, and one button per token
 * to feel each alone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HapticsLab(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    val info = remember { haptics.describe() }
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Haptics lab", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("Close") }
                }
                info.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                HorizontalDivider()
                Text("GAIN (round 1)", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabButton("GAIN grain 0 dB") { haptics.dragGrain(GainFeel.grainScale(0f, 0f)) }
                    LabButton("GAIN grain far") { haptics.dragGrain(GainFeel.grainScale(1f, 0f)) }
                    LabButton("GAIN tooth 1 dB") { haptics.dragTooth(GainFeel.toothScale(0.1f, 0f)) }
                    LabButton("GAIN tooth far") { haptics.dragTooth(GainFeel.toothScale(1f, 0f)) }
                    LabButton("zero click") { haptics.dragClick(GainFeel.zeroScale(0f)) }
                    LabButton("wall") { haptics.dragClick(GainFeel.wallScale(0f)) }
                }
                Text("FREQ and node (round 2)", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabButton("FREQ grain low") { haptics.dragGrain(FreqFeel.grainLowScale(0f), count = 2) }
                    LabButton("FREQ grain mid") { haptics.dragGrain(FreqFeel.grainMidScale(0f)) }
                    LabButton("FREQ grain high") { haptics.dragGrain(FreqFeel.grainHighScale(0f), crisp = true) }
                    LabButton("line") { haptics.dragTooth(FreqFeel.lineScale(false, 0f)) }
                    LabButton("decade line") { haptics.dragTooth(FreqFeel.lineScale(true, 0f)) }
                    LabButton("node texture pack") { haptics.dragGrain(NodeFeel.packScale(0f), count = NodeFeel.PACK_TICKS) }
                }
                Text("PREAMP and DAC settings (round 3)", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabButton("PREAMP step at -24 dB") { haptics.dragTooth(PreampFeel.toothScale(0f, 0f)) }
                    LabButton("PREAMP step at 0 dB") { haptics.dragTooth(PreampFeel.toothScale(1f, 0f)) }
                    LabButton("PREAMP end") { haptics.dragClick(GainFeel.wallScale(0f)) }
                    LabButton("DAC step low") { haptics.dragTooth(DacFeel.stepScale(0f, 0f)) }
                    LabButton("DAC step high") { haptics.dragTooth(DacFeel.stepScale(1f, 0f)) }
                    LabButton("DAC end") { haptics.dragClick(GainFeel.wallScale(0f)) }
                    LabButton("balance home") { haptics.dragClick(DacFeel.homeScale()) }
                }
                Text("Q (round 4)", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabButton("Q grain wide") { haptics.dragGrain(QFeel.grainScale(0f, 0f)) }
                    LabButton("Q grain narrow") { haptics.dragGrain(QFeel.grainScale(1f, 0f)) }
                    LabButton("Q doubling") { haptics.dragTooth(QFeel.toothScale(0.5f, 0f)) }
                    LabButton("Q home") { haptics.dragClick(QFeel.homeScale(0f)) }
                }
                Text("Pages, library, segmented controls", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabButton("threshold off") { haptics.threshold(false) }
                    LabButton("seg start") { haptics.segStart() }
                    LabButton("seg end") { haptics.segEnd() }
                    LabButton("swipe snap") { haptics.swipeSnap() }
                    LabButton("swipe land") { haptics.commit() }
                }
                Text("HOLD TO SEND", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabButton("hold + release") { scope.launch { haptics.holdStart(HOLD_LAB_MS); delay(HOLD_LAB_MS.toLong()); haptics.release() } }
                    LabButton("land") { haptics.land() }
                    LabButton("fail") { haptics.fail() }
                }
            }
        }
    }
}

/** The hold of HOLD TO SEND, as the button runs it. */
private const val HOLD_LAB_MS = 700

@Composable
private fun LabButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick) { Text(label) }
}
