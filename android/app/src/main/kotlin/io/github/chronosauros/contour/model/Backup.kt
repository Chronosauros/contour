package io.github.chronosauros.contour.model

import android.content.Context
import android.util.Log
import java.io.File
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
        val root = context.getExternalFilesDir(null) ?: run { Log.w(TAG, "no external files dir"); return }
        val dest = File(root, "backup/$BEFORE_V1")
        if (dest.exists()) return
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
        val tmp = File(dest.parentFile, ".${dest.name}.tmp")
        tmp.deleteRecursively()
        File(tmp, "profiles").mkdirs()
        var n = 0
        File(filesDir, "profiles").listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".json") }.forEach {
            it.copyTo(File(tmp, "profiles/${it.name}"), overwrite = true)
            n++
        }
        File(filesDir, "state.json").takeIf { it.isFile }?.let {
            it.copyTo(File(tmp, "state.json"), overwrite = true)
            n++
        }
        if (!tmp.renameTo(dest)) Log.w(TAG, "could not rename ${tmp.name}")
        return n
    }
}
