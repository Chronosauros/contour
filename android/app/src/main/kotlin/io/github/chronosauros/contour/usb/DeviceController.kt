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
import io.github.chronosauros.contour.core.DeviceProtocol
import io.github.chronosauros.contour.core.DeviceTarget
import io.github.chronosauros.contour.core.native.*
import io.github.chronosauros.contour.core.WalkPlayCatalog
import io.github.chronosauros.contour.core.WalkPlay
import io.github.chronosauros.contour.BuildConfig
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class Link { NO_DAC, NEEDS_PERMISSION, CONNECTED }
data class SendOutcome(val verified: Boolean, val reason: String?, val pending: Boolean = false)

/** Read-only discovery. No first-device retargeting: multiple candidate DACs disable all operations.
 * Every I/O operation and every publication is bound to one physical attachment generation. */
class DeviceController(private val context: Context, private val scope: CoroutineScope) {
    private val manager = context.getSystemService(UsbManager::class.java)
    @Volatile private var connectionEpoch = 0L
    @Volatile private var connectedName: String? = null
    val sessionGeneration: Long get() = connectionEpoch
    val client = DacClient(manager, { d -> sessionGuard(d) }, { connectionEpoch }, { d -> UsbAudioKeepAlive.start(context, d) })
    var protocol by mutableStateOf(if (BuildConfig.ADVANCED) DeviceTarget.OFFLINE else DeviceTarget.MICRO)
        private set
    /** The last DAC model seen; a different one resets every device-specific state (see [refresh]). */
    private var lastTarget: DeviceTarget? = null
    val hardwareVolumeSupported: Boolean get() = protocol == DeviceTarget.MICRO
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
    var volume by mutableStateOf<UacVolume.State?>(null)
        private set
    var volumeError by mutableStateOf<String?>(null)
        private set
    /** Why the last explicit or automatic read failed (null = it did not, or none ran); cleared by a good read and by a new session. */
    var readFailure by mutableStateOf<String?>(null)
        private set
    /** True only while the descriptor/EQ read runs (not for any other USB operation, which also sets [busy]). */
    var reading by mutableStateOf(false)
        private set
    var abBypassed by mutableStateOf(false)
        private set
    /** The USER slot HOLD TO SEND writes on a DAC with a slot picker (KA15, K13 R2R ...); null = the slot playing now. */
    var selectedSlot by mutableStateOf<Int?>(null)
        private set
    /** The DAC stores USER slot names (FiiO command 0x30, KA15 only): the picker shows and renames them. Without it the picker has generic names and sends no name command. */
    val slotNames: Boolean get() = protocol.fiio?.userSlotNames == true
    /** Named slots (KA15) or a config that opts in (K13 R2R): the user chooses where HOLD TO SEND writes. */
    val slotPicker: Boolean get() = protocol.fiio?.let { it.userSlotNames || it.userSlotPicker } == true
    fun destinationSlot(): Int? = if (!slotPicker) protocol.destinationSlot
        else selectedSlot ?: snapshot?.slot?.takeIf { it in protocol.fiio!!.userSlots } ?: protocol.destinationSlot
    fun selectSlot(slot: Int) { if (slotPicker && slot in protocol.fiio!!.userSlots) selectedSlot = slot }
    /** Long press on a slot (KA15): writes only the name, then shows the name the DAC reads back. */
    fun renameSlot(slot: Int, name: String) {
        if (!slotNames || slot !in protocol.fiio!!.userSlots) return
        launchOp("rename", keepSnapshot = true) { d, guard ->
            val read = client.renameSlot(d, slot, name); guard()
            val s = snapshot; val state = s?.nativeState as? NativeState.Fiio
            if (s != null && state != null) snapshot = s.copy(nativeState = state.copy(names = state.names + (slot to read)))
            if (read != name) throw IOException("name reads back as $read")
        }
    }
    /** The chosen slot is the one playing: only then does the read state say what that slot holds (ON DAC). */
    val destinationActive: Boolean get() = !slotPicker || snapshot?.slot == destinationSlot()
    private var abReference: DacSnapshot? = null
    private var volumeTarget: Int? = null
    private var volumeJob: Job? = null

