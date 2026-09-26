package io.github.chronosauros.contour.ui.library

import androidx.compose.animation.core.animate
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
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
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.model.AppModel
import io.github.chronosauros.contour.model.Sender
import io.github.chronosauros.contour.ui.DeviceStatus
import io.github.chronosauros.contour.ui.Lift
import io.github.chronosauros.contour.ui.Type
import io.github.chronosauros.contour.ui.lift
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
    fun archive(p: Profile)
    fun restore(p: Profile)
    fun delete(p: Profile)
    fun service()
    fun licences()
}

private val CARD_HEIGHT = 76.dp
private val ACTION_WIDTH = 96.dp
private val SIDE = 14.dp
private val CARD = RoundedCornerShape(20.dp)

/**
 * Library: choosing and managing profiles only - it never sends anything to the DAC.
 * Tap = current + Tune, long-press = edit sheet, swipe left = ARCHIVE / DELETE (RESTORE / DELETE in the archive),
 * the empty slot after the active rows = new profile, then the collapsible archive.
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
) {
    val c = pal
    val listState = rememberLazyListState()
    var openId by remember { mutableStateOf<String?>(null) }
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
                    Modifier.fillMaxWidth().height(72.dp).padding(start = SIDE + 6.dp, end = SIDE),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("LIBRARY", style = Type.screenTitle, color = c.text, modifier = Modifier.alignByBaseline())
                    Spacer(Modifier.width(14.dp))
                    Text(
                        "${active.size}",
                        style = Type.count,
                        color = c.textMute,
                        modifier = Modifier.alignByBaseline().testTag("library_count"),
                    )
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
                    open = openId == p.id,
                    onOpen = { openId = if (it) p.id else if (openId == p.id) null else openId },
                    onTap = { model.choose(p.id) },
                    onLongPress = { actions.edit(p.id) },
                    fullAction = { actions.archive(p) },
                    rowActions = listOf(
                        RowAction("ARCHIVE", "archive") { actions.archive(p) },
                        RowAction("DELETE", "delete") { actions.delete(p) },
                    ),
                    coords = rowCoords,
                )
            }
            item(key = "empty-slot") {
                // the empty slot is pressed into the page: a place waiting for a profile
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SIDE, vertical = 4.dp)
                        .height(64.dp)
                        .clip(CARD)
                        .background(c.sunken)
                        .sink(CARD)
                        .clickable { openId = null; actions.newProfile() }
                        .semantics { contentDescription = "empty slot" }
                        .testTag("empty_slot"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Add, null, tint = c.textDim, modifier = Modifier.size(28.dp))
                }
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
                            open = openId == p.id,
                            onOpen = { openId = if (it) p.id else if (openId == p.id) null else openId },
                            onTap = { model.choose(p.id) },
                            onLongPress = { actions.edit(p.id) },
                            fullAction = { actions.restore(p) },
                            rowActions = listOf(
                                RowAction("RESTORE", "restore") { actions.restore(p) },
                                RowAction("DELETE", "delete") { actions.delete(p) },
                            ),
                            coords = rowCoords,
                            dim = true,
                        )
                    }
                }
            }
            item(key = "licences") {
                // the way to the open-source notices, always visible at the foot of the library
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp)
                        .height(48.dp)
                        .clickable { openId = null; actions.licences() }
                        .testTag("licences"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("ABOUT & LICENCES", style = Type.small, color = c.textMute)
                }
            }
        }
    }
}

class RowAction(val label: String, val tag: String, val run: () -> Unit)

/**
 * One library row. Its own gesture is a LEFTWARD swipe (reveal the actions, past 55 % = the full action)
 * and a tap / long-press; a rightward swipe on a closed row is left to the pager (-> Tune).
 */
