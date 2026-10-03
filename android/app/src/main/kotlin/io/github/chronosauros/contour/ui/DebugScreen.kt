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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import io.github.chronosauros.contour.core.DeviceProtocol
import io.github.chronosauros.contour.usb.DeviceController
import io.github.chronosauros.contour.usb.Link
import io.github.chronosauros.contour.usb.UsbLog

/**
 * Service screen: the v0.1 USB debug screen (log, Read, Restore flat, Send test), reached by long-pressing the device
 * status. Its Licences button opens the open-source licences.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DebugScreen(device: DeviceController, onClose: () -> Unit) {
    var licences by remember { mutableStateOf(false) }
    if (licences) {
        LicencesScreen { licences = false }
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
                Text(device.protocol.caps.name)
                if (device.protocol.walkplay == DeviceProtocol.MAX) Text(
                    "10-band PEQ with native shelves. A/B is unavailable on the ${device.protocol.caps.name}.",
                    style = MaterialTheme.typography.bodySmall,
                )
                else if (device.protocol.experimental) Text("Source-backed beta recipe — not hardware verified. ${if (device.protocol.fosi) "First eight diagnostic bands; whole-bank count/layout UNKNOWN" else "${device.protocol.caps.bands} PEQ slots"}; ${device.protocol.caps.types.joinToString()}. A/B, UAC and extra commands are unavailable. ${if (device.protocol.writeBlocker != null) "READ ONLY: ${device.protocol.writeBlocker}" else "Use HOLD TO SEND for EQ writes."}", style = MaterialTheme.typography.bodySmall)
                if (device.link == Link.NEEDS_PERMISSION) Button(onClick = device::requestPermission) { Text("Ask for USB permission") }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRead, enabled = connected) { Text("Read from DAC") }
                    if (device.hardwareVolumeSupported) {
                    Button(
                        onClick = { confirm = "Write band 1 gain -0.5 dB to the DAC and save it to its flash?" to onTestWrite },
                        enabled = connected && !device.protocol.experimental,
                    ) { Text("Send test: band 1 -0.5 dB") }
                    Button(
                        onClick = {
                            confirm = (if (device.protocol.experimental) "Write the flat EQ (${device.protocol.caps.bands} x 0 dB, preamp 0) to the DAC and save it to its flash?"
                                else "Write the factory flat EQ (8 x 0 dB, preamp 0) to the DAC and save it to its flash?") to onRestoreFlat
                        },
                        enabled = connected && (!device.protocol.experimental || device.protocol.walkplay == DeviceProtocol.MAX),
                    ) { Text("Restore flat") }
                    }
                }
                // Hardware volume (USB Audio Class Feature Unit) - the control bit-perfect players set in exclusive mode.
                if (device.hardwareVolumeSupported) {
                Text("Hardware volume" + (device.volume?.let { v ->
                    ": " + v.current256.joinToString(" / ") { "%.1f dB".format(it / 256.0) } +
                        (v.range?.let { r -> "  (range %.1f..%.1f, step %.2f)".format(r.min256 / 256.0, r.max256 / 256.0, r.res256 / 256.0) } ?: "") +
                        "\n${v.target}, path ${v.path}"
                } ?: if (device.hardwareVolumeSupported) ": not read" else ": unavailable on this target"), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = device::readVolume, enabled = connected && device.hardwareVolumeSupported) { Text("Read volume") }
                    val now = device.volume?.current256?.maxOrNull()
                    Button(
                        onClick = { confirm = "Lower the DAC's hardware volume by 3 dB?" to { device.setVolume(now!! / 256.0 - 3) } },
                        enabled = connected && now != null,
                    ) { Text("Volume -3 dB") }
                    Button(
                        onClick = { confirm = "Raise the DAC's hardware volume by 3 dB? Turn the phone volume down first." to { device.setVolume(now!! / 256.0 + 3) } },
                        enabled = connected && now != null,
                    ) { Text("Volume +3 dB") }
                }
                }
                snapshot?.let { s ->
                    Text(
                        "slot ${s.slot}, preamp ${s.shownPreamp?.let { "$it dB" } ?: "UNKNOWN"}, read ${s.readMs} ms\n" + (s.nativeState?.describe() ?: s.bands.joinToString("\n") { b ->
                            val r = b.registers
                            "%d  %s %7.1f Hz %6.2f dB Q %.3f   raw f=%d q=%d g=%d t=%d".format(
                                r.index + 1, b.type.name.take(2), b.freqHz, b.gainDb, b.q, r.freq, r.q256, r.gain256, r.typeCode,
                            )
                        }),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
                device.pendingReason?.let { Text("PENDING: $it", style = MaterialTheme.typography.bodySmall) }
                lastWrite?.let { w ->
                    Text(
                        "last write: verified ${if (w.verified) "YES" else "NO"}, write ${w.writeMs} ms, read-back ${w.readBackMs} ms" +
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
