package com.cryptohunt.app.ui.viewmodel

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptohunt.app.domain.ble.BleScanState
import com.cryptohunt.app.domain.ble.BleScanner
import com.cryptohunt.app.domain.model.LocationState
import com.cryptohunt.app.domain.location.LocationTracker
import com.cryptohunt.app.domain.server.GameServerClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

enum class DebugScanMode(val wireValue: String, val label: String) {
    CHECKIN("checkin", "Debug Scan"),
    HEARTBEAT("heartbeat", "Heartbeat"),
    KILL("kill", "Kill")
}

data class ScanDebugUiState(
    val isSubmitting: Boolean = false,
    val scanLocked: Boolean = false,
    val resultPresented: Boolean = false,
    val lastScanMode: DebugScanMode? = null,
    val lastScannedCode: String? = null,
    val sentPayloadPretty: String? = null,
    val serverResponsePretty: String? = null,
    val error: String? = null
)

@HiltViewModel
class ScanDebugViewModel @Inject constructor(
    private val locationTracker: LocationTracker,
    private val bleScanner: BleScanner,
    private val serverClient: GameServerClient
) : ViewModel() {

    val locationState: StateFlow<LocationState> = locationTracker.state
    val bleState: StateFlow<BleScanState> = bleScanner.state

    private val _uiState = MutableStateFlow(ScanDebugUiState())
    val uiState: StateFlow<ScanDebugUiState> = _uiState.asStateFlow()

    private var locationTrackingEnabled = false
    private var bleScanningEnabled = false

    fun syncSensors(locationPermissionGranted: Boolean, bluetoothPermissionGranted: Boolean) {
        if (locationPermissionGranted && !locationTrackingEnabled) {
            try {
                locationTracker.startTracking()
                locationTrackingEnabled = true
            } catch (_: SecurityException) {
                locationTrackingEnabled = false
            }
        } else if (!locationPermissionGranted && locationTrackingEnabled) {
            locationTracker.stopTracking()
            locationTrackingEnabled = false
        }

        if (bluetoothPermissionGranted && !bleScanningEnabled) {
            bleScanner.startScanning()
            bleScanningEnabled = true
        } else if (!bluetoothPermissionGranted && bleScanningEnabled) {
            bleScanner.stopScanning()
            bleScanningEnabled = false
        }
    }

    fun resetScan() {
        _uiState.value = ScanDebugUiState()
    }

    fun markResultPresented() {
        _uiState.update { it.copy(resultPresented = true) }
    }

    fun submitScannedCode(
        mode: DebugScanMode,
        scannedCode: String,
        cameraPermissionGranted: Boolean,
        locationPermissionGranted: Boolean,
        bluetoothPermissionGranted: Boolean
    ) {
        val snapshot = _uiState.value
        if (snapshot.isSubmitting || snapshot.scanLocked) return

        val location = locationState.value
        val ble = bleState.value
        val bleAddresses = ble.nearbyDevices.map { it.address }.distinct()
        val payload = buildPayload(
            mode = mode,
            scannedCode = scannedCode,
            location = location,
            ble = ble,
            bleAddresses = bleAddresses,
            cameraPermissionGranted = cameraPermissionGranted,
            locationPermissionGranted = locationPermissionGranted,
            bluetoothPermissionGranted = bluetoothPermissionGranted
        )

        val payloadPretty = payload.toString(2)

        _uiState.update {
            it.copy(
                isSubmitting = true,
                scanLocked = true,
                resultPresented = false,
                lastScanMode = mode,
                lastScannedCode = scannedCode,
                sentPayloadPretty = payloadPretty,
                serverResponsePretty = null,
                error = null
            )
        }

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                serverClient.submitScanDebugEcho(payload)
            }

            val responsePretty = prettifyJson(result.responseBody)
            _uiState.update {
                it.copy(
                    isSubmitting = false,
                    serverResponsePretty = responsePretty,
                    error = if (result.success) null else (result.error ?: "Debug endpoint error")
                )
            }
        }
    }

    fun stopSensors() {
        if (locationTrackingEnabled) {
            locationTracker.stopTracking()
            locationTrackingEnabled = false
        }
        if (bleScanningEnabled) {
            bleScanner.stopScanning()
            bleScanningEnabled = false
        }
    }

    override fun onCleared() {
        stopSensors()
        super.onCleared()
    }

    private fun buildPayload(
        mode: DebugScanMode,
        scannedCode: String,
        location: LocationState,
        ble: BleScanState,
        bleAddresses: List<String>,
        cameraPermissionGranted: Boolean,
        locationPermissionGranted: Boolean,
        bluetoothPermissionGranted: Boolean
    ): JSONObject {
        val locationJson = JSONObject().apply {
            put("lat", if (location.isTracking) location.lat else JSONObject.NULL)
            put("lng", if (location.isTracking) location.lng else JSONObject.NULL)
            put("accuracy", if (location.isTracking) location.accuracy else JSONObject.NULL)
            put("isTracking", location.isTracking)
            put("gpsLostSeconds", location.gpsLostSeconds)
            put("isInsideZone", location.isInsideZone)
            put("distanceToZoneEdge", if (location.isTracking) location.distanceToZoneEdge else JSONObject.NULL)
        }

        val nearbyBluetooth = JSONArray().apply {
            ble.nearbyDevices.forEach { device ->
                put(
                    JSONObject().apply {
                        put("address", device.address)
                        put("name", device.name ?: JSONObject.NULL)
                        put("rssi", device.rssi)
                        put("lastSeenMs", device.lastSeenMs)
                    }
                )
            }
        }

        val permissionsJson = JSONObject().apply {
            put("camera", cameraPermissionGranted)
            put("location", locationPermissionGranted)
            put("bluetooth", bluetoothPermissionGranted)
        }

        val deviceJson = JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER ?: JSONObject.NULL)
            put("model", Build.MODEL ?: JSONObject.NULL)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("release", Build.VERSION.RELEASE ?: JSONObject.NULL)
        }

        return JSONObject().apply {
            put("scanType", mode.wireValue)
            put("scannedCode", scannedCode)
            put("capturedAtMs", System.currentTimeMillis())
            put("location", locationJson)
            put("bleNearbyAddresses", JSONArray(bleAddresses))
            put("nearbyBluetooth", nearbyBluetooth)
            put("bluetoothId", bleScanner.getLocalBluetoothId() ?: JSONObject.NULL)
            put("permissions", permissionsJson)
            put("device", deviceJson)
        }
    }

    private fun prettifyJson(raw: String?): String? {
        if (raw.isNullOrBlank()) return raw
        return try {
            JSONObject(raw).toString(2)
        } catch (_: Exception) {
            try {
                JSONArray(raw).toString(2)
            } catch (_: Exception) {
                raw
            }
        }
    }
}
