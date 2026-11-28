package io.github.chronosauros.contour.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.shadow.DropShadowPainter
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.shadow.ShadowContext
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Density
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

/**
 * [lift] for an element whose height moves: the Library block, which parts into cards in REORDER and glues back, and grows and shrinks
 * as rows come and go. Compose keeps one shadow bitmap per size (see [ShadowSteps]) and the block is the whole list, so every height of
 * the part / glue animation kept a bitmap of several MB (five REORDER toggles: +420 MB on the emulator). Here one bitmap of a fixed
 * height is drawn as its top end, its middle stretched to the height and its bottom end: the middle of a blurred rounded rectangle is
 * the same on every row, so this is Compose's own shadow at any height. Any other shape, or an element too low to cut, is drawn as [lift].
 */
@Composable
fun Modifier.liftAnyHeight(shape: Shape, level: Lift = Lift.CARD): Modifier {
    val shadow = liftShadow(level, pal)
    val context = LocalGraphicsContext.current.shadowContext
    val painter = remember(context, shape, shadow) { context.createDropShadowPainter(shape, shadow) }
    return drawBehind {
        val w = size.width
        val h = size.height
        val corner = (shape.createOutline(size, layoutDirection, this) as? Outline.Rounded)?.roundRect?.let {
            maxOf(it.topLeftCornerRadius.y, it.topRightCornerRadius.y, it.bottomLeftCornerRadius.y, it.bottomRightCornerRadius.y)
        }
        // each end holds the corner, the blur (twice the radius covers its three sigma) and the offset; whole pixels, so the parts meet
        // without a seam
        val end = if (corner == null) 0f else ceil(corner + 2f * shadow.radius.toPx() + abs(shadow.offset.y.toPx()) + 2.dp.toPx())
        val ref = 2f * end + ceil(8.dp.toPx())
        if (corner == null || h <= ref) {
            with(painter) { draw(size) }
            return@drawBehind
        }
        val refSize = Size(w, ref)
        val far = 4f * end // beyond the shadow on every side
        clipRect(-far, -far, w + far, end) { with(painter) { draw(refSize) } }
        clipRect(-far, end, w + far, h - end) {
            withTransform({
                translate(0f, end)
                scale(1f, (h - 2f * end) / (ref - 2f * end), Offset.Zero)
                translate(0f, -end)
            }) { with(painter) { draw(refSize) } }
        }
        clipRect(-far, h - end, w + far, h + far) { translate(0f, h - ref) { with(painter) { draw(refSize) } } }
    }
}

/**
 * Pressed-in: a soft shadow inside the top edge. Put it after `background`. Every pressed-in surface (slider
 * tracks, the type well, the page bar, a disabled band, the empty slot, the switch) is [Palette.track] under this.
 */
@Composable
fun Modifier.sink(shape: Shape, strength: Float = 1f): Modifier {
    val p = pal
    return innerShadow(shape, Shadow(radius = 5.dp, color = p.shadow, offset = DpOffset(0.dp, 2.dp), alpha = 0.55f * strength * p.shadowAlpha))
}

/**
 * Compose draws every shadow into a bitmap of its own, one per shape, size and [Shadow] (radius, alpha, colour; androidx
 * ShadowContext), and keeps all of them until the system trims a background app's memory - which never came before the app was
 * cached. A shadow whose radius or alpha changes every frame therefore kept a new bitmap per frame: the breathing REORDER cards of
 * 1.8.1 held about 1 GB after a minute (Google Play, 26.11.2026: "Bitmap memory usage in your app's cached state exceeds the bad
 * behavior threshold", P99 2 GB). An animated shadow goes through [ShadowSteps] and [steppedShadow] instead, and [ContourTheme]
 * lets the kept bitmaps go when the app is hidden.
 */
class ShadowSteps internal constructor(private val context: ShadowContext, private val shape: Shape, private val color: Color) {
    private val painters = HashMap<Int, DropShadowPainter>()

    /** The radius snaps to [SHADOW_STEP_DP]: one bitmap per step and size, however the animation moves. */
    fun painter(radiusDp: Float): DropShadowPainter {
        val step = (radiusDp / SHADOW_STEP_DP).roundToInt().coerceAtLeast(0)
        return painters.getOrPut(step) { context.createDropShadowPainter(shape, Shadow(radius = (step * SHADOW_STEP_DP).dp, color = color)) }
    }
}

/** Half a dp of blur radius - below what the eye tells apart in a breathing shadow. */
private const val SHADOW_STEP_DP = 0.5f

@Composable
fun rememberShadowSteps(shape: Shape, color: Color): ShadowSteps {
    val context = LocalGraphicsContext.current.shadowContext
    return remember(context, shape, color) { ShadowSteps(context, shape, color) }
}

