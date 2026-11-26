package io.github.chronosauros.contour.ui.library

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.withFrameMillis
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.chronosauros.contour.BuildConfig
import io.github.chronosauros.contour.R
import io.github.chronosauros.contour.core.Band
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.core.ShelfForm
import io.github.chronosauros.contour.model.AppModel
import io.github.chronosauros.contour.model.Sender
import io.github.chronosauros.contour.ui.DeviceStatus
import io.github.chronosauros.contour.ui.Grid
import io.github.chronosauros.contour.ui.HapticsRow
import io.github.chronosauros.contour.ui.rememberOptionWidth
import io.github.chronosauros.contour.ui.Lift
import io.github.chronosauros.contour.ui.Radii
import io.github.chronosauros.contour.ui.ResponseThumb
import io.github.chronosauros.contour.ui.Type
import io.github.chronosauros.contour.ui.UsbAttachRow
import io.github.chronosauros.contour.ui.kit.LocalHaptics
import io.github.chronosauros.contour.ui.kit.ProfileIcons
import io.github.chronosauros.contour.ui.kit.SwipeFeel
import io.github.chronosauros.contour.ui.kit.layoutSign
import io.github.chronosauros.contour.ui.lift
import io.github.chronosauros.contour.ui.pal
import io.github.chronosauros.contour.ui.sink
import io.github.chronosauros.contour.usb.DeviceController
import io.github.chronosauros.contour.usb.Link
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** What the Library asks of the app shell (sheets, snackbar, service screen). */
interface LibraryActions {
    fun edit(id: String)
    fun newProfile()
    fun syncAll()
    fun archive(p: Profile)
    fun restore(p: Profile)
    /** RESTORE: the profile's EQ (the working copy) back to its SAVED version, from any row. True when it changed something (UNDO is offered). */
    fun restoreSent(p: Profile): Boolean
    /** SAVE: the profile's EQ as it is now becomes its SAVED version. True when it changed something (UNDO is offered). */
    fun overwriteSent(p: Profile): Boolean
    fun delete(p: Profile)
    fun service()
    fun licences()
}

/** A row of the index: one quiet block of 64 dp rows, the lines between them across its full width (owner 25.11.2026). */
private val ROW_H = 64.dp
private val ACTION_WIDTH = 96.dp
/** The pills behind a row stand this far inside its top and bottom edge. */
private val PILL_INSET = 6.dp
/** The icon column of a row. */
private val ICON_COL = 40.dp
private val ROW_PAD = 16.dp
private const val NEW_SLOT_ID = "library-new-slot"
/** The index of SOFTER VOCALS among the first-run profiles ([io.github.chronosauros.contour.model.ProfileNames.seeds]) and the name it had before 1.6.1: the profile that hints the swipe. */
private const val SEED_SOFTER_VOCALS = 1
private const val LEGACY_HINT_NAME = "DUSK"
private val SIDE = Grid.SIDE
private val BLOCK = RoundedCornerShape(Radii.L)
private val PILL = RoundedCornerShape(Radii.L)
/** How far past a settled-open strip a drag must go before it fires the strip's first action. */
private val FULL_MARGIN = 24.dp
/** The name of a row: the row name role a step smaller (17 sp), as in the index of the prototype. */
private val ROW_NAME = Type.rowName.copy(fontSize = 17.sp)

/**
 * Library: choosing and managing profiles only - it never sends anything to the DAC.
 * Variant D (owner 16.11.2026, `_Projects/contour-refs/library-redesign-2026-11-16/`): LIBRARY and the connect chip on top, under them while a DAC is
 * connected its name and the profile on it; the active profiles as one block of 64 dp rows with hairlines across the full width (the current one a step
 * lighter, ON DAC an accent line under the name, an accent dot after the name for unsaved changes), the empty slot as the last row of the block, the
 * collapsible archive and the bottom: REORDER PROFILES (a grey button, as the empty slot has), the two settings as rows of the DAC pages' kind, and ABOUT.
 * Tap = current + Tune, long-press = edit sheet, swipe left = ARCHIVE / DELETE (UNARCHIVE / DELETE in the archive), swipe right = SAVE / RESTORE the
 * SAVED version (active rows only), the empty slot = new profile (swipe left: SAVE ALL). A row draws its SAVED version, whatever the EQ screen's
 * working copy holds.
 */
