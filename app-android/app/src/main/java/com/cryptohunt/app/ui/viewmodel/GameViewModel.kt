package com.cryptohunt.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptohunt.app.domain.game.GameEngine
import com.cryptohunt.app.domain.game.GameEvent
import com.cryptohunt.app.domain.game.KillResult
import com.cryptohunt.app.domain.location.LocationTracker
import com.cryptohunt.app.domain.model.CheckInResult
import com.cryptohunt.app.domain.model.GamePhase
import com.cryptohunt.app.domain.model.GameState
import com.cryptohunt.app.domain.model.LocationState
import com.cryptohunt.app.domain.wallet.WalletManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GameViewModel @Inject constructor(
    private val gameEngine: GameEngine,
    private val locationTracker: LocationTracker,
    private val walletManager: WalletManager
) : ViewModel() {

    val gameState: StateFlow<GameState?> = gameEngine.state
    val locationState: StateFlow<LocationState> = locationTracker.state
    val events: SharedFlow<GameEvent> = gameEngine.events

    private val _uiEvents = MutableSharedFlow<UiEvent>(extraBufferCapacity = 20)
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    init {
        // Sync location zone status with game engine
        viewModelScope.launch {
            locationTracker.state.collect { locState ->
                gameEngine.updateZoneStatus(locState.isInsideZone)
                // GPS lost for 60+ seconds → disqualify
                if (locState.gpsLostSeconds >= 60) {
                    gameEngine.eliminateForGpsLoss()
                }
            }
        }

        // Forward game events to UI events
        viewModelScope.launch {
            gameEngine.events.collect { event ->
                when (event) {
                    is GameEvent.KillConfirmed -> _uiEvents.emit(UiEvent.ShowKillConfirmed(event.event.targetNumber))
                    is GameEvent.Eliminated -> _uiEvents.emit(UiEvent.NavigateToEliminated)
                    is GameEvent.GpsDisqualified -> _uiEvents.emit(UiEvent.NavigateToEliminated)
                    is GameEvent.OutOfZoneEliminated -> _uiEvents.emit(UiEvent.NavigateToEliminated)
                    is GameEvent.GameEnded -> _uiEvents.emit(UiEvent.NavigateToResults)
                    is GameEvent.TargetReassigned -> _uiEvents.emit(UiEvent.ShowNewTarget)
                    is GameEvent.ZoneShrink -> _uiEvents.emit(UiEvent.ShowZoneShrinkWarning)
                    is GameEvent.CheckInStarted -> _uiEvents.emit(UiEvent.NavigateToCheckIn)
                    is GameEvent.GameStarted -> _uiEvents.emit(UiEvent.NavigateToMainGame)
                    is GameEvent.CheckInVerified -> _uiEvents.emit(UiEvent.CheckInVerified)
                    else -> {}
                }
            }
        }
    }

    fun startLocationTracking() {
        val config = gameState.value?.config ?: return
        locationTracker.setZone(config.zoneCenterLat, config.zoneCenterLng, gameState.value?.currentZoneRadius ?: config.initialRadiusMeters)
        locationTracker.startTracking()
    }

    fun stopLocationTracking() {
        locationTracker.stopTracking()
    }

    fun processKill(qrPayload: String): KillResult {
        return gameEngine.processKill(qrPayload)
    }

    fun processCheckInScan(qrPayload: String): CheckInResult {
        return gameEngine.processCheckInScan(qrPayload)
    }

    fun setSpectatorMode() {
        gameEngine.setSpectatorMode()
    }

    fun useItem(item: IntelItem): ItemResult {
        val state = gameState.value ?: return ItemResult.Failed("No active game")
        if (item.id in state.usedItems) return ItemResult.AlreadyUsed
        gameEngine.markItemUsed(item.id)
        return when (item.id) {
            "ping_target" -> {
                val zone = listOf("NW quadrant", "NE quadrant", "SE quadrant", "SW quadrant", "center area").random()
                ItemResult.Success("Target spotted in the $zone!")
            }
            "ping_hunter" -> {
                val dist = listOf("very close", "nearby", "far away", "across the zone").random()
                ItemResult.Success("Your hunter is $dist!")
            }
            "ghost_mode" -> {
                gameEngine.activateGhostMode()
                ItemResult.Success("Ghost Mode ON — invisible for 2 minutes!")
            }
            "decoy_ping" -> {
                ItemResult.Success("Decoy sent! Your hunter sees a fake location.")
            }
            "emp_blast" -> {
                ItemResult.Success("EMP fired! Target's map disabled for 30s.")
            }
            else -> ItemResult.Failed("Unknown item")
        }
    }

    // Debug controls
    fun debugTriggerElimination() = gameEngine.debugTriggerElimination()
    fun debugTriggerZoneShrink() = gameEngine.debugTriggerZoneShrink()
    fun debugSetPlayersRemaining(count: Int) = gameEngine.debugSetPlayersRemaining(count)
    fun debugSkipToEndgame() = gameEngine.debugSkipToEndgame()
    fun debugScanTarget() = gameEngine.debugScanTarget()
    fun debugVerifyCheckIn() = gameEngine.debugVerifyCheckIn()
    fun debugStartGame() = gameEngine.startGame()

    override fun onCleared() {
        super.onCleared()
        locationTracker.stopTracking()
    }
}

sealed class UiEvent {
    data class ShowKillConfirmed(val targetNumber: Int) : UiEvent()
    data object NavigateToEliminated : UiEvent()
    data object NavigateToResults : UiEvent()
    data object ShowNewTarget : UiEvent()
    data object ShowZoneShrinkWarning : UiEvent()
    data object NavigateToCheckIn : UiEvent()
    data object NavigateToMainGame : UiEvent()
    data object CheckInVerified : UiEvent()
}

data class IntelItem(
    val id: String,
    val title: String,
    val description: String,
    val iconName: String
)

val INTEL_ITEMS = listOf(
    IntelItem("ping_target", "Ping Target", "Reveal your target's approximate zone for 30s", "gps_fixed"),
    IntelItem("ping_hunter", "Ping Hunter", "Find out how close your hunter is to you", "person_search"),
    IntelItem("ghost_mode", "Ghost Mode", "Become invisible to your hunter for 2 minutes", "visibility_off"),
    IntelItem("decoy_ping", "Decoy Ping", "Send a fake location to mislead your hunter", "wrong_location"),
    IntelItem("emp_blast", "EMP Blast", "Disable your target's map & intel for 30 seconds", "flash_on")
)

sealed class ItemResult {
    data class Success(val message: String) : ItemResult()
    data object AlreadyUsed : ItemResult()
    data class Failed(val reason: String) : ItemResult()
}
