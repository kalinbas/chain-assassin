package com.cryptohunt.app.ui.screens.onboarding

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cryptohunt.app.ui.theme.*

@Composable
fun WelcomeScreen(
    onCreateWallet: () -> Unit,
    onImportWallet: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "crosshair")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Background, Color(0xFF0D0D18), Background)
                )
            )
            .systemBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.weight(1f))

            // Crosshair logo
            Box(
                modifier = Modifier.size(120.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size((100 * pulseScale).dp)) {
                    val center = Offset(size.width / 2, size.height / 2)
                    val radius = size.minDimension / 2.5f

                    // Outer circle
                    drawCircle(
                        color = Primary.copy(alpha = glowAlpha),
                        radius = radius,
                        center = center,
                        style = Stroke(width = 3f)
                    )

                    // Inner circle
                    drawCircle(
                        color = Primary,
                        radius = radius * 0.4f,
                        center = center,
                        style = Stroke(width = 2f)
                    )

                    // Crosshair lines
                    val lineLen = radius * 0.8f
                    val gap = radius * 0.5f
                    val stroke = Stroke(width = 2.5f, cap = StrokeCap.Round)

                    // Top
                    drawLine(Primary, Offset(center.x, center.y - gap), Offset(center.x, center.y - lineLen - gap), strokeWidth = 2.5f, cap = StrokeCap.Round)
                    // Bottom
                    drawLine(Primary, Offset(center.x, center.y + gap), Offset(center.x, center.y + lineLen + gap), strokeWidth = 2.5f, cap = StrokeCap.Round)
                    // Left
                    drawLine(Primary, Offset(center.x - gap, center.y), Offset(center.x - lineLen - gap, center.y), strokeWidth = 2.5f, cap = StrokeCap.Round)
                    // Right
                    drawLine(Primary, Offset(center.x + gap, center.y), Offset(center.x + lineLen + gap, center.y), strokeWidth = 2.5f, cap = StrokeCap.Round)

                    // Center dot
                    drawCircle(Primary, radius = 4f, center = center)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "CHAIN",
                style = MaterialTheme.typography.displayMedium,
                color = TextPrimary,
                letterSpacing = 6.sp
            )
            Text(
                text = "ASSASSIN",
                style = MaterialTheme.typography.displayMedium,
                color = Primary,
                letterSpacing = 6.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Hunt or be hunted. On-chain.",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.weight(1.5f))

            // Create wallet button
            Button(
                onClick = onCreateWallet,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = Background
                ),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = "Create Wallet",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Import wallet button
            OutlinedButton(
                onClick = onImportWallet,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = Brush.linearGradient(listOf(TextDim, TextDim))
                ),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = "I Have a Wallet",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}
