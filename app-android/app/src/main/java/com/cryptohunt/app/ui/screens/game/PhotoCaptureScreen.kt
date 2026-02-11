package com.cryptohunt.app.ui.screens.game

import android.graphics.BitmapFactory
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.cryptohunt.app.ui.theme.*
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun PhotoCaptureScreen(
    onBack: () -> Unit,
    onPhotoTaken: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var capturedFile by remember { mutableStateOf<File?>(null) }
    var caption by remember { mutableStateOf("") }
    var uploading by remember { mutableStateOf(false) }
    var uploaded by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val imageCapture = remember { ImageCapture.Builder().build() }

    // Auto-navigate back after successful upload
    LaunchedEffect(uploaded) {
        if (uploaded) {
            delay(1500)
            onPhotoTaken()
        }
    }

    // Reset error
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            delay(2500)
            errorMessage = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (capturedFile == null) {
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

                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageCapture
                            )
                        } catch (_: Exception) { }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

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
                    "Take Photo",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            // Shutter button
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 60.dp)
            ) {
                Button(
                    onClick = {
                        val file = File(context.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
                        val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()
                        imageCapture.takePicture(
                            outputOptions,
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                    capturedFile = file
                                }
                                override fun onError(exc: ImageCaptureException) {
                                    errorMessage = "Capture failed"
                                }
                            }
                        )
                    },
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(Color.White, CircleShape)
                    )
                }
            }
        } else {
            // Photo preview + caption
            val bitmap = remember(capturedFile) {
                BitmapFactory.decodeFile(capturedFile!!.absolutePath)?.asImageBitmap()
            }

            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Captured photo",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            // Overlay controls
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
            ) {
                // Top bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        capturedFile?.delete()
                        capturedFile = null
                        caption = ""
                    }) {
                        Icon(Icons.Default.Close, "Retake", tint = Color.White)
                    }

                    Text(
                        "Preview",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.weight(1f))

                // Caption + send
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    OutlinedTextField(
                        value = caption,
                        onValueChange = { if (it.length <= 200) caption = it },
                        label = { Text("Caption (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = TextDim,
                            focusedLabelColor = Primary,
                            unfocusedLabelColor = TextSecondary
                        )
                    )

                    Spacer(Modifier.height(12.dp))

                    when {
                        uploaded -> {
                            Text(
                                "Uploaded!",
                                style = MaterialTheme.typography.titleMedium,
                                color = Primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        uploading -> {
                            CircularProgressIndicator(
                                color = Primary,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        else -> {
                            Button(
                                onClick = {
                                    // Mock upload (prototype — no real server connection yet)
                                    uploading = true
                                    // Simulate upload delay
                                    // In production: HTTP multipart POST to server
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Primary)
                            ) {
                                Icon(Icons.Default.Send, "Upload", tint = Background)
                                Spacer(Modifier.width(8.dp))
                                Text("Upload Photo", color = Background, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // Error message
        if (errorMessage != null) {
            Text(
                errorMessage!!,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 120.dp),
                style = MaterialTheme.typography.titleMedium,
                color = Danger,
                fontWeight = FontWeight.Bold
            )
        }
    }

    // Simulate upload completion (prototype)
    LaunchedEffect(uploading) {
        if (uploading) {
            delay(1500)
            uploading = false
            uploaded = true
        }
    }
}
