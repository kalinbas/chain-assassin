package com.cryptohunt.app.ui.screens.game

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cryptohunt.app.ui.theme.*
import com.cryptohunt.app.ui.viewmodel.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntelScreen(
    onBack: () -> Unit,
    viewModel: GameViewModel = hiltViewModel()
) {
    val gameState by viewModel.gameState.collectAsState()
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var confirmItem by remember { mutableStateOf<IntelItem?>(null) }

    val usedItems = gameState?.usedItems ?: emptySet()

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
                "Each item can be used once per game",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(Modifier.height(20.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(INTEL_ITEMS) { item ->
                    val isUsed = item.id in usedItems
                    ItemCard(
                        item = item,
                        isUsed = isUsed,
                        onClick = {
                            if (!isUsed) {
                                confirmItem = item
                            }
                        }
                    )
                }
            }

            // Result message
            resultMessage?.let { msg ->
                Spacer(Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        msg,
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Primary,
                        textAlign = TextAlign.Center
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
                    resultMessage = when (result) {
                        is ItemResult.Success -> result.message
                        is ItemResult.AlreadyUsed -> "Already used!"
                        is ItemResult.Failed -> result.reason
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
    isUsed: Boolean,
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

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isUsed) SurfaceVariant.copy(alpha = 0.5f) else CardBackground
        ),
        shape = MaterialTheme.shapes.medium,
        enabled = !isUsed
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = if (isUsed) TextDim else Shield
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isUsed) TextDim else TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isUsed) TextDim else TextSecondary
                )
            }
            if (isUsed) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "USED",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextDim,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
