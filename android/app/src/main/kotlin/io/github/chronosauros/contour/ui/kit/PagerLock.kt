package io.github.chronosauros.contour.ui.kit

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** Counts the touches holding a value control (slider, PREAMP, graph); the pages do not swipe while any is held. */
class PagerLock {
    private var held by mutableIntStateOf(0)
    val locked: Boolean get() = held > 0
    fun hold() { held++ }
    fun release() { if (held > 0) held-- }
}

val LocalPagerLock = staticCompositionLocalOf { PagerLock() }

/**
 * From the first finger down on this element until the last finger up (or a cancel), the pager stays put.
 * Taken on the Initial pass at touch-down, so it is on before either the control or the pager reaches its slop.
 */
fun Modifier.holdsPager(lock: PagerLock): Modifier = pointerInput(lock) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        lock.hold()
        try {
            do {
                val e = awaitPointerEvent(PointerEventPass.Final)
            } while (e.changes.any { it.pressed })
        } finally {
            lock.release()
        }
    }
}
