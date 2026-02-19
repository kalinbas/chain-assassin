package com.cryptohunt.app.domain.model

import java.math.BigInteger

data class GameConfig(
    val id: String,
    val name: String,
    val entryFee: Double,
    val minPlayers: Int,
    val maxPlayers: Int,
    val zoneCenterLat: Double,
    val zoneCenterLng: Double,
    val meetingLat: Double = 0.0,
    val meetingLng: Double = 0.0,
    val initialRadiusMeters: Double,
    val shrinkSchedule: List<ZoneShrink>,
    val durationMinutes: Int,
    val checkInDurationMinutes: Int = 10,
    val bps1st: Int = 4000,
    val bps2nd: Int = 1500,
    val bps3rd: Int = 1000,
    val bpsKills: Int = 2000,
    val entryFeeWei: BigInteger = BigInteger.ZERO,
    val baseReward: Double = 0.0,
    val registrationDeadline: Long = 0L,
    val gameDate: Long = 0L,
    val pregameDurationMinutes: Int = 3
)

data class ZoneShrink(val atMinute: Int, val newRadiusMeters: Double)

data class Player(
    val id: String,
    val number: Int,
    val walletAddress: String,
    val isAlive: Boolean = true,
    val killCount: Int = 0,
    val isCheckedIn: Boolean = false
)

data class Target(val player: Player, val assignedAt: Long)

data class LeaderboardEntry(
    val rank: Int,
    val playerNumber: Int,
    val kills: Int,
    val isAlive: Boolean,
    val isCurrentPlayer: Boolean = false
)

data class KillEvent(
    val id: String,
    val hunterNumber: Int,
    val targetNumber: Int,
    val timestamp: Long,
    val zone: String,
    val isNoCheckIn: Boolean = false
)

enum class GamePhase {
    NOT_JOINED, REGISTERED, CHECK_IN, PREGAME, ACTIVE, ELIMINATED, ENDED, CANCELLED
}

enum class EliminationReason {
    HUNTED,
    ZONE_VIOLATION,
    HEARTBEAT_TIMEOUT,
    COMPLIANCE_LOCATION_TIMEOUT,
    COMPLIANCE_BLE_TIMEOUT,
    COMPLIANCE_NETWORK_TIMEOUT,
    NO_CHECKIN,
    UNKNOWN
}

data class ComplianceWarningStatus(
    val locationSecondsRemaining: Int? = null,
    val bleSecondsRemaining: Int? = null,
    val networkSecondsRemaining: Int? = null,
    val warningThresholdSeconds: Int = 30,
    val graceThresholdSeconds: Int = 120
)

data class GameState(
    val phase: GamePhase = GamePhase.NOT_JOINED,
    val config: GameConfig,
    val currentPlayer: Player,
    val currentTarget: Target? = null,
    val hunterPlayerNumber: Int? = null,
    val playersRemaining: Int = 100,
    val currentZoneRadius: Double = 0.0,
    val killFeed: List<KillEvent> = emptyList(),
    val isInZone: Boolean = true,
    val spectatorMode: Boolean = false,
    val itemCooldowns: Map<String, Long> = emptyMap(), // itemId -> timestamp when last used
    val activePing: PingOverlay? = null,
    val leaderboard: List<LeaderboardEntry> = emptyList(),
    val registeredAt: Long = 0L,
    val gameStartTime: Long = 0L,
    val checkInVerified: Boolean = false,
    // Server-driven timestamps (Unix seconds)
    val checkinEndsAt: Long = 0L,
    val pregameEndsAt: Long = 0L,
    val nextShrinkAt: Long? = null,
    val checkedInCount: Int = 0,
    val checkedInPlayerNumbers: Set<Int> = emptySet(),
    val bluetoothId: String? = null,
    // Heartbeat (anti-QR-hiding fairplay) — server-driven
    val heartbeatDeadline: Long = 0L,
    val heartbeatIntervalSeconds: Int = 600,
    val heartbeatDisableThreshold: Int = 4,
    val heartbeatDisabled: Boolean = false,
    val complianceWarning: ComplianceWarningStatus? = null,
    val eliminationReason: EliminationReason? = null,
    val eliminatedByPlayerNumber: Int? = null
)

sealed class CheckInResult {
    data object Verified : CheckInResult()
    data object AlreadyVerified : CheckInResult()
    data object PlayerNotCheckedIn : CheckInResult()
    data object ScanYourself : CheckInResult()
    data object UnknownPlayer : CheckInResult()
    data object WrongPhase : CheckInResult()
    data object TooFar : CheckInResult()
    data object NoGame : CheckInResult()
}

data class PingOverlay(
    val lat: Double,
    val lng: Double,
    val radiusMeters: Double = 50.0,
    val type: String, // "ping_target" or "ping_hunter"
    val expiresAt: Long // epoch millis
)

sealed class HeartbeatResult {
    data class Success(val scannedPlayerNumber: Int) : HeartbeatResult()
    data object ScanYourself : HeartbeatResult()
    data object ScanTarget : HeartbeatResult()
    data object ScanHunter : HeartbeatResult()
    data object PlayerNotAlive : HeartbeatResult()
    data object UnknownPlayer : HeartbeatResult()
    data object HeartbeatDisabled : HeartbeatResult()
    data object WrongPhase : HeartbeatResult()
    data object NoGame : HeartbeatResult()
}
