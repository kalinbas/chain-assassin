package com.cryptohunt.app.ui.screens.lobby

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cryptohunt.app.ui.theme.*
import com.cryptohunt.app.ui.viewmodel.LobbyViewModel
import com.cryptohunt.app.util.QrPdfGenerator
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisteredDetailScreen(
    gameId: String = "",
    onCheckInStart: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: LobbyViewModel = hiltViewModel()
) {
    val gameState by viewModel.gameState.collectAsState()
    val selectedGame by viewModel.selectedGame.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(gameId) {
        if (gameId.isNotEmpty()) {
            viewModel.selectGame(gameId)
        }
    }

    // When selectedGame loads, restore local GameState if needed
    LaunchedEffect(selectedGame?.config?.id) {
        if (gameId.isNotEmpty()) {
            viewModel.ensureRegisteredState(gameId)
        }
    }

    // Use gameState if available, otherwise fall back to selectedGame for config
    val config = gameState?.config ?: selectedGame?.config ?: return
    val player = gameState?.currentPlayer
    val gameStartTime = gameState?.gameStartTime
        ?: selectedGame?.startTime
        ?: 0L

    // Countdown timer
    var timeRemainingMs by remember { mutableStateOf(gameStartTime - System.currentTimeMillis()) }
    LaunchedEffect(gameStartTime) {
        while (true) {
            timeRemainingMs = gameStartTime - System.currentTimeMillis()
            if (timeRemainingMs <= 0) {
                viewModel.beginCheckIn()
                onCheckInStart(config.id)
                break
            }
            delay(1000)
        }
    }

    val days = (timeRemainingMs / 86400000).coerceAtLeast(0)
    val hours = ((timeRemainingMs % 86400000) / 3600000).coerceAtLeast(0)
    val minutes = ((timeRemainingMs % 3600000) / 60000).coerceAtLeast(0)
    val seconds = ((timeRemainingMs % 60000) / 1000).coerceAtLeast(0)

    val dateFormat = SimpleDateFormat("EEEE, MMM d 'at' HH:mm", Locale.getDefault())
    val arrivalTime = dateFormat.format(Date(gameStartTime - config.checkInDurationMinutes * 60000L))
    val startTimeStr = dateFormat.format(Date(gameStartTime))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(config.name, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        containerColor = Background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Registered badge
            Surface(
                color = Primary.copy(alpha = 0.15f),
                shape = RoundedCornerShape(20.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "You're Registered",
                        style = MaterialTheme.typography.titleMedium,
                        color = Primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Countdown
            Text(
                "Game starts in",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (days > 0) "%dd %02d:%02d:%02d".format(days, hours, minutes, seconds)
                else "%02d:%02d:%02d".format(hours, minutes, seconds),
                style = MaterialTheme.typography.displayMedium.copy(fontSize = 48.sp),
                color = Warning,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                startTimeStr,
                style = MaterialTheme.typography.bodySmall,
                color = TextDim
            )

            Spacer(Modifier.height(24.dp))

            // Player info card
            if (player != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "#${player.number}",
                                style = MaterialTheme.typography.labelLarge,
                                color = Background,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Text(
                            "Player #${player.number}",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Background,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Text(
                            "Registered",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Print QR Code button (only when player number is assigned)
            if (player != null) {
                Button(
                    onClick = {
                        val uri = QrPdfGenerator.generatePdf(
                            context = context,
                            gameId = config.id.toIntOrNull() ?: 0,
                            playerNumber = player.number,
                            gameName = config.name
                        )
                        QrPdfGenerator.sharePdf(context, uri)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary,
                        contentColor = Background
                    )
                ) {
                    Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Print QR Code",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    "Print this page twice \u2014 attach to front and back of your shirt",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(Modifier.height(24.dp))

            // Arrival instructions
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Place, null, tint = Warning, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "ARRIVAL INSTRUCTIONS",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Text(
                        "Arrive by: $arrivalTime",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Warning,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        "Go to the game location and be ready for the ${config.checkInDurationMinutes}-minute check-in period. " +
                                "You will need to scan any already checked-in player\u2019s QR code to verify your presence.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Game Rules
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "GAME RULES",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )

                    Spacer(Modifier.height(12.dp))

                    RuleItem("\uD83D\uDC55", "Print your QR code and attach it to the front and back of your shirt so other players can scan it.")
                    Spacer(Modifier.height(8.dp))
                    RuleItem("\uD83C\uDFAF", "When the game starts, you\u2019ll be assigned a target. Find and scan their QR code to eliminate them.")
                    Spacer(Modifier.height(8.dp))
                    RuleItem("\uD83D\uDDFA\uFE0F", "Stay inside the shrinking zone \u2014 it gets smaller over time to keep the action moving.")
                    Spacer(Modifier.height(8.dp))
                    RuleItem("\uD83C\uDFC6", "Last player standing wins the prize pool!")
                }
            }

            Spacer(Modifier.height(16.dp))

            // Prize breakdown
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("PRIZE POOL", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Spacer(Modifier.height(8.dp))
                    val prizePool = config.entryFee * config.maxPlayers * 0.9
                    PrizeRow("Winner (40%)", "%.3f ETH".format(prizePool * 0.4))
                    PrizeRow("Most Kills (20%)", "%.3f ETH".format(prizePool * 0.2))
                    PrizeRow("2nd Place (15%)", "%.3f ETH".format(prizePool * 0.15))
                    PrizeRow("3rd Place (10%)", "%.3f ETH".format(prizePool * 0.1))
                }
            }

            Spacer(Modifier.height(16.dp))

            // Refund notice
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Warning.copy(alpha = 0.1f)),
                shape = MaterialTheme.shapes.medium
            ) {
                Row(modifier = Modifier.padding(16.dp)) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = Warning,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "If fewer than ${config.minPlayers} players register by start time, " +
                                "your ${config.entryFee} ETH entry fee is fully refundable.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Debug: skip to check-in
            OutlinedButton(
                onClick = {
                    viewModel.beginCheckIn()
                    onCheckInStart(config.id)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextDim)
            ) {
                Text("Debug: Skip to Check-in", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun RuleItem(emoji: String, text: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(emoji, modifier = Modifier.width(28.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun PrizeRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.bodySmall, color = TextPrimary, fontWeight = FontWeight.Medium)
    }
}
