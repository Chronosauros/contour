package io.github.chronosauros.contour.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.chronosauros.contour.desktop.model.DesktopAppModel
import io.github.chronosauros.contour.desktop.model.DesktopNavPage
import io.github.chronosauros.contour.desktop.ui.components.AboutDialog
import io.github.chronosauros.contour.desktop.ui.components.DacSettingsView
import io.github.chronosauros.contour.desktop.ui.components.LibraryView
import io.github.chronosauros.contour.desktop.ui.components.TuneView
import io.github.chronosauros.contour.desktop.ui.theme.ContourColors
import io.github.chronosauros.contour.desktop.ui.theme.ContourTheme

/** Main desktop application window: top bar navigation and active view. */
@Composable
fun MainScreen(model: DesktopAppModel) {
    ContourTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ContourColors.Background)
        ) {
            TopBar(model = model)

            Box(modifier = Modifier.weight(1f)) {
                when (model.currentPage) {
                    DesktopNavPage.TUNE -> TuneView(model = model)
                    DesktopNavPage.LIBRARY -> LibraryView(model = model)
                    DesktopNavPage.DAC_SETTINGS -> DacSettingsView(model = model)
                }
            }
        }

        if (model.showAboutDialog) {
            AboutDialog(
                model = model,
                onDismiss = { model.showAboutDialog = false }
            )
        }
    }
}

@Composable
fun TopBar(model: DesktopAppModel) {
    val connected = model.controller.connectedHandle != null
    val target = model.currentTarget
    val hasDacSettings = target.settings.isNotEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(ContourColors.Surface)
            .border(1.dp, ContourColors.SurfaceBorder)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // brand tile
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(ContourColors.NeonPink, ContourColors.NeonCyan)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text("C", fontWeight = FontWeight.Black, fontSize = 16.sp, color = Color.Black)
            }

            Column(verticalArrangement = Arrangement.Center) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "CONTOUR",
                        fontWeight = FontWeight.Black,
                        fontSize = 17.sp,
                        letterSpacing = 3.sp,
                        color = ContourColors.TextPrimary
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(ContourColors.SurfaceElevated)
                            .border(1.dp, ContourColors.NeonCyan.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "macOS",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            color = ContourColors.NeonCyan
                        )
                    }
                }
                Text(
                    text = "PARAMETRIC EQ FOR USB DACS",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = ContourColors.NeonPurple
                )
            }
        }

        // navigation tabs: Tune, Library, and (when connected) DAC Settings
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(ContourColors.SurfaceElevated)
                .border(1.dp, ContourColors.SurfaceBorder, RoundedCornerShape(10.dp))
                .padding(4.dp)
        ) {
            NavTabItem(
                title = "TUNE",
                selected = model.currentPage == DesktopNavPage.TUNE,
                onClick = { model.currentPage = DesktopNavPage.TUNE }
            )

            NavTabItem(
                title = "LIBRARY",
                selected = model.currentPage == DesktopNavPage.LIBRARY,
                onClick = { model.currentPage = DesktopNavPage.LIBRARY }
            )

            if (connected && hasDacSettings) {
                NavTabItem(
                    title = "DAC SETTINGS",
                    selected = model.currentPage == DesktopNavPage.DAC_SETTINGS,
                    onClick = { model.currentPage = DesktopNavPage.DAC_SETTINGS }
                )
            }
        }

        // status and about trigger
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(ContourColors.SurfaceElevated)
                    .border(
                        width = 1.dp,
                        color = if (connected) ContourColors.NeonGreen.copy(alpha = 0.6f) else ContourColors.SurfaceBorder,
                        shape = RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (connected) ContourColors.NeonGreen else ContourColors.TextMuted)
                )

                Text(
                    text = if (connected) target.caps.name else "NO DAC CONNECTED",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (connected) ContourColors.TextPrimary else ContourColors.TextMuted
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(ContourColors.SurfaceElevated)
                    .border(1.dp, ContourColors.SurfaceBorder, RoundedCornerShape(6.dp))
                    .clickable { model.showAboutDialog = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "About",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = ContourColors.TextSecondary
                )
            }
        }
    }
}

@Composable
fun NavTabItem(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .then(
                if (selected) {
                    Modifier.background(
                        Brush.horizontalGradient(
                            listOf(ContourColors.NeonPink, ContourColors.NeonPurple)
                        )
                    )
                } else {
                    Modifier.background(Color.Transparent)
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp,
            color = if (selected) Color.White else ContourColors.TextSecondary
        )
    }
}
