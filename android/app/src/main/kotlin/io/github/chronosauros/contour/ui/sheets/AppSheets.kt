package io.github.chronosauros.contour.ui.sheets

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.chronosauros.contour.core.ApoText
import io.github.chronosauros.contour.core.ImportedEq
import io.github.chronosauros.contour.model.AppModel
import io.github.chronosauros.contour.model.Importer
import io.github.chronosauros.contour.model.shownPreamp
import io.github.chronosauros.contour.ui.kit.ProfileIcons
import io.github.chronosauros.contour.ui.tune.Param
import io.github.chronosauros.contour.usb.DeviceController
import io.github.chronosauros.contour.usb.Link
import io.github.chronosauros.contour.usb.UsbLog

/** The bottom sheets of the app. */
sealed interface Sheet {
    data class Edit(val id: String) : Sheet
    data object New : Sheet
    data class Value(val param: Param) : Sheet
    data class BandActions(val index: Int) : Sheet
    data class SendFailure(val reason: String?) : Sheet
}

/** A Material 3 bottom sheet whose contents keep their test tags as resource ids (it is its own window). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SheetFrame(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(
            Modifier
                .fillMaxWidth()
                .semantics { testTagsAsResourceId = true }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

/** NAME, HEADPHONES, icon grid, DUPLICATE, SHARE. Every change is saved as you type. */
@Composable
fun EditSheet(model: AppModel, id: String, onDone: () -> Unit) {
    val p = model.byId(id) ?: return onDone()
    val context = LocalContext.current
    var name by remember(id) { mutableStateOf(p.name) }
    var sub by remember(id) { mutableStateOf(p.subtitle) }
    SheetFrame(onDone) {
        SheetTitle("EDIT PROFILE")
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(AppModel.NAME_MAX); model.rename(id, name) },
            label = { Text("NAME") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().testTag("edit_name"),
        )
        OutlinedTextField(
            value = sub,
            onValueChange = { sub = it.take(40); model.setHeadphones(id, sub) },
            label = { Text("HEADPHONES") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().testTag("edit_headphones"),
        )
        Text("ICON", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val icon = model.byId(id)?.icon
        Column(Modifier.testTag("edit_icons"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ProfileIcons.ALL.chunked(5).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (n, v) ->
                        val sel = n == icon
                        Box(
                            Modifier
                                .weight(1f)
                                .aspectRatio(1.4f)
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (sel) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceContainerLow)
                                .border(if (sel) 2.dp else 0.dp, if (sel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(10.dp))
                                .clickable { model.setIcon(id, n) }
                                .testTag("icon_$n"),
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) { Icon(v, n, Modifier.size(26.dp)) }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { model.duplicate(id); onDone() },
                modifier = Modifier.weight(1f).height(52.dp).testTag("edit_duplicate"),
            ) { Text("DUPLICATE") }
            OutlinedButton(
                onClick = {
                    val cur = model.byId(id) ?: return@OutlinedButton
                    val text = ApoText.format(cur.bands, cur.effectivePreampDb())
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, cur.name)
                        .putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(send, cur.name))
                },
                modifier = Modifier.weight(1f).height(52.dp).testTag("edit_share"),
            ) { Text("SHARE") }
        }
    }
}

/** FLAT (one 1 kHz band), PASTE (clipboard APO / AutoEQ / EQ by Ear JSON), FROM DAC. The new profile opens in Tune. */
@Composable
fun NewProfileSheet(model: AppModel, device: DeviceController, onDone: () -> Unit) {
    val context = LocalContext.current
    var clip by remember { mutableStateOf<Pair<ImportedEq, String?>?>(null) }
    LaunchedEffect(Unit) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = runCatching { cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
        clip = Importer.parse(text)?.let { Importer.fit(it) }
    }
    val snapshot = device.snapshot
    val dac = device.link == Link.CONNECTED && snapshot != null
    SheetFrame(onDone) {
        SheetTitle("NEW PROFILE")
        Option("FLAT", "One flat band at 1 kHz - drag it or add more", true, "new_empty") {
            model.create(listOf(model.flatBand()), null)
            onDone()
        }
        val c = clip
        Option(
            "PASTE",
            if (c == null) "No APO, AutoEQ or EQ by Ear text on the clipboard"
            else "${c.first.bands.size} filter${if (c.first.bands.size == 1) "" else "s"} from the clipboard" + (c.second?.let { " - $it" } ?: ""),
            c != null,
            "new_paste",
        ) {
            val (eq, _) = c ?: return@Option
            model.create(eq.bands, eq.preampDb)
            onDone()
        }
        Option("FROM DAC", if (dac) "The EQ the DAC holds now" else "Connect the DAC first", dac, "new_dac") {
            val s = snapshot ?: return@Option
            val (eq, _) = Importer.fit(Importer.fromDac(s.bands, s.preampDb))
            model.create(eq.bands, eq.preampDb, sub = "FROM DAC")
            onDone()
        }
    }
}

