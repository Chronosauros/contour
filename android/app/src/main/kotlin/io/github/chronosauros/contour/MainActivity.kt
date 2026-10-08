package io.github.chronosauros.contour

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import io.github.chronosauros.contour.core.ApoText
import io.github.chronosauros.contour.model.AppModel
import io.github.chronosauros.contour.model.Backup
import io.github.chronosauros.contour.model.ImportFile
import io.github.chronosauros.contour.model.Incoming
import io.github.chronosauros.contour.model.Page
import io.github.chronosauros.contour.model.ProfileStore
import io.github.chronosauros.contour.model.Sender
import io.github.chronosauros.contour.ui.ContourApp
import io.github.chronosauros.contour.ui.ContourTheme
import io.github.chronosauros.contour.ui.fitDensity
import io.github.chronosauros.contour.ui.sheets.Sheet
import io.github.chronosauros.contour.ui.tune.Param
import io.github.chronosauros.contour.usb.DeviceController
import io.github.chronosauros.contour.usb.UsbLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    companion object {
        private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var processModel: AppModel? = null
        private var pendingAbCleanup: Job? = null
        private const val KEY_SAVE_ID = "saveTxt.profileId"
        private const val KEY_IN_URI = "incoming.uri"
        private const val KEY_IN_TEXT = "incoming.text"
        private const val KEY_IN_SUBJECT = "incoming.subject"
    }
    private lateinit var model: AppModel
    private lateinit var device: DeviceController
    private lateinit var sender: Sender

    /** Theme override from the launch intent (debug and perf builds only): null = follow the system. */
    private var forcedDark by mutableStateOf<Boolean?>(null)
    private var sheetRequest by mutableStateOf<Sheet?>(null)
    /** A .txt opened with or shared to Contour: parsed and waiting for the user's yes (the shell shows the dialog). */
    private var incoming by mutableStateOf<Incoming?>(null)
    /** Screen emulation from the launch intent (perf and debug only): width in dp and font scale, null = the real screen. */
    private var emulate by mutableStateOf<Pair<Float, Float>?>(null)
    private var initialPage = Page.LIBRARY

    /**
     * SAVE .TXT. The system file dialog's answer must reach code that is always there: after Android kills the process
     * while the dialog is open, no sheet is restored, but the Activity is, and with it this callback and the profile id.
     */
    private var pendingSaveId: String? = null
    private val saveTxt = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> finishSaveTxt(uri) }

    private fun startSaveTxt(profileId: String, fileName: String) {
        pendingSaveId = profileId
        saveTxt.launch(fileName)
    }

    private fun finishSaveTxt(uri: Uri?) {
        val id = pendingSaveId
        pendingSaveId = null
        if (uri == null) return
        val resolver = contentResolver
        val app = applicationContext
        persistenceScope.launch {
            // after process death the library is still loading: wait for it, "not loaded yet" is not "gone"
            snapshotFlow { model.loading }.first { !it }
            val text = id?.let(model::byId)?.let { runCatching { ApoText.fileText(it) }.getOrNull() }
            val ok = text != null && withContext(Dispatchers.IO) {
                runCatching { resolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) } }.isSuccess
            }
            // the dialog has already created an empty file: never leave it behind
            if (!ok) withContext(Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(resolver, uri) } }
            Toast.makeText(app, if (ok) "FILE SAVED" else "COULD NOT SAVE THE FILE", Toast.LENGTH_SHORT).show()
        }
    }

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
        // A/B restoration must survive ON_STOP and Activity destruction long enough to release HID.
        sender = Sender(model, device, lifecycleScope, persistenceScope)
        device.start()
        applyReviewExtras(intent, first = true)
        // Not again when the system recreates the Activity and hands it the launch intent a second time.
        pendingSaveId = savedInstanceState?.getString(KEY_SAVE_ID)
        if (savedInstanceState == null) readIncoming(intent) else restoreIncoming(savedInstanceState)

        setContent {
            LaunchedEffect(model.loading) {
                if (!model.loading && !model.loadError) applyReviewExtras(intent, first = false)
            }
            val dark = forcedDark ?: isSystemInDarkTheme()
            val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            LaunchedEffect(dark) { enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style) }
            val widthPx = LocalWindowInfo.current.containerSize.width
            val screen = emulate?.let { (widthDp, fontScale) -> Density(widthPx / widthDp, fontScale) } ?: LocalDensity.current
            CompositionLocalProvider(LocalDensity provides fitDensity(screen, widthPx)) {
                ContourTheme(dark) {
                    ContourApp(
                        model, device, sender, initialPage, sheetRequest, onSheetRequestTaken = { sheetRequest = null },
                        incoming = incoming, onIncomingDone = { incoming = null },
                        onSaveTxt = ::startSaveTxt,
                    )
                }
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
        // `--es emulate 320x1.3`: a narrow / zoomed phone (width in dp, font scale); `--es emulate off` = the real screen
        i.getStringExtra("emulate")?.let { e ->
            val parts = e.split("x")
            emulate = parts.getOrNull(0)?.toFloatOrNull()?.let { w -> w to (parts.getOrNull(1)?.toFloatOrNull() ?: 1f) }
        }
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
            refreshDevice()
        }
        applyReviewExtras(intent, first = false)
        readIncoming(intent)
    }

    /**
     * Open with Contour (VIEW) and Share to Contour (SEND) of a text file or text. This Activity is exported, so nothing
     * is created here: the file is only parsed, and the user confirms in a dialog. No storage permission: the URI
     * carries the read grant.
     */
    private fun readIncoming(i: Intent?) {
        if (i?.action != Intent.ACTION_VIEW && i?.action != Intent.ACTION_SEND) return
        val resolver = contentResolver
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { ImportFile.fromIntent(resolver, i) }
            if (result != null) incoming = result
        }
    }

    /** The dialog was open when the Activity was recreated: read the same source again (never one the user already answered). */
    private fun restoreIncoming(state: Bundle) {
        val uri = state.getString(KEY_IN_URI)
        val text = state.getString(KEY_IN_TEXT)
        if (uri == null && text == null) return
        val resolver = contentResolver
        val subject = state.getString(KEY_IN_SUBJECT)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (uri != null) ImportFile.fromUri(resolver, Uri.parse(uri)) else ImportFile.fromText(text, subject)
            }
            // after process death the read grant may be gone: then there is nothing to ask about
            if (result.eq != null) incoming = result
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingSaveId?.let { outState.putString(KEY_SAVE_ID, it) }
        incoming?.takeIf { it.eq != null }?.let {
            outState.putString(KEY_IN_URI, it.uri)
            // a Bundle is small; an unusually long shared text is simply dropped
            if (it.uri == null && (it.text?.length ?: 0) <= 100_000) {
                outState.putString(KEY_IN_TEXT, it.text)
                outState.putString(KEY_IN_SUBJECT, it.subject)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        sender.onResume()
        refreshDevice()
    }

    private fun refreshDevice() {
        // A recreated Activity must not read B before the old one restores A and releases HID.
        val cleanup = pendingAbCleanup
        lifecycleScope.launch {
            cleanup?.join()
            if (pendingAbCleanup === cleanup) pendingAbCleanup = null
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) device.refresh(read = true)
        }
    }

    override fun onStop() {
        sender.onStop()
        model.flush()
        super.onStop()
    }

    override fun onDestroy() {
        val previous = pendingAbCleanup
        pendingAbCleanup = sender.leaveAb { device.stop() } ?: previous?.takeIf { it.isActive }
        super.onDestroy()
    }
}
