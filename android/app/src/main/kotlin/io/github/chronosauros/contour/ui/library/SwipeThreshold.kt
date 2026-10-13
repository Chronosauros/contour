package io.github.chronosauros.contour.ui.library

/** A settled strip has no drag start. Dragging an open strip inward cannot arm its full action.
 * [fullAt] is the row's full-action point (SwipeRow.fullAt: 55 % of the row, past the open strip by a margin). */
internal fun fullSwipeArmed(shown: Float, fullAt: Float, dragStart: Float?): Boolean =
    dragStart != null && shown > dragStart && shown > fullAt
