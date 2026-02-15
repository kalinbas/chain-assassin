package com.cryptohunt.app.ui.screens.onboarding

import android.Manifest
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.SoundEffectConstants
import android.view.View
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.cryptohunt.app.ui.testing.TestTags
import com.cryptohunt.app.ui.theme.Danger
import com.cryptohunt.app.ui.theme.Primary
import com.cryptohunt.app.ui.theme.Warning
import com.cryptohunt.app.ui.viewmodel.DebugScanMode
import com.cryptohunt.app.ui.viewmodel.ScanDebugViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.launch

private const val SCAN_DEBUG_PREFS = "scan_debug_prefs"
private const val PREF_MAX_OPTICAL_CAMERA = "pref_max_optical_camera"

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ScanDebugScreen(
    onBack: () -> Unit,
    onShowResult: () -> Unit,
    viewModel: ScanDebugViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    val haptic = LocalHapticFeedback.current

    val permissions = remember {
        buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
    }
    val permissionsState = rememberMultiplePermissionsState(permissions)

    val cameraGranted = permissionsState.permissions
        .firstOrNull { it.permission == Manifest.permission.CAMERA }
        ?.status?.isGranted == true

    val locationGranted = permissionsState.permissions
        .firstOrNull { it.permission == Manifest.permission.ACCESS_FINE_LOCATION }
        ?.status?.isGranted == true

    val bluetoothGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        permissionsState.permissions
            .filter {
                it.permission == Manifest.permission.BLUETOOTH_SCAN ||
                    it.permission == Manifest.permission.BLUETOOTH_CONNECT
            }
            .all { it.status.isGranted }
    } else {
        locationGranted
    }

    val lensPrefs = remember(context) {
        context.getSharedPreferences(SCAN_DEBUG_PREFS, Context.MODE_PRIVATE)
    }
    var selectedMode by remember { mutableStateOf(DebugScanMode.CHECKIN) }
    var useMaxOpticalCamera by remember {
        mutableStateOf(lensPrefs.getBoolean(PREF_MAX_OPTICAL_CAMERA, false))
    }
    var lockFeedbackScanKey by remember { mutableStateOf<String?>(null) }
    var successFeedbackScanKey by remember { mutableStateOf<String?>(null) }
    var lockAnimationFinished by remember { mutableStateOf(false) }
    var scanFlashNonce by remember { mutableStateOf(0) }
    val scanFlashAlpha = remember { Animatable(0f) }
    val scanFlashOffset = remember { Animatable(0f) }
    val toneGenerator = remember {
        createToneGenerator(AudioManager.STREAM_MUSIC, 92)
            ?: createToneGenerator(AudioManager.STREAM_ALARM, 88)
            ?: createToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
    }

    LaunchedEffect(locationGranted, bluetoothGranted) {
        viewModel.syncSensors(
            locationPermissionGranted = locationGranted,
            bluetoothPermissionGranted = bluetoothGranted
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopSensors()
            toneGenerator?.release()
        }
    }

    LaunchedEffect(scanFlashNonce) {
        if (scanFlashNonce <= 0) return@LaunchedEffect
        scanFlashAlpha.snapTo(0f)
        scanFlashOffset.snapTo(0f)
        scanFlashAlpha.animateTo(0.34f, animationSpec = tween(durationMillis = 50, easing = LinearEasing))
        scanFlashOffset.snapTo(14f)
        scanFlashAlpha.animateTo(0.14f, animationSpec = tween(durationMillis = 45, easing = EaseInOutSine))
        scanFlashOffset.snapTo(-9f)
        scanFlashAlpha.animateTo(0f, animationSpec = tween(durationMillis = 160, easing = EaseInOutSine))
        scanFlashOffset.animateTo(0f, animationSpec = tween(durationMillis = 160, easing = EaseInOutSine))
    }

    LaunchedEffect(uiState.scanLocked, uiState.lastScannedCode) {
        val scanKey = uiState.lastScannedCode
        if (uiState.scanLocked && !scanKey.isNullOrBlank() && lockFeedbackScanKey != scanKey) {
            lockFeedbackScanKey = scanKey
            triggerScanFeedback(
                context = context,
                view = view,
                haptic = haptic,
                toneGenerator = toneGenerator,
                toneType = ToneGenerator.TONE_PROP_BEEP2,
                toneDurationMs = 120,
                vibrationMs = 45L
            )
            scanFlashNonce += 1
        }
    }

    LaunchedEffect(
        uiState.isSubmitting,
        uiState.serverResponsePretty,
        uiState.error,
        uiState.lastScannedCode
    ) {
        val scanKey = uiState.lastScannedCode
        val successfulSubmit = !uiState.isSubmitting &&
            uiState.error == null &&
            !uiState.serverResponsePretty.isNullOrBlank()
        if (successfulSubmit && !scanKey.isNullOrBlank() && successFeedbackScanKey != scanKey) {
            successFeedbackScanKey = scanKey
            triggerScanFeedback(
                context = context,
                view = view,
                haptic = haptic,
                toneGenerator = toneGenerator,
                toneType = ToneGenerator.TONE_PROP_ACK,
                toneDurationMs = 170,
                vibrationMs = 85L
            )
        }
    }

    LaunchedEffect(uiState.lastScannedCode) {
        if (uiState.lastScannedCode == null) {
            lockFeedbackScanKey = null
            successFeedbackScanKey = null
            lockAnimationFinished = false
        }
    }

    LaunchedEffect(uiState.scanLocked) {
        if (!uiState.scanLocked) lockAnimationFinished = false
    }

    LaunchedEffect(
        uiState.scanLocked,
        uiState.isSubmitting,
        uiState.serverResponsePretty,
        uiState.error,
        uiState.resultPresented,
        lockAnimationFinished
    ) {
        val hasResult = uiState.serverResponsePretty != null || uiState.error != null
        if (
            uiState.scanLocked &&
            lockAnimationFinished &&
            !uiState.isSubmitting &&
            hasResult &&
            !uiState.resultPresented
        ) {
            viewModel.markResultPresented()
            onShowResult()
        }
    }

    val scanEnabled = cameraGranted && !uiState.isSubmitting && !uiState.scanLocked
    val lensToggleEnabled = !uiState.scanLocked && !uiState.isSubmitting

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(TestTags.SCAN_DEBUG_SCREEN)
    ) {
        if (cameraGranted) {
            DebugScanCameraPreview(
                isScanEnabled = scanEnabled,
                scanLocked = uiState.scanLocked,
                useMaxOpticalCamera = useMaxOpticalCamera,
                onQrDetected = { raw, _ ->
                    lockAnimationFinished = false
                    viewModel.submitScannedCode(
                        mode = selectedMode,
                        scannedCode = raw,
                        cameraPermissionGranted = cameraGranted,
                        locationPermissionGranted = locationGranted,
                        bluetoothPermissionGranted = bluetoothGranted
                    )
                },
                onLockAnimationFinished = { lockAnimationFinished = true },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Camera permission required",
                    style = MaterialTheme.typography.titleMedium,
                    color = Danger,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (scanFlashAlpha.value > 0.001f) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val flashColor = Color(0xFF6BFFB4)
                drawRect(color = flashColor.copy(alpha = scanFlashAlpha.value * 0.5f))

                val stripeHeight = size.height / 20f
                repeat(8) { index ->
                    val y = ((index * 137f) + (scanFlashNonce * 41f)) % size.height
                    val direction = if (index % 2 == 0) 1f else -1f
                    drawRect(
                        color = flashColor.copy(
                            alpha = scanFlashAlpha.value * (0.1f + (index % 3) * 0.04f)
                        ),
                        topLeft = Offset(scanFlashOffset.value * direction, y),
                        size = androidx.compose.ui.geometry.Size(size.width, stripeHeight)
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .systemBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }

            DebugModeChip(
                selectedMode = selectedMode,
                enabled = lensToggleEnabled,
                onClick = {
                    selectedMode = when (selectedMode) {
                        DebugScanMode.CHECKIN -> DebugScanMode.HEARTBEAT
                        DebugScanMode.HEARTBEAT -> DebugScanMode.KILL
                        DebugScanMode.KILL -> DebugScanMode.CHECKIN
                    }
                }
            )
        }

        if (cameraGranted) {
            IconButton(
                enabled = lensToggleEnabled,
                onClick = {
                    val updated = !useMaxOpticalCamera
                    useMaxOpticalCamera = updated
                    lensPrefs.edit().putBoolean(PREF_MAX_OPTICAL_CAMERA, updated).apply()
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 18.dp, bottom = 92.dp)
                    .size(54.dp)
                    .background(
                        color = if (useMaxOpticalCamera) {
                            Primary.copy(alpha = 0.22f)
                        } else {
                            Color.Black.copy(alpha = 0.5f)
                        },
                        shape = CircleShape
                    )
                    .border(
                        width = 1.5.dp,
                        color = if (useMaxOpticalCamera) {
                            Primary
                        } else {
                            Color.White.copy(alpha = 0.45f)
                        },
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = if (useMaxOpticalCamera) {
                        Icons.Default.GpsFixed
                    } else {
                        Icons.Default.GpsNotFixed
                    },
                    contentDescription = if (useMaxOpticalCamera) {
                        "Max optical lens enabled"
                    } else {
                        "Normal lens enabled"
                    },
                    tint = if (lensToggleEnabled) {
                        if (useMaxOpticalCamera) Primary else Color.White
                    } else {
                        Color.White.copy(alpha = 0.35f)
                    },
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 76.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when {
                !cameraGranted -> {
                    Text(
                        text = "Grant camera permission",
                        style = MaterialTheme.typography.titleMedium,
                        color = Danger,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { permissionsState.launchMultiplePermissionRequest() }) {
                        Text("Grant Permissions")
                    }
                }

                uiState.isSubmitting -> {
                    Text(
                        text = "Submitting debug payload...",
                        style = MaterialTheme.typography.titleMedium,
                        color = Warning,
                        fontWeight = FontWeight.Bold
                    )
                }

                uiState.scanLocked -> {
                    Text(
                        text = "SCAN CAPTURED",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Opening debug details...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.75f)
                    )
                }
            }
        }
    }
}

private fun createToneGenerator(streamType: Int, volume: Int): ToneGenerator? {
    return try {
        @Suppress("DEPRECATION")
        ToneGenerator(streamType, volume)
    } catch (_: Throwable) {
        null
    }
}

private fun triggerScanFeedback(
    context: Context,
    view: View,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    toneGenerator: ToneGenerator?,
    toneType: Int,
    toneDurationMs: Int,
    vibrationMs: Long
) {
    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    @Suppress("DEPRECATION")
    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    vibrateOnce(context, vibrationMs)
    view.playSoundEffect(SoundEffectConstants.CLICK)
    toneGenerator?.startTone(toneType, toneDurationMs)
}

private fun vibrateOnce(context: Context, durationMs: Long) {
    val amplitude = 180
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(VibratorManager::class.java)
        val vibrator = vibratorManager?.defaultVibrator
        if (vibrator?.hasVibrator() == true) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
        }
    } else {
        @Suppress("DEPRECATION")
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (vibrator?.hasVibrator() == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        }
    }
}

