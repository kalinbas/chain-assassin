package com.cryptohunt.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.cryptohunt.app.domain.game.GameEngine
import com.cryptohunt.app.domain.location.LocationTracker
import com.cryptohunt.app.domain.model.GameState
import com.cryptohunt.app.domain.model.LocationState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import kotlin.random.Random

data class HeatmapBlob(
    val lat: Double,
    val lng: Double,
    val intensity: Float // 0.0 - 1.0
)

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

    fun generateHeatmapBlobs(): List<HeatmapBlob> {
        val config = gameState.value?.config ?: return emptyList()
        val radius = gameState.value?.currentZoneRadius ?: config.initialRadiusMeters
        val blobs = mutableListOf<HeatmapBlob>()

        // Generate 8-15 random clusters within the zone
        val count = Random.nextInt(8, 16)
        for (i in 0 until count) {
            val angle = Random.nextDouble(0.0, 2 * Math.PI)
            val dist = Random.nextDouble(0.0, radius * 0.8)
            val offsetLat = (dist / 111_000.0) * Math.cos(angle)
            val offsetLng = (dist / (111_000.0 * Math.cos(Math.toRadians(config.zoneCenterLat)))) * Math.sin(angle)

            blobs.add(
                HeatmapBlob(
                    lat = config.zoneCenterLat + offsetLat,
                    lng = config.zoneCenterLng + offsetLng,
                    intensity = Random.nextFloat() * 0.6f + 0.2f
                )
            )
        }
        return blobs
    }
}
