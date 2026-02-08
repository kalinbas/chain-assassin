package com.cryptohunt.app.ui.screens.game

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.DashPathEffect
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.cryptohunt.app.ui.theme.*
import com.cryptohunt.app.ui.viewmodel.MapViewModel
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
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
    val zoneRadius = gameState?.currentZoneRadius ?: config?.initialRadiusMeters ?: 1000.0

    val zoneCenterLat = config?.zoneCenterLat ?: 0.0
    val zoneCenterLng = config?.zoneCenterLng ?: 0.0

    val heatmapBlobs = remember { viewModel.generateHeatmapBlobs() }
    var blobsState by remember { mutableStateOf(heatmapBlobs) }

    // Refresh heatmap periodically
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            blobsState = viewModel.generateHeatmapBlobs()
        }
    }

    // Configure osmdroid
    LaunchedEffect(Unit) {
        Configuration.getInstance().userAgentValue = context.packageName
    }

    // Colors for the canvas overlays
    val dangerArgb = Danger.toArgb()
    val dangerFillArgb = Danger.copy(alpha = 0.1f).toArgb()
    val warningArgb = Warning.copy(alpha = 0.5f).toArgb()
    val warningStrongArgb = Warning.copy(alpha = 0.7f).toArgb()
    val warningFillArgb = Warning.copy(alpha = 0.08f).toArgb()
    val primaryArgb = Primary.toArgb()

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
                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        controller.setZoom(15.0)
                        controller.setCenter(GeoPoint(zoneCenterLat, zoneCenterLng))

                        // Enable built-in zoom controls
                        setBuiltInZoomControls(true)
                    }
                },
                update = { mapView ->
                    mapView.overlays.clear()

                    val center = GeoPoint(zoneCenterLat, zoneCenterLng)

                    // Current zone circle overlay
                    mapView.overlays.add(object : Overlay() {
                        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
                            if (shadow) return
                            val projection = mapView.projection
                            val centerPoint = android.graphics.Point()
                            projection.toPixels(center, centerPoint)

                            // Calculate pixel radius from meters
                            val edgePoint = center.destinationPoint(zoneRadius, 90.0)
                            val edgePixel = android.graphics.Point()
                            projection.toPixels(GeoPoint(edgePoint.latitude, edgePoint.longitude), edgePixel)
                            val pixelRadius = Math.abs(edgePixel.x - centerPoint.x).toFloat()

                            // Fill
                            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                style = Paint.Style.FILL
                                color = dangerFillArgb
                            }
                            canvas.drawCircle(centerPoint.x.toFloat(), centerPoint.y.toFloat(), pixelRadius, fillPaint)

                            // Stroke
                            val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                style = Paint.Style.STROKE
                                color = dangerArgb
                                strokeWidth = 3f
                            }
                            canvas.drawCircle(centerPoint.x.toFloat(), centerPoint.y.toFloat(), pixelRadius, strokePaint)
                        }
                    })

                    // All future zone shrink previews (dashed circles)
                    val futureShrinks = gameState?.config?.shrinkSchedule?.filter {
                        it.newRadiusMeters < zoneRadius
                    } ?: emptyList()
                    val finalShrinkRadius = gameState?.config?.shrinkSchedule?.lastOrNull()?.newRadiusMeters
                    futureShrinks.forEach { shrink ->
                        val isFinal = shrink.newRadiusMeters == finalShrinkRadius
                        mapView.overlays.add(object : Overlay() {
                            override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
                                if (shadow) return
                                val projection = mapView.projection
                                val centerPoint = android.graphics.Point()
                                projection.toPixels(center, centerPoint)

                                val edgePoint = center.destinationPoint(shrink.newRadiusMeters, 90.0)
                                val edgePixel = android.graphics.Point()
                                projection.toPixels(GeoPoint(edgePoint.latitude, edgePoint.longitude), edgePixel)
                                val pixelRadius = Math.abs(edgePixel.x - centerPoint.x).toFloat()

                                if (isFinal) {
                                    // Final zone: filled + solid stroke
                                    canvas.drawCircle(centerPoint.x.toFloat(), centerPoint.y.toFloat(), pixelRadius,
                                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                            style = Paint.Style.FILL
                                            color = warningFillArgb
                                        })
                                    canvas.drawCircle(centerPoint.x.toFloat(), centerPoint.y.toFloat(), pixelRadius,
                                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                            style = Paint.Style.STROKE
                                            color = warningStrongArgb
                                            strokeWidth = 3f
                                        })
                                } else {
                                    // Other shrinks: dashed stroke
                                    canvas.drawCircle(centerPoint.x.toFloat(), centerPoint.y.toFloat(), pixelRadius,
                                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                            style = Paint.Style.STROKE
                                            color = warningArgb
                                            strokeWidth = 2f
                                            pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
                                        })
                                }
                            }
                        })
                    }

                    // Heatmap blobs
                    blobsState.forEach { blob ->
                        mapView.overlays.add(object : Overlay() {
                            override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
                                if (shadow) return
                                val projection = mapView.projection
                                val blobGeo = GeoPoint(blob.lat, blob.lng)
                                val blobPoint = android.graphics.Point()
                                projection.toPixels(blobGeo, blobPoint)

                                val blobRadius = 50.0 + (blob.intensity * 100).toDouble()
                                val edgePoint = blobGeo.destinationPoint(blobRadius, 90.0)
                                val edgePixel = android.graphics.Point()
                                projection.toPixels(GeoPoint(edgePoint.latitude, edgePoint.longitude), edgePixel)
                                val pixelRadius = Math.abs(edgePixel.x - blobPoint.x).toFloat().coerceAtLeast(4f)

                                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    style = Paint.Style.FILL
                                    color = primaryArgb
                                    alpha = (blob.intensity * 0.3f * 255).toInt()
                                }
                                canvas.drawCircle(blobPoint.x.toFloat(), blobPoint.y.toFloat(), pixelRadius, paint)
                            }
                        })
                    }

                    // Current location marker
                    if (locationState.isTracking) {
                        val locMarker = Marker(mapView).apply {
                            position = GeoPoint(locationState.lat, locationState.lng)
                            title = "You"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        }
                        mapView.overlays.add(locMarker)
                    }

                    mapView.invalidate()
                }
            )

            // Zone info card
            Card(
                modifier = Modifier.padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = Surface.copy(alpha = 0.9f)),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Zone: ${zoneRadius.toInt()}m radius",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextPrimary
                    )
                    gameState?.let { state ->
                        Text(
                            "${state.playersRemaining} players remaining",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}