private data class NormalizedQrBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val sourceWidth: Int,
    val sourceHeight: Int
)

private data class CanvasQrBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

private fun Barcode.toNormalizedQrBounds(sourceWidth: Int, sourceHeight: Int): NormalizedQrBounds? {
    val box: Rect = boundingBox ?: return null
    if (sourceWidth <= 0 || sourceHeight <= 0 || box.width() <= 0 || box.height() <= 0) return null
    val left = (box.left.toFloat() / sourceWidth).coerceIn(0f, 1f)
    val top = (box.top.toFloat() / sourceHeight).coerceIn(0f, 1f)
    val right = (box.right.toFloat() / sourceWidth).coerceIn(0f, 1f)
    val bottom = (box.bottom.toFloat() / sourceHeight).coerceIn(0f, 1f)
    if (right <= left || bottom <= top) return null
    return NormalizedQrBounds(
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight
    )
}

private fun NormalizedQrBounds.toCanvasBounds(canvasWidth: Float, canvasHeight: Float): CanvasQrBounds {
    val sourceW = sourceWidth.toFloat().coerceAtLeast(1f)
    val sourceH = sourceHeight.toFloat().coerceAtLeast(1f)
    val scale = max(canvasWidth / sourceW, canvasHeight / sourceH)
    val drawnWidth = sourceW * scale
    val drawnHeight = sourceH * scale
    val offsetX = (canvasWidth - drawnWidth) / 2f
    val offsetY = (canvasHeight - drawnHeight) / 2f

    val mappedLeft = offsetX + (left * drawnWidth)
    val mappedTop = offsetY + (top * drawnHeight)
    val mappedRight = offsetX + (right * drawnWidth)
    val mappedBottom = offsetY + (bottom * drawnHeight)

    val clampedLeft = mappedLeft.coerceIn(0f, canvasWidth)
    val clampedTop = mappedTop.coerceIn(0f, canvasHeight)
    val clampedRight = mappedRight.coerceIn(0f, canvasWidth)
    val clampedBottom = mappedBottom.coerceIn(0f, canvasHeight)

    if (clampedRight <= clampedLeft || clampedBottom <= clampedTop) {
        return CanvasQrBounds(
            left = canvasWidth * 0.2f,
            top = canvasHeight * 0.25f,
            right = canvasWidth * 0.8f,
            bottom = canvasHeight * 0.75f
        )
    }
    return CanvasQrBounds(
        left = clampedLeft,
        top = clampedTop,
        right = clampedRight,
        bottom = clampedBottom
    )
}

