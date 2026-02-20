package com.cryptohunt.app.ui.screens.lobby

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
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
import com.cryptohunt.app.domain.model.GamePhase
import com.cryptohunt.app.domain.model.LeaderboardEntry
import com.cryptohunt.app.ui.theme.*
import com.cryptohunt.app.ui.viewmodel.LobbyViewModel
import com.cryptohunt.app.util.TimeUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameHistoryDetailScreen(
    historyIndex: Int,
    onBack: () -> Unit,
    viewModel: LobbyViewModel = hiltViewModel()
) {
    val history by viewModel.gameHistory.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val txPending by viewModel.txPending.collectAsState()
    var txError by remember { mutableStateOf<String?>(null) }
    var claimSuccess by remember { mutableStateOf(false) }
    val item = history.getOrNull(historyIndex) ?: run {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (isLoading || history.isEmpty()) {
                Text("Loading…", color = Primary, style = MaterialTheme.typography.bodyLarge)
            } else {
                Text("Game not found", color = TextSecondary)
            }
        }
        return
    }

    val isCancelled = item.phase == GamePhase.CANCELLED
    val isWinner = item.rank == 1 && item.phase == GamePhase.ENDED
    val hasUnclaimedPrize = item.prizeEth > 0.0 && !item.claimed && item.participated
    val dateFormat = SimpleDateFormat("EEEE, MMM d · HH:mm", Locale.getDefault())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item.config.name, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        containerColor = Background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(
                    Brush.verticalGradient(
                        colors = if (isWinner) listOf(Color(0xFF0A1A0A), Background) else listOf(Background, Background)
                    )
                )
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(vertical = 24.dp)
        ) {
            // Header
            item {
                if (isWinner && item.participated) {
                    Icon(
                        Icons.Default.EmojiEvents,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = Warning
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "WINNER",
                        style = MaterialTheme.typography.displaySmall,
                        color = Primary,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 6.sp
                    )
                } else {
                    Text(
                        when {
                            item.phase == GamePhase.CANCELLED -> "CANCELLED"
                            !item.participated -> "GAME OVER"
                            item.phase == GamePhase.ELIMINATED -> "ELIMINATED"
                            else -> "GAME OVER"
                        },
                        style = MaterialTheme.typography.displaySmall,
                        color = when {
                            item.phase == GamePhase.CANCELLED -> TextSecondary
                            !item.participated -> TextSecondary
                            item.phase == GamePhase.ELIMINATED -> Danger
                            else -> TextPrimary
                        },
                        fontWeight = FontWeight.Black,
                        letterSpacing = 4.sp
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    dateFormat.format(Date(item.playedAt)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                if (isCancelled) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (item.cancelledAfterStart) {
                            "Game was not finished before max game duration. Entry fees were refunded."
                        } else {
                            "Not enough players — ${item.playersTotal}/${item.config.minPlayers} registered."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextDim,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(Modifier.height(24.dp))
            }

            // Stats card (not shown for cancelled games)
            if (!isCancelled) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CardBackground),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                if (item.participated) "YOUR STATS" else "GAME STATS",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary
                            )
                            Spacer(Modifier.height(16.dp))

                            if (item.participated) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    StatColumn("KILLS", "${item.kills}", Primary)
                                    StatColumn("SURVIVED", if (item.survivalSeconds > 0) TimeUtils.formatDuration(item.survivalSeconds) else "—", TextPrimary)
                                    StatColumn("RANK", if (item.rank > 0) "#${item.rank}/${item.playersTotal}" else "#—/${item.playersTotal}", if (isWinner) Warning else TextPrimary)
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    StatColumn("PLAYERS", "${item.playersTotal}", TextPrimary)
                                    StatColumn("ENTRY", "${item.config.entryFee} ETH", Primary)
                                }
                            }
                        }
                    }

                    // Winners section for non-participated games
                    if (!item.participated && item.winner1 != 0) {
                        Spacer(Modifier.height(16.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = CardBackground),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Text("WINNERS", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                                Spacer(Modifier.height(12.dp))

                                WinnerRow("1st Place", item.winner1, Warning)
                                if (item.winner2 != 0) WinnerRow("2nd Place", item.winner2, TextPrimary)
                                if (item.winner3 != 0) WinnerRow("3rd Place", item.winner3, TextPrimary)
                                if (item.topKiller != 0) WinnerRow("Most Kills", item.topKiller, Primary)
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }

            // Claim prize / refund section — only shown when unclaimed
            if (hasUnclaimedPrize && !claimSuccess) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Warning.copy(alpha = 0.1f)
                        ),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.EmojiEvents,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = Warning
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (isCancelled) "REFUND AVAILABLE" else "PRIZE WON",
                                style = MaterialTheme.typography.labelMedium,
                                color = Warning,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "%.4f ETH".format(item.prizeEth),
                                style = MaterialTheme.typography.headlineSmall,
                                color = Warning,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    txError = null
                                    viewModel.claimPrize(
                                        index = historyIndex,
                                        onSuccess = { claimSuccess = true },
                                        onError = { error -> txError = error }
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Warning,
                                    contentColor = Background
                                ),
                                shape = RoundedCornerShape(8.dp),
                                enabled = !txPending
                            ) {
                                if (txPending) {
                                    Text(
                                        "⏳ Claiming...",
                                        fontWeight = FontWeight.Bold
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.AccountBalanceWallet,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (isCancelled) "Claim Refund" else "Claim to Wallet",
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            if (txError != null) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    txError ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Danger,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }

            // Claim success message
            if (claimSuccess) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Primary.copy(alpha = 0.1f)
                        ),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                if (isCancelled) "Refund claimed!" else "Prize claimed!",
                                style = MaterialTheme.typography.titleMedium,
                                color = Primary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "%.4f ETH sent to your wallet".format(item.prizeEth),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }

            // Prize distribution (not shown for cancelled games)
            if (!isCancelled) {
                item {
                    Text("PRIZE DISTRIBUTION", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Spacer(Modifier.height(12.dp))

                    val playerCount = maxOf(item.playersTotal, item.config.minPlayers)
                    val totalPool = item.config.entryFee * playerCount + item.config.baseReward
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CardBackground),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            PrizeRow("1st Place (${item.config.bps1st / 100}%)", "%.4f ETH".format(totalPool * item.config.bps1st / 10000.0), Warning)
                            PrizeRow("Most Kills (${item.config.bpsKills / 100}%)", "%.4f ETH".format(totalPool * item.config.bpsKills / 10000.0), Primary)
                            PrizeRow("2nd Place (${item.config.bps2nd / 100}%)", "%.4f ETH".format(totalPool * item.config.bps2nd / 10000.0), TextPrimary)
                            PrizeRow("3rd Place (${item.config.bps3rd / 100}%)", "%.4f ETH".format(totalPool * item.config.bps3rd / 10000.0), TextPrimary)
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }

            // Leaderboard (not shown for cancelled games)
            if (!isCancelled && item.leaderboard.isNotEmpty()) {
                item {
                    Text("LEADERBOARD", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Spacer(Modifier.height(12.dp))
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CardBackground),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column {
                            // Header row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("#", style = MaterialTheme.typography.labelSmall, color = TextDim, modifier = Modifier.width(32.dp))
                                Text("PLAYER", style = MaterialTheme.typography.labelSmall, color = TextDim, modifier = Modifier.weight(1f))
                                Text("KILLS", style = MaterialTheme.typography.labelSmall, color = TextDim, modifier = Modifier.width(48.dp), textAlign = TextAlign.Center)
                                Text("STATUS", style = MaterialTheme.typography.labelSmall, color = TextDim, modifier = Modifier.width(64.dp), textAlign = TextAlign.End)
                            }

                            item.leaderboard.forEach { entry ->
                                LeaderboardRow(entry)
                            }
                        }
                    }
                }
            } else if (!isCancelled) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CardBackground),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(
                            "Final leaderboard data is unavailable for this restored game snapshot.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }

            item {
                Spacer(Modifier.height(32.dp))
            }
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

@Composable
private fun WinnerRow(label: String, playerNumber: Int, labelColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = labelColor, fontWeight = FontWeight.SemiBold)
        Text("Player #$playerNumber", style = MaterialTheme.typography.bodyMedium, color = TextDim)
    }
}

