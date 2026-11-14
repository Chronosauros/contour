package io.github.chronosauros.contour.ui.kit

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Calls [onPress] at the touch-down on this element, not at the release: `clickable` fires on the release, but the start of a segment's pair
 * belongs to the press (haptics round 5). Watches the initial pass and consumes nothing, so the element's own click, long press and drag work as before.
 * [onPress] decides whether this press starts anything (a new cell, not the selected one).
 */
@Composable
fun Modifier.pressStart(enabled: Boolean = true, onPress: () -> Unit): Modifier {
    val action = rememberUpdatedState(onPress)
    return if (!enabled) this else pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            action.value()
        }
    }
}