private fun lerpFloat(start: Float, end: Float, progress: Float): Float {
    val t = progress.coerceIn(0f, 1f)
    return start + (end - start) * t
}

private fun selectMaxOpticalBackCamera(
    cameraProvider: ProcessCameraProvider
): CameraSelector? {
    val backCameraInfos = cameraProvider.availableCameraInfos.filter { info ->
        Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_BACK
    }
    if (backCameraInfos.isEmpty()) return null

    val targetInfo = backCameraInfos.maxByOrNull { info ->
        Camera2CameraInfo.from(info)
            .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.maxOrNull() ?: 0f
    } ?: return null

    val targetId = runCatching { Camera2CameraInfo.from(targetInfo).cameraId }.getOrNull()
    if (targetId == null) return null

    return CameraSelector.Builder()
        .addCameraFilter { infos ->
            val filtered = infos.filter { info ->
                runCatching { Camera2CameraInfo.from(info).cameraId }.getOrNull() == targetId
            }
            if (filtered.isNotEmpty()) filtered else infos
        }
        .build()
}

private fun findDefaultBackCameraId(
    cameraProvider: ProcessCameraProvider
): String? {
    return cameraProvider.availableCameraInfos
        .mapNotNull { info ->
            val lensFacing = Camera2CameraInfo.from(info)
                .getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
            if (lensFacing != CameraCharacteristics.LENS_FACING_BACK) return@mapNotNull null
            runCatching { Camera2CameraInfo.from(info).cameraId }.getOrNull()
        }
        .minOrNull()
}

