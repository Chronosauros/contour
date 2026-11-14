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
import io.github.chronosauros.contour.R
import io.github.chronosauros.contour.ui.CoreText
import io.github.chronosauros.contour.ui.UiText
import io.github.chronosauros.contour.ui.asString
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
    data class SendFailure(val reason: UiText?) : Sheet
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
    val sharePreamp = runCatching { p.effectivePreampDb(model.protocol.shelfForm) }.getOrNull()
    SheetFrame(onDone) {
        SheetTitle(stringResource(R.string.edit_title))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(AppModel.NAME_MAX); model.rename(id, name.ifBlank { openedName }) },
            label = { Text(stringResource(R.string.edit_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            shape = FIELD,
            modifier = Modifier.fillMaxWidth().testTag("edit_name"),
        )
        OutlinedTextField(
            value = sub,
            onValueChange = { sub = it.take(40); model.setHeadphones(id, sub) },
            label = { Text(stringResource(R.string.edit_headphones)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            shape = FIELD,
            modifier = Modifier.fillMaxWidth().testTag("edit_headphones"),
        )
        Text(stringResource(R.string.edit_icon), style = Type.label, color = pal.textDim)
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
                        ) { Icon(v, stringResource(ProfileIcons.descriptionRes(n)), Modifier.size(26.dp), tint = if (sel) pal.onAccent else pal.text) }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Grid.GAP)) {
            AppButton(
                stringResource(R.string.edit_share),
                onClick = {
                    val cur = model.byId(id) ?: return@AppButton
                    val text = runCatching { ApoText.formatProfile(cur, model.protocol.shelfForm) }.getOrNull() ?: return@AppButton
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, cur.name)
                        .putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(send, cur.name))
                },
                enabled = sharePreamp != null,
                modifier = Modifier.weight(1f).testTag("edit_share"),
            )
            AppButton(
                stringResource(R.string.edit_save_txt),
                onClick = {
                    val cur = model.byId(id) ?: return@AppButton
                    // the system file dialog and its answer live in MainActivity, which survives this sheet (and process death)
                    onSaveTxt(id, ApoText.fileNameFor(cur.name))
                },
                enabled = sharePreamp != null,
                modifier = Modifier.weight(1f).testTag("edit_save_txt"),
            )
        }
        if (sharePreamp == null) Text(stringResource(R.string.edit_cannot_share))
        val clear = model.byId(id)?.let { model.isClear(it) } ?: true
        Row(horizontalArrangement = Arrangement.spacedBy(Grid.GAP)) {
            AppButton(stringResource(R.string.edit_duplicate), { model.duplicate(id); onDone() }, Modifier.weight(1f).testTag("edit_duplicate"))
            AppButton(stringResource(R.string.action_clear_eq), { model.clearEq(id); onDone() }, Modifier.weight(1f).testTag("edit_clear"), enabled = !clear)
        }
        Text(
            stringResource(R.string.edit_clear_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** FLAT (one 1 kHz band), PASTE (clipboard APO / AutoEQ / EQ by Ear JSON), OPEN FILE (a .txt), FROM DAC (the library profile for what plays, or a new one). The profile opens in Tune. */
@Composable
fun NewProfileSheet(model: AppModel, device: DeviceController, editor: DacEditor, onDone: () -> Unit) {
    val context = LocalContext.current
    var clip by remember { mutableStateOf<Pair<ImportedEq, UiText?>?>(null) }
    val scope = rememberCoroutineScope()
    var reading by remember { mutableStateOf(false) }
    var fileMessage by remember { mutableStateOf<UiText?>(null) }
    // OPEN FILE: the system file picker (no storage permission); browsers save squig.link's .txt with odd MIME types.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            reading = true
            scope.launch {
                val incoming = withContext(Dispatchers.IO) { ImportFile.fromUri(context.contentResolver, uri) }
                reading = false
                if (incoming.eq != null && ImportFile.create(model, incoming)) onDone()
                else {
                    fileMessage = incoming.error ?: model.notice?.text
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
        SheetTitle(stringResource(R.string.new_title))
        Option(stringResource(R.string.new_flat), stringResource(R.string.new_flat_detail), true, "new_empty") {
            model.create(listOf(model.flatBand()), null)
            onDone()
        }
        val c = clip
        Option(
            stringResource(R.string.new_paste),
            if (c == null) stringResource(R.string.new_paste_empty)
            else if (c.second == null) pluralStringResource(R.plurals.new_paste_filters, c.first.bands.size, c.first.bands.size)
            else pluralStringResource(R.plurals.new_paste_filters_report, c.first.bands.size, c.first.bands.size, c.second!!.asString()),
            c != null,
            "new_paste",
        ) {
            val (eq, _) = c ?: return@Option
            model.create(eq.bands, eq.preampDb)
            onDone()
        }
        Option(
            stringResource(R.string.new_open_file),
            fileMessage?.asString() ?: if (reading) stringResource(R.string.new_open_file_reading) else stringResource(R.string.new_open_file_detail),
            !reading,
            "new_file",
        ) {
            fileMessage = null
            picker.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
        }
        Option(
            stringResource(R.string.new_from_dac),
            when (dacImport) {
                is DacImport.Ready -> dacImport.warning?.let { stringResource(R.string.new_from_dac_warning, UiText.Core(it).asString()) }
                    ?: stringResource(R.string.new_from_dac_detail)
                is DacImport.Rejected -> snapshot!!.refusalText(dacImport).asString()
                null -> stringResource(R.string.new_from_dac_connect)
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
            SheetTitle(stringResource(R.string.param_preamp))
            Text(stringResource(R.string.value_auto_unavailable))
            AppButton(stringResource(R.string.action_close), onDone, Modifier.fillMaxWidth(), primary = true)
        }
        return
    }
    // A DAC that keeps a whole-dB preamp only: the field opens as -4, not -4.0, and a typed fraction says what is saved (below).
    val wholeDb = param == Param.PREAMP && model.protocol.preampStep == 1.0
    val initial = param.edit(start, wholeDb) // the preamp in full when it is not on a 0.1 step (Param.edit)
    val opening = remember { Triple(p.id, if (param == Param.PREAMP) null else i to b!!.id, initial) }
    var text by remember { mutableStateOf(TextFieldValue(opening.third, TextRange(0, opening.third.length))) }
    var invalid by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf<UiText?>(null) }
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
                reason = UiText.res(R.string.preamp_enter_number)
                invalid = true
            } else if (model.setPreamp(typed)) {
                onDone()
            } else {
                reason = model.preampRefusal(live.bands, typed) ?: UiText.res(R.string.preamp_enter_in_range)
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
        SheetTitle(if (param == Param.PREAMP) stringResource(R.string.param_preamp) else stringResource(R.string.value_title_band, i + 1, param.label.asString()))
        val caps = model.protocol.caps
        val shelfType = model.current?.bands?.getOrNull(i)?.type?.takeIf { it.isShelf }
        val range = when (param) {
            Param.FREQ -> stringResource(R.string.value_range_freq)
            Param.GAIN -> (shelfType?.let { caps.gainRange(it) } ?: (caps.gainMinDb..caps.gainMaxDb)).let { stringResource(R.string.value_range_gain, "%.1f".format(java.util.Locale.ROOT, it.start), "%.1f".format(java.util.Locale.ROOT, it.endInclusive)) }
            Param.Q -> if (shelfType != null && caps.shelfLimits != null) caps.qRange(shelfType).let { stringResource(R.string.value_range_q_shelf, "%.2f".format(java.util.Locale.ROOT, it.start), "%.2f".format(java.util.Locale.ROOT, it.endInclusive)) } else stringResource(R.string.value_range_q)
            Param.PREAMP -> if (model.protocol.usesWalkPlayRules) stringResource(R.string.value_range_preamp_whole, "%.0f".format(java.util.Locale.ROOT, model.protocol.preampMin), (if (model.protocol.preampMax > 0) "+" else "") + "%.0f".format(java.util.Locale.ROOT, model.protocol.preampMax))
                else stringResource(R.string.value_range_preamp_step, "%.1f".format(java.util.Locale.ROOT, model.protocol.preampMin), "%.1f".format(java.util.Locale.ROOT, model.protocol.preampMax), if (model.protocol.preampStep == 1.0) "1" else "0.1")
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
                invalid -> ({ Text(reason?.asString() ?: stringResource(if (param == Param.PREAMP) R.string.preamp_enter_in_range else R.string.value_enter_valid_number)) })
                savedAs != null -> ({ Text(stringResource(R.string.value_saved_as_whole_db, Param.PREAMP.text(savedAs, true))) })
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
                    stringResource(R.string.value_sign),
                    onClick = {
                        val t = text.text
                        val n = if (t.startsWith("-")) t.drop(1) else "-$t"
                        text = TextFieldValue(n, TextRange(n.length))
                    },
                    modifier = Modifier.weight(1f).testTag("value_sign"),
                )
            }
            AppButton(stringResource(R.string.action_done), { commit() }, Modifier.weight(1f).testTag("value_done"), primary = true)
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
        SheetTitle(stringResource(R.string.band_title, index + 1))
        AppButton(stringResource(if (b.enabled) R.string.band_bypass else R.string.band_enable), { act { model.toggleBypass(index) } }, Modifier.fillMaxWidth().testTag("band_bypass"))
        // the only band stays, as with the "-" of the band row (a profile without a band has no rows to edit)
        AppButton(stringResource(R.string.band_delete), { act { model.deleteBand(index) } }, Modifier.fillMaxWidth().testTag("band_delete"), enabled = p.bands.size > 1)
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
fun SendFailureSheet(reason: UiText?, onDone: () -> Unit) {
    // the last USB log lines are for the maintainer: a review build shows them, a release shows the title and the reason
    val review = Proven.channel == BuildChannel.PERF
    val lines = remember { UsbLog.lines.value.takeLast(12) }
    SheetFrame(onDone) {
        SheetTitle(stringResource(R.string.send_failed_title))
        Text((reason ?: UiText.res(R.string.send_reason_unknown)).asString(), style = MaterialTheme.typography.bodyLarge)
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val busy = state is ResetUi.Running || state == ResetUi.Restoring
    val close = { if (state == ResetUi.Confirm) device.cancelFactoryReset() else device.dismissFactoryReset() }
    SheetFrame(close, dismissible = !busy) {
        when (state) {
            ResetUi.Idle -> Unit
            ResetUi.Confirm -> {
                SheetTitle(stringResource(R.string.dac_factory_reset))
                val walkplay = device.resetIsWalkPlay
                val review = Proven.channel == BuildChannel.PERF
                // a review build keeps the note of :core (it names the steps it does not understand); a release says it in the words of the build
                Text(if (review) (if (walkplay) WalkPlayReset.ERASE_NOTE else FiioReset.ERASE_NOTE)
                    else stringResource(if (walkplay) R.string.reset_erase_note_walkplay else R.string.reset_erase_note_fiio), style = MaterialTheme.typography.bodyLarge)
                Text(
                    (if (walkplay) stringResource(R.string.reset_keep_plugged_walkplay) + (if (review) " The reset sequence comes from the manufacturer's web tool, not from a capture of this DAC." else "")
                    else stringResource(R.string.reset_keep_plugged_fiio) + (if (review) " The reset command comes from FiiO's own app, not from a capture of this DAC." else "")) +
                        (if (device.resetProven) "" else " This reset is not verified on hardware yet."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AppButton(stringResource(R.string.reset_confirm), { device.confirmFactoryReset() }, Modifier.fillMaxWidth().testTag("reset_confirm"), primary = true)
                AppButton(stringResource(R.string.action_cancel), { device.cancelFactoryReset() }, Modifier.fillMaxWidth().testTag("reset_cancel"))
            }
            is ResetUi.Running -> {
                SheetTitle(stringResource(R.string.dac_factory_reset))
                Text(stringResource(when (state.step) {
                    ResetUi.Step.COPY -> R.string.reset_step_copy
                    ResetUi.Step.SEND -> R.string.reset_step_send
                    ResetUi.Step.REPLUG -> R.string.reset_step_replug
                    ResetUi.Step.WAIT -> R.string.reset_step_wait
                    ResetUi.Step.READ -> R.string.reset_step_read
                }), style = Type.segment, color = pal.text, modifier = Modifier.testTag("reset_step"))
                Text(
                    stringResource(if (state.step == ResetUi.Step.REPLUG) R.string.reset_replug_note else R.string.reset_do_not_unplug),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The wait for the unplug and plug-in has no time limit, so it is the one running step the owner can leave (it ends as the old time limit did: STOPPED, the saved copy can be written back).
                if (state.step == ResetUi.Step.REPLUG) AppButton(stringResource(R.string.reset_stop_waiting), { device.stopWaitingForReplug() }, Modifier.fillMaxWidth().testTag("reset_stop_waiting"))
            }
            is ResetUi.Result -> {
                SheetTitle(stringResource(R.string.reset_sent_title))
                if (state.changes.isEmpty()) Text(
                    stringResource(R.string.reset_unchanged),
                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("reset_unchanged"),
                ) else {
                    Text(stringResource(R.string.reset_changed), style = Type.label, color = pal.textDim)
                    Text(state.changes.joinToString("\n") { CoreText.translateResetLine(context, it) }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("reset_changes"))
                }
                state.sentNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(
                    stringResource(R.string.reset_copy_saved_before, state.backupFile),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.changes.isNotEmpty()) AppButton(stringResource(R.string.reset_write_back), { device.restoreAfterFactoryReset() }, Modifier.fillMaxWidth().testTag("reset_restore"), primary = true)
                AppButton(stringResource(R.string.action_done), { device.dismissFactoryReset() }, Modifier.fillMaxWidth().testTag("reset_done"))
            }
            ResetUi.Restoring -> {
                SheetTitle(stringResource(R.string.reset_restoring_title))
                Text(
                    stringResource(if (device.resetIsWalkPlay) R.string.reset_restoring_walkplay else R.string.reset_restoring_fiio),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            is ResetUi.Restored -> {
                SheetTitle(stringResource(if (state.ok) R.string.reset_restored_title else R.string.reset_restored_partial_title))
                Text(
                    stringResource(if (state.ok) R.string.reset_restored_ok else R.string.reset_restored_partial),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    state.lines.joinToString("\n") { CoreText.translateResetLine(context, it) },
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("reset_restore_lines"),
                )
                AppButton(stringResource(R.string.action_done), { device.dismissFactoryReset() }, Modifier.fillMaxWidth().testTag("reset_done"), primary = true)
            }
            is ResetUi.Failed -> {
                SheetTitle(stringResource(R.string.reset_stopped_title))
                Text(state.reason.asString(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("reset_failed"))
                Text(
                    stringResource(if (state.sent) R.string.reset_stopped_sent else R.string.reset_stopped_nothing_sent),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.backupFile?.let {
                    Text(stringResource(R.string.reset_copy_saved, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.canRestore) AppButton(stringResource(R.string.reset_write_back), { device.restoreAfterFactoryReset() }, Modifier.fillMaxWidth().testTag("reset_restore"), primary = true)
                AppButton(stringResource(R.string.action_done), { device.dismissFactoryReset() }, Modifier.fillMaxWidth().testTag("reset_done"))
            }
        }
    }
}
