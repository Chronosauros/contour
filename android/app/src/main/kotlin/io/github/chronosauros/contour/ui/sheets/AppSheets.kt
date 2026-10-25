package io.github.chronosauros.contour.ui.sheets

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import io.github.chronosauros.contour.ui.AppButton
import io.github.chronosauros.contour.ui.Grid
import io.github.chronosauros.contour.ui.Radii
import io.github.chronosauros.contour.ui.Type
import io.github.chronosauros.contour.ui.pal
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.chronosauros.contour.core.ApoText
import io.github.chronosauros.contour.core.DacImport
import io.github.chronosauros.contour.core.ImportedEq
import io.github.chronosauros.contour.core.native.FiioReset
import io.github.chronosauros.contour.core.native.WalkPlayReset
import io.github.chronosauros.contour.model.AppModel
import io.github.chronosauros.contour.model.DacEditor
import io.github.chronosauros.contour.model.ImportFile
import io.github.chronosauros.contour.model.Importer
import io.github.chronosauros.contour.ui.kit.ProfileIcons
import io.github.chronosauros.contour.ui.tune.Param
import io.github.chronosauros.contour.usb.DeviceController
import io.github.chronosauros.contour.usb.Link
import io.github.chronosauros.contour.usb.ResetUi
import io.github.chronosauros.contour.usb.UsbLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Text fields in sheets and dialogs: the M corner of the app instead of the Material 4 dp. */
private val FIELD = RoundedCornerShape(Radii.M)

/** The bottom sheets of the app. */
sealed interface Sheet {
    data class Edit(val id: String) : Sheet
    data object New : Sheet
    data class Value(val param: Param) : Sheet
    data class BandActions(val index: Int) : Sheet
    data class SendFailure(val reason: String?) : Sheet
}

