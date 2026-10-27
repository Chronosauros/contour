package io.github.chronosauros.contour.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.chronosauros.contour.R
import io.github.chronosauros.contour.ui.kit.LocalHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * The Google Play closed-beta invitation of the APK builds (GitHub, Obtainium, F-Droid, a sideload; owner 27.10.2026, full-screen look C): at a start of the app, at most
 * once in [INTERVAL_MS] and [MAX_SHOWS] times in all; "Tap here" in the small print ends it for good. Never on an install from Google Play (the installer of record), never
 * by itself in a review build (perf, debug: the review hook `--es sheet playbeta` shows it). Remove or rewrite it when the closed test ends.
 */
object PlayBeta {
    /** contoureq.app: the home page introduces Contour and its "Join the Google Play test" button leads to the testers group (owner: send the traffic to the site). */
    const val SITE_URL = "https://contoureq.app/?utm_source=contour-app&utm_medium=play-beta-board"
    const val MAX_SHOWS = 3
    const val INTERVAL_MS = 3L * 24 * 60 * 60 * 1000
    private const val PLAY_STORE = "com.android.vending"
    private const val KEY_SHOWS = "playBetaShows"
    private const val KEY_LAST = "playBetaLastShownMs"
    private const val KEY_NEVER = "playBetaNever"

    /** Shown at most [MAX_SHOWS] times, [INTERVAL_MS] apart, until "Tap here". A clock set back counts as time passed, so it cannot hold the board back for good. */
    fun due(shows: Int, lastShownMs: Long, never: Boolean, nowMs: Long): Boolean =
        !never && shows < MAX_SHOWS && (shows == 0 || nowMs - lastShownMs >= INTERVAL_MS || nowMs < lastShownMs)

    /** True when the board should show now: counts the showing at once, so a recreated Activity or a crash does not show it twice. */
    fun takeShowing(context: Context, prefs: SharedPreferences, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (reviewBuild(context) || fromPlay(context)) return false
        val shows = prefs.getInt(KEY_SHOWS, 0)
        if (!due(shows, prefs.getLong(KEY_LAST, 0L), prefs.getBoolean(KEY_NEVER, false), nowMs)) return false
        prefs.edit().putInt(KEY_SHOWS, shows + 1).putLong(KEY_LAST, nowMs).apply()
        return true
    }

    fun never(prefs: SharedPreferences) = prefs.edit().putBoolean(KEY_NEVER, true).apply()

    private fun reviewBuild(context: Context): Boolean =
        context.packageName.endsWith(".perf") || (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Installed (or last updated) by Google Play: the user already has the Play build. An unknown installer counts as not Play. */
    private fun fromPlay(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) pm.getInstallSourceInfo(context.packageName).installingPackageName
        else @Suppress("DEPRECATION") pm.getInstallerPackageName(context.packageName)
        installer == PLAY_STORE
    }.getOrDefault(false)
}

/** What the Soundblooms face shows: dots, a blink, "^ ^" arcs, or flat lines (the NOT NOW face, as the annoyed eyes on soundblooms.com). */
private enum class Mood { CALM, HAPPY, FLAT }

/**
 * Full screen over the app: the Contour and Google Play marks, the Soundblooms face, the invitation. The face says hello with "^ ^", blinks and sways while it waits,
 * smiles when JOIN THE TEST opens contoureq.app or when it is tapped, and flattens its eyes and drops its band on NOT NOW and "Tap here" before the board closes.
 */
