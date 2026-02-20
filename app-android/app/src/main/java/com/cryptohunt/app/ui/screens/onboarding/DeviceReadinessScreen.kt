package com.cryptohunt.app.ui.screens.onboarding

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.location.LocationManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
    val type: RequirementType,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val available: Boolean,
    val actionLabel: String? = null,
    val action: RequirementAction? = null
)

private enum class RequirementType {
    LOCATION_PERMISSION,
    GPS_ENABLED,
    BLUETOOTH_READY,
    CAMERA_ACCESS,
    INTERNET_CONNECTED
}

private enum class RequirementAction {
    REQUEST_LOCATION_PERMISSION,
    REQUEST_BLUETOOTH_PERMISSIONS,
    REQUEST_CAMERA_PERMISSION,
    ENABLE_BLUETOOTH,
    ENABLE_GPS,
    OPEN_BLUETOOTH_SETTINGS,
    OPEN_DEVICE_SETTINGS,
    OPEN_NETWORK_SETTINGS
}

fun isDeviceGameReady(context: Context): Boolean {
    val requirements = evaluateRequirements(context)
    return requirements.isNotEmpty() && requirements.all { it.available }
}

@Composable
fun DeviceReadinessScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var requirements by remember { mutableStateOf(emptyList<RequirementStatus>()) }

    val refreshRequirements = remember(context) {
        { requirements = evaluateRequirements(context) }
    }
    val requiredPermissions = remember {
        buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
    }
    val requestPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshRequirements()
    }
    val requestSinglePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        refreshRequirements()
    }
    val requestEnableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        refreshRequirements()
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
    val missingPermissions = requiredPermissions.filterNot { permission ->
        isPermissionGranted(context, permission)
    }
    val openSettings: (String) -> Unit = { action ->
        runCatching {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    val runRequirementAction: (RequirementAction) -> Unit = { action ->
        when (action) {
            RequirementAction.REQUEST_LOCATION_PERMISSION -> {
                requestSinglePermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            RequirementAction.REQUEST_BLUETOOTH_PERMISSIONS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    requestPermissionsLauncher.launch(
                        arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_ADVERTISE,
                            Manifest.permission.BLUETOOTH_CONNECT
                        )
                    )
                } else {
                    requestSinglePermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }
            RequirementAction.REQUEST_CAMERA_PERMISSION -> {
                requestSinglePermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            RequirementAction.ENABLE_BLUETOOTH -> {
                requestEnableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
            RequirementAction.ENABLE_GPS -> openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            RequirementAction.OPEN_BLUETOOTH_SETTINGS -> openSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
            RequirementAction.OPEN_DEVICE_SETTINGS -> openSettings(Settings.ACTION_SETTINGS)
            RequirementAction.OPEN_NETWORK_SETTINGS -> openSettings(Settings.ACTION_WIRELESS_SETTINGS)
        }
    }

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
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
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
                RequirementRow(
                    requirement = requirement,
                    onActionClick = {
                        requirement.action?.let(runRequirementAction)
                    }
                )
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

                            if (missingPermissions.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        requestPermissionsLauncher.launch(missingPermissions.toTypedArray())
                                    }
                                ) {
                                    Text("Grant Missing Permissions")
                                }
                            }
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
private fun RequirementRow(
    requirement: RequirementStatus,
    onActionClick: () -> Unit
) {
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
                if (!requirement.available && requirement.actionLabel != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = onActionClick) {
                        Text(requirement.actionLabel)
                    }
                }
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
    val locationPermissionGranted = hasFineLocationPermission(context)
    val gpsEnabled = isGpsEnabled(context)
    val bluetoothRequirement = evaluateBluetoothRequirement(context)
    val cameraHardwareAvailable = hasCameraHardware(context)
    val cameraPermissionGranted = hasCameraPermission(context)
    val internetConnected = hasValidatedInternet(context)

    val gpsAction = if (!gpsEnabled) {
        if (!locationPermissionGranted) RequirementAction.REQUEST_LOCATION_PERMISSION
        else RequirementAction.ENABLE_GPS
    } else {
        null
    }
    val gpsActionLabel = if (!gpsEnabled) {
        if (!locationPermissionGranted) "Grant Location Permission"
        else "Enable GPS"
    } else {
        null
    }
    val cameraAction = if (!cameraHardwareAvailable) {
        RequirementAction.OPEN_DEVICE_SETTINGS
    } else if (!cameraPermissionGranted) {
        RequirementAction.REQUEST_CAMERA_PERMISSION
    } else {
        null
    }
    val cameraActionLabel = when {
        !cameraHardwareAvailable -> "Open Device Settings"
        !cameraPermissionGranted -> "Grant Camera Permission"
        else -> null
    }

    return listOf(
        RequirementStatus(
            type = RequirementType.LOCATION_PERMISSION,
            title = "Location permission",
            description = "Fine location access is required.",
            icon = Icons.Default.LocationOn,
            available = locationPermissionGranted,
            actionLabel = if (locationPermissionGranted) null else "Grant Location Permission",
            action = if (locationPermissionGranted) null else RequirementAction.REQUEST_LOCATION_PERMISSION
        ),
        RequirementStatus(
            type = RequirementType.GPS_ENABLED,
            title = "GPS enabled",
            description = "Location services and GPS must be turned on.",
            icon = Icons.Default.GpsFixed,
            available = gpsEnabled,
            actionLabel = gpsActionLabel,
            action = gpsAction
        ),
        RequirementStatus(
            type = RequirementType.BLUETOOTH_READY,
            title = "Bluetooth ready",
            description = "Bluetooth scanning + advertising must be available.",
            icon = Icons.Default.Bluetooth,
            available = bluetoothRequirement.available,
            actionLabel = bluetoothRequirement.actionLabel,
            action = bluetoothRequirement.action
        ),
        RequirementStatus(
            type = RequirementType.CAMERA_ACCESS,
            title = "Camera access",
            description = "Camera hardware and permission are required.",
            icon = Icons.Default.CameraAlt,
            available = cameraHardwareAvailable && cameraPermissionGranted,
            actionLabel = cameraActionLabel,
            action = cameraAction
        ),
        RequirementStatus(
            type = RequirementType.INTERNET_CONNECTED,
            title = "Internet connected",
            description = "Mobile data or Wi-Fi is needed for live sync.",
            icon = Icons.Default.SignalCellularAlt,
            available = internetConnected,
            actionLabel = if (internetConnected) null else "Open Network Settings",
            action = if (internetConnected) null else RequirementAction.OPEN_NETWORK_SETTINGS
        )
    )
}