/** A Material 3 bottom sheet whose contents keep their test tags as resource ids (it is its own window). [dismissible] false: a swipe, the scrim and BACK leave it open (the factory reset while it runs). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SheetFrame(onDismiss: () -> Unit, dismissible: Boolean = true, content: @Composable () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { dismissible || it != SheetValue.Hidden })
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(
            Modifier
                .fillMaxWidth()
                .semantics { testTagsAsResourceId = true }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Grid.SIDE)
                .padding(bottom = Grid.SIDE)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(Grid.GAP),
        ) { content() }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, style = Type.rowName, color = pal.text)
}

/** NAME, HEADPHONES, icon grid, SHARE, SAVE .TXT, DUPLICATE, CLEAR EQ. Every change is saved as you type. */
@Composable
fun EditSheet(model: AppModel, id: String, onDone: () -> Unit, onSaveTxt: (profileId: String, fileName: String) -> Unit) {
    val p = model.byId(id) ?: return onDone()
    val context = LocalContext.current
    var name by remember(id) { mutableStateOf(p.name) }
    var sub by remember(id) { mutableStateOf(p.subtitle) }
    val sharePreamp = runCatching { p.effectivePreampDb() }.getOrNull()
    SheetFrame(onDone) {
        SheetTitle("EDIT PROFILE")
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(AppModel.NAME_MAX); model.rename(id, name) },
            label = { Text("NAME") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            shape = FIELD,
            modifier = Modifier.fillMaxWidth().testTag("edit_name"),
        )
        OutlinedTextField(
            value = sub,
            onValueChange = { sub = it.take(40); model.setHeadphones(id, sub) },
            label = { Text("HEADPHONES") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            shape = FIELD,
            modifier = Modifier.fillMaxWidth().testTag("edit_headphones"),
        )
        Text("ICON", style = Type.label, color = pal.textDim)
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
                                .clip(RoundedCornerShape(Radii.S))
                                // the chosen icon is orange, like every other selection in the app
                                .background(if (sel) pal.accent else MaterialTheme.colorScheme.surfaceContainerLow)
                                .clickable { model.setIcon(id, n) }
                                .testTag("icon_$n"),
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) { Icon(v, n, Modifier.size(26.dp), tint = if (sel) pal.onAccent else pal.text) }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Grid.GAP)) {
            AppButton(
                "SHARE",
                onClick = {
                    val cur = model.byId(id) ?: return@AppButton
                    val text = runCatching { ApoText.formatProfile(cur) }.getOrNull() ?: return@AppButton
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, cur.name)
                        .putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(send, cur.name))
                },
                enabled = sharePreamp != null,
                modifier = Modifier.weight(1f).testTag("edit_share"),
            )
            AppButton(
                "SAVE .TXT",
                onClick = {
                    val cur = model.byId(id) ?: return@AppButton
                    // the system file dialog and its answer live in MainActivity, which survives this sheet (and process death)
                    onSaveTxt(id, ApoText.fileNameFor(cur.name))
                },
                enabled = sharePreamp != null,
                modifier = Modifier.weight(1f).testTag("edit_save_txt"),
            )
        }
        if (sharePreamp == null) Text("Cannot share: AUTO preamp unavailable for invalid EQ")
        val clear = model.byId(id)?.let { model.isClear(it) } ?: true
        Row(horizontalArrangement = Arrangement.spacedBy(Grid.GAP)) {
            AppButton("DUPLICATE", { model.duplicate(id); onDone() }, Modifier.weight(1f).testTag("edit_duplicate"))
            AppButton("CLEAR EQ", { model.clearEq(id); onDone() }, Modifier.weight(1f).testTag("edit_clear"), enabled = !clear)
        }
        Text(
            "One flat band, preamp AUTO. UNDO on the EQ page brings the curve back.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** FLAT (one 1 kHz band), PASTE (clipboard APO / AutoEQ / EQ by Ear JSON), OPEN FILE (a .txt), FROM DAC (the library profile for what plays, or a new one). The profile opens in Tune. */
@Composable
fun NewProfileSheet(model: AppModel, device: DeviceController, editor: DacEditor, onDone: () -> Unit) {
    val context = LocalContext.current
    var clip by remember { mutableStateOf<Pair<ImportedEq, String?>?>(null) }
    val scope = rememberCoroutineScope()
    var reading by remember { mutableStateOf(false) }
    var fileMessage by remember { mutableStateOf<String?>(null) }
    // OPEN FILE: the system file picker (no storage permission); browsers save squig.link's .txt with odd MIME types.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            reading = true
            scope.launch {
                val incoming = withContext(Dispatchers.IO) { ImportFile.fromUri(context.contentResolver, uri) }
                reading = false
                if (incoming.eq != null && ImportFile.create(model, incoming)) onDone()
                else {
                    fileMessage = incoming.error ?: model.notice
                    model.notice = null
                }
            }
        }
    }
    LaunchedEffect(model.protocol) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = runCatching { cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
        clip = Importer.parse(text)?.let { Importer.fit(it, model.protocol) }
    }
    val snapshot = device.snapshot
    val dacImport = if (device.link == Link.CONNECTED && snapshot != null)
        snapshot.importExact() else null
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
        Option(
            "OPEN FILE",
            fileMessage ?: if (reading) "Reading the file..." else "A .txt from squig.link, graph.hangout.audio or AutoEQ",
            !reading,
            "new_file",
        ) {
            fileMessage = null
            picker.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
        }
        Option(
            "FROM DAC",
            when (dacImport) {
                is DacImport.Ready -> dacImport.warning?.let { "WARNING: $it" }
                    ?: "Re-encodes to the DAC's registers; exact original coefficients are not guaranteed"
                is DacImport.Rejected -> dacImport.issues.joinToString("; ")
                null -> "Connect the DAC first"
            },
            dacImport is DacImport.Ready,
            "new_dac",
        ) {
            // The profile the DAC content already has in the library opens; a new one only when there is none.
            if (dacImport is DacImport.Ready) editor.openFromDac()
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
            .clip(RoundedCornerShape(Radii.L))
            .background(c.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(tag),
    ) {
        Text(title, style = Type.rowName, color = if (enabled) c.onSurface else c.onSurface.copy(alpha = 0.38f))
        Text(detail, style = MaterialTheme.typography.bodySmall, color = if (enabled) c.onSurfaceVariant else c.onSurfaceVariant.copy(alpha = 0.5f))
    }
}