@Composable
fun LibraryScreen(
    model: AppModel,
    device: DeviceController,
    sender: Sender,
    actions: LibraryActions,
    archiveOpen: Boolean,
    onArchiveOpen: (Boolean) -> Unit,
    top: Dp,
    bottom: Dp,
    visible: Boolean = true,
    /** REORDER PROFILES is on (the shell keeps it: the page bar is DONE meanwhile and the pager stands still). */
    reorder: Boolean = false,
    onReorder: (Boolean) -> Unit = {},
) {
    val c = pal
    val listState = rememberLazyListState()
    var openId by remember { mutableStateOf<String?>(null) }
    var openSide by remember { mutableStateOf(Side.RIGHT) }
    // The shipped SOFTER VOCALS profile (DUSK before 1.6.1) hints the swipe until someone swipes a row once - remembered
    // for good (app data survives updates), so the hint never returns, whatever happens to that profile.
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE) }
    var swiped by remember { mutableStateOf(prefs.getBoolean("swipeHintDone", false)) }
    var nudge by remember { mutableIntStateOf(0) }
    // the one width of an option of every well of the bottom (the OFF | ON well and the HAPTIC FEEDBACK menu are both two of them)
    val optionW = rememberOptionWidth()
    val hintId = if (swiped) null else model.active.firstOrNull { it.name == model.names.seeds[SEED_SOFTER_VOCALS] || it.name == LEGACY_HINT_NAME }?.id
    // not while the cards are apart in REORDER (the hop and the strip behind it belong to the glued block; review 18.11.2026)
    val reorderNow = rememberUpdatedState(reorder)
    LaunchedEffect(visible, hintId) {
        if (!visible || hintId == null) return@LaunchedEffect
        delay(900)
        repeat(3) {
            if (openId == null && !reorderNow.value && !listState.isScrollInProgress) nudge++
            delay(6000)
        }
    }
    val markSwiped = {
        if (!swiped) {
            swiped = true
            prefs.edit().putBoolean("swipeHintDone", true).apply()
        }
    }
    // bounds in window coordinates, as plain values (no LayoutCoordinates kept across item reuse)
    val rowCoords = remember { HashMap<String, Rect>() }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    val openIdState = rememberUpdatedState(openId)

    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) openId = null }

    val active = model.active
    val archived = model.archived
    val onDac = sender.savedOnDacIds
    val archBg = lerp(c.bg, c.surface, 0.7f)

    // REORDER PROFILES: the block comes apart into cards; a card held 150 ms follows the finger ([Reorder])
    val haptics = LocalHaptics.current
    val density = LocalDensity.current
    val view = LocalView.current
    val reorderCtl = remember(model) { Reorder { id, to -> model.moveActive(id, to) } }
    // a card dropped just before the app went to the background (or the library left the screen) is saved in its new place now, not when frames come back
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { reorderCtl.landNow() }
    DisposableEffect(reorderCtl) { onDispose { reorderCtl.landNow() } }
    reorderCtl.density = density.density
    reorderCtl.haptics = haptics
    if (!reorderCtl.busy) reorderCtl.ids = active.map { it.id }
    var rootBottom by remember { mutableStateOf(0f) }
    val topPx = with(density) { top.toPx() }
    val bottomPx = with(density) { bottom.toPx() }
    LaunchedEffect(reorder) {
        if (reorder) {
            openId = null
            reorderCtl.calm = android.provider.Settings.Global.getFloat(view.context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
            // the one haptic of entering is the REORDER PROFILES button's own; leaving: DONE and back each play theirs (the shell)
            reorderCtl.enter()
            launch { listState.animateScrollToItem(0) }
        } else {
            reorderCtl.leave()
        }
        while (reorderCtl.running) {
            withFrameMillis { t -> reorderCtl.tick(t, { listState.dispatchRawDelta(it) }, rootOrigin.y + topPx, rootBottom - bottomPx) }
        }
    }
    val blockGlue by animateFloatAsState(if (reorder) 0f else 1f, tween(280, easing = Reorder.SEPARATE), label = "block")
    val slotOpen by animateFloatAsState(if (reorder) 0f else 1f, tween(220, easing = Reorder.SEPARATE), label = "slot")

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootOrigin = it.positionInWindow(); rootBottom = rootOrigin.y + it.size.height }
            // REORDER: a card held still for 150 ms comes up and follows the finger; moving sooner leaves the touch to the list (a flick scrolls)
            .pointerInput(reorder) {
                if (!reorder) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val w = down.position + rootOrigin
                    val id = reorderCtl.cardAt({ rowCoords[it] }, w.x, w.y) ?: return@awaitEachGesture
                    val slop = viewConfiguration.touchSlop
                    val early = withTimeoutOrNull(Reorder.HOLD_MS) {
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                            if (!ch.pressed || ch.isConsumed) return@withTimeoutOrNull true
                            if ((ch.position - down.position).getDistance() > slop) return@withTimeoutOrNull true
                        }
                        @Suppress("UNREACHABLE_CODE")
                        true
                    }
                    if (early != null) return@awaitEachGesture
                    val r = rowCoords[id] ?: return@awaitEachGesture
                    if (!reorderCtl.lift(id, w.x, w.y, r.left, r.width)) return@awaitEachGesture
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = ev.changes.firstOrNull { it.id == down.id }
                        ev.changes.forEach { it.consume() }
                        if (ch == null || !ch.pressed) break
                        val p = ch.position + rootOrigin
                        reorderCtl.move(p.x, p.y)
                    }
                    reorderCtl.drop()
                }
            }
            // One open row at a time: a touch anywhere outside it closes it and is used up by that.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val id = openIdState.value ?: return@awaitEachGesture
                    val row = rowCoords[id]
                    val inside = row != null && row.contains(down.position + rootOrigin)
                    if (inside) return@awaitEachGesture
                    openId = null
                    down.consume()
                    do {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        ev.changes.forEach { it.consume() }
                    } while (ev.changes.any { it.pressed })
                }
            },
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(top = top, bottom = bottom + 16.dp),
        ) {
            item(key = "header") {
                LibraryHeader(model, device, onDac, actions, reorder)
            }
            item(key = "block") {
                // One block: every active profile and the empty slot as its last row. The block draws the shadow; each row draws its own surface
                // and clips its own content, so a sliding row stays inside its slot.
                // In REORDER the block's own surface and shadow fade out and the rows part into cards ([RowFx]).
                Box(Modifier.fillMaxWidth().padding(horizontal = SIDE).testTag("library_block")) {
                Box(Modifier.matchParentSize().graphicsLayer { alpha = blockGlue }.lift(BLOCK, Lift.CARD).background(c.surface, BLOCK))
                Column(Modifier.fillMaxWidth()) {
                    active.forEachIndexed { i, p ->
                        key(p.id) {
                            // The action lists are kept while the row's profile is the same: with new lists on every pass no row could be skipped, and every edit on EQ
                            // (each step of a drag changes the list of profiles) recomposed every row of the Library behind it.
                            val rowActions = remember(p, actions) {
                                listOf(
                                    RowAction(R.string.library_action_archive, "archive", Icons.Outlined.Archive, R.string.library_action_archive_description) { actions.archive(p) },
                                    RowAction(R.string.library_action_delete, "delete", Icons.Outlined.Delete, R.string.library_action_delete_description) { actions.delete(p) },
                                )
                            }
                            // owner 13.10.2026: SAVE first and on the full swipe, the mirror of ARCHIVE on the other side
                            val leftActions = remember(p, actions) {
                                listOf(
                                    RowAction(R.string.library_action_save, "overwrite_sent", Icons.Outlined.Save, R.string.library_action_save_description) { actions.overwriteSent(p) },
                                    RowAction(R.string.library_action_restore, "restore_sent", Icons.Outlined.Restore, R.string.library_action_restore_description) { actions.restoreSent(p) },
                                )
                            }
                            SwipeRow(
                                p = p,
                                current = p.id == model.currentId,
                                onDac = p.id in onDac,
                                saved = model.savedBands(p),
                                edited = model.canRevertToSent(p),
                                shelfForm = model.protocol.shelfForm,
                                shape = rowShape(first = i == 0, last = false),
                                hairline = i < active.lastIndex,
                                surface = if (p.id == model.currentId) c.surface2 else c.surface,
                                fx = reorderCtl.fx(p.id),
                                first = i == 0,
                                reorder = reorder,
                                onMoveUp = if (i > 0) ({ model.moveActive(p.id, i - 1) }) else null,
                                onMoveDown = if (i < active.lastIndex) ({ model.moveActive(p.id, i + 1) }) else null,
                                open = if (openId == p.id) openSide else Side.NONE,
                                onOpen = { if (it != Side.NONE) { openId = p.id; openSide = it } else if (openId == p.id) openId = null },
                                onTap = { sender.leaveAb { model.choose(p.id) } },
                                onLongPress = { actions.edit(p.id) },
                                fullAction = { actions.archive(p) },
                                rowActions = rowActions,
                                leftActions = leftActions,
                                leftFull = { actions.overwriteSent(p) },
                                coords = rowCoords,
                                nudge = if (p.id == hintId) nudge else 0,
                                onSwipe = markSwiped,
                            )
                        }
                    }
                    // the empty slot folds away while the cards are apart
                    key(NEW_SLOT_ID) { Box(Modifier.fillMaxWidth().clipToBounds().layout { m, cs ->
                        val pl = m.measure(cs)
                        layout(pl.width, (pl.height * slotOpen).roundToInt()) { pl.place(0, 0) }
                    }) {
                        val rowActions = remember(actions) { listOf(RowAction(R.string.library_action_save_all, "sync", Icons.Rounded.Sync, R.string.library_action_save_all_description, run = actions::syncAll)) }
                        SwipeRow(
                            p = null,
                            current = false,
                            onDac = false,
                            shelfForm = model.protocol.shelfForm,
                            shape = rowShape(first = active.isEmpty(), last = true),
                            hairline = false,
                            surface = c.surface,
                            open = if (openId == NEW_SLOT_ID) openSide else Side.NONE,
                            onOpen = { if (it != Side.NONE) { openId = NEW_SLOT_ID; openSide = it } else if (openId == NEW_SLOT_ID) openId = null },
                            onTap = { openId = null; actions.newProfile() },
                            onLongPress = {},
                            fullAction = actions::syncAll,
                            rowActions = rowActions,
                            coords = rowCoords,
                            reorder = reorder,
                        )
                    } }
                }
                }
            }
            if (archived.isNotEmpty() && !reorder) {
                item(key = "archive-header") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .height(56.dp)
                            .clickable { onArchiveOpen(!archiveOpen) }
                            .padding(start = SIDE + 6.dp, end = SIDE + 6.dp)
                            .testTag("archive_header"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.library_archive_header), style = Type.label, color = c.textDim)
                        // the dot and the count as two words, so a right-to-left phone reads them in its own order
                        Spacer(Modifier.width(8.dp))
                        Text("·", style = Type.label, color = c.textMute)
                        Spacer(Modifier.width(6.dp))
                        Text("${archived.size}", style = Type.label, color = c.textMute)
                        Spacer(Modifier.weight(1f))
                        Icon(if (archiveOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = c.textDim)
                    }
                }
                if (archiveOpen) {
                    item(key = "archive-block") {
                        // the archive lies flat: no shadow, the block's surface at 70 % over the page
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = SIDE)
                                .background(archBg, BLOCK)
                                .testTag("archive_block"),
                        ) {
                            archived.forEachIndexed { i, p ->
                                key(p.id) {
                                    val rowActions = remember(p, actions) {
                                        listOf(
                                            RowAction(R.string.library_action_unarchive, "restore", Icons.Outlined.Unarchive, R.string.library_action_unarchive_description) { actions.restore(p) },
                                            RowAction(R.string.library_action_delete, "delete", Icons.Outlined.Delete, R.string.library_action_delete_description) { actions.delete(p) },
                                        )
                                    }
                                    SwipeRow(
                                        p = p,
                                        current = false,
                                        onDac = p.id in onDac,
                                        saved = model.savedBands(p),
                                        edited = model.canRevertToSent(p),
                                        shelfForm = model.protocol.shelfForm,
                                        shape = rowShape(first = i == 0, last = i == archived.lastIndex),
                                        hairline = i < archived.lastIndex,
                                        surface = archBg,
                                        under = archBg,
                                        open = if (openId == p.id) openSide else Side.NONE,
                                        onOpen = { if (it != Side.NONE) { openId = p.id; openSide = it } else if (openId == p.id) openId = null },
                                        onTap = { sender.leaveAb { model.choose(p.id) } },
                                        onLongPress = { actions.edit(p.id) },
                                        fullAction = { actions.restore(p) },
                                        rowActions = rowActions,
                                        coords = rowCoords,
                                        dim = true,
                                        onSwipe = markSwiped,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item(key = "foot") {
                // The bottom (owner 26.11.2026): REORDER PROFILES, the two settings and ABOUT on the app's grid - every row 64 dp, every control 48 dp with 8 dp of air.
                // Away while the cards are apart.
                AnimatedVisibility(!reorder, enter = fadeIn(tween(220)), exit = fadeOut(tween(220))) {
                    LibraryBottom(optionW, onReorder = { openId = null; onReorder(true) }, onAbout = { openId = null; actions.licences() })
                }
            }
        }
    }
}

/**
 * The grey raised button of the bottom and of the empty slot (owner 26.11.2026): AppButton's look (surface2, RAISED, the button type) at the size of every control
 * of the bottom - 48 dp, M corners. [onClick] null = the row around it takes the tap (the empty slot); with it the whole button is the target (one tap haptic).
 */
@Composable
private fun GreyButton(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable BoxScope.() -> Unit) {
    val c = pal
    val haptics = LocalHaptics.current
    val shape = RoundedCornerShape(Radii.M)
    Box(
        modifier
            .lift(shape, Lift.RAISED)
            .clip(shape)
            .background(c.surface2)
            .let { if (onClick == null) it else it.clickable(role = Role.Button) { haptics.tap(); onClick() } },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** REORDER PROFILES: a 64 dp row with the grey button of 48 dp in it (8 dp of air), the text in the accent colour. */
@Composable
private fun ReorderRow(onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(ROW_H).padding(Grid.GAP)) {
        GreyButton(Modifier.fillMaxSize().testTag("reorder_profiles"), onClick) {
            Text(stringResource(R.string.library_reorder_profiles), style = Type.segment, color = pal.accent, maxLines = 1)
        }
    }
}

/**
 * The bottom of the Library (owner 26.11.2026): one block in the language of the profile list, [Grid.GROUP] under the lists as the EQ and DAC pages separate their groups,
 * with full-width lines between its rows - REORDER PROFILES (the same grey button as the "+" of the list), OPEN CONTOUR with an OFF | ON well and HAPTIC FEEDBACK as a menu
 * like FILTER on the DAC page, rows like those of the DAC page - and under it the ABOUT line.
 */
@Composable
private fun LibraryBottom(optionW: Dp, onReorder: () -> Unit, onAbout: () -> Unit) {
    val c = pal
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(start = SIDE, end = SIDE, top = Grid.GROUP).lift(BLOCK, Lift.CARD).background(c.surface, BLOCK).testTag("library_foot")) {
            ReorderRow(onReorder)
            FootHairline()
            UsbAttachRow(optionW)
            FootHairline()
            HapticsRow(optionW)
        }
        // the way to the privacy policy and the open-source notices, always visible at the foot of the library, with the version (centred, one line)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = SIDE)
                .height(Grid.ROW)
                .clip(BLOCK)
                .clickable(onClick = onAbout)
                .testTag("licences"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${stringResource(R.string.library_about)} · ${BuildConfig.VERSION_NAME}",
                style = Type.small, color = c.textMute, maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The corners of a row inside its block: the block's own on its first and last row, square between them. */
private fun rowShape(first: Boolean, last: Boolean): Shape = RoundedCornerShape(
    topStart = if (first) Radii.L else 0.dp, topEnd = if (first) Radii.L else 0.dp,
    bottomStart = if (last) Radii.L else 0.dp, bottomEnd = if (last) Radii.L else 0.dp,
)

@Composable
private fun FootHairline() {
    // edge to edge (owner 25.11.2026: a line that starts after the icon column and ends at the edge looks lopsided)
    Box(Modifier.fillMaxWidth().height(1.dp).background(pal.grid))
}

/**
 * LIBRARY and the connect chip on the right (tap = connect, long-press = service, as before: connecting is more frequent than managing the library);
 * under the title, only while a DAC is connected, its name and the profile on it ("TANCHJIM SPACE II · NIGHTFALL ON DAC", the profile part in accent).
 */
@Composable
private fun LibraryHeader(model: AppModel, device: DeviceController, onDac: Set<String>, actions: LibraryActions, reorder: Boolean) {
    val c = pal
    Row(
        // Same header height as Tune; the chip ends at the block's edge.
        Modifier.fillMaxWidth().height(Grid.ROW).padding(start = SIDE + 6.dp, end = SIDE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.page_library), style = Type.screenTitle, color = c.text)
        Spacer(Modifier.weight(1f))
        DeviceStatus(device, actions::service)
    }
    val connected = device.link == Link.CONNECTED
    // REORDER PROFILES: the line says what to do (it fades in over the DAC line)
    Crossfade(reorder, animationSpec = tween(200), label = "header") { arranging ->
    if (arranging) {
        Column {
            Box(Modifier.fillMaxWidth().height(36.dp).padding(start = SIDE + 6.dp, end = SIDE), contentAlignment = Alignment.CenterStart) {
                Text(stringResource(R.string.library_drag_to_reorder), style = Type.label, color = c.textMute, maxLines = 1, modifier = Modifier.testTag("drag_to_reorder"))
            }
            Spacer(Modifier.height(8.dp))
        }
    } else if (connected) {
        val dac = device.protocol.caps.name.uppercase()
        // twins (the same EQ under two names) are all ON DAC: the current one when it is among them, else the first in the library
        val profile = (model.current?.takeIf { it.id in onDac } ?: model.profiles.firstOrNull { it.id in onDac })?.name
        val onDacWord = stringResource(R.string.library_on_dac)
        val profilePart = profile?.let { stringResource(R.string.library_header_on_dac, it, onDacWord) }
        val line = buildAnnotatedString {
            append(dac)
            if (profilePart != null) {
                append(" · ")
                withStyle(SpanStyle(color = c.accent)) { append(profilePart) }
            }
        }
        Column {
            Box(Modifier.fillMaxWidth().height(36.dp).padding(start = SIDE + 6.dp, end = SIDE), contentAlignment = Alignment.CenterStart) {
                Text(line, style = Type.label, color = c.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("library_dac_line"))
            }
            Spacer(Modifier.height(8.dp))
        }
    } else {
        Spacer(Modifier.fillMaxWidth().height(8.dp))
    }
    }
}

/** A button behind a swipe: [labelRes] under its icon, [descriptionRes] its accessibility action. */
class RowAction(@StringRes val labelRes: Int, val tag: String, val icon: ImageVector, @StringRes val descriptionRes: Int, val run: () -> Unit)

/** Which strip of a [SwipeRow] stands open: the left one (SAVE / RESTORE, a rightward drag) or the right one (ARCHIVE / DELETE, a leftward drag). */
private enum class Side { NONE, LEFT, RIGHT }

/**
 * One library row. Its own gestures are a LEFTWARD swipe (reveal the right strip: [rowActions], past fullAt = [fullAction]),
 * for an active profile also a RIGHTWARD swipe (the mirror: reveal the left strip [leftActions], past fullAt = [leftFull],
 * which says whether it did anything), and a tap / long-press. A row without [leftActions] leaves a rightward swipe to
 * the pager (-> Tune). [surface] is the row's own colour (opaque: the strips lie under it); [shape] its corners in the block.
 */
@Composable
private fun SwipeRow(
    p: Profile?,
    current: Boolean,
    onDac: Boolean,
    open: Side,
    onOpen: (Side) -> Unit,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    fullAction: () -> Unit,
    rowActions: List<RowAction>,
    coords: HashMap<String, Rect>,
    shape: Shape,
    hairline: Boolean,
    surface: Color,
    /** The block's colour: what a sliding row reveals around the action pills. */
    under: Color = pal.surface,
    leftActions: List<RowAction> = emptyList(),
    leftFull: () -> Boolean = { false },
    dim: Boolean = false,
    nudge: Int = 0,
    onSwipe: () -> Unit = {},
    saved: List<Band>? = null,
    edited: Boolean = false,
    /** The shelf form of the attached DAC ([ShelfForm]): the curve of the row is drawn in it. */
    shelfForm: ShelfForm,
    /** An active row's motion in REORDER ([Reorder]); null for the archive and the empty slot. */
    fx: RowFx? = null,
    /** The first row of the block (its top corners are the block's). */
    first: Boolean = false,
    /** REORDER PROFILES is on: no swipes, taps or long-presses on the row (the screen's own gesture lifts the cards). */
    reorder: Boolean = false,
    /** TalkBack: Move up / Move down (null at the ends). */
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    val c = pal
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // A right-to-left phone mirrors the row, as Material's swipe-to-dismiss does (start to end, end to start): ARCHIVE / DELETE stand at the
    // left edge behind a rightward swipe, SAVE / RESTORE at the right edge behind a leftward one. [offset] is in the layout's direction
    // ([Modifier.offset] flips it), so a finger's x is turned once at the input and everything below - the strips, the full swipe, the haptics,
    // the hint - stays as written: "left" and "right" in this code are the layout's start and end.
    val sign = layoutSign()
    val reveal = with(density) { (ACTION_WIDTH * rowActions.size).toPx() }
    val revealLeft = if (leftActions.isEmpty()) 0f else with(density) { (ACTION_WIDTH * leftActions.size).toPx() }
    val hop = with(density) { 20.dp.toPx() }
    var width by remember { mutableIntStateOf(1) }
    var offset by remember { mutableFloatStateOf(0f) } // negative = the right strip is showing, positive = the left one
    var anim by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var nudging by remember { mutableStateOf(false) }
    var fullSwipeStart by remember { mutableStateOf<Float?>(null) }
    // 0..1: past the full-action point the first action takes over the whole revealed width (one per strip)
    val arm = remember { Animatable(0f) }
    val armLeft = remember { Animatable(0f) }
    val revealNow = rememberUpdatedState(reveal)
    val revealLeftNow = rememberUpdatedState(revealLeft)
    val fullMargin = with(density) { FULL_MARGIN.toPx() }

    // the full-action point of a strip (owner 13.10.2026: 55 % alone fired too easily): 55 % of the row, but always past
    // the settled-open strip by a margin, so on a narrow phone an open strip is never armed and a small drag cannot fire it
    fun fullAt(strip: Float) = maxOf(0.55f * width, strip + fullMargin)

    fun animateTo(target: Float) {
        anim?.cancel()
        anim = scope.launch { animate(offset, target) { v, _ -> offset = v } }
    }

    LaunchedEffect(open) {
        val target = when (open) {
            Side.NONE -> 0f
            Side.RIGHT -> -reveal
            Side.LEFT -> revealLeft
        }
        if (offset != target) animateTo(target)
    }

    // Arming the full swipe is a threshold the finger crosses (owner 14.11.2026): the snap of the bowstring when it arms ([SwipeFeel] draws the tension on
    // the way), a soft tick when the finger takes it back while still dragging (not when the drag ends, which also disarms).
    LaunchedEffect(Unit) {
        var was = false
        snapshotFlow { fullSwipeArmed(-offset, fullAt(revealNow.value), fullSwipeStart) }.distinctUntilChanged().collectLatest { armed ->
            if (armed) haptics.swipeSnap() else if (was && fullSwipeStart != null) haptics.threshold(false)
            was = armed
            arm.animateTo(if (armed) 1f else 0f, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium))
        }
    }
    LaunchedEffect(Unit) {
        var was = false
        snapshotFlow { fullSwipeArmed(offset, fullAt(revealLeftNow.value), fullSwipeStart) }.distinctUntilChanged().collectLatest { armed ->
            if (armed) haptics.swipeSnap() else if (was && fullSwipeStart != null) haptics.threshold(false)
            was = armed
            armLeft.animateTo(if (armed) 1f else 0f, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium))
        }
    }

    // the swipe hint: the closed row hops left twice, a small bounce, and the actions behind it peek out
    LaunchedEffect(nudge) {
        if (nudge == 0 || reorder || open != Side.NONE || offset != 0f || anim?.isActive == true) return@LaunchedEffect
        anim = scope.launch {
            nudging = true
            try {
                val back = spring<Float>(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow)
                animate(0f, -hop, animationSpec = tween(170, easing = FastOutSlowInEasing)) { v, _ -> offset = v }
                animate(offset, 0f, animationSpec = back) { v, _ -> offset = v }
                delay(70)
                animate(offset, -hop * 0.55f, animationSpec = tween(140, easing = FastOutSlowInEasing)) { v, _ -> offset = v }
                animate(offset, 0f, animationSpec = back) { v, _ -> offset = v }
            } finally {
                nudging = false
            }
        }
    }

    val openNow = rememberUpdatedState(open)
    val swipe = rememberUpdatedState(onSwipe)
    val tap = rememberUpdatedState(onTap)
    val longPress = rememberUpdatedState(onLongPress)
    val full = rememberUpdatedState(fullAction)
    val fullLeft = rememberUpdatedState(leftFull)
    val hasLeft = rememberUpdatedState(leftActions.isNotEmpty())
    val setOpen = rememberUpdatedState(onOpen)
    val name = p?.name ?: "empty_slot"
    // the accessibility words of the row (the semantics block below is not composable)
    val rtl = sign < 0f
    val rowDescription = when {
        p == null -> stringResource(if (rtl) R.string.library_row_new_rtl else R.string.library_row_new)
        dim -> stringResource(if (edited) R.string.library_row_archived_edited else R.string.library_row_archived, p.name)
        rtl -> stringResource(if (edited) R.string.library_row_normal_edited_rtl else R.string.library_row_normal_rtl, p.name)
        else -> stringResource(if (edited) R.string.library_row_normal_edited else R.string.library_row_normal, p.name)
    }
    val tapLabel = stringResource(if (p == null) R.string.library_row_create else R.string.library_row_select)
    val editLabel = stringResource(R.string.library_row_edit)
    val actionDescriptions = (rowActions + leftActions).map { stringResource(it.descriptionRes) }
    val moveUpLabel = stringResource(R.string.library_move_up)
    val moveDownLabel = stringResource(R.string.library_move_down)
    val reorderNow = rememberUpdatedState(reorder)
    val shadowColor = c.shadow
    val shadowAlpha = c.shadowAlpha
    val gapPx = with(density) { Reorder.GAP_DP.dp.toPx() }
    val rCard = with(density) { Radii.L.toPx() }
    val liftedColor = c.surface2
    val rowId = p?.id ?: NEW_SLOT_ID
    DisposableEffect(rowId, coords) { onDispose { coords.remove(rowId) } }
    val gridColor = c.grid

    Box(
        Modifier
            .fillMaxWidth()
            // REORDER: an 8 dp gap opens under every card; the dragged card is placed above the others
            .let {
                if (fx == null) it else it.layout { m, cs ->
                    val pl = m.measure(cs)
                    layout(pl.width, pl.height + (gapPx * fx.apart).roundToInt()) { pl.place(0, 0, zIndex = fx.z) }
                }
            }
            .height(ROW_H)
            .onSizeChanged { width = it.width }
            .onGloballyPositioned { coords[rowId] = it.boundsInWindow() }
            .let {
                if (fx == null) it.clip(shape) else it
                    .graphicsLayer {
                        translationY = fx.dy
                        rotationZ = fx.rot
                        scaleX = fx.sx
                        scaleY = fx.sy
                        transformOrigin = TransformOrigin(fx.pivotX, 0.5f)
                    }
                    // the card's own shadow: RAISED when apart, breathing to HERO, HERO while dragged
                    .dropShadow(RoundedCornerShape(Radii.L)) {
                        val s = fx.shadow
                        radius = (6f + 8f * s).dp.toPx()
                        this.offset = Offset(0f, (2f + 3f * s).dp.toPx())
                        color = shadowColor
                        alpha = 0.55f * shadowAlpha * fx.apart
                    }
                    // the block's corners on its first row, a card's (Radii.L) corners when apart
                    .graphicsLayer {
                        val a = fx.apart
                        val top = if (first) rCard else rCard * a
                        val bottom = rCard * a
                        this.shape = RoundedCornerShape(top, top, bottom, bottom)
                        clip = true
                    }
            }
            // the block under the row: the revealed strips show it around their pills
            .background(under)
            .pointerInput(rowId, sign, reorder) {
                if (reorder) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = viewConfiguration.touchSlop
                    // the strip the row stands open on (a row caught mid-slide counts as open)
                    val side0 = when {
                        openNow.value != Side.NONE -> openNow.value
                        offset < -1f && !nudging -> Side.RIGHT
                        offset > 1f -> Side.LEFT
                        else -> Side.NONE
                    }
                    val wasOpen = side0 != Side.NONE
                    var dragSide = side0
                    var total = Offset.Zero
                    // 0 = still undecided, 1 = up (tap), 2 = drag (ours), 3 = not ours
                    val phase = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull 3
                            if (ch.changedToUp()) return@withTimeoutOrNull if (ch.isConsumed) 3 else 1
                            if (ch.isConsumed) return@withTimeoutOrNull 3
                            total += ch.positionChange().let { Offset(it.x * sign, it.y) }
                            if (total.getDistance() > slop) {
                                val horizontal = abs(total.x) > abs(total.y)
                                return@withTimeoutOrNull if (horizontal && (wasOpen || total.x < 0 || hasLeft.value)) {
                                    // an open row keeps its side: a drag the other way only closes it
                                    if (!wasOpen) dragSide = if (total.x < 0) Side.RIGHT else Side.LEFT
                                    ch.consume()
                                    2
                                } else {
                                    3
                                }
                            }
                        }
                        @Suppress("UNREACHABLE_CODE")
                        3
                    }
                    when (phase) {
                        null -> { // long press
                            haptics.longPress()
                            longPress.value()
                            do {
                                val ev = awaitPointerEvent()
                                ev.changes.forEach { it.consume() }
                            } while (ev.changes.any { it.pressed })
                        }
                        1 -> if (wasOpen) setOpen.value(Side.NONE) else tap.value()
                        2 -> {
                            anim?.cancel()
                            swipe.value()
                            // A short reveal may settle past 55% on a narrow row. An active drag must extend
                            // beyond its starting reveal to arm; settling and closing cannot fire the action.
                            fullSwipeStart = if (wasOpen) abs(offset) else 0f
                            val lo = if (dragSide == Side.RIGHT) -width.toFloat() else 0f
                            val hi = if (dragSide == Side.LEFT) width.toFloat() else 0f
                            var x = offset + total.x
                            val right = dragSide == Side.RIGHT
                            // the tension of the swipe follows the row: how far it is towards the full-action point, and how far it moved
                            val tension = SwipeFeel(haptics)
                            val feelTension = {
                                val shownNow = if (right) -offset else offset
                                val fullPx = fullAt(if (right) revealNow.value else revealLeftNow.value)
                                tension.move(shownNow, fullPx, fullSwipeArmed(shownNow, fullPx, fullSwipeStart))
                            }
                            tension.start(abs(offset))
                            val fireFull = try {
                                offset = x.coerceIn(lo, hi)
                                feelTension()
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) break
                                    x += ch.positionChange().x * sign
                                    ch.consume()
                                    offset = x.coerceIn(lo, hi)
                                    feelTension()
                                }
                                fullSwipeArmed(if (dragSide == Side.RIGHT) -offset else offset,
                                    fullAt(if (dragSide == Side.RIGHT) revealNow.value else revealLeftNow.value), fullSwipeStart)
                            } finally {
                                fullSwipeStart = null
                            }
                            val shown = if (dragSide == Side.RIGHT) -offset else offset
                            val strip = if (dragSide == Side.RIGHT) revealNow.value else revealLeftNow.value
                            when {
                                fireFull -> {
                                    setOpen.value(Side.NONE)
                                    anim?.cancel()
                                    offset = 0f
                                    scope.launch { arm.snapTo(0f); armLeft.snapTo(0f) }
                                    if (dragSide == Side.RIGHT) {
                                        haptics.commit()
                                        full.value()
                                    } else if (fullLeft.value()) {
                                        haptics.commit()
                                    }
                                }
                                shown > strip / 2 -> {
                                    setOpen.value(dragSide)
                                    animateTo(if (dragSide == Side.RIGHT) -strip else strip)
                                }
                                else -> {
                                    setOpen.value(Side.NONE)
                                    animateTo(0f)
                                }
                            }
                        }
                        else -> Unit
                    }
                    // a touch that lands mid-hop settles the row instead of leaving it ajar
                    if (phase != 2 && openNow.value == Side.NONE && offset != 0f && anim?.isActive != true) animateTo(0f)
                }
            }
            .semantics(mergeDescendants = true) {
                selected = current
                contentDescription = rowDescription
                onClick(label = tapLabel) { if (!reorderNow.value) tap.value(); !reorderNow.value }
                if (p != null) onLongClick(label = editLabel) { if (!reorderNow.value) longPress.value(); !reorderNow.value }
                val moves = listOfNotNull(
                    onMoveUp?.let { CustomAccessibilityAction(moveUpLabel) { it(); true } },
                    onMoveDown?.let { CustomAccessibilityAction(moveDownLabel) { it(); true } },
                )
                customActions = moves + if (reorder) emptyList() else (rowActions + leftActions).mapIndexed { index, a ->
                    CustomAccessibilityAction(actionDescriptions[index]) {
                        setOpen.value(Side.NONE)
                        a.run()
                        true
                    }
                }
            }
            .testTag("row_$name"),
    ) {
        if (leftActions.isNotEmpty()) ActionsLayer(
            actions = leftActions,
            mirrored = true,
            offset = { -offset },
            arm = { armLeft.value },
            enabled = open == Side.LEFT,
            live = edited,
            name = name,
            onRun = { a ->
                setOpen.value(Side.NONE)
                offset = 0f
                a.run()
            },
            modifier = Modifier.matchParentSize(),
        )
        ActionsLayer(
            actions = rowActions,
            offset = { offset },
            arm = { arm.value },
            enabled = open == Side.RIGHT,
            name = name,
            onRun = { a ->
                setOpen.value(Side.NONE)
                offset = 0f
                a.run()
            },
            modifier = Modifier.matchParentSize(),
        )
        Row(
            Modifier
                .offset { IntOffset(offset.roundToInt(), 0) }
                .fillMaxSize()
                // the row's surface (the dragged card a step lighter) and the hairline under it, across the block's full width (owner 25.11.2026);
                // the hairline slides with the row and fades as the cards part
                .drawBehind {
                    drawRect(if (fx == null) surface else lerp(surface, liftedColor, fx.lifted))
                    val fade = 1f - (fx?.apart ?: 0f)
                    if (hairline && fade > 0f) {
                        val y = size.height - 0.5.dp.toPx()
                        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(), alpha = fade)
                    }
                }
                .padding(horizontal = if (p == null) Grid.GAP else ROW_PAD)
                .testTag(if (p == null) "empty_slot" else "profile_card"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (p == null) {
                // the empty slot: the grey button of the bottom (48 dp, 8 dp of air) with the + in the accent colour; the row takes the tap
                GreyButton(Modifier.fillMaxSize().padding(vertical = Grid.GAP)) {
                    Icon(Icons.Rounded.Add, null, tint = c.accent, modifier = Modifier.size(24.dp))
                }
                return@Row
            }
            val faded = if (dim) 0.8f else 1f
            Box(Modifier.width(ICON_COL).graphicsLayer { alpha = faded }, contentAlignment = Alignment.CenterStart) {
                Icon(
                    ProfileIcons.of(p.icon),
                    contentDescription = null,
                    tint = if (current) c.accent else c.textDim,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        p.name,
                        style = ROW_NAME,
                        color = if (dim) c.textDim else c.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).graphicsLayer { alpha = faded },
                    )
                    // unsaved changes: the working copy differs from the SAVED version (SAVE / RESTORE would change something)
                    if (edited) {
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(6.dp).background(c.accent, CircleShape).testTag("row_${name}_edited"))
                    }
                }
                val sub = p.subtitle
                if (onDac || sub.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    val onDacWord = stringResource(R.string.library_on_dac)
                    val line = if (!onDac) null else buildAnnotatedString {
                        withStyle(SpanStyle(color = c.accent)) { append(onDacWord) }
                        if (sub.isNotEmpty()) append(" · $sub")
                    }
                    if (line != null) {
                        Text(line, style = Type.sub, color = c.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("row_${name}_ondac"))
                    } else {
                        Text(sub, style = Type.sub, color = if (dim) c.textMute else c.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            // the curve is the SAVED version; RESTORE / SAVE live only behind the rightward swipe
            Spacer(Modifier.width(10.dp))
            ResponseThumb(saved ?: p.bands, shelfForm, if (dim) c.textMute else c.textDim, Modifier.size(72.dp, 28.dp).graphicsLayer { alpha = faded })
        }
    }
}

