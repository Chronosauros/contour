package io.github.chronosauros.contour.model

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.core.ProtocolMicro
import io.github.chronosauros.contour.usb.DeviceController
import io.github.chronosauros.contour.usb.SendOutcome
import io.github.chronosauros.contour.usb.UsbLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Sending and matching: which profile the DAC holds (its plan produces exactly the registers read back) and
 * the one send the app makes, started by HOLD TO SEND. Every send is an explicit user gesture.
 */
class Sender(private val model: AppModel, private val device: DeviceController, private val scope: CoroutineScope) {
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
        val regs = s.bands.map { it.registers }
        if (cacheSnapshot !== s) {
            cache.clear()
            cacheSnapshot = s
        }
        model.profiles.firstOrNull { p ->
            val c = cache[p.id]
            if (c != null && c.first === p) c.second else ProtocolMicro.matches(p, regs, s.preampDb).also { cache[p.id] = p to it }
        }?.id
    }

    val busy: Boolean get() = device.busy || sendingId != null

    /** HOLD TO SEND: writes a snapshot of [p], reads it back and compares. */
    fun send(p: Profile) {
        if (busy) return fail(p.id, "DAC BUSY")
        sendingId = p.id
        failedId = null
        scope.launch {
            val r: SendOutcome = device.send(p)
            sendingId = null
            if (r.verified) model.setLastSent(p.id) else fail(p.id, r.reason ?: "UNKNOWN")
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
