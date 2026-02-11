package com.cryptohunt.app.ui.screens.game

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.cryptohunt.app.domain.model.HeartbeatResult
import com.cryptohunt.app.ui.theme.*
import com.cryptohunt.app.ui.viewmodel.GameViewModel
import com.cryptohunt.app.util.QrGenerator
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay

@Composable
fun HeartbeatScanScreen(
    onSuccess: () -> Unit,
    onBack: () -> Unit,
    viewModel: GameViewModel = hiltViewModel()
) {
    val gameState by viewModel.gameState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current

    var success by remember { mutableStateOf(false) }
    var successPlayerNumber by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Auto-navigate back after success
    LaunchedEffect(success) {
        if (success) {
            delay(1500)
            onSuccess()
        }
    }

    // Reset error message
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            delay(2000)
            errorMessage = null
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
                                            if (!success && QrGenerator.parsePayload(raw) != null) {
                                                val result = viewModel.processHeartbeatScan(raw)
                                                when (result) {
                                                    is HeartbeatResult.Success -> {
                                                        success = true
                                                        successPlayerNumber = result.scannedPlayerNumber
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    }
                                                    is HeartbeatResult.ScanYourself -> {
                                                        errorMessage = "You can\u2019t scan yourself!"
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    }
                                                    is HeartbeatResult.ScanTarget -> {
                                                        errorMessage = "Can\u2019t heartbeat with your target!"
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    }
                                                    is HeartbeatResult.ScanHunter -> {
                                                        errorMessage = "Can\u2019t heartbeat with your hunter!"
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    }
                                                    is HeartbeatResult.PlayerNotAlive -> {
                                                        errorMessage = "This player is eliminated"
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    }
                                                    is HeartbeatResult.UnknownPlayer -> {
                                                        errorMessage = "Unknown player"
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    }
                                                    is HeartbeatResult.HeartbeatDisabled -> {
                                                        errorMessage = "Heartbeat disabled (endgame)"
                                                    }
                                                    else -> {}
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
                success -> Primary
                errorMessage != null -> Danger
                else -> Color.White
            }

            // Corner brackets
            val corners = listOf(
                Pair(Offset(centerX - halfSize, centerY - halfSize + cornerLen), Offset(centerX - halfSize, centerY - halfSize)),
                Pair(Offset(centerX - halfSize, centerY - halfSize), Offset(centerX - halfSize + cornerLen, centerY - halfSize)),
                Pair(Offset(centerX + halfSize - cornerLen, centerY - halfSize), Offset(centerX + halfSize, centerY - halfSize)),
                Pair(Offset(centerX + halfSize, centerY - halfSize), Offset(centerX + halfSize, centerY - halfSize + cornerLen)),
                Pair(Offset(centerX - halfSize, centerY + halfSize - cornerLen), Offset(centerX - halfSize, centerY + halfSize)),
                Pair(Offset(centerX - halfSize, centerY + halfSize), Offset(centerX - halfSize + cornerLen, centerY + halfSize)),
                Pair(Offset(centerX + halfSize - cornerLen, centerY + halfSize), Offset(centerX + halfSize, centerY + halfSize)),
                Pair(Offset(centerX + halfSize, centerY + halfSize), Offset(centerX + halfSize, centerY + halfSize - cornerLen))
            )
            corners.forEach { (start, end) ->
                drawLine(reticleColor, start, end, 3f, StrokeCap.Round)
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

            Text(
                "Heartbeat Scan",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }

        // Status text
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when {
                success -> {
                    Text(
                        "\u2764\ufe0f HEARTBEAT OK",
                        style = MaterialTheme.typography.headlineLarge,
                        color = Primary,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        "Player #$successPlayerNumber scanned",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
                errorMessage != null -> {
                    Text(
                        errorMessage!!,
                        style = MaterialTheme.typography.titleMedium,
                        color = Danger,
                        fontWeight = FontWeight.Bold
                    )
                }
                else -> {
                    Text(
                        "Scan another player\u2019s QR code",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Not your target or hunter!",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}
