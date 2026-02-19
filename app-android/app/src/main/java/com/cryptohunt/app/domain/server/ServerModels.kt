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
    val playerNumber: Int,
    val kills: Int,
    val isAlive: Boolean,
    val eliminatedAt: Long?
)

// ============ REST API Response Models ============

/**
 * Response from GET /api/games and GET /api/games/:gameId
 */
data class ServerGame(
    val gameId: Int,
    val title: String,
    val entryFee: String,       // wei as string
    val baseReward: String,     // wei as string
    val minPlayers: Int,
    val maxPlayers: Int,
    val registrationDeadline: Long,
    val gameDate: Long,
    val expiryDeadline: Long,
    val maxDuration: Long,
    val createdAt: Long,
    val creator: String,
    val centerLat: Int,
    val centerLng: Int,
    val meetingLat: Int,
    val meetingLng: Int,
    val bps1st: Int,
    val bps2nd: Int,
    val bps3rd: Int,
    val bpsKills: Int,
    val bpsCreator: Int,
    val totalCollected: String,
    val playerCount: Int,
    val phase: Int,
    val subPhase: String?,
    val winner1: Int,
    val winner2: Int,
    val winner3: Int,
    val topKiller: Int,
    val zoneShrinks: List<ServerZoneShrink>,
    val leaderboard: List<ServerLeaderboardEntry> = emptyList()
)

data class ServerZoneShrink(
    val atSecond: Int,
    val radiusMeters: Int
)

/**
 * Response from GET /api/games/:gameId/player/:address
 */
data class ServerPlayerInfo(
    val registered: Boolean,
    val alive: Boolean,
    val kills: Int,
    val claimed: Boolean,
    val playerNumber: Int,
    val checkedIn: Boolean
)

// ============ WebSocket Models ============

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
        val heartbeatDeadline: Long?,
        val heartbeatIntervalSeconds: Int?,
        val heartbeatDisableThreshold: Int?,
        val heartbeatDisabled: Boolean?,
        val subPhase: String?,
        val checkinEndsAt: Long?,
        val pregameEndsAt: Long?,
        val playerCount: Int?,
        val checkedInCount: Int?,
        val aliveCount: Int?
    ) : ServerMessage()

    /** A kill was recorded. */
    data class KillRecorded(
        val hunterNumber: Int,
        val targetNumber: Int,
        val hunterKills: Int
    ) : ServerMessage()

    /** A player was eliminated (killed, zone violation, or heartbeat timeout). */
    data class PlayerEliminated(
        val playerNumber: Int,
        val eliminatorNumber: Int,
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

    /** Compliance warning for stale location/BLE/network signals. */
    data class ComplianceWarning(
        val locationSecondsRemaining: Int?,
        val bleSecondsRemaining: Int?,
        val networkSecondsRemaining: Int?,
        val warningThresholdSeconds: Int,
        val graceThresholdSeconds: Int
    ) : ServerMessage()

    /** Compliance status recovered (all required signals are fresh again). */
    data object ComplianceOk : ServerMessage()

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
        val pregameEndsAt: Long,
        val checkedInCount: Int,
        val playerCount: Int
    ) : ServerMessage()

    /** Game started — per-player message with target assignment. */
    data class GameStarted(
        val target: TargetInfo,
        val hunterPlayerNumber: Int,
        val heartbeatDeadline: Long,
        val heartbeatIntervalSeconds: Int,
        val heartbeatDisableThreshold: Int,
        val heartbeatDisabled: Boolean,
        val zone: ZoneState?
    ) : ServerMessage()

    /** Game started — broadcast to all. */
    data class GameStartedBroadcast(
        val playerCount: Int
    ) : ServerMessage()

    /** Game ended. */
    data class GameEnded(
        val winner1: Int,
        val winner2: Int,
        val winner3: Int,
        val topKiller: Int
    ) : ServerMessage()

    /** Game cancelled. */
    data class GameCancelled(
        val gameId: Int
    ) : ServerMessage()

    /** Check-in progress updated. */
    data class CheckinUpdate(
        val checkedInCount: Int,
        val totalPlayers: Int,
        val playerNumber: Int
    ) : ServerMessage()

    /** Check-in phase started. */
    data class CheckinStarted(
        val checkinDurationSeconds: Int,
        val checkinEndsAt: Long
    ) : ServerMessage()

    /** A player registered for the game. */
    data class PlayerRegistered(
        val playerNumber: Int,
        val playerCount: Int
    ) : ServerMessage()

    /** Server error message. */
    data class Error(
        val error: String
    ) : ServerMessage()
}
