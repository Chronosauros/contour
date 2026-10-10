package io.github.chronosauros.contour.ui.library

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.chronosauros.contour.BuildConfig
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.model.AppModel
import io.github.chronosauros.contour.model.Sender
import io.github.chronosauros.contour.ui.DeviceStatus
import io.github.chronosauros.contour.ui.Grid
import io.github.chronosauros.contour.ui.Lift
import io.github.chronosauros.contour.ui.Type
import io.github.chronosauros.contour.ui.lift
import io.github.chronosauros.contour.ui.Radii
import io.github.chronosauros.contour.ui.pal
import io.github.chronosauros.contour.ui.sink
import io.github.chronosauros.contour.ui.ResponseThumb
import io.github.chronosauros.contour.ui.kit.LocalHaptics
import io.github.chronosauros.contour.ui.kit.ProfileIcons
import io.github.chronosauros.contour.usb.DeviceController
import io.github.chronosauros.contour.usb.Link
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** What the Library asks of the app shell (sheets, snackbar, service screen). */
interface LibraryActions {
    fun edit(id: String)
    fun newProfile()
    fun syncAll()
    fun archive(p: Profile)
    fun restore(p: Profile)
    /** RESTORE: the profile's EQ back to its LAST SENT, from any row. True when it changed something (UNDO is offered). */
    fun restoreSent(p: Profile): Boolean
    /** OVERWRITE: the profile's EQ as it is now becomes its LAST SENT. True when it changed something (UNDO is offered). */
    fun overwriteSent(p: Profile): Boolean
    fun delete(p: Profile)
    fun service()
    fun licences()
}

private val CARD_HEIGHT = 76.dp
private val ACTION_WIDTH = 96.dp
/** At rest the card stops short of the right row edge; the strip behind it shows faint action icons (a hint to swipe left). The left edge has no such gap. */
private val HINT_GAP = 22.dp
private const val NEW_SLOT_ID = "library-new-slot"
private val SIDE = Grid.SIDE
private val CARD = RoundedCornerShape(Radii.L)
private val ON_DAC_RAIL = 5.dp
/** How far past a settled-open strip a drag must go before it fires the strip's first action. */
private val FULL_MARGIN = 24.dp

