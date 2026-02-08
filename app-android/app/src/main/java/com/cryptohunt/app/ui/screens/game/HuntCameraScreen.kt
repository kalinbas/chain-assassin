package com.cryptohunt.app.ui.screens.game

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.cryptohunt.app.domain.game.KillResult
import com.cryptohunt.app.ui.theme.*
import com.cryptohunt.app.ui.viewmodel.GameViewModel
import com.cryptohunt.app.util.QrGenerator
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay

@Composable
fun HuntCameraScreen(
    onKillConfirmed: () -> Unit,
    onBack: () -> Unit,
    viewModel: GameViewModel = hiltViewModel()
) {
    val gameState by viewModel.gameState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current

    var scannedTarget by remember { mutableStateOf(false) }
    var wrongTarget by remember { mutableStateOf(false) }
    var holdProgress by remember { mutableStateOf(0f) }
    var killConfirmed by remember { mutableStateOf(false) }
    var lastScannedPayload by remember { mutableStateOf("") }

    // Hold timer for kill confirmation
    LaunchedEffect(scannedTarget) {
        if (scannedTarget) {
            holdProgress = 0f
            val steps = 30 // 3 seconds / 100ms
            for (i in 1..steps) {
                delay(100)
                holdProgress = i.toFloat() / steps
            }
            // Hold complete — process kill
            val result = viewModel.processKill(lastScannedPayload)
            if (result is KillResult.Confirmed) {
                killConfirmed = true
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                delay(1500)
                onKillConfirmed()
            }
        }
    }

    // Reset wrong target indicator
    LaunchedEffect(wrongTarget) {
        if (wrongTarget) {
            delay(1500)
            wrongTarget = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Camera preview
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    val scanner = BarcodeScanning.getClient()

                    imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                        @androidx.camera.core.ExperimentalGetImage
                        val mediaImage = imageProxy.image
                        if (mediaImage != null) {
                            val inputImage = InputImage.fromMediaImage(
                                mediaImage,
                                imageProxy.imageInfo.rotationDegrees
                            )
                            scanner.process(inputImage)
                                .addOnSuccessListener { barcodes ->
                                    for (barcode in barcodes) {
                                        if (barcode.format == Barcode.FORMAT_QR_CODE) {
                                            val raw = barcode.rawValue ?: continue
                                            if (raw.startsWith("cryptohunt:") && !scannedTarget && !killConfirmed) {
                                                val parsed = QrGenerator.parsePayload(raw)
                                                if (parsed != null) {
                                                    val targetId = gameState?.currentTarget?.player?.id ?: ""
                                                    if (parsed.second == targetId) {
                                                        lastScannedPayload = raw
                                                        scannedTarget = true
                                                    } else {
                                                        wrongTarget = true
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                .addOnCompleteListener {
                                    imageProxy.close()
                                }
                        } else {
                            imageProxy.close()
                        }
                    }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis
                        )
                    } catch (e: Exception) {
                        // Camera binding failed
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Reticle overlay
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerX = size.width / 2
            val centerY = size.height / 2
            val reticleSize = size.minDimension * 0.6f
            val halfSize = reticleSize / 2
            val cornerLen = reticleSize * 0.15f
            val strokeWidth = 3f

            // Dim area outside reticle
            val path = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = centerX - halfSize,
                        top = centerY - halfSize,
                        right = centerX + halfSize,
                        bottom = centerY + halfSize,
                        cornerRadius = CornerRadius(16f)
                    )
                )
            }
            clipPath(path, clipOp = ClipOp.Difference) {
                drawRect(Color.Black.copy(alpha = 0.5f))
            }

            val reticleColor = when {
                killConfirmed -> Primary
                scannedTarget -> Primary
                wrongTarget -> Danger
                else -> Color.White
            }

            // Corner brackets
            val corners = listOf(
                // Top-left
                Pair(Offset(centerX - halfSize, centerY - halfSize + cornerLen), Offset(centerX - halfSize, centerY - halfSize)),
                Pair(Offset(centerX - halfSize, centerY - halfSize), Offset(centerX - halfSize + cornerLen, centerY - halfSize)),
                // Top-right
                Pair(Offset(centerX + halfSize - cornerLen, centerY - halfSize), Offset(centerX + halfSize, centerY - halfSize)),
                Pair(Offset(centerX + halfSize, centerY - halfSize), Offset(centerX + halfSize, centerY - halfSize + cornerLen)),
                // Bottom-left
                Pair(Offset(centerX - halfSize, centerY + halfSize - cornerLen), Offset(centerX - halfSize, centerY + halfSize)),
                Pair(Offset(centerX - halfSize, centerY + halfSize), Offset(centerX - halfSize + cornerLen, centerY + halfSize)),
                // Bottom-right
                Pair(Offset(centerX + halfSize - cornerLen, centerY + halfSize), Offset(centerX + halfSize, centerY + halfSize)),
                Pair(Offset(centerX + halfSize, centerY + halfSize), Offset(centerX + halfSize, centerY + halfSize - cornerLen))
            )
            corners.forEach { (start, end) ->
                drawLine(reticleColor, start, end, strokeWidth, StrokeCap.Round)
            }

            // Progress ring (when holding for kill)
            if (scannedTarget && holdProgress > 0f) {
                drawArc(
                    color = Primary,
                    startAngle = -90f,
                    sweepAngle = 360f * holdProgress,
                    useCenter = false,
                    topLeft = Offset(centerX - halfSize - 8, centerY - halfSize - 8),
                    size = androidx.compose.ui.geometry.Size(reticleSize + 16, reticleSize + 16),
                    style = Stroke(width = 4f, cap = StrokeCap.Round)
                )
            }
        }

        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .systemBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.Close, "Close", tint = Color.White)
            }

            val targetNum = gameState?.currentTarget?.player?.number
            if (targetNum != null) {
                Text(
                    "Target: #$targetNum",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Status text
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when {
                killConfirmed -> {
                    Text(
                        "KILL CONFIRMED",
                        style = MaterialTheme.typography.headlineLarge,
                        color = Primary,
                        fontWeight = FontWeight.Black
                    )
                }
                scannedTarget -> {
                    Text(
                        "TARGET LOCKED \u2014 HOLD",
                        style = MaterialTheme.typography.titleMedium,
                        color = Primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                wrongTarget -> {
                    Text(
                        "NOT YOUR TARGET",
                        style = MaterialTheme.typography.titleMedium,
                        color = Danger,
                        fontWeight = FontWeight.Bold
                    )
                }
                else -> {
                    Text(
                        "Scan target's QR code",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}