    private fun name(d: UsbDevice): String? = runCatching { d.productName }.getOrNull()
    private var explicitConsentPath: String? = null
    private var explicitConsentTarget: DeviceTarget? = null
    private var explicitConsentIdentity: NativeUsbIdentity? = null
    private fun consentIdentity(d: UsbDevice) = NativeUsbIdentity(d.vendorId, d.productId, name(d))
    private fun consented(d: UsbDevice, p: DeviceTarget): Boolean = explicitConsentPath == d.deviceName &&
        explicitConsentTarget == p && explicitConsentIdentity == consentIdentity(d)
    // Retained across attachment invalidation, never persisted or used as an automatic send.
    var pendingReceipt by mutableStateOf<NativePendingReceipt?>(null)
        private set
    var pendingReason by mutableStateOf<String?>(null)
        private set
    var profileForReceipt: (String) -> Profile? = { null }
    var onPendingVerified: (Profile) -> Unit = {}
    private var explicitReadConsent = false
    private fun identity(d: UsbDevice) = NativeUsbIdentity(d.vendorId, d.productId, name(d),
        runCatching { d.serialNumber }.getOrNull()?.takeIf { it.isNotEmpty() })
    private fun blocked(d: UsbDevice): String? = DeviceTarget.blockedReason(d.vendorId, d.productId, name(d))
    private fun candidates(): List<UsbDevice> = manager.deviceList.values.filter {
        DeviceTarget.candidate(it.vendorId, it.productId, name(it), BuildConfig.ADVANCED)
    }
    private fun multipleDacs(): Boolean = candidates().size > 1 || (BuildConfig.ADVANCED &&
        manager.deviceList.values.count { d -> (0 until d.interfaceCount).any {
            d.getInterface(it).interfaceClass == android.hardware.usb.UsbConstants.USB_CLASS_AUDIO
        } } > 1)
    private fun findDac(): UsbDevice? = if (multipleDacs()) null else candidates().singleOrNull()
    private fun resolved(d: UsbDevice): DeviceTarget? = DeviceTarget.find(d.vendorId, d.productId, BuildConfig.ADVANCED, name(d))

    fun sessionCurrent(expectedGeneration: Long): Boolean {
        val d = findDac() ?: return false
        return expectedGeneration == connectionEpoch && connectedName == d.deviceName &&
            link == Link.CONNECTED && manager.hasPermission(d) && resolved(d) == protocol
    }
    private fun sessionGuard(d: UsbDevice): () -> Unit {
        val epoch = connectionEpoch
        val product = name(d)
        val vid = d.vendorId; val pid = d.productId; val id = d.deviceId; val path = d.deviceName
        return {
            val live = findDac()
            if (epoch != connectionEpoch || connectedName != path || live == null || live.deviceId != id ||
                live.vendorId != vid || live.productId != pid || name(live) != product || !manager.hasPermission(live))
                throw IOException("DAC SESSION CHANGED; reconnect and retry explicitly")
        }
    }

