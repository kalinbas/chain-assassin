package com.cryptohunt.app.domain.game

import android.util.Log
import com.cryptohunt.app.domain.model.*
import com.cryptohunt.app.domain.server.*
import kotlinx.coroutines.*
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<GameState?>(null)
    val state: StateFlow<GameState?> = _state.asStateFlow()

    /** One-shot events for the UI to react to. */
    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 20)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private var timerJob: Job? = null
    private var checkInTimerJob: Job? = null
    private var pregameTimerJob: Job? = null

    private val killFeedList = mutableListOf<KillEvent>()

    // --- Public API ---

    /** Clear all game state (used on logout). */
    fun reset() {
        timerJob?.cancel()
        checkInTimerJob?.cancel()
        pregameTimerJob?.cancel()
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

    fun beginCheckIn() {
        val current = _state.value ?: return
        if (current.phase != GamePhase.REGISTERED) return

        _state.value = current.copy(
            phase = GamePhase.CHECK_IN,
            checkInTimeRemainingSeconds = current.config.checkInDurationMinutes * 60,
            checkedInCount = 0,
            checkedInPlayerNumbers = emptySet()
        )
        _events.tryEmit(GameEvent.CheckInStarted)
        startCheckInCountdown()
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

    fun eliminateForGpsLoss() {
        val current = _state.value ?: return
        if (current.phase != GamePhase.ACTIVE) return
        _state.value = current.copy(phase = GamePhase.ELIMINATED)
        _events.tryEmit(GameEvent.GpsDisqualified)
        stopTimers()
    }

    fun updateZoneStatus(isInZone: Boolean) {
        val current = _state.value ?: return
        _state.value = current.copy(
            isInZone = isInZone,
            outOfZoneSeconds = if (isInZone) 0 else current.outOfZoneSeconds
        )
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
        stopTimers()
        checkInTimerJob?.cancel()
        pregameTimerJob?.cancel()
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
                player = Player(id = "player_${it.playerNumber}", number = it.playerNumber, walletAddress = it.address),
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
            phase = phase
        )

        // If we reconnected to an active game, restart the tick timer
        if (phase == GamePhase.ACTIVE) {
            startTickTimer()
        }
        // If we reconnected to check-in phase, start countdown
        if (phase == GamePhase.CHECK_IN) {
            startCheckInCountdown()
        }
        // If we reconnected to pregame, start countdown
        if (phase == GamePhase.PREGAME && msg.pregameEndsAt != null) {
            val remainingSeconds = ((msg.pregameEndsAt - System.currentTimeMillis() / 1000)).toInt().coerceAtLeast(0)
            _state.value = _state.value?.copy(pregameTimeRemainingSeconds = remainingSeconds)
            startPregameCountdown()
        }
    }

    private fun handleTargetAssigned(msg: ServerMessage.TargetAssigned) {
        val current = _state.value ?: return
        val targetPlayer = Player(
            id = "player_${msg.target.playerNumber}",
            number = msg.target.playerNumber,
            walletAddress = msg.target.address
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
        val myAddress = current.currentPlayer.walletAddress.lowercase()

        if (msg.player.lowercase() == myAddress) {
            // We were eliminated
            _state.value = current.copy(phase = GamePhase.ELIMINATED)
            stopTimers()
            when (msg.reason) {
                "zone_violation" -> _events.tryEmit(GameEvent.OutOfZoneEliminated)
                "heartbeat_timeout" -> _events.tryEmit(GameEvent.HeartbeatEliminated)
                "no_checkin" -> _events.tryEmit(GameEvent.NoCheckInEliminated)
                "killed" -> _events.tryEmit(GameEvent.Eliminated(0))
                else -> _events.tryEmit(GameEvent.Eliminated(0))
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
        val myAddress = current.currentPlayer.walletAddress.lowercase()

        val killEvent = KillEvent(
            id = UUID.randomUUID().toString(),
            hunterNumber = 0, // server sends addresses, not numbers — map if needed
            targetNumber = 0,
            timestamp = System.currentTimeMillis(),
            zone = ""
        )

        // Only add to kill feed if this wasn't our own optimistic kill
        if (msg.hunter.lowercase() != myAddress) {
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
            currentZoneRadius = msg.zone.currentRadiusMeters
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
                isCurrentPlayer = entry.address.lowercase() == current.currentPlayer.walletAddress.lowercase()
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
        val nowSeconds = now / 1000

        val targetPlayer = Player(
            id = "player_${msg.target.playerNumber}",
            number = msg.target.playerNumber,
            walletAddress = msg.target.address
        )

        _state.value = current.copy(
            phase = GamePhase.ACTIVE,
            currentTarget = Target(targetPlayer, now),
            hunterPlayerNumber = msg.hunterPlayerNumber,
            lastHeartbeatAt = msg.heartbeatDeadline,
            heartbeatIntervalSeconds = msg.heartbeatIntervalSeconds,
            heartbeatDisabled = false,
            currentZoneRadius = msg.zone?.currentRadiusMeters ?: current.currentZoneRadius,
            pregameTimeRemainingSeconds = 0
        )

        startTickTimer()
        _events.tryEmit(GameEvent.GameStarted)
    }

    private fun handlePregameStarted(msg: ServerMessage.GamePregameStarted) {
        val current = _state.value ?: return
        _state.value = current.copy(
            phase = GamePhase.PREGAME,
            pregameTimeRemainingSeconds = msg.pregameDurationSeconds,
            checkedInCount = msg.checkedInCount,
            playersRemaining = msg.playerCount
        )
        _events.tryEmit(GameEvent.PregameStarted)
        startPregameCountdown()
    }

    private fun handleGameStartedBroadcast(msg: ServerMessage.GameStartedBroadcast) {
        val current = _state.value ?: return
        _state.value = current.copy(playersRemaining = msg.playerCount)
    }

    private fun handleCheckinUpdate(msg: ServerMessage.CheckinUpdate) {
        val current = _state.value ?: return
        _state.value = current.copy(
            checkedInCount = msg.checkedInCount,
            playersRemaining = msg.totalPlayers
        )
    }

    private fun handleGameEnded(msg: ServerMessage.GameEnded) {
        val current = _state.value ?: return
        _state.value = current.copy(phase = GamePhase.ENDED)
        _events.tryEmit(GameEvent.GameEnded)
        stopTimers()
    }

    private fun handleGameCancelled(msg: ServerMessage.GameCancelled) {
        val current = _state.value ?: return
        _state.value = current.copy(phase = GamePhase.CANCELLED)
        _events.tryEmit(GameEvent.GameCancelled)
        stopTimers()
    }

    private fun handleCheckinStarted(msg: ServerMessage.CheckinStarted) {
        val current = _state.value ?: return
        _state.value = current.copy(
            phase = GamePhase.CHECK_IN,
            checkInTimeRemainingSeconds = msg.checkinDurationSeconds,
            checkedInCount = 0,
            checkedInPlayerNumbers = emptySet()
        )
        _events.tryEmit(GameEvent.CheckInStarted)
        startCheckInCountdown()
    }

    private fun handlePlayerRegistered(msg: ServerMessage.PlayerRegistered) {
        val current = _state.value ?: return
        _state.value = current.copy(
            playersRemaining = msg.playerCount
        )
        Log.i(TAG, "Player registered: ${msg.address.take(10)}..., total: ${msg.playerCount}")
    }

    private fun handleZoneWarning(msg: ServerMessage.ZoneWarning) {
        // Could show a UI warning — for now just update zone status
        val current = _state.value ?: return
        _state.value = current.copy(isInZone = msg.inZone)
    }

    // --- Timers ---

    private fun startTickTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive) {
                delay(1000)
                tick()
            }
        }
    }

    private fun startCheckInCountdown() {
        checkInTimerJob?.cancel()
        checkInTimerJob = scope.launch {
            while (isActive) {
                delay(1000)
                val current = _state.value ?: break
                if (current.phase != GamePhase.CHECK_IN) break
                val remaining = current.checkInTimeRemainingSeconds - 1
                if (remaining <= 0) break // server handles phase transition
                _state.value = current.copy(checkInTimeRemainingSeconds = remaining)
            }
        }
    }

    private fun startPregameCountdown() {
        pregameTimerJob?.cancel()
        pregameTimerJob = scope.launch {
            while (isActive) {
                delay(1000)
                val current = _state.value ?: break
                if (current.phase != GamePhase.PREGAME) break
                val remaining = current.pregameTimeRemainingSeconds - 1
                if (remaining <= 0) break // server handles phase transition
                _state.value = current.copy(pregameTimeRemainingSeconds = remaining)
            }
        }
    }

    private fun stopTimers() {
        timerJob?.cancel()
        checkInTimerJob?.cancel()
        pregameTimerJob?.cancel()
    }

    private fun tick() {
        val current = _state.value ?: return
        if (current.phase != GamePhase.ACTIVE) return

        val elapsed = current.gameTimeElapsedSeconds + 1

        // Out-of-zone deadline (60 seconds to return)
        val newOutOfZoneSeconds = if (!current.isInZone && !current.spectatorMode) {
            current.outOfZoneSeconds + 1
        } else {
            0
        }
        if (newOutOfZoneSeconds >= 60 && !current.spectatorMode) {
            _state.value = current.copy(phase = GamePhase.ELIMINATED)
            _events.tryEmit(GameEvent.OutOfZoneEliminated)
            stopTimers()
            return
        }

        // Auto-disable heartbeat when ≤4 players remain
        val heartbeatDisabled = current.playersRemaining <= 4

        _state.value = current.copy(
            gameTimeElapsedSeconds = elapsed,
            outOfZoneSeconds = newOutOfZoneSeconds,
            heartbeatDisabled = heartbeatDisabled
        )
    }

    private fun endGame() {
        val current = _state.value ?: return
        _state.value = current.copy(phase = GamePhase.ENDED)
        _events.tryEmit(GameEvent.GameEnded)
        stopTimers()
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
