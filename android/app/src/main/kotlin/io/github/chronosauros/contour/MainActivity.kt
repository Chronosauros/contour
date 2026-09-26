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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MainActivity : ComponentActivity() {
    companion object {
        private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var processModel: AppModel? = null
    }
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
        // Backup failure blocks loading and all writes; retry loading retries the copy first.
        val appContext = applicationContext
        model = processModel ?: AppModel(ProfileStore(filesDir), persistenceScope) {
            Backup.beforeV1(appContext)
        }.also { processModel = it; it.load() }
        device = DeviceController(this, lifecycleScope)
        sender = Sender(model, device, lifecycleScope)
        device.start()
        applyReviewExtras(intent, first = true)

        setContent {
            LaunchedEffect(model.loading) {
                if (!model.loading && !model.loadError) applyReviewExtras(intent, first = false)
            }
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
     * No mutating or exporting intent extras: this activity is exported for USB attachment.
     */
    private fun applyReviewExtras(i: Intent?, first: Boolean) {
        if (i == null || !reviewBuild || model.loadError) return
        when (i.getStringExtra("theme")) {
            "light" -> forcedDark = false
            "dark" -> forcedDark = true
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
