package io.github.chronosauros.contour.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.LibraryMusic
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
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.model.AppModel
import io.github.chronosauros.contour.model.Page
import io.github.chronosauros.contour.model.Sender
import io.github.chronosauros.contour.ui.kit.LocalHaptics
import io.github.chronosauros.contour.ui.kit.rememberHaptics
import io.github.chronosauros.contour.ui.library.LibraryActions
import io.github.chronosauros.contour.ui.library.LibraryScreen
import io.github.chronosauros.contour.ui.sheets.BandSheet
import io.github.chronosauros.contour.ui.sheets.EditSheet
import io.github.chronosauros.contour.ui.sheets.NewProfileSheet
import io.github.chronosauros.contour.ui.sheets.SendFailureSheet
import io.github.chronosauros.contour.ui.sheets.Sheet
import io.github.chronosauros.contour.ui.sheets.ValueSheet
import io.github.chronosauros.contour.ui.tune.Param
import io.github.chronosauros.contour.ui.tune.TuneActions
import io.github.chronosauros.contour.ui.tune.TuneScreen
import io.github.chronosauros.contour.usb.DeviceController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

private val BAR_TOUCH = 60.dp
private val BAR_GAP = 12.dp

private class UndoVisuals(override val message: String) : SnackbarVisuals {
    override val actionLabel = "UNDO"
    override val withDismissAction = false
    override val duration = SnackbarDuration.Indefinite
}

/**
 * The shell: Tune (left) and Library (right) side by side in a pager, the page bar at the bottom, sheets,
 * the undo snackbar and the service screen. [sheetRequest] comes from the review hooks.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ContourApp(
    model: AppModel,
    device: DeviceController,
    sender: Sender,
    initialPage: Int,
    sheetRequest: Sheet?,
    onSheetRequestTaken: () -> Unit,
) {
    if (model.loading || model.loadError) {
        Box(Modifier.fillMaxSize().background(pal.bg).padding(24.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (model.loadError) {
                    Text("LIBRARY COULD NOT BE READ. YOUR FILES WERE NOT CHANGED.", color = pal.text)
                    Button(onClick = { model.load() }) { Text("RETRY LOADING") }
                } else Text("LOADING LIBRARY…", color = pal.text)
            }
        }
        return
    }
    val haptics = rememberHaptics()
    // LocalContentColor: every Text without an explicit color follows the theme (dark mode drew them black)
    CompositionLocalProvider(LocalHaptics provides haptics, LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        val context = LocalContext.current
        val prefs = remember { context.getSharedPreferences("ui", Context.MODE_PRIVATE) }
        val pager = rememberPagerState(initialPage = initialPage) { 2 }
        val scope = rememberCoroutineScope()
        var sheet by remember { mutableStateOf<Sheet?>(null) }
        var service by remember { mutableStateOf(false) }
        var licences by remember { mutableStateOf(false) }
        var archiveOpen by remember { mutableStateOf(prefs.getBoolean("archiveOpen", false)) }
        val snackbar = remember { SnackbarHostState() }
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val bottom = nav + BAR_GAP + BAR_TOUCH + BAR_GAP

        LaunchedEffect(sheetRequest) {
            if (sheetRequest != null) {
                sheet = sheetRequest
                onSheetRequestTaken()
            }
        }
        // Page requests from the model (tap on a row, a new profile) and the review hooks. Clearing the request
        // must not cancel the slide, so the slide runs in its own coroutine.
        LaunchedEffect(pager) {
            snapshotFlow { model.pageRequest }.filterNotNull().collect { p ->
                model.pageRequest = null
                launch { pager.animateScrollToPage(p) }
            }
        }
        LaunchedEffect(pager) {
            snapshotFlow { pager.settledPage }.drop(1).collect { haptics.step() }
        }

        fun undoable(text: String, onUndo: () -> Unit, onGone: () -> Unit) {
            snackbar.currentSnackbarData?.dismiss()
            scope.launch {
                val v = UndoVisuals(text)
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

        val recentlyDeleted = model.recentlyDeleted
        LaunchedEffect(recentlyDeleted?.id) {
            if (recentlyDeleted != null) undoable("DELETED ${recentlyDeleted.name}",
                onUndo = { model.undoDelete(recentlyDeleted.id) },
                onGone = { model.finishDelete(recentlyDeleted.id) })
        }
        val libraryActions = remember(model) {
            object : LibraryActions {
                override fun edit(id: String) { sheet = Sheet.Edit(id) }
                override fun newProfile() { sheet = Sheet.New }
                override fun archive(p: Profile) {
                    model.setArchived(p.id, true)
                    undoable("ARCHIVED ${p.name}", onUndo = { model.setArchived(p.id, false) }, onGone = {})
                }
                override fun restore(p: Profile) { model.setArchived(p.id, false) }
                override fun delete(p: Profile) {
                    model.delete(p.id)
                }
                override fun service() { service = true }
                override fun licences() { licences = true }
            }
        }
        val tuneActions = remember(model) {
            object : TuneActions {
                override fun edit(id: String) { sheet = Sheet.Edit(id) }
                override fun value(param: Param) { sheet = Sheet.Value(param) }
                override fun band(index: Int) { sheet = Sheet.BandActions(index) }
                override fun sendDetails() { sheet = Sheet.SendFailure(sender.failure) }
                override fun service() { service = true }
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
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                if (page == Page.TUNE) {
                    Box(Modifier.fillMaxSize().testTag("page_tune")) {
                        TuneScreen(model, device, sender, tuneActions, top, bottom)
                    }
                } else {
                    Box(Modifier.fillMaxSize().testTag("page_library")) {
                        LibraryScreen(
                            model, device, sender, libraryActions,
                            archiveOpen = archiveOpen,
                            onArchiveOpen = { archiveOpen = it; prefs.edit().putBoolean("archiveOpen", it).apply() },
                            top = top, bottom = bottom,
                        )
                    }
                }
            }
            PageBar(
                pager,
                onPage = { scope.launch { pager.animateScrollToPage(it) } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = nav + BAR_GAP),
            )
            SnackbarHost(
                snackbar,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottom + 8.dp),
            ) { data ->
                val shape = RoundedCornerShape(18.dp)
                Snackbar(
                    modifier = Modifier.padding(horizontal = 14.dp).lift(shape, Lift.HERO),
                    shape = shape,
                    containerColor = pal.surface2,
                    contentColor = pal.text,
                    action = {
                        TextButton(onClick = { data.performAction() }, modifier = Modifier.testTag("undo")) {
                            Text(data.visuals.actionLabel ?: "UNDO", style = Type.label, color = pal.accent)
                        }
                    },
                ) { Text(data.visuals.message, style = Type.label, modifier = Modifier.testTag("snackbar_text")) }
            }
            if (model.saveError || model.deleting) {
                Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = top + 8.dp, start = 16.dp, end = 16.dp)
                        .background(pal.surface2, RoundedCornerShape(12.dp)).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (model.saveError) "NOT SAVED — CHECK STORAGE" else "SAVING DELETE…", style = Type.label, modifier = Modifier.weight(1f))
                    if (model.saveError) TextButton(onClick = { model.retrySave() }) { Text("RETRY") }
                }
            }
            if (service) DebugScreen(device) { service = false }
            if (licences) LicencesScreen { licences = false }
        }

        val close = { sheet = null }
        when (val s = sheet) {
            is Sheet.Edit -> EditSheet(model, s.id, close)
            Sheet.New -> NewProfileSheet(model, device, close)
            is Sheet.Value -> ValueSheet(model, s.param, close)
            is Sheet.BandActions -> BandSheet(model, s.index, close)
            is Sheet.SendFailure -> SendFailureSheet(s.reason, close)
            null -> Unit
        }

        BackHandler(enabled = sheet == null && !service && !licences && pager.currentPage == Page.TUNE) {
            scope.launch { pager.animateScrollToPage(Page.LIBRARY) }
        }
    }
}

/**
 * The page switch: a pressed-in block (full width, [BAR_TOUCH] tall) with a raised orange half that follows the
 * pager position continuously, under two labelled halves - EQ (Tune) and LIBRARY. Tapping a half goes there.
 */
