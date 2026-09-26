package io.github.chronosauros.contour.model

import io.github.chronosauros.contour.core.Band
import io.github.chronosauros.contour.core.Profile
import kotlinx.serialization.Serializable

/** The part of a profile that UNDO / REDO / LAST SENT bring back: the bands and the preamp. */
@Serializable
data class EqState(val bands: List<Band>, val preampDb: Double? = null) {
    constructor(p: Profile) : this(p.bands, p.preampDb)
}

/**
 * One profile's edit history. [undo] ends with the most recent step; [sent] is the EQ as it was last
 * verified on the DAC (null until the first send from a build that keeps it).
 */
@Serializable
data class ProfileHistory(
    val undo: List<EqState> = emptyList(),
    val redo: List<EqState> = emptyList(),
    val sent: EqState? = null,
)

/** filesDir/history.json: every profile's history, by profile id. */
@Serializable
data class HistoryFile(val profiles: Map<String, ProfileHistory> = emptyMap())
