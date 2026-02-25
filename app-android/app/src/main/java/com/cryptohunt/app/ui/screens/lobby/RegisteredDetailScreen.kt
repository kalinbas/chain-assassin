package com.cryptohunt.app.ui.screens.lobby

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.cryptohunt.app.domain.model.GamePhase
import com.cryptohunt.app.ui.screens.onboarding.isDeviceGameReady
import com.cryptohunt.app.ui.testing.TestTags
import com.cryptohunt.app.ui.theme.*
import com.cryptohunt.app.ui.viewmodel.LobbyViewModel
import com.cryptohunt.app.util.QrPdfGenerator
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
fun RegisteredDetailScreen(
    gameId: String = "",
    onCheckInStart: (String) -> Unit,
    onPregameStart: (String) -> Unit,
    onGameStart: () -> Unit,
    onEliminated: () -> Unit,
    onOpenReadiness: () -> Unit,
    onBack: () -> Unit,
    viewModel: LobbyViewModel = hiltViewModel()
) {
    val gameState by viewModel.gameState.collectAsState()
    val selectedGame by viewModel.selectedGame.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isRefreshing by remember { mutableStateOf(false) }
    var refreshError by remember { mutableStateOf<String?>(null) }
    var deviceReady by remember { mutableStateOf(isDeviceGameReady(context)) }

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

    DisposableEffect(lifecycleOwner, context) {
        val mainHandler = Handler(Looper.getMainLooper())
        val refreshDeviceReady = {
            mainHandler.post {
                deviceReady = isDeviceGameReady(context)
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshDeviceReady()
            }
        }

        val stateReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED,
                    LocationManager.PROVIDERS_CHANGED_ACTION,
                    LocationManager.MODE_CHANGED_ACTION -> refreshDeviceReady()
                }
            }
        }

        val receiverFilter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(stateReceiver, receiverFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(stateReceiver, receiverFilter)
        }

        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                refreshDeviceReady()
            }

            override fun onLost(network: Network) {
                refreshDeviceReady()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                refreshDeviceReady()
            }
        }
        try {
            connectivityManager?.registerDefaultNetworkCallback(networkCallback)
        } catch (_: Exception) {
            // Keep readiness updates functional via lifecycle + state broadcasts.
        }

        refreshDeviceReady()
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            try {
                context.unregisterReceiver(stateReceiver)
            } catch (_: Exception) {
                // Receiver may already be unregistered by system.
            }
            try {
                connectivityManager?.unregisterNetworkCallback(networkCallback)
            } catch (_: Exception) {
                // Callback may already be removed.
            }
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
            if (timeRemainingMs <= 0) break
            delay(1000)
        }
    }

    // Navigate when server changes phase (via WebSocket message).
    // This handles late-entry cases where we reconnect directly into pregame/game.
    val currentPhase = gameState?.phase
    LaunchedEffect(currentPhase) {
        when (currentPhase) {
            GamePhase.CHECK_IN -> onCheckInStart(config.id)
            GamePhase.PREGAME -> onPregameStart(config.id)
            GamePhase.ACTIVE -> onGameStart()
            GamePhase.ELIMINATED -> onEliminated()
            GamePhase.CANCELLED -> {
                viewModel.refresh()
                onBack()
            }
            else -> {}
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
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
                .testTag(TestTags.REGISTERED_DETAIL_SCREEN)
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

            if (refreshError != null) {
                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Danger.copy(alpha = 0.1f)),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        refreshError ?: "",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Danger
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

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (deviceReady) {
                            Modifier
                        } else {
                            Modifier.clickable { onOpenReadiness() }
                        }
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = if (deviceReady) {
                        Primary.copy(alpha = 0.12f)
                    } else {
                        Warning.copy(alpha = 0.12f)
                    }
                ),
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (deviceReady) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (deviceReady) Primary else Warning
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (deviceReady) "Device Ready" else "Device Not Ready",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (deviceReady) {
                                "All gameplay checks are currently passing."
                            } else {
                                "Tap to open device readiness and fix missing requirements."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    if (!deviceReady) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = TextSecondary
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
                            gameName = config.name,
                            gameStartTimeMillis = gameStartTime
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

            // Meeting point map
            if (config.meetingLat != 0.0 && config.meetingLng != 0.0) {
                Spacer(Modifier.height(12.dp))

                val meetingMarkerArgb = Warning.toArgb()

                // Configure osmdroid
                LaunchedEffect(Unit) {
                    Configuration.getInstance().userAgentValue = context.packageName
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    shape = MaterialTheme.shapes.medium
                ) {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(Background),
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
                                controller.setZoom(16.0)
                                controller.setCenter(GeoPoint(config.meetingLat, config.meetingLng))
                                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)

                                // Meeting point marker
                                val meetingGeo = GeoPoint(config.meetingLat, config.meetingLng)
                                overlays.add(object : Overlay() {
                                    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
                                        if (shadow) return
                                        val projection = mapView.projection
                                        val pt = android.graphics.Point()
                                        projection.toPixels(meetingGeo, pt)
                                        val px = pt.x.toFloat()
                                        val py = pt.y.toFloat()

                                        // Outer glow
                                        canvas.drawCircle(px, py, 18f,
                                            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                                style = Paint.Style.FILL
                                                color = meetingMarkerArgb
                                                alpha = 50
                                            })

                                        // Inner dot
                                        canvas.drawCircle(px, py, 10f,
                                            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                                style = Paint.Style.FILL
                                                color = meetingMarkerArgb
                                            })

                                        // Border
                                        canvas.drawCircle(px, py, 10f,
                                            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                                style = Paint.Style.STROKE
                                                color = meetingMarkerArgb
                                                strokeWidth = 2f
                                            })
                                    }
                                })
                            }
                        }
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
                    val playerCount = maxOf(selectedGame?.currentPlayers ?: 0, config.minPlayers)
                    val totalPool = (config.entryFee * playerCount) + config.baseReward
                    PrizeRow(
                        "Winner (${formatBpsPercent(config.bps1st)})",
                        "%.3f ETH".format(totalPool * config.bps1st / 10_000.0)
                    )
                    PrizeRow(
                        "Most Kills (${formatBpsPercent(config.bpsKills)})",
                        "%.3f ETH".format(totalPool * config.bpsKills / 10_000.0)
                    )
                    PrizeRow(
                        "2nd Place (${formatBpsPercent(config.bps2nd)})",
                        "%.3f ETH".format(totalPool * config.bps2nd / 10_000.0)
                    )
                    PrizeRow(
                        "3rd Place (${formatBpsPercent(config.bps3rd)})",
                        "%.3f ETH".format(totalPool * config.bps3rd / 10_000.0)
                    )
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

            Spacer(Modifier.height(12.dp))

            Text(
                "Troubleshooting",
                style = MaterialTheme.typography.labelSmall,
                color = TextDim
            )
            TextButton(
                enabled = !isRefreshing,
                onClick = {
                    isRefreshing = true
                    refreshError = null
                    viewModel.refreshRegisteredDetail(
                        gameId = config.id,
                        onSuccess = {
                            isRefreshing = false
                        },
                        onError = { error ->
                            refreshError = error
                            isRefreshing = false
                        }
                    )
                }
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = TextDim
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Re-syncing state...", color = TextDim, style = MaterialTheme.typography.labelMedium)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = TextDim, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Re-sync live state", color = TextDim, style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(
                "Use only if live status looks stuck.",
                style = MaterialTheme.typography.bodySmall,
                color = TextDim
            )

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

private fun formatBpsPercent(bps: Int): String {
    val pct = bps / 100.0
    return if (pct % 1.0 == 0.0) {
        "${pct.toInt()}%"
    } else {
        "${"%.2f".format(Locale.US, pct)}%"
    }
}
