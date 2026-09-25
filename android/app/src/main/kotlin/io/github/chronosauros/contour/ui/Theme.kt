package io.github.chronosauros.contour.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Phase 2 look, variant A (Figma "Contour - design", collection "Contour colors"): soft tonal blocks, big type,
 * one accent. Depth gives the hierarchy: cards sit on the page, controls sit higher, HOLD TO SEND highest,
 * tracks and the empty slot are pressed in. No highlights or glow - shadows only.
 */
@Immutable
data class Palette(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val track: Color,
    val fill: Color,
    val text: Color,
    val textDim: Color,
    val textMute: Color,
    val grid: Color,
    val accent: Color,
    val onAccent: Color,
    val sunken: Color,
    val shadow: Color,
    /** Shadow strength for this palette (dark pages need darker shadows to read at all). */
    val shadowAlpha: Float,
)

val DarkPalette = Palette(
    bg = Color(0xFF171615), surface = Color(0xFF242220), surface2 = Color(0xFF2E2B29),
    track = Color(0xFF3A3734), fill = Color(0xFFD9D5D0),
    text = Color(0xFFF3F0EC), textDim = Color(0xFFA39E98), textMute = Color(0xFF77726C),
    grid = Color(0xFF34312E), accent = Color(0xFFF07A3A), onAccent = Color(0xFF2B1A10),
    sunken = Color(0xFF121110), shadow = Color(0xFF000000), shadowAlpha = 1f,
)

val LightPalette = Palette(
    bg = Color(0xFFEFECE8), surface = Color(0xFFFAF8F5), surface2 = Color(0xFFE7E3DE),
    track = Color(0xFFE2DDD7), fill = Color(0xFF3A3633),
    text = Color(0xFF1D1B19), textDim = Color(0xFF6E6963), textMute = Color(0xFF9A948D),
    grid = Color(0xFFE9E5E0), accent = Color(0xFFF07A3A), onAccent = Color(0xFF2B1A10),
    sunken = Color(0xFFE6E2DD), shadow = Color(0xFF2B2118), shadowAlpha = 0.3f,
)

/** The accent, for the few places that do not read the palette. */
val Accent = DarkPalette.accent

val LocalPalette = staticCompositionLocalOf { DarkPalette }

/** The palette of the current theme. */
val pal: Palette
    @Composable @ReadOnlyComposable get() = LocalPalette.current

/** Levels of the depth hierarchy. */
enum class Lift { CARD, RAISED, HERO }

private fun liftShadow(level: Lift, p: Palette): Shadow {
    val (r, y, a) = when (level) {
        Lift.CARD -> Triple(10f, 3f, 0.42f)
        Lift.RAISED -> Triple(6f, 2f, 0.55f)
        Lift.HERO -> Triple(14f, 5f, 0.55f)
    }
    return Shadow(radius = r.dp, color = p.shadow, offset = DpOffset(0.dp, y.dp), alpha = a * p.shadowAlpha)
}

/** A soft shadow under the element, per [level]. Put it before `background` / `clip`. */
@Composable
fun Modifier.lift(shape: Shape, level: Lift = Lift.CARD): Modifier = dropShadow(shape, liftShadow(level, pal))

/** Pressed-in: a soft shadow inside the top edge. Put it after `background`. */
@Composable
fun Modifier.sink(shape: Shape): Modifier {
    val p = pal
    return innerShadow(shape, Shadow(radius = 5.dp, color = p.shadow, offset = DpOffset(0.dp, 2.dp), alpha = 0.55f * p.shadowAlpha))
}

/** Google Sans from the Pixel (no bundled font); the system sans elsewhere. */
@OptIn(ExperimentalTextApi::class)
val Display = FontFamily(
    Font(DeviceFontFamilyName("google-sans"), FontWeight.Normal),
    Font(DeviceFontFamilyName("google-sans"), FontWeight.Medium),
    Font(DeviceFontFamilyName("google-sans"), FontWeight.SemiBold),
    Font(DeviceFontFamilyName("google-sans"), FontWeight.Bold),
)

