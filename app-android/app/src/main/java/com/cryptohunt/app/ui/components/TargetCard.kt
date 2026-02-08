package com.cryptohunt.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptohunt.app.domain.model.Target
import com.cryptohunt.app.ui.theme.*

@Composable
fun TargetCard(
    target: Target?,
    modifier: Modifier = Modifier
) {
    // Flip animation for new target
    var previousTargetId by remember { mutableStateOf(target?.player?.id) }
    var flipTrigger by remember { mutableStateOf(false) }

    LaunchedEffect(target?.player?.id) {
        if (target?.player?.id != previousTargetId && previousTargetId != null) {
            flipTrigger = !flipTrigger
        }
        previousTargetId = target?.player?.id
    }

    val rotation by animateFloatAsState(
        targetValue = if (flipTrigger) 360f else 0f,
        animationSpec = tween(600, easing = EaseInOutCubic),
        label = "cardFlip"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { rotationY = rotation },
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(16.dp)
    ) {
        if (target != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "YOUR TARGET",
                    style = MaterialTheme.typography.labelMedium,
                    color = Danger,
                    letterSpacing = 3.sp
                )

                Spacer(Modifier.height(12.dp))

                // Target number - big and centered
                Text(
                    text = "#${target.player.number}",
                    style = MaterialTheme.typography.displayLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.Black
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(40.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No target assigned",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }
    }
}
