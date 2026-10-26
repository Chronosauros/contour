package io.github.chronosauros.contour.ui

import androidx.activity.compose.BackHandler
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The repo's licence files as the build copies them into assets/licences/ (see app/build.gradle.kts). */
private val LICENCE_FILES = listOf("NOTICE", "THIRD_PARTY_NOTICES.md", "LICENSE")

/** The repo-root PRIVACY.md, copied into assets/licences/ by the same build step (the one source of the in-app policy text). */
private const val PRIVACY_FILE = "PRIVACY.md"
private const val PRIVACY_URL = "https://contoureq.app/privacy/"

/**
 * PRIVACY.md as blocks for the screen (heading?, text): a "#" line is a heading of its own, a blank line ends a paragraph,
 * a line starting with "- " or "* " starts a bullet (shown with a dot), any other line continues the current paragraph
 * or bullet (joined with one space, so the hard wrap of the source does not show). Bold and backtick markers are dropped.
 */
private fun privacyBlocks(md: String): List<Pair<Boolean, String>> {
    val out = mutableListOf<Pair<Boolean, String>>()
    var cur: StringBuilder? = null
    fun flush() { cur?.let { out += false to it.toString().replace("**", "").replace("`", "") }; cur = null }
    for (raw in md.lines()) {
        val line = raw.trim()
        when {
            line.isEmpty() -> flush()
            line.startsWith("#") -> { flush(); out += true to line.trimStart('#', ' ').replace("**", "").replace("`", "") }
            line.startsWith("- ") || line.startsWith("* ") -> { flush(); cur = StringBuilder("\u2022 ").append(line.substring(2).trim()) }
            else -> cur?.append(' ')?.append(line) ?: run { cur = StringBuilder(line) }
        }
    }
    flush()
    return out
}

/** About, privacy & licences, reached from the foot of the library and from the service screen: the app line, the privacy policy (PRIVACY.md), NOTICE, the third-party list and the Apache License 2.0. */
@Composable
fun LicencesScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val files = remember {
        LICENCE_FILES.map { name ->
            name to runCatching { context.assets.open("licences/$name").bufferedReader().use { it.readText() } }
                .getOrDefault("(not found in this build)")
        }
    }
    val privacy = remember {
        runCatching { context.assets.open("licences/$PRIVACY_FILE").bufferedReader().use { it.readText() } }
            .getOrNull()?.let { privacyBlocks(it) } ?: listOf(false to "(not found in this build)")
    }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    }
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ABOUT, PRIVACY & LICENCES", style = Type.rowName, modifier = Modifier.weight(1f))
                        TextButton(onClick = onClose) { Text("CLOSE", style = Type.segment, color = pal.accent) }
                    }
                }
                item {
                    Text(
                        "Contour $version\nCopyright 2026 Chronosaur. Licensed under the Apache License 2.0.\n" +
                            "Source code: https://github.com/Chronosauros/contour\n" +
                            "Unofficial - not affiliated with or endorsed by CrinEar or any other brand named in the app.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                items(privacy.size) { i ->
                    val (heading, text) = privacy[i]
                    Text(text, style = if (heading) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall)
                }
                item {
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                            // no INTERNET permission here: the browser fetches the page; no browser installed = nothing happens
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL))) }
                        },
                        contentAlignment = Alignment.CenterStart,
                    ) { Text("contoureq.app/privacy", style = Type.segment, color = pal.accent) }
                }
                item { HorizontalDivider() }
                files.forEach { (name, text) ->
                    item { Text(name, style = MaterialTheme.typography.titleSmall) }
                    item { Text(text.trim(), fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp) }
                    item { HorizontalDivider() }
                }
            }
        }
    }
}
