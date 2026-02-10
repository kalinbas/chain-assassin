package com.cryptohunt.app.ui.screens.lobby

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cryptohunt.app.ui.theme.*
import com.cryptohunt.app.ui.viewmodel.LobbyViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameLobbyScreen(
    onGameStart: () -> Unit,
    onBack: () -> Unit,
    viewModel: LobbyViewModel = hiltViewModel()
) {
    val gameState by viewModel.gameState.collectAsState()
    var countdown by remember { mutableStateOf(10) }

    LaunchedEffect(Unit) {
        while (countdown > 0) {
            delay(1000)
            countdown--
        }
    }

    val config = gameState?.config

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Game Lobby", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
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
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Countdown
            Text(
                text = if (countdown > 0) "Starting in" else "Ready!",
                style = MaterialTheme.typography.titleMedium,
                color = TextSecondary
            )
            Spacer(Modifier.height(8.dp))

            val infiniteTransition = rememberInfiniteTransition(label = "countdown")
            val scale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.15f,
                animationSpec = infiniteRepeatable(
                    animation = tween(500),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "scale"
            )

            Text(
                text = if (countdown > 0) "$countdown" else "GO",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = (64 * if (countdown > 0) scale else 1f).sp
                ),
                color = if (countdown > 0) Warning else Primary,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(24.dp))

            // Your player card
            gameState?.currentPlayer?.let { player ->
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
                        Column {
                            Text("YOU ARE", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            Text(
                                "Player #${player.number}",
                                style = MaterialTheme.typography.headlineSmall,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            val playerCount = config?.maxPlayers ?: 100
            val minPlayers = config?.minPlayers ?: 10
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
                    "$playerCount/$playerCount",
                    style = MaterialTheme.typography.labelMedium,
                    color = Primary
                )
            }
            Text(
                "Min $minPlayers · Max $playerCount",
                style = MaterialTheme.typography.labelSmall,
                color = TextDim
            )
            Spacer(Modifier.height(12.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(10),
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(playerCount) { index ->
                    val isMe = index == (gameState?.currentPlayer?.number ?: 0) - 1
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(CircleShape)
                            .background(if (isMe) Primary else TextSecondary.copy(alpha = 0.5f))
                            .then(
                                if (isMe)
                                    Modifier.border(2.dp, Primary, CircleShape)
                                else Modifier
                            )
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    viewModel.startGame()
                    onGameStart()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = Background
                )
            ) {
                Text(
                    if (countdown > 0) "Start Now (Skip)" else "START GAME",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
