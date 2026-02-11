package com.cryptohunt.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptohunt.app.domain.game.GameEngine
import com.cryptohunt.app.domain.game.GameEvent
import com.cryptohunt.app.domain.game.KillResult
import com.cryptohunt.app.domain.location.LocationTracker
import com.cryptohunt.app.domain.model.CheckInResult
import com.cryptohunt.app.domain.model.HeartbeatResult
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
                    is GameEvent.PregameStarted -> _uiEvents.emit(UiEvent.NavigateToPregame)
                    is GameEvent.GameStarted -> _uiEvents.emit(UiEvent.NavigateToMainGame)
                    is GameEvent.CheckInVerified -> _uiEvents.emit(UiEvent.CheckInVerified)
                    is GameEvent.HeartbeatEliminated -> _uiEvents.emit(UiEvent.NavigateToEliminated)
                    is GameEvent.NoCheckInEliminated -> _uiEvents.emit(UiEvent.NavigateToEliminated)
                    is GameEvent.GameCancelled -> _uiEvents.emit(UiEvent.GameCancelled)
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

    fun processHeartbeatScan(qrPayload: String): HeartbeatResult {
        return gameEngine.processHeartbeatScan(qrPayload)
    }

    fun setSpectatorMode() {
        gameEngine.setSpectatorMode()
    }

    companion object {
        const val ITEM_COOLDOWN_MS = 5 * 60 * 1000L // 5 minutes
    }

    fun getItemCooldownRemaining(itemId: String): Long {
        val state = gameState.value ?: return 0
        val lastUsed = state.itemCooldowns[itemId] ?: return 0
        val remaining = (lastUsed + ITEM_COOLDOWN_MS) - System.currentTimeMillis()
        return remaining.coerceAtLeast(0)
    }

    fun useItem(item: IntelItem): ItemResult {
        val state = gameState.value ?: return ItemResult.Failed("No active game")
        val cooldownRemaining = getItemCooldownRemaining(item.id)
        if (cooldownRemaining > 0) return ItemResult.OnCooldown(cooldownRemaining)
        val ping = gameEngine.useItemWithPing(item.id) ?: return ItemResult.Failed("Failed")
        return when (item.id) {
            "ping_target" -> ItemResult.Success("Target ping activated!")
            "ping_hunter" -> ItemResult.Success("Hunter ping activated!")
            else -> ItemResult.Success("Ping activated!")
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
    data object NavigateToPregame : UiEvent()
    data object NavigateToMainGame : UiEvent()
    data object CheckInVerified : UiEvent()
    data object GameCancelled : UiEvent()
}

data class IntelItem(
    val id: String,
    val title: String,
    val description: String,
    val iconName: String
)

val INTEL_ITEMS = listOf(
    IntelItem("ping_target", "Ping Target", "Shows a 50m circle on the map near your target. 5-min cooldown.", "gps_fixed"),
    IntelItem("ping_hunter", "Ping Hunter", "Shows a 50m circle on the map near your hunter. 5-min cooldown.", "person_search"),
)

sealed class ItemResult {
    data class Success(val message: String) : ItemResult()
    data class OnCooldown(val remainingMs: Long) : ItemResult()
    data class Failed(val reason: String) : ItemResult()
}
