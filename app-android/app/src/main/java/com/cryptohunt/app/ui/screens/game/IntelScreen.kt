package com.cryptohunt.app.ui.screens.game

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cryptohunt.app.ui.theme.*
import com.cryptohunt.app.ui.viewmodel.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntelScreen(
    onBack: () -> Unit,
    onNavigateToMap: () -> Unit,
    viewModel: GameViewModel = hiltViewModel()
) {
    val gameState by viewModel.gameState.collectAsState()
    var confirmItem by remember { mutableStateOf<IntelItem?>(null) }
    var activatingItem by remember { mutableStateOf<String?>(null) }

    // Tick every second to update cooldown timers
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick = System.currentTimeMillis()
        }
    }

    val itemCooldowns = gameState?.itemCooldowns ?: emptyMap()

    // Activation animation: pulse then navigate
    LaunchedEffect(activatingItem) {
        if (activatingItem != null) {
            delay(800) // show activation animation
            activatingItem = null
            onBack()
            onNavigateToMap()
        }
    }

    // Pulse animation for activating item
    val infiniteTransition = rememberInfiniteTransition(label = "itemPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    ModalBottomSheet(
        onDismissRequest = onBack,
        containerColor = Surface,
        contentColor = TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 48.dp)
        ) {
            Text(
                "ITEMS",
                style = MaterialTheme.typography.headlineSmall,
                color = Shield,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(Modifier.height(4.dp))

            Text(
                "5-minute cooldown between uses",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(Modifier.height(20.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(INTEL_ITEMS) { item ->
                    // Force recompose on tick
                    val cooldownMs = remember(tick, itemCooldowns) {
                        viewModel.getItemCooldownRemaining(item.id)
                    }
                    val isOnCooldown = cooldownMs > 0
                    val isActivating = activatingItem == item.id

                    ItemCard(
                        item = item,
                        isOnCooldown = isOnCooldown,
                        cooldownMs = cooldownMs,
                        isActivating = isActivating,
                        activatingAlpha = if (isActivating) pulseAlpha else 1f,
                        onClick = {
                            if (!isOnCooldown && activatingItem == null) {
                                confirmItem = item
                            }
                        }
                    )
                }
            }
        }
    }

    // Confirm dialog
    confirmItem?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmItem = null },
            title = { Text("Use ${item.title}?", color = TextPrimary) },
            text = { Text(item.description, color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    confirmItem = null
                    val result = viewModel.useItem(item)
                    when (result) {
                        is ItemResult.Success -> {
                            activatingItem = item.id
                        }
                        is ItemResult.OnCooldown -> { /* shouldn't happen, button is disabled */ }
                        is ItemResult.Failed -> { /* show nothing for now */ }
                    }
                }) {
                    Text("USE", color = Primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmItem = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = Surface
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemCard(
    item: IntelItem,
    isOnCooldown: Boolean,
    cooldownMs: Long,
    isActivating: Boolean,
    activatingAlpha: Float,
    onClick: () -> Unit
) {
    val icon: ImageVector = when (item.iconName) {
        "gps_fixed" -> Icons.Default.GpsFixed
        "person_search" -> Icons.Default.MyLocation
        "visibility_off" -> Icons.Default.VisibilityOff
        "wrong_location" -> Icons.Default.LocationOff
        "flash_on" -> Icons.Default.FlashOn
        else -> Icons.Default.Star
    }

    val itemColor = if (item.id == "ping_target") Primary else Danger

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isActivating) Modifier.alpha(activatingAlpha) else Modifier),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isActivating -> itemColor.copy(alpha = 0.2f)
                isOnCooldown -> SurfaceVariant.copy(alpha = 0.5f)
                else -> CardBackground
            }
        ),
        shape = MaterialTheme.shapes.medium,
        enabled = !isOnCooldown && !isActivating
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = when {
                    isActivating -> itemColor
                    isOnCooldown -> TextDim
                    else -> Shield
                }
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = when {
                        isActivating -> itemColor
                        isOnCooldown -> TextDim
                        else -> TextPrimary
                    },
                    fontWeight = FontWeight.Bold
                )
                Text(
                    item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isOnCooldown) TextDim else TextSecondary
                )
            }
            if (isActivating) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "PINGING",
                    style = MaterialTheme.typography.labelMedium,
                    color = itemColor,
                    fontWeight = FontWeight.Bold
                )
            } else if (isOnCooldown) {
                Spacer(Modifier.width(8.dp))
                val totalSeconds = (cooldownMs / 1000).toInt()
                val minutes = totalSeconds / 60
                val seconds = totalSeconds % 60
                Text(
                    "%d:%02d".format(minutes, seconds),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextDim,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
