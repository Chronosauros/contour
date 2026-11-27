package io.github.chronosauros.contour.ui

import io.github.chronosauros.contour.core.BuildChannel
import io.github.chronosauros.contour.core.Proven
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.model.AppModel
import io.github.chronosauros.contour.model.DacEditor
import io.github.chronosauros.contour.model.ImportFile
import io.github.chronosauros.contour.model.Importer
import io.github.chronosauros.contour.model.Incoming
import io.github.chronosauros.contour.model.LastDac
import io.github.chronosauros.contour.model.Page
import io.github.chronosauros.contour.model.Sender
import io.github.chronosauros.contour.ui.kit.LocalHaptics
import io.github.chronosauros.contour.ui.kit.LocalPagerLock
import io.github.chronosauros.contour.ui.kit.PagerLock
import io.github.chronosauros.contour.ui.kit.pressStart
import io.github.chronosauros.contour.ui.kit.rememberGatedHapticFeedback
import io.github.chronosauros.contour.ui.kit.rememberHaptics
import io.github.chronosauros.contour.ui.dac.DacScreen
import io.github.chronosauros.contour.ui.library.LibraryActions
import io.github.chronosauros.contour.ui.library.LibraryScreen
import io.github.chronosauros.contour.ui.sheets.BandSheet
import io.github.chronosauros.contour.ui.sheets.EditSheet
import io.github.chronosauros.contour.ui.sheets.FactoryResetSheet
import io.github.chronosauros.contour.ui.sheets.NewProfileSheet
import io.github.chronosauros.contour.ui.sheets.SendFailureSheet
import io.github.chronosauros.contour.ui.sheets.Sheet
import io.github.chronosauros.contour.ui.sheets.ValueSheet
import io.github.chronosauros.contour.ui.tune.Param
import io.github.chronosauros.contour.ui.tune.TuneActions
import io.github.chronosauros.contour.ui.tune.TuneScreen
import io.github.chronosauros.contour.usb.DeviceController
import io.github.chronosauros.contour.usb.Link
import io.github.chronosauros.contour.usb.ResetUi
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.chronosauros.contour.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** The page bar: a control row like any other, [Grid.SIDE] from the screen edge below and beside it, [Grid.GAP] under the content. */
private val BAR_H = Grid.ROW
private val BAR_EDGE = Grid.SIDE

private class UndoVisuals(override val message: String, override val actionLabel: String) : SnackbarVisuals {
    override val withDismissAction = false
    override val duration = SnackbarDuration.Indefinite
}

