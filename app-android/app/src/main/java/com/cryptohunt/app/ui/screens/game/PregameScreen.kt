package com.cryptohunt.app.ui.screens.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cryptohunt.app.domain.model.GamePhase
import com.cryptohunt.app.ui.theme.*
import com.cryptohunt.app.ui.viewmodel.GameViewModel
import com.cryptohunt.app.ui.viewmodel.UiEvent
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PregameScreen(
    gameId: String = "",
    onGameStart: () -> Unit,
    onEliminated: () -> Unit,
    onBack: () -> Unit,
    viewModel: GameViewModel = hiltViewModel()
) {
    val gameState by viewModel.gameState.collectAsState()
    val state = gameState ?: return

    // Navigate when game starts
    LaunchedEffect(state.phase) {
        when (state.phase) {
            GamePhase.ACTIVE -> onGameStart()
            GamePhase.ELIMINATED -> onEliminated()
            GamePhase.CANCELLED -> onBack()
            else -> {}
        }
    }

    // Also listen for GameStarted event
    LaunchedEffect(Unit) {
        viewModel.uiEvents.collectLatest { event ->
            when (event) {
                is UiEvent.NavigateToMainGame -> onGameStart()
                else -> {}
            }
        }
    }

    val minutes = state.pregameTimeRemainingSeconds / 60
    val seconds = state.pregameTimeRemainingSeconds % 60

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Get Ready", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        containerColor = Background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Player number badge
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Primary)
                    .border(3.dp, Primary.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "#${state.currentPlayer.number}",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Background,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(32.dp))

            // Phase badge
            Surface(
                color = Primary.copy(alpha = 0.15f),
                shape = RoundedCornerShape(20.dp),
                border = ButtonDefaults.outlinedButtonBorder,
            ) {
                Text(
                    "PREGAME",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = Primary,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(24.dp))

            // Countdown timer
            Text(
                "%02d:%02d".format(minutes, seconds),
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 64.sp),
                color = Primary,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(16.dp))

            Text(
                "Disperse and Prepare",
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "Spread out, find hiding spots, and get ready. Targets will be assigned when the countdown ends.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(Modifier.height(32.dp))

            // Players remaining info
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "${state.playersRemaining}",
                            style = MaterialTheme.typography.headlineMedium,
                            color = Primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Players",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            // Debug: skip to game
            OutlinedButton(
                onClick = {
                    viewModel.debugStartGame()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextDim)
            ) {
                Text("Debug: Skip to Game", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