@Composable
private fun SwipeRow(
    p: Profile,
    current: Boolean,
    onDac: Boolean,
    open: Boolean,
    onOpen: (Boolean) -> Unit,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    fullAction: () -> Unit,
    rowActions: List<RowAction>,
    coords: HashMap<String, Rect>,
    dim: Boolean = false,
) {
    val c = pal
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val reveal = with(density) { (ACTION_WIDTH * rowActions.size).toPx() }
    var width by remember { mutableIntStateOf(1) }
    var offset by remember { mutableFloatStateOf(0f) }
    var anim by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun animateTo(target: Float) {
        anim?.cancel()
        anim = scope.launch { animate(offset, target) { v, _ -> offset = v } }
    }

    LaunchedEffect(open) {
        if (!open && offset != 0f) animateTo(0f)
        if (open && offset != -reveal) animateTo(-reveal)
    }

    val openNow = rememberUpdatedState(open)
    val tap = rememberUpdatedState(onTap)
    val longPress = rememberUpdatedState(onLongPress)
    val full = rememberUpdatedState(fullAction)
    val setOpen = rememberUpdatedState(onOpen)
    val name = p.name
    DisposableEffect(p.id, coords) { onDispose { coords.remove(p.id) } }

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = SIDE, vertical = 4.dp)
            .height(CARD_HEIGHT)
            .onSizeChanged { width = it.width }
            .onGloballyPositioned { coords[p.id] = it.boundsInWindow() }
            .pointerInput(p.id) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = viewConfiguration.touchSlop
                    val wasOpen = openNow.value || offset < -1f
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
                                return@withTimeoutOrNull if (horizontal && (total.x < 0 || wasOpen)) {
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
                        1 -> if (wasOpen) setOpen.value(false) else tap.value()
                        2 -> {
                            anim?.cancel()
                            var x = offset + total.x
                            offset = x.coerceIn(-width.toFloat(), 0f)
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                x += ch.positionChange().x
                                ch.consume()
                                offset = x.coerceIn(-width.toFloat(), 0f)
                            }
                            when {
                                -offset > 0.55f * width -> {
                                    haptics.confirm()
                                    setOpen.value(false)
                                    anim?.cancel()
                                    offset = 0f
                                    full.value()
                                }
                                -offset > reveal / 2 -> {
                                    setOpen.value(true)
                                    animateTo(-reveal)
                                }
                                else -> {
                                    setOpen.value(false)
                                    animateTo(0f)
                                }
                            }
                        }
                        else -> Unit
                    }
                }
            }
            .semantics(mergeDescendants = true) {
                selected = current
                contentDescription = if (dim) "${p.name}, archived" else p.name
                onClick(label = "Select profile") { tap.value(); true }
                onLongClick(label = "Edit profile") { longPress.value(); true }
                customActions = rowActions.map { a ->
                    CustomAccessibilityAction(a.label.lowercase().replaceFirstChar { it.uppercase() } + " profile") {
                        setOpen.value(false)
                        a.run()
                        true
                    }
                }
            }
            .testTag("row_$name"),
    ) {
        if (offset < -1f) {
            Row(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rowActions.forEachIndexed { i, a ->
                    val last = i == rowActions.lastIndex
                    Box(
                        Modifier
                            .width(ACTION_WIDTH - 6.dp)
                            .fillMaxHeight()
                            .clip(CARD)
                            .background(if (last) c.text else c.surface2)
                            .clickable {
                                setOpen.value(false)
                                offset = 0f
                                a.run()
                            }
                            .testTag("row_${name}_${a.tag}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(a.label, style = Type.label, color = if (last) c.bg else c.text)
                    }
                }
            }
        }
        Row(
            Modifier
                .offset { IntOffset(offset.roundToInt(), 0) }
                .fillMaxSize()
                // the current profile stands a step higher than the others; archived rows lie flat
                .let { if (dim) it else it.lift(CARD, if (current) Lift.RAISED else Lift.CARD) }
                .background(c.surface, CARD)
                .padding(start = 20.dp, end = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            if (onDac) {
                Text(
                    "ON DAC",
                    style = Type.small,
                    color = c.textDim,
                    modifier = Modifier
                        .background(c.surface2, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("row_${name}_ondac"),
                )
                Spacer(Modifier.width(12.dp))
            }
            ResponseThumb(p.bands, if (dim) c.textMute else c.textDim, Modifier.width(74.dp).height(32.dp))
        }
    }
}
