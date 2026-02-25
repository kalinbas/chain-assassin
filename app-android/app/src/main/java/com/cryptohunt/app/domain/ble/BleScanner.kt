package com.cryptohunt.app.domain.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nearby BLE device discovered during scanning.
 */
data class NearbyDevice(
    val address: String,
    val token: String? = null,
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
    private var shouldScan = false
    private var receiverRegistered = false

    private val devices = mutableMapOf<String, NearbyDevice>()
    private var pruneJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        private const val TAG = "BleScanner"
        private const val STALE_THRESHOLD_MS = 8_000L // Remove devices not seen for 8s
        private const val PRUNE_INTERVAL_MS = 3_000L  // Prune every 3s
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = try {
                result.device.address
            } catch (e: SecurityException) {
                Log.e(TAG, "Failed to read scan result device address", e)
                _state.value = _state.value.copy(errorMessage = "Failed to read BLE scan result (permission denied)")
                null
            } ?: return
            @SuppressLint("MissingPermission")
            val name = try { result.device.name } catch (_: SecurityException) { null }
            val scanRecord = result.scanRecord
            val token = BleTokenProtocol.decodeToken(
                scanRecord?.getManufacturerSpecificData(BleTokenProtocol.manufacturerId)
            )
            val device = NearbyDevice(
                address = address,
                token = token,
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
            Log.e(TAG, "BLE scan failed: $msg")
            scanner = null
            pruneJob?.cancel()
            pruneJob = null
            synchronized(devices) { devices.clear() }
            _state.value = BleScanState(
                isScanning = false,
                isBluetoothEnabled = isBluetoothEnabled(),
                errorMessage = msg
            )
            if (shouldScan && isBluetoothEnabled()) {
                startScanningInternal()
            }
        }
    }

    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> {
                    if (shouldScan) {
                        startScanningInternal()
                    } else {
                        _state.value = _state.value.copy(
                            isBluetoothEnabled = true,
                            errorMessage = null
                        )
                    }
                }
                BluetoothAdapter.STATE_OFF -> {
                    stopScanningInternal(clearDesired = false, reason = "Bluetooth is off")
                }
            }
        }
    }

    init {
        registerAdapterStateReceiver()
    }

    private fun registerAdapterStateReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(adapterStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(adapterStateReceiver, filter)
        }
        receiverRegistered = true
    }

    @SuppressLint("MissingPermission")
    fun startScanning() {
        shouldScan = true
        startScanningInternal()
    }

    @SuppressLint("MissingPermission")
    private fun startScanningInternal() {
        if (_state.value.isScanning) return
        val adapter = bluetoothAdapter
        if (adapter == null) {
            Log.e(TAG, "Cannot start BLE scan: Bluetooth adapter unavailable")
            _state.value = BleScanState(errorMessage = "Bluetooth not available")
            return
        }

        val adapterEnabled = try {
            adapter.isEnabled
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot read Bluetooth enabled state (permission denied)", e)
            _state.value = BleScanState(errorMessage = "BLE permission denied")
            return
        }
        if (!adapterEnabled) {
            Log.e(TAG, "Cannot start BLE scan: Bluetooth is off")
            _state.value = BleScanState(isBluetoothEnabled = false, errorMessage = "Bluetooth is off")
            return
        }

        scanner = try {
            adapter.bluetoothLeScanner
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot obtain BLE scanner (permission denied)", e)
            _state.value = BleScanState(errorMessage = "BLE permission denied")
            return
        }
        if (scanner == null) {
            Log.e(TAG, "Cannot start BLE scan: scanner unavailable")
            _state.value = BleScanState(isBluetoothEnabled = true, errorMessage = "BLE scanner unavailable")
            return
        }

        // Clear old devices
        synchronized(devices) { devices.clear() }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(BleTokenProtocol.serviceParcelUuid)
                .build()
        )

        try {
            scanner?.startScan(filters, settings, scanCallback)
            Log.i(TAG, "BLE scan started")
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
            Log.e(TAG, "BLE scan start blocked by permission", e)
            _state.value = BleScanState(errorMessage = "BLE permission denied")
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "BLE scan start failed due to invalid filter/settings", e)
            _state.value = BleScanState(errorMessage = "BLE scan configuration error")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        stopScanningInternal(clearDesired = true)
    }

    @SuppressLint("MissingPermission")
    private fun stopScanningInternal(clearDesired: Boolean, reason: String? = null) {
        if (clearDesired) {
            shouldScan = false
        }
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "BLE stopScan failed", e)
            _state.value = BleScanState(
                isScanning = false,
                isBluetoothEnabled = isBluetoothEnabled(),
                errorMessage = "Failed to stop BLE scan: ${e.message}"
            )
            scanner = null
            pruneJob?.cancel()
            pruneJob = null
            synchronized(devices) { devices.clear() }
            return
        }
        scanner = null
        pruneJob?.cancel()
        pruneJob = null
        synchronized(devices) { devices.clear() }
        Log.i(TAG, "BLE scan stopped")
        _state.value = BleScanState(
            isScanning = false,
            isBluetoothEnabled = isBluetoothEnabled(),
            errorMessage = reason
        )
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
     * Returns this device Bluetooth adapter address (MAC) when available.
     * No opaque device-id fallback is used, because server proximity checks
     * compare against scanned nearby Bluetooth addresses.
     */
    fun getLocalBluetoothAddress(): String? {
        val adapter = bluetoothAdapter ?: return null
        try {
            @SuppressLint("MissingPermission")
            val address = adapter.address
            if (address != null && address != "02:00:00:00:00:00") {
                return address
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Failed to read local Bluetooth adapter address", e)
        }
        return null
    }

    fun getNearbyTokens(): List<String> {
        return synchronized(devices) {
            devices.values.mapNotNull { BleTokenProtocol.normalizeToken(it.token) }.distinct()
        }
    }

    private fun isBluetoothEnabled(): Boolean {
        val adapter = bluetoothAdapter ?: return false
        return try {
            adapter.isEnabled
        } catch (_: SecurityException) {
            false
        }
    }

    fun cleanup() {
        stopScanning()
        if (receiverRegistered) {
            context.unregisterReceiver(adapterStateReceiver)
            receiverRegistered = false
        }
        scope.cancel()
    }
}
