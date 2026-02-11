package com.cryptohunt.app.domain.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nearby BLE device discovered during scanning.
 */
data class NearbyDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val lastSeenMs: Long = System.currentTimeMillis()
)

/**
 * State of the BLE scanner exposed to the UI.
 */
data class BleScanState(
    val isScanning: Boolean = false,
    val isBluetoothEnabled: Boolean = false,
    val nearbyDevices: List<NearbyDevice> = emptyList(),
    val errorMessage: String? = null
)

/**
 * Real BLE scanner that discovers nearby Bluetooth LE devices.
 * Used for debugging / proximity awareness on the game screen.
 * Scans continuously while active, pruning stale devices every few seconds.
 */
@Singleton
class BleScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _state = MutableStateFlow(BleScanState())
    val state: StateFlow<BleScanState> = _state.asStateFlow()

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter get() = bluetoothManager?.adapter
    private var scanner: BluetoothLeScanner? = null

    private val devices = mutableMapOf<String, NearbyDevice>()
    private var scanJob: Job? = null
    private var pruneJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        private const val STALE_THRESHOLD_MS = 8_000L // Remove devices not seen for 8s
        private const val PRUNE_INTERVAL_MS = 3_000L  // Prune every 3s
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address ?: return
            @SuppressLint("MissingPermission")
            val name = try { result.device.name } catch (_: SecurityException) { null }
            val device = NearbyDevice(
                address = address,
                name = name,
                rssi = result.rssi,
                lastSeenMs = System.currentTimeMillis()
            )
            synchronized(devices) {
                devices[address] = device
            }
            updateState()
        }

        override fun onScanFailed(errorCode: Int) {
            val msg = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE not supported"
                SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                else -> "Scan failed ($errorCode)"
            }
            _state.value = _state.value.copy(isScanning = false, errorMessage = msg)
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanning() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            _state.value = BleScanState(errorMessage = "Bluetooth not available")
            return
        }
        if (!adapter.isEnabled) {
            _state.value = BleScanState(isBluetoothEnabled = false, errorMessage = "Bluetooth is off")
            return
        }

        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            _state.value = BleScanState(isBluetoothEnabled = true, errorMessage = "BLE scanner unavailable")
            return
        }

        // Clear old devices
        synchronized(devices) { devices.clear() }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        try {
            scanner?.startScan(null, settings, scanCallback)
            _state.value = BleScanState(isScanning = true, isBluetoothEnabled = true)

            // Start pruning stale devices
            pruneJob?.cancel()
            pruneJob = scope.launch {
                while (isActive) {
                    delay(PRUNE_INTERVAL_MS)
                    pruneStaleDevices()
                }
            }
        } catch (e: SecurityException) {
            _state.value = BleScanState(errorMessage = "BLE permission denied")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) { }
        scanner = null
        pruneJob?.cancel()
        pruneJob = null
        synchronized(devices) { devices.clear() }
        _state.value = BleScanState(isScanning = false, isBluetoothEnabled = bluetoothAdapter?.isEnabled == true)
    }

    private fun pruneStaleDevices() {
        val now = System.currentTimeMillis()
        var changed = false
        synchronized(devices) {
            val iter = devices.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if (now - entry.value.lastSeenMs > STALE_THRESHOLD_MS) {
                    iter.remove()
                    changed = true
                }
            }
        }
        if (changed) updateState()
    }

    private fun updateState() {
        val sorted = synchronized(devices) {
            devices.values.sortedByDescending { it.rssi }
        }
        _state.value = _state.value.copy(nearbyDevices = sorted)
    }

    /**
     * Returns a stable device identifier for this device's Bluetooth.
     * On Android 6+, BluetoothAdapter.getAddress() returns "02:00:00:00:00:00" for privacy,
     * so we use ANDROID_ID as a stable per-device identifier prefixed with "BLE:".
     */
    @SuppressLint("HardwareIds")
    fun getLocalBluetoothId(): String? {
        val adapter = bluetoothAdapter ?: return null
        // Try real BLE address first (works on some devices / with BLUETOOTH_CONNECT permission)
        try {
            @SuppressLint("MissingPermission")
            val address = adapter.address
            if (address != null && address != "02:00:00:00:00:00") {
                return address
            }
        } catch (_: SecurityException) { }
        // Fallback: use ANDROID_ID as stable device identifier
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return if (androidId != null) "BLE:$androidId" else null
    }

    fun cleanup() {
        stopScanning()
        scope.cancel()
    }
}
