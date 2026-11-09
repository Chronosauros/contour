package io.github.chronosauros.contour.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.chronosauros.contour.desktop.model.DesktopAppModel
import io.github.chronosauros.contour.desktop.ui.theme.ContourColors

/** About dialog: version, author, USB DAC support notes and licences. */
@Composable
fun AboutDialog(
    model: DesktopAppModel,
    onDismiss: () -> Unit
) {
    val connected = model.controller.connectedHandle != null
    val target = model.currentTarget

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = ContourColors.Primary),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text("Close", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Contour for macOS",
                    fontWeight = FontWeight.Black,
                    fontSize = 18.sp,
                    color = ContourColors.TextPrimary
                )
                Text(
                    text = "v1.6.5",
                    fontSize = 12.sp,
                    color = ContourColors.NeonPurple,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.width(440.dp).padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // port attribution
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(ContourColors.Surface)
                        .border(1.dp, ContourColors.NeonCyan.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "ported to macOS by: nu11p0t4t0",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        color = ContourColors.NeonCyan
                    )
                }

                // connected hardware status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("DAC STATUS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ContourColors.TextMuted)
                        Text(if (connected) target.caps.name else "No DAC Connected", fontSize = 12.sp, color = ContourColors.TextPrimary, fontWeight = FontWeight.Bold)
                    }
                    Column {
                        Text("PROTOCOL", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ContourColors.TextMuted)
                        Text(if (connected) target.family.name else "—", fontSize = 12.sp, color = ContourColors.TextPrimary, fontWeight = FontWeight.Bold)
                    }
                }

                // credits and licences
                Text(
                    text = "• Original Android concept & architecture: Chronosauros (Apache 2.0)\n" +
                            "• WalkPlay protocol reverse-engineering & biquad maths: devicePEQ (0BSD)\n" +
                            "• macOS transport: Native IOKit HID via hid4java\n" +
                            "• User interface: Compose Multiplatform (Desktop)",
                    fontSize = 11.sp,
                    color = ContourColors.TextSecondary,
                    lineHeight = 16.sp
                )
            }
        },
        containerColor = ContourColors.SurfaceElevated,
        shape = RoundedCornerShape(12.dp)
    )
}
