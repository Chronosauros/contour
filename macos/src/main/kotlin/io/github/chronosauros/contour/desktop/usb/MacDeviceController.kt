package io.github.chronosauros.contour.desktop.usb

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.chronosauros.contour.core.DeviceTarget
import io.github.chronosauros.contour.core.FilterType
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.core.WalkPlay
import io.github.chronosauros.contour.core.native.SettingReading
import io.github.chronosauros.contour.core.native.SettingWriteOutcome
import kotlinx.coroutines.*
import org.hid4java.HidDevice
import org.hid4java.HidManager
import org.hid4java.HidServices
import org.hid4java.HidServicesListener
import org.hid4java.event.HidServicesEvent
import java.io.Closeable

/** Manages attached DAC discovery, state transitions, reactive readings, and profile sends. */
class MacDeviceController(
    private val services: HidServices,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) : HidServicesListener, Closeable {

    val client = MacDacClient()

    var connectedHandle by mutableStateOf<MacDacHandle?>(null)
        private set

    val target: DeviceTarget? get() = connectedHandle?.target

    var snapshot by mutableStateOf<DacSnapshot?>(null)
        private set

    var settings by mutableStateOf<List<SettingReading>>(emptyList())
        private set

    var isReading by mutableStateOf(false)
        private set

    var isSending by mutableStateOf(false)
        private set

    var sendProgress by mutableStateOf(0f)
        private set

    var statusMessage by mutableStateOf<String?>("Checking for connected DACs...")
        private set

    var isAbActive by mutableStateOf(false)
        private set

    init {
        services.addHidServicesListener(this)
        scanAndConnect()
    }

    fun scanAndConnect() {
        val attached = services.attachedHidDevices
        val candidate = attached.firstNotNullOfOrNull { MacDacHandle.of(it) }
        if (candidate != null) {
            onDacConnected(candidate)
        } else {
            connectedHandle = null
            snapshot = null
            settings = emptyList()
            statusMessage = "No supported DAC connected"
        }
    }

    private fun onDacConnected(handle: MacDacHandle) {
        connectedHandle = handle
        statusMessage = "Connected to ${handle.target.caps.name}"
        scope.launch {
            readCurrentDac()
            if (handle.target.settings.isNotEmpty()) {
                readDacSettings()
            }
        }
    }

    suspend fun readCurrentDac(): DacSnapshot? {
        val handle = connectedHandle ?: return null
        isReading = true
        statusMessage = "Reading EQ from ${handle.target.caps.name}..."
        return try {
            val s = client.readDevice(handle)
            snapshot = s
            statusMessage = "${handle.target.caps.name} (v${s.firmware}) ready"
            s
        } catch (e: Exception) {
            statusMessage = "Read failed: ${e.message}"
            null
        } finally {
            isReading = false
        }
    }

    suspend fun readDacSettings(): List<SettingReading> {
        val handle = connectedHandle ?: return emptyList()
        return try {
            val r = client.readSettings(handle)
            settings = r
            r
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun sendProfile(
        profile: Profile,
        slot: Int? = null,
        onProgress: (Float) -> Unit = {}
    ): WriteResult? {
        val handle = connectedHandle ?: return null
        isSending = true
        sendProgress = 0f
        statusMessage = "Sending EQ to ${handle.target.caps.name}..."
        return try {
            val r = client.writeProfile(handle, profile, slot) { p ->
                sendProgress = p
                onProgress(p)
            }
            if (r.verified) {
                snapshot = r.readBack
                statusMessage = "Verified! Saved to DAC memory."
            } else {
                statusMessage = "Sent with warnings: ${r.mismatches.joinToString()}"
            }
            r
        } catch (e: Exception) {
            statusMessage = "Send failed: ${e.message}"
            throw e
        } finally {
            isSending = false
            sendProgress = 0f
        }
    }

    suspend fun toggleAb(enableBypass: Boolean, originalProfile: Profile) {
        val handle = connectedHandle ?: return
        if (!handle.target.supportsAb) return
        val slot = snapshot?.slot ?: 0
        try {
            if (enableBypass) {
                val flatBands = (0 until 8).map { i ->
                    WalkPlay.BandWrite(i, 1000.0, 0.0, 1.0, WalkPlay.TYPE_PK, rateHz = 96000.0)
                }
                client.writeTemporaryBands(handle, flatBands, slot)
                isAbActive = true
                statusMessage = "A/B Bypass Active (EQ bypassed in RAM)"
            } else {
                val writes = originalProfile.bands.take(8).mapIndexed { i, b ->
                    val typeCode = when (b.type) {
                        FilterType.LOW_SHELF -> WalkPlay.TYPE_LSQ
                        FilterType.HIGH_SHELF -> WalkPlay.TYPE_HSQ
                        FilterType.LOW_PASS -> WalkPlay.TYPE_LP
                        FilterType.HIGH_PASS -> WalkPlay.TYPE_HP
                        else -> WalkPlay.TYPE_PK
                    }
                    WalkPlay.BandWrite(i, b.freqHz, if (b.enabled) b.gainDb else 0.0, b.q, typeCode, rateHz = 96000.0)
                }
                client.writeTemporaryBands(handle, writes, slot)
                isAbActive = false
                statusMessage = "A/B Restored to Profile"
            }
        } catch (e: Exception) {
            statusMessage = "A/B toggle failed: ${e.message}"
        }
    }

    suspend fun writeSetting(id: String, value: Int): SettingWriteOutcome {
        val handle = connectedHandle ?: return SettingWriteOutcome.NoReply
        val outcome = client.writeWalkPlaySetting(handle, id, value)
        readDacSettings()
        return outcome
    }

    override fun hidDeviceAttached(event: HidServicesEvent) {
        val device = event.hidDevice
        val handle = MacDacHandle.of(device)
        if (handle != null) {
            onDacConnected(handle)
        }
    }

    override fun hidDeviceDetached(event: HidServicesEvent) {
        val device = event.hidDevice
        if (connectedHandle?.device?.path == device.path) {
            connectedHandle = null
            snapshot = null
            settings = emptyList()
            statusMessage = "DAC disconnected"
        }
    }

    override fun hidFailure(event: HidServicesEvent) {
        statusMessage = "HID Error: ${event.hidDevice?.lastErrorMessage}"
    }

    override fun hidDataReceived(event: HidServicesEvent) {
        // No-op for polled/requested reports
    }

    override fun close() {
        services.removeHidServicesListener(this)
        client.shutdown()
        scope.cancel()
    }
}
