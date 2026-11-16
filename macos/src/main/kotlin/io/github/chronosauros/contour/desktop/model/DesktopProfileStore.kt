package io.github.chronosauros.contour.desktop.model

import io.github.chronosauros.contour.core.Band
import io.github.chronosauros.contour.core.FilterType
import io.github.chronosauros.contour.core.Profile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class DesktopSavedState(
    val order: List<String> = emptyList(),
    val lastOpen: String? = null,
    val lastBand: Int = 0,
    val lastSent: String? = null,
    val seeded: Boolean = false,
)

/** Local profile persistence in ~/Library/Application Support/Contour. */
class DesktopProfileStore(baseDir: File? = null) {
    private val root = baseDir ?: File(System.getProperty("user.home"), "Library/Application Support/Contour").also { it.mkdirs() }
    private val dir = File(root, "profiles").also { it.mkdirs() }
    private val stateFile = File(root, "state.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    private fun profileFile(id: String): File = File(dir, "$id.json")

    fun loadState(): DesktopSavedState? {
        if (!stateFile.exists()) return null
        return try {
            json.decodeFromString(DesktopSavedState.serializer(), stateFile.readText())
        } catch (e: Exception) {
            null
        }
    }

    fun saveState(state: DesktopSavedState) {
        stateFile.writeText(json.encodeToString(DesktopSavedState.serializer(), state))
    }

    fun loadProfile(id: String): Profile? {
        val f = profileFile(id)
        if (!f.exists()) return null
        return try {
            json.decodeFromString(Profile.serializer(), f.readText())
        } catch (e: Exception) {
            null
        }
    }

    fun saveProfile(profile: Profile) {
        val f = profileFile(profile.id)
        f.writeText(json.encodeToString(Profile.serializer(), profile))
    }

    fun deleteProfile(id: String) {
        val f = profileFile(id)
        if (f.exists()) f.delete()
    }

    fun loadAll(): List<Profile> {
        val state = loadState()
        if (state == null || !state.seeded) {
            val defaults = seedDefaults()
            saveState(DesktopSavedState(defaults.map { it.id }, defaults.first().id, 0, null, seeded = true))
            return defaults
        }
        val profiles = mutableListOf<Profile>()
        for (id in state.order) {
            loadProfile(id)?.let { profiles.add(it) }
        }
        dir.listFiles { _, name -> name.endsWith(".json") }?.forEach { f ->
            val id = f.nameWithoutExtension
            if (!state.order.contains(id)) {
                loadProfile(id)?.let { profiles.add(it) }
            }
        }
        if (profiles.isEmpty()) {
            return seedDefaults()
        }
        return profiles
    }

    private fun seedDefaults(): List<Profile> {
        val now = System.currentTimeMillis()
        val bassBoost = Profile(
            id = "bass-boost",
            name = "BASS BOOST",
            subtitle = "Warm low-end lift",
            bands = listOf(
                Band("b1", FilterType.LOW_SHELF, 105.0, 4.0, 0.71, true),
                Band("b2", FilterType.PEAK, 250.0, -1.0, 1.0, true),
                Band("b3", FilterType.PEAK, 1000.0, 0.0, 1.0, true),
                Band("b4", FilterType.PEAK, 3000.0, 0.0, 1.0, true),
                Band("b5", FilterType.PEAK, 6000.0, 0.0, 1.0, true),
                Band("b6", FilterType.PEAK, 8000.0, 0.0, 1.0, true),
                Band("b7", FilterType.PEAK, 10000.0, 0.0, 1.0, true),
                Band("b8", FilterType.HIGH_SHELF, 12000.0, 0.0, 0.71, true),
            ),
            preampDb = -4.0,
            createdAt = now,
            updatedAt = now
        )

        val softerVocals = Profile(
            id = "softer-vocals",
            name = "SOFTER VOCALS",
            subtitle = "Tames harsh 3 kHz shout",
            bands = listOf(
                Band("b1", FilterType.LOW_SHELF, 80.0, 0.0, 0.71, true),
                Band("b2", FilterType.PEAK, 200.0, 0.0, 1.0, true),
                Band("b3", FilterType.PEAK, 1000.0, 0.0, 1.0, true),
                Band("b4", FilterType.PEAK, 3000.0, -2.5, 1.5, true),
                Band("b5", FilterType.PEAK, 5000.0, -1.0, 2.0, true),
                Band("b6", FilterType.PEAK, 8000.0, 0.0, 1.0, true),
                Band("b7", FilterType.PEAK, 10000.0, 0.0, 1.0, true),
                Band("b8", FilterType.HIGH_SHELF, 12000.0, 0.0, 0.71, true),
            ),
            preampDb = 0.0,
            createdAt = now,
            updatedAt = now
        )

        val trebleBoost = Profile(
            id = "treble-boost",
            name = "TREBLE BOOST",
            subtitle = "Air and detail brilliance",
            bands = listOf(
                Band("b1", FilterType.LOW_SHELF, 100.0, 0.0, 0.71, true),
                Band("b2", FilterType.PEAK, 500.0, 0.0, 1.0, true),
                Band("b3", FilterType.PEAK, 1500.0, 0.0, 1.0, true),
                Band("b4", FilterType.PEAK, 3000.0, 0.0, 1.0, true),
                Band("b5", FilterType.PEAK, 6000.0, 1.5, 1.2, true),
                Band("b6", FilterType.PEAK, 8000.0, 2.0, 1.5, true),
                Band("b7", FilterType.HIGH_SHELF, 10000.0, 3.5, 0.71, true),
                Band("b8", FilterType.PEAK, 14000.0, 1.0, 2.0, true),
            ),
            preampDb = -3.5,
            createdAt = now,
            updatedAt = now
        )

        val flat = Profile(
            id = "flat-neutral",
            name = "FLAT / NEUTRAL",
            subtitle = "Reference line",
            bands = listOf(
                Band("b1", FilterType.PEAK, 32.0, 0.0, 1.0, true),
                Band("b2", FilterType.PEAK, 64.0, 0.0, 1.0, true),
                Band("b3", FilterType.PEAK, 125.0, 0.0, 1.0, true),
                Band("b4", FilterType.PEAK, 250.0, 0.0, 1.0, true),
                Band("b5", FilterType.PEAK, 500.0, 0.0, 1.0, true),
                Band("b6", FilterType.PEAK, 1000.0, 0.0, 1.0, true),
                Band("b7", FilterType.PEAK, 2000.0, 0.0, 1.0, true),
                Band("b8", FilterType.PEAK, 4000.0, 0.0, 1.0, true),
            ),
            preampDb = 0.0,
            createdAt = now,
            updatedAt = now
        )

        val list = listOf(bassBoost, softerVocals, trebleBoost, flat)
        list.forEach { saveProfile(it) }
        return list
    }
}
