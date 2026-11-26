package io.github.chronosauros.contour.model

import android.content.Context
import io.github.chronosauros.contour.core.DeviceTarget

/**
 * The last DAC Contour found on the bus (or the simulated one of a review build), kept across restarts by its device record id ([DeviceTarget.recordIdOf]).
 * An import with no DAC attached is fitted to it ([target]); with none ever found, to the Protocol Micro as before. Before 1.8.1 every import with no DAC
 * attached was fitted to the Micro, so the owner of a 10-band DAC lost two filters importing unplugged (audit 10.11.2026, L7).
 * It chooses only the rules of an import: nothing is read from or written to a DAC here, and the editor's ranges with no DAC attached stay the Micro's.
 */
class LastDac(context: Context) {
    private val prefs = context.getSharedPreferences("last_dac", Context.MODE_PRIVATE)

    /** The DAC last found; null when none has been (or its record is gone from this version). */
    var target: DeviceTarget? = prefs.getString(KEY, null)?.let(DeviceTarget::ofRecord)
        private set

    /** [found] is on the bus now: it is the one remembered from here on. */
    fun seen(found: DeviceTarget) {
        if (found == target) return
        val id = DeviceTarget.recordIdOf(found) ?: return
        target = found
        prefs.edit().putString(KEY, id).apply()
    }

    private companion object {
        const val KEY = "recordId"
    }
}
