package io.github.chronosauros.contour

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.github.chronosauros.contour.core.Band
import io.github.chronosauros.contour.core.FilterType
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.model.AppModel
import io.github.chronosauros.contour.model.Backup
import io.github.chronosauros.contour.model.Page
import io.github.chronosauros.contour.model.ProfileStore
import io.github.chronosauros.contour.model.Sender
import io.github.chronosauros.contour.ui.ContourApp
import io.github.chronosauros.contour.ui.ContourTheme
import io.github.chronosauros.contour.ui.sheets.Sheet
import io.github.chronosauros.contour.ui.tune.Param
import io.github.chronosauros.contour.usb.DeviceController
import io.github.chronosauros.contour.usb.UsbLog
import kotlinx.serialization.json.Json

private const val TEST_NAME = "TEST"
private const val TEST_SUB = "AUTOMATION TEST"

class MainActivity : ComponentActivity() {
    private lateinit var model: AppModel
    private lateinit var device: DeviceController
    private lateinit var sender: Sender

    /** Theme override from the launch intent (debug and perf builds only): null = follow the system. */
    private var forcedDark by mutableStateOf<Boolean?>(null)
    private var sheetRequest by mutableStateOf<Sheet?>(null)
    private var initialPage = Page.LIBRARY

    private val reviewBuild: Boolean
        get() = packageName.endsWith(".perf") || (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before loading or writing anything: the one-time copy of the owner's library (brief v1, Owner's data).
        Backup.beforeV1(this)
        model = AppModel(ProfileStore(filesDir), lifecycleScope)
        model.load()
        device = DeviceController(this, lifecycleScope)
        sender = Sender(model, device, lifecycleScope)
        device.start()
        applyReviewExtras(intent, first = true)

        setContent {
            val dark = forcedDark ?: isSystemInDarkTheme()
            val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            LaunchedEffect(dark) { enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style) }
            ContourTheme(dark) {
                ContourApp(model, device, sender, initialPage, sheetRequest, onSheetRequestTaken = { sheetRequest = null })
            }
        }
    }

    /**
     * Review hooks for screenshots and scripted checks (perf and debug builds only, never release):
     * `--es theme light|dark`, `--es screen tune|library`, `--es sheet edit|new|value|band`,
     * `--es bench create|delete`: create = always a NEW profile "TEST" (subtitle AUTOMATION TEST, fresh id, the
     *   4 benchmark bands), it never edits or replaces an existing profile; delete = removes only profiles named
     *   TEST with that subtitle. (The owner's own BENCH profile, tuned by hand on 25.09, is never touched.)
     * `--es demo load`: adds every profile file from .json files in external files/demo (the store's own JSON) as a
     *   NEW profile, skipping names the library already has (README screenshots); `--es demo replace` first
     *   deletes the library's profiles with those names (restoring the owner's own tunings).
     * `--es dump profiles` (the library files as they are on disk -> external files/backup/dump-<time>).
     */
    private fun applyReviewExtras(i: Intent?, first: Boolean) {
        if (i == null || !reviewBuild) return
        when (i.getStringExtra("dump")) {
            "profiles" -> Backup.dump(this)
        }
        when (i.getStringExtra("theme")) {
            "light" -> forcedDark = false
            "dark" -> forcedDark = true
        }
        when (i.getStringExtra("bench")) {
            "create" -> {
                model.create(
                    listOf(
                        Band("b", FilterType.LOW_SHELF, 105.0, 3.0, 0.71),
                        Band("b", FilterType.PEAK, 1000.0, 0.0, 1.0),
                        Band("b", FilterType.PEAK, 6207.0, -3.0, 3.9),
                        Band("b", FilterType.HIGH_SHELF, 10000.0, 2.0, 0.71),
                    ),
                    null, name = TEST_NAME, sub = TEST_SUB, open = false,
                )
                model.selectBand(1)
            }
            "delete" -> removeTest()
        }
        when (i.getStringExtra("demo")) {
            "load" -> loadDemo(replace = false)
            "replace" -> loadDemo(replace = true)
        }
        val page = when (i.getStringExtra("screen")) {
            "tune" -> Page.TUNE
            "library" -> Page.LIBRARY
            else -> null
        }
        val sheet = when (i.getStringExtra("sheet")) {
            "edit" -> model.currentId?.let { Sheet.Edit(it) }
            "new" -> Sheet.New
            "value" -> Sheet.Value(Param.FREQ)
            "band" -> Sheet.BandActions(model.selectedBand)
            else -> null
        }
        val sheetPage = when (sheet) {
            is Sheet.Value, is Sheet.BandActions -> Page.TUNE
            Sheet.New -> Page.LIBRARY
            else -> null
        }
        (page ?: sheetPage)?.let { if (first) initialPage = it else model.pageRequest = it }
        if (sheet != null) sheetRequest = sheet
    }

    private fun loadDemo(replace: Boolean) {
        val json = Json { ignoreUnknownKeys = true }
        getExternalFilesDir("demo")?.listFiles().orEmpty().filter { it.name.endsWith(".json") }.sortedBy { it.name }.forEach { f ->
            val p = runCatching { json.decodeFromString(Profile.serializer(), f.readText()) }.getOrNull() ?: return@forEach
            if (replace) model.profiles.filter { it.name == p.name }.map { it.id }.forEach { model.delete(it); model.finishDelete(it) }
            if (model.profiles.none { it.name == p.name }) model.create(p.bands, p.preampDb, p.name, p.subtitle, p.icon, open = false)
        }
    }

    private fun removeTest() {
        model.profiles.filter { it.name == TEST_NAME && it.subtitle == TEST_SUB }.map { it.id }.forEach {
            model.delete(it)
            model.finishDelete(it)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            UsbLog.line("attached")
            device.refresh(read = true)
        }
        applyReviewExtras(intent, first = false)
    }

    override fun onResume() {
        super.onResume()
        device.refresh(read = true)
    }

    override fun onStop() {
        model.flush()
        super.onStop()
    }

    override fun onDestroy() {
        device.stop()
        super.onDestroy()
    }
}
