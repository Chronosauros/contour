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
import androidx.compose.ui.platform.LocalWindowInfo
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
import io.github.chronosauros.contour.core.BuildChannel
import io.github.chronosauros.contour.core.Proven
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
import io.github.chronosauros.contour.core.isShelf

/** Text fields in sheets and dialogs: the M corner of the app instead of the Material 4 dp. */
private val FIELD = RoundedCornerShape(Radii.M)

/** The bottom sheets of the app. */
sealed interface Sheet {
    data class Edit(val id: String) : Sheet
    data object New : Sheet
    data class Value(val param: Param) : Sheet
    data class BandActions(val index: Int) : Sheet
    data class SendFailure(val reason: String?) : Sheet
    /** Review hook only (`--es sheet playbeta`): the Google Play beta board, which a review build never shows by itself. */
    data object PlayBetaPreview : Sheet
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
    // what an emptied field falls back to: the name the sheet opened with, not the last letter left on the way to empty ("Bass" cleared ended as "B")
    val openedName = remember(id) { p.name }
    var sub by remember(id) { mutableStateOf(p.subtitle) }
    val sharePreamp = runCatching { p.effectivePreampDb() }.getOrNull()
    SheetFrame(onDone) {
        SheetTitle("EDIT PROFILE")
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(AppModel.NAME_MAX); model.rename(id, name.ifBlank { openedName }) },
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
    fun readClipboard() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = runCatching { cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
        clip = Importer.parse(text)?.let { Importer.fit(it, model.protocol) }
    }
    LaunchedEffect(model.protocol) { readClipboard() }
    val snapshot = device.snapshot
    val dacImport = if (device.link == Link.CONNECTED && snapshot != null)
        snapshot.importExact() else null
    SheetFrame(onDone) {
        // Read again when the sheet's window has the focus back: open the sheet, see nothing to paste, copy the text in the browser, return - PASTE stayed
        // dead until the sheet was closed and opened again. (The system gives the clipboard only to the focused window, so the read waits for the focus.)
        val focused = LocalWindowInfo.current.isWindowFocused
        var away by remember { mutableStateOf(false) }
        LaunchedEffect(focused) {
            if (!focused) away = true
            else if (away) { away = false; readClipboard() }
        }
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
                    ?: "Copies the EQ the DAC holds now. Small rounding is possible."
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
    // A DAC that keeps a whole-dB preamp only: the field opens as -4, not -4.0, and a typed fraction says what is saved (below).
    val wholeDb = param == Param.PREAMP && model.protocol.preampStep == 1.0
    val initial = param.edit(start, wholeDb) // the preamp in full when it is not on a 0.1 step (Param.edit)
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
        // The field shows the value rounded (a Q with two decimals, a gain with one, a frequency in whole Hz); Done on that text, untouched, keeps the stored
        // value: a Q of 0.707 is not rounded to 0.71, a gain read from a DAC (-2.30078125) is not rewritten as -2.3 with an UNDO step and an EDITED card, and
        // a value kept from an import outside this DAC's range is not pulled into it.
        if (band != null && text.text == opening.third) {
            onDone()
            return@commit
        }
        val parsed = param.parse(text.text, model.protocol.caps, band?.let { live.bands.getOrNull(it.first)?.type })
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
        val shelfType = model.current?.bands?.getOrNull(i)?.type?.takeIf { it.isShelf }
        val range = when (param) {
            Param.FREQ -> "20 - 20 000 Hz"
            Param.GAIN -> (shelfType?.let { caps.gainRange(it) } ?: (caps.gainMinDb..caps.gainMaxDb)).let { "%.1f - +%.1f dB".format(java.util.Locale.ROOT, it.start, it.endInclusive) }
            Param.Q -> if (shelfType != null && caps.shelfLimits != null) caps.qRange(shelfType).let { "%.2f - %.2f".format(java.util.Locale.ROOT, it.start, it.endInclusive) } else "0.10 - 10.00"
            Param.PREAMP -> if (model.protocol.usesWalkPlayRules) "%.0f - %s%.0f dB, whole dB".format(java.util.Locale.ROOT, model.protocol.preampMin, if (model.protocol.preampMax > 0) "+" else "", model.protocol.preampMax)
                else "%.1f - +%.1f dB, step %s".format(java.util.Locale.ROOT, model.protocol.preampMin, model.protocol.preampMax, if (model.protocol.preampStep == 1.0) "1" else "0.1")
        }
        // A typed fraction on a whole-dB DAC is saved rounded down (AppModel.setPreamp): say so before DONE, not after (user report 11.11.2026:
        // -3.6 became -4.0 without a word). Only for a value DONE will take; one it refuses gets the refusal instead.
        val savedAs = if (!wholeDb || invalid) null else text.text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }?.let { typed ->
            val down = io.github.chronosauros.contour.core.Preamp.floorTo(typed, model.protocol.preampStep)
            val bands = model.current?.bands
            if (bands == null || down == typed || !model.preampAccepted(bands, typed)) null else down
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; invalid = false; reason = null },
            label = { Text(range) },
            isError = invalid,
            supportingText = when {
                invalid -> ({ Text(reason ?: (if (param == Param.PREAMP) "Enter a value within the range above" else "Enter a valid number")) })
                savedAs != null -> ({ Text("Saved as ${Param.PREAMP.text(savedAs, true)} - this DAC keeps whole dB, so Contour rounds down (never louder).") })
                else -> null
            },
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
    val p = model.current ?: return onDone()
    val b = p.bands.getOrNull(index) ?: return onDone()
    // The band the sheet was opened for. A DAC read can put another profile on the page while the sheet is open (ValueSheet guards the same case): then the
    // buttons close the sheet and change nothing, instead of acting on band n of that other profile.
    val opened = remember { p.id to b.id }
    fun act(change: () -> Unit) {
        val live = model.current
        if (live?.id == opened.first && live.bands.getOrNull(index)?.id == opened.second) change()
        onDone()
    }
    SheetFrame(onDone) {
        SheetTitle("BAND ${index + 1}")
        AppButton(if (b.enabled) "BYPASS" else "ENABLE", { act { model.toggleBypass(index) } }, Modifier.fillMaxWidth().testTag("band_bypass"))
        // the only band stays, as with the "-" of the band row (a profile without a band has no rows to edit)
        AppButton("DELETE", { act { model.deleteBand(index) } }, Modifier.fillMaxWidth().testTag("band_delete"), enabled = p.bands.size > 1)
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
    // the last USB log lines are for the maintainer: a review build shows them, a release shows the title and the reason
    val review = Proven.channel == BuildChannel.PERF
    val lines = remember { UsbLog.lines.value.takeLast(12) }
    SheetFrame(onDone) {
        SheetTitle("SEND FAILED")
        Text(reason ?: "Unknown reason", style = MaterialTheme.typography.bodyLarge)
        if (review) {
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
                val review = Proven.channel == BuildChannel.PERF
                Text(if (walkplay) WalkPlayReset.ERASE_NOTE else FiioReset.ERASE_NOTE, style = MaterialTheme.typography.bodyLarge)
                Text(
                    (if (walkplay) "Keep the DAC plugged in until Contour says the reset is done." + (if (review) " The reset sequence comes from the manufacturer's web tool, not from a capture of this DAC." else "")
                    else "Keep the DAC plugged in until Contour asks you to unplug it and plug it back in after the reset." + (if (review) " The reset command comes from FiiO's own app, not from a capture of this DAC." else "")) +
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