private fun hasFineLocationPermission(context: Context): Boolean {
    return isPermissionGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)
}

private fun hasCameraHardware(context: Context): Boolean {
    return context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
}

private fun hasCameraPermission(context: Context): Boolean {
    return isPermissionGranted(context, Manifest.permission.CAMERA)
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

private data class BluetoothRequirement(
    val available: Boolean,
    val actionLabel: String? = null,
    val action: RequirementAction? = null
)

private fun evaluateBluetoothRequirement(context: Context): BluetoothRequirement {
    val packageManager = context.packageManager
    val hasBle = packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    if (!hasBle) {
        return BluetoothRequirement(
            available = false,
            actionLabel = "Open Bluetooth Settings",
            action = RequirementAction.OPEN_BLUETOOTH_SETTINGS
        )
    }

    if (!hasBluetoothPermissions(context)) {
        return BluetoothRequirement(
            available = false,
            actionLabel = "Grant Bluetooth Permission",
            action = RequirementAction.REQUEST_BLUETOOTH_PERMISSIONS
        )
    }

    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val adapter = bluetoothManager?.adapter
    if (!isBluetoothEnabled(adapter)) {
        return BluetoothRequirement(
            available = false,
            actionLabel = "Enable Bluetooth",
            action = RequirementAction.ENABLE_BLUETOOTH
        )
    }

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

    if (!scannerAvailable || !advertiserAvailable) {
        return BluetoothRequirement(
            available = false,
            actionLabel = "Open Bluetooth Settings",
            action = RequirementAction.OPEN_BLUETOOTH_SETTINGS
        )
    }

    return BluetoothRequirement(available = true)
}

private fun hasBluetoothPermissions(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        isPermissionGranted(context, Manifest.permission.BLUETOOTH_SCAN) &&
            isPermissionGranted(context, Manifest.permission.BLUETOOTH_ADVERTISE) &&
            isPermissionGranted(context, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        hasFineLocationPermission(context)
    }
}

private fun isBluetoothEnabled(adapter: BluetoothAdapter?): Boolean {
    if (adapter == null) return false
    return try {
        adapter.isEnabled
    } catch (_: SecurityException) {
        false
    }
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