/**
 * The shell: DAC Settings (only while the connected DAC has settings), Tune and Library side by side in a pager, the page bar at the bottom, sheets,
 * the undo snackbar and the service screen. [sheetRequest] comes from the review hooks; [incoming] is a text file opened
 * with or shared to Contour, imported only after the user confirms.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ContourApp(
    model: AppModel,
    device: DeviceController,
    sender: Sender,
    editor: DacEditor,
    initialPage: Int,
    sheetRequest: Sheet?,
    onSheetRequestTaken: () -> Unit,
    incoming: Incoming? = null,
    onIncomingDone: () -> Unit = {},
    onSaveTxt: (profileId: String, fileName: String) -> Unit = { _, _ -> },
    onBackup: () -> Unit = {},
    onRestoreBackup: () -> Unit = {},
) {
    LaunchedEffect(device.protocol) { model.protocol = device.protocol }
    // An import with no DAC attached is fitted to the last DAC found, not to the Micro the editor falls back to (audit 10.11.2026, L7). Nothing reaches a DAC here.
    val appContext = LocalContext.current.applicationContext
    val lastDac = remember { LastDac(appContext) }
    LaunchedEffect(device.protocol, device.link) {
        val attached = device.link != Link.NO_DAC
        if (attached) lastDac.seen(device.protocol)
        model.importTarget = if (attached) device.protocol else lastDac.target ?: device.protocol
    }
    if (model.loading || model.loadError) {
        Box(Modifier.fillMaxSize().background(pal.bg).padding(24.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (model.loadError) {
                    Text(stringResource(R.string.app_library_load_error), color = pal.text)
                    AppButton(stringResource(R.string.app_retry_loading), { model.load() })
                } else Text(stringResource(R.string.app_loading_library), color = pal.text)
            }
        }
        return
    }
    val haptics = rememberHaptics()
    val pagerLock = remember { PagerLock() }
    // LocalContentColor: every Text without an explicit color follows the theme (dark mode drew them black)
    val gatedFeedback = rememberGatedHapticFeedback(haptics)
    CompositionLocalProvider(LocalHaptics provides haptics, LocalHapticFeedback provides gatedFeedback, LocalPagerLock provides pagerLock, LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        val context = LocalContext.current
        val prefs = remember { context.getSharedPreferences("ui", Context.MODE_PRIVATE) }
        // DAC | EQ | LIBRARY while the DAC has a settings list, EQ | LIBRARY otherwise. Pages are logical ids ([Page]); the pager keys its
        // pages by id, so a DAC page appearing or going away moves no one: EQ stays EQ.
        val hasDac = device.dacTarget != null
        val pages = remember(hasDac) { if (hasDac) listOf(Page.DAC, Page.TUNE, Page.LIBRARY) else listOf(Page.TUNE, Page.LIBRARY) }
        val pagesNow = rememberUpdatedState(pages)
        val pager = rememberPagerState(initialPage = pages.indexOf(initialPage).coerceAtLeast(0)) { pagesNow.value.size }
        val logicalPage = { index: Int -> pagesNow.value.getOrNull(index) }
        val scope = rememberCoroutineScope()
        var sheet by remember { mutableStateOf<Sheet?>(null) }
        var service by remember { mutableStateOf(false) }
        var licences by remember { mutableStateOf(false) }
        var confirmSync by remember { mutableStateOf(false) }
        var archiveOpen by remember { mutableStateOf(prefs.getBoolean("archiveOpen", false)) }
        // REORDER PROFILES (owner 16.11.2026): the library's cards come apart, the page bar becomes DONE, the pager stands still
        var reorder by remember { mutableStateOf(false) }
        val snackbar = remember { SnackbarHostState() }
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val bottom = nav + BAR_EDGE + BAR_H + Grid.GAP

        LaunchedEffect(sheetRequest) {
            if (sheetRequest != null) {
                sender.leaveAb { sheet = sheetRequest }
                onSheetRequestTaken()
            }
        }
        // Page requests from the model (tap on a row, a new profile) and the review hooks. Clearing the request
        // must not cancel the slide, so the slide runs in its own coroutine.
        LaunchedEffect(pager) {
            snapshotFlow { model.pageRequest }.filterNotNull().collect { p ->
                model.pageRequest = null
                sender.leaveAb { scope.launch { pagesNow.value.indexOf(p).takeIf { it >= 0 }?.let { pager.animateScrollToPage(it) } } }
            }
        }
        // Page swipes (owner 14.11.2026, haptics round 5): while a finger drags, the start TICK when the page the pager would settle on moves away from the
        // settled one and a soft one when it comes back; when the pager settles after a drag or a tap on the bottom bar, the end CLICK of the pair (the same
        // for every control). By logical page: a DAC page appearing shifts the positions, not the page.
        LaunchedEffect(pager) {
            var dragging = false
            launch {
                pager.interactionSource.interactions.collect { i ->
                    dragging = when (i) {
                        is DragInteraction.Start -> true
                        is DragInteraction.Stop, is DragInteraction.Cancel -> false
                        else -> dragging
                    }
                }
            }
            launch {
                snapshotFlow { logicalPage(pager.targetPage) != logicalPage(pager.settledPage) }.drop(1).collect { away -> if (dragging) { if (away) haptics.segStart() else haptics.threshold(false) } }
            }
            // The end plays when the page visually arrives (owner 14.11.2026: the end at the spring's full stop, ~400 ms after the press, was late): the first
            // frame in which the pager shows its target page within 3 % of a page of rest, or when it settles, whichever comes first (segEnd disarms, so once).
            // Only a page a drag or a tab press started is felt; a page the model asks for (a tapped row, a new profile) settles in silence.
            launch {
                var shown = logicalPage(pager.currentPage)
                snapshotFlow {
                    if (pager.currentPage == pager.targetPage && abs(pager.currentPageOffsetFraction) < PAGE_ARRIVED) logicalPage(pager.currentPage) else null
                }.collect { arrived ->
                    if (arrived != null && arrived != shown) {
                        shown = arrived
                        if (haptics.segArmed()) haptics.segEnd()
                    }
                }
            }
            snapshotFlow { logicalPage(pager.settledPage) }.drop(1).collect { if (haptics.segArmed()) haptics.segEnd() }
        }
        // The DAC Settings page reads the DAC when it settles with a DAC connected, and again after a (re)connect while it is open.
        val onDacPage = logicalPage(pager.settledPage) == Page.DAC
        LaunchedEffect(onDacPage, device.link, device.connectReads, hasDac) {
            if (onDacPage && !device.dacIsPreview && device.link == Link.CONNECTED) device.readSettings()
        }
        // Swipes have no page-button callback: restore as soon as the pager targets any page but EQ (Library, and the DAC page: no DAC setting is written
        // while B plays, since a setting write that ends with a flash save could store B).
        LaunchedEffect(pager) {
            snapshotFlow { logicalPage(pager.targetPage) }.collect { if (it != Page.TUNE) sender.leaveAb() }
        }
        // Also covers model/review-hook changes that did not originate in a Tune control. Collected, not read here: the current profile changes on every
        // step of a drag, and a read in this scope would recompose the whole shell (pager, both pages' parameters, the page bar) each time.
        LaunchedEffect(model, sender) {
            snapshotFlow { model.current }.collect { p ->
                if (p == null || !sender.canAb(p)) sender.leaveAb()
            }
        }

        fun undoable(text: UiText, onUndo: () -> Unit, onGone: () -> Unit) {
            snackbar.currentSnackbarData?.dismiss()
            scope.launch {
                val v = UndoVisuals(text.resolve(context), context.getString(R.string.action_undo))
                val timer = launch {
                    while (snackbar.currentSnackbarData?.visuals !== v) delay(50)
                    delay(5000)
                    if (snackbar.currentSnackbarData?.visuals === v) snackbar.currentSnackbarData?.dismiss()
                }
                val r = snackbar.showSnackbar(v)
                timer.cancel()
                if (r == SnackbarResult.ActionPerformed) onUndo() else onGone()
            }
        }

        fun toast(text: UiText) {
            snackbar.currentSnackbarData?.dismiss()
            scope.launch { snackbar.showSnackbar(text.resolve(context), duration = SnackbarDuration.Short) }
        }

        // Library RESTORE / SAVE: nothing to do says so; a change can be taken back with UNDO. True when something changed.
        fun sentChange(r: AppModel.SentChange, done: UiText): Boolean = when (r) {
            AppModel.SentChange.NoCheckpoint -> { toast(UiText.res(R.string.library_no_saved_version)); false }
            AppModel.SentChange.AlreadyThere -> { toast(UiText.res(R.string.library_already_saved)); false }
            is AppModel.SentChange.Changed -> { undoable(done, onUndo = r.undo, onGone = {}); true }
        }

        // What an import from a file did (the one-line report): shown once, in its own coroutine so that clearing the
        // notice does not cancel the snackbar.
        val notice = model.notice
        LaunchedEffect(notice) {
            if (notice != null) {
                model.notice = null
                snackbar.currentSnackbarData?.dismiss()
                // A bare "IMPORTED n FILTERS" stays 4 s instead of 10; a line with an import report has to be readable, so it keeps the long time ([Notice.long]).
                scope.launch { snackbar.showSnackbar(notice.text.resolve(context), duration = if (notice.long) SnackbarDuration.Long else SnackbarDuration.Short) }
            }
        }

        val recentlyDeleted = model.recentlyDeleted
        LaunchedEffect(recentlyDeleted?.id) {
            if (recentlyDeleted != null) undoable(UiText.res(R.string.library_deleted, recentlyDeleted.name),
                onUndo = { model.undoDelete(recentlyDeleted.id) },
                onGone = { model.finishDelete(recentlyDeleted.id) })
        }
        val libraryActions = remember(model) {
            object : LibraryActions {
                override fun edit(id: String) { sender.leaveAb { sheet = Sheet.Edit(id) } }
                override fun newProfile() { sender.leaveAb { sheet = Sheet.New } }
                override fun syncAll() {
                    if (!model.loading && !model.loadError && !model.deleting && model.profiles.isNotEmpty()) {
                        confirmSync = true
                    }
                }
                override fun archive(p: Profile) {
                    model.setArchived(p.id, true)
                    undoable(UiText.res(R.string.library_archived, p.name), onUndo = { model.setArchived(p.id, false) }, onGone = {})
                }
                override fun restore(p: Profile) { model.setArchived(p.id, false) }
                override fun restoreSent(p: Profile): Boolean = sentChange(model.restoreSent(p.id), UiText.res(R.string.library_restored_to_saved))
                override fun overwriteSent(p: Profile): Boolean = sentChange(model.overwriteSent(p.id), UiText.res(R.string.library_saved))
                override fun delete(p: Profile) {
                    model.delete(p.id)
                }
                override fun service() { if (Proven.channel == BuildChannel.PERF) sender.leaveAb { service = true } } // the service screen is a review tool: a release has no long-press
                override fun licences() { sender.leaveAb { licences = true } }
                // BACKUP never touches the DAC (no leaveAb: it may write to it); it only asks the system for a file
                override fun backup() { onBackup() }
            }
        }
        val tuneActions = remember(model) {
            object : TuneActions {
                override fun edit(id: String) { sender.leaveAb { sheet = Sheet.Edit(id) } }
                override fun value(param: Param) { sender.leaveAb { sheet = Sheet.Value(param) } }
                override fun band(index: Int) { sender.leaveAb { sheet = Sheet.BandActions(index) } }
                override fun sendDetails() { sheet = Sheet.SendFailure(sender.failure) }
                override fun service() { if (Proven.channel == BuildChannel.PERF) sender.leaveAb { service = true } } // the service screen is a review tool: a release has no long-press
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(pal.bg)
                .semantics { testTagsAsResourceId = true },
        ) {
            HorizontalPager(
                state = pager,
                beyondViewportPageCount = 1,
                // a finger on a slider, PREAMP or the graph never turns the page
                userScrollEnabled = !pagerLock.locked && !reorder,
                modifier = Modifier.fillMaxSize(),
                key = { pagesNow.value.getOrNull(it) ?: (-1 - it) },
            ) { page ->
                val logical = pagesNow.value.getOrNull(page)
                if (logical == Page.DAC) {
                    Box(Modifier.fillMaxSize().testTag("page_dac")) {
                        DacScreen(device, top, bottom, onNotSent = ::toast)
                    }
                } else if (logical == Page.TUNE) {
                    Box(Modifier.fillMaxSize().testTag("page_tune")) {
                        TuneScreen(model, device, sender, editor, tuneActions, top, bottom)
                    }
                } else {
                    Box(Modifier.fillMaxSize().testTag("page_library")) {
                        LibraryScreen(
                            model, device, sender, libraryActions,
                            archiveOpen = archiveOpen,
                            onArchiveOpen = { archiveOpen = it; prefs.edit().putBoolean("archiveOpen", it).apply() },
                            top = top, bottom = bottom,
                            visible = pager.settledPage == page,
                            reorder = reorder,
                            onReorder = { reorder = it },
                        )
                    }
                }
            }
            // A page scrolled up goes under the status bar, not through it (25.11.2026: LIBRARY and the connect chip ran under the clock and the icons):
            // the page colour behind the status bar only (a fade under it darkened the top edge of CLEAR EQ on the Pixel).
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(top).background(pal.bg))
            // REORDER: the page bar slides down and fades, a full-width DONE takes its place (and back to the page bar on DONE)
            val doneShown by animateFloatAsState(if (reorder) 1f else 0f, tween(220, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)), label = "done-bar")
            // Gone once DONE has taken its place, and out of reach while REORDER is on: faded and moved, it still took taps at the very bottom and TalkBack
            // still focused its DAC / EQ / LIBRARY under DONE (review 18.11.2026).
            if (doneShown < 1f) PageBar(
                pager, pages,
                onPage = { page -> if (!reorder) sender.leaveAb { scope.launch { pagesNow.value.indexOf(page).takeIf { it >= 0 }?.let { pager.animateScrollToPage(it) } } } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = nav + BAR_EDGE).graphicsLayer {
                    alpha = 1f - doneShown
                    translationY = 80.dp.toPx() * doneShown
                }.then(if (reorder) Modifier.clearAndSetSemantics {} else Modifier),
            )
            if (doneShown > 0f) DoneBar(
                onDone = { reorder = false },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = nav + BAR_EDGE).graphicsLayer { alpha = doneShown },
            )
            // Every snackbar sits at the top, right under the header row of the page (all three headers are Grid.ROW tall, from the status bar down), over the top edge of the
            // content and never over the header controls (owner 25.10.2026: at the bottom it covered HOLD TO SEND).
            SnackbarHost(
                snackbar,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = top + Grid.ROW),
            ) { data ->
                val shape = RoundedCornerShape(Radii.L)
                val actionLabel = data.visuals.actionLabel
                Snackbar(
                    modifier = Modifier.padding(horizontal = Grid.SIDE).lift(shape, Lift.HERO),
                    shape = shape,
                    containerColor = pal.surface2,
                    contentColor = pal.text,
                    action = if (actionLabel == null) null else { {
                        TextButton(onClick = { data.performAction() }, modifier = Modifier.testTag("undo")) {
                            Text(actionLabel, style = Type.segment, color = pal.accent)
                        }
                    } },
                ) { Text(data.visuals.message, style = Type.label, modifier = Modifier.testTag("snackbar_text")) }
            }
            if (model.saveError || model.deleting) {
                Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = top + Grid.GAP, start = Grid.SIDE, end = Grid.SIDE)
                        .background(pal.surface2, RoundedCornerShape(Radii.L)).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(if (model.saveError) R.string.app_not_saved else R.string.app_saving_delete), style = Type.label, modifier = Modifier.weight(1f))
                    if (model.saveError) TextButton(onClick = { model.retrySave() }) { Text(stringResource(R.string.action_retry), style = Type.segment, color = pal.accent) }
                }
            }
            if (service) DebugScreen(device) { service = false }
            if (licences) LicencesScreen { licences = false }
        }

        if (confirmSync) {
            AlertDialog(
                onDismissRequest = { confirmSync = false },
                shape = RoundedCornerShape(Radii.L),
                containerColor = pal.surface2,
                titleContentColor = pal.text,
                textContentColor = pal.textDim,
                title = { Text(stringResource(R.string.library_save_all_title), style = Type.rowName) },
                text = {
                    Text(stringResource(R.string.library_save_all_text))
                },
                confirmButton = {
                    TextButton(
                        enabled = !model.loading && !model.loadError && !model.deleting && model.profiles.isNotEmpty(),
                        modifier = Modifier.testTag("sync_all_confirm"),
                        onClick = {
                            confirmSync = false
                            val count = model.saveAllAsSent()
                            if (count > 0) {
                                snackbar.currentSnackbarData?.dismiss()
                                scope.launch {
                                    snackbar.showSnackbar(context.resources.getQuantityString(R.plurals.library_profiles_saved, count, count), duration = SnackbarDuration.Short)
                                }
                            }
                        },
                    ) { Text(stringResource(R.string.library_save_all), style = Type.segment, color = pal.accent) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmSync = false }, modifier = Modifier.testTag("sync_all_cancel")) {
                        Text(stringResource(R.string.action_cancel), style = Type.segment, color = pal.textDim)
                    }
                },
                modifier = Modifier.testTag("sync_all_dialog"),
            )
        }

        if (incoming != null) IncomingDialog(model, incoming) { import ->
            onIncomingDone()
            if (import) sender.leaveAb { ImportFile.create(model, incoming) }
        }

        val close = { sheet = null }
        when (val s = sheet) {
            is Sheet.Edit -> EditSheet(model, s.id, close, onSaveTxt)
            Sheet.New -> NewProfileSheet(model, device, editor, close, onRestoreBackup = { close(); onRestoreBackup() })
            is Sheet.Value -> ValueSheet(model, s.param, close)
            is Sheet.BandActions -> BandSheet(model, s.index, close)
            is Sheet.SendFailure -> SendFailureSheet(s.reason, close)
            null -> Unit
        }
        // The factory reset sheet is outside the DAC SETTINGS page: the DAC leaves the bus after the reset and the page goes with it.
        if (device.resetUi != ResetUi.Idle) FactoryResetSheet(device)

        // Back while the cards are apart glues them back (as DONE); leaving the library page ends REORDER too.
        BackHandler(enabled = reorder && sheet == null && !service && !licences) { haptics.segTap(); reorder = false }
        LaunchedEffect(pager) {
            snapshotFlow { logicalPage(pager.settledPage) }.collect { if (it != Page.LIBRARY) reorder = false }
        }
        // Back from EQ goes to the Library (as before); back from DAC Settings goes to EQ.
        val backFrom = logicalPage(pager.currentPage)
        BackHandler(enabled = sheet == null && !service && !licences && (backFrom == Page.TUNE || backFrom == Page.DAC)) {
            val to = if (backFrom == Page.DAC) Page.TUNE else Page.LIBRARY
            sender.leaveAb { scope.launch { pagesNow.value.indexOf(to).takeIf { it >= 0 }?.let { pager.animateScrollToPage(it) } } }
        }
    }
}

/** IMPORT? for a .txt opened with or shared to Contour: what it holds for the device in use, then IMPORT / CANCEL. */
@Composable
private fun IncomingDialog(model: AppModel, incoming: Incoming, onAnswer: (import: Boolean) -> Unit) {
    val raw = incoming.eq
    val (eq, report) = if (raw != null) Importer.fit(raw, model.importTarget) else null to null
    val ok = eq != null && eq.bands.isNotEmpty()
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        shape = RoundedCornerShape(Radii.L),
        containerColor = pal.surface2,
        titleContentColor = pal.text,
        textContentColor = pal.textDim,
        title = { Text(stringResource(if (ok) R.string.incoming_title else R.string.incoming_title_cannot), style = Type.rowName) },
        text = {
            Text(
                if (eq != null && ok) {
                    val n = eq.bands.size
                    val name = incoming.name ?: stringResource(R.string.incoming_default_name)
                    if (report == null) pluralStringResource(R.plurals.incoming_summary, n, name, n)
                    else pluralStringResource(R.plurals.incoming_summary_report, n, name, n, report.asString())
                } else (incoming.error ?: report ?: ImportFile.NO_FILTERS).asString(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(ok) }, modifier = Modifier.testTag("import_confirm")) {
                Text(stringResource(if (ok) R.string.incoming_import else R.string.action_ok), style = Type.segment, color = pal.accent)
            }
        },
        dismissButton = if (ok) { {
            TextButton(onClick = { onAnswer(false) }, modifier = Modifier.testTag("import_cancel")) {
                Text(stringResource(R.string.action_cancel), style = Type.segment, color = pal.textDim)
            }
        } } else null,
        modifier = Modifier.testTag("import_dialog"),
    )
}

