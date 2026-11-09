package io.github.chronosauros.contour.desktop.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.chronosauros.contour.desktop.model.DesktopAppModel
import io.github.chronosauros.contour.desktop.ui.theme.ContourColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Global preamp control: manual attenuation slider and auto headroom calculation. */
@Composable
fun PreampBar(
    model: DesktopAppModel,
    modifier: Modifier = Modifier
) {
    val profile = model.currentProfile ?: return
    val target = model.currentTarget
    if (!target.hasPreamp) return

    val minPreamp = target.preampMin
    val maxPreamp = target.preampMax
    val currentPreamp = profile.preampDb ?: model.calculateAutoPreamp()
    val isAuto = profile.preampDb == null

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(ContourColors.Surface)
            .border(1.dp, ContourColors.SurfaceBorderGlow.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(modifier = Modifier.width(140.dp)) {
            Text(
                text = target.preampLabel,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = ContourColors.TextSecondary
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "%+.1f dB".format(currentPreamp),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = ContourColors.NeonAmber
                )
                if (isAuto) {
                    Text(
                        text = "(AUTO)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = ContourColors.NeonCyan
                    )
                }
            }
        }

        Slider(
            value = ((currentPreamp - minPreamp) / (maxPreamp - minPreamp)).toFloat().coerceIn(0f, 1f),
            onValueChange = { norm ->
                val v = minPreamp + norm * (maxPreamp - minPreamp)
                val rounded = if (target.caps.preampWholeDb) v.roundToInt().toDouble() else (v * 10).roundToInt() / 10.0
                model.setPreamp(rounded)
            },
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = ContourColors.NeonAmber,
                activeTrackColor = ContourColors.NeonAmber,
                inactiveTrackColor = ContourColors.SurfaceElevated
            )
        )

        Button(
            onClick = { model.toggleAutoPreamp() },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isAuto) ContourColors.NeonCyan else ContourColors.SurfaceElevated
            ),
            shape = RoundedCornerShape(6.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            border = if (isAuto) null else androidx.compose.foundation.BorderStroke(1.dp, ContourColors.SurfaceBorder)
        ) {
            Text(
                text = "AUTO",
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                color = if (isAuto) Color.Black else ContourColors.TextPrimary
            )
        }
    }
}

/** Device action bar: connected status, read DAC, destination slot, A/B toggle, and SEND TO DAC. */
@Composable
fun SendBar(
    model: DesktopAppModel,
    modifier: Modifier = Modifier
) {
    val profile = model.currentProfile ?: return
    val controller = model.controller
    val target = model.currentTarget
    val scope = rememberCoroutineScope()
    val connected = controller.connectedHandle != null

    var selectedSlot by remember { mutableStateOf<Int?>(target.destinationSlot) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ContourColors.SurfaceElevated)
            .border(1.dp, ContourColors.SurfaceBorderGlow, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // device identity and readback status
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (connected) ContourColors.NeonGreen else ContourColors.TextMuted)
            )

            Column {
                Text(
                    text = if (connected) target.caps.name else "NO DAC CONNECTED",
                    fontWeight = FontWeight.Black,
                    fontSize = 13.sp,
                    color = if (connected) ContourColors.TextPrimary else ContourColors.TextMuted
                )
                Text(
                    text = controller.statusMessage ?: "Ready",
                    fontSize = 11.sp,
                    color = ContourColors.NeonCyan.copy(alpha = 0.8f)
                )
            }

            if (connected) {
                Button(
                    onClick = {
                        scope.launch {
                            controller.readCurrentDac()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ContourColors.Surface),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    enabled = !controller.isReading && !controller.isSending,
                    border = androidx.compose.foundation.BorderStroke(1.dp, ContourColors.NeonCyan.copy(alpha = 0.6f))
                ) {
                    Text(
                        text = if (controller.isReading) "Reading..." else "Read DAC",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = ContourColors.NeonCyan
                    )
                }
            }
        }

        // destination slot picker (FiiO user slots)
        val userSlots = target.fiio?.userSlots
        if (connected && userSlots != null && userSlots.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                userSlots.forEach { s ->
                    val isSlotSelected = selectedSlot == s
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isSlotSelected) ContourColors.NeonCyan else ContourColors.Surface)
                            .clickable { selectedSlot = s }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = target.fiio?.slotLabels?.get(s) ?: "SLOT $s",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSlotSelected) Color.Black else ContourColors.TextSecondary
                        )
                    }
                }
            }
        }

        // comparison and flash write actions
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (connected && target.supportsAb) {
                Button(
                    onClick = {
                        scope.launch {
                            controller.toggleAb(!controller.isAbActive, profile)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (controller.isAbActive) ContourColors.NeonAmber else ContourColors.Surface
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (controller.isAbActive) ContourColors.NeonAmber else ContourColors.SurfaceBorder
                    )
                ) {
                    Text(
                        text = if (controller.isAbActive) "A/B: BYPASS" else "A/B TEST",
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp,
                        color = if (controller.isAbActive) Color.Black else ContourColors.NeonAmber
                    )
                }
            }

            // flash write button with progress fill
            val progressAnim by animateFloatAsState(targetValue = controller.sendProgress)
            Box(
                modifier = Modifier
                    .width(180.dp)
                    .height(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (connected) {
                            Brush.horizontalGradient(
                                listOf(ContourColors.NeonPink, ContourColors.NeonAmber)
                            )
                        } else {
                            Brush.linearGradient(
                                listOf(ContourColors.SurfaceBorder, ContourColors.SurfaceBorder)
                            )
                        }
                    )
                    .border(
                        1.dp,
                        if (connected) ContourColors.NeonPink else Color.Transparent,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable(enabled = connected && !controller.isSending && !controller.isReading) {
                        model.sendCurrentToDac(selectedSlot)
                    },
                contentAlignment = Alignment.Center
            ) {
                if (controller.isSending) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progressAnim)
                            .align(Alignment.CenterStart)
                            .background(Color.White.copy(alpha = 0.45f))
                    )
                }

                Text(
                    text = if (controller.isSending) "SAVING TO DAC..." else "SEND TO DAC",
                    fontWeight = FontWeight.Black,
                    fontSize = 13.sp,
                    letterSpacing = 1.sp,
                    color = if (connected) Color.White else ContourColors.TextMuted
                )
            }
        }
    }
}
