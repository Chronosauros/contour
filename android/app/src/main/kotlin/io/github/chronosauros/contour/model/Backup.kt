package io.github.chronosauros.contour.model

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Copies of the owner's library files in the app's external files dir (readable with adb):
 * `Android/data/<pkg>/files/backup/<name>/profiles/<id>.json` and `.../state.json`, byte for byte.
 */
object Backup {
    const val TAG = "ContourBackup"
    const val BEFORE_V1 = "2026-09-25-before-v1"

    /**
     * First start of v1: before anything is loaded or written, copy the library once. A folder that already
     * exists is never touched again. The copy goes to a temp folder first and is renamed when complete.
     */
    fun beforeV1(context: Context) {
        val root = context.getExternalFilesDir(null) ?: throw IOException("No external backup directory")
        val dest = File(root, "backup/$BEFORE_V1")
        val parent = requireNotNull(dest.parentFile)
        if (parent.canonicalFile.parentFile != root.canonicalFile ||
            dest.canonicalFile.parentFile != parent.canonicalFile || dest.canonicalFile.name != dest.name)
            throw IOException("Unsafe backup path")
        if (dest.exists()) {
            if (!dest.isDirectory) throw IOException("Backup destination is not a directory")
            return
        }
        val n = copyLibrary(context.filesDir, dest)
        Log.i(TAG, "backup $BEFORE_V1: $n files")
    }

    /** Review hook `--es dump profiles`: the library files as they are on disk now. */
    fun dump(context: Context): String? {
        val root = context.getExternalFilesDir(null) ?: return null
        val name = "dump-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val dest = File(root, "backup/$name")
        val n = copyLibrary(context.filesDir, dest)
        // the originals' modification times (the copies get new ones): "<ISO time> <epoch ms> <path>"
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ROOT)
        val lines = (File(context.filesDir, "profiles").listFiles().orEmpty().filter { it.isFile }.map { "profiles/${it.name}" to it } +
            listOfNotNull(File(context.filesDir, "state.json").takeIf { it.isFile }?.let { "state.json" to it }))
            .sortedBy { it.first }
            .joinToString("\n", postfix = "\n") { (p, f) -> "${fmt.format(Date(f.lastModified()))} ${f.lastModified()} $p" }
        File(dest, "mtimes.txt").writeText(lines)
        Log.i(TAG, "dump $name: $n files")
        return name
    }

    private fun copyLibrary(filesDir: File, dest: File): Int {
        val parent = requireNotNull(dest.parentFile)
        val tmp = File(parent, ".${dest.name}.tmp")
        if (tmp.canonicalFile.parentFile != parent.canonicalFile || tmp.canonicalFile.name != tmp.name)
            throw IOException("Unsafe temporary backup path")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create backup directory")
        if (tmp.exists() && !tmp.deleteRecursively()) throw IOException("Cannot clear incomplete backup")
        if (!File(tmp, "profiles").mkdirs()) throw IOException("Cannot create backup profiles directory")
        if (!filesDir.isDirectory || filesDir.listFiles() == null) throw IOException("Cannot read source library")
        var n = 0
        val source = File(filesDir, "profiles")
        val state = File(filesDir, "state.json")
        if (source.exists() && source.canonicalFile.parentFile != filesDir.canonicalFile)
            throw IOException("Unsafe source profiles directory")
        if (!source.exists() && state.exists()) throw IOException("Source profiles directory missing")
        val profiles = if (source.exists()) source.listFiles() ?: throw IOException("Cannot list source profiles") else emptyArray()
        profiles.filter { it.name.endsWith(".json") }.forEach {
            val id = it.name.removeSuffix(".json")
            if (!Regex("[A-Za-z0-9_-]{1,64}").matches(id) || it.canonicalFile.parentFile != source.canonicalFile)
                throw IOException("Unsafe source profile path")
            if (!it.isFile) throw IOException("Unreadable source profile ${it.name}")
            it.copyTo(File(tmp, "profiles/${it.name}"), overwrite = true)
            n++
        }
        if (state.exists() && (state.canonicalFile.parentFile != filesDir.canonicalFile || !state.isFile))
            throw IOException("Unreadable or unsafe source state")
        state.takeIf { it.isFile }?.let {
            it.copyTo(File(tmp, "state.json"), overwrite = true)
            n++
        }
        if (!tmp.renameTo(dest)) throw IOException("Cannot finalize backup ${dest.name}")
        return n
    }
}
