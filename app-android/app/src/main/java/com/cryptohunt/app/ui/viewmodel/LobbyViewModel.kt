package com.cryptohunt.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptohunt.app.domain.chain.ChainMapper
import com.cryptohunt.app.domain.chain.ContractService
import com.cryptohunt.app.domain.chain.OnChainPhase
import com.cryptohunt.app.domain.game.GameEngine
import com.cryptohunt.app.domain.location.LocationTracker
import com.cryptohunt.app.domain.model.*
import com.cryptohunt.app.domain.server.GameServerClient
import com.cryptohunt.app.domain.wallet.WalletManager
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
    val winner1: String = "",
    val winner2: String = "",
    val winner3: String = "",
    val topKiller: String = ""
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
            val nextId = contractService.getNextGameId()
            val address = walletManager.getAddress()
            val items = mutableListOf<GameListItem>()
            for (gameId in 1 until nextId) {
                try {
                    val config = contractService.getGameConfig(gameId)
                    val state = contractService.getGameState(gameId)
                    val shrinks = contractService.getZoneShrinks(gameId)
                    val appConfig = ChainMapper.toGameConfig(gameId, config, shrinks)

                    if (state.phase == OnChainPhase.REGISTRATION) {
                        // Show REGISTRATION games in the upcoming list
                        val playerInfo = if (address.isNotEmpty()) {
                            try { contractService.getPlayerInfo(gameId, address) } catch (_: Exception) { null }
                        } else null

                        items.add(
                            GameListItem(
                                config = appConfig,
                                currentPlayers = state.playerCount,
                                locationName = appConfig.name,
                                startTime = appConfig.gameDate,
                                onChainPhase = state.phase,
                                totalCollected = state.totalCollected,
                                isPlayerRegistered = playerInfo?.registered == true,
                                playerNumber = playerInfo?.number ?: 0
                            )
                        )
                    } else if (state.phase == OnChainPhase.ACTIVE && address.isNotEmpty()) {
                        // Check if we're a registered player in this active game
                        val playerInfo = try {
                            contractService.getPlayerInfo(gameId, address)
                        } catch (_: Exception) { null }

                        if (playerInfo != null && playerInfo.registered) {
                            // Restore GameEngine state so the lobby shows the active game card
                            val existingState = gameEngine.state.value
                            if (existingState == null || existingState.config.id != appConfig.id) {
                                gameEngine.registerForGame(
                                    appConfig, address, appConfig.gameDate,
                                    assignedPlayerNumber = playerInfo.number
                                )
                                // Connect to server — auth:success will set the correct phase
                                serverClient.connect(gameId)
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Skip games that fail to load
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
            val nextId = contractService.getNextGameId()
            val items = mutableListOf<GameHistoryItem>()
            for (gameId in 1 until nextId) {
                try {
                    val config = contractService.getGameConfig(gameId)
                    val state = contractService.getGameState(gameId)

                    // Only include ended or cancelled games in history
                    if (state.phase != OnChainPhase.ENDED && state.phase != OnChainPhase.CANCELLED) continue

                    val shrinks = contractService.getZoneShrinks(gameId)
                    val appConfig = ChainMapper.toGameConfig(gameId, config, shrinks)

                    val isCancelled = state.phase == OnChainPhase.CANCELLED
                    val gamePhase = if (isCancelled) GamePhase.CANCELLED else GamePhase.ENDED

                    // Check if current player participated
                    val playerInfo = if (address.isNotEmpty()) {
                        try { contractService.getPlayerInfo(gameId, address) } catch (_: Exception) { null }
                    } else null
                    val participated = playerInfo?.registered == true

                    // Only fetch claimable if player participated
                    val claimable = if (participated && address.isNotEmpty()) {
                        try { contractService.getClaimableAmount(gameId, address) } catch (_: Exception) { BigInteger.ZERO }
                    } else BigInteger.ZERO
                    val claimableEth = org.web3j.utils.Convert.fromWei(
                        java.math.BigDecimal(claimable),
                        org.web3j.utils.Convert.Unit.ETHER
                    ).toDouble()

                    // Determine rank based on winners (only relevant if participated)
                    val rank = if (participated && address.isNotEmpty()) {
                        when (address.lowercase()) {
                            state.winner1.lowercase() -> 1
                            state.winner2.lowercase() -> 2
                            state.winner3.lowercase() -> 3
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
                            playersTotal = state.playerCount,
                            leaderboard = emptyList(),
                            playedAt = config.gameDate * 1000,
                            prizeEth = claimableEth,
                            claimed = if (participated) (playerInfo?.claimed == true || claimable == BigInteger.ZERO) else true,
                            claimableWei = claimable,
                            isCancelled = isCancelled,
                            gameId = gameId,
                            participated = participated,
                            winner1 = state.winner1,
                            winner2 = state.winner2,
                            winner3 = state.winner3,
                            topKiller = state.topKiller
                        )
                    )
                } catch (_: Exception) {
                    // Skip games that fail to load
                }
            }
            // Sort by date descending
            _gameHistory.value = items.sortedByDescending { it.playedAt }
        } catch (_: Exception) {
            // Silently fail — history stays empty
        }
    }

    private fun saveGameToHistory(state: GameState) {
        // After local gameplay ends, reload history from chain
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
        // Load from chain if not in local list (e.g. fresh ViewModel instance)
        viewModelScope.launch {
            try {
                val gameIdInt = gameId.toIntOrNull() ?: return@launch
                val config = contractService.getGameConfig(gameIdInt)
                val state = contractService.getGameState(gameIdInt)
                val shrinks = contractService.getZoneShrinks(gameIdInt)
                val appConfig = ChainMapper.toGameConfig(gameIdInt, config, shrinks)
                val address = walletManager.getAddress()
                val playerInfo = if (address.isNotEmpty()) {
                    try { contractService.getPlayerInfo(gameIdInt, address) } catch (_: Exception) { null }
                } else null
                _selectedGame.value = GameListItem(
                    config = appConfig,
                    currentPlayers = state.playerCount,
                    locationName = appConfig.name,
                    startTime = appConfig.gameDate,
                    onChainPhase = state.phase,
                    totalCollected = state.totalCollected,
                    isPlayerRegistered = playerInfo?.registered == true,
                    playerNumber = playerInfo?.number ?: 0
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
        val game = _games.value.find { it.config.id == gameId } ?: return
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
