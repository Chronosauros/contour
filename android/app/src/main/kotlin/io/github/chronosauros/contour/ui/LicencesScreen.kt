package io.github.chronosauros.contour.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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

/** Open-source licences, reached from the service screen: NOTICE, the third-party list and the Apache License 2.0. */
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
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Open-source licences", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = onClose) { Text("Close") }
                    }
                }
                files.forEach { (name, text) ->
                    item { Text(name, style = MaterialTheme.typography.titleSmall) }
                    item { Text(text.trim(), fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp) }
                    item { HorizontalDivider() }
                }
            }
        }
    }
}
