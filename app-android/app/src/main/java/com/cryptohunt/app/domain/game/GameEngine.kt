package com.cryptohunt.app.domain.game

import android.util.Log
import com.cryptohunt.app.domain.model.*
import com.cryptohunt.app.domain.server.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GameEngine"

@Singleton
class GameEngine @Inject constructor() {

    private val _state = MutableStateFlow<GameState?>(null)
    val state: StateFlow<GameState?> = _state.asStateFlow()

    /** One-shot events for the UI to react to. */
    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 20)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private val killFeedList = mutableListOf<KillEvent>()

    // --- Public API ---

    /** Clear all game state (used on logout). */
    fun reset() {
        killFeedList.clear()
        _state.value = null
    }

    fun registerForGame(config: GameConfig, walletAddress: String, startTime: Long, assignedPlayerNumber: Int = 0) {
        val playerNumber = if (assignedPlayerNumber > 0) assignedPlayerNumber else 0

        val currentPlayer = Player(
            id = "player_$playerNumber",
            number = playerNumber,
            walletAddress = walletAddress
        )

        killFeedList.clear()

        _state.value = GameState(
            phase = GamePhase.REGISTERED,
            config = config,
            currentPlayer = currentPlayer,
            playersRemaining = config.maxPlayers,
            currentZoneRadius = config.initialRadiusMeters,
            registeredAt = System.currentTimeMillis(),
            gameStartTime = startTime,
            isInZone = true
        )
    }

    fun beginCheckIn(checkinEndsAt: Long = 0L) {
        val current = _state.value ?: return
        if (current.phase != GamePhase.REGISTERED) return

        _state.value = current.copy(
            phase = GamePhase.CHECK_IN,
            checkinEndsAt = checkinEndsAt,
            checkedInCount = 0,
            checkedInPlayerNumbers = emptySet()
        )
        _events.tryEmit(GameEvent.CheckInStarted)
    }

    fun processCheckInScan(scannedPayload: String, bluetoothId: String? = null): CheckInResult {
        val current = _state.value ?: return CheckInResult.NoGame
        if (current.phase != GamePhase.CHECK_IN) return CheckInResult.WrongPhase
        if (current.checkInVerified) return CheckInResult.AlreadyVerified

        val parts = scannedPayload.split(":")
        val scannedPlayerId = if (parts.size >= 3) parts[2] else scannedPayload

        // Can't scan yourself
        if (scannedPlayerId == current.currentPlayer.id) {
            return CheckInResult.ScanYourself
        }

        // Optimistic: mark as verified locally, server will confirm
        _state.value = current.copy(
            checkInVerified = true,
            bluetoothId = bluetoothId
        )
        _events.tryEmit(GameEvent.CheckInVerified)
        return CheckInResult.Verified
    }


    fun claimRefund(): Boolean {
        val current = _state.value ?: return false
        if (current.phase != GamePhase.REGISTERED) return false
        cleanup()
        _events.tryEmit(GameEvent.RefundClaimed)
        return true
    }

    fun processKill(scannedPayload: String): KillResult {
        val current = _state.value ?: return KillResult.NoGame
        val target = current.currentTarget ?: return KillResult.NoTarget

        // Parse payload: "ca:gameId:playerNumber"
        val parts = scannedPayload.split(":")
        val scannedPlayerId = if (parts.size >= 3) parts[2] else scannedPayload

        if (scannedPlayerId != target.player.id) {
            return KillResult.WrongTarget
        }

        // Optimistic: show kill immediately; server will confirm via KillRecorded
        val killEvent = KillEvent(
            id = UUID.randomUUID().toString(),
            hunterNumber = current.currentPlayer.number,
            targetNumber = target.player.number,
            timestamp = System.currentTimeMillis(),
            zone = ""
        )
        killFeedList.add(0, killEvent)

        _state.value = current.copy(
            currentPlayer = current.currentPlayer.copy(killCount = current.currentPlayer.killCount + 1),
            currentTarget = null, // cleared until server assigns new target
            playersRemaining = current.playersRemaining - 1,
            killFeed = killFeedList.toList()
        )

        _events.tryEmit(GameEvent.KillConfirmed(killEvent))
        return KillResult.Confirmed(killEvent)
    }

    fun updateZoneStatus(isInZone: Boolean) {
        val current = _state.value ?: return
        _state.value = current.copy(isInZone = isInZone)
    }

    fun setSpectatorMode() {
        val current = _state.value ?: return
        _state.value = current.copy(spectatorMode = true, phase = GamePhase.ACTIVE)
    }

    fun useItemWithPing(itemId: String): PingOverlay? {
        val current = _state.value ?: return null
        val now = System.currentTimeMillis()

        // Record cooldown — server will send actual ping coordinates via message
        _state.value = current.copy(
            itemCooldowns = current.itemCooldowns + (itemId to now)
        )

        // Return a placeholder; actual coordinates come from server
        return PingOverlay(
            lat = 0.0,
            lng = 0.0,
            radiusMeters = 50.0,
            type = itemId,
            expiresAt = now + 30_000
        )
    }

    fun clearExpiredPing() {
        val current = _state.value ?: return
        val ping = current.activePing ?: return
        if (System.currentTimeMillis() > ping.expiresAt) {
            _state.value = current.copy(activePing = null)
        }
    }

    fun processHeartbeatScan(scannedPayload: String): HeartbeatResult {
        val current = _state.value ?: return HeartbeatResult.NoGame
        if (current.phase != GamePhase.ACTIVE) return HeartbeatResult.WrongPhase
        if (current.heartbeatDisabled) return HeartbeatResult.HeartbeatDisabled

        val parts = scannedPayload.split(":")
        val scannedPlayerId = if (parts.size >= 3) parts[2] else scannedPayload

        // Can't scan yourself
        if (scannedPlayerId == current.currentPlayer.id) {
            return HeartbeatResult.ScanYourself
        }

        // Can't scan your target
        if (current.currentTarget != null && scannedPlayerId == current.currentTarget.player.id) {
            return HeartbeatResult.ScanTarget
        }

        // Server will validate and confirm via heartbeat:scan_success or heartbeat:error
        // Optimistic local success — scanned player number extracted from payload
        val scannedNumber = scannedPlayerId.removePrefix("player_").toIntOrNull() ?: 0
        return HeartbeatResult.Success(scannedNumber)
    }

    fun cleanup() {
        _state.value = null
        killFeedList.clear()
    }

    // --- Server Message Processing ---

    /**
     * Central handler that updates GameState based on server WebSocket messages.
     */
    fun processServerMessage(msg: ServerMessage) {
        when (msg) {
            is ServerMessage.AuthSuccess -> handleAuthSuccess(msg)
            is ServerMessage.TargetAssigned -> handleTargetAssigned(msg)
            is ServerMessage.HunterUpdated -> handleHunterUpdated(msg)
            is ServerMessage.PlayerEliminated -> handlePlayerEliminated(msg)
            is ServerMessage.KillRecorded -> handleKillRecorded(msg)
            is ServerMessage.ZoneShrink -> handleZoneShrink(msg)
            is ServerMessage.LeaderboardUpdate -> handleLeaderboardUpdate(msg)
            is ServerMessage.HeartbeatRefreshed -> handleHeartbeatRefreshed(msg)
            is ServerMessage.HeartbeatScanSuccess -> handleHeartbeatScanSuccess(msg)
            is ServerMessage.GameEnded -> handleGameEnded(msg)
            is ServerMessage.GameCancelled -> handleGameCancelled(msg)
            is ServerMessage.GameStarted -> handleGameStarted(msg)
            is ServerMessage.GamePregameStarted -> handlePregameStarted(msg)
            is ServerMessage.GameStartedBroadcast -> handleGameStartedBroadcast(msg)
            is ServerMessage.CheckinUpdate -> handleCheckinUpdate(msg)
            is ServerMessage.CheckinStarted -> handleCheckinStarted(msg)
            is ServerMessage.PlayerRegistered -> handlePlayerRegistered(msg)
            is ServerMessage.HeartbeatError -> Log.w(TAG, "Heartbeat error: ${msg.error}")
            is ServerMessage.ZoneWarning -> handleZoneWarning(msg)
            is ServerMessage.Error -> Log.e(TAG, "Server error: ${msg.error}")
        }
    }

    // --- Server Message Handlers ---

    private fun handleAuthSuccess(msg: ServerMessage.AuthSuccess) {
        val current = _state.value ?: return
        Log.i(TAG, "Auth success: player #${msg.playerNumber}, alive=${msg.isAlive}, kills=${msg.kills}, subPhase=${msg.subPhase}")

        // Sync local state with server on (re)connect
        val player = current.currentPlayer.copy(
            id = "player_${msg.playerNumber}",
            number = msg.playerNumber,
            killCount = msg.kills,
            isAlive = msg.isAlive
        )

        val target = msg.target?.let {
            Target(
                player = Player(id = "player_${it.playerNumber}", number = it.playerNumber, walletAddress = ""),
                assignedAt = System.currentTimeMillis()
            )
        }

        // Determine phase from server subPhase
        val phase = when (msg.subPhase) {
            "checkin" -> GamePhase.CHECK_IN
            "pregame" -> GamePhase.PREGAME
            "game" -> GamePhase.ACTIVE
            "ended" -> GamePhase.ENDED
            "cancelled" -> GamePhase.CANCELLED
            else -> if (!msg.isAlive) GamePhase.ELIMINATED else current.phase
        }

        _state.value = current.copy(
            currentPlayer = player,
            currentTarget = target,
            hunterPlayerNumber = msg.hunterPlayerNumber,
            lastHeartbeatAt = msg.lastHeartbeatAt ?: 0L,
            phase = phase,
            checkinEndsAt = msg.checkinEndsAt ?: current.checkinEndsAt,
            pregameEndsAt = msg.pregameEndsAt ?: current.pregameEndsAt
        )
    }

    private fun handleTargetAssigned(msg: ServerMessage.TargetAssigned) {
        val current = _state.value ?: return
        val targetPlayer = Player(
            id = "player_${msg.target.playerNumber}",
            number = msg.target.playerNumber,
            walletAddress = ""
        )
        _state.value = current.copy(
            currentTarget = Target(targetPlayer, System.currentTimeMillis()),
            hunterPlayerNumber = msg.hunterPlayerNumber
        )
        _events.tryEmit(GameEvent.TargetReassigned(targetPlayer))
    }

    private fun handleHunterUpdated(msg: ServerMessage.HunterUpdated) {
        val current = _state.value ?: return
        _state.value = current.copy(hunterPlayerNumber = msg.hunterPlayerNumber)
    }

    private fun handlePlayerEliminated(msg: ServerMessage.PlayerEliminated) {
        val current = _state.value ?: return

        if (msg.playerNumber == current.currentPlayer.number) {
            // We were eliminated
            _state.value = current.copy(phase = GamePhase.ELIMINATED)
            when (msg.reason) {
                "zone_violation" -> _events.tryEmit(GameEvent.OutOfZoneEliminated)
                "heartbeat_timeout" -> _events.tryEmit(GameEvent.HeartbeatEliminated)
                "no_checkin" -> _events.tryEmit(GameEvent.NoCheckInEliminated)
                "killed" -> _events.tryEmit(GameEvent.Eliminated(msg.eliminatorNumber))
                else -> _events.tryEmit(GameEvent.Eliminated(msg.eliminatorNumber))
            }
        } else {
            // Another player eliminated — decrement count
            _state.value = current.copy(
                playersRemaining = (current.playersRemaining - 1).coerceAtLeast(1)
            )
        }
    }

    private fun handleKillRecorded(msg: ServerMessage.KillRecorded) {
        val current = _state.value ?: return

        val killEvent = KillEvent(
            id = UUID.randomUUID().toString(),
            hunterNumber = msg.hunterNumber,
            targetNumber = msg.targetNumber,
            timestamp = System.currentTimeMillis(),
            zone = ""
        )

        // Only add to kill feed if this wasn't our own optimistic kill
        if (msg.hunterNumber != current.currentPlayer.number) {
            killFeedList.add(0, killEvent)
            _state.value = current.copy(killFeed = killFeedList.take(50))
            _events.tryEmit(GameEvent.KillFeedUpdate(killEvent))
        } else {
            // Server confirmed our kill — update kill count from server
            _state.value = current.copy(
                currentPlayer = current.currentPlayer.copy(killCount = msg.hunterKills)
            )
        }
    }

    private fun handleZoneShrink(msg: ServerMessage.ZoneShrink) {
        val current = _state.value ?: return
        _state.value = current.copy(
            currentZoneRadius = msg.zone.currentRadiusMeters,
            nextShrinkAt = msg.zone.nextShrinkAt
        )
        _events.tryEmit(GameEvent.ZoneShrink(msg.zone.currentRadiusMeters))
    }

    private fun handleLeaderboardUpdate(msg: ServerMessage.LeaderboardUpdate) {
        val current = _state.value ?: return
        val entries = msg.entries.mapIndexed { index, entry ->
            LeaderboardEntry(
                rank = index + 1,
                playerNumber = entry.playerNumber,
                kills = entry.kills,
                isAlive = entry.isAlive,
                isCurrentPlayer = entry.playerNumber == current.currentPlayer.number
            )
        }
        _state.value = current.copy(
            leaderboard = entries,
            playersRemaining = entries.count { it.isAlive }
        )
    }

    private fun handleHeartbeatRefreshed(msg: ServerMessage.HeartbeatRefreshed) {
        val current = _state.value ?: return
        _state.value = current.copy(lastHeartbeatAt = msg.refreshedUntil)
    }

    private fun handleHeartbeatScanSuccess(msg: ServerMessage.HeartbeatScanSuccess) {
        _events.tryEmit(GameEvent.HeartbeatRefreshed(msg.scannedPlayerNumber))
    }

    private fun handleGameStarted(msg: ServerMessage.GameStarted) {
        val current = _state.value ?: return
        val now = System.currentTimeMillis()

        val targetPlayer = Player(
            id = "player_${msg.target.playerNumber}",
            number = msg.target.playerNumber,
            walletAddress = ""
        )

        _state.value = current.copy(
            phase = GamePhase.ACTIVE,
            currentTarget = Target(targetPlayer, now),
            hunterPlayerNumber = msg.hunterPlayerNumber,
            lastHeartbeatAt = msg.heartbeatDeadline,
            heartbeatIntervalSeconds = msg.heartbeatIntervalSeconds,
            heartbeatDisabled = false,
            currentZoneRadius = msg.zone?.currentRadiusMeters ?: current.currentZoneRadius,
            nextShrinkAt = msg.zone?.nextShrinkAt,
            gameStartTime = System.currentTimeMillis() / 1000
        )

        _events.tryEmit(GameEvent.GameStarted)
    }

    private fun handlePregameStarted(msg: ServerMessage.GamePregameStarted) {
        val current = _state.value ?: return
        _state.value = current.copy(
            phase = GamePhase.PREGAME,
            pregameEndsAt = msg.pregameEndsAt,
            checkedInCount = msg.checkedInCount,
            playersRemaining = msg.playerCount
        )
        _events.tryEmit(GameEvent.PregameStarted)
    }

    private fun handleGameStartedBroadcast(msg: ServerMessage.GameStartedBroadcast) {
        val current = _state.value ?: return
        _state.value = current.copy(playersRemaining = msg.playerCount)
    }

    private fun handleCheckinUpdate(msg: ServerMessage.CheckinUpdate) {
        val current = _state.value ?: return
        val isMe = msg.playerNumber == current.currentPlayer.number
        val updatedNumbers = if (msg.playerNumber > 0)
            current.checkedInPlayerNumbers + msg.playerNumber
        else
            current.checkedInPlayerNumbers
        _state.value = current.copy(
            checkedInCount = msg.checkedInCount,
            playersRemaining = msg.totalPlayers,
            checkedInPlayerNumbers = updatedNumbers,
            checkInVerified = current.checkInVerified || isMe
        )
        if (isMe && !current.checkInVerified) {
            _events.tryEmit(GameEvent.CheckInVerified)
        }
    }

    private fun handleGameEnded(msg: ServerMessage.GameEnded) {
        val current = _state.value ?: return
        _state.value = current.copy(phase = GamePhase.ENDED)
        _events.tryEmit(GameEvent.GameEnded)
    }

    private fun handleGameCancelled(msg: ServerMessage.GameCancelled) {
        val current = _state.value ?: return
        _state.value = current.copy(phase = GamePhase.CANCELLED)
        _events.tryEmit(GameEvent.GameCancelled)
    }

    private fun handleCheckinStarted(msg: ServerMessage.CheckinStarted) {
        val current = _state.value ?: return
        _state.value = current.copy(
            phase = GamePhase.CHECK_IN,
            checkinEndsAt = msg.checkinEndsAt,
            checkedInCount = 0,
            checkedInPlayerNumbers = emptySet()
        )
        _events.tryEmit(GameEvent.CheckInStarted)
    }

    private fun handlePlayerRegistered(msg: ServerMessage.PlayerRegistered) {
        val current = _state.value ?: return
        _state.value = current.copy(
            playersRemaining = msg.playerCount
        )
        Log.i(TAG, "Player registered: #${msg.playerNumber}, total: ${msg.playerCount}")
    }

    private fun handleZoneWarning(msg: ServerMessage.ZoneWarning) {
        // Could show a UI warning — for now just update zone status
        val current = _state.value ?: return
        _state.value = current.copy(isInZone = msg.inZone)
    }

}

sealed class KillResult {
    data class Confirmed(val event: KillEvent) : KillResult()
    data object WrongTarget : KillResult()
    data object NoTarget : KillResult()
    data object NoGame : KillResult()
}

sealed class GameEvent {
    data class KillConfirmed(val event: KillEvent) : GameEvent()
    data class KillFeedUpdate(val event: KillEvent) : GameEvent()
    data class TargetReassigned(val newTarget: Player?) : GameEvent()
    data class ZoneShrink(val newRadius: Double) : GameEvent()
    data class Eliminated(val byPlayerNumber: Int) : GameEvent()
    data object GameEnded : GameEvent()
    data object GpsDisqualified : GameEvent()
    data object OutOfZoneEliminated : GameEvent()
    data object CheckInStarted : GameEvent()
    data object CheckInVerified : GameEvent()
    data object PregameStarted : GameEvent()
    data object GameStarted : GameEvent()
    data object RefundClaimed : GameEvent()
    data object GameCancelled : GameEvent()
    data object NoCheckInEliminated : GameEvent()
    data class HeartbeatRefreshed(val scannedPlayerNumber: Int) : GameEvent()
    data object HeartbeatEliminated : GameEvent()
}
