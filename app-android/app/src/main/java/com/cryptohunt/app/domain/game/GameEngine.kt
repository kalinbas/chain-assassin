package com.cryptohunt.app.domain.game

import com.cryptohunt.app.domain.model.*
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
import kotlin.random.Random

@Singleton
class GameEngine @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<GameState?>(null)
    val state: StateFlow<GameState?> = _state.asStateFlow()

    /** One-shot events for the UI to react to. */
    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 20)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private var timerJob: Job? = null
    private var simulationJob: Job? = null
    private var checkInTimerJob: Job? = null

    private val mockPlayers = mutableListOf<Player>()
    private val killFeedList = mutableListOf<KillEvent>()
    private val checkedInPlayerIds = mutableSetOf<String>() // viral check-in tracking

    private val zoneNames = listOf("Zone A", "Zone B", "Zone C", "Zone D", "Zone E", "Central")

    // --- Public API ---

    fun registerForGame(config: GameConfig, walletAddress: String, startTime: Long, assignedPlayerNumber: Int = 0) {
        val maxNum = config.maxPlayers + 1
        val playerNumber = if (assignedPlayerNumber > 0) assignedPlayerNumber else Random.nextInt(1, maxNum)

        val currentPlayer = Player(
            id = "player_$playerNumber",
            number = playerNumber,
            walletAddress = walletAddress
        )

        // Generate mock players
        mockPlayers.clear()
        val usedNumbers = mutableSetOf(playerNumber)
        for (i in 1 until config.maxPlayers) {
            var num: Int
            do { num = Random.nextInt(1, maxNum) } while (num in usedNumbers)
            usedNumbers.add(num)
            mockPlayers.add(
                Player(
                    id = "player_$num",
                    number = num,
                    walletAddress = "0x${UUID.randomUUID().toString().replace("-", "").take(40)}"
                )
            )
        }

        killFeedList.clear()
        checkedInPlayerIds.clear()

        // Bootstrap: first mock player is auto-checked-in (represents the organizer)
        if (mockPlayers.isNotEmpty()) {
            checkedInPlayerIds.add(mockPlayers.first().id)
        }

        _state.value = GameState(
            phase = GamePhase.REGISTERED,
            config = config,
            currentPlayer = currentPlayer,
            currentTarget = null, // target assigned when game starts
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

        val initialCheckedInNumbers = mockPlayers
            .filter { it.id in checkedInPlayerIds }
            .map { it.number }
            .toSet()

        _state.value = current.copy(
            phase = GamePhase.CHECK_IN,
            checkInTimeRemainingSeconds = current.config.checkInDurationMinutes * 60,
            checkedInCount = checkedInPlayerIds.size,
            checkedInPlayerNumbers = initialCheckedInNumbers
        )
        _events.tryEmit(GameEvent.CheckInStarted)
        startCheckInTimer()
    }

    fun processCheckInScan(scannedPayload: String): CheckInResult {
        val current = _state.value ?: return CheckInResult.NoGame
        if (current.phase != GamePhase.CHECK_IN) return CheckInResult.WrongPhase
        if (current.checkInVerified) return CheckInResult.AlreadyVerified

        val parts = scannedPayload.split(":")
        val scannedPlayerId = if (parts.size >= 3) parts[2] else scannedPayload

        // Can't scan yourself
        if (scannedPlayerId == current.currentPlayer.id) {
            return CheckInResult.ScanYourself
        }

        // Check if scanned player is a registered player
        val scannedPlayer = mockPlayers.find { it.id == scannedPlayerId }
        if (scannedPlayer == null) {
            return CheckInResult.UnknownPlayer
        }

        // Viral check-in: scanned player must already be checked in
        if (scannedPlayerId !in checkedInPlayerIds) {
            return CheckInResult.PlayerNotCheckedIn
        }

        // Verified! Add current player to checked-in set
        checkedInPlayerIds.add(current.currentPlayer.id)
        val checkedInNumbers = mockPlayers
            .filter { it.id in checkedInPlayerIds }
            .map { it.number }
            .toSet() + current.currentPlayer.number
        _state.value = current.copy(
            checkInVerified = true,
            checkedInCount = checkedInPlayerIds.size,
            checkedInPlayerNumbers = checkedInNumbers
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

    fun startGame() {
        completeCheckInAndStartGame()
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

        // Kill confirmed
        val killEvent = KillEvent(
            id = UUID.randomUUID().toString(),
            hunterNumber = current.currentPlayer.number,
            targetNumber = target.player.number,
            timestamp = System.currentTimeMillis(),
            zone = zoneNames.random()
        )
        killFeedList.add(0, killEvent)

        // Remove target from mock players
        mockPlayers.removeAll { it.id == target.player.id }

        val newTarget = pickRandomTarget()
        val newKillCount = current.currentPlayer.killCount + 1
        val newRemaining = current.playersRemaining - 1

        _state.value = current.copy(
            currentPlayer = current.currentPlayer.copy(killCount = newKillCount),
            currentTarget = newTarget?.let { Target(it, System.currentTimeMillis()) },
            playersRemaining = newRemaining,
            killFeed = killFeedList.toList()
        )

        _events.tryEmit(GameEvent.KillConfirmed(killEvent))
        updateLeaderboard()

        if (newRemaining <= 1) {
            endGame()
        }

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

    fun markItemUsed(itemId: String) {
        val current = _state.value ?: return
        _state.value = current.copy(usedItems = current.usedItems + itemId)
    }

    fun activateGhostMode() {
        val current = _state.value ?: return
        _state.value = current.copy(
            ghostModeActive = true,
            ghostModeExpiresAt = System.currentTimeMillis() + 2 * 60 * 1000
        )
    }

    fun debugVerifyCheckIn(): CheckInResult {
        val current = _state.value ?: return CheckInResult.NoGame
        val checkedInPlayer = checkedInPlayerIds.firstOrNull() ?: return CheckInResult.NoGame
        return processCheckInScan(checkedInPlayer)
    }

    fun debugScanTarget(): KillResult {
        val current = _state.value ?: return KillResult.NoGame
        val target = current.currentTarget ?: return KillResult.NoTarget
        return processKill(target.player.id)
    }

    // --- Debug Controls ---

    fun debugTriggerElimination() {
        val current = _state.value ?: return
        val hunterNumber = Random.nextInt(1, 101)
        val killEvent = KillEvent(
            id = UUID.randomUUID().toString(),
            hunterNumber = hunterNumber,
            targetNumber = current.currentPlayer.number,
            timestamp = System.currentTimeMillis(),
            zone = zoneNames.random()
        )
        killFeedList.add(0, killEvent)
        _state.value = current.copy(
            phase = GamePhase.ELIMINATED,
            killFeed = killFeedList.toList()
        )
        updateLeaderboard()
        _events.tryEmit(GameEvent.Eliminated(hunterNumber))
        stopTimers()
    }

    fun debugTriggerZoneShrink() {
        val current = _state.value ?: return
        val newRadius = (current.currentZoneRadius * 0.7).coerceAtLeast(50.0)
        _state.value = current.copy(currentZoneRadius = newRadius)
        _events.tryEmit(GameEvent.ZoneShrink(newRadius))
    }

    fun debugSetPlayersRemaining(count: Int) {
        val current = _state.value ?: return
        _state.value = current.copy(playersRemaining = count)
    }

    fun debugSkipToEndgame() {
        val current = _state.value ?: return
        _state.value = current.copy(playersRemaining = 2)
        // Next simulated kill will end the game
    }

    fun cleanup() {
        stopTimers()
        checkInTimerJob?.cancel()
        _state.value = null
        mockPlayers.clear()
        killFeedList.clear()
        checkedInPlayerIds.clear()
    }

    // --- Internal ---

    private fun startCheckInTimer() {
        checkInTimerJob?.cancel()
        checkInTimerJob = scope.launch {
            // Simulate mock players checking in virally over time
            while (isActive) {
                delay(1000)
                val current = _state.value ?: break
                if (current.phase != GamePhase.CHECK_IN) break

                val remaining = current.checkInTimeRemainingSeconds - 1

                // Viral simulation: checked-in players enable others to check in
                // More checked-in players = faster spread
                val spreadChance = 0.05f + (checkedInPlayerIds.size.toFloat() / mockPlayers.size) * 0.2f
                val uncheckedPlayers = mockPlayers.filter { it.id !in checkedInPlayerIds }
                if (uncheckedPlayers.isNotEmpty() && Random.nextFloat() < spreadChance) {
                    checkedInPlayerIds.add(uncheckedPlayers.random().id)
                }

                val myCheckIn = if (current.checkInVerified) 1 else 0

                if (remaining <= 0) {
                    completeCheckInAndStartGame()
                    break
                }
                val checkedInNumbers = mockPlayers
                    .filter { it.id in checkedInPlayerIds }
                    .map { it.number }
                    .toSet()

                _state.value = current.copy(
                    checkInTimeRemainingSeconds = remaining,
                    checkedInCount = checkedInPlayerIds.size + myCheckIn,
                    checkedInPlayerNumbers = if (current.checkInVerified)
                        checkedInNumbers + current.currentPlayer.number
                    else checkedInNumbers
                )
            }
        }
    }

    private fun completeCheckInAndStartGame() {
        val current = _state.value ?: return
        val target = pickRandomTarget()
        _state.value = current.copy(
            phase = GamePhase.ACTIVE,
            currentTarget = target?.let { Target(it, System.currentTimeMillis()) },
            nextShrinkSeconds = if (current.config.shrinkSchedule.isNotEmpty())
                current.config.shrinkSchedule[0].atMinute * 60 else 0
        )
        updateLeaderboard()
        startTimers()
        _events.tryEmit(GameEvent.GameStarted)
    }

    private fun startTimers() {
        timerJob?.cancel()
        simulationJob?.cancel()

        // Main tick timer (every second)
        timerJob = scope.launch {
            while (isActive) {
                delay(1000)
                tick()
            }
        }

        // Simulation (random kills by other players)
        simulationJob = scope.launch {
            delay(10_000) // Initial grace period
            while (isActive) {
                val interval = simulationInterval()
                delay(interval)
                simulateRandomKill()
            }
        }
    }

    private fun stopTimers() {
        timerJob?.cancel()
        simulationJob?.cancel()
        checkInTimerJob?.cancel()
    }

    private fun tick() {
        val current = _state.value ?: return
        if (current.phase != GamePhase.ACTIVE) return

        val elapsed = current.gameTimeElapsedSeconds + 1
        val newShrinkSeconds = (current.nextShrinkSeconds - 1).coerceAtLeast(0)

        var newRadius = current.currentZoneRadius
        var nextShrink = newShrinkSeconds

        // Check zone shrink schedule
        val elapsedMinutes = (elapsed / 60).toInt()
        for (shrink in current.config.shrinkSchedule) {
            if (elapsedMinutes == shrink.atMinute && current.currentZoneRadius > shrink.newRadiusMeters) {
                newRadius = shrink.newRadiusMeters
                val nextSchedule = current.config.shrinkSchedule.firstOrNull { it.atMinute > elapsedMinutes }
                nextShrink = if (nextSchedule != null) (nextSchedule.atMinute * 60 - elapsed).toInt() else 0
                _events.tryEmit(GameEvent.ZoneShrink(newRadius))
                break
            }
        }

        // Ghost mode expiry
        var ghostModeActive = current.ghostModeActive
        if (ghostModeActive && System.currentTimeMillis() >= current.ghostModeExpiresAt) {
            ghostModeActive = false
        }

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

        // Check game end
        if (elapsed >= current.config.durationMinutes * 60L) {
            endGame()
            return
        }

        _state.value = current.copy(
            gameTimeElapsedSeconds = elapsed,
            currentZoneRadius = newRadius,
            nextShrinkSeconds = nextShrink,
            ghostModeActive = ghostModeActive,
            outOfZoneSeconds = newOutOfZoneSeconds
        )
    }

    private fun simulateRandomKill() {
        val current = _state.value ?: return
        if (current.phase != GamePhase.ACTIVE) return
        if (mockPlayers.size < 2) return

        val victim = mockPlayers.random()
        val hunter = mockPlayers.filter { it.id != victim.id }.randomOrNull() ?: return

        mockPlayers.remove(victim)

        val killEvent = KillEvent(
            id = UUID.randomUUID().toString(),
            hunterNumber = hunter.number,
            targetNumber = victim.number,
            timestamp = System.currentTimeMillis(),
            zone = zoneNames.random()
        )
        killFeedList.add(0, killEvent)

        val newRemaining = current.playersRemaining - 1

        // If the victim was our target, reassign
        var newTarget = current.currentTarget
        if (current.currentTarget?.player?.id == victim.id) {
            val replacement = pickRandomTarget()
            newTarget = replacement?.let { Target(it, System.currentTimeMillis()) }
            _events.tryEmit(GameEvent.TargetReassigned(newTarget?.player))
        }

        _state.value = current.copy(
            playersRemaining = newRemaining,
            currentTarget = newTarget,
            killFeed = killFeedList.take(50)
        )
        _events.tryEmit(GameEvent.KillFeedUpdate(killEvent))
        updateLeaderboard()

        if (newRemaining <= 1) {
            endGame()
        }
    }

    private fun endGame() {
        val current = _state.value ?: return
        _state.value = current.copy(phase = GamePhase.ENDED)
        _events.tryEmit(GameEvent.GameEnded)
        stopTimers()
    }

    private fun pickRandomTarget(): Player? {
        return mockPlayers.filter { it.isAlive }.randomOrNull()
    }

    private fun buildLeaderboard(): List<LeaderboardEntry> {
        val current = _state.value ?: return emptyList()

        // Count kills per player from kill feed
        val killCounts = mutableMapOf<Int, Int>()
        val eliminatedNumbers = mutableSetOf<Int>()
        for (event in killFeedList) {
            killCounts[event.hunterNumber] = (killCounts[event.hunterNumber] ?: 0) + 1
            eliminatedNumbers.add(event.targetNumber)
        }

        val entries = mutableListOf<LeaderboardEntry>()

        // Current player
        entries.add(
            LeaderboardEntry(
                rank = 0,
                playerNumber = current.currentPlayer.number,
                kills = current.currentPlayer.killCount,
                isAlive = current.phase != GamePhase.ELIMINATED,
                isCurrentPlayer = true
            )
        )

        // All mock players (alive + dead)
        for (player in mockPlayers) {
            entries.add(
                LeaderboardEntry(
                    rank = 0,
                    playerNumber = player.number,
                    kills = killCounts[player.number] ?: 0,
                    isAlive = player.number !in eliminatedNumbers
                )
            )
        }

        // Also add eliminated players no longer in mockPlayers
        for (number in eliminatedNumbers) {
            if (entries.none { it.playerNumber == number }) {
                entries.add(
                    LeaderboardEntry(
                        rank = 0,
                        playerNumber = number,
                        kills = killCounts[number] ?: 0,
                        isAlive = false
                    )
                )
            }
        }

        // Sort: alive first (by kills desc), then dead (by kills desc)
        val sorted = entries.sortedWith(
            compareByDescending<LeaderboardEntry> { it.isAlive }
                .thenByDescending { it.kills }
                .thenBy { it.playerNumber }
        )

        return sorted.mapIndexed { index, entry -> entry.copy(rank = index + 1) }
    }

    private fun updateLeaderboard() {
        val current = _state.value ?: return
        _state.value = current.copy(leaderboard = buildLeaderboard())
    }

    private fun simulationInterval(): Long {
        val remaining = _state.value?.playersRemaining ?: 100
        return when {
            remaining > 50 -> Random.nextLong(30_000, 90_000)
            remaining > 25 -> Random.nextLong(15_000, 45_000)
            remaining > 10 -> Random.nextLong(8_000, 25_000)
            remaining > 5 -> Random.nextLong(5_000, 15_000)
            else -> Random.nextLong(3_000, 8_000)
        }
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
    data object GameStarted : GameEvent()
    data object RefundClaimed : GameEvent()
}