@Composable
private fun Option(title: String, detail: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(c.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(tag),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) c.onSurface else c.onSurface.copy(alpha = 0.38f))
        Text(detail, style = MaterialTheme.typography.bodySmall, color = if (enabled) c.onSurfaceVariant else c.onSurfaceVariant.copy(alpha = 0.5f))
    }
}

/** A typed value: the system numeric keyboard, Done = set (clamped and quantized to the device range). */
@Composable
fun ValueSheet(model: AppModel, param: Param, onDone: () -> Unit) {
    val p = model.current ?: return onDone()
    val i = model.selectedBand
    val b = p.bands.getOrNull(i)
    if (param != Param.PREAMP && b == null) return onDone()
    val start = if (param == Param.PREAMP) shownPreamp(p) else param.of(b!!)
    val initial = param.edit(start)
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    val commit = {
        param.parse(text.text)?.let { v ->
            if (param == Param.PREAMP) model.setPreamp(v)
            else model.current?.bands?.getOrNull(i)?.let { model.setBand(i, param.set(it, v)) }
        }
        onDone()
    }
    SheetFrame(onDone) {
        SheetTitle(if (param == Param.PREAMP) "PREAMP" else "BAND ${i + 1} - ${param.label}")
        val range = when (param) {
            Param.FREQ -> "20 - 20 000 Hz"
            Param.GAIN -> "-10.0 - +10.0 dB"
            Param.Q -> "0.10 - 10.00"
            Param.PREAMP -> "curve preamp, device -30 - 0 dB"
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(range) },
            suffix = { if (param.unit.isNotEmpty()) Text(param.unit) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("value_input"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (param == Param.GAIN || param == Param.PREAMP) {
                OutlinedButton(
                    onClick = {
                        val t = text.text
                        val n = if (t.startsWith("-")) t.drop(1) else "-$t"
                        text = TextFieldValue(n, TextRange(n.length))
                    },
                    modifier = Modifier.weight(1f).height(52.dp).testTag("value_sign"),
                ) { Text("+/-") }
            }
            Button(onClick = { commit() }, modifier = Modifier.weight(1f).height(52.dp).testTag("value_done")) { Text("DONE") }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** Long-press on a band chip: BYPASS / ENABLE and DELETE. */
@Composable
fun BandSheet(model: AppModel, index: Int, onDone: () -> Unit) {
    val b = model.current?.bands?.getOrNull(index) ?: return onDone()
    SheetFrame(onDone) {
        SheetTitle("BAND ${index + 1}")
        OutlinedButton(
            onClick = { model.toggleBypass(index); onDone() },
            modifier = Modifier.fillMaxWidth().height(52.dp).testTag("band_bypass"),
        ) { Text(if (b.enabled) "BYPASS" else "ENABLE") }
        OutlinedButton(
            onClick = { model.deleteBand(index); onDone() },
            modifier = Modifier.fillMaxWidth().height(52.dp).testTag("band_delete"),
        ) { Text("DELETE") }
    }
}

/** FAILED - HOLD TO RETRY, quick tap: the reason and the last USB log lines. */
@Composable
fun SendFailureSheet(reason: String?, onDone: () -> Unit) {
    val lines = remember { UsbLog.lines.value.takeLast(12) }
    SheetFrame(onDone) {
        SheetTitle("SEND FAILED")
        Text(reason ?: "Unknown reason", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            lines.joinToString("\n").ifEmpty { "(no log lines)" },
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
