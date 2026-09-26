package io.github.chronosauros.contour.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.core.WalkPlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class Link { NO_DAC, NEEDS_PERMISSION, CONNECTED }

/** Outcome of one send from the app (HOLD TO SEND). */
data class SendOutcome(val verified: Boolean, val reason: String?)

/**
 * The DAC as the UI sees it: link state, the last read (for matching), busy flag. Reads on connect and on
 * resume (read-only); writes only through [send] / the service screen's buttons, i.e. only on an
 * explicit user action. All I/O runs on DacClient's single USB thread.
 */
class DeviceController(private val context: Context, private val scope: CoroutineScope) {
    private val manager = context.getSystemService(UsbManager::class.java)
    val client = DacClient(manager)

    var link by mutableStateOf(Link.NO_DAC)
        private set
    var busy by mutableStateOf(false)
        private set
    var snapshot by mutableStateOf<DacSnapshot?>(null)
        private set
    var lastWrite by mutableStateOf<WriteResult?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    UsbLog.line("permission ${if (granted) "granted" else "denied"}")
                    refresh(read = granted)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    UsbLog.line("detached")
                    snapshot = null
                    refresh(read = false)
                }
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
        client.shutdown()
    }

    private fun findDac(): UsbDevice? = manager.deviceList.values.firstOrNull {
        it.vendorId == WalkPlay.VENDOR_ID && it.productId == WalkPlay.PRODUCT_ID
    }

    /** Recomputes the link; when connected and [read], reads the DAC (read-only). */
    fun refresh(read: Boolean) {
        val d = findDac()
        link = when {
            d == null -> Link.NO_DAC
            !manager.hasPermission(d) -> Link.NEEDS_PERMISSION
            else -> Link.CONNECTED
        }
        if (link != Link.CONNECTED) snapshot = null
        if (read && link == Link.CONNECTED) read()
    }

    /** The system permission dialog (TAP TO CONNECT). */
    fun requestPermission() {
        val d = findDac() ?: return refresh(false)
        if (manager.hasPermission(d)) return refresh(true)
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        manager.requestPermission(d, PendingIntent.getBroadcast(context, 0, intent, flags))
    }

    fun read() {
        launchOp("read") { d ->
            snapshot = client.readDevice(d)
        }
    }

    /** Writes [profile] (plan, commit, read back, compare). The result's read-back becomes the snapshot. */
    suspend fun send(profile: Profile): SendOutcome = sendWith("send") { d -> client.writeProfile(d, profile) }

    private suspend fun sendWith(what: String, op: suspend (UsbDevice) -> WriteResult): SendOutcome {
        val d = findDac() ?: run { link = Link.NO_DAC; snapshot = null; return SendOutcome(false, "NO DAC") }
        if (!manager.hasPermission(d)) {
            link = Link.NEEDS_PERMISSION
            snapshot = null
            return SendOutcome(false, "NO PERMISSION")
        }
        if (busy) return SendOutcome(false, "DAC BUSY")
        busy = true
        return try {
            val r = op(d)
            lastWrite = r
            snapshot = r.readBack
            error = null
            if (r.verified) SendOutcome(true, null) else SendOutcome(false, "READ-BACK MISMATCH: ${r.mismatches.firstOrNull() ?: ""}")
        } catch (e: Exception) {
            UsbLog.line("$what failed: ${e.message}")
            snapshot = null // a partial write or failed read cannot establish what the DAC holds
            error = e.message
            SendOutcome(false, e.message ?: e.javaClass.simpleName)
        } finally {
            busy = false
        }
    }

    // ---- service screen (the v0.1 debug buttons; only the owner presses them) ----------------------------

    fun serviceTestWrite() = launchOp("test write") { d -> showWrite(client.sendTestBand1(d)) }
    fun serviceRestoreFlat() = launchOp("restore flat") { d -> showWrite(client.restoreFlat(d)) }

    private fun showWrite(r: WriteResult) {
        lastWrite = r
        snapshot = r.readBack
    }

    private fun launchOp(what: String, op: suspend (UsbDevice) -> Unit) {
        val d = findDac() ?: run { link = Link.NO_DAC; snapshot = null; return }
        if (!manager.hasPermission(d)) { link = Link.NEEDS_PERMISSION; snapshot = null; return }
        if (busy) return
        busy = true
        scope.launch {
            try {
                op(d)
                error = null
            } catch (e: Exception) {
                UsbLog.line("$what failed: ${e.message}")
                snapshot = null
                error = "$what failed: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "io.github.chronosauros.contour.USB_PERMISSION"
    }
}
