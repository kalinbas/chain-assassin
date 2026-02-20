package com.cryptohunt.app.ui.screens.game

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.cryptohunt.app.ui.theme.*
import com.cryptohunt.app.ui.viewmodel.MapViewModel
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    onBack: () -> Unit,
    viewModel: MapViewModel = hiltViewModel()
) {
    val gameState by viewModel.gameState.collectAsState()
    val locationState by viewModel.locationState.collectAsState()
    val context = LocalContext.current

    val config = gameState?.config
    val activePing = gameState?.activePing
    val zoneRadius = gameState?.currentZoneRadius ?: config?.initialRadiusMeters ?: 1000.0

    val zoneCenterLat = config?.zoneCenterLat ?: 0.0
    val zoneCenterLng = config?.zoneCenterLng ?: 0.0

    // Compute next shrink radius from schedule using server gameStartTime
    val nextShrinkRadius = remember(gameState?.currentZoneRadius, gameState?.gameStartTime) {
        val state = gameState ?: return@remember null
        if (state.gameStartTime <= 0L) return@remember null
        val elapsedSeconds = (System.currentTimeMillis() / 1000) - state.gameStartTime
        val elapsedMinutes = (elapsedSeconds / 60).toInt()
        val schedule = state.config.shrinkSchedule
        val nextShrink = schedule.firstOrNull { it.atMinute > elapsedMinutes }
        if (nextShrink != null && nextShrink.newRadiusMeters < state.currentZoneRadius) {
            nextShrink.newRadiusMeters
        } else null
    }

    // Configure osmdroid
    LaunchedEffect(Unit) {
        Configuration.getInstance().userAgentValue = context.packageName
    }

    val mapViewRef = remember { mutableStateOf<MapView?>(null) }

    // Auto-center on ping when map opens with active ping
    LaunchedEffect(activePing) {
        if (activePing != null && System.currentTimeMillis() < activePing.expiresAt) {
            kotlinx.coroutines.delay(500)
            mapViewRef.value?.controller?.animateTo(
                GeoPoint(activePing.lat, activePing.lng), 17.0, 1000L
            )
        }
    }

    // Clear expired pings
    LaunchedEffect(activePing) {
        if (activePing != null) {
            val remaining = activePing.expiresAt - System.currentTimeMillis()
            if (remaining > 0) {
                kotlinx.coroutines.delay(remaining)
                viewModel.clearExpiredPing()
            }
        }
    }

    // Colors
    val primaryFillArgb = Primary.copy(alpha = 0.08f).toArgb()
    val primaryStrokeArgb = Primary.copy(alpha = 0.8f).toArgb()
    val nextZoneStrokeArgb = Warning.copy(alpha = 0.6f).toArgb()

    val shieldArgb = Shield.toArgb()
    val whiteArgb = Color.White.toArgb()

    val pingTargetArgb = Primary.toArgb()
    val pingHunterArgb = Danger.toArgb()
    val pingTargetFillArgb = Primary.copy(alpha = 0.15f).toArgb()
    val pingHunterFillArgb = Danger.copy(alpha = 0.15f).toArgb()

    // Pulse animation for player marker
    val infiniteTransition = rememberInfiniteTransition(label = "playerPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    val pulseAlphaInt = (pulseAlpha * 255).toInt()

    // Ping circle pulse animation
    val pingPulse by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pingPulse"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Zone Map", style = MaterialTheme.typography.titleLarge) },
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
                .padding(padding)
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
                        setTileSource(cartoDark)
                        setMultiTouchControls(true)
                        controller.setZoom(15.0)
                        controller.setCenter(GeoPoint(zoneCenterLat, zoneCenterLng))
                        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    }.also { mapViewRef.value = it }
                },
                update = { mapView ->
                    mapView.overlays.clear()

                    val center = GeoPoint(zoneCenterLat, zoneCenterLng)

                    // Current zone circle — green stroke + fill
                    mapView.overlays.add(object : Overlay() {
                        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
                            if (shadow) return
                            val projection = mapView.projection
                            val centerPoint = android.graphics.Point()
                            projection.toPixels(center, centerPoint)

                            val edgePoint = center.destinationPoint(zoneRadius, 90.0)
                            val edgePixel = android.graphics.Point()
                            projection.toPixels(GeoPoint(edgePoint.latitude, edgePoint.longitude), edgePixel)
                            val pixelRadius = Math.abs(edgePixel.x - centerPoint.x).toFloat()

                            // Fill
                            canvas.drawCircle(centerPoint.x.toFloat(), centerPoint.y.toFloat(), pixelRadius,
                                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    style = Paint.Style.FILL
                                    color = primaryFillArgb
                                })

                            // Stroke
                            canvas.drawCircle(centerPoint.x.toFloat(), centerPoint.y.toFloat(), pixelRadius,
                                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    style = Paint.Style.STROKE
                                    color = primaryStrokeArgb
                                    strokeWidth = 2f
                                })
                        }
                    })

                    // Next shrink zone — dashed yellow/warning circle
                    if (nextShrinkRadius != null) {
                        mapView.overlays.add(object : Overlay() {
                            override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
                                if (shadow) return
                                val projection = mapView.projection
                                val centerPoint = android.graphics.Point()
                                projection.toPixels(center, centerPoint)

                                val edgePoint = center.destinationPoint(nextShrinkRadius, 90.0)
                                val edgePixel = android.graphics.Point()
                                projection.toPixels(GeoPoint(edgePoint.latitude, edgePoint.longitude), edgePixel)
                                val pixelRadius = Math.abs(edgePixel.x - centerPoint.x).toFloat()

                                canvas.drawCircle(centerPoint.x.toFloat(), centerPoint.y.toFloat(), pixelRadius,
                                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                        style = Paint.Style.STROKE
                                        color = nextZoneStrokeArgb
                                        strokeWidth = 2f
                                        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
                                    })
                            }
                        })
                    }

                    // Own position — cyan pulsing marker with white center
                    if (locationState.isTracking) {
                        val playerGeo = GeoPoint(locationState.lat, locationState.lng)
                        mapView.overlays.add(object : Overlay() {
                            override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
                                if (shadow) return
                                val projection = mapView.projection
                                val pt = android.graphics.Point()
                                projection.toPixels(playerGeo, pt)
                                val px = pt.x.toFloat()
                                val py = pt.y.toFloat()

                                // Outer pulse ring
                                canvas.drawCircle(px, py, 22f,
                                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                        style = Paint.Style.FILL
                                        color = shieldArgb
                                        alpha = pulseAlphaInt
                                    })

                                // Middle glow
                                canvas.drawCircle(px, py, 14f,
                                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                        style = Paint.Style.FILL
                                        color = shieldArgb
                                        alpha = 90
                                    })

                                // Inner solid dot (white)
                                canvas.drawCircle(px, py, 7f,
                                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                        style = Paint.Style.FILL
                                        color = whiteArgb
                                    })

                                // Cyan border on inner dot
                                canvas.drawCircle(px, py, 7f,
                                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                        style = Paint.Style.STROKE
                                        color = shieldArgb
                                        strokeWidth = 1.5f
                                    })
                            }
                        })
                    }

                    // Ping circle overlay from items (50m radius, pulsing)
                    if (activePing != null && System.currentTimeMillis() < activePing.expiresAt) {
                        val pingGeo = GeoPoint(activePing.lat, activePing.lng)
                        val isTarget = activePing.type == "ping_target"
                        val pingStrokeColor = if (isTarget) pingTargetArgb else pingHunterArgb
                        val pingFillColor = if (isTarget) pingTargetFillArgb else pingHunterFillArgb

                        mapView.overlays.add(object : Overlay() {
                            override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
                                if (shadow) return
                                val projection = mapView.projection
                                val pingPoint = android.graphics.Point()
                                projection.toPixels(pingGeo, pingPoint)

                                val pingEdge = pingGeo.destinationPoint(activePing.radiusMeters * pingPulse.toDouble(), 90.0)
                                val pingEdgePixel = android.graphics.Point()
                                projection.toPixels(GeoPoint(pingEdge.latitude, pingEdge.longitude), pingEdgePixel)
                                val pingPixelRadius = Math.abs(pingEdgePixel.x - pingPoint.x).toFloat().coerceAtLeast(8f)

                                // Fill
                                canvas.drawCircle(pingPoint.x.toFloat(), pingPoint.y.toFloat(), pingPixelRadius,
                                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                        style = Paint.Style.FILL
                                        color = pingFillColor
                                    })

                                // Stroke
                                canvas.drawCircle(pingPoint.x.toFloat(), pingPoint.y.toFloat(), pingPixelRadius,
                                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                        style = Paint.Style.STROKE
                                        color = pingStrokeColor
                                        strokeWidth = 3f
                                    })

                                // Center dot
                                canvas.drawCircle(pingPoint.x.toFloat(), pingPoint.y.toFloat(), 6f,
                                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                        style = Paint.Style.FILL
                                        color = pingStrokeColor
                                    })
                            }
                        })
                    }

                    mapView.invalidate()
                }
            )

            // Center-on-me button
            if (locationState.isTracking) {
                SmallFloatingActionButton(
                    onClick = {
                        mapViewRef.value?.controller?.animateTo(
                            GeoPoint(locationState.lat, locationState.lng)
                        )
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    containerColor = Surface,
                    contentColor = Primary
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = "Center on my location")
                }
            }
        }
    }
}