/**
 * Library: choosing and managing profiles only - it never sends anything to the DAC.
 * Tap = current + Tune, long-press = edit sheet, swipe left = ARCHIVE / DELETE (RESTORE / DELETE in the archive),
 * swipe right = RESTORE / OVERWRITE the LAST SENT checkpoint (active rows only), the empty slot after the active rows
 * = new profile, then the collapsible archive.
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
) {
    val c = pal
    val listState = rememberLazyListState()
    var openId by remember { mutableStateOf<String?>(null) }
    var openSide by remember { mutableStateOf(Side.RIGHT) }
    // The shipped DUSK profile hints the swipe until someone swipes a row once - remembered for good
    // (app data survives updates), so the hint never returns, whatever happens to the DUSK profile.
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE) }
    var swiped by remember { mutableStateOf(prefs.getBoolean("swipeHintDone", false)) }
    var nudge by remember { mutableIntStateOf(0) }
    val hintId = if (swiped) null else model.active.firstOrNull { it.name == "DUSK" }?.id
    LaunchedEffect(visible, hintId) {
        if (!visible || hintId == null) return@LaunchedEffect
        delay(900)
        repeat(3) {
            if (openId == null && !listState.isScrollInProgress) nudge++
            delay(6000)
        }
    }
    // bounds in window coordinates, as plain values (no LayoutCoordinates kept across item reuse)
    val rowCoords = remember { HashMap<String, Rect>() }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    val openIdState = rememberUpdatedState(openId)

    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) openId = null }

    val active = model.active
    val archived = model.archived
    val onDac = if (device.link == Link.CONNECTED) sender.onDacId else null

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootOrigin = it.positionInWindow() }
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
                Row(
                    // Same header height as Tune; the status ends at the profile cards' right edge.
                    Modifier.fillMaxWidth().height(Grid.ROW).padding(start = SIDE + 6.dp, end = SIDE + HINT_GAP),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("LIBRARY", style = Type.screenTitle, color = c.text)
                    Spacer(Modifier.weight(1f))
                    DeviceStatus(device, actions::service)
                }
                Spacer(Modifier.height(4.dp))
            }
            items(active, key = { it.id }) { p ->
                SwipeRow(
                    p = p,
                    current = p.id == model.currentId,
                    onDac = p.id == onDac,
                    open = if (openId == p.id) openSide else Side.NONE,
                    onOpen = { if (it != Side.NONE) { openId = p.id; openSide = it } else if (openId == p.id) openId = null },
                    onTap = { sender.leaveAb { model.choose(p.id) } },
                    onLongPress = { actions.edit(p.id) },
                    fullAction = { actions.archive(p) },
                    rowActions = listOf(
                        RowAction("ARCHIVE", "archive", Icons.Outlined.Archive) { actions.archive(p) },
                        RowAction("DELETE", "delete", Icons.Outlined.Delete) { actions.delete(p) },
                    ),
                    leftActions = listOf(
                        RowAction("RESTORE", "restore_sent", Icons.Outlined.Restore, "Restore profile to LAST SENT") { actions.restoreSent(p) },
                        RowAction("OVERWRITE", "overwrite_sent", Icons.Outlined.Save, "Save profile as LAST SENT") { actions.overwriteSent(p) },
                    ),
                    leftFull = { actions.restoreSent(p) },
                    coords = rowCoords,
                    nudge = if (p.id == hintId) nudge else 0,
                    onSwipe = {
                        if (!swiped) {
                            swiped = true
                            prefs.edit().putBoolean("swipeHintDone", true).apply()
                        }
                    },
                )
            }
            item(key = "empty-slot") {
                SwipeRow(
                    p = null,
                    current = false,
                    onDac = false,
                    open = if (openId == NEW_SLOT_ID) openSide else Side.NONE,
                    onOpen = { if (it != Side.NONE) { openId = NEW_SLOT_ID; openSide = it } else if (openId == NEW_SLOT_ID) openId = null },
                    onTap = { openId = null; actions.newProfile() },
                    onLongPress = {},
                    fullAction = actions::syncAll,
                    rowActions = listOf(RowAction("LAST SENT", "sync", Icons.Rounded.Sync, run = actions::syncAll)),
                    coords = rowCoords,
                )
            }
            if (archived.isNotEmpty()) {
                item(key = "archive-header") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .height(56.dp)
                            .clickable { onArchiveOpen(!archiveOpen) }
                            .padding(start = SIDE + 6.dp, end = SIDE + 6.dp)
                            .testTag("archive_header"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("ARCHIVE", style = Type.label, color = c.textDim)
                        Spacer(Modifier.width(10.dp))
                        Text("${archived.size}", style = Type.label, color = c.textMute)
                        Spacer(Modifier.weight(1f))
                        Icon(if (archiveOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = c.textDim)
                    }
                }
                if (archiveOpen) {
                    items(archived, key = { it.id }) { p ->
                        SwipeRow(
                            p = p,
                            current = p.id == model.currentId,
                            onDac = p.id == onDac,
                            open = if (openId == p.id) openSide else Side.NONE,
                            onOpen = { if (it != Side.NONE) { openId = p.id; openSide = it } else if (openId == p.id) openId = null },
                            onTap = { sender.leaveAb { model.choose(p.id) } },
                            onLongPress = { actions.edit(p.id) },
                            fullAction = { actions.restore(p) },
                            rowActions = listOf(
                                RowAction("RESTORE", "restore", Icons.Outlined.Unarchive) { actions.restore(p) },
                                RowAction("DELETE", "delete", Icons.Outlined.Delete) { actions.delete(p) },
                            ),
                            coords = rowCoords,
                            dim = true,
                            onSwipe = {
                        if (!swiped) {
                            swiped = true
                            prefs.edit().putBoolean("swipeHintDone", true).apply()
                        }
                    },
                        )
                    }
                }
            }
            item(key = "licences") {
                // the way to the open-source notices, always visible at the foot of the library, with the version
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp)
                        .height(48.dp)
                        .clickable { openId = null; actions.licences() }
                        .testTag("licences"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("ABOUT & LICENCES · ${BuildConfig.VERSION_NAME}", style = Type.small, color = c.textMute)
                }
            }
        }
    }
}

class RowAction(val label: String, val tag: String, val icon: ImageVector, val description: String? = null, val run: () -> Unit)

/** Which strip of a [SwipeRow] stands open: the left one (RESTORE / OVERWRITE, a rightward drag) or the right one (ARCHIVE / DELETE, a leftward drag). */
private enum class Side { NONE, LEFT, RIGHT }

