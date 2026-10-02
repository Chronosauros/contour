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
import kotlinx.coroutines.Job
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

    /** Last hardware-volume read or write (USB Audio Class Feature Unit); null until read. */
    var volume by mutableStateOf<UacVolume.State?>(null)
        private set

    /** snapshot remains the A reference during B; it must not be replaced by a bypass read. */
    var abBypassed by mutableStateOf(false)
        private set
    private var abReference: DacSnapshot? = null
    private var connectionEpoch = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    UsbLog.line("permission ${if (granted) "granted" else "denied"}")
                    refresh(read = granted)
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    // The app may be open while the system dialog is dismissed or handled elsewhere: TAP TO CONNECT.
                    if (!isDac(intent)) return
                    UsbLog.line("attached (broadcast)")
                    refresh(read = true)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (!isDac(intent)) return
                    UsbLog.line("detached")
                    connectionEpoch++
                    abBypassed = false
                    abReference = null
                    snapshot = null
                    volume = null
                    refresh(read = false)
                }
            }
        }
    }

    private fun isDac(intent: Intent): Boolean {
        val d = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        return d?.vendorId == WalkPlay.VENDOR_ID && d.productId == WalkPlay.PRODUCT_ID
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
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
        if (link != Link.CONNECTED) {
            connectionEpoch++
            snapshot = null
            abBypassed = false
            abReference = null
        }
        if (read && link == Link.CONNECTED && !abBypassed) read()
    }

    /**
     * Tap on NO DAC / the status pill: looks for the DAC again (it may have been plugged in without an attach
     * event reaching the app), then asks for USB permission or reads it. Returns false when there is none.
     */
    fun connect(): Boolean {
        refresh(read = true)
        if (link == Link.NEEDS_PERMISSION) requestPermission()
        return link != Link.NO_DAC
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
        if (abBypassed) return
        launchOp("read") { d ->
            snapshot = client.readDevice(d)
        }
    }

    /** Writes [profile] (plan, commit, read back, compare). The result's read-back becomes the snapshot. */
    suspend fun send(profile: Profile): SendOutcome = sendWith("send") { d -> client.writeProfile(d, profile) }

    /** Toggle against the actual A registers, not the editor's rounded or emulated band values. */
    suspend fun switchAb(bypass: Boolean): SendOutcome {
        if (bypass == abBypassed) return SendOutcome(true, null)
        val reference = (if (bypass) snapshot else abReference) ?: return forgetAb("DAC STATE UNKNOWN")
        val d = findDac() ?: run {
            abBypassed = false; abReference = null; snapshot = null; link = Link.NO_DAC
            return SendOutcome(false, "NO DAC")
        }
        if (!manager.hasPermission(d)) { link = Link.NEEDS_PERMISSION; return forgetAb("NO PERMISSION") }
        if (busy) return forgetAb("DAC BUSY")
        val epoch = connectionEpoch
        val bands = reference.bands.map { it.registers }.filter { it.gain256 != 0 }.map { r ->
            WalkPlay.BandWrite(r.index, r.freq.toDouble(), if (bypass) 0.0 else r.gain256 / 256.0, r.q256 / 256.0, r.typeCode)
        }
        busy = true
        return try {
            client.writeTemporaryBands(d, bands, reference.slot)
            if (epoch != connectionEpoch) return SendOutcome(false, "DAC DETACHED")
            abBypassed = bypass
            abReference = if (bypass) reference else null
            error = null
            UsbLog.line("A/B: ${if (bypass) "B — EQ OFF, PREAMP KEPT" else "A restored"}")
            SendOutcome(true, null)
        } catch (e: Exception) {
            UsbLog.line("A/B failed: ${e.message}")
            if (epoch != connectionEpoch) SendOutcome(false, "DAC DETACHED")
            else forgetAb(e.message ?: e.javaClass.simpleName)
        } finally {
            busy = false
        }
    }

    private fun forgetAb(reason: String): SendOutcome {
        abBypassed = false
        abReference = null
        snapshot = null // partial switch: UI returns to A, but the DAC state is unknown, not ON DAC
        error = reason
        return SendOutcome(false, reason)
    }

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

    /** Reads the hardware volume. May briefly interrupt audio when the kernel does not share the control interface. */
    fun readVolume() = launchOp("volume read", keepSnapshot = true) { d -> volume = client.readVolume(d) }

    /** Sets the hardware volume on every channel to [db] (clamped to the DAC's range). Only on a user action. */
    fun setVolume(db: Double) = launchOp("volume write", keepSnapshot = true) { d -> volume = client.writeVolume(d, Math.round(db * 256).toInt()) }

    /** The value a slider is asking for; written by [volumeJob], newest value wins. */
    private var volumeTarget: Int? = null
    private var volumeJob: Job? = null
    var volumeError by mutableStateOf<String?>(null)
        private set

    /**
     * Slider writes (Contour advBeta): the latest [db] goes to the DAC as soon as the previous write is done, so a
     * drag sends a few writes, never a queue, and the last position always arrives. Does not set [busy] - the
     * requests share the single USB thread with the HID operations, so they never overlap.
     */
    fun dragVolume(db: Double) {
        volumeTarget = Math.round(db * 256).toInt()
        if (volumeJob?.isActive == true) return
        volumeJob = scope.launch {
            while (true) {
                val v = volumeTarget ?: break
                volumeTarget = null
                val d = findDac() ?: break
                if (!manager.hasPermission(d)) break
                try {
                    volume = client.writeVolume(d, v)
                    volumeError = null
                } catch (e: Exception) {
                    UsbLog.line("volume write failed: ${e.message}")
                    volumeError = e.message ?: e.javaClass.simpleName
                }
            }
        }
    }

    // ---- service screen (the v0.1 debug buttons; only the owner presses them) ----------------------------

    fun serviceTestWrite() = launchOp("test write") { d -> showWrite(client.sendTestBand1(d)) }
    fun serviceRestoreFlat() = launchOp("restore flat") { d -> showWrite(client.restoreFlat(d)) }

    private fun showWrite(r: WriteResult) {
        lastWrite = r
        snapshot = r.readBack
    }

    /** [keepSnapshot]: the op does not touch the EQ registers, so a failure leaves ON DAC as it was. */
    private fun launchOp(what: String, keepSnapshot: Boolean = false, op: suspend (UsbDevice) -> Unit) {
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
                if (!keepSnapshot) snapshot = null
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