/**
 * The page switch: a pressed-in block (full width, [BAR_H] tall) with a raised orange part that follows the pager position
 * continuously, under equal labelled parts - EQ (Tune) and LIBRARY, and DAC (settings) left of them while the DAC has settings.
 * Tapping a part goes there. [pages] are the logical ids ([Page]) in pager order.
 */
@Composable
private fun PageBar(pager: PagerState, pages: List<Int>, onPage: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = pal
    val haptics = LocalHaptics.current
    val n = pages.size
    val name = stringResource(when (pages.getOrNull(pager.settledPage)) { Page.DAC -> R.string.page_description_dac; Page.TUNE -> R.string.page_description_tune; else -> R.string.page_description_library })
    val track = RoundedCornerShape(Radii.L)
    val knob = RoundedCornerShape(Radii.M)
    val pos = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, (n - 1).toFloat())
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Grid.SIDE)
            .height(BAR_H)
            .clip(track)
            .background(c.track)
            .sink(track)
            .padding(Grid.INSET)
            .semantics { contentDescription = name }
            .testTag("page_bar"),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .layout { m, cs ->
                    val part = cs.maxWidth / n
                    val pl = m.measure(Constraints.fixed(part, cs.maxHeight))
                    layout(cs.maxWidth, cs.maxHeight) {
                        val p = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, (n - 1).toFloat())
                        // relative: on a right-to-left phone the pager and the labels are mirrored (page 0 on the right), so is the knob
                        pl.placeRelative((p * part).roundToInt(), 0)
                    }
                }
                .lift(knob, Lift.RAISED)
                .background(c.accent, knob),
        )
        Row(Modifier.fillMaxSize()) {
            val none = remember { MutableInteractionSource() }
            pages.forEachIndexed { i, page ->
                val (label, icon, tag) = when (page) {
                    Page.DAC -> Triple(stringResource(R.string.page_dac), Icons.Outlined.Tune, "page_bar_dac")
                    Page.TUNE -> Triple(stringResource(R.string.page_eq), Icons.Outlined.GraphicEq, "page_bar_tune")
                    else -> Triple(stringResource(R.string.page_library), Icons.Outlined.LibraryMusic, "page_bar_library")
                }
                // on the knob (onAccent) when the pager is on its page, dim (textDim) a whole page away
                PageHalf(
                    label, icon, lerp(c.onAccent, c.textDim, abs(pos - i).coerceIn(0f, 1f)),
                    Modifier.weight(1f).pressStart { if (pager.targetPage != i) haptics.segStart() }.clickable(none, null) { onPage(page) }.testTag(tag),
                )
            }
        }
    }
}

@Composable
private fun PageHalf(label: String, icon: ImageVector, color: Color, modifier: Modifier) {
    Row(modifier.fillMaxHeight(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = Type.button, color = color, maxLines = 1)
    }
}

/** DONE in place of the page bar while the library's cards are apart: the same size and place, the orange of HOLD TO SEND, the button type. */
@Composable
private fun DoneBar(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val c = pal
    val haptics = LocalHaptics.current
    val shape = RoundedCornerShape(Radii.L)
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Grid.SIDE)
            .height(BAR_H)
            .lift(shape, Lift.RAISED)
            .clip(shape)
            .background(c.accent)
            .clickable(role = androidx.compose.ui.semantics.Role.Button) { haptics.segTap(); onDone() }
            .testTag("reorder_done"),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.action_done), style = Type.button, color = c.onAccent, maxLines = 1)
    }
}

/** The pager has visually arrived at a page when it shows it within this fraction of a page of rest (the end CLICK of a page change plays then). */
private const val PAGE_ARRIVED = 0.03f
