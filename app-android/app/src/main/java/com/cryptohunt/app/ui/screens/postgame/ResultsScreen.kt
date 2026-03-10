package com.cryptohunt.app.ui.screens.postgame

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cryptohunt.app.ui.components.KillFeedItem
import com.cryptohunt.app.ui.testing.TestTags
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
    val playerNumber = state?.currentPlayer?.number ?: 0
    val placement = if (playerNumber > 0) {
        when (playerNumber) {
            state?.winner1 ?: 0 -> 1
            state?.winner2 ?: 0 -> 2
            state?.winner3 ?: 0 -> 3
            else -> 0
        }
    } else {
        0
    }
    val isTopKiller = state?.topKiller == playerNumber && playerNumber > 0
    val isWinner = placement == 1
    val title = when {
        placement == 1 -> "FIRST PLACE"
        placement == 2 -> "SECOND PLACE"
        placement == 3 -> "THIRD PLACE"
        isTopKiller -> "MOST KILLS"
        else -> "GAME OVER"
    }
    val titleColor = when {
        placement == 1 -> Warning
        placement == 2 -> TextPrimary
        placement == 3 -> Color(0xFFCD7F32)
        isTopKiller -> Primary
        else -> TextPrimary
    }
    val endedAt = state?.gameEndedAt ?: (System.currentTimeMillis() / 1000)
    val survivedSeconds = if ((state?.gameStartTime ?: 0L) > 0L) {
        (endedAt - state!!.gameStartTime).coerceAtLeast(0L)
    } else {
        0L
    }
    val leaderboardRank = state?.leaderboard?.firstOrNull { it.isCurrentPlayer }?.rank ?: 0
    val totalPlayers = when {
        (state?.registeredPlayerCount ?: 0) > 0 -> state?.registeredPlayerCount ?: 0
        (state?.leaderboard?.size ?: 0) > 0 -> state?.leaderboard?.size ?: 0
        else -> state?.playersRemaining ?: 0
    }

    LaunchedEffect(state?.config?.id, state?.phase) {
        if (state?.phase == com.cryptohunt.app.domain.model.GamePhase.ENDED) {
            viewModel.refreshEndedSummary()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(TestTags.RESULTS_SCREEN)
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

            if (placement in 1..3) {
                Icon(
                    Icons.Default.EmojiEvents,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = titleColor
                )
                Spacer(Modifier.height(16.dp))
            } else if (isTopKiller) {
                Icon(
                    Icons.Default.FlashOn,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = titleColor
                )
                Spacer(Modifier.height(16.dp))
            }

            Text(
                title,
                style = MaterialTheme.typography.displayMedium,
                color = titleColor,
                fontWeight = FontWeight.Black,
                letterSpacing = if (placement == 1) 6.sp else 4.sp
            )

            Spacer(Modifier.height(8.dp))
            Text(
                state?.config?.name ?: "CryptoHunt",
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
                        StatColumn("SURVIVED", TimeUtils.formatDuration(survivedSeconds), TextPrimary)
                        StatColumn(
                            "RANK",
                            when {
                                placement in 1..3 -> "#$placement/$totalPlayers"
                                leaderboardRank > 0 && totalPlayers > 0 -> "#$leaderboardRank/$totalPlayers"
                                totalPlayers > 0 -> "#—/$totalPlayers"
                                else -> "—"
                            },
                            if (isWinner) Warning else TextPrimary
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }

        // Prize distribution
        item {
            Text("PRIZE DISTRIBUTION", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Spacer(Modifier.height(12.dp))

            val cfg = state?.config
            val playerCount = maxOf(totalPlayers, cfg?.minPlayers ?: 0)
            val totalPool = ((cfg?.entryFee ?: 0.0) * playerCount) + (cfg?.baseReward ?: 0.0)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    PrizeRow(
                        "1st Place (${formatBpsPercent(cfg?.bps1st ?: 0)})",
                        "%.3f ETH".format(totalPool * (cfg?.bps1st ?: 0) / 10_000.0),
                        Warning
                    )
                    PrizeRow(
                        "Most Kills (${formatBpsPercent(cfg?.bpsKills ?: 0)})",
                        "%.3f ETH".format(totalPool * (cfg?.bpsKills ?: 0) / 10_000.0),
                        Primary
                    )
                    PrizeRow(
                        "2nd Place (${formatBpsPercent(cfg?.bps2nd ?: 0)})",
                        "%.3f ETH".format(totalPool * (cfg?.bps2nd ?: 0) / 10_000.0),
                        TextPrimary
                    )
                    PrizeRow(
                        "3rd Place (${formatBpsPercent(cfg?.bps3rd ?: 0)})",
                        "%.3f ETH".format(totalPool * (cfg?.bps3rd ?: 0) / 10_000.0),
                        TextPrimary
                    )
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

private fun formatBpsPercent(bps: Int): String {
    val pct = bps / 100.0
    return if (pct % 1.0 == 0.0) {
        "${pct.toInt()}%"
    } else {
        "${"%.2f".format(java.util.Locale.US, pct)}%"
    }
}
