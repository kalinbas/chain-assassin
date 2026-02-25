package com.cryptohunt.app.ui.screens.lobby

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.cryptohunt.app.domain.chain.OnChainPhase
import com.cryptohunt.app.domain.model.GamePhase
import com.cryptohunt.app.ui.testing.TestTags
import com.cryptohunt.app.ui.theme.*
import com.cryptohunt.app.ui.viewmodel.LobbyViewModel
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDetailScreen(
    gameId: String = "",
    onJoinGame: (String) -> Unit,
    onViewRegistration: (String) -> Unit = onJoinGame,
    onBack: () -> Unit,
    viewModel: LobbyViewModel = hiltViewModel()
) {
    LaunchedEffect(gameId) {
        if (gameId.isNotEmpty()) {
            viewModel.selectGame(gameId)
            viewModel.startDetailAutoRefresh(gameId)
        }
    }
    DisposableEffect(gameId) {
        onDispose { viewModel.stopDetailAutoRefresh() }
    }

    val game by viewModel.selectedGame.collectAsState()
    val walletState by viewModel.walletState.collectAsState()
    val gameState by viewModel.gameState.collectAsState()
    val txPending by viewModel.txPending.collectAsState()
    var showConfirmDialog by remember { mutableStateOf(false) }
    var txError by remember { mutableStateOf<String?>(null) }
    val alreadyRegistered = game?.isPlayerRegistered == true ||
            (gameState != null &&
            gameState?.config?.id == gameId &&
            gameState?.phase in listOf(GamePhase.REGISTERED, GamePhase.CHECK_IN, GamePhase.ACTIVE))

    val config = game?.config
    val context = LocalContext.current
    val registrationDeadlineMs = config?.registrationDeadline ?: 0L
    var registrationNowMs by remember(registrationDeadlineMs, game?.onChainPhase) {
        mutableStateOf(System.currentTimeMillis())
    }

    LaunchedEffect(registrationDeadlineMs, game?.onChainPhase) {
        if (registrationDeadlineMs <= 0L || game?.onChainPhase != OnChainPhase.REGISTRATION) {
            return@LaunchedEffect
        }
        while (true) {
            registrationNowMs = System.currentTimeMillis()
            if (registrationNowMs >= registrationDeadlineMs) break
            delay(1000)
        }
    }

    // Show loading while game data loads from server
    if (config == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Loading...", style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
                )
            },
            containerColor = Background
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("Loading…", color = Primary, style = MaterialTheme.typography.bodyLarge)
            }
        }
        return
    }

    // Configure osmdroid
    LaunchedEffect(Unit) {
        Configuration.getInstance().userAgentValue = context.packageName
    }

    val zoneArgb = Primary.toArgb()
    val zoneFillArgb = Primary.copy(alpha = 0.08f).toArgb()
    val meetingArgb = Warning.toArgb()
    val meetingFillArgb = Warning.copy(alpha = 0.9f).toArgb()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(config.name, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        bottomBar = {
            Surface(
                color = Surface,
                tonalElevation = 8.dp
            ) {
                val canAfford = walletState.balanceEth >= config.entryFee
                val isRegistrationOpen = game?.onChainPhase == OnChainPhase.REGISTRATION
                if (alreadyRegistered) {
                    Button(
                        onClick = { onViewRegistration(config.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .height(56.dp)
                            .testTag(TestTags.GAME_DETAIL_VIEW_REGISTRATION_BUTTON),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Primary,
                            contentColor = Background
                        )
                    ) {
                        Text(
                            "View Registration",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Button(
                        onClick = { showConfirmDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .height(56.dp)
                            .testTag(TestTags.GAME_DETAIL_REGISTER_BUTTON),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Primary,
                            contentColor = Background
                        ),
                        enabled = isRegistrationOpen && canAfford && !txPending
                    ) {
                        if (txPending) {
                            Text(
                                "⏳ Registering...",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Text(
                                if (!isRegistrationOpen) "Registration Closed"
                                else if (canAfford) "Register \u2014 ${config.entryFee} ETH"
                                else "Insufficient Balance",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        },
        containerColor = Background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag(TestTags.GAME_DETAIL_SCREEN)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Registered banner
            if (alreadyRegistered) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Primary.copy(alpha = 0.1f)),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "You're Registered",
                                style = MaterialTheme.typography.titleMedium,
                                color = Primary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Your entry fee has been paid",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // Zone preview map
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Background)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val cartoDark = object : OnlineTileSourceBase(
                            "CartoDB_Dark", 0, 20, 256, ".png",
                            arrayOf(
                                "https://a.basemaps.cartocdn.com/dark_all/",
                                "https://b.basemaps.cartocdn.com/dark_all/",
                                "https://c.basemaps.cartocdn.com/dark_all/",
                                "https://d.basemaps.cartocdn.com/dark_all/"
                            )
                        ) {
                            override fun getTileURLString(pMapTileIndex: Long): String {
                                val z = MapTileIndex.getZoom(pMapTileIndex)
                                val x = MapTileIndex.getX(pMapTileIndex)
                                val y = MapTileIndex.getY(pMapTileIndex)
                                return baseUrl + "$z/$x/$y.png"
                            }
                        }
                        MapView(ctx).apply {
                            setBackgroundColor(Background.toArgb())
                            setTileSource(cartoDark)
                            setMultiTouchControls(true)
                            controller.setZoom(15.0)
                            controller.setCenter(GeoPoint(config.zoneCenterLat, config.zoneCenterLng))
                            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                        }
                    },
                    update = { mapView ->
                        mapView.overlays.clear()
                        val center = GeoPoint(config.zoneCenterLat, config.zoneCenterLng)
                        val radius = config.initialRadiusMeters

                        // Zone circle (green, matching website)
                        mapView.overlays.add(object : Overlay() {
                            override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
                                if (shadow) return
                                val projection = mapView.projection
                                val centerPx = android.graphics.Point()
                                projection.toPixels(center, centerPx)

                                val edgeGeo = center.destinationPoint(radius, 90.0)
                                val edgePx = android.graphics.Point()
                                projection.toPixels(GeoPoint(edgeGeo.latitude, edgeGeo.longitude), edgePx)
                                val pxRadius = Math.abs(edgePx.x - centerPx.x).toFloat()

                                canvas.drawCircle(centerPx.x.toFloat(), centerPx.y.toFloat(), pxRadius,
                                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                        style = Paint.Style.FILL
                                        color = zoneFillArgb
                                    })
                                canvas.drawCircle(centerPx.x.toFloat(), centerPx.y.toFloat(), pxRadius,
                                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                        style = Paint.Style.STROKE
                                        color = zoneArgb
                                        strokeWidth = 3f
                                    })
                            }
                        })

                        // Meeting point marker — yellow circle
                        if (config.meetingLat != 0.0 && config.meetingLng != 0.0) {
                            val meetingPoint = GeoPoint(config.meetingLat, config.meetingLng)
                            mapView.overlays.add(object : Overlay() {
                                override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
                                    if (shadow) return
                                    val projection = mapView.projection
                                    val pt = android.graphics.Point()
                                    projection.toPixels(meetingPoint, pt)
                                    canvas.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 10f,
                                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                            style = Paint.Style.FILL
                                            color = meetingFillArgb
                                        })
                                    canvas.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 10f,
                                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                            style = Paint.Style.STROKE
                                            color = meetingArgb
                                            strokeWidth = 2f
                                        })
                                }
                            })
                        }

                        mapView.invalidate()
                    }
                )

                // Radius label overlay
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp),
                    color = Surface.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    val r = config.initialRadiusMeters
                    Text(
                        if (r >= 1000) "%.1f km radius".format(r / 1000) else "${r.toInt()}m radius",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextPrimary
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Game info
            val startTime = game?.startTime ?: 0L
            if (startTime > 0) {
                val dateFormat = SimpleDateFormat("EEEE, MMM d · HH:mm", Locale.getDefault())
                InfoRow("Starts", dateFormat.format(Date(startTime)))
            }
            if (config.registrationDeadline > 0L) {
                val dateFormat = SimpleDateFormat("EEEE, MMM d · HH:mm", Locale.getDefault())
                val msLeft = config.registrationDeadline - registrationNowMs
                val registrationValue = if (msLeft > 0L) {
                    "${dateFormat.format(Date(config.registrationDeadline))} (${formatCountdown(msLeft)} left)"
                } else {
                    "${dateFormat.format(Date(config.registrationDeadline))} (closed)"
                }
                InfoRow("Registration open until", registrationValue)
            }
            InfoRow("Entry Fee", "${config.entryFee} ETH")
            InfoRow("Players", "${config.minPlayers} – ${config.maxPlayers}")
            InfoRow("Duration", "${config.durationMinutes} minutes")
            InfoRow("Check-in", "${config.checkInDurationMinutes} min before start")

            Spacer(Modifier.height(24.dp))

            // Rules
            Text("Rules", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Spacer(Modifier.height(8.dp))
            RuleItem("Scan your target's QR code to eliminate them")
            RuleItem("Stay inside the zone \u2014 it shrinks over time")
            RuleItem("Last player standing wins the prize pool")

            Spacer(Modifier.height(24.dp))

            // Prize breakdown — use real BPS from contract
            Text("Prize Distribution", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Spacer(Modifier.height(8.dp))
            val playerCount = maxOf(game?.currentPlayers ?: 0, config.minPlayers)
            val totalPool = config.entryFee * playerCount + config.baseReward
            InfoRow("1st Place (${config.bps1st / 100}%)", "%.4f ETH".format(totalPool * config.bps1st / 10000.0))
            InfoRow("Most Kills (${config.bpsKills / 100}%)", "%.4f ETH".format(totalPool * config.bpsKills / 10000.0))
            InfoRow("2nd Place (${config.bps2nd / 100}%)", "%.4f ETH".format(totalPool * config.bps2nd / 10000.0))
            InfoRow("3rd Place (${config.bps3rd / 100}%)", "%.4f ETH".format(totalPool * config.bps3rd / 10000.0))

            // Transaction error
            if (txError != null) {
                Spacer(Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Danger.copy(alpha = 0.1f))
                ) {
                    Text(
                        txError ?: "",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Danger
                    )
                }
            }

            Spacer(Modifier.height(80.dp))
        }
    }

    // Confirm dialog
    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("Register for Game?") },
            text = {
                Text("You'll pay ${config.entryFee} ETH to register for ${config.name}. " +
                        "This sends a real transaction on Base Sepolia. " +
                        "If fewer than ${config.minPlayers} players register by start time, " +
                        "your entry fee is fully refundable.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmDialog = false
                    txError = null
                    viewModel.registerForGame(
                        gameId = config.id,
                        onSuccess = { onJoinGame(config.id) },
                        onError = { error -> txError = error }
                    )
                }) {
                    Text("Register", color = Primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = Surface
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RuleItem(text: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text("\u2022", color = Primary, modifier = Modifier.padding(end = 8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}

private fun formatCountdown(millis: Long): String {
    val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.getDefault(), "%dh %02dm %02ds", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%dm %02ds", minutes, seconds)
    }
}
