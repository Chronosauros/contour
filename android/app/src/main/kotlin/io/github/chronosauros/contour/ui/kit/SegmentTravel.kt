package io.github.chronosauros.contour.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlin.math.abs

/**
 * The end of a segmented control's pair (design: `_Projects/contour-refs/haptics-research-2026-11-14/claude/haptics-report-and-design.md`, "Projekt:
 * segmenty, zakładki i strony", round 5). The press that picks a new cell plays [Haptics.segStart]; this plays [Haptics.segEnd], the same CLICK for every
 * control and every direction, when the sliding indicator [at] (its cell position) has arrived at [target]. Only a move a press started is felt (armed by
 * [Haptics.segStart]): a band picked on the graph, or a slot the DAC reports, moves the indicator in silence.
 */
@Composable
fun SegmentTravel(at: State<Float>, target: Int) {
    val haptics = LocalHaptics.current
    val targetNow = rememberUpdatedState(target)
    LaunchedEffect(Unit) {
        var rested = at.value
        snapshotFlow { at.value }.collect { v ->
            val goal = targetNow.value.toFloat()
            if (abs(v - goal) <= ARRIVED && abs(goal - rested) >= 0.5f) {
                if (haptics.segArmed()) haptics.segEnd()
                rested = goal
            }
        }
    }
}

/** The animation of a float state ends within this of its target (visibility threshold 0.01). */
private const val ARRIVED = 0.012f