/**
 * One library row. Its own gestures are a LEFTWARD swipe (reveal the right strip: [rowActions], past 55 % = [fullAction]),
 * for an active profile also a RIGHTWARD swipe (the mirror: reveal the left strip [leftActions], past 55 % = [leftFull],
 * which says whether it did anything), and a tap / long-press. A row without [leftActions] leaves a rightward swipe to
 * the pager (-> Tune).
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
    leftActions: List<RowAction> = emptyList(),
    leftFull: () -> Boolean = { false },
    dim: Boolean = false,
    nudge: Int = 0,
    onSwipe: () -> Unit = {},
) {
    val c = pal
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val gap = with(density) { HINT_GAP.toPx() }
    // the gap is already open at rest, so the card travels that much less to show the right strip; the left strip has none
    val reveal = with(density) { (ACTION_WIDTH * rowActions.size).toPx() } - gap
    val revealLeft = with(density) { (ACTION_WIDTH * leftActions.size).toPx() }
    val hop = with(density) { 20.dp.toPx() }
    val fullMargin = with(density) { FULL_MARGIN.toPx() }
    var width by remember { mutableIntStateOf(1) }
    var offset by remember { mutableFloatStateOf(0f) } // negative = the right strip is showing, positive = the left one
    var anim by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var nudging by remember { mutableStateOf(false) }
    // 0..1: past the full-action point the first action takes over the whole revealed width (one per strip)
    val arm = remember { Animatable(0f) }
    val armLeft = remember { Animatable(0f) }
    val revealNow = rememberUpdatedState(reveal)
    val revealLeftNow = rememberUpdatedState(revealLeft)

    // the full-action point of a strip: 55 % of the row, but always past the settled-open strip by a margin, so on a narrow
    // phone an open strip is never armed (its first pill would take the whole width) and a small drag cannot fire it
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

    LaunchedEffect(Unit) {
        snapshotFlow { -offset > fullAt(revealNow.value) }.distinctUntilChanged().collectLatest { armed ->
            if (armed) haptics.step()
            arm.animateTo(if (armed) 1f else 0f, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium))
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { offset > fullAt(revealLeftNow.value) }.distinctUntilChanged().collectLatest { armed ->
            if (armed) haptics.step()
            armLeft.animateTo(if (armed) 1f else 0f, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium))
        }
    }

    // the swipe hint: the closed card hops left twice, a small bounce, and the icons behind it stir
    LaunchedEffect(nudge) {
        if (nudge == 0 || open != Side.NONE || offset != 0f || anim?.isActive == true) return@LaunchedEffect
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
    val rowId = p?.id ?: NEW_SLOT_ID
    DisposableEffect(rowId, coords) { onDispose { coords.remove(rowId) } }

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = SIDE, vertical = 4.dp)
            .height(CARD_HEIGHT)
            .onSizeChanged { width = it.width }
            .onGloballyPositioned { coords[rowId] = it.boundsInWindow() }
            .pointerInput(rowId) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = viewConfiguration.touchSlop
                    // the strip the row stands open on (a card caught mid-slide counts as open)
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
                            total += ch.positionChange()
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
                            val lo = if (dragSide == Side.RIGHT) -width.toFloat() else 0f
                            val hi = if (dragSide == Side.LEFT) width.toFloat() else 0f
                            var x = offset + total.x
                            offset = x.coerceIn(lo, hi)
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                x += ch.positionChange().x
                                ch.consume()
                                offset = x.coerceIn(lo, hi)
                            }
                            val shown = if (dragSide == Side.RIGHT) -offset else offset
                            val strip = if (dragSide == Side.RIGHT) revealNow.value else revealLeftNow.value
                            when {
                                shown > fullAt(strip) -> {
                                    setOpen.value(Side.NONE)
                                    anim?.cancel()
                                    offset = 0f
                                    scope.launch { arm.snapTo(0f); armLeft.snapTo(0f) }
                                    if (dragSide == Side.RIGHT) {
                                        haptics.confirm()
                                        full.value()
                                    } else if (fullLeft.value()) {
                                        haptics.confirm()
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
                    // a touch that lands mid-hop settles the card instead of leaving it ajar
                    if (phase != 2 && openNow.value == Side.NONE && offset != 0f && anim?.isActive != true) animateTo(0f)
                }
            }
            .semantics(mergeDescendants = true) {
                selected = current
                contentDescription = if (p == null) "New profile; swipe left to save all profiles as LAST SENT"
                    else if (dim) "${p.name}, archived" else "${p.name}. Swipe left: archive, delete. Swipe right: restore to last sent, overwrite last sent."
                onClick(label = if (p == null) "Create profile" else "Select profile") { tap.value(); true }
                if (p != null) onLongClick(label = "Edit profile") { longPress.value(); true }
                customActions = (rowActions + leftActions).map { a ->
                    CustomAccessibilityAction(if (p == null) "Save all profiles as LAST SENT"
                        else a.description ?: (a.label.lowercase().replaceFirstChar { it.uppercase() } + " profile")) {
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
                .fillMaxHeight()
                .fillMaxWidth()
                .padding(end = HINT_GAP)
                // the current profile stands a step higher than the others; archived rows lie flat
                .let { if (p == null || dim) it else it.lift(CARD, if (current) Lift.RAISED else Lift.CARD) }
                .background(if (p == null) c.track else c.surface, CARD)
                // the profile on the DAC: an orange rail along the card's left edge, cut by its corners
                .let {
                    if (!onDac) it else it.clip(CARD).drawBehind {
                        drawRect(c.accent, size = Size(ON_DAC_RAIL.toPx(), size.height))
                    }
                }
                .let { if (p == null) it.sink(CARD) else it.padding(start = 20.dp, end = 22.dp) }
                .testTag(if (p == null) "empty_slot" else "profile_card"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (p == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Add, null, tint = c.textDim, modifier = Modifier.size(28.dp))
                }
                return@Row
            }
            Icon(
                ProfileIcons.of(p.icon),
                contentDescription = null,
                tint = if (current) c.accent else if (dim) c.textMute else c.textDim,
                modifier = Modifier.size(30.dp),
            )
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    p.name,
                    style = Type.rowName,
                    color = if (dim) c.textDim else c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (p.subtitle.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        p.subtitle,
                        style = Type.sub,
                        color = if (dim) c.textMute else c.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // ON DAC sits under the curve, never in the name's row: the name keeps its whole width
            Column(Modifier.width(74.dp).height(52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ResponseThumb(p.bands, if (dim) c.textMute else c.textDim, Modifier.fillMaxWidth().weight(1f))
                if (onDac) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "ON DAC",
                        style = Type.small,
                        color = c.accent,
                        maxLines = 1,
                        modifier = Modifier.testTag("row_${name}_ondac"),
                    )
                }
            }
        }
    }
}

private val ICON = 20.dp

/**
 * The row's actions behind the card, laid out from the swipe alone. At rest they are two small faint icons in
 * the strip; as the card slides, each icon travels from there to the middle of its own share of the revealed
 * width, growing and taking its colour, while the pills and labels settle in under them - everything flies
 * into place, as in Quick Settings. Past the full-action point the first action takes the whole width.
 * [offset] is how far the card has slid away from this strip (negative = revealed). [mirrored] is the strip on the
 * left edge: the same layout flipped, with nothing showing at rest (the card has no gap on that side).
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
) {
    val c = pal
    val density = LocalDensity.current
    val n = actions.size
    val g = remember(density, n, mirrored) { Geo(density, n, if (mirrored) 0.dp else HINT_GAP) }
    val hintTint = c.textMute.copy(alpha = 0.75f)
    Layout(
        modifier = modifier,
        content = {
            actions.forEachIndexed { i, a ->
                val bg = if (i == n - 1) c.text else c.surface2
                Box(
                    Modifier
                        .clip(CARD)
                        .drawBehind { drawRect(bg, alpha = g.pillAlpha(i, offset(), arm())) }
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
                        val tint = lerp(hintTint, fg, g.colour(i, o))
                        translate((size.width - s) / 2, (size.height - s) / 2) {
                            with(painter) { draw(Size(s, s), g.iconAlpha(i, o, arm()), ColorFilter.tint(tint)) }
                        }
                    },
                )
            }
            actions.forEachIndexed { i, a ->
                Text(
                    a.label,
                    style = Type.label,
                    color = if (i == n - 1) c.bg else c.text,
                    maxLines = 1,
                    modifier = Modifier.graphicsLayer {
                        val v = g.labelAlpha(i, offset(), arm())
                        alpha = v
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
            measurables[i].measure(Constraints.fixed(g.pillWidth(i, o, ar).roundToInt().coerceAtLeast(0), h))
        }
        val icons = (0 until n).map { measurables[n + it].measure(Constraints()) }
        val labels = (0 until n).map { measurables[2 * n + it].measure(Constraints()) }
        layout(w, h) {
            // laid out as for the right edge, then flipped for the left one
            fun x(v: Float) = if (mirrored) w - v else v
            for (i in 0 until n) {
                val left = g.pillLeft(i, w, o, ar)
                pills[i].place((if (mirrored) w - (left + pills[i].width) else left).roundToInt(), 0)
                val cx = x(left + pills[i].width / 2f)
                val (ix, iy) = g.iconCentre(i, w, h, o, left + pills[i].width / 2f)
                icons[i].place((x(ix) - icons[i].width / 2f).roundToInt(), (iy - icons[i].height / 2f).roundToInt())
                labels[i].place((cx - labels[i].width / 2f).roundToInt(), (h / 2f + g.labelTop).roundToInt())
            }
        }
    }
}

/** The geometry of [ActionsLayer]: everything is a function of the card's offset and the full-action arm. */
private class Geo(d: Density, val n: Int, gapDp: Dp) {
    private val gap = with(d) { gapDp.toPx() }
    private val open = with(d) { (ACTION_WIDTH * n).toPx() }
    private val inset = with(d) { 6.dp.toPx() }
    private val hintSize = with(d) { 13.dp.toPx() }
    private val iconSize = with(d) { ICON.toPx() }
    private val hintStep = with(d) { 23.dp.toPx() }
    private val raise = with(d) { 10.dp.toPx() }
    val labelTop = with(d) { 5.dp.toPx() }
    val settle = with(d) { 6.dp.toPx() }