/**
 * The animated twin of `dropShadow(shape) { radius; offset; alpha }`: the same shadow, read every frame, but the alpha and the
 * offset are applied when the step's bitmap is drawn (they are not part of it), so the animation reuses a handful of bitmaps.
 */
fun Modifier.steppedShadow(steps: ShadowSteps, radiusDp: () -> Float, offsetYDp: () -> Float, alpha: () -> Float): Modifier = drawBehind {
    val a = alpha().coerceAtMost(1f)
    if (a <= 0f) return@drawBehind
    val painter = steps.painter(radiusDp())
    translate(top = offsetYDp().dp.toPx()) { with(painter) { draw(size, alpha = a) } }
}

/** Lets Compose's kept shadow bitmaps go as soon as the app is hidden (Google Play: release UI bitmaps on ON_STOP); they are drawn again on return. */
@Composable
private fun ReleaseShadowsWhenHidden() {
    val shadows = LocalGraphicsContext.current.shadowContext
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { shadows.clearCache() }
}

/** Google Sans from the Pixel (no bundled font); the system sans elsewhere. */
@OptIn(ExperimentalTextApi::class)
val Display = FontFamily(
    Font(DeviceFontFamilyName("google-sans"), FontWeight.Normal),
    Font(DeviceFontFamilyName("google-sans"), FontWeight.Medium),
    Font(DeviceFontFamilyName("google-sans"), FontWeight.SemiBold),
    Font(DeviceFontFamilyName("google-sans"), FontWeight.Bold),
)

/**
 * The direction of every paragraph: its own text's, left to right when it has no letters. Without it a right-to-left phone (Arabic, Hebrew) set
 * every paragraph right to left - English sentences ended with the full stop at their start, "2 750" read "750 2", "+12" read "12+" - while a
 * profile name typed in Hebrew or Arabic still runs right to left (Material Design: text is always in the direction of its language).
 */
val TEXT_DIRECTION = TextDirection.ContentOrLtr

/** The type roles of the look (sizes from Figma, in sp). */
object Type {
    private fun s(size: Int, w: FontWeight, ls: TextUnit = 0.em) =
        TextStyle(fontFamily = Display, fontSize = size.sp, fontWeight = w, letterSpacing = ls, fontFeatureSettings = "tnum", textDirection = TEXT_DIRECTION)

    val screenTitle = s(38, FontWeight.Bold, (-0.01).em)
    val profileTitle = s(30, FontWeight.Bold, (-0.01).em)
    /** The number in a value card. */
    val value = s(18, FontWeight.SemiBold, 0.02.em)
    /** FREQ / GAIN / Q / PREAMP over the number: about half of it. */
    val paramLabel = s(7, FontWeight.SemiBold, 0.08.em)
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
    fun TextStyle.f() = copy(fontFamily = Display, textDirection = TEXT_DIRECTION)
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
    ReleaseShadowsWhenHidden()
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme(p, dark), typography = TYPOGRAPHY, content = content)
    }
}

/** The look is laid out for phones at least this wide (the common 411 dp; the Pixel 9 Pro is 427 dp). */
const val DESIGN_WIDTH_DP = 411f
/** System text up to this scale fits the fixed rows; anything larger stops here. */
const val MAX_FONT_SCALE = 1.15f

/**
 * A narrower or zoomed screen (Samsung display zoom, a small phone) gets the same layout scaled down as a whole
 * instead of rows that cut names, chips and segments; larger system text stops at [MAX_FONT_SCALE].
 */
fun fitDensity(screen: Density, widthPx: Int): Density = Density(
    density = if (widthPx > 0) minOf(screen.density, widthPx / DESIGN_WIDTH_DP) else screen.density,
    fontScale = minOf(screen.fontScale, MAX_FONT_SCALE),
)

/** Tabular numerals for every value. */
val Tabular = TextStyle(fontFeatureSettings = "tnum", textDirection = TEXT_DIRECTION)

/**
 * The one layout grid: the page margin, the gap between neighbouring blocks (and between a row's buttons), the
 * height of every control row (bands, filter type, values, PREAMP, HOLD TO SEND, the page bar, sheet buttons)
 * and the inset of a control inside its well (the type pill, the slider, the page-bar knob).
 */
object Grid {
    val SIDE = 16.dp
    val GAP = 8.dp
    val ROW = 56.dp
    val INSET = 4.dp
    /** Text inside an L-radius card starts this far from its edge: past the curve of the corner. */
    val TEXT = 24.dp
    /** Air around the send button: it stands apart from the controls above and the page bar below. */
    val GROUP = 16.dp
}

/** The one corner scale. L = anything that stands on the page, M = inside an L (4 dp inset) or a small control, S = tap areas and tags. */
object Radii {
    val L = 20.dp
    val M = 16.dp
    val S = 12.dp
}
