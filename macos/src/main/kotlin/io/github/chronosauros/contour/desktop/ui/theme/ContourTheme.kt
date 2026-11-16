package io.github.chronosauros.contour.desktop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Desktop dark palette: deep indigo surfaces with cyan and magenta accents. */
object ContourColors {
    val Background = Color(0xFF090814)
    val Surface = Color(0xFF130F26)
    val SurfaceElevated = Color(0xFF1C163A)
    val SurfaceBorder = Color(0xFF382A6E)
    val SurfaceBorderGlow = Color(0xFF5D43B2)

    val NeonCyan = Color(0xFF00F0FF)
    val NeonPink = Color(0xFFFF007F)
    val NeonPurple = Color(0xFFB967FF)
    val NeonViolet = Color(0xFF8A2BE2)
    val NeonAmber = Color(0xFFFF8A00)
    val NeonGreen = Color(0xFF05FFA1)
    val NeonYellow = Color(0xFFFFE600)

    val Primary = NeonPink
    val PrimaryDim = Color(0xFFC70062)
    val Secondary = NeonCyan
    val AccentOrange = NeonAmber
    val AccentGreen = NeonGreen
    val AccentRed = Color(0xFFFF2A55)

    val TextPrimary = Color(0xFFFBF8FF)
    val TextSecondary = Color(0xFFB8B0D6)
    val TextMuted = Color(0xFF756A99)

    val GridLine = Color(0xFF1E173E)
    val GridLineMajor = Color(0xFF342668)
    val ZeroLine = Color(0x99FF007F)
    val PreampLine = Color(0xFFFF8A00)

    val CurveStroke = NeonCyan
    val CurveGlow = Color(0x66FF007F)
    val CurveFillTop = Color(0x35FF007F)
    val CurveFillMid = Color(0x1F9D4EDD)
    val CurveFillBottom = Color(0x0200F0FF)

    val NodeNormal = NeonCyan
    val NodeSelected = NeonYellow
    val NodeFill = Color(0xFF130F26)

    val VaporwaveGradient = Brush.horizontalGradient(
        listOf(NeonCyan, NeonPurple, NeonPink)
    )
    val SunsetGradient = Brush.horizontalGradient(
        listOf(NeonAmber, NeonPink, NeonPurple)
    )
    val NeonCyanToPink = Brush.horizontalGradient(
        listOf(NeonCyan, NeonPink)
    )
    val CardGradient = Brush.verticalGradient(
        listOf(SurfaceElevated, Surface)
    )
}

val ContourDarkColorScheme = darkColorScheme(
    primary = ContourColors.Primary,
    background = ContourColors.Background,
    surface = ContourColors.Surface,
    onPrimary = Color.Black,
    onBackground = ContourColors.TextPrimary,
    onSurface = ContourColors.TextPrimary,
    surfaceVariant = ContourColors.SurfaceElevated,
    onSurfaceVariant = ContourColors.TextSecondary,
    outline = ContourColors.SurfaceBorder
)

@Composable
fun ContourTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ContourDarkColorScheme,
        content = content
    )
}
