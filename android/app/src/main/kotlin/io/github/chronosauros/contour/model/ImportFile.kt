package io.github.chronosauros.contour.model

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import io.github.chronosauros.contour.core.ApoText
import io.github.chronosauros.contour.core.ImportedEq
import io.github.chronosauros.contour.core.LibraryFile
import io.github.chronosauros.contour.core.LibraryRead
import io.github.chronosauros.contour.R
import io.github.chronosauros.contour.ui.UiText
import java.io.ByteArrayOutputStream

/**
 * An EQ text that came from outside the app (a file picked, opened with Contour, shared to Contour), read and parsed:
 * either [eq] with the profile [name] it should get, or an [error] to show. Nothing is created from it until the user
 * says yes (a picked file: the pick was the yes).
 */
data class Incoming(
    val name: String?,
    val eq: ImportedEq?,
    val error: UiText?,
    /** Where it came from, to read it again after the Activity is recreated while the dialog is open: a content URI, or the shared text with its subject. */
    val uri: String? = null,
    val text: String? = null,
    val subject: String? = null,
)

/** Reads .txt files from content URIs (no storage permission: the URI carries the grant) and turns them into [Incoming]. */
object ImportFile {
    /** An EQ text is a few KB; anything bigger is not one. */
    const val MAX_BYTES = 256 * 1024

    /** A library backup holds every profile twice (working copy and SAVED version): its own, bigger cap. */
    const val MAX_LIBRARY_BYTES = 8 * 1024 * 1024

    val UNREADABLE: UiText get() = UiText.res(R.string.import_error_unreadable)
    val NOT_CONTENT: UiText get() = UiText.res(R.string.import_error_not_content)
    val TOO_BIG: UiText get() = UiText.res(R.string.import_error_too_big)
    val NO_FILTERS: UiText get() = UiText.res(R.string.import_error_no_filters)
    val IS_BACKUP: UiText get() = UiText.res(R.string.import_error_is_backup)
    val NOT_BACKUP: UiText get() = UiText.res(R.string.restore_not_backup)
    val BACKUP_TOO_BIG: UiText get() = UiText.res(R.string.restore_too_big)

    /** UTF-8 (a BOM is dropped); the UTF-16 files Windows Notepad can save are read too. */
    fun decode(bytes: ByteArray): String {
        fun starts(vararg b: Int) = bytes.size >= b.size && b.indices.all { bytes[it] == b[it].toByte() }
        return when {
            starts(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            starts(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> String(bytes, Charsets.UTF_8)
        }.removePrefix("﻿")
    }

    /** The file's text, or null with the reason in the second value ([tooBig] when it is over [max] bytes). Blocking: call off the main thread. */
    private fun read(resolver: ContentResolver, uri: Uri, max: Int = MAX_BYTES, tooBig: UiText = TOO_BIG): Pair<String?, UiText?> = try {
        resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                if (out.size() + n > max) return null to tooBig
                out.write(chunk, 0, n)
            }
            decode(out.toByteArray()) to null
        } ?: (null to UNREADABLE)
    } catch (e: Exception) {
        null to UNREADABLE
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? {
        val fromProvider = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
        return fromProvider ?: uri.lastPathSegment
    }

    /** Parse [text] for import; [fileName] only names the profile. */
    fun fromText(text: String?, fileName: String?): Incoming {
        val name = ApoText.nameFromFile(fileName, AppModel.NAME_MAX)
        // a whole-library backup is no EQ: say what it is and where it goes (RESTORE FROM BACKUP), not "no filters"
        if (text != null && LibraryFile.decode(text) is LibraryRead.Library) return Incoming(name, null, IS_BACKUP)
        val eq = Importer.parse(text) ?: return Incoming(name, null, NO_FILTERS)
        return Incoming(name, eq, null, text = text, subject = fileName)
    }

    /** Blocking (file IO): call off the main thread. */
    fun fromUri(resolver: ContentResolver, uri: Uri): Incoming {
        // content:// only: a file:// URI from another app could point at Contour's own private files
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return Incoming(null, null, NOT_CONTENT)
        val (text, error) = read(resolver, uri)
        if (text == null) return Incoming(null, null, error ?: UNREADABLE)
        return fromText(text, displayName(resolver, uri)).copy(uri = uri.toString(), text = null, subject = null)
    }

    /**
     * RESTORE FROM BACKUP: the library file at [uri] (content:// only, up to [MAX_LIBRARY_BYTES]), read and decoded; the reason as the second value when it is not one.
     * Blocking (file IO): call off the main thread.
     */
    fun libraryFromUri(resolver: ContentResolver, uri: Uri): Pair<LibraryRead.Library?, UiText?> {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null to NOT_CONTENT
        val (text, error) = read(resolver, uri, MAX_LIBRARY_BYTES, BACKUP_TOO_BIG)
        if (text == null) return null to (error ?: UNREADABLE)
        return when (val library = LibraryFile.decode(text)) {
            is LibraryRead.Library -> library to null
            LibraryRead.NotLibrary -> null to NOT_BACKUP
        }
    }

    /**
     * What an intent from outside asks for: a text file opened with Contour (VIEW) or shared to it (SEND with a
     * stream or with text); null for any other intent. Blocking: call off the main thread.
     */
    fun fromIntent(resolver: ContentResolver, intent: Intent?): Incoming? {
        when (intent?.action) {
            Intent.ACTION_VIEW -> return intent.data?.let { fromUri(resolver, it) }
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { return fromUri(resolver, it) }
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: return null
                if (text.length > MAX_BYTES) return Incoming(null, null, TOO_BIG)
                // Contour's own SHARE puts the profile name in the subject.
                return fromText(text, intent.getStringExtra(Intent.EXTRA_SUBJECT))
            }
            else -> return null
        }
    }

    /**
     * Creates the profile from [incoming] for the device the app is on now (the same fitting as PASTE) and opens it in
     * Tune. The one-line report goes to [AppModel.notice]. False when there was nothing to create.
     */
    fun create(model: AppModel, incoming: Incoming): Boolean {
        val raw = incoming.eq ?: return false
        val (eq, report) = Importer.fit(raw, model.importTarget)
        if (eq.bands.isEmpty()) {
            model.notice = Notice(if (report == null) UiText.res(R.string.import_nothing) else UiText.res(R.string.import_nothing_report, report), long = report != null)
            return false
        }
        model.create(eq.bands, eq.preampDb, name = incoming.name)
        val n = eq.bands.size
        model.notice = Notice(if (report == null) UiText.plural(R.plurals.import_imported, n, n) else UiText.plural(R.plurals.import_imported_report, n, n, report), long = report != null)
        return true
    }
}
