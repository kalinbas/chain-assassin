package com.cryptohunt.app.ui.screens.lobby

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cryptohunt.app.domain.model.GamePhase
import com.cryptohunt.app.ui.theme.*
import com.cryptohunt.app.ui.viewmodel.GameHistoryItem
import com.cryptohunt.app.ui.viewmodel.GameListItem
import com.cryptohunt.app.ui.viewmodel.LobbyViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameBrowserScreen(
    onGameClick: (String) -> Unit,
    onRegisteredGameClick: (String) -> Unit = onGameClick,
    onActiveGameClick: () -> Unit = {},
    onEliminatedGameClick: () -> Unit = {},
    onHistoryGameClick: (Int) -> Unit = {},
    onDeposit: () -> Unit = {},
    viewModel: LobbyViewModel = hiltViewModel()
) {
    val games by viewModel.games.collectAsState()
    val walletState by viewModel.walletState.collectAsState()
    val gameState by viewModel.gameState.collectAsState()
    val gameHistory by viewModel.gameHistory.collectAsState()

    val activePhase = gameState?.phase
    val hasActiveGame = activePhase in listOf(GamePhase.ACTIVE, GamePhase.ELIMINATED)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "CRYPTOHUNT",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Primary
                    )
                },
                actions = {
                    // Wallet chip
                    AssistChip(
                        onClick = onDeposit,
                        label = {
                            Text(
                                "%.3f ETH".format(walletState.balanceEth),
                                style = MaterialTheme.typography.labelMedium,
                                color = TextPrimary
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.AccountBalanceWallet,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Primary
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = SurfaceVariant
                        ),
                        modifier = Modifier.padding(end = 8.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Background
                )
            )
        },
        containerColor = Background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // Active Game section
            if (hasActiveGame && gameState != null) {
                item {
                    Text(
                        "Active Game",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                item {
                    ActiveGameCard(
                        gameName = gameState!!.config.name,
                        phase = activePhase!!,
                        playersRemaining = gameState!!.playersRemaining,
                        kills = gameState!!.currentPlayer.killCount,
                        onClick = {
                            if (activePhase == GamePhase.ACTIVE) {
                                onActiveGameClick()
                            } else {
                                onEliminatedGameClick()
                            }
                        }
                    )
                }

                item { Spacer(Modifier.height(8.dp)) }
            }

            // Upcoming Games section
            item {
                Text(
                    "Upcoming Games",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            items(games) { game ->
                val isRegistered = gameState?.config?.id == game.config.id &&
                        gameState?.phase in listOf(GamePhase.REGISTERED, GamePhase.CHECK_IN)
                GameCard(
                    game = game,
                    isRegistered = isRegistered,
                    onClick = {
                        if (isRegistered) {
                            onRegisteredGameClick(game.config.id)
                        } else {
                            onGameClick(game.config.id)
                        }
                    }
                )
            }

            // Past Games section
            if (gameHistory.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Past Games",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                itemsIndexed(gameHistory) { index, historyItem ->
                    HistoryCard(
                        item = historyItem,
                        onClick = { onHistoryGameClick(index) }
                    )
                }
            }
        }
    }
}

@Composable
private fun GameCard(game: GameListItem, isRegistered: Boolean = false, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = game.config.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (isRegistered) {
                    Surface(
                        color = Primary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "REGISTERED",
                                style = MaterialTheme.typography.labelSmall,
                                color = Primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    Text(
                        text = "${game.config.entryFee} ETH",
                        style = MaterialTheme.typography.labelLarge,
                        color = Primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Place,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = TextSecondary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = game.locationName,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Date and time
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = TextSecondary
                )
                Spacer(modifier = Modifier.width(4.dp))
                val dateFormat = SimpleDateFormat("EEE, MMM d · HH:mm", Locale.getDefault())
                Text(
                    text = dateFormat.format(Date(game.startTime)),
                    style = MaterialTheme.typography.bodySmall,
                    color = Primary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Player count bar
            val meetsMin = game.currentPlayers >= game.config.minPlayers
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.People,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = TextSecondary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "${game.currentPlayers}/${game.config.maxPlayers}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (meetsMin) Primary else TextSecondary
                )
                Spacer(modifier = Modifier.width(12.dp))
                LinearProgressIndicator(
                    progress = game.currentPlayers.toFloat() / game.config.maxPlayers,
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp),
                    color = if (meetsMin) Primary else Warning,
                    trackColor = SurfaceVariant,
                )
            }
            if (!meetsMin) {
                Text(
                    "Min ${game.config.minPlayers} players to start",
                    style = MaterialTheme.typography.labelSmall,
                    color = Warning,
                    modifier = Modifier.padding(start = 24.dp, top = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Prize pool
            val prizePool = game.config.entryFee * game.config.maxPlayers * 0.9
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Prize Pool",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Text(
                    "%.2f ETH".format(prizePool),
                    style = MaterialTheme.typography.labelLarge,
                    color = Warning,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ActiveGameCard(
    gameName: String,
    phase: GamePhase,
    playersRemaining: Int,
    kills: Int,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (phase == GamePhase.ACTIVE) Primary.copy(alpha = 0.1f) else Danger.copy(alpha = 0.1f)
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    gameName,
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    color = if (phase == GamePhase.ACTIVE) Primary.copy(alpha = 0.2f) else Danger.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        if (phase == GamePhase.ACTIVE) "ACTIVE" else "ELIMINATED",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (phase == GamePhase.ACTIVE) Primary else Danger,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.People, null, modifier = Modifier.size(16.dp), tint = TextSecondary)
                    Spacer(Modifier.width(4.dp))
                    Text("$playersRemaining remaining", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CenterFocusStrong, null, modifier = Modifier.size(16.dp), tint = Primary)
                    Spacer(Modifier.width(4.dp))
                    Text("$kills kills", style = MaterialTheme.typography.bodySmall, color = Primary)
                }
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (phase == GamePhase.ACTIVE) Primary else SurfaceVariant,
                    contentColor = if (phase == GamePhase.ACTIVE) Background else TextPrimary
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    if (phase == GamePhase.ACTIVE) "Resume Game" else "View Game",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun HistoryCard(item: GameHistoryItem, onClick: () -> Unit) {
    val isCancelled = item.phase == GamePhase.CANCELLED
    val isWinner = item.rank == 1 && item.phase == GamePhase.ENDED
    val hasUnclaimedPrize = item.prizeEth > 0.0 && !item.claimed
    val dateFormat = SimpleDateFormat("EEE, MMM d · HH:mm", Locale.getDefault())

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (hasUnclaimedPrize) Warning.copy(alpha = 0.06f) else CardBackground
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    item.config.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (hasUnclaimedPrize) {
                        Surface(
                            color = Warning.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (isCancelled) Icons.Default.Replay else Icons.Default.EmojiEvents,
                                    contentDescription = if (isCancelled) "Unclaimed refund" else "Unclaimed prize",
                                    tint = Warning,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    "%.3f ETH".format(item.prizeEth),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Warning,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Surface(
                        color = when {
                            isCancelled -> TextSecondary.copy(alpha = 0.15f)
                            isWinner -> Warning.copy(alpha = 0.15f)
                            else -> Danger.copy(alpha = 0.15f)
                        },
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            when {
                                isCancelled -> "CANCELLED"
                                isWinner -> "WON"
                                else -> "LOST"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                isCancelled -> TextSecondary
                                isWinner -> Warning
                                else -> Danger
                            },
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, null, modifier = Modifier.size(14.dp), tint = TextDim)
                Spacer(Modifier.width(4.dp))
                Text(
                    dateFormat.format(Date(item.playedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDim
                )
            }

            Spacer(Modifier.height(8.dp))

            if (isCancelled) {
                Text(
                    "${item.playersTotal}/${item.config.minPlayers} players registered — game cancelled",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDim
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "#${item.rank}/${item.playersTotal}",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isWinner) Warning else TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text("RANK", style = MaterialTheme.typography.labelSmall, color = TextDim)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "${item.kills}",
                            style = MaterialTheme.typography.titleMedium,
                            color = Primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text("KILLS", style = MaterialTheme.typography.labelSmall, color = TextDim)
                    }
                }
            }
        }
    }
}
