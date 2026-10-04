package io.github.chronosauros.contour.model

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.usb.DeviceController
import io.github.chronosauros.contour.usb.Link
import io.github.chronosauros.contour.usb.SendOutcome
import io.github.chronosauros.contour.usb.UsbLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

/**
 * Sending and matching: which profile the DAC holds (its plan produces exactly the registers read back) and
 * the one send the app makes, started by HOLD TO SEND. Every send is an explicit user gesture.
 */
class Sender(private val model: AppModel, private val device: DeviceController, private val scope: CoroutineScope,
    private val abScope: CoroutineScope = scope) {
    /** Profile id being sent by HOLD TO SEND. */
    var sendingId by mutableStateOf<String?>(null)
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
        failedId = null
        leaveAb { restored ->
            if (!restored) sendingId = null else scope.launch {
                val r: SendOutcome = device.send(p)
                sendingId = null
                if (r.verified) model.markSent(p) else fail(p.id, r.reason ?: "UNKNOWN")
            }
        }
    }

    private fun fail(id: String, reason: String) {
        failedAt = System.currentTimeMillis()
        failedId = id
        failure = reason
        UsbLog.line("HOLD TO SEND failed: $reason")
    }

    /** FAILED - HOLD TO RETRY applies to [p] until it is edited. */
    fun failedFor(p: Profile): Boolean = failedId == p.id && p.updatedAt <= failedAt
}
