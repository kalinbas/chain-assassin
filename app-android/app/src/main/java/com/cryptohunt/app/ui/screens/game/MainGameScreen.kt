package com.cryptohunt.app.ui.screens.game

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cryptohunt.app.domain.model.LeaderboardEntry
import com.cryptohunt.app.domain.model.GamePhase
import com.cryptohunt.app.ui.components.*
import com.cryptohunt.app.ui.testing.TestTags
import com.cryptohunt.app.ui.theme.*
import com.cryptohunt.app.ui.viewmodel.GameViewModel
import com.cryptohunt.app.ui.viewmodel.UiEvent
import kotlinx.coroutines.delay

@Composable
fun MainGameScreen(
    onScan: () -> Unit,
    onPhoto: () -> Unit,
    onMap: () -> Unit,
    onIntel: () -> Unit,
    onEliminated: () -> Unit,
    onGameEnd: () -> Unit,
    viewModel: GameViewModel = hiltViewModel()
) {
    val gameState by viewModel.gameState.collectAsState()
    val locationState by viewModel.locationState.collectAsState()
    val haptic = LocalHapticFeedback.current

    // Kill feed banner
    var bannerText by remember { mutableStateOf<String?>(null) }
    var showKillFlash by remember { mutableStateOf(false) }

    // Ensure server connection + start location tracking and BLE scanning
    LaunchedEffect(Unit) {
        val gameId = gameState?.config?.id?.toIntOrNull()
        if (gameId != null) {
            viewModel.connectToServer(gameId)
        }
        viewModel.startLocationTracking()
        viewModel.startBleScanning()
    }

    // Stop BLE scanning when leaving screen
    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopBleScanning()
        }
    }

    // Handle UI events
    LaunchedEffect(Unit) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                is UiEvent.ShowKillConfirmed -> {
                    showKillFlash = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    delay(500)
                    showKillFlash = false
                }
                is UiEvent.NavigateToEliminated -> onEliminated()
                is UiEvent.NavigateToResults -> onGameEnd()
                is UiEvent.ShowNewTarget -> {
                    bannerText = "NEW TARGET ASSIGNED"
                    delay(3000)
                    bannerText = null
                }
                is UiEvent.ShowZoneShrinkWarning -> {
                    bannerText = "ZONE SHRINKING"
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    delay(3000)
                    bannerText = null
                }
                is UiEvent.NavigateToCheckIn -> { /* handled elsewhere */ }
                is UiEvent.NavigateToPregame -> { /* handled elsewhere */ }
                is UiEvent.NavigateToMainGame -> { /* already on main game */ }
                is UiEvent.CheckInVerified -> { /* handled in CheckInScreen */ }
                is UiEvent.GameCancelled -> { /* handled elsewhere */ }
            }
        }
    }

    // Fallback navigation based on authoritative phase state.
    LaunchedEffect(gameState?.phase) {
        when (gameState?.phase) {
            GamePhase.ELIMINATED -> onEliminated()
            GamePhase.ENDED,
            GamePhase.CANCELLED -> onGameEnd()
            else -> {}
        }
    }

    // Handle kill feed updates
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event is com.cryptohunt.app.domain.game.GameEvent.KillFeedUpdate) {
                val e = event.event
                bannerText = "Player #${e.targetNumber} eliminated — ${gameState?.playersRemaining ?: "?"} remain"
                delay(3000)
                bannerText = null
            }
        }
    }

    val state = gameState ?: return
    val isSpectator = state.spectatorMode

    // Compute zone shrink countdown from server timestamp
    var nextShrinkSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.nextShrinkAt) {
        while (true) {
            nextShrinkSeconds = state.nextShrinkAt?.let {
                ((it - System.currentTimeMillis() / 1000).toInt()).coerceAtLeast(0)
            } ?: 0
            kotlinx.coroutines.delay(1000)
        }
    }

    // Dynamic background
    val bgColor by animateColorAsState(
        targetValue = when {
            !state.isInZone && !isSpectator -> Danger.copy(alpha = 0.1f)
            nextShrinkSeconds in 1..120 -> Warning.copy(alpha = 0.05f)
            else -> Background
        },
        animationSpec = tween(500),
        label = "bgColor"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(TestTags.MAIN_GAME_SCREEN)
    ) {
        Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgColor)
                    .systemBarsPadding()
                    .padding(16.dp)
            ) {
                if (isSpectator) {
                    // ========== SPECTATOR VIEW ==========
                    SpectatorHeader()

                    Spacer(Modifier.height(12.dp))

                    // Status bar (players remaining)
                    StatusBar(
                        killCount = state.currentPlayer.killCount,
                        playersRemaining = state.playersRemaining
                    )

                    Spacer(Modifier.height(12.dp))

                    // Timers row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ZoneTimer(secondsRemaining = nextShrinkSeconds)
                    }

                    Spacer(Modifier.height(12.dp))

                    // Leaderboard (fills remaining space)
                    Text("LEADERBOARD", style = MaterialTheme.typography.labelSmall, color = TextDim)
                    Spacer(Modifier.height(4.dp))

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        colors = CardDefaults.cardColors(containerColor = Surface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (state.leaderboard.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Waiting for data...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextDim
                                )
                            }
                        } else {
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
                            Divider(color = DividerColor, thickness = 0.5.dp)

                            LazyColumn {
                                items(state.leaderboard) { entry ->
                                    LeaderboardRow(entry = entry)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Kill feed (smaller)
                    Text("KILL FEED", style = MaterialTheme.typography.labelSmall, color = TextDim)
                    Spacer(Modifier.height(4.dp))

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        colors = CardDefaults.cardColors(containerColor = Surface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (state.killFeed.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No kills yet...", style = MaterialTheme.typography.bodySmall, color = TextDim)
                            }
                        } else {
                            LazyColumn(modifier = Modifier.padding(vertical = 4.dp)) {
                                items(state.killFeed.take(20)) { event ->
                                    KillFeedItem(event = event)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Map button
                    Button(
                        onClick = onMap,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SurfaceVariant,
                            contentColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Map, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("VIEW MAP", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                } else {
                    // ========== ACTIVE PLAYER VIEW ==========

                    // Top bar with items button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onPhoto) {
                            Icon(Icons.Default.CameraAlt, "Photo", tint = TextSecondary)
                        }
                        IconButton(onClick = onIntel) {
                            Icon(Icons.Default.Star, "Items", tint = Shield)
                        }
                    }

                    // Target card + hunter info
                    TargetCard(
                        target = state.currentTarget,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    // Hunter info
                    if (state.hunterPlayerNumber != null) {
                        Text(
                            "\uD83D\uDC41 Your hunter: Player #${state.hunterPlayerNumber}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Danger.copy(alpha = 0.8f),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }

                    // Heartbeat timer (tap opens scan screen)
                    if (!state.heartbeatDisabled && state.lastHeartbeatAt > 0) {
                        val now = System.currentTimeMillis() / 1000
                        val deadline = state.lastHeartbeatAt + state.heartbeatIntervalSeconds
                        val remaining = (deadline - now).coerceAtLeast(0)
                        val minutes = remaining / 60
                        val seconds = remaining % 60
                        val heartbeatColor = when {
                            remaining < 30 -> Danger
                            remaining < 60 -> Warning
                            remaining < 180 -> Warning.copy(alpha = 0.7f)
                            else -> Primary
                        }

                        TextButton(
                            onClick = onScan,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "\u2764\ufe0f %d:%02d".format(minutes, seconds),
                                style = MaterialTheme.typography.titleMedium,
                                color = heartbeatColor,
                                fontWeight = if (remaining < 60) FontWeight.Black else FontWeight.Bold
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Status bar
                    StatusBar(
                        killCount = state.currentPlayer.killCount,
                        playersRemaining = state.playersRemaining
                    )

                    Spacer(Modifier.height(12.dp))

                    // Timers row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ZoneTimer(secondsRemaining = nextShrinkSeconds)
                    }

                    Spacer(Modifier.height(8.dp))

                    // Kill feed (scrollable, fills remaining space)
                    Text("KILL FEED", style = MaterialTheme.typography.labelSmall, color = TextDim)
                    Spacer(Modifier.height(4.dp))

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        colors = CardDefaults.cardColors(containerColor = Surface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (state.killFeed.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "No kills yet...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextDim
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                items(state.killFeed.take(20)) { event ->
                                    KillFeedItem(event = event)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = onScan,
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Primary,
                                contentColor = Background
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.CenterFocusStrong, null, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("SCAN", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = onMap,
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SurfaceVariant,
                                contentColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.Map, null, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("MAP", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

        // Kill confirmed flash overlay
        AnimatedVisibility(
            visible = showKillFlash,
            enter = fadeIn(tween(100)),
            exit = fadeOut(tween(400))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Primary.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "KILL CONFIRMED",
                    style = MaterialTheme.typography.displayMedium,
                    color = Primary,
                    fontWeight = FontWeight.Black
                )
            }
        }

        // Banner notification (top)
        AnimatedVisibility(
            visible = bannerText != null,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .systemBarsPadding()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = Surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = bannerText ?: "",
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    style = MaterialTheme.typography.labelLarge,
                    color = Primary,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Out-of-zone warning bar (only for active players — server handles elimination)
        if (!state.isInZone && !isSpectator) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .padding(bottom = 80.dp),
                colors = CardDefaults.cardColors(containerColor = Danger.copy(alpha = 0.9f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(12.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "OUTSIDE ZONE",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "Return to the zone or you will be eliminated!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // GPS lost warning overlay (>10 seconds, only for active players)
        AnimatedVisibility(
            visible = locationState.gpsLostSeconds >= 10 && !isSpectator,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(300)),
            modifier = Modifier
                .align(Alignment.Center)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Danger.copy(alpha = 0.95f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.LocationOff,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = TextPrimary
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "GPS SIGNAL LOST",
                        style = MaterialTheme.typography.headlineSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Move to an open area to restore GPS",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

    }
}

@Composable
private fun SpectatorHeader() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Visibility,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = TextSecondary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "SPECTATING",
                style = MaterialTheme.typography.titleMedium,
                color = TextSecondary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp
            )
        }
    }
}

@Composable
private fun LeaderboardRow(entry: LeaderboardEntry) {
    val isMe = entry.isCurrentPlayer
    val bgColor = when {
        isMe -> Primary.copy(alpha = 0.12f)
        !entry.isAlive -> Color.Transparent
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Rank
        Text(
            text = "${entry.rank}",
            style = MaterialTheme.typography.bodyMedium,
            color = when (entry.rank) {
                1 -> Warning
                2 -> TextSecondary
                3 -> Color(0xFFCD7F32) // bronze
                else -> TextDim
            },
            fontWeight = if (entry.rank <= 3) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.width(32.dp)
        )

        // Player number
        Text(
            text = if (isMe) "#${entry.playerNumber} (You)" else "#${entry.playerNumber}",
            style = MaterialTheme.typography.bodyMedium,
            color = if (entry.isAlive) {
                if (isMe) Primary else TextPrimary
            } else TextDim,
            fontWeight = if (isMe) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )

        // Kills
        Text(
            text = "${entry.kills}",
            style = MaterialTheme.typography.bodyMedium,
            color = if (entry.kills > 0) Primary else TextDim,
            fontWeight = if (entry.kills > 0) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.width(48.dp),
            textAlign = TextAlign.Center
        )

        // Status
        Text(
            text = if (entry.isAlive) "ALIVE" else "DEAD",
            style = MaterialTheme.typography.labelSmall,
            color = if (entry.isAlive) Primary else Danger,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(64.dp),
            textAlign = TextAlign.End
        )
    }
}
