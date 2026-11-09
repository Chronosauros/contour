package io.github.chronosauros.contour.desktop.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.chronosauros.contour.desktop.model.DesktopAppModel
import io.github.chronosauros.contour.desktop.ui.theme.ContourColors

/** Tune view: interactive response graph, band selector, filter values, preamp and send actions. */
@Composable
fun TuneView(
    model: DesktopAppModel,
    modifier: Modifier = Modifier
) {
    val profile = model.currentProfile
    if (profile == null) {
        Box(modifier = modifier.fillMaxSize()) {
            Text("No profile selected", color = ContourColors.TextSecondary)
        }
        return
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // active profile header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = profile.name,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = ContourColors.TextPrimary
                )
                if (profile.subtitle.isNotBlank()) {
                    Text(
                        text = profile.subtitle,
                        fontSize = 12.sp,
                        color = ContourColors.TextSecondary
                    )
                }
            }
        }

        // response graph with draggable handles
        ResponseGraph(model = model, profile = profile)

        // preamp slider and auto calculation
        PreampBar(model = model)

        // band selection strip
        BandStrip(model = model)

        // filter parameters: frequency, gain, Q factor
        BandEditor(model = model)

        // flash send action and readback verification
        SendBar(model = model)
    }
}
