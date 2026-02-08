package com.cryptohunt.app.domain.model

data class LocationState(
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val accuracy: Float = 0f,
    val isTracking: Boolean = false,
    val distanceToZoneEdge: Double = 0.0,
    val isInsideZone: Boolean = true,
    val gpsLostSeconds: Int = 0
)
