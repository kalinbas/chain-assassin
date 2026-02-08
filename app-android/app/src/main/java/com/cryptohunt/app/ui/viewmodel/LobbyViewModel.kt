package com.cryptohunt.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptohunt.app.domain.game.GameEngine
import com.cryptohunt.app.domain.model.*
import com.cryptohunt.app.domain.wallet.WalletManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

data class GameListItem(
    val config: GameConfig,
    val currentPlayers: Int,
    val locationName: String,
    val startTime: Long = 0L
)

data class GameHistoryItem(
    val config: GameConfig,
    val phase: GamePhase,
    val kills: Int,
    val rank: Int,
    val survivalSeconds: Long,
    val playersTotal: Int,
    val leaderboard: List<LeaderboardEntry>,
    val playedAt: Long,
    val prizeEth: Double = 0.0,
    val claimed: Boolean = false
)

@HiltViewModel
class LobbyViewModel @Inject constructor(
    private val gameEngine: GameEngine,
    private val walletManager: WalletManager
) : ViewModel() {

    val walletState: StateFlow<WalletState> = walletManager.state
    val gameState: StateFlow<GameState?> = gameEngine.state

    private val _games = MutableStateFlow(generateMockGames())
    val games: StateFlow<List<GameListItem>> = _games.asStateFlow()

    private val _selectedGame = MutableStateFlow<GameListItem?>(null)
    val selectedGame: StateFlow<GameListItem?> = _selectedGame.asStateFlow()

    private val _gameHistory = MutableStateFlow(generateMockHistory())
    val gameHistory: StateFlow<List<GameHistoryItem>> = _gameHistory.asStateFlow()

    private var lastSavedGameId: String? = null

    init {
        viewModelScope.launch {
            gameEngine.state.filterNotNull().collect { state ->
                if (state.phase in listOf(GamePhase.ELIMINATED, GamePhase.ENDED)) {
                    if (state.config.id != lastSavedGameId) {
                        saveGameToHistory(state)
                        lastSavedGameId = state.config.id
                    }
                }
            }
        }
    }

    private fun saveGameToHistory(state: GameState) {
        val rank = if (state.phase == GamePhase.ENDED && state.playersRemaining <= 1) {
            1
        } else {
            (state.playersRemaining + 1).coerceAtLeast(2)
        }
        val prizePool = state.config.entryFee * state.config.maxPlayers * 0.9
        val prize = when (rank) {
            1 -> prizePool * 0.4
            2 -> prizePool * 0.15
            3 -> prizePool * 0.1
            else -> 0.0
        }
        val item = GameHistoryItem(
            config = state.config,
            phase = state.phase,
            kills = state.currentPlayer.killCount,
            rank = rank,
            survivalSeconds = state.gameTimeElapsedSeconds,
            playersTotal = state.config.maxPlayers,
            leaderboard = state.leaderboard,
            playedAt = System.currentTimeMillis(),
            prizeEth = prize,
            claimed = false
        )
        _gameHistory.value = listOf(item) + _gameHistory.value
    }

    fun getHistoryItem(index: Int): GameHistoryItem? {
        return _gameHistory.value.getOrNull(index)
    }

    fun claimPrize(index: Int): Boolean {
        val history = _gameHistory.value.toMutableList()
        val item = history.getOrNull(index) ?: return false
        if (item.prizeEth <= 0.0 || item.claimed) return false
        history[index] = item.copy(claimed = true)
        _gameHistory.value = history
        walletManager.addBalance(item.prizeEth)
        return true
    }

    fun selectGame(gameId: String) {
        _selectedGame.value = _games.value.find { it.config.id == gameId }
    }

    fun registerForGame(gameId: String): Boolean {
        val game = _games.value.find { it.config.id == gameId } ?: return false
        if (!walletManager.payEntryFee(game.config.entryFee)) return false
        gameEngine.registerForGame(game.config, walletManager.getAddress(), game.startTime)
        return true
    }

    fun beginCheckIn() {
        gameEngine.beginCheckIn()
    }

    fun startGame() {
        gameEngine.startGame()
    }

    fun claimRefund(): Boolean {
        val game = _selectedGame.value ?: return false
        val refunded = gameEngine.claimRefund()
        if (refunded) {
            walletManager.addBalance(game.config.entryFee)
        }
        return refunded
    }

    fun isRegisteredForGame(gameId: String): Boolean {
        val state = gameEngine.state.value ?: return false
        return state.config.id == gameId &&
                state.phase in listOf(GamePhase.REGISTERED, GamePhase.CHECK_IN)
    }

    fun shortenedAddress(): String = walletManager.shortenedAddress()

    private fun generateMockHistory(): List<GameHistoryItem> {
        val cal = Calendar.getInstance()
        // Past game 1: won 2 days ago, unclaimed prize
        cal.add(Calendar.DAY_OF_YEAR, -2)
        val time1 = cal.timeInMillis
        // Past game 2: lost 5 days ago
        cal.timeInMillis = System.currentTimeMillis()
        cal.add(Calendar.DAY_OF_YEAR, -5)
        val time2 = cal.timeInMillis
        // Past game 3: 3rd place 8 days ago, unclaimed prize
        cal.timeInMillis = System.currentTimeMillis()
        cal.add(Calendar.DAY_OF_YEAR, -8)
        val time3 = cal.timeInMillis
        // Past game 4: cancelled 12 days ago, refund unclaimed
        cal.timeInMillis = System.currentTimeMillis()
        cal.add(Calendar.DAY_OF_YEAR, -12)
        val time4 = cal.timeInMillis

        val config1 = GameConfig(
            id = "past1",
            name = "Chain-Assassin CDMX #47",
            entryFee = 0.05,
            minPlayers = 20,
            maxPlayers = 100,
            zoneCenterLat = 19.4326,
            zoneCenterLng = -99.1332,
            initialRadiusMeters = 2000.0,
            shrinkSchedule = listOf(
                ZoneShrink(10, 1500.0),
                ZoneShrink(20, 1000.0),
                ZoneShrink(30, 600.0),
                ZoneShrink(40, 300.0)
            ),
            durationMinutes = 60,
            checkInDurationMinutes = 10
        )
        val config2 = GameConfig(
            id = "past2",
            name = "Chain-Assassin NYC #1",
            entryFee = 0.1,
            minPlayers = 30,
            maxPlayers = 100,
            zoneCenterLat = 40.7580,
            zoneCenterLng = -73.9855,
            initialRadiusMeters = 1500.0,
            shrinkSchedule = listOf(
                ZoneShrink(8, 1200.0),
                ZoneShrink(16, 800.0),
                ZoneShrink(24, 500.0)
            ),
            durationMinutes = 45,
            checkInDurationMinutes = 10
        )
        val config3 = GameConfig(
            id = "past3",
            name = "Chain-Assassin Berlin #5",
            entryFee = 0.02,
            minPlayers = 10,
            maxPlayers = 50,
            zoneCenterLat = 52.5200,
            zoneCenterLng = 13.4050,
            initialRadiusMeters = 2500.0,
            shrinkSchedule = listOf(
                ZoneShrink(15, 2000.0),
                ZoneShrink(30, 1200.0),
                ZoneShrink(45, 600.0)
            ),
            durationMinutes = 60,
            checkInDurationMinutes = 15
        )

        val config4 = GameConfig(
            id = "past4",
            name = "Chain-Assassin Tokyo #1",
            entryFee = 0.08,
            minPlayers = 25,
            maxPlayers = 100,
            zoneCenterLat = 35.6762,
            zoneCenterLng = 139.6503,
            initialRadiusMeters = 1800.0,
            shrinkSchedule = listOf(
                ZoneShrink(10, 1400.0),
                ZoneShrink(20, 900.0),
                ZoneShrink(30, 500.0)
            ),
            durationMinutes = 50,
            checkInDurationMinutes = 10
        )

        val prizePool1 = config1.entryFee * config1.maxPlayers * 0.9
        val prizePool3 = config3.entryFee * config3.maxPlayers * 0.9

        return listOf(
            // Won game — prize unclaimed
            GameHistoryItem(
                config = config1,
                phase = GamePhase.ENDED,
                kills = 7,
                rank = 1,
                survivalSeconds = 3420,
                playersTotal = 100,
                leaderboard = listOf(
                    LeaderboardEntry(1, 42, 7, isAlive = true, isCurrentPlayer = true),
                    LeaderboardEntry(2, 17, 5, isAlive = false),
                    LeaderboardEntry(3, 88, 4, isAlive = false),
                    LeaderboardEntry(4, 55, 3, isAlive = false),
                    LeaderboardEntry(5, 23, 3, isAlive = false),
                    LeaderboardEntry(6, 91, 2, isAlive = false),
                    LeaderboardEntry(7, 12, 2, isAlive = false),
                    LeaderboardEntry(8, 5, 1, isAlive = false),
                    LeaderboardEntry(9, 33, 1, isAlive = false),
                    LeaderboardEntry(10, 67, 0, isAlive = false)
                ),
                playedAt = time1,
                prizeEth = prizePool1 * 0.4,
                claimed = false
            ),
            // Lost game — eliminated at rank 15
            GameHistoryItem(
                config = config2,
                phase = GamePhase.ELIMINATED,
                kills = 2,
                rank = 15,
                survivalSeconds = 1560,
                playersTotal = 100,
                leaderboard = listOf(
                    LeaderboardEntry(1, 7, 9, isAlive = true),
                    LeaderboardEntry(2, 31, 6, isAlive = false),
                    LeaderboardEntry(3, 88, 5, isAlive = false),
                    LeaderboardEntry(4, 19, 4, isAlive = false),
                    LeaderboardEntry(5, 55, 3, isAlive = false),
                    LeaderboardEntry(6, 44, 3, isAlive = false),
                    LeaderboardEntry(7, 12, 2, isAlive = false),
                    LeaderboardEntry(8, 67, 2, isAlive = false),
                    LeaderboardEntry(9, 33, 2, isAlive = false),
                    LeaderboardEntry(10, 91, 2, isAlive = false),
                    LeaderboardEntry(15, 42, 2, isAlive = false, isCurrentPlayer = true)
                ),
                playedAt = time2,
                prizeEth = 0.0,
                claimed = false
            ),
            // 3rd place — prize unclaimed
            GameHistoryItem(
                config = config3,
                phase = GamePhase.ENDED,
                kills = 4,
                rank = 3,
                survivalSeconds = 3300,
                playersTotal = 50,
                leaderboard = listOf(
                    LeaderboardEntry(1, 8, 6, isAlive = true),
                    LeaderboardEntry(2, 19, 5, isAlive = false),
                    LeaderboardEntry(3, 42, 4, isAlive = false, isCurrentPlayer = true),
                    LeaderboardEntry(4, 31, 3, isAlive = false),
                    LeaderboardEntry(5, 44, 3, isAlive = false),
                    LeaderboardEntry(6, 7, 2, isAlive = false),
                    LeaderboardEntry(7, 15, 1, isAlive = false),
                    LeaderboardEntry(8, 22, 1, isAlive = false),
                    LeaderboardEntry(9, 36, 0, isAlive = false),
                    LeaderboardEntry(10, 48, 0, isAlive = false)
                ),
                playedAt = time3,
                prizeEth = prizePool3 * 0.1,
                claimed = false
            ),
            // Cancelled game — not enough players, refund unclaimed
            GameHistoryItem(
                config = config4,
                phase = GamePhase.CANCELLED,
                kills = 0,
                rank = 0,
                survivalSeconds = 0,
                playersTotal = 18,
                leaderboard = emptyList(),
                playedAt = time4,
                prizeEth = config4.entryFee,
                claimed = false
            )
        )
    }

    private fun generateMockGames(): List<GameListItem> {
        val cal = Calendar.getInstance()
        cal.add(Calendar.HOUR_OF_DAY, 2)
        val time1 = cal.timeInMillis

        return listOf(
            GameListItem(
                config = GameConfig(
                    id = "game1",
                    name = "Chain-Assassin CDMX #48",
                    entryFee = 0.05,
                    minPlayers = 20,
                    maxPlayers = 100,
                    zoneCenterLat = 19.4326,
                    zoneCenterLng = -99.1332,
                    initialRadiusMeters = 2000.0,
                    shrinkSchedule = listOf(
                        ZoneShrink(10, 1500.0),
                        ZoneShrink(20, 1000.0),
                        ZoneShrink(30, 600.0),
                        ZoneShrink(40, 300.0)
                    ),
                    durationMinutes = 60,
                    checkInDurationMinutes = 10
                ),
                currentPlayers = 87,
                locationName = "Mexico City Centro",
                startTime = time1
            )
        )
    }
}
