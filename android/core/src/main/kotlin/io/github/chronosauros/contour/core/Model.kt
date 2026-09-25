package io.github.chronosauros.contour.core

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** Filter shapes the editor knows. Which of them a device accepts is in its [DeviceCapabilities]. */
@Serializable
enum class FilterType { PEAK, LOW_SHELF, HIGH_SHELF, LOW_PASS, HIGH_PASS }

/**
 * One EQ band as the user means it (the intended filter, before any device compensation).
 * [gainDb] is ignored by the pass filters.
 */
@Serializable
data class Band(
    val id: String,
    val type: FilterType,
    val freqHz: Double,
    val gainDb: Double,
    val q: Double,
    val enabled: Boolean = true,
)

/**
 * A named profile in the library. [preampDb] = null means auto (see [Preamp.auto]).
 * Times are epoch milliseconds. [archived] is written only when true, so an active profile's file keeps
 * the pre-v1 bytes (older files without the field load as active).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val subtitle: String = "",
    val icon: String = "",
    val bands: List<Band>,
    val preampDb: Double? = null,
    val createdAt: Long,
    val updatedAt: Long,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val archived: Boolean = false,
) {
    /** The preamp that goes with this profile: the stored value, or the auto value when none is stored. */
    fun effectivePreampDb(): Double = preampDb ?: Preamp.auto(bands).toDouble()
}
