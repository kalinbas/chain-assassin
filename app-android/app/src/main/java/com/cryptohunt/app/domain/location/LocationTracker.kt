package com.cryptohunt.app.domain.location

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.cryptohunt.app.domain.model.LocationState
import com.cryptohunt.app.util.GeoUtils
import com.google.android.gms.location.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocationTracker @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val _state = MutableStateFlow(LocationState())
    val state: StateFlow<LocationState> = _state.asStateFlow()

    private var zoneCenterLat = 0.0
    private var zoneCenterLng = 0.0
    private var zoneRadius = 0.0

    private var lastGpsFixTime: Long = 0L
    private var gpsWatchdogJob: Job? = null
    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { loc ->
                lastGpsFixTime = System.currentTimeMillis()
                val distToEdge = GeoUtils.distanceToZoneEdge(
                    loc.latitude, loc.longitude,
                    zoneCenterLat, zoneCenterLng, zoneRadius
                )
                _state.value = LocationState(
                    lat = loc.latitude,
                    lng = loc.longitude,
                    accuracy = loc.accuracy,
                    isTracking = true,
                    distanceToZoneEdge = distToEdge,
                    isInsideZone = distToEdge >= 0,
                    gpsLostSeconds = 0
                )
            }
        }
    }

    fun setZone(centerLat: Double, centerLng: Double, radiusMeters: Double) {
        zoneCenterLat = centerLat
        zoneCenterLng = centerLng
        zoneRadius = radiusMeters
    }

    fun updateZoneRadius(radiusMeters: Double) {
        zoneRadius = radiusMeters
        // Recompute with current location
        val current = _state.value
        if (current.isTracking) {
            val distToEdge = GeoUtils.distanceToZoneEdge(
                current.lat, current.lng,
                zoneCenterLat, zoneCenterLng, radiusMeters
            )
            _state.value = current.copy(
                distanceToZoneEdge = distToEdge,
                isInsideZone = distToEdge >= 0
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun startTracking() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateIntervalMillis(2000L)
            .build()

        lastGpsFixTime = System.currentTimeMillis()
        fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        startGpsWatchdog()
    }

    fun stopTracking() {
        fusedClient.removeLocationUpdates(locationCallback)
        stopGpsWatchdog()
        _state.value = _state.value.copy(isTracking = false, gpsLostSeconds = 0)
    }

    private fun startGpsWatchdog() {
        gpsWatchdogJob?.cancel()
        gpsWatchdogJob = watchdogScope.launch {
            while (isActive) {
                delay(1000)
                val elapsed = ((System.currentTimeMillis() - lastGpsFixTime) / 1000).toInt()
                if (elapsed > 5) {
                    // Only update gpsLostSeconds if GPS is actually lost (>5s grace for normal interval gaps)
                    _state.value = _state.value.copy(gpsLostSeconds = elapsed)
                }
            }
        }
    }

    private fun stopGpsWatchdog() {
        gpsWatchdogJob?.cancel()
        gpsWatchdogJob = null
    }
}
