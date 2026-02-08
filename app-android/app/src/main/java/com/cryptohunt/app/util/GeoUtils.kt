package com.cryptohunt.app.util

import kotlin.math.*

object GeoUtils {

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /** Haversine distance in meters between two lat/lng points. */
    fun haversineDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    /** True if point is inside a circular zone. */
    fun isInsideZone(lat: Double, lng: Double, centerLat: Double, centerLng: Double, radiusMeters: Double): Boolean {
        return haversineDistance(lat, lng, centerLat, centerLng) <= radiusMeters
    }

    /**
     * Distance from point to zone edge.
     * Positive = inside (meters to edge), negative = outside.
     */
    fun distanceToZoneEdge(lat: Double, lng: Double, centerLat: Double, centerLng: Double, radiusMeters: Double): Double {
        val distanceToCenter = haversineDistance(lat, lng, centerLat, centerLng)
        return radiusMeters - distanceToCenter
    }

    /** Bearing from point 1 to point 2 in degrees (0-360). */
    fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLng = Math.toRadians(lng2 - lng1)
        val y = sin(dLng) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }
}