/** A typed value: the system numeric keyboard, Done = set (clamped and quantized to the device range; a Q is only clamped, and Done on an unchanged Q keeps the stored precision). */
@Composable
fun ValueSheet(model: AppModel, param: Param, onDone: () -> Unit) {
    val p = model.current ?: return onDone()
    val i = model.selectedBand
    val b = p.bands.getOrNull(i)
    if (param != Param.PREAMP && b == null) return onDone()
    // The stored value opens as it is, even one outside the range (a preserved import): Done unchanged then keeps it.
    val start = if (param == Param.PREAMP) p.preampDb?.let { model.protocol.heldPreamp(it) } ?: runCatching { model.shownPreamp(p) }.getOrNull() else param.of(b!!)
    if (start == null) {
        SheetFrame(onDone) {
            SheetTitle("PREAMP")
            Text("AUTO preamp unavailable for invalid EQ. Fix the shelf settings first.")
            AppButton("CLOSE", onDone, Modifier.fillMaxWidth(), primary = true)
        }
        return
    }
    val initial = param.edit(start) // the preamp in full when it is not on a 0.1 step (Param.edit)
    val opening = remember { Triple(p.id, if (param == Param.PREAMP) null else i to b!!.id, initial) }
    var text by remember { mutableStateOf(TextFieldValue(opening.third, TextRange(0, opening.third.length))) }
    var invalid by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val commit = commit@{
        val live = model.current
        val band = opening.second
        if (live == null || live.id != opening.first ||
            (band != null && (model.selectedBand != band.first || live.bands.getOrNull(band.first)?.id != band.second))) {
            onDone()
            return@commit
        }
        if (param == Param.PREAMP) {
            // Nothing typed: write nothing; a stored value the device would not take stays open with the planner's reason.
            // Typed text goes to the setter exactly as validated (it does its own 0.1 rounding); if it is not applied the
            // sheet stays open. The sheet never closes after an edit that was not applied.
            val typed = text.text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
            if (text.text == opening.third) {
                val why = live.preampDb?.let { model.storedPreampRefusal(live.bands, it) }
                if (why == null) onDone() else { reason = why; invalid = true }
            } else if (typed == null) {
                reason = "Enter a number"
                invalid = true
            } else if (model.setPreamp(typed)) {
                onDone()
            } else {
                reason = model.preampRefusal(live.bands, typed) ?: "Enter a value within the range above"
                invalid = true
            }
            return@commit
        }
        // The field shows a Q with two decimals; Done on that text must not round a stored 0.707 to 0.71.
        if (param == Param.Q && band != null && text.text == opening.third) {
            onDone()
            return@commit
        }
        val parsed = param.parse(text.text, model.protocol.caps)
        if (parsed == null || band == null) {
            invalid = true
        } else {
            model.setBand(band.first, param.set(live.bands[band.first], parsed))
            onDone()
        }
    }
    SheetFrame(onDone) {
        SheetTitle(if (param == Param.PREAMP) "PREAMP" else "BAND ${i + 1} - ${param.label}")
        val caps = model.protocol.caps
        val range = when (param) {
            Param.FREQ -> "20 - 20 000 Hz"
            Param.GAIN -> "%.1f - +%.1f dB".format(java.util.Locale.ROOT, caps.gainMinDb, caps.gainMaxDb)
            Param.Q -> "0.10 - 10.00"
            Param.PREAMP -> if (model.protocol.usesWalkPlayRules) "curve preamp, device %.0f - %s%.0f dB".format(java.util.Locale.ROOT, model.protocol.preampMin, if (model.protocol.preampMax > 0) "+" else "", model.protocol.preampMax)
                else "%.1f - +%.1f dB, step 0.1".format(java.util.Locale.ROOT, model.protocol.preampMin, model.protocol.preampMax)
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; invalid = false; reason = null },
            label = { Text(range) },
            isError = invalid,
            supportingText = if (invalid) ({ Text(reason ?: (if (param == Param.PREAMP) "Enter a value within the range above" else "Enter a valid number")) }) else null,
            suffix = { if (param.unit.isNotEmpty()) Text(param.unit) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            shape = FIELD,
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("value_input"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Grid.GAP)) {
            if (param == Param.GAIN || param == Param.PREAMP) {
                AppButton(
                    "+/-",
                    onClick = {
                        val t = text.text
                        val n = if (t.startsWith("-")) t.drop(1) else "-$t"
                        text = TextFieldValue(n, TextRange(n.length))
                    },
                    modifier = Modifier.weight(1f).testTag("value_sign"),
                )
            }
            AppButton("DONE", { commit() }, Modifier.weight(1f).testTag("value_done"), primary = true)
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
        AppButton(if (b.enabled) "BYPASS" else "ENABLE", { model.toggleBypass(index); onDone() }, Modifier.fillMaxWidth().testTag("band_bypass"))
        AppButton("DELETE", { model.deleteBand(index); onDone() }, Modifier.fillMaxWidth().testTag("band_delete"))
    }
}

/** One option out of several (the DAC page's long choices): a button per option, the current one in the accent colour; picking one closes the sheet. */
@Composable
fun ChoiceSheet(title: String, options: List<String>, current: Int?, onPick: (Int) -> Unit, onDone: () -> Unit) {
    SheetFrame(onDone) {
        SheetTitle(title)
        options.forEachIndexed { i, label ->
            AppButton(label, { onPick(i); onDone() }, Modifier.fillMaxWidth().testTag("choice_option_$i"), primary = i == current)
        }
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

/**
 * FACTORY RESET of the SnowSky Melody and Tiny A and the FiiO KA15, one sheet for the whole flow ([DeviceController.resetUi]): what may be erased and the two buttons, then the steps
 * while it runs, then what changed with the one action to write the saved copy back. It lives here, not on the DAC SETTINGS page, because the DAC leaves the bus after the
 * reset and the page leaves with it.
 */
@Composable
fun FactoryResetSheet(device: DeviceController) {
    val state = device.resetUi
    val busy = state is ResetUi.Running || state == ResetUi.Restoring
    val close = { if (state == ResetUi.Confirm) device.cancelFactoryReset() else device.dismissFactoryReset() }
    SheetFrame(close, dismissible = !busy) {
        when (state) {
            ResetUi.Idle -> Unit
            ResetUi.Confirm -> {
                SheetTitle("FACTORY RESET")
                val walkplay = device.resetIsWalkPlay
                Text(if (walkplay) WalkPlayReset.ERASE_NOTE else FiioReset.ERASE_NOTE, style = MaterialTheme.typography.bodyLarge)
                Text(
                    (if (walkplay) "Keep the DAC plugged in until Contour says the reset is done. The reset sequence comes from the manufacturer's web tool, not from a capture of this DAC."
                    else "Keep the DAC plugged in until Contour asks you to unplug it and plug it back in after the reset. The reset command comes from FiiO's own app, not from a capture of this DAC.") +
                        (if (device.resetProven) "" else " This reset is not verified on hardware yet."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AppButton("SAVE A COPY AND RESET", { device.confirmFactoryReset() }, Modifier.fillMaxWidth().testTag("reset_confirm"), primary = true)
                AppButton("CANCEL", { device.cancelFactoryReset() }, Modifier.fillMaxWidth().testTag("reset_cancel"))
            }
            is ResetUi.Running -> {
                SheetTitle("FACTORY RESET")
                Text(state.what, style = Type.segment, color = pal.text, modifier = Modifier.testTag("reset_step"))
                Text(
                    if (state.what == ResetUi.REPLUG_STEP) "The reset was sent. Unplug the DAC now and plug it back in; Contour goes on when it sees it come back." else "Do not unplug the DAC.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The wait for the unplug and plug-in has no time limit, so it is the one running step the owner can leave (it ends as the old time limit did: STOPPED, the saved copy can be written back).
                if (state.what == ResetUi.REPLUG_STEP) AppButton("STOP WAITING", { device.stopWaitingForReplug() }, Modifier.fillMaxWidth().testTag("reset_stop_waiting"))
            }
            is ResetUi.Result -> {
                SheetTitle("RESET SENT")
                if (state.changes.isEmpty()) Text(
                    "The DAC reads the same as the saved copy: the reset may not have run, or it erased nothing.",
                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("reset_unchanged"),
                ) else {
                    Text("The reset changed:", style = Type.label, color = pal.textDim)
                    Text(state.changes.joinToString("\n"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("reset_changes"))
                }
                state.sentNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(
                    "The copy from before the reset is saved on the phone (${state.backupFile}).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.changes.isNotEmpty()) AppButton("WRITE THE SAVED COPY BACK", { device.restoreAfterFactoryReset() }, Modifier.fillMaxWidth().testTag("reset_restore"), primary = true)
                AppButton("DONE", { device.dismissFactoryReset() }, Modifier.fillMaxWidth().testTag("reset_done"))
            }
            ResetUi.Restoring -> {
                SheetTitle("WRITING THE SAVED COPY BACK")
                Text(
                    if (device.resetIsWalkPlay) "Bands, preamp and settings, one after another. Do not unplug the DAC." else "Slot names, slot EQs and settings, one after another. Do not unplug the DAC.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            is ResetUi.Restored -> {
                SheetTitle(if (state.ok) "SAVED COPY WRITTEN BACK" else "SAVED COPY NOT FULLY WRITTEN")
                Text(
                    if (state.ok) "Every slot and setting reads back as saved." else "Something did not read back as saved:",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    state.lines.joinToString("\n"),
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("reset_restore_lines"),
                )
                AppButton("DONE", { device.dismissFactoryReset() }, Modifier.fillMaxWidth().testTag("reset_done"), primary = true)
            }
            is ResetUi.Failed -> {
                SheetTitle("FACTORY RESET STOPPED")
                Text(state.reason, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("reset_failed"))
                Text(
                    if (state.sent) "The reset command had been sent." else "Nothing was sent to the DAC.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.backupFile?.let {
                    Text("The copy of the DAC is saved on the phone ($it).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.canRestore) AppButton("WRITE THE SAVED COPY BACK", { device.restoreAfterFactoryReset() }, Modifier.fillMaxWidth().testTag("reset_restore"), primary = true)
                AppButton("DONE", { device.dismissFactoryReset() }, Modifier.fillMaxWidth().testTag("reset_done"))
            }
        }
    }
}