@Composable
fun PlayBetaBoard(onClose: () -> Unit, onNever: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    var mood by remember { mutableStateOf(Mood.HAPPY) }
    var leaving by remember { mutableStateOf(false) }
    val happy = remember { Animatable(1f) }
    val flat = remember { Animatable(0f) }
    val open = remember { Animatable(1f) }
    val tilt = remember { Animatable(0f) }
    val hop = remember { Animatable(0f) }

    LaunchedEffect(mood) {
        launch { happy.animateTo(if (mood == Mood.HAPPY) 1f else 0f, tween(280)) }
        launch { flat.animateTo(if (mood == Mood.FLAT) 1f else 0f, tween(220)) }
        when (mood) {
            Mood.FLAT -> tilt.animateTo(-16f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow))
            Mood.HAPPY -> {
                hop.animateTo(-1f, tween(140)); hop.animateTo(0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow))
            }
            Mood.CALM -> tilt.animateTo(0f, spring(Spring.DampingRatioMediumBouncy))
        }
    }
    // Hello: "^ ^" for a moment, then the calm face that blinks now and then.
    LaunchedEffect(Unit) {
        delay(1400)
        if (!leaving && mood == Mood.HAPPY) mood = Mood.CALM
        while (true) {
            delay(Random.nextLong(2200, 4200))
            if (mood == Mood.CALM) { open.animateTo(0.08f, tween(90)); open.animateTo(1f, tween(140)) }
        }
    }
    val sway by rememberInfiniteTransition(label = "sway").animateFloat(-3f, 3f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "band")

    fun leave(face: Mood, wait: Long, then: () -> Unit) {
        if (leaving) return
        leaving = true
        mood = face
        scope.launch { delay(wait); then() }
    }

    BackHandler(onBack = { leave(Mood.FLAT, 650, onClose) })
    Column(
        Modifier
            .fillMaxSize()
            .background(pal.bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {} // nothing under the board takes a touch
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(Grid.SIDE)
            .testTag("play_beta_board"),
    ) {
        // One centre axis and one spacing scale (multiples of 8 dp): the Soundblooms mark centred in the free space on top; then three groups - the two app marks,
        // the message (title, text, thanks) and the actions - each further from the next than its own parts are from each other.
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            SoundbloomsMark(
                fontSize = 128.sp, ink = pal.text, open = open.value, happy = happy.value, flat = flat.value, tilt = tilt.value + if (mood == Mood.CALM) sway else 0f, hop = hop.value,
                modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    if (!leaving) { haptics.tap(); scope.launch { mood = Mood.HAPPY; delay(1100); if (!leaving) mood = Mood.CALM } }
                }.testTag("play_beta_face"),
            )
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            ContourMark(MARK)
            PlusMark(pal.textMute, Modifier.padding(horizontal = 16.dp))
            Box(Modifier.size(MARK).clip(RoundedCornerShape(MARK / 4)).background(MARK_TILE), contentAlignment = Alignment.Center) { // the same tile as the Contour mark: two equal squares
                // the arrow's mass sits left of its box: 3 dp to the right centres it to the eye
                Image(painterResource(R.drawable.ic_google_play), contentDescription = "Google Play", Modifier.padding(start = 3.dp).size(width = 34.dp, height = 37.dp))
            }
        }
        Spacer(Modifier.height(32.dp))
        BasicText(
            "Contour is on Google Play.", Modifier.fillMaxWidth(), style = Type.screenTitle.copy(color = pal.text, textAlign = TextAlign.Center), maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = Type.screenTitle.fontSize, stepSize = 1.sp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Contour is now on the Google Play Store as a closed beta. I'd be grateful if you could join the test. The app is free.",
            Modifier.fillMaxWidth(), style = Type.value.copy(fontSize = 18.sp, lineHeight = 26.sp, lineBreak = LineBreak.Heading, textAlign = TextAlign.Center), color = pal.textDim, // balanced lines: no word left alone on a line
        )
        Spacer(Modifier.height(8.dp))
        Text("Thank you!", Modifier.fillMaxWidth(), style = Type.value.copy(fontSize = 18.sp, textAlign = TextAlign.Center), color = pal.accent)
        Spacer(Modifier.height(32.dp))
        AppButton("JOIN THE TEST", {
            leave(Mood.HAPPY, 450) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PlayBeta.SITE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                onClose()
            }
        }, Modifier.fillMaxWidth().testTag("play_beta_join"), primary = true)
        Spacer(Modifier.height(Grid.GAP))
        AppButton("NOT NOW", { leave(Mood.FLAT, 850, onClose) }, Modifier.fillMaxWidth().testTag("play_beta_later"))
        Spacer(Modifier.height(8.dp))
        val small = buildAnnotatedString {
            append("This window shows up ${PlayBeta.MAX_SHOWS} times at most.\nDon't want to see it again? ")
            withStyle(SpanStyle(color = pal.accent, textDecoration = TextDecoration.Underline)) { append("Tap here.") }
        }
        // 8 dp above and below the two lines: a finger-sized target for "Tap here" without pushing the text away from the buttons
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.S)).clickable { leave(Mood.FLAT, 850, onNever) }.padding(vertical = 8.dp).testTag("play_beta_never")) {
            Text(small, Modifier.fillMaxWidth(), style = Type.graph.copy(lineHeight = 16.sp, textAlign = TextAlign.Center), color = pal.textMute)
        }
    }
}

/** The Contour launcher icon: the EQ curve with the orange node on [MARK_TILE]. */
@Composable
private fun ContourMark(size: Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(MARK_TILE)) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = "Contour", Modifier.size(size).scale(1.25f)) // the 72 dp safe zone of the 108 dp icon, with a margin to the tile edge
    }
}

