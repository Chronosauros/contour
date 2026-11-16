package io.github.chronosauros.contour.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import io.github.chronosauros.contour.core.Band
import io.github.chronosauros.contour.core.FilterType
import io.github.chronosauros.contour.core.isPass
import io.github.chronosauros.contour.desktop.model.DesktopAppModel
import io.github.chronosauros.contour.desktop.ui.theme.ContourColors
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** Band selector strip: shows filter pills for each band in the profile. */
@Composable
fun BandStrip(
    model: DesktopAppModel,
    modifier: Modifier = Modifier
) {
    val profile = model.currentProfile ?: return
    val scrollState = rememberScrollState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        profile.bands.forEachIndexed { i, band ->
            val isSelected = i == model.selectedBandIndex
            val typeShort = when (band.type) {
                FilterType.PEAK -> "PK"
                FilterType.LOW_SHELF -> "LS"
                FilterType.HIGH_SHELF -> "HS"
                FilterType.LOW_PASS -> "LP"
                FilterType.HIGH_PASS -> "HP"
                FilterType.BAND_PASS -> "BP"
                FilterType.ALL_PASS -> "AP"
            }
            val gainText = if (band.type.isPass) "" else (if (band.gainDb >= 0) "+%.1f".format(band.gainDb) else "%.1f".format(band.gainDb)) + "dB"
            val freqText = if (band.freqHz >= 1000) "%.1fk".format(band.freqHz / 1000.0) else "%.0f".format(band.freqHz)

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) ContourColors.SurfaceElevated else ContourColors.Surface)
                    .border(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) ContourColors.NodeSelected else ContourColors.SurfaceBorder,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable { model.selectBand(i) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "${i + 1}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = if (isSelected) ContourColors.NodeSelected else ContourColors.NeonCyan
                    )
                    Text(
                        text = typeShort,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (!band.enabled) ContourColors.TextMuted else ContourColors.NeonPurple
                    )
                    Text(
                        text = freqText,
                        fontSize = 11.sp,
                        color = if (!band.enabled) ContourColors.TextMuted else ContourColors.TextPrimary
                    )
                    if (!band.type.isPass) {
                        Text(
                            text = gainText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (!band.enabled) ContourColors.TextMuted else if (band.gainDb > 0) ContourColors.NeonGreen else if (band.gainDb < 0) ContourColors.NeonAmber else ContourColors.TextSecondary
                        )
                    }
                }
            }
        }

        if (profile.bands.size < model.maxBands) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(ContourColors.Surface)
                    .border(1.dp, ContourColors.NeonCyan.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .clickable { model.addBand() }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text("+ Band", fontSize = 11.sp, color = ContourColors.NeonCyan, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Parameter controls for the selected band: filter type, frequency, gain and Q factor. */
@Composable
fun BandEditor(
    model: DesktopAppModel,
    modifier: Modifier = Modifier
) {
    val profile = model.currentProfile ?: return
    val bandIndex = model.selectedBandIndex
    val band = profile.bands.getOrNull(bandIndex) ?: return
    val caps = model.currentTarget.caps
    val allowedTypes = caps.types.toList().sortedBy { it.ordinal }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ContourColors.Surface)
            .border(1.dp, ContourColors.SurfaceBorderGlow.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // band title and enable/bypass toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "BAND ${bandIndex + 1}",
                    fontWeight = FontWeight.Black,
                    fontSize = 15.sp,
                    color = ContourColors.NodeSelected
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (band.enabled) ContourColors.NeonGreen.copy(alpha = 0.15f) else ContourColors.SurfaceElevated)
                        .border(
                            1.dp,
                            if (band.enabled) ContourColors.NeonGreen.copy(alpha = 0.5f) else ContourColors.SurfaceBorder,
                            RoundedCornerShape(6.dp)
                        )
                        .clickable { model.updateBand(bandIndex, band.copy(enabled = !band.enabled)) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (band.enabled) "ACTIVE" else "BYPASSED",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (band.enabled) ContourColors.NeonGreen else ContourColors.TextMuted
                    )
                }
            }

            if (profile.bands.size > 1) {
                Button(
                    onClick = { model.removeBand(bandIndex) },
                    colors = ButtonDefaults.buttonColors(containerColor = ContourColors.SurfaceElevated),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, ContourColors.AccentRed.copy(alpha = 0.6f))
                ) {
                    Text("Delete Band", color = ContourColors.AccentRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // filter type selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            allowedTypes.forEach { type ->
                val isSelected = band.type == type
                val label = when (type) {
                    FilterType.PEAK -> "PEAK"
                    FilterType.LOW_SHELF -> "LOW SHELF"
                    FilterType.HIGH_SHELF -> "HIGH SHELF"
                    FilterType.LOW_PASS -> "LOW PASS"
                    FilterType.HIGH_PASS -> "HIGH PASS"
                    FilterType.BAND_PASS -> "BAND PASS"
                    FilterType.ALL_PASS -> "ALL PASS"
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (isSelected) {
                                Brush.horizontalGradient(listOf(ContourColors.NeonPink, ContourColors.NeonPurple))
                            } else {
                                Brush.linearGradient(listOf(ContourColors.SurfaceElevated, ContourColors.SurfaceElevated))
                            }
                        )
                        .border(
                            1.dp,
                            if (isSelected) ContourColors.NeonPink else ContourColors.SurfaceBorder,
                            RoundedCornerShape(6.dp)
                        )
                        .clickable {
                            val newGain = if (type.isPass) 0.0 else band.gainDb
                            model.updateBand(bandIndex, band.copy(type = type, gainDb = newGain))
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Black else FontWeight.Normal,
                        color = if (isSelected) Color.White else ContourColors.TextSecondary
                    )
                }
            }
        }

        HorizontalDivider(color = ContourColors.SurfaceBorder, thickness = 1.dp)

        // frequency slider and steppers
        ParameterSlider(
            title = "FREQUENCY",
            valueText = "%.0f Hz".format(band.freqHz),
            value = (ln(band.freqHz.coerceIn(caps.freqMinHz, caps.freqMaxHz) / caps.freqMinHz) / ln(caps.freqMaxHz / caps.freqMinHz)).toFloat(),
            onValueChange = { norm ->
                val f = caps.freqMinHz * (caps.freqMaxHz / caps.freqMinHz).pow(norm.toDouble())
                model.updateBand(bandIndex, band.copy(freqHz = (f * 10).roundToInt() / 10.0))
            },
            steppers = listOf(
                "-100" to { model.updateBand(bandIndex, band.copy(freqHz = (band.freqHz - 100.0).coerceIn(caps.freqMinHz, caps.freqMaxHz))) },
                "-10" to { model.updateBand(bandIndex, band.copy(freqHz = (band.freqHz - 10.0).coerceIn(caps.freqMinHz, caps.freqMaxHz))) },
                "+10" to { model.updateBand(bandIndex, band.copy(freqHz = (band.freqHz + 10.0).coerceIn(caps.freqMinHz, caps.freqMaxHz))) },
                "+100" to { model.updateBand(bandIndex, band.copy(freqHz = (band.freqHz + 100.0).coerceIn(caps.freqMinHz, caps.freqMaxHz))) }
            ),
            accentColor = ContourColors.NeonCyan
        )

        // gain slider and steppers (pass filters carry no gain)
        if (!band.type.isPass) {
            val minGain = caps.gainMinDb
            val maxGain = caps.gainMaxDb
            ParameterSlider(
                title = "GAIN",
                valueText = "%+.1f dB".format(band.gainDb),
                value = ((band.gainDb - minGain) / (maxGain - minGain)).toFloat(),
                onValueChange = { norm ->
                    val g = minGain + norm * (maxGain - minGain)
                    model.updateBand(bandIndex, band.copy(gainDb = (g * 10).roundToInt() / 10.0))
                },
                steppers = listOf(
                    "-1.0" to { model.updateBand(bandIndex, band.copy(gainDb = (band.gainDb - 1.0).coerceIn(minGain, maxGain))) },
                    "-0.1" to { model.updateBand(bandIndex, band.copy(gainDb = ((band.gainDb * 10 - 1).roundToInt() / 10.0).coerceIn(minGain, maxGain))) },
                    "+0.1" to { model.updateBand(bandIndex, band.copy(gainDb = ((band.gainDb * 10 + 1).roundToInt() / 10.0).coerceIn(minGain, maxGain))) },
                    "+1.0" to { model.updateBand(bandIndex, band.copy(gainDb = (band.gainDb + 1.0).coerceIn(minGain, maxGain))) }
                ),
                accentColor = ContourColors.NeonPink
            )
        }

        // Q factor slider and steppers
        val minQ = caps.qMin
        val maxQ = caps.qMax
        ParameterSlider(
            title = "QUALITY FACTOR (Q)",
            valueText = "%.2f".format(band.q),
            value = (ln(band.q / minQ) / ln(maxQ / minQ)).toFloat().coerceIn(0f, 1f),
            onValueChange = { norm ->
                val q = minQ * (maxQ / minQ).pow(norm.toDouble())
                model.updateBand(bandIndex, band.copy(q = (q * 100).roundToInt() / 100.0))
            },
            steppers = listOf(
                "-0.5" to { model.updateBand(bandIndex, band.copy(q = (band.q - 0.5).coerceIn(minQ, maxQ))) },
                "-0.1" to { model.updateBand(bandIndex, band.copy(q = ((band.q * 10 - 1).roundToInt() / 10.0).coerceIn(minQ, maxQ))) },
                "+0.1" to { model.updateBand(bandIndex, band.copy(q = ((band.q * 10 + 1).roundToInt() / 10.0).coerceIn(minQ, maxQ))) },
                "+0.5" to { model.updateBand(bandIndex, band.copy(q = (band.q + 0.5).coerceIn(minQ, maxQ))) }
            ),
            accentColor = ContourColors.NeonPurple
        )
    }
}

/** Parameter slider with fine stepper buttons on the right. */
@Composable
fun ParameterSlider(
    title: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    steppers: List<Pair<String, () -> Unit>>,
    accentColor: Color = ContourColors.NeonCyan
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ContourColors.TextSecondary)
            Text(text = valueText, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accentColor)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Slider(
                value = value.coerceIn(0f, 1f),
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = accentColor,
                    activeTrackColor = accentColor,
                    inactiveTrackColor = ContourColors.SurfaceElevated
                )
            )

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                steppers.forEach { (label, action) ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(ContourColors.SurfaceElevated)
                            .border(1.dp, ContourColors.SurfaceBorder, RoundedCornerShape(4.dp))
                            .clickable(onClick = action)
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text(text = label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = ContourColors.TextPrimary)
                    }
                }
            }
        }
    }
}
