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
import io.github.chronosauros.contour.core.DeviceTarget
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.core.WalkPlay
import io.github.chronosauros.contour.core.native.NativeState
import java.io.IOException
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
    val client = DacClient(manager, { d -> sessionGuard(d) }, { d -> UsbAudioKeepAlive.start(context, d) })

    /** The DAC found on the bus (MICRO when none). MAX for the Protocol Max, KA15 for the FiiO KA15. */
    var protocol by mutableStateOf(DeviceTarget.MICRO)
        private set
    /** The last DAC model seen; a different one resets every device-specific state (see [refresh]). */
    private var lastProtocol: DeviceTarget? = null

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

    /** snapshot remains the A reference during B; it must not be replaced by a bypass read. */
    var abBypassed by mutableStateOf(false)
        private set
    private var abReference: DacSnapshot? = null
    @Volatile private var connectionEpoch = 0L

    /** The USER slot HOLD TO SEND writes on a DAC with a slot picker (KA15, K13 R2R); null = the slot playing now. */
    var selectedSlot by mutableStateOf<Int?>(null)
        private set
    /** The DAC stores USER slot names (FiiO command 0x30, KA15 only): the picker shows and renames them. Without it the picker has generic names and sends no name command. */
    val slotNames: Boolean get() = protocol.fiio?.userSlotNames == true
    /** Named slots (KA15) or a config that opts in (K13 R2R): the user chooses where HOLD TO SEND writes. */
    val slotPicker: Boolean get() = protocol.fiio?.let { it.userSlotNames || it.userSlotPicker } == true
    fun destinationSlot(): Int? = if (!slotPicker) protocol.destinationSlot
        else selectedSlot ?: snapshot?.slot?.takeIf { it in protocol.fiio!!.userSlots } ?: protocol.destinationSlot
    fun selectSlot(slot: Int) { if (slotPicker && slot in protocol.fiio!!.userSlots) selectedSlot = slot }
    /** The chosen slot is the one playing: only then does the read state say what that slot holds (ON DAC). */
    val destinationActive: Boolean get() = !slotPicker || snapshot?.slot == destinationSlot()

    /** Long press on a slot (KA15): writes only the name, then shows the name the DAC reads back. */
    fun renameSlot(slot: Int, name: String) {
        if (!slotNames || slot !in protocol.fiio!!.userSlots) return
        launchOp("rename", keepSnapshot = true) { d ->
            val read = client.renameSlot(d, slot, name)
            val s = snapshot; val state = s?.nativeState as? NativeState.Fiio
            if (s != null && state != null) snapshot = s.copy(nativeState = state.copy(names = state.names + (slot to read)))
            if (read != name) throw IOException("name reads back as $read")
        }
    }

    /** KA15 operations: throws once the attachment the operation started on is gone (detach, other DAC). */
    private fun sessionGuard(d: UsbDevice): () -> Unit {
        val epoch = connectionEpoch
        val id = d.deviceId
        return {
            if (epoch != connectionEpoch || manager.deviceList.values.none { it.deviceId == id } || !manager.hasPermission(d))
                throw IOException("DAC SESSION CHANGED; reconnect and retry")
        }
    }

    /** Why [profile] cannot be sent to the DAC now; empty = HOLD TO SEND may write. */
    fun sendIssues(profile: Profile): List<String> {
        val issues = protocol.issues(profile)
        if (issues.isNotEmpty() || !protocol.native) return issues
        val state = snapshot?.nativeState as? NativeState.Fiio ?: return listOf("Read the DAC first")
        return runCatching {
            state.codec.planOnExplicitSend(profile.copy(bands = state.codec.padToDeviceCount(profile.bands), preampDb = profile.effectivePreampDb()), destinationSlot()!!, true)
        }.exceptionOrNull()?.let { listOf(it.message ?: "Unrepresentable EQ") }.orEmpty()
    }

    private var loggedBlock: String? = null
    /** The full issue list that blocks HOLD TO SEND, once per distinct block, in the USB log (logcat tag [UsbLog.TAG]). An empty list re-arms it. */
    fun logSendBlock(issues: List<String>) {
        val text = issues.joinToString(" | ").takeIf { it.isNotEmpty() }
        if (text == loggedBlock) return
        loggedBlock = text
        if (text != null) UsbLog.line("SEND BLOCKED (${issues.size}): $text")
    }

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
                    refresh(read = false)
                }
            }
        }
    }

    private fun isDac(intent: Intent): Boolean {
        val d = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        return d != null && DeviceTarget.find(d.vendorId, d.productId) != null
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
        DeviceTarget.find(it.vendorId, it.productId) != null
    }

    /** Recomputes the link; when connected and [read], reads the DAC (read-only). */
    fun refresh(read: Boolean) {
        val d = findDac()
        val found = d?.let { DeviceTarget.find(it.vendorId, it.productId) }
        if (found != null && lastProtocol != null && found != lastProtocol) {
            // Another DAC model (Micro <-> Max <-> KA15): nothing read, written or switched on the old one carries over.
            UsbLog.line("DAC changed: ${lastProtocol?.caps?.name} -> ${found.caps.name}")
            connectionEpoch++
            snapshot = null
            lastWrite = null
            error = null
            abBypassed = false
            abReference = null
            selectedSlot = null
        }
        if (found != null) lastProtocol = found
        protocol = found ?: DeviceTarget.MICRO
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
            selectedSlot = null
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
    suspend fun send(profile: Profile): SendOutcome {
        // A refusal (KA15 limits, Micro/Max Q30 or AUTO) happens here, before anything is written or any state is dropped.
        sendIssues(profile).takeIf { it.isNotEmpty() }?.let { return SendOutcome(false, it.joinToString("; ")) }
        val slot = if (slotPicker) destinationSlot() else null
        return sendWith("send") { d -> client.writeProfile(d, profile, slot) }
    }

    /** Toggle against the actual A registers, not the editor's rounded or emulated band values. */
    suspend fun switchAb(bypass: Boolean): SendOutcome {
        if (!protocol.supportsAb) return SendOutcome(false, "A/B unavailable on ${protocol.caps.name}")
        if (bypass == abBypassed) return SendOutcome(true, null)
        val reference = (if (bypass) snapshot else abReference) ?: return forgetAb("DAC STATE UNKNOWN")
        val d = findDac() ?: run {
            abBypassed = false; abReference = null; snapshot = null; link = Link.NO_DAC
            return SendOutcome(false, "NO DAC")
        }
        if (!manager.hasPermission(d)) { link = Link.NEEDS_PERMISSION; return forgetAb("NO PERMISSION") }
        if (busy) return forgetAb("DAC BUSY")
        if (bypass) {
            // B is entered only when A can be put back: the restoring sequence is encoded (and so checked) first.
            val restore = reference.bands.map { it.registers }.filter { it.gain256 != 0 }.map { r ->
                WalkPlay.BandWrite(r.index, r.freq.toDouble(), r.gain256 / 256.0, r.q256 / 256.0, r.typeCode)
            }
            if (runCatching { WalkPlay.temporaryBandSequence(restore, reference.slot) }.isFailure) {
                return SendOutcome(false, "A/B blocked: a band on the DAC does not fit its filter format, so A could not be restored. Nothing was changed.")
            }
        }
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
        val epoch = connectionEpoch
        val started = protocol
        return try {
            val r = op(d)
            // Experimental device, or the DAC model changed under the write: never keep a result for the wrong DAC.
            if ((started.experimental || protocol != started) && epoch != connectionEpoch) return SendOutcome(false, "DAC DETACHED")
            lastWrite = r
            // Protocol Max and KA15 fail closed: a mismatching read-back is not ON DAC. The Micro keeps the 1.2.2 behaviour.
            snapshot = if (r.readBack.target.experimental && !r.verified) null else r.readBack
            // A verified write can still carry a note (KA15: the slot name did not read back).
            error = if (r.verified) r.mismatches.firstOrNull() else null
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
    fun serviceRestoreFlat() {
        if (protocol.native) return // Micro and Max only; the KA15 resets through a flat profile and HOLD TO SEND
        launchOp("restore flat") { d -> showWrite(client.restoreFlat(d)) }
    }

    private fun showWrite(r: WriteResult) {
        lastWrite = r
        if (r.readBack.target.experimental && !r.verified) {
            snapshot = null
            throw java.io.IOException("READ-BACK MISMATCH: ${r.mismatches.firstOrNull()}")
        }
        snapshot = r.readBack
    }

    private fun launchOp(what: String, keepSnapshot: Boolean = false, op: suspend (UsbDevice) -> Unit) {
        val d = findDac() ?: run { link = Link.NO_DAC; snapshot = null; return }
        if (!manager.hasPermission(d)) { link = Link.NEEDS_PERMISSION; snapshot = null; return }
        if (busy) return
        busy = true
        val epoch = connectionEpoch
        val started = protocol
        scope.launch {
            try {
                op(d)
                if ((started.experimental || protocol != started) && epoch != connectionEpoch) { snapshot = null; return@launch }
                error = null
            } catch (e: Exception) {
                UsbLog.line("$what failed: ${e.message}")
                if (!keepSnapshot || epoch != connectionEpoch) snapshot = null
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