/**
 * The Soundblooms mark of soundblooms.com as the mascot: "d", the face, "b" in Geom 500 - the face between the two letters like a head between headphone cups
 * (skill soundblooms-mark: the face is 0.62 x 1 em with 0.05 em at each side, its bottom on the baseline; band 0.56 x 0.085 em with its bottom 0.6 em up; eyes 0.14 em,
 * 0.15 em apart, their bottom 0.24 em up; flat eyes 0.28 x 0.105 em, 0.22 em wide here so the two lines keep a gap at this size; arcs "^ ^").
 * [open] blinks (eye height), [happy] crossfades dots to arcs, [flat] stretches them into lines, [tilt] turns the band in degrees, [hop] lifts the face (-1 = up).
 */
@Composable
private fun SoundbloomsMark(fontSize: TextUnit, ink: Color, open: Float, happy: Float, flat: Float, tilt: Float, hop: Float, modifier: Modifier = Modifier) {
    val style = TextStyle(fontFamily = GEOM, fontWeight = FontWeight.Medium, fontSize = fontSize, color = ink)
    val faceWidth = with(LocalDensity.current) { (fontSize * 0.72f).toDp() } // 0.62 em + 0.05 em at each side
    val faceHeight = with(LocalDensity.current) { fontSize.toDp() }
    Row(modifier) {
        Text("d", style = style, modifier = Modifier.alignByBaseline())
        Canvas(Modifier.size(faceWidth, faceHeight).alignBy { it.measuredHeight }) {
            val em = this.size.height
            val cx = this.size.width / 2
            translate(top = hop * em * 0.08f) {
                val bw = em * 0.56f; val bh = em * 0.085f; val by = em - em * 0.6f - bh
                rotate(tilt, pivot = Offset(cx, by + bh / 2)) {
                    drawRoundRect(ink, Offset(cx - bw / 2, by), Size(bw, bh), CornerRadius(bh / 2))
                }
                val eyeY = em - em * 0.24f - em * 0.07f
                for (side in listOf(-1f, 1f)) {
                    val ex = cx + side * em * 0.145f
                    val dots = 1f - happy
                    if (dots > 0.01f) {
                        val w = em * (0.14f + 0.08f * flat)
                        val h = em * (0.14f - 0.035f * flat) * open
                        drawRoundRect(ink.copy(alpha = dots), Offset(ex - w / 2, eyeY - h / 2), Size(w, h), CornerRadius(minOf(w, h) / 2))
                    }
                    if (happy > 0.01f) {
                        val aw = em * 0.245f; val ah = em * 0.189f
                        val x0 = ex - aw / 2; val y0 = eyeY - ah / 2
                        scale(0.8f + 0.2f * happy, pivot = Offset(ex, eyeY)) {
                            val path = Path().apply {
                                moveTo(x0 + aw * 3 / 22, y0 + ah * 13 / 17)
                                quadraticTo(x0 + aw * 11 / 22, y0 + ah * 2.6f / 17, x0 + aw * 19 / 22, y0 + ah * 13 / 17)
                            }
                            drawPath(path, ink.copy(alpha = happy), style = Stroke(width = aw * 4.6f / 22, cap = StrokeCap.Round))
                        }
                    }
                }
            }
        }
        Text("b", style = style, modifier = Modifier.alignByBaseline())
    }
}

/** Geom 500, the Soundblooms wordmark font (SIL OFL 1.1, THIRD_PARTY_NOTICES.md section 3). */
private val GEOM = FontFamily(Font(R.font.geom_500, FontWeight.Medium))

/** The "+" between the two app marks, drawn so it sits on their centre line (a "+" glyph sits on the font's math axis, below the box centre). */
@Composable
private fun PlusMark(ink: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(16.dp)) {
        val s = 2.5.dp.toPx(); val c = size.width / 2
        drawLine(ink, Offset(c, s / 2), Offset(c, size.height - s / 2), s, StrokeCap.Round)
        drawLine(ink, Offset(s / 2, c), Offset(size.width - s / 2, c), s, StrokeCap.Round)
    }
}

/** The size of the two app-mark tiles over the title. */
private val MARK = 64.dp
/** Both app-mark tiles, in both themes: the dark page's surface2. The Contour icon is drawn for a dark tile (its curve is light), and the launcher's own #201E1C vanishes on the dark page. */
private val MARK_TILE = DarkPalette.surface2
