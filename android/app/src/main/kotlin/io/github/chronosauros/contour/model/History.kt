package io.github.chronosauros.contour.model

import io.github.chronosauros.contour.core.Band
import io.github.chronosauros.contour.core.Profile
import kotlinx.serialization.Serializable

/** The part of a profile that UNDO / REDO / REVERT bring back: the bands and the preamp. */
@Serializable
data class EqState(val bands: List<Band>, val preampDb: Double? = null) {
    constructor(p: Profile) : this(p.bands, p.preampDb)
}

/**
 * One profile's edit history. [undo] ends with the most recent step; [sent] is the SAVED version (every profile has one): the EQ as it was
 * last verified on the DAC, imported (PASTE, a file, FROM DAC) or saved explicitly in the Library (SAVE ALL, SAVE) or when the profile was created;
 * null until one of those happens.
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
