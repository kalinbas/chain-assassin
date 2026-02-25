package com.cryptohunt.app.domain.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class BleAdvertiseState(
    val isAdvertising: Boolean = false,
    val token: String? = null,
    val isSupported: Boolean = false,
    val errorMessage: String? = null
)

@Singleton
class BleAdvertiser @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "BleAdvertiser"
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter get() = bluetoothManager?.adapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var activeToken: String? = null
    private var desiredToken: String? = null
    private var receiverRegistered = false

    private val _state = MutableStateFlow(
        BleAdvertiseState(isSupported = isAdvertiserSupported())
    )
    val state: StateFlow<BleAdvertiseState> = _state.asStateFlow()

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            _state.value = BleAdvertiseState(
                isAdvertising = true,
                token = activeToken,
                isSupported = isAdvertiserSupported()
            )
        }

        override fun onStartFailure(errorCode: Int) {
            val reason = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "data too large"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers"
                ADVERTISE_FAILED_ALREADY_STARTED -> "already started"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "internal error"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported"
                else -> "unknown"
            }
            Log.e(TAG, "BLE advertise start failed ($errorCode: $reason)")
            advertiser = null
            activeToken = null
            _state.value = BleAdvertiseState(
                isAdvertising = false,
                token = null,
                isSupported = isAdvertiserSupported(),
                errorMessage = "BLE advertise failed ($errorCode: $reason)"
            )
        }
    }

    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> {
                    if (desiredToken != null) {
                        startAdvertisingInternal()
                    } else {
                        _state.value = BleAdvertiseState(
                            isAdvertising = false,
                            token = null,
                            isSupported = isAdvertiserSupported()
                        )
                    }
                }
                BluetoothAdapter.STATE_OFF -> {
                    stopAdvertisingInternal(clearDesired = false, reason = "Bluetooth is off")
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

    fun isAdvertiserSupported(): Boolean {
        val adapter = bluetoothAdapter ?: return false
        return try {
            adapter.isEnabled &&
                adapter.isMultipleAdvertisementSupported &&
                adapter.bluetoothLeAdvertiser != null
        } catch (_: SecurityException) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising(token: String): Boolean {
        val normalizedToken = BleTokenProtocol.normalizeToken(token)
            ?: run {
                Log.e(TAG, "Cannot start advertising: invalid BLE token")
                desiredToken = null
                _state.value = BleAdvertiseState(
                    isAdvertising = false,
                    token = null,
                    isSupported = isAdvertiserSupported(),
                    errorMessage = "Invalid BLE token"
                )
                return false
            }
        desiredToken = normalizedToken
        return startAdvertisingInternal()
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertisingInternal(): Boolean {
        val normalizedToken = desiredToken ?: return false

        val adapter = bluetoothAdapter
        if (adapter == null) {
            Log.e(TAG, "Cannot start advertising: Bluetooth adapter unavailable")
            _state.value = BleAdvertiseState(
                isAdvertising = false,
                token = null,
                isSupported = isAdvertiserSupported(),
                errorMessage = "Bluetooth not available"
            )
            return false
        }

        val adapterEnabled = try {
            adapter.isEnabled
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot read Bluetooth enabled state (permission denied)", e)
            _state.value = BleAdvertiseState(
                isAdvertising = false,
                token = null,
                isSupported = false,
                errorMessage = "BLE advertise permission denied"
            )
            return false
        }
        if (!adapterEnabled) {
            Log.e(TAG, "Cannot start advertising: Bluetooth is off")
            _state.value = BleAdvertiseState(
                isAdvertising = false,
                token = null,
                isSupported = isAdvertiserSupported(),
                errorMessage = "Bluetooth is off"
            )
            return false
        }

        val multipleAdvertiseSupported = try {
            adapter.isMultipleAdvertisementSupported
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot read BLE advertising capabilities (permission denied)", e)
            _state.value = BleAdvertiseState(
                isAdvertising = false,
                token = null,
                isSupported = false,
                errorMessage = "BLE advertise permission denied"
            )
            return false
        }
        if (!multipleAdvertiseSupported) {
            Log.e(TAG, "Cannot start advertising: multiple advertisement unsupported")
            _state.value = BleAdvertiseState(
                isAdvertising = false,
                token = null,
                isSupported = false,
                errorMessage = "BLE advertising unsupported"
            )
            return false
        }

        val bluetoothLeAdvertiser = try {
            adapter.bluetoothLeAdvertiser
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot obtain BLE advertiser (permission denied)", e)
            _state.value = BleAdvertiseState(
                isAdvertising = false,
                token = null,
                isSupported = false,
                errorMessage = "BLE advertise permission denied"
            )
            return false
        }
        if (bluetoothLeAdvertiser == null) {
            Log.e(TAG, "Cannot start advertising: BLE advertiser unavailable")
            _state.value = BleAdvertiseState(
                isAdvertising = false,
                token = null,
                isSupported = false,
                errorMessage = "BLE advertiser unavailable"
            )
            return false
        }

        if (_state.value.isAdvertising && activeToken == normalizedToken) {
            return true
        }

        stopAdvertisingInternal(clearDesired = false)
        advertiser = bluetoothLeAdvertiser
        activeToken = normalizedToken

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .build()

        val dataBuilder = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(BleTokenProtocol.serviceParcelUuid)
            // Keep raw payload deterministic: manufacturer marker + encoded player token bytes.
            .addManufacturerData(
                BleTokenProtocol.manufacturerId,
                BleTokenProtocol.encodeToken(normalizedToken)
            )
        val data = dataBuilder.build()

        return try {
            advertiser?.startAdvertising(settings, data, advertiseCallback)
            Log.i(TAG, "BLE advertising requested (token=$normalizedToken)")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "BLE advertising start blocked by permission", e)
            activeToken = null
            _state.value = BleAdvertiseState(
                isAdvertising = false,
                token = null,
                isSupported = isAdvertiserSupported(),
                errorMessage = "BLE advertise permission denied"
            )
            false
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "BLE advertising start failed due to invalid advertise payload", e)
            activeToken = null
            _state.value = BleAdvertiseState(
                isAdvertising = false,
                token = null,
                isSupported = isAdvertiserSupported(),
                errorMessage = "BLE advertise payload invalid"
            )
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        stopAdvertisingInternal(clearDesired = true)
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertisingInternal(clearDesired: Boolean, reason: String? = null) {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            Log.w(TAG, "BLE stopAdvertising failed", e)
            _state.value = BleAdvertiseState(
                isAdvertising = false,
                token = null,
                isSupported = isAdvertiserSupported(),
                errorMessage = "Failed to stop BLE advertising: ${e.message}"
            )
            return
        }
        advertiser = null
        activeToken = null
        if (clearDesired) {
            desiredToken = null
        }
        Log.i(TAG, "BLE advertising stopped")
        _state.value = BleAdvertiseState(
            isAdvertising = false,
            token = null,
            isSupported = isAdvertiserSupported(),
            errorMessage = reason
        )
    }

    fun cleanup() {
        stopAdvertising()
        if (receiverRegistered) {
            context.unregisterReceiver(adapterStateReceiver)
            receiverRegistered = false
        }
    }
}
