package com.cryptohunt.app.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * BLE service for proximity verification.
 * In the prototype, BLE operations are mocked since real BLE testing requires
 * two physical devices. The handshake always succeeds after a short delay.
 */
class BleService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val binder = LocalBinder()

    private val _scanEvents = MutableSharedFlow<BleScanEvent>(extraBufferCapacity = 10)
    val scanEvents: SharedFlow<BleScanEvent> = _scanEvents.asSharedFlow()

    private var isAdvertising = false
    private var isScanning = false

    inner class LocalBinder : Binder() {
        fun getService(): BleService = this@BleService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** Start advertising our presence (peripheral mode). */
    fun startAdvertising(gameId: String, playerId: String) {
        if (isAdvertising) return
        isAdvertising = true
        // In production: set up BLE GATT server with custom service UUID
        // For prototype: just track state
    }

    fun stopAdvertising() {
        isAdvertising = false
    }

    /** Scan for a specific target's BLE advertisement. */
    fun startScanForTarget(targetPlayerId: String) {
        if (isScanning) return
        isScanning = true

        // Mock BLE scan: succeed after 1-second delay
        scope.launch {
            delay(1000)
            if (isScanning) {
                _scanEvents.emit(BleScanEvent.TargetFound(targetPlayerId, rssi = -55))
                isScanning = false
            }
        }
    }

    fun stopScanning() {
        isScanning = false
    }

    /**
     * Perform the full proximity verification handshake.
     * Returns true if the target is confirmed within range.
     */
    suspend fun verifyProximity(targetPlayerId: String): Boolean {
        // Mock: always succeed after brief delay
        delay(1200)
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        stopAdvertising()
        stopScanning()
    }
}

sealed class BleScanEvent {
    data class TargetFound(val playerId: String, val rssi: Int) : BleScanEvent()
    data class IncomingScan(val scannerPlayerId: String) : BleScanEvent()
    data object ScanTimeout : BleScanEvent()
}