@Composable
private fun LeaderboardRow(entry: LeaderboardEntry) {
    val isMe = entry.isCurrentPlayer
    val bgColor = if (isMe) Primary.copy(alpha = 0.12f) else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${entry.rank}",
            style = MaterialTheme.typography.bodyMedium,
            color = when (entry.rank) {
                1 -> Warning
                2 -> TextSecondary
                3 -> Color(0xFFCD7F32)
                else -> TextDim
            },
            fontWeight = if (entry.rank <= 3) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.width(32.dp)
        )
        Text(
            text = if (isMe) "#${entry.playerNumber} (You)" else "#${entry.playerNumber}",
            style = MaterialTheme.typography.bodyMedium,
            color = if (isMe) Primary else if (entry.isAlive) TextPrimary else TextDim,
            fontWeight = if (isMe) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${entry.kills}",
            style = MaterialTheme.typography.bodyMedium,
            color = if (entry.kills > 0) Primary else TextDim,
            fontWeight = if (entry.kills > 0) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.width(48.dp),
            textAlign = TextAlign.Center
        )
        Text(
            text = if (entry.isAlive) "WINNER" else "DEAD",
            style = MaterialTheme.typography.labelSmall,
            color = if (entry.isAlive) Warning else Danger,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(64.dp),
            textAlign = TextAlign.End
        )
    }
}
