package io.github.chronosauros.contour.model

import io.github.chronosauros.contour.core.Profile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
    val deletedIds: Set<String> = emptySet(),
)

/** Per-profile JSON and state.json. All methods are invoked by the process-owned IO worker. */
class ProfileStore(filesDir: File) {
    private val root = filesDir
    private val dir = File(root, "profiles")
    private val stateFile = File(root, "state.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }
    private val safeId = Regex("[A-Za-z0-9_-]{1,64}")

    private fun validate(id: String) {
        if (!safeId.matches(id)) throw IOException("Invalid profile id")
    }

    // Check canonical containment as well as the strict single-component id (including symlinked directories/files).
    private fun confined(file: File, parent: File): File {
        if (file.canonicalFile.parentFile != parent.canonicalFile || file.canonicalFile.name != file.name ||
            (parent != root && parent.canonicalFile.parentFile != root.canonicalFile))
            throw IOException("Storage path escapes app directory")
        return file
    }

    private fun profileFile(id: String): File {
        validate(id)
        return confined(File(dir, "$id.json"), dir)
    }

    fun loadState(): SavedState? {
        if (!root.isDirectory || root.listFiles() == null) throw IOException("Cannot read app storage")
        val statePath = confined(stateFile, root)
        val state = if (!statePath.exists()) null else
            json.decodeFromString(SavedState.serializer(), statePath.readText())
        if (state != null) {
            (state.order + state.deletedIds + listOfNotNull(state.lastOpen, state.lastSent)).forEach(::validate)
            if (state.order.size != state.order.toSet().size) throw IOException("Duplicate ids in state order")
            // Do not normalize a partially missing library into a new state.json.
            state.order.filterNot { it in state.deletedIds }.forEach { id ->
                if (!profileFile(id).isFile) throw IOException("Missing profile $id; state preserved")
            }
        }
        return state
    }

    fun loadProfiles(deletedIds: Set<String> = emptySet()): List<Profile> {
        deletedIds.forEach(::validate)
        val files = if (dir.exists()) {
            if (dir.canonicalFile.parentFile != root.canonicalFile) throw IOException("Profiles path escapes app directory")
            dir.listFiles() ?: throw IOException("Cannot list profiles")
        } else emptyArray()
        return files.filter { it.name.endsWith(".json") }.mapNotNull { file ->
            val id = file.name.removeSuffix(".json")
            val path = profileFile(id)
            if (id in deletedIds) null else {
                val profile = json.decodeFromString(Profile.serializer(), path.readText())
                if (profile.id != id) throw IOException("Profile id does not match file $id")
                profile
            }
        }
    }

    fun save(p: Profile) {
        validate(p.id)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create profiles directory")
        atomicWrite(profileFile(p.id), json.encodeToString(Profile.serializer(), p))
    }

    fun delete(id: String) {
        val file = profileFile(id)
        if (dir.exists() && dir.listFiles() == null) throw IOException("Cannot inspect profiles directory")
        if (file.exists() && !file.delete()) throw IOException("Cannot delete profile $id")
    }

    fun saveState(s: SavedState) {
        (s.order + s.deletedIds + listOfNotNull(s.lastOpen, s.lastSent)).forEach(::validate)
        atomicWrite(confined(stateFile, root), json.encodeToString(SavedState.serializer(), s))
    }

    /** fsync temp then rename without removing the previous target. Directory rename metadata is not fsynced. */
    private fun atomicWrite(target: File, text: String) {
        if (target.parentFile?.isDirectory != true && target.parentFile?.mkdirs() != true)
            throw IOException("Cannot create storage directory")
        val tmp = confined(File(target.parentFile, ".${target.name}.tmp"), target.parentFile!!)
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Cannot replace ${target.name}; previous file kept")
        }
    }

    companion object { const val TAG = "ContourStore" }
}
