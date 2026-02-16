package com.cryptohunt.app.ui.screens.onboarding

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.location.LocationManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.cryptohunt.app.ui.testing.TestTags
import com.cryptohunt.app.ui.theme.Background
import com.cryptohunt.app.ui.theme.CardBackground
import com.cryptohunt.app.ui.theme.Danger
import com.cryptohunt.app.ui.theme.Primary
import com.cryptohunt.app.ui.theme.TextPrimary
import com.cryptohunt.app.ui.theme.TextSecondary

private data class RequirementStatus(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val available: Boolean
)

@Composable
fun DeviceReadinessScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var requirements by remember { mutableStateOf(emptyList<RequirementStatus>()) }

    val refreshRequirements = remember(context) {
        { requirements = evaluateRequirements(context) }
    }

    LaunchedEffect(Unit) {
        refreshRequirements()
    }

    DisposableEffect(lifecycleOwner, refreshRequirements) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshRequirements()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val gameReady = requirements.isNotEmpty() && requirements.all { it.available }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(TestTags.DEVICE_READINESS_SCREEN)
            .background(
                Brush.verticalGradient(
                    colors = listOf(Background, Color(0xFF0D0D18), Background)
                )
            )
            .systemBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPrimary
                    )
                }

                Text(
                    text = "Phone Requirements",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )

                IconButton(onClick = refreshRequirements) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Re-check",
                        tint = Primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "To play Chain Assassin, your phone needs the capabilities below.",
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Checked means it is currently available on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(16.dp))

            requirements.forEach { requirement ->
                RequirementRow(requirement = requirement)
                Spacer(modifier = Modifier.height(10.dp))
            }

            if (requirements.isNotEmpty()) {
                val statusColor = if (gameReady) Primary else Danger
                val statusText = if (gameReady) {
                    "This phone is game ready."
                } else {
                    "This phone is not game ready yet."
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.14f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = if (gameReady) "Game Ready" else "Not Ready",
                            style = MaterialTheme.typography.titleMedium,
                            color = statusColor,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )

                        if (!gameReady) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Enable missing items and use the refresh icon.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RequirementRow(requirement: RequirementStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = requirement.available,
                onCheckedChange = null
            )

            Spacer(modifier = Modifier.size(8.dp))

            Icon(
                imageVector = requirement.icon,
                contentDescription = null,
                tint = if (requirement.available) Primary else TextSecondary
            )

            Spacer(modifier = Modifier.size(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = requirement.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Text(
                    text = requirement.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            Text(
                text = if (requirement.available) "OK" else "Missing",
                style = MaterialTheme.typography.labelLarge,
                color = if (requirement.available) Primary else Danger,
                textAlign = TextAlign.End
            )
        }
    }
}

private fun evaluateRequirements(context: Context): List<RequirementStatus> {
    return listOf(
        RequirementStatus(
            title = "Location permission",
            description = "Fine location access is required.",
            icon = Icons.Default.LocationOn,
            available = hasFineLocationPermission(context)
        ),
        RequirementStatus(
            title = "GPS enabled",
            description = "Location services and GPS must be turned on.",
            icon = Icons.Default.GpsFixed,
            available = isGpsEnabled(context)
        ),
        RequirementStatus(
            title = "Bluetooth ready",
            description = "Bluetooth scanning + advertising must be available.",
            icon = Icons.Default.Bluetooth,
            available = isBluetoothReady(context)
        ),
        RequirementStatus(
            title = "Camera access",
            description = "Camera hardware and permission are required.",
            icon = Icons.Default.CameraAlt,
            available = isCameraReady(context)
        ),
        RequirementStatus(
            title = "Internet connected",
            description = "Mobile data or Wi-Fi is needed for live sync.",
            icon = Icons.Default.SignalCellularAlt,
            available = hasValidatedInternet(context)
        )
    )
}

private fun hasFineLocationPermission(context: Context): Boolean {
    return isPermissionGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)
}

private fun isCameraReady(context: Context): Boolean {
    val hasCamera = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    return hasCamera && isPermissionGranted(context, Manifest.permission.CAMERA)
}

private fun isGpsEnabled(context: Context): Boolean {
    val packageManager = context.packageManager
    val hasGps = packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)
    if (!hasGps) return false

    val locationManager = context.getSystemService(LocationManager::class.java) ?: return false
    val locationEnabled = locationManager.isLocationEnabled
    val gpsProviderEnabled = try {
        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    } catch (_: Exception) {
        false
    }

    return locationEnabled && gpsProviderEnabled
}

private fun isBluetoothReady(context: Context): Boolean {
    val packageManager = context.packageManager
    val hasBle = packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    if (!hasBle) return false

    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val adapter = bluetoothManager?.adapter
    val bluetoothEnabled = adapter?.isEnabled == true

    val permissionsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        isPermissionGranted(context, Manifest.permission.BLUETOOTH_SCAN) &&
            isPermissionGranted(context, Manifest.permission.BLUETOOTH_ADVERTISE) &&
            isPermissionGranted(context, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        true
    }

    if (!bluetoothEnabled || !permissionsGranted) return false

    val scannerAvailable = try {
        adapter?.bluetoothLeScanner != null
    } catch (_: SecurityException) {
        false
    }

    val advertiserAvailable = try {
        adapter?.isMultipleAdvertisementSupported == true &&
            adapter.bluetoothLeAdvertiser != null
    } catch (_: SecurityException) {
        false
    }

    return scannerAvailable && advertiserAvailable
}

private fun hasValidatedInternet(context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

private fun isPermissionGranted(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