private val ICON = 20.dp

/**
 * The row's actions behind the sliding content, laid out from the swipe alone: as the row slides, each icon travels to the middle of its own share
 * of the revealed width, growing and taking its colour, while the pills and labels settle in under them - everything flies into place, as in
 * Quick Settings. Past the full-action point the first action takes the whole width. [offset] is how far the row has slid away from this strip
 * (negative = revealed). [mirrored] is the strip on the left edge: the same layout flipped. [live] says whether the actions would change anything:
 * null = always (ARCHIVE / DELETE), true = they would, false = they would not (the whole strip fades). The pills stand [PILL_INSET] inside the
 * row's top and bottom edge.
 */
@Composable
private fun ActionsLayer(
    actions: List<RowAction>,
    offset: () -> Float,
    arm: () -> Float,
    enabled: Boolean,
    name: String,
    onRun: (RowAction) -> Unit,
    modifier: Modifier = Modifier,
    mirrored: Boolean = false,
    live: Boolean? = null,
) {
    val c = pal
    val density = LocalDensity.current
    val n = actions.size
    val g = remember(density, n) { Geo(density, n) }
    val restTint = if (live == true) c.accent else c.textMute.copy(alpha = 0.75f)
    val fade = if (live == false) 0.35f else 1f
    val vInset = with(density) { PILL_INSET.roundToPx() }
    Layout(
        modifier = modifier,
        content = {
            actions.forEachIndexed { i, a ->
                val bg = if (i == n - 1) c.text else c.surface2
                Box(
                    Modifier
                        .clip(PILL)
                        .drawBehind { drawRect(bg, alpha = g.pillAlpha(i, offset(), arm()) * fade) }
                        .clickable(enabled = enabled) { onRun(a) }
                        .testTag("row_${name}_${a.tag}"),
                )
            }
            actions.forEachIndexed { i, a ->
                val painter = rememberVectorPainter(a.icon)
                val fg = if (i == n - 1) c.bg else c.text
                Spacer(
                    Modifier.size(ICON).drawBehind {
                        val o = offset()
                        val s = g.iconSize(i, o)
                        val tint = lerp(restTint, fg, g.colour(i, o))
                        translate((size.width - s) / 2, (size.height - s) / 2) {
                            with(painter) { draw(Size(s, s), g.iconAlpha(i, o, arm()) * fade, ColorFilter.tint(tint)) }
                        }
                    },
                )
            }
            actions.forEachIndexed { i, a ->
                Text(
                    stringResource(a.labelRes),
                    style = Type.segment,
                    color = if (i == n - 1) c.bg else c.text,
                    maxLines = 1,
                    modifier = Modifier.graphicsLayer {
                        val v = g.labelAlpha(i, offset(), arm())
                        alpha = v * fade
                        translationY = (1f - v) * g.settle
                    },
                )
            }
        },
    ) { measurables, cs ->
        val w = cs.maxWidth
        val h = cs.maxHeight
        val o = offset()
        val ar = arm()
        val pills = (0 until n).map { i ->
            measurables[i].measure(Constraints.fixed(g.pillWidth(i, o, ar).roundToInt().coerceAtLeast(0), (h - 2 * vInset).coerceAtLeast(0)))
        }
        val icons = (0 until n).map { measurables[n + it].measure(Constraints()) }
        val labels = (0 until n).map { measurables[2 * n + it].measure(Constraints()) }
        layout(w, h) {
            // laid out as for the right edge, then flipped for the left one; placed relative, so on a right-to-left phone the whole layer
            // mirrors with the row (the end strip at the left edge, the start strip at the right)
            fun x(v: Float) = if (mirrored) w - v else v
            for (i in 0 until n) {
                val left = g.pillLeft(i, w, o, ar)
                pills[i].placeRelative((if (mirrored) w - (left + pills[i].width) else left).roundToInt(), vInset)
                val cx = x(left + pills[i].width / 2f)
                val (ix, iy) = g.iconCentre(i, w, h, o, left + pills[i].width / 2f)
                icons[i].placeRelative((x(ix) - icons[i].width / 2f).roundToInt(), (iy - icons[i].height / 2f).roundToInt())
                labels[i].placeRelative((cx - labels[i].width / 2f).roundToInt(), (h / 2f + g.labelTop).roundToInt())
            }
        }
    }
}

