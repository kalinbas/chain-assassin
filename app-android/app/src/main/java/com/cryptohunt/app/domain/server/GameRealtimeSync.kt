package com.cryptohunt.app.domain.server

import com.cryptohunt.app.domain.game.GameEngine
import com.cryptohunt.app.domain.ble.BleAdvertiser
import com.cryptohunt.app.domain.ble.BleScanner
import com.cryptohunt.app.domain.location.LocationTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton runtime bridge for server + location side effects.
 *
 * This avoids route-scoped ViewModels processing the same streams multiple times.
 */
@Singleton
class GameRealtimeSync @Inject constructor(
    private val serverClient: GameServerClient,
    private val gameEngine: GameEngine,
    private val locationTracker: LocationTracker,
    private val bleScanner: BleScanner,
    private val bleAdvertiser: BleAdvertiser
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            serverClient.serverMessages.collect { msg ->
                gameEngine.processServerMessage(msg)
            }
        }

        scope.launch {
            locationTracker.state.collect { locState ->
                gameEngine.updateZoneStatus(locState.isInsideZone)
                if (serverClient.connectionState.value == ConnectionState.CONNECTED &&
                    locState.lat != 0.0 &&
                    locState.lng != 0.0
                ) {
                    val scanState = bleScanner.state.value
                    val advertiseState = bleAdvertiser.state.value
                    val bleOperational =
                        scanState.isScanning &&
                            scanState.isBluetoothEnabled &&
                            scanState.errorMessage == null &&
                            advertiseState.isAdvertising &&
                            advertiseState.errorMessage == null
                    serverClient.sendLocation(
                        lat = locState.lat,
                        lng = locState.lng,
                        bleOperational = bleOperational
                    )
                }
            }
        }
    }
}
