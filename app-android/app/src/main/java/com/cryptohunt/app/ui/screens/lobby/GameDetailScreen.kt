package com.cryptohunt.app.ui.screens.lobby

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.cryptohunt.app.domain.model.GamePhase
import com.cryptohunt.app.ui.theme.*
import com.cryptohunt.app.ui.viewmodel.LobbyViewModel
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDetailScreen(
    gameId: String = "",
    onJoinGame: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: LobbyViewModel = hiltViewModel()
) {
    LaunchedEffect(gameId) {
        if (gameId.isNotEmpty()) {
            viewModel.selectGame(gameId)
        }
    }

    val game by viewModel.selectedGame.collectAsState()
    val walletState by viewModel.walletState.collectAsState()
    val gameState by viewModel.gameState.collectAsState()
    var showConfirmDialog by remember { mutableStateOf(false) }
    val alreadyRegistered = gameState != null &&
            gameState?.config?.id == gameId &&
            gameState?.phase in listOf(GamePhase.REGISTERED, GamePhase.CHECK_IN, GamePhase.ACTIVE)

    val config = game?.config ?: return
    val context = LocalContext.current

    // Configure osmdroid
    LaunchedEffect(Unit) {
        Configuration.getInstance().userAgentValue = context.packageName
    }

    val dangerArgb = Danger.toArgb()
    val dangerFillArgb = Danger.copy(alpha = 0.1f).toArgb()
    val warningArgb = Warning.copy(alpha = 0.7f).toArgb()
    val warningFillArgb = Warning.copy(alpha = 0.08f).toArgb()

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
                Button(
                    onClick = { showConfirmDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary,
                        contentColor = Background
                    ),
                    enabled = canAfford && !alreadyRegistered
                ) {
                    Text(
                        when {
                            alreadyRegistered -> "Already Registered"
                            canAfford -> "Register \u2014 ${config.entryFee} ETH"
                            else -> "Insufficient Balance"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        containerColor = Background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Zone preview map
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        MapView(ctx).apply {
                            setTileSource(TileSourceFactory.MAPNIK)
                            setMultiTouchControls(true)
                            controller.setZoom(15.0)
                            controller.setCenter(GeoPoint(config.zoneCenterLat, config.zoneCenterLng))
                            // Disable interactions for preview
                            setBuiltInZoomControls(false)
                        }
                    },
                    update = { mapView ->
                        mapView.overlays.clear()
                        val center = GeoPoint(config.zoneCenterLat, config.zoneCenterLng)
                        val radius = config.initialRadiusMeters

                        // Zone circle
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

                                // Fill
                                canvas.drawCircle(centerPx.x.toFloat(), centerPx.y.toFloat(), pxRadius,
                                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                        style = Paint.Style.FILL
                                        color = dangerFillArgb
                                    })
                                // Stroke
                                canvas.drawCircle(centerPx.x.toFloat(), centerPx.y.toFloat(), pxRadius,
                                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                        style = Paint.Style.STROKE
                                        color = dangerArgb
                                        strokeWidth = 3f
                                    })
                            }
                        })

                        // Final zone circle (smallest shrink)
                        val finalShrink = config.shrinkSchedule.lastOrNull()
                        if (finalShrink != null) {
                            mapView.overlays.add(object : Overlay() {
                                override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
                                    if (shadow) return
                                    val projection = mapView.projection
                                    val centerPx = android.graphics.Point()
                                    projection.toPixels(center, centerPx)

                                    val edgeGeo = center.destinationPoint(finalShrink.newRadiusMeters, 90.0)
                                    val edgePx = android.graphics.Point()
                                    projection.toPixels(GeoPoint(edgeGeo.latitude, edgeGeo.longitude), edgePx)
                                    val pxRadius = Math.abs(edgePx.x - centerPx.x).toFloat()

                                    // Fill
                                    canvas.drawCircle(centerPx.x.toFloat(), centerPx.y.toFloat(), pxRadius,
                                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                            style = Paint.Style.FILL
                                            color = warningFillArgb
                                        })
                                    // Dashed stroke
                                    canvas.drawCircle(centerPx.x.toFloat(), centerPx.y.toFloat(), pxRadius,
                                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                            style = Paint.Style.STROKE
                                            color = warningArgb
                                            strokeWidth = 2f
                                            pathEffect = DashPathEffect(floatArrayOf(15f, 8f), 0f)
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
                    val finalRadius = config.shrinkSchedule.lastOrNull()?.newRadiusMeters?.toInt()
                    Text(
                        if (finalRadius != null) {
                            "Zone: ${config.initialRadiusMeters.toInt()}m → ${finalRadius}m"
                        } else {
                            "Zone: ${config.initialRadiusMeters.toInt()}m radius"
                        },
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
            InfoRow("Entry Fee", "${config.entryFee} ETH")
            InfoRow("Players", "${config.minPlayers} – ${config.maxPlayers}")
            InfoRow("Duration", "${config.durationMinutes} minutes")
            InfoRow("Check-in", "${config.checkInDurationMinutes} min before start")
            InfoRow("Zone Shrinks", "${config.shrinkSchedule.size} stages")

            Spacer(Modifier.height(24.dp))

            // Rules
            Text("Rules", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Spacer(Modifier.height(8.dp))
            RuleItem("Scan your target's QR code to eliminate them")
            RuleItem("Stay inside the zone \u2014 it shrinks over time")
            RuleItem("Last player standing wins the prize pool")

            Spacer(Modifier.height(24.dp))

            // Prize breakdown
            Text("Prize Distribution", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Spacer(Modifier.height(8.dp))
            val prizePool = config.entryFee * config.maxPlayers * 0.9
            InfoRow("Winner (40%)", "%.3f ETH".format(prizePool * 0.4))
            InfoRow("Most Kills (20%)", "%.3f ETH".format(prizePool * 0.2))
            InfoRow("2nd Place (15%)", "%.3f ETH".format(prizePool * 0.15))
            InfoRow("3rd Place (10%)", "%.3f ETH".format(prizePool * 0.1))
            InfoRow("Platform (10%)", "%.3f ETH".format(prizePool * 0.1))

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
                        "If fewer than ${config.minPlayers} players register by start time, " +
                        "your entry fee is fully refundable.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmDialog = false
                    if (viewModel.registerForGame(config.id)) {
                        onJoinGame(config.id)
                    }
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
