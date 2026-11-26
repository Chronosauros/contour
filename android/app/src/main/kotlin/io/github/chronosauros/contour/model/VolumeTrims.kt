package io.github.chronosauros.contour.model

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import io.github.chronosauros.contour.core.DeviceTarget
import io.github.chronosauros.contour.core.ProtocolMicro

/**
 * MAX VOLUME per DAC (owner 25.11.2026, a Reddit report: the Protocol Micro plays at full scale, 20 % of the phone's volume was already very loud): the fixed cut Contour adds
 * under every profile's preamp when it sends to that DAC ([ProtocolMicro.trimmedRegister]), kept across restarts by the DAC's device record id ([DeviceTarget.recordIdOf]).
 * The controller changes it only after a write under the new value read back VERIFIED, so it is what the DAC holds unless another tool wrote to it since. Observable: the
 * DAC page reads it while it draws. A DAC without MAX VOLUME ([DeviceTarget.takesVolumeTrim]) always reads 0.
 */
class VolumeTrims(context: Context) {
    private val prefs = context.getSharedPreferences("volume_trim", Context.MODE_PRIVATE)
    private val values = mutableStateMapOf<String, Int>().apply { prefs.all.forEach { (k, v) -> if (v is Int) put(k, v) } }

    /** The cut for [target] in whole dB, [ProtocolMicro.TRIM_MIN_DB]..0. */
    fun of(target: DeviceTarget): Int {
        if (!target.takesVolumeTrim) return 0
        return DeviceTarget.recordIdOf(target)?.let { values[it] }?.coerceIn(ProtocolMicro.TRIM_MIN_DB, 0) ?: 0
    }

    /** [db] is what the DAC holds now under [target]'s sends. */
    fun set(target: DeviceTarget, db: Int) {
        require(target.takesVolumeTrim && db in ProtocolMicro.TRIM_MIN_DB..0) { "MAX VOLUME $db dB for ${target.caps.name}" }
        val id = DeviceTarget.recordIdOf(target) ?: return
        values[id] = db
        prefs.edit().putInt(id, db).apply()
    }
}
