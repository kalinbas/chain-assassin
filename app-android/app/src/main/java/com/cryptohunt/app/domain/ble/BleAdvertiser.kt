package com.cryptohunt.app.domain.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
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
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter get() = bluetoothManager?.adapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var currentToken: String? = null

    private val _state = MutableStateFlow(
        BleAdvertiseState(isSupported = isAdvertiserSupported())
    )
    val state: StateFlow<BleAdvertiseState> = _state.asStateFlow()

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            _state.value = BleAdvertiseState(
                isAdvertising = true,
                token = currentToken,
                isSupported = isAdvertiserSupported()
            )
        }

        override fun onStartFailure(errorCode: Int) {
            _state.value = BleAdvertiseState(
                isAdvertising = false,
                token = null,
                isSupported = isAdvertiserSupported(),
                errorMessage = "BLE advertise failed ($errorCode)"
            )
        }
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
                _state.value = BleAdvertiseState(
                    isAdvertising = false,
                    token = null,
                    isSupported = isAdvertiserSupported(),
                    errorMessage = "Invalid BLE token"
                )
                return false
            }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _state.value = BleAdvertiseState(
                isAdvertising = false,
                token = null,
                isSupported = isAdvertiserSupported(),
                errorMessage = "Bluetooth is off"
            )
            return false
        }

        if (!adapter.isMultipleAdvertisementSupported) {
            _state.value = BleAdvertiseState(
                isAdvertising = false,
                token = null,
                isSupported = false,
                errorMessage = "BLE advertising unsupported"
            )
            return false
        }

        val bluetoothLeAdvertiser = adapter.bluetoothLeAdvertiser
        if (bluetoothLeAdvertiser == null) {
            _state.value = BleAdvertiseState(
                isAdvertising = false,
                token = null,
                isSupported = false,
                errorMessage = "BLE advertiser unavailable"
            )
            return false
        }

        if (_state.value.isAdvertising && currentToken == normalizedToken) {
            return true
        }

        stopAdvertising()
        advertiser = bluetoothLeAdvertiser
        currentToken = normalizedToken

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(BleTokenProtocol.serviceParcelUuid)
            .addServiceData(
                BleTokenProtocol.serviceParcelUuid,
                BleTokenProtocol.encodeToken(normalizedToken)
            )
            .build()

        return try {
            advertiser?.startAdvertising(settings, data, advertiseCallback)
            true
        } catch (_: SecurityException) {
            currentToken = null
            _state.value = BleAdvertiseState(
                isAdvertising = false,
                token = null,
                isSupported = isAdvertiserSupported(),
                errorMessage = "BLE advertise permission denied"
            )
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) { }
        advertiser = null
        currentToken = null
        _state.value = BleAdvertiseState(
            isAdvertising = false,
            token = null,
            isSupported = isAdvertiserSupported()
        )
    }

    fun cleanup() {
        stopAdvertising()
    }
}

