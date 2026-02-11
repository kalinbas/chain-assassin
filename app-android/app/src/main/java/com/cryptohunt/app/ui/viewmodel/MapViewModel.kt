package com.cryptohunt.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.cryptohunt.app.domain.game.GameEngine
import com.cryptohunt.app.domain.location.LocationTracker
import com.cryptohunt.app.domain.model.GameState
import com.cryptohunt.app.domain.model.LocationState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class MapViewModel @Inject constructor(
    private val gameEngine: GameEngine,
    private val locationTracker: LocationTracker
) : ViewModel() {

    val gameState: StateFlow<GameState?> = gameEngine.state
    val locationState: StateFlow<LocationState> = locationTracker.state

    fun clearExpiredPing() {
        gameEngine.clearExpiredPing()
    }
}
