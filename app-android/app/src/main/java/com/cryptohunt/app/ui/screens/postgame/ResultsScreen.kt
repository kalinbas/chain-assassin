package com.cryptohunt.app.ui.screens.postgame

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cryptohunt.app.ui.components.KillFeedItem
import com.cryptohunt.app.ui.theme.*
import com.cryptohunt.app.ui.viewmodel.GameViewModel
import com.cryptohunt.app.util.TimeUtils

@Composable
fun ResultsScreen(
    onPlayAgain: () -> Unit,
    viewModel: GameViewModel = hiltViewModel()
) {
    val gameState by viewModel.gameState.collectAsState()
    val state = gameState

    val isWinner = state?.playersRemaining == 1 &&
            state.currentPlayer.isAlive &&
            state.phase == com.cryptohunt.app.domain.model.GamePhase.ENDED

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = if (isWinner) listOf(Color(0xFF0A1A0A), Background) else listOf(Background, Background)
                )
            )
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(vertical = 32.dp)
    ) {
        item {
            Spacer(Modifier.height(16.dp))

            if (isWinner) {
                Icon(
                    Icons.Default.EmojiEvents,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = Warning
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "WINNER",
                    style = MaterialTheme.typography.displayMedium,
                    color = Primary,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 6.sp
                )
            } else {
                Text(
                    "GAME OVER",
                    style = MaterialTheme.typography.displayMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 4.sp
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                state?.config?.name ?: "Chain-Assassin",
                style = MaterialTheme.typography.titleMedium,
                color = TextSecondary
            )

            Spacer(Modifier.height(32.dp))
        }

        // Your stats card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    Text("YOUR STATS", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatColumn("KILLS", "${state?.currentPlayer?.killCount ?: 0}", Primary)
                        StatColumn("SURVIVED", TimeUtils.formatDuration(state?.gameTimeElapsedSeconds ?: 0), TextPrimary)
                        StatColumn("RANK", if (isWinner) "#1" else "#${(state?.playersRemaining ?: 0) + 1}", if (isWinner) Warning else TextPrimary)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }

        // Prize distribution
        item {
            Text("PRIZE DISTRIBUTION", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Spacer(Modifier.height(12.dp))

            val prizePool = (state?.config?.entryFee ?: 0.0) * (state?.config?.maxPlayers ?: 100) * 0.9
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    PrizeRow("1st Place (40%)", "%.3f ETH".format(prizePool * 0.4), Warning)
                    PrizeRow("Most Kills (20%)", "%.3f ETH".format(prizePool * 0.2), Primary)
                    PrizeRow("2nd Place (15%)", "%.3f ETH".format(prizePool * 0.15), TextPrimary)
                    PrizeRow("3rd Place (10%)", "%.3f ETH".format(prizePool * 0.1), TextPrimary)
                }
            }

            Spacer(Modifier.height(24.dp))
        }

        // Kill timeline
        item {
            Text("KILL TIMELINE", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Spacer(Modifier.height(12.dp))
        }

        val killFeed = state?.killFeed ?: emptyList()
        if (killFeed.isEmpty()) {
            item {
                Text(
                    "No kills recorded",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            itemsIndexed(killFeed.reversed()) { _, event ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    colors = CardDefaults.cardColors(containerColor = Surface),
                    shape = MaterialTheme.shapes.small
                ) {
                    KillFeedItem(event = event)
                }
            }
        }

        // Play again button
        item {
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = onPlayAgain,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = Background
                ),
                shape = MaterialTheme.shapes.medium
            ) {
                Text("Play Again", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StatColumn(label: String, value: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.headlineMedium,
            color = valueColor,
            fontWeight = FontWeight.Bold
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
    }
}

@Composable
private fun PrizeRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor, fontWeight = FontWeight.SemiBold)
    }
}