/** The geometry of [ActionsLayer]: everything is a function of the row's offset and the full-action arm. The row has no resting gap: nothing shows at rest. */
private class Geo(d: Density, val n: Int) {
    private val open = with(d) { (ACTION_WIDTH * n).toPx() }
    private val inset = with(d) { 6.dp.toPx() }
    private val startSize = with(d) { 13.dp.toPx() }
    private val iconSize = with(d) { ICON.toPx() }
    private val raise = with(d) { 10.dp.toPx() }
    val labelTop = with(d) { 5.dp.toPx() }
    val settle = with(d) { 6.dp.toPx() }

    private fun revealed(o: Float) = -o.coerceAtMost(0f)
    private fun progress(o: Float) = (revealed(o) / open).coerceIn(0f, 1f)
    private fun rest(i: Int, ar: Float) = if (i == 0) 1f else 1f - ar

    /**
     * How far icon [i] has moved, 0..1. The first one goes farthest, so it leaves the edge at once and is in
     * place by half the reveal instead of lingering next to the others; the rest follow a beat later.
     */
    private fun travel(i: Int, o: Float): Float {
        if (i == 0) return LinearOutSlowInEasing.transform((progress(o) / 0.5f).coerceIn(0f, 1f))
        val lead = 0.08f * i
        return FastOutSlowInEasing.transform(((progress(o) - lead) / (1f - lead)).coerceIn(0f, 1f))
    }

