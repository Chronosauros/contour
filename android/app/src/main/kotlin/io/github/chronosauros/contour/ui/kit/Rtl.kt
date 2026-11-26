package io.github.chronosauros.contour.ui.kit

import android.content.res.Configuration
import android.icu.text.DecimalFormatSymbols
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import java.util.Locale

/*
 * A phone whose system language is written right to left (Arabic, Hebrew, Persian, Urdu) mirrors Contour's layout; the words stay English
 * (Chinese in the cn edition). What mirrors and what keeps its left-to-right direction (Reddit request 16.11.2026, decisions in
 * _Projects/contour-refs/rtl-2026-11-16/, Material Design "Bidirectionality"):
 * - mirrored: the page order and the page bar, rows and their swipes, headers, lists, choice rows, sheets, HOLD TO SEND's fill, and the
 *   value sliders ([MIRROR_VALUE_SLIDERS]);
 * - left to right: the response graph and the row thumbnails (charts and graphs are not mirrored), the band strip under it (band 1 on the
 *   left, as on the graph), every number with its unit; BALANCE is physical (left ear, right ear) and never mirrors.
 */

/**
 * The value sliders (FREQ, GAIN, Q and PREAMP on the EQ page, the level rows of the DAC page) on a right-to-left phone: true = they mirror like
 * Android's own volume sliders (the fill grows from the right edge, next to the value, and a drag to the left raises it); false = they run left to
 * right as everywhere else, the way the graph's frequency axis does. Mirrored: the owner's choice at the review of 16.11.2026 ("suwaki też mirror").
 */
const val MIRROR_VALUE_SLIDERS = true

/** +1 when a finger moving right raises a value slider, -1 when it lowers it (a mirrored slider on a right-to-left phone). */
@Composable
@ReadOnlyComposable
fun sliderSign(): Float = if (MIRROR_VALUE_SLIDERS && LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f

/** +1, or -1 on a right-to-left layout: turns a finger's x movement into the layout's own direction (what [androidx.compose.ui.Modifier.offset] moves by). */
@Composable
@ReadOnlyComposable
fun layoutSign(): Float = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f

/** Where a value slider's fill starts: its low end, the left edge (or the start edge while sliders mirror). */
val SliderFillStart: Alignment = if (MIRROR_VALUE_SLIDERS) Alignment.TopStart else AbsoluteAlignment.TopLeft

/** Places a value slider's fill (laid out inside a track as wide as the slider) at its low end, see [SliderFillStart]. */
fun Placeable.PlacementScope.placeFill(fill: Placeable) = if (MIRROR_VALUE_SLIDERS) fill.placeRelative(0, 0) else fill.place(0, 0)

/**
 * The system's locales with Latin digits (the Unicode extension nu-latn), or null when the first one already writes 0-9. An Arabic or Persian
 * phone formats the numbers of the app's own texts (BAND %1$d, PROFILE %1$d, SLOT %1$d) in its digits, so BAND 1 read "BAND ١" under an English
 * word, next to a graph, values and DAC data written 0-9. The language and the right-to-left layout stay the system's.
 */
fun latinDigitLocales(config: Configuration): LocaleList? {
    val list = config.locales
    if (list.isEmpty) return null
    val first = list[0]
    if (runCatching { DecimalFormatSymbols.getInstance(first).zeroDigit == '0' }.getOrDefault(true)) return null
    val latin = (0 until list.size()).map { i ->
        val l = list[i]
        runCatching { Locale.Builder().setLocale(l).setUnicodeLocaleKeyword("nu", "latn").build() }.getOrDefault(l)
    }
    return LocaleList(*latin.toTypedArray())
}