/** The type roles of the look (sizes from Figma, in sp). */
object Type {
    private fun s(size: Int, w: FontWeight, ls: TextUnit = 0.em) =
        TextStyle(fontFamily = Display, fontSize = size.sp, fontWeight = w, letterSpacing = ls, fontFeatureSettings = "tnum")

    val screenTitle = s(38, FontWeight.Bold, (-0.01).em)
    val profileTitle = s(30, FontWeight.Bold, (-0.01).em)
    val count = s(26, FontWeight.SemiBold)
    val value = s(26, FontWeight.SemiBold, (-0.01).em)
    val chip = s(20, FontWeight.SemiBold)
    val rowName = s(18, FontWeight.Bold, (-0.015).em)
    val button = s(17, FontWeight.Bold, 0.04.em)
    val segment = s(13, FontWeight.SemiBold, 0.06.em)
    val label = s(12, FontWeight.SemiBold, 0.08.em)
    val sub = s(11, FontWeight.Medium, 0.14.em)
    val small = s(10, FontWeight.SemiBold, 0.08.em)
    val graph = s(11, FontWeight.Medium)
    val node = s(12, FontWeight.Bold)
}

private fun typography(): Typography {
    val t = Typography()
    fun TextStyle.f() = copy(fontFamily = Display)
    return Typography(
        displayLarge = t.displayLarge.f(), displayMedium = t.displayMedium.f(), displaySmall = t.displaySmall.f(),
        headlineLarge = t.headlineLarge.f(), headlineMedium = t.headlineMedium.f(), headlineSmall = t.headlineSmall.f(),
        titleLarge = t.titleLarge.f(), titleMedium = t.titleMedium.f(), titleSmall = t.titleSmall.f(),
        bodyLarge = t.bodyLarge.f(), bodyMedium = t.bodyMedium.f(), bodySmall = t.bodySmall.f(),
        labelLarge = t.labelLarge.f(), labelMedium = t.labelMedium.f(), labelSmall = t.labelSmall.f(),
    )
}

/** Stock Material components (sheets, text fields, snackbar, dialogs) take their colors from the palette. */
private fun scheme(p: Palette, dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = p.text, onPrimary = p.bg,
        primaryContainer = p.surface2, onPrimaryContainer = p.text,
        secondary = p.textDim, onSecondary = p.bg,
        secondaryContainer = p.surface2, onSecondaryContainer = p.text,
        tertiary = p.textDim, onTertiary = p.bg,
        background = p.bg, onBackground = p.text,
        surface = p.bg, onSurface = p.text,
        surfaceVariant = p.surface2, onSurfaceVariant = p.textDim,
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = p.sunken, surfaceContainerLow = p.surface,
        surfaceContainer = p.surface, surfaceContainerHigh = p.surface2,
        surfaceContainerHighest = p.track,
        inverseSurface = p.text, inverseOnSurface = p.bg, inversePrimary = p.accent,
        outline = p.textMute, outlineVariant = p.track,
    )
} else {
    lightColorScheme(
        primary = p.text, onPrimary = p.surface,
        primaryContainer = p.surface2, onPrimaryContainer = p.text,
        secondary = p.textDim, onSecondary = p.surface,
        secondaryContainer = p.surface2, onSecondaryContainer = p.text,
        tertiary = p.textDim, onTertiary = p.surface,
        background = p.bg, onBackground = p.text,
        surface = p.bg, onSurface = p.text,
        surfaceVariant = p.surface2, onSurfaceVariant = p.textDim,
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = p.surface, surfaceContainerLow = p.surface,
        surfaceContainer = p.surface, surfaceContainerHigh = p.surface2,
        surfaceContainerHighest = p.track,
        inverseSurface = p.text, inverseOnSurface = p.bg, inversePrimary = p.accent,
        outline = p.textMute, outlineVariant = p.track,
    )
}

private val TYPOGRAPHY = typography()

@Composable
fun ContourTheme(dark: Boolean, content: @Composable () -> Unit) {
    val p = if (dark) DarkPalette else LightPalette
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme(p, dark), typography = TYPOGRAPHY, content = content)
    }
}

/** Tabular numerals for every value. */
val Tabular = TextStyle(fontFeatureSettings = "tnum")