    private fun slotWidth(i: Int, o: Float, ar: Float): Float {
        val r = revealed(o)
        if (n == 1) return r
        val first = r / n + (r - r / n) * ar
        return if (i == 0) first else (r - first) / (n - 1)
    }

    fun pillLeft(i: Int, w: Int, o: Float, ar: Float): Float {
        var x = w - revealed(o)
        for (k in 0 until i) x += slotWidth(k, o, ar)
        return x + inset
    }

    fun pillWidth(i: Int, o: Float, ar: Float) = slotWidth(i, o, ar) - inset
    fun pillAlpha(i: Int, o: Float, ar: Float) = smooth(0.05f, 0.55f, progress(o)) * rest(i, ar)
    fun labelAlpha(i: Int, o: Float, ar: Float) = smooth(0.55f, 0.95f, progress(o)) * rest(i, ar)
    /** The icons only appear as the row slides off. */
    fun iconAlpha(i: Int, o: Float, ar: Float) = rest(i, ar) * smooth(0.02f, 0.2f, progress(o))
    fun iconSize(i: Int, o: Float) = startSize + (iconSize - startSize) * travel(i, o)
    fun colour(i: Int, o: Float) = smooth(0.1f, 0.65f, travel(i, o))

    /** From the middle of what is revealed so far to the top half of its pill. */
    fun iconCentre(i: Int, w: Int, h: Int, o: Float, pillCentre: Float): Pair<Float, Float> {
        val t = travel(i, o)
        val hx = w - revealed(o) / 2
        val hy = h / 2f
        val ty = h / 2f - raise
        return (hx + (pillCentre - hx) * t) to (hy + (ty - hy) * t)
    }

    private fun smooth(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
