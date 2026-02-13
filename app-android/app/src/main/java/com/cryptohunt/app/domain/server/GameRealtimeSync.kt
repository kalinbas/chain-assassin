package com.cryptohunt.app.domain.server

import com.cryptohunt.app.domain.game.GameEngine
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
    private val locationTracker: LocationTracker
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
                    serverClient.sendLocation(locState.lat, locState.lng)
                }
            }
        }
    }
}
