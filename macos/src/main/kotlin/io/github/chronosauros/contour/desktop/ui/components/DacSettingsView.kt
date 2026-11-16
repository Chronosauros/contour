package io.github.chronosauros.contour.desktop.ui.components

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.chronosauros.contour.core.native.DacSetting
import io.github.chronosauros.contour.desktop.model.DesktopAppModel
import io.github.chronosauros.contour.desktop.ui.theme.ContourColors
import kotlinx.coroutines.launch

/** Hardware settings view for supported DACs (gain, hardware filters, UAC modes). */
@Composable
fun DacSettingsView(
    model: DesktopAppModel,
    modifier: Modifier = Modifier
) {
    val controller = model.controller
    val target = model.currentTarget
    val scope = rememberCoroutineScope()
    val connected = controller.connectedHandle != null
    val settingsList = target.settings

    if (!connected) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("No DAC Connected", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ContourColors.TextSecondary)
                Text("Connect a supported USB DAC to configure internal hardware settings.", fontSize = 13.sp, color = ContourColors.TextMuted)
            }
        }
        return
    }

    if (settingsList.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(target.caps.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ContourColors.TextPrimary)
                Text("This DAC stores parametric EQ in hardware, but does not provide additional configuration registers.", fontSize = 13.sp, color = ContourColors.TextMuted)
            }
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "${target.caps.name} HARDWARE SETTINGS",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = ContourColors.TextPrimary
                )
                Text(
                    text = "Settings are stored in the DAC's non-volatile memory.",
                    fontSize = 12.sp,
                    color = ContourColors.TextSecondary
                )
            }

            Button(
                onClick = {
                    scope.launch {
                        controller.readDacSettings()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = ContourColors.SurfaceElevated),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Refresh", fontSize = 12.sp, color = ContourColors.Primary)
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(settingsList) { setting ->
                val currentReading = controller.settings.firstOrNull { it.id == setting.id }
                val currentValue = currentReading?.value ?: 0

                SettingRow(
                    setting = setting,
                    currentValue = currentValue,
                    onSelectValue = { chosenValue ->
                        scope.launch {
                            controller.writeSetting(setting.id, chosenValue)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun SettingRow(
    setting: DacSetting,
    currentValue: Int,
    onSelectValue: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(ContourColors.Surface)
            .border(1.dp, ContourColors.SurfaceBorder, RoundedCornerShape(10.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = setting.label,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = ContourColors.TextPrimary
            )
        }

        if (setting.options.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                setting.options.forEachIndexed { optIndex, optLabel ->
                    val isSelected = optIndex == currentValue
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) ContourColors.Primary else ContourColors.SurfaceElevated)
                            .border(1.dp, if (isSelected) ContourColors.Primary else ContourColors.SurfaceBorder, RoundedCornerShape(6.dp))
                            .clickable { onSelectValue(optIndex) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = optLabel,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) Color.Black else ContourColors.TextPrimary
                        )
                    }
                }
            }
        }
    }
}
