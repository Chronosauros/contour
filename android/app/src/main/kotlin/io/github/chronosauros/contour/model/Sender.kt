package io.github.chronosauros.contour.model

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.core.native.FamilyState
import io.github.chronosauros.contour.usb.DeviceController
import io.github.chronosauros.contour.usb.DacSnapshot
import io.github.chronosauros.contour.usb.Link
import io.github.chronosauros.contour.usb.SendOutcome
import io.github.chronosauros.contour.usb.UsbLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One profile's answer to "does the DAC hold its SAVED version": good while the SAVED version is the same object ([AppModel.savedEqKey]) and nothing else of the
 * profile that a device plan could read (its texts, the archive flag) has changed. Only [Profile.updatedAt] is left out: it moves with every edit of the working copy.
 */
internal class SavedMatch(private val eq: Any, private val p: Profile, val on: Boolean) {
    fun stands(eq: Any, p: Profile): Boolean = this.eq === eq && (this.p === p ||
        (this.p.name == p.name && this.p.subtitle == p.subtitle && this.p.icon == p.icon && this.p.archived == p.archived))
}

/**
 * Library badges follow the saved curve, not Tune's working copy. A read belongs only to the active destination. [cache] (by profile id, for this one
 * [snapshot]) keeps each profile's answer: without it every profile of the library was planned for the DAC again on each step of a drag on EQ.
 */
internal fun savedOnDacIds(model: AppModel, snapshot: DacSnapshot?, connected: Boolean,
    destinationActive: Boolean, pendingId: String?, cache: MutableMap<String, SavedMatch>? = null): Set<String> {
    if (!connected || !destinationActive || snapshot == null) return emptySet()
    return model.profiles.filter { p ->
        if (p.id == pendingId) return@filter false
        if (cache == null) return@filter snapshot.matches(model.savedProfile(p))
        val eq = model.savedEqKey(p)
        val kept = cache[p.id]
        if (kept != null && kept.stands(eq, p)) kept.on
        else snapshot.matches(model.savedProfile(p)).also { cache[p.id] = SavedMatch(eq, p, it) }
    }.map { it.id }.toSet()
}

/**
 * Sending and matching: which profile the DAC holds (its plan produces exactly the registers read back) and
 * the one send the app makes, started by HOLD TO SEND. Every send is an explicit user gesture.
 */