    private fun revealed(o: Float) = gap - o.coerceAtMost(0f)
    private fun progress(o: Float) = ((revealed(o) - gap) / (open - gap)).coerceIn(0f, 1f)
    private fun rest(i: Int, ar: Float) = if (i == 0) 1f else 1f - ar

    /**
     * How far icon [i] has travelled, 0..1. The first one goes farthest, so it leaves the strip at once and is in
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
    /** With a gap the icons rest faintly in it; without one (the left strip) they only appear as the card slides off. */
    fun iconAlpha(i: Int, o: Float, ar: Float) = rest(i, ar) * if (gap > 0f) 1f else smooth(0.02f, 0.2f, progress(o))
    fun iconSize(i: Int, o: Float) = hintSize + (iconSize - hintSize) * travel(i, o)
    fun colour(i: Int, o: Float) = smooth(0.1f, 0.65f, travel(i, o))

    /** From its place in the strip (stacked, card edge) to the top half of its pill. */
    fun iconCentre(i: Int, w: Int, h: Int, o: Float, pillCentre: Float): Pair<Float, Float> {
        val t = travel(i, o)
        val hx = w - (if (gap > 0f) gap else revealed(o)) / 2 // the middle of the gap, or without one of what is revealed so far
        val hy = h / 2f + (i - (n - 1) / 2f) * hintStep
        val ty = h / 2f - raise
        return (hx + (pillCentre - hx) * t) to (hy + (ty - hy) * t)
    }

    private fun smooth(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
