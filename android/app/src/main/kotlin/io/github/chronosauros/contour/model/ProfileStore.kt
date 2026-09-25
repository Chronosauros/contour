package io.github.chronosauros.contour.model

import android.util.Log
import io.github.chronosauros.contour.core.Profile
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** filesDir/state.json: everything the library needs besides the profiles themselves. */
@Serializable
data class SavedState(
    val order: List<String> = emptyList(),
    val lastOpen: String? = null,
    val lastBand: Int = 0,
    val lastSent: String? = null,
    val seeded: Boolean = false,
)

/**
 * One JSON file per profile in filesDir/profiles/<id>.json plus filesDir/state.json. Writes are atomic
 * (temp file + rename). Call from a background thread.
 */
class ProfileStore(filesDir: File) {
    private val dir = File(filesDir, "profiles")
    private val stateFile = File(filesDir, "state.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }

    val hasState: Boolean get() = stateFile.exists()

    fun loadProfiles(): List<Profile> = dir.listFiles().orEmpty()
        .filter { it.name.endsWith(".json") }
        .mapNotNull { f ->
            runCatching { json.decodeFromString(Profile.serializer(), f.readText()) }
                .onFailure { Log.w(TAG, "unreadable profile ${f.name}: ${it.message}") }
                .getOrNull()
        }

    fun loadState(): SavedState? = if (!stateFile.exists()) null else
        runCatching { json.decodeFromString(SavedState.serializer(), stateFile.readText()) }.getOrNull()

    // Every write and delete, of profiles and of state.json, takes this store's one lock (@Synchronized):
    // the temp name is fixed per file, so two concurrent writes must never overlap.
    @Synchronized
    fun save(p: Profile) {
        dir.mkdirs()
        atomicWrite(File(dir, "${p.id}.json"), json.encodeToString(Profile.serializer(), p))
    }

    @Synchronized
    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }

    @Synchronized
    fun saveState(s: SavedState) = atomicWrite(stateFile, json.encodeToString(SavedState.serializer(), s))

    /** Temp file + rename. It never deletes the target: if the rename fails, the old file stays and only the temp goes. */
    private fun atomicWrite(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            Log.e(TAG, "could not write ${target.name}: rename failed, the previous file is kept")
        }
    }

    companion object {
        const val TAG = "ContourStore"
    }
}
