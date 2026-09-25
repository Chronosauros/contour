package io.github.chronosauros.contour.ui.kit

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Anchor
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChangeHistory
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.Cyclone
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Profile icons: the stored names (the 20 used so far, kept as they are in the files) mapped to Material
 * icons. An unknown name shows the circle.
 */
object ProfileIcons {
    val ALL: List<Pair<String, ImageVector>> = listOf(
        "moon" to Icons.Outlined.DarkMode,
        "in-ear" to Icons.Outlined.Hearing,
        "headphones" to Icons.Outlined.Headphones,
        "wave" to Icons.Outlined.GraphicEq,
        "leaf" to Icons.Outlined.Eco,
        "mountain" to Icons.Outlined.Landscape,
        "sun" to Icons.Outlined.WbSunny,
        "flame" to Icons.Outlined.LocalFireDepartment,
        "drop" to Icons.Outlined.WaterDrop,
        "star" to Icons.Outlined.StarOutline,
        "bolt" to Icons.Outlined.Bolt,
        "circle" to Icons.Outlined.Circle,
        "triangle" to Icons.Outlined.ChangeHistory,
        "note" to Icons.Outlined.MusicNote,
        "speaker" to Icons.Outlined.Speaker,
        "snowflake" to Icons.Outlined.AcUnit,
        "coffee" to Icons.Outlined.Coffee,
        "eye" to Icons.Outlined.Visibility,
        "anchor" to Icons.Outlined.Anchor,
        "spiral" to Icons.Outlined.Cyclone,
    )
    private val MAP = ALL.toMap()

    fun of(name: String): ImageVector = MAP[name] ?: Icons.Outlined.Circle
}
