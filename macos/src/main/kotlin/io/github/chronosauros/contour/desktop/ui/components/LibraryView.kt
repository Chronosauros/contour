package io.github.chronosauros.contour.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.chronosauros.contour.core.Dsp
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.desktop.model.DesktopAppModel
import io.github.chronosauros.contour.desktop.model.DesktopNavPage
import io.github.chronosauros.contour.desktop.ui.theme.ContourColors
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/** Library view: lists saved EQ profiles, curve thumbnails, import/export, and management actions. */
@Composable
fun LibraryView(
    model: DesktopAppModel,
    modifier: Modifier = Modifier
) {
    var showImportDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf<Profile?>(null) }
    var renameText by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // top toolbar: profile count, import, and creation actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "EQ PROFILES (${model.profiles.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = ContourColors.TextPrimary
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (model.controller.connectedHandle != null) {
                    Button(
                        onClick = {
                            val imported = model.importFromDac()
                            if (imported != null) {
                                model.currentPage = DesktopNavPage.TUNE
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ContourColors.SurfaceElevated),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Read from DAC as Profile", fontSize = 12.sp, color = ContourColors.Primary)
                    }
                }

                Button(
                    onClick = { showImportDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = ContourColors.SurfaceElevated),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Import AutoEQ / Text", fontSize = 12.sp, color = ContourColors.Primary)
                }

                Button(
                    onClick = {
                        val p = model.createProfile("NEW PROFILE")
                        model.currentPage = DesktopNavPage.TUNE
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ContourColors.Primary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("+ New Profile", fontSize = 12.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }

        // saved profiles list
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(model.profiles, key = { it.id }) { profile ->
                val isSelected = profile.id == model.currentProfileId
                val isOnDac = profile.id == model.onDacProfileId

                ProfileCard(
                    profile = profile,
                    isSelected = isSelected,
                    isOnDac = isOnDac,
                    onSelect = {
                        model.selectProfile(profile.id)
                        model.currentPage = DesktopNavPage.TUNE
                    },
                    onDuplicate = { model.duplicateProfile(profile) },
                    onRename = {
                        renameText = profile.name
                        showRenameDialog = profile
                    },
                    onDelete = { model.deleteProfile(profile.id) },
                    onExport = {
                        val text = model.exportApoText(profile)
                        val selection = StringSelection(text)
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
                    }
                )
            }
        }
    }

    // rename dialog
    if (showRenameDialog != null) {
        val targetProfile = showRenameDialog!!
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text("Rename Profile", color = ContourColors.TextPrimary) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Profile Name") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = ContourColors.TextPrimary,
                        unfocusedTextColor = ContourColors.TextPrimary,
                        focusedBorderColor = ContourColors.Primary,
                        unfocusedBorderColor = ContourColors.SurfaceBorder
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        model.renameProfile(targetProfile.id, renameText)
                        showRenameDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ContourColors.Primary)
                ) {
                    Text("Save", color = Color.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) {
                    Text("Cancel", color = ContourColors.TextSecondary)
                }
            },
            containerColor = ContourColors.SurfaceElevated
        )
    }

    // import dialog: parses AutoEQ and Equalizer APO parametric text
    if (showImportDialog) {
        var importInput by remember { mutableStateOf("") }
        var importName by remember { mutableStateOf("IMPORTED") }
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import Parametric EQ (AutoEQ / Equalizer APO)", color = ContourColors.TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = importName,
                        onValueChange = { importName = it },
                        label = { Text("Profile Name") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = ContourColors.TextPrimary,
                            unfocusedTextColor = ContourColors.TextPrimary,
                            focusedBorderColor = ContourColors.Primary,
                            unfocusedBorderColor = ContourColors.SurfaceBorder
                        )
                    )
                    OutlinedTextField(
                        value = importInput,
                        onValueChange = { importInput = it },
                        label = { Text("Paste APO Text / AutoEQ Config") },
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = ContourColors.TextPrimary,
                            unfocusedTextColor = ContourColors.TextPrimary,
                            focusedBorderColor = ContourColors.Primary,
                            unfocusedBorderColor = ContourColors.SurfaceBorder
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val p = model.importApoText(importInput, importName)
                        if (p != null) {
                            showImportDialog = false
                            model.currentPage = DesktopNavPage.TUNE
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ContourColors.Primary),
                    enabled = importInput.isNotBlank()
                ) {
                    Text("Import", color = Color.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("Cancel", color = ContourColors.TextSecondary)
                }
            },
            containerColor = ContourColors.SurfaceElevated
        )
    }
}

/** Individual profile card showing curve thumbnail and action icons. */
@Composable
fun ProfileCard(
    profile: Profile,
    isSelected: Boolean,
    isOnDac: Boolean,
    onSelect: () -> Unit,
    onDuplicate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) ContourColors.SurfaceElevated else ContourColors.Surface)
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) ContourColors.Primary else ContourColors.SurfaceBorder,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onSelect)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CurveThumbnail(profile = profile, modifier = Modifier.size(90.dp, 48.dp))

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = profile.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = ContourColors.TextPrimary
                    )
                    if (isOnDac) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(ContourColors.AccentGreen.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "ON DAC",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = ContourColors.AccentGreen
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "${profile.bands.size} bands",
                        fontSize = 12.sp,
                        color = ContourColors.TextSecondary
                    )
                    Text(
                        text = "Preamp: ${profile.preampDb?.let { "%+.1f dB".format(it) } ?: "Auto"}",
                        fontSize = 12.sp,
                        color = ContourColors.TextSecondary
                    )
                    if (profile.subtitle.isNotBlank()) {
                        Text(
                            text = profile.subtitle,
                            fontSize = 12.sp,
                            color = ContourColors.TextMuted
                        )
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = onSelect,
                colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) ContourColors.Primary else ContourColors.Surface),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (isSelected) "Editing" else "Edit in Tune",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) Color.Black else ContourColors.TextPrimary
                )
            }

            IconButton(onClick = onDuplicate, modifier = Modifier.size(32.dp)) {
                Text("📋", fontSize = 13.sp)
            }

            IconButton(onClick = onRename, modifier = Modifier.size(32.dp)) {
                Text("✏️", fontSize = 13.sp)
            }

            IconButton(onClick = onExport, modifier = Modifier.size(32.dp)) {
                Text("📤", fontSize = 13.sp)
            }

            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Text("🗑️", fontSize = 13.sp)
            }
        }
    }
}

/** Micro response thumbnail rendered directly from biquad evaluations. */
@Composable
fun CurveThumbnail(profile: Profile, modifier: Modifier = Modifier) {
    val curve = remember(profile) {
        val buf = DoubleArray(Dsp.DISPLAY_POINTS)
        Dsp.responseDb(profile.bands, Dsp.DISPLAY_GRID, buf)
        buf
    }

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(ContourColors.Background)
            .border(1.dp, ContourColors.SurfaceBorderGlow.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
    ) {
        val w = size.width
        val h = size.height
        val path = Path()
        val y0 = h / 2f

        for (i in 0 until Dsp.DISPLAY_POINTS) {
            val px = (i.toFloat() / (Dsp.DISPLAY_POINTS - 1)) * w
            val py = (y0 - (curve[i].toFloat() / 15f) * (h / 2.2f)).coerceIn(2f, h - 2f)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }

        drawPath(
            path = path,
            brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                listOf(ContourColors.NeonCyan, ContourColors.NeonPink)
            ),
            style = Stroke(width = 2.0f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}
