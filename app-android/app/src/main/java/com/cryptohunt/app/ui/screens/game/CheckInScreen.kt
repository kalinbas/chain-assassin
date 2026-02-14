package com.cryptohunt.app.ui.screens.game

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cryptohunt.app.domain.model.GamePhase
import com.cryptohunt.app.ui.testing.TestTags
import com.cryptohunt.app.ui.theme.*
import com.cryptohunt.app.ui.viewmodel.GameViewModel
import com.cryptohunt.app.ui.viewmodel.UiEvent
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckInScreen(
    gameId: String = "",
    onScanPlayer: () -> Unit,
    onPregame: () -> Unit,
    onGameStart: () -> Unit,
    onEliminated: () -> Unit,
    onBack: () -> Unit,
    viewModel: GameViewModel = hiltViewModel()
) {
    val gameState by viewModel.gameState.collectAsState()
    val state = gameState ?: return

    val config = state.config
    val isVerified = state.checkInVerified
    val checkedInCount = state.checkedInCount
    val totalPlayers = state.playersRemaining

    // Compute countdown from server timestamp (Unix seconds)
    var timeRemaining by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.checkinEndsAt) {
        while (true) {
            timeRemaining = ((state.checkinEndsAt - System.currentTimeMillis() / 1000).toInt()).coerceAtLeast(0)
            kotlinx.coroutines.delay(1000)
        }
    }

    // Ensure server connection and start sending location for auto-seed
    LaunchedEffect(Unit) {
        val id = gameId.toIntOrNull() ?: config.id.toIntOrNull()
        if (id != null) {
            viewModel.connectToServer(id)
        }
        viewModel.startLocationTracking()
    }

    // Navigate based on phase change
    LaunchedEffect(state.phase) {
        when (state.phase) {
            GamePhase.PREGAME -> onPregame()
            GamePhase.ACTIVE -> onGameStart()
            GamePhase.ENDED -> onGameStart()
            GamePhase.CANCELLED -> onBack()
            GamePhase.ELIMINATED -> onEliminated()
            else -> {}
        }
    }

    // Also listen for events
    LaunchedEffect(Unit) {
        viewModel.uiEvents.collectLatest { event ->
            when (event) {
                is UiEvent.NavigateToPregame -> onPregame()
                is UiEvent.NavigateToMainGame -> onGameStart()
                else -> {}
            }
        }
    }

    val minutes = timeRemaining / 60
    val seconds = timeRemaining % 60

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Check-In", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        containerColor = Background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag(TestTags.CHECKIN_SCREEN)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Countdown timer
            Text(
                "Check-in closes in",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Spacer(Modifier.height(8.dp))

            val timerColor = if (timeRemaining < 60) Danger else Warning
            Text(
                "%02d:%02d".format(minutes, seconds),
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 56.sp),
                color = timerColor,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(24.dp))

            // Verification status
            if (isVerified) {
                // Checked in successfully
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Primary.copy(alpha = 0.15f)),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(
                        modifier = Modifier
                            .padding(24.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Checked In!",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Primary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Waiting for the check-in period to end\u2026",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                // Need to scan a checked-in player
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(
                        modifier = Modifier
                            .padding(20.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            tint = Warning,
                            modifier = Modifier.size(48.dp)
                        )

                        Spacer(Modifier.height(12.dp))

                        Text(
                            "VERIFY YOUR PRESENCE",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )

                        Spacer(Modifier.height(16.dp))

                        Text(
                            "Scan Any Checked-In Player",
                            style = MaterialTheme.typography.headlineSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(Modifier.height(4.dp))

                        Text(
                            "Find someone who\u2019s already verified and scan their QR code",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )

                        Spacer(Modifier.height(20.dp))

                        Button(
                            onClick = onScanPlayer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Primary,
                                contentColor = Background
                            )
                        ) {
                            Icon(
                                Icons.Default.CameraAlt,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Scan QR Code",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Player check-in progress
            Card(
                modifier = Modifier.fillMaxWidth(),
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
                            "Players Checked In",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextSecondary
                        )
                        Text(
                            "$checkedInCount/$totalPlayers",
                            style = MaterialTheme.typography.labelMedium,
                            color = Primary
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    LinearProgressIndicator(
                        progress = if (totalPlayers > 0) checkedInCount.toFloat() / totalPlayers else 0f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = Primary,
                        trackColor = SurfaceVariant
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        "Game starts when check-in period ends",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextDim
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Player circles grid
            val checkedInNumbers = state.checkedInPlayerNumbers
            val myNumber = state.currentPlayer.number
            val allNumbers = (1..totalPlayers).toList().sorted()

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "PLAYERS",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(12.dp))

                    val columns = 8
                    val circleSize = 36.dp
                    val spacing = 6.dp

                    allNumbers.chunked(columns).forEach { row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(spacing),
                            modifier = Modifier.padding(bottom = spacing)
                        ) {
                            row.forEach { number ->
                                val isMe = number == myNumber
                                val isCheckedIn = number in checkedInNumbers

                                val bgColor = when {
                                    isCheckedIn -> Primary.copy(alpha = if (isMe) 1f else 0.6f)
                                    else -> SurfaceVariant.copy(alpha = 0.4f)
                                }
                                val textColor = when {
                                    isCheckedIn -> Background
                                    else -> TextDim
                                }

                                Box(
                                    modifier = Modifier
                                        .size(circleSize)
                                        .clip(CircleShape)
                                        .background(bgColor)
                                        .then(
                                            if (isMe) Modifier.border(2.dp, if (isCheckedIn) Primary else Warning, CircleShape)
                                            else Modifier
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "$number",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = textColor,
                                        fontWeight = if (isMe) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Info text
            Text(
                "The game will automatically start when the check-in period ends. " +
                        "Some players are randomly pre-verified as seeds. " +
                        "Scan any checked-in player\u2019s QR code to verify your presence. " +
                        "Once you\u2019re verified, other players can scan you too!",
                style = MaterialTheme.typography.bodySmall,
                color = TextDim,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}