@Composable
private fun DebugModeChip(
    selectedMode: DebugScanMode,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (baseColor, accentColor) = when (selectedMode) {
        DebugScanMode.CHECKIN -> Color(0xFF5CFF8A) to Color(0xFF9EFFC8)
        DebugScanMode.HEARTBEAT -> Color(0xFF39FFC6) to Color(0xFF8DFFE4)
        DebugScanMode.KILL -> Color(0xFF78FF4F) to Color(0xFFD0FF7A)
    }
    val animatedBase by animateColorAsState(
        targetValue = baseColor,
        animationSpec = tween(durationMillis = 320),
        label = "mode_chip_base"
    )
    val animatedAccent by animateColorAsState(
        targetValue = accentColor,
        animationSpec = tween(durationMillis = 320),
        label = "mode_chip_accent"
    )
    val scale by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.96f,
        animationSpec = tween(durationMillis = 220, easing = EaseInOutSine),
        label = "mode_chip_scale"
    )
    val transition = rememberInfiniteTransition(label = "mode_chip_transition")
    val pulse by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mode_chip_pulse"
    )
    val shimmer by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "mode_chip_shimmer"
    )
    val chipShape = RoundedCornerShape(18.dp)

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .border(
                width = 1.4.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        animatedAccent.copy(alpha = 0.85f),
                        animatedBase.copy(alpha = 0.55f + pulse * 0.35f),
                        animatedAccent.copy(alpha = 0.9f)
                    ),
                    start = Offset(20f, 0f),
                    end = Offset(320f + shimmer * 150f, 120f)
                ),
                shape = chipShape
            )
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        animatedBase.copy(alpha = 0.3f + pulse * 0.25f),
                        Color.Black.copy(alpha = 0.52f),
                        animatedAccent.copy(alpha = 0.25f + pulse * 0.2f)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(300f + shimmer * 180f, 160f)
                ),
                shape = chipShape
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(
                        color = animatedAccent.copy(alpha = 0.9f),
                        shape = CircleShape
                    )
            )
            Text(
                text = selectedMode.label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun DebugScanCameraPreview(
    isScanEnabled: Boolean,
    scanLocked: Boolean,
    useMaxOpticalCamera: Boolean,
    onQrDetected: (String, NormalizedQrBounds?) -> Unit,
    onLockAnimationFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val isScanEnabledState = rememberUpdatedState(isScanEnabled)
    val onQrDetectedState = rememberUpdatedState(onQrDetected)
    val onLockAnimationFinishedState = rememberUpdatedState(onLockAnimationFinished)
    val scanScope = rememberCoroutineScope()
    val scannerOptions = remember {
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    }
    val scanner = remember(scannerOptions) { BarcodeScanning.getClient(scannerOptions) }
    var cameraProviderRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var defaultBackCameraId by remember { mutableStateOf<String?>(null) }
    var normalModeCameraId by remember { mutableStateOf<String?>(null) }
    var boundOpticalMode by remember { mutableStateOf<Boolean?>(null) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var imageAnalysisRef by remember { mutableStateOf<ImageAnalysis?>(null) }
    var analyzerContextRef by remember { mutableStateOf<Context?>(null) }
    var analyzerCleared by remember { mutableStateOf(false) }
    var frozenFrameBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var lockedQrBounds by remember { mutableStateOf<NormalizedQrBounds?>(null) }
    var hasLockedDetection by remember { mutableStateOf(false) }
    val lockToQrProgress = remember { Animatable(0f) }

    fun attachAnalyzer(imageAnalysis: ImageAnalysis, ctx: Context) {
        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
            if (!isScanEnabledState.value || hasLockedDetection) {
                imageProxy.close()
                return@setAnalyzer
            }

            @androidx.camera.core.ExperimentalGetImage
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                return@setAnalyzer
            }

            val inputImage = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.imageInfo.rotationDegrees
            )
            val rotation = imageProxy.imageInfo.rotationDegrees
            val sourceWidth = if (rotation == 90 || rotation == 270) {
                imageProxy.height
            } else {
                imageProxy.width
            }
            val sourceHeight = if (rotation == 90 || rotation == 270) {
                imageProxy.width
            } else {
                imageProxy.height
            }

            scanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    if (!isScanEnabledState.value || hasLockedDetection) return@addOnSuccessListener
                    val qr = barcodes.firstOrNull {
                        it.format == Barcode.FORMAT_QR_CODE && !it.rawValue.isNullOrBlank()
                    } ?: return@addOnSuccessListener

                    val raw = qr.rawValue ?: return@addOnSuccessListener
                    hasLockedDetection = true
                    val normalizedBounds = qr.toNormalizedQrBounds(
                        sourceWidth = sourceWidth,
                        sourceHeight = sourceHeight
                    )
                    lockedQrBounds = normalizedBounds
                    frozenFrameBitmap = previewViewRef?.bitmap
                    onQrDetectedState.value(raw, normalizedBounds)

                    if (!analyzerCleared) {
                        imageAnalysis.clearAnalyzer()
                        analyzerCleared = true
                    }

                    scanScope.launch {
                        lockToQrProgress.snapTo(0f)
                        lockToQrProgress.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(
                                durationMillis = 1000,
                                easing = EaseInOutSine
                            )
                        )
                        onLockAnimationFinishedState.value()
                    }
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }
    }

    fun bindCamera(
        cameraProvider: ProcessCameraProvider,
        previewView: PreviewView
    ) {
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(1280, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysisRef = imageAnalysis
        analyzerContextRef = previewView.context
        analyzerCleared = false
        attachAnalyzer(imageAnalysis, previewView.context)

        val cameraSelector = if (useMaxOpticalCamera) {
            selectMaxOpticalBackCamera(cameraProvider) ?: CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        try {
            cameraProvider.unbindAll()
            val camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )
            val boundCameraId = runCatching {
                Camera2CameraInfo.from(camera.cameraInfo).cameraId
            }.getOrNull()
            if (!useMaxOpticalCamera && !boundCameraId.isNullOrBlank()) {
                normalModeCameraId = boundCameraId
            }

            val referenceNormalId = normalModeCameraId ?: defaultBackCameraId
            val shouldApplyDigitalFallback = useMaxOpticalCamera &&
                !boundCameraId.isNullOrBlank() &&
                !referenceNormalId.isNullOrBlank() &&
                boundCameraId == referenceNormalId

            val requestedZoomRatio = if (shouldApplyDigitalFallback) 2f else 1f
            val zoomState = camera.cameraInfo.zoomState.value
            val minZoom = zoomState?.minZoomRatio ?: 1f
            val maxZoom = zoomState?.maxZoomRatio ?: 1f
            camera.cameraControl.setZoomRatio(requestedZoomRatio.coerceIn(minZoom, maxZoom))

            boundOpticalMode = useMaxOpticalCamera
        } catch (_: Exception) {
            // Ignore binding failures.
        }
    }

    LaunchedEffect(scanLocked) {
        if (!scanLocked) {
            hasLockedDetection = false
            lockedQrBounds = null
            frozenFrameBitmap = null
            lockToQrProgress.snapTo(0f)
        }
    }

    LaunchedEffect(useMaxOpticalCamera, cameraProviderRef, previewViewRef) {
        val cameraProvider = cameraProviderRef ?: return@LaunchedEffect
        val previewView = previewViewRef ?: return@LaunchedEffect
        if (boundOpticalMode == useMaxOpticalCamera) return@LaunchedEffect
        bindCamera(cameraProvider, previewView)
    }

    LaunchedEffect(isScanEnabled, hasLockedDetection, imageAnalysisRef, analyzerContextRef) {
        val imageAnalysis = imageAnalysisRef ?: return@LaunchedEffect
        val ctx = analyzerContextRef ?: return@LaunchedEffect
        val shouldPause = !isScanEnabled || hasLockedDetection
        if (shouldPause && !analyzerCleared) {
            imageAnalysis.clearAnalyzer()
            analyzerCleared = true
        } else if (!shouldPause && analyzerCleared) {
            attachAnalyzer(imageAnalysis, ctx)
            analyzerCleared = false
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "scan_debug_overlay")
    val glowPulse by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowPulse"
    )
    val scanSweep by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scanSweep"
    )
    val cornerPulse by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cornerPulse"
    )
    val radarRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radarRotation"
    )

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                previewViewRef = previewView
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    cameraProviderRef = cameraProvider
                    if (defaultBackCameraId == null) {
                        defaultBackCameraId = findDefaultBackCameraId(cameraProvider)
                    }
                    boundOpticalMode = null
                    bindCamera(cameraProvider, previewView)
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            update = { previewViewRef = it },
            modifier = Modifier.fillMaxSize()
        )

        if (frozenFrameBitmap != null) {
            Image(
                bitmap = frozenFrameBitmap!!.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerX = size.width / 2
            val centerY = size.height / 2
            val baseReticleSize = size.minDimension * 0.6f
            val baseHalfSize = baseReticleSize / 2
            var animatedLeft = centerX - baseHalfSize
            var animatedTop = centerY - baseHalfSize
            var animatedRight = centerX + baseHalfSize
            var animatedBottom = centerY + baseHalfSize

            val detectedBounds = lockedQrBounds?.toCanvasBounds(
                canvasWidth = size.width,
                canvasHeight = size.height
            )
            if (detectedBounds != null) {
                val t = if (scanLocked) lockToQrProgress.value else 0f
                animatedLeft = lerpFloat(animatedLeft, detectedBounds.left, t)
                animatedTop = lerpFloat(animatedTop, detectedBounds.top, t)
                animatedRight = lerpFloat(animatedRight, detectedBounds.right, t)
                animatedBottom = lerpFloat(animatedBottom, detectedBounds.bottom, t)
            }

            val animatedCenterX = (animatedLeft + animatedRight) / 2f
            val animatedCenterY = (animatedTop + animatedBottom) / 2f
            val halfWidth = max(36f, (animatedRight - animatedLeft) / 2f)
            val halfHeight = max(36f, (animatedBottom - animatedTop) / 2f)
            val frameLeft = (animatedCenterX - halfWidth).coerceIn(0f, size.width - 2f)
            val frameTop = (animatedCenterY - halfHeight).coerceIn(0f, size.height - 2f)
            val frameRight = (animatedCenterX + halfWidth).coerceIn(frameLeft + 2f, size.width)
            val frameBottom = (animatedCenterY + halfHeight).coerceIn(frameTop + 2f, size.height)
            val frameWidth = frameRight - frameLeft
            val frameHeight = frameBottom - frameTop
            val frameCenterX = (frameLeft + frameRight) / 2f
            val frameCenterY = (frameTop + frameBottom) / 2f
            val cornerLen = min(frameWidth, frameHeight) * 0.15f * cornerPulse
            val neon = Primary
            val neonAccent = Color(0xFF6BFFB4)

            val path = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = frameLeft,
                        top = frameTop,
                        right = frameRight,
                        bottom = frameBottom,
                        cornerRadius = CornerRadius(16f)
                    )
                )
            }
            clipPath(path, clipOp = ClipOp.Difference) {
                drawRect(Color.Black.copy(alpha = 0.5f))
            }

            val reticleColor = if (scanLocked) neon else neonAccent
            val ringInset = 18f

            drawRoundRect(
                color = reticleColor.copy(alpha = 0.12f + glowPulse * 0.2f),
                topLeft = Offset(frameLeft - 6f, frameTop - 6f),
                size = androidx.compose.ui.geometry.Size(frameWidth + 12f, frameHeight + 12f),
                cornerRadius = CornerRadius(20f),
                style = Stroke(width = 10f)
            )

            val radarRadius = max(frameWidth, frameHeight) / 2f + ringInset
            drawCircle(
                color = reticleColor.copy(alpha = 0.12f + glowPulse * 0.08f),
                radius = radarRadius,
                center = Offset(frameCenterX, frameCenterY),
                style = Stroke(width = 1.8f)
            )
            drawArc(
                color = neonAccent.copy(alpha = 0.55f),
                startAngle = radarRotation,
                sweepAngle = 42f,
                useCenter = false,
                topLeft = Offset(frameCenterX - radarRadius, frameCenterY - radarRadius),
                size = androidx.compose.ui.geometry.Size(
                    radarRadius * 2f,
                    radarRadius * 2f
                ),
                style = Stroke(width = 4.2f, cap = StrokeCap.Round)
            )
            drawArc(
                color = reticleColor.copy(alpha = 0.32f),
                startAngle = radarRotation + 188f,
                sweepAngle = 26f,
                useCenter = false,
                topLeft = Offset(frameCenterX - radarRadius, frameCenterY - radarRadius),
                size = androidx.compose.ui.geometry.Size(
                    radarRadius * 2f,
                    radarRadius * 2f
                ),
                style = Stroke(width = 3f, cap = StrokeCap.Round)
            )

            val corners = listOf(
                Pair(Offset(frameLeft, frameTop + cornerLen), Offset(frameLeft, frameTop)),
                Pair(Offset(frameLeft, frameTop), Offset(frameLeft + cornerLen, frameTop)),
                Pair(Offset(frameRight - cornerLen, frameTop), Offset(frameRight, frameTop)),
                Pair(Offset(frameRight, frameTop), Offset(frameRight, frameTop + cornerLen)),
                Pair(Offset(frameLeft, frameBottom - cornerLen), Offset(frameLeft, frameBottom)),
                Pair(Offset(frameLeft, frameBottom), Offset(frameLeft + cornerLen, frameBottom)),
                Pair(Offset(frameRight - cornerLen, frameBottom), Offset(frameRight, frameBottom)),
                Pair(Offset(frameRight, frameBottom), Offset(frameRight, frameBottom - cornerLen))
            )
            corners.forEach { (start, end) ->
                drawLine(reticleColor, start, end, 3f, StrokeCap.Round)
            }

            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        reticleColor.copy(alpha = 0.95f),
                        neon.copy(alpha = 0.65f),
                        reticleColor.copy(alpha = 0.95f)
                    ),
                    start = Offset(frameLeft, frameTop),
                    end = Offset(frameRight, frameBottom)
                ),
                topLeft = Offset(frameLeft, frameTop),
                size = androidx.compose.ui.geometry.Size(frameWidth, frameHeight),
                cornerRadius = CornerRadius(16f),
                style = Stroke(width = 2.4f)
            )

            if (!scanLocked) {
                val sweepInset = min(12f, frameWidth * 0.16f)
                val sweepY = frameTop + (frameHeight * scanSweep)
                drawLine(
                    color = reticleColor.copy(alpha = 0.18f + glowPulse * 0.15f),
                    start = Offset(frameLeft + sweepInset, sweepY),
                    end = Offset(frameRight - sweepInset, sweepY),
                    strokeWidth = 11f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = reticleColor.copy(alpha = 0.85f),
                    start = Offset(frameLeft + sweepInset, sweepY),
                    end = Offset(frameRight - sweepInset, sweepY),
                    strokeWidth = 2.6f,
                    cap = StrokeCap.Round
                )
            }

            drawCircle(
                color = reticleColor.copy(alpha = 0.5f + glowPulse * 0.4f),
                radius = 3.5f + glowPulse * 3f,
                center = Offset(frameCenterX, frameCenterY)
            )
        }
    }
}
