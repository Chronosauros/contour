package io.github.chronosauros.contour.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.chronosauros.contour.BuildConfig
import io.github.chronosauros.contour.R

/**
 * The Google Play board of the APK builds (GitHub, Obtainium, F-Droid, a sideload). Contour 1.8.0 goes to production on Google Play; the owner (14.11.2026) wanted
 * a board "visibly different" from the closed-beta invitation, or users simply skip it, shown "only once, at the first start after the update and never again".
 * So: exactly once per install - the first start of the app where [KEY_SHOWN] (prefs `ui`) is not set - counted at once, so a recreated Activity or a crash does not
 * show it twice. Old beta keys play no part. Never on an install from Google Play (the installer of record), never by itself in a review build (perf, debug: the
 * review hook `--es sheet playbeta` shows it on demand). To be removed a week after 1.8.0 or in the next update (Desk task 5c4e1cad).
 */
object PlayBeta {
    /** The store page itself (production release, 1.8.0): Google Play opens it in the Play Store app when it is installed. The address is the resource `play_beta_play_url`. */
    private const val PLAY_STORE = "com.android.vending"
    private const val KEY_SHOWN = "playStoreBoardShown"

    /** True when the board should show now: marks it shown at once. */
    fun takeShowing(context: Context, prefs: SharedPreferences): Boolean {
        if (reviewBuild(context) || chineseBuild() || fromPlay(context)) return false
        if (prefs.getBoolean(KEY_SHOWN, false)) return false
        prefs.edit().putBoolean(KEY_SHOWN, true).apply()
        return true
    }

    /** The Chinese edition ("cn" build type) points to Google Play, which is not there: never shown. */
    private fun chineseBuild(): Boolean = BuildConfig.BUILD_TYPE == "cn"

    private fun reviewBuild(context: Context): Boolean =
        context.packageName.endsWith(".perf") || (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Installed (or last updated) by Google Play: the user already has the Play build. An unknown installer counts as not Play. */
    private fun fromPlay(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) pm.getInstallSourceInfo(context.packageName).installingPackageName
        else @Suppress("DEPRECATION") pm.getInstallerPackageName(context.packageName)
        installer == PLAY_STORE
    }.getOrDefault(false)

    /** The version as major.minor ("1.8") from the build's version name: the patch and any suffix ("-perf") are left off. */
    fun majorMinor(versionName: String): String = Regex("""^(\d+)\.(\d+)""").find(versionName)?.let { "${it.groupValues[1]}.${it.groupValues[2]}" } ?: versionName
}

/**
 * Full screen over the app (sketch B "1.8", owner 14.11.2026): the version as a huge orange numeral with the Google Play mark at its lower right, the title, three
 * things that are new, one line about updates, and the two buttons. Back closes it like NOT NOW.
 */
@Composable
fun PlayBetaBoard(onClose: () -> Unit) {
    val context = LocalContext.current
    val version = remember { PlayBeta.majorMinor(BuildConfig.VERSION_NAME) }
    BackHandler(onBack = onClose)
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
        // The numeral takes the free height and the full width (autoSize: it never clips on a narrow phone); the Play tile sits at its lower right.
        Box(Modifier.fillMaxWidth().weight(1f)) {
            BasicText(
                version, Modifier.align(Alignment.CenterStart).padding(end = 8.dp),
                style = TextStyle(fontFamily = HERO, fontWeight = FontWeight.Black, color = pal.accent, letterSpacing = (-0.045).em, lineHeight = 1.em),
                maxLines = 1, softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = 80.sp, maxFontSize = 320.sp, stepSize = 2.sp),
            )
            Box(
                Modifier.align(Alignment.BottomEnd).size(52.dp).clip(RoundedCornerShape(16.dp)).background(MARK_TILE),
                contentAlignment = Alignment.Center,
            ) {
                // the arrow's mass sits left of its box: 2 dp to the right centres it to the eye
                Image(painterResource(R.drawable.ic_google_play), contentDescription = stringResource(R.string.play_beta_google_play_description), Modifier.padding(start = 2.dp).size(width = 27.dp, height = 29.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        BasicText(
            stringResource(R.string.play_beta_title, version), Modifier.fillMaxWidth(), style = Type.screenTitle.copy(color = pal.text), maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = Type.screenTitle.fontSize, stepSize = 1.sp),
        )
        Spacer(Modifier.height(20.dp))
        NewRow(Icons.Rounded.Usb, stringResource(R.string.play_beta_new_dacs))
        Spacer(Modifier.height(11.dp))
        NewRow(Icons.Rounded.Vibration, stringResource(R.string.play_beta_new_haptics))
        Spacer(Modifier.height(11.dp))
        NewRow(Icons.Rounded.PowerSettingsNew, stringResource(R.string.play_beta_new_popup))
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.play_beta_text),
            Modifier.fillMaxWidth(), style = Type.value.copy(fontSize = 16.sp, lineHeight = 24.sp), color = pal.textDim,
        )
        Spacer(Modifier.height(24.dp))
        val playUrl = stringResource(R.string.play_beta_play_url)
        AppButton(stringResource(R.string.play_beta_open), {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(playUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            onClose()
        }, Modifier.fillMaxWidth().testTag("play_beta_join"), primary = true)
        Spacer(Modifier.height(Grid.GAP))
        AppButton(stringResource(R.string.play_beta_not_now), onClose, Modifier.fillMaxWidth().testTag("play_beta_later"))
    }
}

/** One thing that is new: a small rounded tile with an orange icon, then the line. */
@Composable
private fun NewRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(pal.surface2), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, Modifier.size(22.dp), tint = pal.accent)
        }
        Spacer(Modifier.width(14.dp))
        Text(text, style = Type.value.copy(fontSize = 18.sp, fontWeight = FontWeight.Medium), color = pal.text, maxLines = 1)
    }
}

/** The display family at its heaviest: the device's Google Sans in Black (Bold where the device has nothing heavier), as the numeral of the sketch. */
private val HERO = FontFamily(
    Font(DeviceFontFamilyName("google-sans"), FontWeight.Black),
    Font(DeviceFontFamilyName("google-sans"), FontWeight.Bold),
)

/** The tile of the Google Play mark, in both themes: the dark page's surface2 (the Play colours read on it as in the sketch). */
private val MARK_TILE = DarkPalette.surface2