class Sender(private val model: AppModel, private val device: DeviceController, private val scope: CoroutineScope,
    private val abScope: CoroutineScope = scope) {
    /** Profile id being sent by HOLD TO SEND. */
    var sendingId by mutableStateOf<String?>(null)
        private set
    /** How far the running send is, 0..1: the HID frames done out of those planned (the fill of SAVING TO DAC). Only grows during one send. */
    var sendProgress by mutableStateOf(0f)
        private set
    /** Profile id of the last failed send and its reason (FAILED - HOLD TO RETRY; tap = details). */
    var failedId by mutableStateOf<String?>(null)
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    /** When it failed (epoch ms): a profile edited later shows HOLD TO SEND again. */
    var failedAt = 0L
        private set

    private val cache = HashMap<String, Pair<Profile, Boolean>>()
    private var cacheSnapshot: Any? = null

    /** [AppModel.touches] at the last read or send of the cable: the DUSK-SP foot names its preset until the person touches the EQ after it ([presetFoot]). */
    private var footTouches = 0

    init {
        // eager, so a pick in the Library made while Tune is not composed still counts. The baseline moves when a read shows another cable state (connect, a preset choice, a send that changed the bank)
        // and when a send is verified ([send]); a background read of the same state does not take the foot back from HOLD TO SEND.
        scope.launch { snapshotFlow { (device.snapshot?.familyState as? FamilyState.Dusk)?.describe() }.collect { footTouches = model.touches } }
    }

    /**
     * The foot button of the DUSK-SP when it is not a send control: the cable's active preset in capitals (CUSTOM EQ, DUSK-DEFAULT, ...), shown while the cable is connected and nothing was picked, imported or edited
     * since its last read or send (a preset chosen on the DAC page is a read, so it shows at once; a verified send ends in a read, so it reads CUSTOM EQ). Null for every other DAC, for a cable not read, after a
     * touch (HOLD TO SEND then) and after a failed send of [p] (FAILED - HOLD TO RETRY).
     */
    fun presetFoot(p: Profile): String? {
        val dusk = device.snapshot?.familyState as? FamilyState.Dusk ?: return null
        if (device.link != Link.CONNECTED || model.touches != footTouches || failedFor(p)) return null
        return dusk.footLabel
    }

    /** Id of the library profile the DAC holds right now, or null. */
    val onDacId: String? by derivedStateOf {
        val s = device.snapshot ?: return@derivedStateOf null
        if (cacheSnapshot !== s) {
            cache.clear()
            cacheSnapshot = s
        }
        model.profiles.firstOrNull { p ->
            val c = cache[p.id]
            if (c != null && c.first === p) c.second else s.matches(p).also { cache[p.id] = p to it }
        }?.id
    }

    /** The chosen destination holds [p]. Not [onDacId] == p.id: the library can have twins with the same
     * registers, and the first of them would take ON DAC from the profile on screen. */
    fun isOnDac(p: Profile): Boolean {
        val s = device.snapshot ?: return false
        if (!device.destinationActive) return false
        if (cacheSnapshot !== s) {
            cache.clear()
            cacheSnapshot = s
        }
        val c = cache[p.id]
        return if (c != null && c.first === p) c.second else s.matches(p).also { cache[p.id] = p to it }
    }

    /** Library "ON DAC" badges: the profiles whose SAVED curve the DAC holds (only for the active destination). */
    private val savedCache = HashMap<String, SavedMatch>()
    private var savedSnapshot: Any? = null
    val savedOnDacIds: Set<String> by derivedStateOf {
        val s = device.snapshot
        if (savedSnapshot !== s) {
            savedCache.clear()
            savedSnapshot = s
        }
        savedOnDacIds(model, s, device.link == Link.CONNECTED, device.destinationActive, null, savedCache)
    }

    private var abJob by mutableStateOf<Job?>(null)
    var abBusy by mutableStateOf(false)
        private set
    private var abProfileId: String? = null
    private var foreground = true
    val bypassed: Boolean get() = device.abBypassed

    val busy: Boolean get() = device.busy || sendingId != null || abBusy

    fun canAb(p: Profile): Boolean = device.protocol.supportsAb && device.link == Link.CONNECTED && onDacId == p.id

    fun toggleAb(p: Profile) {
        if (!foreground || busy || !canAb(p)) return
        abProfileId = p.id
        failedId = null
        abBusy = true
        abJob = abScope.launch(start = CoroutineStart.LAZY) {
            try {
                val r = device.switchAb(!bypassed)
                if (!r.verified) fail(p.id, r.reason ?: "UNKNOWN")
            } finally {
                if (abJob === currentCoroutineContext()[Job]) abBusy = false
            }
        }
        abJob?.start()
    }

    private suspend fun restoreAb(): Boolean {
        if (!bypassed) return true // detach resets B without writing to a replacement device
        val r = device.switchAb(false)
        if (!r.verified) abProfileId?.let { fail(it, r.reason ?: "UNKNOWN") }
        return r.verified
    }

    /** Join an in-flight switch, restore A through RAM, then navigate/edit/send. Never cancel a USB write. */
    fun leaveAb(after: (Boolean) -> Unit = {}): Job? {
        val previous = abJob
        if (!bypassed && previous?.isActive != true) { after(true); return null }
        abBusy = true
        val job = abScope.launch(start = CoroutineStart.LAZY) {
            try {
                previous?.join()
                after(restoreAb())
            } finally {
                if (abJob === currentCoroutineContext()[Job]) abBusy = false
            }
        }
        abJob = job
        job.start()
        return job
    }

    fun onResume() { foreground = true }
    fun onStop() { foreground = false; leaveAb() }

    /** HOLD TO SEND: writes a snapshot of [p], reads it back and compares. */
    fun send(p: Profile) {
        if (busy) return fail(p.id, "DAC BUSY")
        sendingId = p.id
        sendProgress = 0f
        failedId = null
        leaveAb { restored ->
            if (!restored) sendingId = null else scope.launch {
                // The write reports from the USB thread; the state is only touched on the main one.
                val r: SendOutcome = device.send(p) { f -> scope.launch { sendProgress = maxOf(sendProgress, f.coerceIn(0f, 1f)) } }
                if (r.verified) {
                    sendProgress = 1f
                    footTouches = model.touches // the DUSK-SP foot names the cable's preset again (CUSTOM EQ) until the EQ is touched
                    model.markSent(p)
                    delay(PROGRESS_SETTLE_MS) // the fill reaches the end before the button turns ON DAC
                    sendingId = null
                } else {
                    sendingId = null
                    fail(p.id, r.reason ?: "UNKNOWN")
                }
            }
        }
    }

    private fun fail(id: String, reason: String) {
        failedAt = System.currentTimeMillis()
        failedId = id
        failure = reason
        UsbLog.line("HOLD TO SEND failed: $reason")
    }

    /**
     * FAILED - HOLD TO RETRY applies to [p] until it is edited. A send that ended NOT VERIFIED (the DAC took longer to come back than the wait, e.g. while the system's
     * USB permission dialog was open) stops failing once a read of the DAC shows exactly [p]; a read-back that does not match leaves it failed.
     */
    fun failedFor(p: Profile): Boolean = failedId == p.id && p.updatedAt <= failedAt && !(failure?.startsWith(DeviceController.NOT_VERIFIED) == true && isOnDac(p))

    private companion object {
        const val PROGRESS_SETTLE_MS = 250L
    }
}
