package com.cryptohunt.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptohunt.app.domain.chain.ContractService
import com.cryptohunt.app.domain.chain.OnChainPhase
import com.cryptohunt.app.domain.game.GameEngine
import com.cryptohunt.app.domain.location.LocationTracker
import com.cryptohunt.app.domain.model.*
import com.cryptohunt.app.domain.server.GameServerClient
import com.cryptohunt.app.domain.server.ServerMapper
import com.cryptohunt.app.domain.wallet.WalletManager
import android.util.Log
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.math.BigInteger
import javax.inject.Inject

data class GameListItem(
    val config: GameConfig,
    val currentPlayers: Int,
    val locationName: String,
    val startTime: Long = 0L,
    val onChainPhase: OnChainPhase = OnChainPhase.REGISTRATION,
    val totalCollected: BigInteger = BigInteger.ZERO,
    val isPlayerRegistered: Boolean = false,
    val playerNumber: Int = 0
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
    val claimed: Boolean = false,
    val claimableWei: BigInteger = BigInteger.ZERO,
    val isCancelled: Boolean = false,
    val gameId: Int = 0,
    val participated: Boolean = true,
    val winner1: Int = 0,      // playerNumber (0 = none)
    val winner2: Int = 0,
    val winner3: Int = 0,
    val topKiller: Int = 0
)