@Composable
private fun PageBar(pager: PagerState, onPage: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = pal
    val name = if (pager.settledPage == Page.TUNE) "page tune" else "page library"
    val track = RoundedCornerShape(20.dp)
    val knob = RoundedCornerShape(16.dp)
    val pos = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(BAR_TOUCH)
            .clip(track)
            .background(c.track)
            .sink(track)
            .padding(4.dp)
            .semantics { contentDescription = name }
            .testTag("page_bar"),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .layout { m, cs ->
                    val half = cs.maxWidth / 2
                    val pl = m.measure(Constraints.fixed(half, cs.maxHeight))
                    layout(cs.maxWidth, cs.maxHeight) {
                        val p = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                        pl.place((p * half).roundToInt(), 0)
                    }
                }
                .lift(knob, Lift.RAISED)
                .background(c.accent, knob),
        )
        Row(Modifier.fillMaxSize()) {
            val none = remember { MutableInteractionSource() }
            PageHalf(
                "EQ", Icons.Outlined.GraphicEq, lerp(c.onAccent, c.textDim, pos),
                Modifier.weight(1f).clickable(none, null) { onPage(Page.TUNE) }.testTag("page_bar_tune"),
            )
            PageHalf(
                "LIBRARY", Icons.Outlined.LibraryMusic, lerp(c.textDim, c.onAccent, pos),
                Modifier.weight(1f).clickable(none, null) { onPage(Page.LIBRARY) }.testTag("page_bar_library"),
            )
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
