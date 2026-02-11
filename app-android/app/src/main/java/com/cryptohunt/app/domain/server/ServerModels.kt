package com.cryptohunt.app.domain.server

/**
 * Connection state of the WebSocket client.
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    CONNECTED,
    RECONNECTING
}

/**
 * Target info received from server.
 */
data class TargetInfo(
    val address: String,
    val playerNumber: Int
)

/**
 * Zone state received from server.
 */
data class ZoneState(
    val centerLat: Double,
    val centerLng: Double,
    val currentRadiusMeters: Double,
    val nextShrinkAt: Long?,
    val nextRadiusMeters: Double?
)

/**
 * Leaderboard entry from server.
 */
data class ServerLeaderboardEntry(
    val address: String,
    val playerNumber: Int,
    val kills: Int,
    val isAlive: Boolean,
    val eliminatedAt: Long?
)

/**
 * Sealed class hierarchy for all server WebSocket messages.
 * Each subclass maps to a specific `type` field in the JSON payload.
 */
sealed class ServerMessage {

    /** Response to successful authentication. */
    data class AuthSuccess(
        val gameId: Int,
        val playerNumber: Int,
        val isAlive: Boolean,
        val kills: Int,
        val target: TargetInfo?,
        val hunterPlayerNumber: Int?,
        val lastHeartbeatAt: Long?,
        val subPhase: String?,
        val pregameEndsAt: Long?
    ) : ServerMessage()

    /** A kill was recorded. */
    data class KillRecorded(
        val hunter: String,
        val target: String,
        val hunterKills: Int
    ) : ServerMessage()

    /** A player was eliminated (killed, zone violation, or heartbeat timeout). */
    data class PlayerEliminated(
        val player: String,
        val eliminator: String,
        val reason: String
    ) : ServerMessage()

    /** New target assigned to this player. */
    data class TargetAssigned(
        val target: TargetInfo,
        val hunterPlayerNumber: Int
    ) : ServerMessage()

    /** Hunter assignment changed for this player. */
    data class HunterUpdated(
        val hunterPlayerNumber: Int
    ) : ServerMessage()

    /** Zone shrink occurred. */
    data class ZoneShrink(
        val zone: ZoneState
    ) : ServerMessage()

    /** Warning: player is outside the zone. */
    data class ZoneWarning(
        val secondsRemaining: Int,
        val inZone: Boolean
    ) : ServerMessage()

    /** Leaderboard updated. */
    data class LeaderboardUpdate(
        val entries: List<ServerLeaderboardEntry>
    ) : ServerMessage()

    /** This player's heartbeat was refreshed (someone scanned our QR). */
    data class HeartbeatRefreshed(
        val refreshedUntil: Long
    ) : ServerMessage()

    /** Heartbeat scan succeeded (we scanned someone else's QR). */
    data class HeartbeatScanSuccess(
        val scannedPlayerNumber: Int
    ) : ServerMessage()

    /** Heartbeat scan failed. */
    data class HeartbeatError(
        val error: String
    ) : ServerMessage()

    /** Pregame phase started. */
    data class GamePregameStarted(
        val pregameDurationSeconds: Int,
        val checkedInCount: Int,
        val playerCount: Int
    ) : ServerMessage()

    /** Game started — per-player message with target assignment. */
    data class GameStarted(
        val target: TargetInfo,
        val hunterPlayerNumber: Int,
        val heartbeatDeadline: Long,
        val heartbeatIntervalSeconds: Int,
        val zone: ZoneState?
    ) : ServerMessage()

    /** Game started — broadcast to all. */
    data class GameStartedBroadcast(
        val playerCount: Int
    ) : ServerMessage()

    /** Game ended. */
    data class GameEnded(
        val winner1: String,
        val winner2: String,
        val winner3: String,
        val topKiller: String
    ) : ServerMessage()

    /** Game cancelled. */
    data class GameCancelled(
        val gameId: Int
    ) : ServerMessage()

    /** Check-in progress updated. */
    data class CheckinUpdate(
        val checkedInCount: Int,
        val totalPlayers: Int,
        val player: String
    ) : ServerMessage()

    /** Check-in phase started. */
    data class CheckinStarted(
        val checkinDurationSeconds: Int
    ) : ServerMessage()

    /** A player registered for the game. */
    data class PlayerRegistered(
        val address: String,
        val playerCount: Int
    ) : ServerMessage()

    /** Server error message. */
    data class Error(
        val error: String
    ) : ServerMessage()
}