    private fun invalidate() {
        connectionEpoch++
        explicitConsentPath = null
        explicitConsentTarget = null
        explicitConsentIdentity = null
        explicitReadConsent = false
        busy = false
        snapshot = null; lastWrite = null; volume = null; volumeError = null; readFailure = null; reading = false
        abBypassed = false; abReference = null; volumeTarget = null; selectedSlot = null
        volumeJob?.cancel(); volumeJob = null
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    refresh(granted)
                    if (!granted) error = "USB permission denied; tap Connect to retry explicitly"
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    // Even a reused /dev path is a new session. No descriptor/name read is a write.
                    invalidate(); connectedName = null
                    refresh(intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED)
                }
            }
        }
    }
    fun start() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION); addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    fun stop() { invalidate(); pendingReceipt = null; pendingReason = null; runCatching { context.unregisterReceiver(receiver) }; client.shutdown() }

    fun refresh(read: Boolean) {
        val all = candidates()
        val multiple = multipleDacs()
        val d = all.singleOrNull()?.takeUnless { multiple }
        if (connectedName != d?.deviceName) { invalidate(); connectedName = d?.deviceName }
        val p = d?.let { resolved(it) }
        if (p != null && lastTarget != null && p != lastTarget) {
            // Another DAC model (e.g. Micro <-> Max): nothing read, written or switched on the old one carries over.
            UsbLog.line("DAC changed: ${lastTarget?.caps?.name} -> ${p.caps.name}")
            invalidate()
        }
        if (p != null) lastTarget = p
        protocol = p ?: if (BuildConfig.ADVANCED) DeviceTarget.OFFLINE else DeviceTarget.MICRO
        link = when {
            d == null -> Link.NO_DAC
            blocked(d) != null -> Link.NO_DAC
            !manager.hasPermission(d) -> Link.NEEDS_PERMISSION
            p == null -> Link.NO_DAC
            p.runtimeCandidate && !consented(d, p) -> Link.NO_DAC
            else -> Link.CONNECTED
        }
        error = when {
            multiple -> "Multiple DACs attached: disconnect all but the target (selector not available)"
            d != null && blocked(d) != null -> blocked(d)
            d != null && manager.hasPermission(d) && p == null && name(d) == null -> "USB product name unavailable; cannot safely select a recipe"
            d != null && manager.hasPermission(d) && p == null -> when (val r = WalkPlayCatalog.resolve(d.vendorId, d.productId, name(d), BuildConfig.ADVANCED)) {
                is WalkPlayCatalog.Resolution.Blocked -> r.reason
                WalkPlayCatalog.Resolution.NeedsName -> "USB product name unavailable; cannot safely select a recipe"
                else -> if (name(d) == null) "USB product name unavailable; cannot safely select a native recipe" else "Unsupported DAC identity"
            }
            p?.runtimeCandidate == true && d != null && !consented(d, p) -> "Experimental exact source-name candidate; tap Connect to consent to descriptor and complete read qualification. Not hardware tested."
            else -> null
        }
        if (link != Link.CONNECTED) { snapshot = null; abBypassed = false; abReference = null }
        if (read && link == Link.CONNECTED && !abBypassed) {
            val explicit = explicitReadConsent
            explicitReadConsent = false
            read(explicit)
        }
    }
    fun connect(): Boolean {
        refresh(false)
        val d = findDac()
        explicitConsentPath = d?.deviceName
        explicitConsentTarget = d?.let { resolved(it) }
        explicitConsentIdentity = d?.let { consentIdentity(it) }
        explicitReadConsent = true
        refresh(true)
        if (link == Link.NEEDS_PERMISSION) requestPermission()
        return link != Link.NO_DAC
    }
    fun requestPermission() {
        val d = findDac() ?: return refresh(false)
        if (blocked(d) != null) return refresh(false)
        explicitConsentPath = d.deviceName
        explicitConsentTarget = resolved(d) // Unknown pre-permission name cannot consent to a future native route.
        explicitConsentIdentity = consentIdentity(d)
        explicitReadConsent = true
        if (manager.hasPermission(d)) return refresh(true)
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        manager.requestPermission(d, PendingIntent.getBroadcast(context, 0, intent, flags))
    }

    fun read() = read(explicitUserRead = true)
    private fun read(explicitUserRead: Boolean) {
        if (abBypassed) return
        val generation = connectionEpoch
        if (!busy) readFailure = null // READING DAC while it runs; a failure sets it again
        launchOp("read") { d, guard ->
            reading = true
            try {
                val s = client.readDevice(d); guard()
                readFailure = null
                val receipt = pendingReceipt
                val check = receipt?.verify(identity(d), s.target, profileForReceipt(receipt.profile.id),
                    s.nativeState, generation, connectionEpoch, explicitUserRead)
                guard()
                if (receipt != null && check?.verified == true) {
                    val verified = s.copy(nativeState = check.state)
                    snapshot = verified
                    lastWrite = WriteResult(true, listOf(check.reason), lastWrite?.writeMs ?: 0, s.readMs, verified)
                    pendingReceipt = null; pendingReason = null
                    guard(); onPendingVerified(receipt.profile)
                } else {
                    snapshot = s
                    if (receipt != null) pendingReason = (check?.reason ?: receipt.pendingReason) + " " + receipt.identityWarning
                }
            } finally { if (generation == connectionEpoch) reading = false }
        }
    }
    private var loggedBlock: String? = null
    /** The full issue list that blocks HOLD TO SEND, once per distinct block, in the USB log (logcat tag [UsbLog.TAG]). An empty list re-arms it. */
    fun logSendBlock(issues: List<String>) {
        val text = issues.joinToString(" | ").takeIf { it.isNotEmpty() }
        if (text == loggedBlock) return
        loggedBlock = text
        if (text != null) UsbLog.line("SEND BLOCKED (${issues.size}): $text")
    }
    /** Why HOLD TO SEND cannot go: the EQ itself, or the DAC read that has to come first (never mislabel a read problem as an EQ one). */
    enum class SendBlock { NONE, EQ, READING, NEEDS_READ, READ_FAILED }
    fun sendBlock(profile: Profile): SendBlock {
        if (protocol.issues(profile).isNotEmpty()) return SendBlock.EQ
        if (protocol.native && snapshot == null) return when {
            readFailure != null -> SendBlock.READ_FAILED
            reading -> SendBlock.READING
            else -> SendBlock.NEEDS_READ
        }
        return if (sendIssues(profile).isNotEmpty()) SendBlock.EQ else SendBlock.NONE
    }
    fun sendIssues(profile: Profile): List<String> {
        val issues = protocol.issues(profile)
        if (issues.isNotEmpty()) return issues
        if (protocol.native) {
            val state = snapshot?.nativeState ?: return listOf("Complete descriptor-qualified read required before HOLD")
            return runCatching {
                when (state) {
                    is NativeState.Fiio -> state.codec.planOnExplicitSend(profile.copy(preampDb = profile.effectivePreampDb()), destinationSlot()!!, true)
                    is NativeState.Kt -> KtMicroCodec.compile(profile, state.raw)
                    is NativeState.Fosi -> FosiCodec.compile(profile, state.raw)
                    is NativeState.Moondrop -> error(state.raw.model.reasonReadOnly)
                }
            }.exceptionOrNull()?.let { listOf(it.message ?: "Unrepresentable native EQ") }.orEmpty()
        }
        return emptyList()
    }
    suspend fun send(profile: Profile, expectedGeneration: Long = connectionEpoch): SendOutcome {
        if (pendingReceipt != null) return SendOutcome(false, "Pending save unresolved: verify/read the original DAC and unmodified profile before another HOLD. No resend was attempted.", pending = true)
        if (expectedGeneration != connectionEpoch) return SendOutcome(false, "DAC SESSION CHANGED")
        val issues = sendIssues(profile)
        if (issues.isNotEmpty()) return SendOutcome(false, issues.joinToString("; "))
        val slot = destinationSlot()
        return sendWith("send") { d ->
            val origin = identity(d); val originTarget = protocol
            client.writeProfile(d, profile, slot) { expected ->
                // Captured immediately before SAVE; survives a SAVE-triggered detach, not earlier failures.
                pendingReceipt = NativePendingReceipt(origin, originTarget, profile, expectedGeneration, expected)
                pendingReason = pendingReceipt?.pendingReason
            }
        }
    }

    suspend fun switchAb(bypass: Boolean): SendOutcome {
        if (!protocol.supportsAb) return SendOutcome(false, "A/B is Micro-only")
        if (bypass == abBypassed) return SendOutcome(true, null)
        val reference = (if (bypass) snapshot else abReference) ?: return forgetAb("DAC STATE UNKNOWN")
        val d = findDac() ?: return SendOutcome(false, "NO DAC / MULTIPLE DACS")
        if (busy) return SendOutcome(false, "DAC BUSY")
        if (bypass) {
            // B is entered only when A can be put back: the restoring sequence is encoded (and so checked) first.
            val restore = reference.bands.map { it.registers }.filter { it.gain256 != 0 }.map { r ->
                WalkPlay.BandWrite(r.index, r.freq.toDouble(), r.gain256 / 256.0, r.q256 / 256.0, r.typeCode)
            }
            if (runCatching { WalkPlay.temporaryBandSequence(restore, reference.slot) }.isFailure) {
                return SendOutcome(false, "A/B blocked: a band on the DAC does not fit its filter format, so A could not be restored. Nothing was changed.")
            }
        }
        val epoch = connectionEpoch; val guard = sessionGuard(d)
        busy = true
        return try {
            guard()
            require(resolved(d) == DeviceTarget.MICRO && reference.target == DeviceTarget.MICRO)
            val bands = reference.bands.map { it.registers }.filter { it.gain256 != 0 }.map { r ->
                WalkPlay.BandWrite(r.index, r.freq.toDouble(), if (bypass) 0.0 else r.gain256 / 256.0, r.q256 / 256.0, r.typeCode)
            }
            client.writeTemporaryBands(d, bands, reference.slot); guard()
            abBypassed = bypass; abReference = if (bypass) reference else null; error = null
            SendOutcome(true, null)
        } catch (e: Exception) {
            if (epoch != connectionEpoch || runCatching { guard() }.isFailure) SendOutcome(false, "DAC SESSION CHANGED")
            else forgetAb(e.message ?: "A/B failed")
        } finally { if (epoch == connectionEpoch && runCatching { guard() }.isSuccess) busy = false }
    }
    private fun forgetAb(reason: String): SendOutcome {
        abBypassed = false; abReference = null; snapshot = null; error = reason
        return SendOutcome(false, reason)
    }

    private suspend fun sendWith(what: String, op: suspend (UsbDevice) -> WriteResult): SendOutcome {
        val d = findDac() ?: return SendOutcome(false, "NO DAC / MULTIPLE DACS")
        if (link != Link.CONNECTED || resolved(d) == null) return SendOutcome(false, error ?: "CONNECT DAC FIRST")
        if (busy) return SendOutcome(false, "DAC BUSY")
        val epoch = connectionEpoch; val guard = sessionGuard(d)
        busy = true
        return try {
            guard(); val r = op(d); guard()
            // Strict targets (Max, catalog, native) fail closed: a mismatching read-back is not ON DAC.
            // The Micro keeps the 1.2.2/1.3.0 behaviour: its read-back stays the snapshot.
            lastWrite = r; snapshot = if (r.verified || r.readBack?.protocol == DeviceProtocol.MICRO) r.readBack else null
            // A verified write can still carry a note (KA15: the slot name did not read back).
            error = if (r.verified) r.mismatches.firstOrNull() else null
            if (r.pending) SendOutcome(false, pendingReason ?: r.mismatches.firstOrNull(), pending = true)
            else if (r.verified) SendOutcome(true, null) else SendOutcome(false, "READ-BACK MISMATCH: ${r.mismatches.firstOrNull()}")
        } catch (e: Exception) {
            if (epoch == connectionEpoch && runCatching { guard() }.isSuccess) { snapshot = null; error = "$what failed: ${e.message}" }
            pendingReceipt?.let { SendOutcome(false, pendingReason ?: it.pendingReason, pending = true) }
                ?: SendOutcome(false, e.message ?: "Send failed")
        } finally { if (epoch == connectionEpoch && runCatching { guard() }.isSuccess) busy = false }
    }

    fun readVolume() = launchOp("volume read", true) { d, guard -> val v = client.readVolume(d); guard(); volume = v }
    fun setVolume(db: Double) {
        if (!hardwareVolumeSupported || !db.isFinite()) return
        launchOp("volume write", true) { d, guard -> val v = client.writeVolume(d, Math.round(db * 256).toInt()); guard(); volume = v }
    }
    fun dragVolume(db: Double) {
        if (!hardwareVolumeSupported || link != Link.CONNECTED || !db.isFinite()) return
        val d = findDac() ?: return
        val epoch = connectionEpoch; val guard = sessionGuard(d)
        volumeTarget = Math.round(db * 256).toInt()
        if (volumeJob?.isActive == true) return
        volumeJob = scope.launch {
            try {
                while (epoch == connectionEpoch) {
                    guard(); val v = volumeTarget ?: break; volumeTarget = null
                    val result = client.writeVolume(d, v); guard()
                    volume = result; volumeError = null
                }
            } catch (e: Exception) {
                if (epoch == connectionEpoch && runCatching { guard() }.isSuccess) volumeError = e.message
            } finally {
                if (epoch == connectionEpoch && runCatching { guard() }.isSuccess) { volumeTarget = null; volumeJob = null }
            }
        }
    }

    fun serviceTestWrite() {
        if (protocol != DeviceTarget.MICRO) return
        launchOp("test write") { d, guard -> val r = client.sendTestBand1(d); guard(); showWrite(r) }
    }
    fun serviceRestoreFlat() {
        // Micro and Protocol Max, as in 1.3.0; other targets reset through a flat profile and HOLD TO SEND.
        if (protocol != DeviceTarget.MICRO && protocol.walkplay?.isMax != true) return
        launchOp("restore flat") { d, guard -> val r = client.restoreFlat(d); guard(); showWrite(r) }
    }
    private fun showWrite(r: WriteResult) {
        val micro = r.readBack?.protocol == DeviceProtocol.MICRO // 1.2.2/1.3.0: the Micro's read-back is shown as is
        lastWrite = r; snapshot = if (r.verified || micro) r.readBack else null
        if (!r.verified && !micro) throw IOException("READ-BACK MISMATCH: ${r.mismatches.firstOrNull()}")
    }
    private fun launchOp(what: String, keepSnapshot: Boolean = false, op: suspend (UsbDevice, () -> Unit) -> Unit) {
        val d = findDac() ?: return
        if (link != Link.CONNECTED || resolved(d) == null || busy) return
        val epoch = connectionEpoch; val guard = sessionGuard(d)
        busy = true
        scope.launch {
            try { guard(); op(d, guard); guard(); error = null }
            catch (e: Exception) {
                if (epoch == connectionEpoch && runCatching { guard() }.isSuccess) {
                    if (!keepSnapshot) snapshot = null
                    error = "$what failed: ${e.message}"
                    UsbLog.line("$what failed: ${e.message}")
                    if (what == "read") readFailure = e.message ?: e.javaClass.simpleName
                    if (what == "read" && pendingReceipt != null) pendingReason = "Pending readback failed/incomplete: ${e.message}. ${pendingReceipt?.identityWarning}"
                }
            } finally { if (epoch == connectionEpoch && runCatching { guard() }.isSuccess) busy = false }
        }
    }
    companion object { private const val ACTION_USB_PERMISSION = "io.github.chronosauros.contour.USB_PERMISSION" }
}
