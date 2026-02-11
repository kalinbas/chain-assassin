package com.cryptohunt.app.ui.screens.postgame

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cryptohunt.app.ui.theme.*
import com.cryptohunt.app.ui.viewmodel.GameViewModel
import com.cryptohunt.app.util.TimeUtils

@Composable
fun EliminatedScreen(
    onSpectate: () -> Unit,
    onExit: () -> Unit,
    viewModel: GameViewModel = hiltViewModel()
) {
    val gameState by viewModel.gameState.collectAsState()
    val state = gameState
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1A0A0A),
                        Background,
                        Background
                    )
                )
            )
            .systemBarsPadding()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(Modifier.weight(1f))

        // Skull / eliminated header
        Text(
            text = "YOU'VE BEEN",
            style = MaterialTheme.typography.headlineSmall,
            color = TextSecondary,
            letterSpacing = 4.sp
        )
        Text(
            text = "HUNTED",
            style = MaterialTheme.typography.displayLarge,
            color = Danger,
            fontWeight = FontWeight.Black,
            letterSpacing = 6.sp
        )

        Spacer(Modifier.height(32.dp))

        // Stats card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Survival time
                StatRow(
                    label = "SURVIVED",
                    value = TimeUtils.formatDuration(state?.gameTimeElapsedSeconds ?: 0)
                )

                Divider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = DividerColor
                )

                // Kills
                StatRow(
                    label = "KILLS",
                    value = "${state?.currentPlayer?.killCount ?: 0}",
                    valueColor = Primary
                )

                Divider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = DividerColor
                )

                // Final rank
                val totalPlayers = state?.config?.maxPlayers ?: 100
                val rank = (state?.playersRemaining ?: 1) + 1
                StatRow(
                    label = "FINAL RANK",
                    value = "#$rank / $totalPlayers"
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // QR removal warning
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Warning.copy(alpha = 0.12f)),
            shape = MaterialTheme.shapes.medium
        ) {
            Text(
                "Please remove your QR codes from your shirt as soon as possible to not interfere with the running game.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Warning,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.weight(1f))

        // Spectate button
        Button(
            onClick = {
                viewModel.setSpectatorMode()
                onSpectate()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SurfaceVariant,
                contentColor = TextPrimary
            ),
            shape = MaterialTheme.shapes.medium
        ) {
            Icon(Icons.Default.RemoveRedEye, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Watch as Spectator", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(12.dp))

        // Share results button
        OutlinedButton(
            onClick = {
                val kills = state?.currentPlayer?.killCount ?: 0
                val survived = TimeUtils.formatDuration(state?.gameTimeElapsedSeconds ?: 0)
                val totalPlayers = state?.config?.maxPlayers ?: 100
                val rank = (state?.playersRemaining ?: 1) + 1
                val gameName = state?.config?.name ?: "Chain-Assassin"

                val shareText = "I was hunted in $gameName!\n" +
                    "Rank: #$rank / $totalPlayers\n" +
                    "Kills: $kills\n" +
                    "Survived: $survived\n" +
                    "#ChainAssassin"

                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, shareText)
                    type = "text/plain"
                }
                context.startActivity(Intent.createChooser(sendIntent, "Share Results"))
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
            shape = MaterialTheme.shapes.medium
        ) {
            Icon(Icons.Default.Share, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Share Results", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(12.dp))

        // Exit button
        TextButton(
            onClick = onExit,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.ExitToApp, null, modifier = Modifier.size(20.dp), tint = TextDim)
            Spacer(Modifier.width(8.dp))
            Text("Leave Game", style = MaterialTheme.typography.bodyMedium, color = TextDim)
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StatRow(
    label: String,
    value: String,
    valueColor: Color = TextPrimary
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary
        )
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            color = valueColor,
            fontWeight = FontWeight.Bold
        )
    }
}