@HiltViewModel
class LobbyViewModel @Inject constructor(
    private val gameEngine: GameEngine,
    private val walletManager: WalletManager,
    private val contractService: ContractService,
    private val locationTracker: LocationTracker,
    private val serverClient: GameServerClient
) : ViewModel() {

    val walletState: StateFlow<WalletState> = walletManager.state
    val gameState: StateFlow<GameState?> = gameEngine.state
    val locationState: StateFlow<LocationState> = locationTracker.state

    private val _games = MutableStateFlow<List<GameListItem>>(emptyList())
    val games: StateFlow<List<GameListItem>> = _games.asStateFlow()

    private val _selectedGame = MutableStateFlow<GameListItem?>(null)
    val selectedGame: StateFlow<GameListItem?> = _selectedGame.asStateFlow()

    private val _gameHistory = MutableStateFlow<List<GameHistoryItem>>(emptyList())
    val gameHistory: StateFlow<List<GameHistoryItem>> = _gameHistory.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _txPending = MutableStateFlow(false)
    val txPending: StateFlow<Boolean> = _txPending.asStateFlow()

    private var lastSavedGameId: String? = null

    init {
        loadGames()
        loadGameHistory()
        // Fetch wallet balance on startup so the lobby balance chip is populated
        viewModelScope.launch { walletManager.refreshBalance() }

        // Watch for game-over events from active gameplay to save to history
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

        // Forward server messages to GameEngine (so phase transitions reach the UI)
        viewModelScope.launch {
            serverClient.serverMessages.collect { msg ->
                Log.d("LobbyViewModel", "Server: $msg")
                gameEngine.processServerMessage(msg)
            }
        }
    }

    fun loadGames() {
        viewModelScope.launch {
            _isLoading.value = true
            loadGamesInternal()
            _isLoading.value = false
        }
    }

    private suspend fun loadGamesInternal() {
        _error.value = null
        try {
            val allGames = serverClient.fetchAllGames() ?: run {
                _error.value = "Failed to load games from server"
                return
            }
            val address = walletManager.getAddress()
            val items = mutableListOf<GameListItem>()

            for (game in allGames) {
                try {
                    val appConfig = ServerMapper.toGameConfig(game)
                    val phase = OnChainPhase.fromInt(game.phase)
                    val totalCollected = java.math.BigInteger(game.totalCollected)

                    val nowMs = System.currentTimeMillis()
                    if (phase == OnChainPhase.REGISTRATION && appConfig.registrationDeadline > nowMs) {
                        // Show REGISTRATION games in the upcoming list
                        val playerInfo = if (address.isNotEmpty()) {
                            try { serverClient.fetchPlayerInfo(game.gameId, address) } catch (_: Exception) { null }
                        } else null

                        items.add(
                            GameListItem(
                                config = appConfig,
                                currentPlayers = game.playerCount,
                                locationName = appConfig.name,
                                startTime = appConfig.gameDate,
                                onChainPhase = phase,
                                totalCollected = totalCollected,
                                isPlayerRegistered = playerInfo?.registered == true,
                                playerNumber = playerInfo?.playerNumber ?: 0
                            )
                        )
                    } else if (phase == OnChainPhase.ACTIVE && address.isNotEmpty()) {
                        // Check if we're a registered player in this active game
                        val playerInfo = try {
                            serverClient.fetchPlayerInfo(game.gameId, address)
                        } catch (_: Exception) { null }

                        if (playerInfo != null && playerInfo.registered) {
                            // Restore GameEngine state so the lobby shows the active game card
                            val existingState = gameEngine.state.value
                            if (existingState == null || existingState.config.id != appConfig.id) {
                                gameEngine.registerForGame(
                                    appConfig, address, appConfig.gameDate,
                                    assignedPlayerNumber = playerInfo.playerNumber
                                )
                                // Server connection deferred to when user navigates to the game screen
                            }
                            break // Player can only be in one active game at a time
                        }
                    }
                } catch (_: Exception) {
                    // Skip games that fail to parse
                }
            }
            _games.value = items
        } catch (e: Exception) {
            _error.value = "Failed to load games: ${e.message}"
        }
    }

    fun loadGameHistory() {
        viewModelScope.launch { loadGameHistoryInternal() }
    }

    private suspend fun loadGameHistoryInternal() {
        val address = walletManager.getAddress()
        try {
            val allGames = serverClient.fetchAllGames() ?: return
            val items = mutableListOf<GameHistoryItem>()

            for (game in allGames) {
                try {
                    val phase = OnChainPhase.fromInt(game.phase)

                    // Only include ended or cancelled games in history
                    if (phase != OnChainPhase.ENDED && phase != OnChainPhase.CANCELLED) continue

                    val appConfig = ServerMapper.toGameConfig(game)

                    val isCancelled = phase == OnChainPhase.CANCELLED
                    val gamePhase = if (isCancelled) GamePhase.CANCELLED else GamePhase.ENDED

                    // Check if current player participated
                    val playerInfo = if (address.isNotEmpty()) {
                        try { serverClient.fetchPlayerInfo(game.gameId, address) } catch (_: Exception) { null }
                    } else null
                    val participated = playerInfo?.registered == true

                    // Only fetch claimable if player participated (stays on-chain)
                    val claimable = if (participated && address.isNotEmpty()) {
                        try { contractService.getClaimableAmount(game.gameId, address) } catch (_: Exception) { BigInteger.ZERO }
                    } else BigInteger.ZERO
                    val claimableEth = org.web3j.utils.Convert.fromWei(
                        java.math.BigDecimal(claimable),
                        org.web3j.utils.Convert.Unit.ETHER
                    ).toDouble()

                    // Determine rank based on winners (only relevant if participated)
                    val pNum = playerInfo?.playerNumber ?: 0
                    val rank = if (participated && pNum != 0) {
                        when (pNum) {
                            game.winner1 -> 1
                            game.winner2 -> 2
                            game.winner3 -> 3
                            else -> 0
                        }
                    } else 0

                    items.add(
                        GameHistoryItem(
                            config = appConfig,
                            phase = gamePhase,
                            kills = playerInfo?.kills ?: 0,
                            rank = rank,
                            survivalSeconds = 0L,
                            playersTotal = game.playerCount,
                            leaderboard = emptyList(),
                            playedAt = game.gameDate * 1000,
                            prizeEth = claimableEth,
                            claimed = if (participated) (playerInfo?.claimed == true || claimable == BigInteger.ZERO) else true,
                            claimableWei = claimable,
                            isCancelled = isCancelled,
                            gameId = game.gameId,
                            participated = participated,
                            winner1 = game.winner1,
                            winner2 = game.winner2,
                            winner3 = game.winner3,
                            topKiller = game.topKiller
                        )
                    )
                } catch (_: Exception) {
                    // Skip games that fail to parse
                }
            }
            // Sort by date descending
            _gameHistory.value = items.sortedByDescending { it.playedAt }
        } catch (_: Exception) {
            // Silently fail — history stays empty
        }
    }

    private fun saveGameToHistory(state: GameState) {
        // After local gameplay ends, reload history from server
        loadGameHistory()
    }

    fun getHistoryItem(index: Int): GameHistoryItem? {
        return _gameHistory.value.getOrNull(index)
    }

    fun claimPrize(index: Int, onSuccess: () -> Unit = {}, onError: (String) -> Unit = {}) {
        val item = _gameHistory.value.getOrNull(index) ?: return
        if (item.claimed || item.claimableWei == BigInteger.ZERO) return
        val credentials = walletManager.getCredentials() ?: return

        viewModelScope.launch {
            _txPending.value = true
            try {
                val txHash = if (item.isCancelled) {
                    contractService.claimRefund(item.gameId, credentials)
                } else {
                    contractService.claimPrize(item.gameId, credentials)
                }
                val receipt = contractService.waitForReceipt(txHash)
                if (receipt != null && receipt.isStatusOK) {
                    // Update local state
                    val history = _gameHistory.value.toMutableList()
                    history[index] = item.copy(claimed = true, claimableWei = BigInteger.ZERO)
                    _gameHistory.value = history
                    // Refresh balance
                    walletManager.refreshBalance()
                    onSuccess()
                } else {
                    onError("Transaction failed")
                }
            } catch (e: Exception) {
                onError(e.message ?: "Claim failed")
            } finally {
                _txPending.value = false
            }
        }
    }

    fun selectGame(gameId: String) {
        // Check local list first
        val local = _games.value.find { it.config.id == gameId }
        if (local != null) {
            _selectedGame.value = local
            return
        }
        // Load from server if not in local list (e.g. fresh ViewModel instance)
        viewModelScope.launch {
            try {
                val gameIdInt = gameId.toIntOrNull() ?: return@launch
                val game = serverClient.fetchGameDetail(gameIdInt) ?: run {
                    _error.value = "Game not found"
                    return@launch
                }
                val appConfig = ServerMapper.toGameConfig(game)
                val phase = OnChainPhase.fromInt(game.phase)
                val address = walletManager.getAddress()
                val playerInfo = if (address.isNotEmpty()) {
                    try { serverClient.fetchPlayerInfo(gameIdInt, address) } catch (_: Exception) { null }
                } else null
                _selectedGame.value = GameListItem(
                    config = appConfig,
                    currentPlayers = game.playerCount,
                    locationName = appConfig.name,
                    startTime = appConfig.gameDate,
                    onChainPhase = phase,
                    totalCollected = java.math.BigInteger(game.totalCollected),
                    isPlayerRegistered = playerInfo?.registered == true,
                    playerNumber = playerInfo?.playerNumber ?: 0
                )
            } catch (e: Exception) {
                _error.value = "Failed to load game: ${e.message}"
            }
        }
    }

    fun registerForGame(
        gameId: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val game = _games.value.find { it.config.id == gameId }
            ?: _selectedGame.value?.takeIf { it.config.id == gameId }
            ?: return
        val credentials = walletManager.getCredentials()
        if (credentials == null) {
            onError("Wallet not connected")
            return
        }

        viewModelScope.launch {
            _txPending.value = true
            try {
                val gameIdInt = gameId.toInt()
                val txHash = contractService.register(
                    gameId = gameIdInt,
                    entryFeeWei = game.config.entryFeeWei,
                    credentials = credentials
                )
                val receipt = contractService.waitForReceipt(txHash)
                if (receipt != null && receipt.isStatusOK) {
                    // Refresh wallet balance
                    walletManager.refreshBalance()
                    // Get the on-chain player number
                    val playerInfo = contractService.getPlayerInfo(gameIdInt, walletManager.getAddress())
                    // Register in local game engine for gameplay simulation
                    gameEngine.registerForGame(game.config, walletManager.getAddress(), game.startTime, assignedPlayerNumber = playerInfo.number)
                    // Update selected game immediately so UI reflects registration
                    _selectedGame.value = game.copy(
                        isPlayerRegistered = true,
                        playerNumber = playerInfo.number,
                        currentPlayers = game.currentPlayers + 1
                    )
                    // Refresh games list to update player count
                    loadGames()
                    onSuccess()
                } else {
                    onError("Transaction failed")
                }
            } catch (e: Exception) {
                onError(e.message ?: "Registration failed")
            } finally {
                _txPending.value = false
            }
        }
    }

    /**
     * Connect to server for receiving phase transition messages.
     * Called from RegisteredDetailScreen so we get game:checkin_started etc.
     */
    fun connectToServer(gameId: String) {
        val gameIdInt = gameId.toIntOrNull() ?: return
        Log.d("LobbyViewModel", "Connecting to server for game $gameIdInt")
        serverClient.connect(gameIdInt)
    }

    fun startLocationTracking() {
        locationTracker.startTracking()
    }

    fun stopLocationTracking() {
        locationTracker.stopTracking()
    }

    fun beginCheckIn() {
        gameEngine.beginCheckIn()
    }

    fun startGame() {
        // Server drives game start — this is a no-op now.
        // Kept for UI compatibility (GameLobbyScreen button).
    }

    /**
     * Restore local game engine state from on-chain data.
     * Called when navigating to RegisteredDetailScreen and no local GameState exists.
     */
    fun ensureRegisteredState(gameId: String) {
        val existing = gameEngine.state.value
        if (existing != null && existing.config.id == gameId &&
            existing.phase in listOf(GamePhase.REGISTERED, GamePhase.CHECK_IN, GamePhase.PREGAME)) {
            return // Already have local state
        }
        // Find in local list or selected game
        val game = _games.value.find { it.config.id == gameId }
            ?: _selectedGame.value?.takeIf { it.config.id == gameId }
            ?: return
        if (game.isPlayerRegistered) {
            gameEngine.registerForGame(
                game.config,
                walletManager.getAddress(),
                game.startTime,
                assignedPlayerNumber = game.playerNumber
            )
        }
    }

    fun isRegisteredForGame(gameId: String): Boolean {
        val state = gameEngine.state.value ?: return false
        return state.config.id == gameId &&
                state.phase in listOf(GamePhase.REGISTERED, GamePhase.CHECK_IN, GamePhase.PREGAME)
    }

    fun shortenedAddress(): String = walletManager.shortenedAddress()

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                // Reload games and history in parallel
                val gamesJob = launch { loadGamesInternal() }
                val historyJob = launch { loadGameHistoryInternal() }
                gamesJob.join()
                historyJob.join()
                walletManager.refreshBalance()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
