package com.cryptohunt.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptohunt.app.domain.chain.ContractService
import com.cryptohunt.app.domain.chain.OnChainPhase
import com.cryptohunt.app.domain.game.GameEngine
import com.cryptohunt.app.domain.location.LocationTracker
import com.cryptohunt.app.domain.model.*
import com.cryptohunt.app.domain.server.GameServerClient
import com.cryptohunt.app.domain.server.ServerConfig
import com.cryptohunt.app.domain.server.ServerMapper
import com.cryptohunt.app.domain.server.ServerGame
import com.cryptohunt.app.domain.server.ServerPlayerInfo
import com.cryptohunt.app.domain.wallet.WalletManager
import android.util.Log
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
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
                _error.value = "Failed to load games from ${ServerConfig.SERVER_URL}"
                return
            }
            val address = walletManager.getAddress()
            val items = mutableListOf<GameListItem>()
            var activeGameRestored = false

            for (game in allGames) {
                try {
                    val appConfig = ServerMapper.toGameConfig(game)
                    val phase = OnChainPhase.fromInt(game.phase)
                    val totalCollected = game.totalCollected.toBigIntegerOrNull() ?: BigInteger.ZERO

                    val nowMs = System.currentTimeMillis()
                    if (phase == OnChainPhase.REGISTRATION && appConfig.gameDate > nowMs) {
                        // Show all upcoming REGISTRATION games.
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

                        if (playerInfo != null && playerInfo.registered && !activeGameRestored) {
                            // Restore GameEngine state so the lobby shows the active game card
                            val existingState = gameEngine.state.value
                            if (existingState == null || existingState.config.id != appConfig.id) {
                                gameEngine.registerForGame(
                                    appConfig, address, appConfig.gameDate,
                                    assignedPlayerNumber = playerInfo.playerNumber
                                )
                                // Server connection deferred to when user navigates to the game screen
                            }
                            activeGameRestored = true
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

                    // Only include past games where current wallet participated.
                    val playerInfo = if (address.isNotEmpty()) {
                        try { serverClient.fetchPlayerInfo(game.gameId, address) } catch (_: Exception) { null }
                    } else null
                    if (playerInfo?.registered != true) continue
                    val participated = true

                    val claimable = computeClaimableWei(game, playerInfo)
                    val claimableEth = org.web3j.utils.Convert.fromWei(
                        java.math.BigDecimal(claimable),
                        org.web3j.utils.Convert.Unit.ETHER
                    ).toDouble()

                    // Determine rank based on winners (only relevant if participated)
                    val pNum = playerInfo.playerNumber
                    val rank = if (pNum != 0) {
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
                            kills = playerInfo.kills,
                            rank = rank,
                            survivalSeconds = 0L,
                            playersTotal = game.playerCount,
                            leaderboard = emptyList(),
                            playedAt = game.gameDate * 1000,
                            prizeEth = claimableEth,
                            claimed = playerInfo.claimed || claimable == BigInteger.ZERO,
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
        val credentials = walletManager.getCredentials() ?: run {
            onError("Wallet not connected")
            return
        }

        viewModelScope.launch {
            _txPending.value = true
            try {
                val txHash = if (item.isCancelled) {
                    contractService.claimRefund(item.gameId, credentials)
                } else {
                    contractService.claimPrize(item.gameId, credentials)
                }
                val receipt = contractService.waitForReceipt(txHash, timeoutMs = 120_000)
                if (receipt == null) {
                    onError("Transaction sent but confirmation timed out. Check explorer for tx: $txHash")
                    return@launch
                }
                if (receipt.isStatusOK) {
                    // Update local state
                    val history = _gameHistory.value.toMutableList()
                    history[index] = item.copy(claimed = true, claimableWei = BigInteger.ZERO, prizeEth = 0.0)
                    _gameHistory.value = history
                    // Refresh balance
                    walletManager.refreshBalance()
                    onSuccess()
                } else {
                    onError("Transaction reverted on-chain (status ${receipt.status}). Tx: $txHash")
                }
            } catch (e: Exception) {
                onError(mapTxError(e, defaultMessage = "Claim failed"))
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
                val selected = fetchSelectedGameFromServer(gameIdInt) ?: run {
                    _error.value = "Game not found"
                    return@launch
                }
                _selectedGame.value = selected
            } catch (e: Exception) {
                _error.value = "Failed to load game: ${e.message}"
            }
        }
    }

    /**
     * Full detail refresh:
     * 1) Pull latest game snapshot from REST
     * 2) Restore local registered state from fresh data
     * 3) Force WS reconnect to receive a fresh auth snapshot
     */
    fun refreshRegisteredDetail(
        gameId: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            try {
                val gameIdInt = gameId.toIntOrNull() ?: run {
                    onError("Invalid game ID")
                    return@launch
                }
                val selected = fetchSelectedGameFromServer(gameIdInt) ?: run {
                    onError("Game not found")
                    return@launch
                }
                _selectedGame.value = selected
                ensureRegisteredState(gameId)
                serverClient.reconnect(gameIdInt)
                onSuccess()
            } catch (e: Exception) {
                onError("Refresh failed: ${e.message ?: "unknown error"}")
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
                val receipt = contractService.waitForReceipt(txHash, timeoutMs = 120_000)
                if (receipt == null) {
                    onError("Transaction sent but confirmation timed out. Check explorer for tx: $txHash")
                    return@launch
                }
                if (receipt.isStatusOK) {
                    // Refresh wallet balance
                    walletManager.refreshBalance()

                    val address = walletManager.getAddress()
                    val playerInfo = waitForServerRegistration(gameIdInt, address)

                    val assignedNumber = playerInfo?.playerNumber ?: 0
                    val currentPlayers = try {
                        serverClient.fetchGameDetail(gameIdInt)?.playerCount ?: (game.currentPlayers + 1)
                    } catch (_: Exception) {
                        game.currentPlayers + 1
                    }

                    // Register in local game engine for gameplay simulation
                    gameEngine.registerForGame(
                        game.config,
                        address,
                        game.startTime,
                        assignedPlayerNumber = assignedNumber
                    )

                    // Update selected game immediately so UI reflects registration
                    _selectedGame.value = game.copy(
                        isPlayerRegistered = true,
                        playerNumber = assignedNumber,
                        currentPlayers = currentPlayers
                    )
                    // Refresh games list to update player count
                    loadGames()
                    onSuccess()
                } else {
                    onError("Transaction reverted on-chain (status ${receipt.status}). Tx: $txHash")
                }
            } catch (e: Exception) {
                onError(mapTxError(e, defaultMessage = "Registration failed"))
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

    fun disconnectFromServer() {
        serverClient.disconnect()
    }

    /**
     * Registered detail screen owns the connection only before live phases.
     * Once check-in/pregame/game starts, downstream screens take over the same WS.
     */
    fun disconnectFromRegisteredDetailIfIdle(): Unit {
        val phase = gameEngine.state.value?.phase
        if (phase in listOf(GamePhase.CHECK_IN, GamePhase.PREGAME, GamePhase.ACTIVE, GamePhase.ELIMINATED)) {
            return
        }
        serverClient.disconnect()
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
        // Server drives game start — local trigger intentionally disabled.
    }

    /**
     * Restore local game engine state from server-indexed data.
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

    private suspend fun waitForServerRegistration(
        gameId: Int,
        address: String,
        attempts: Int = 12,
        delayMs: Long = 1_500
    ): ServerPlayerInfo? {
        repeat(attempts) {
            val playerInfo = try {
                serverClient.fetchPlayerInfo(gameId, address)
            } catch (_: Exception) {
                null
            }
            if (playerInfo?.registered == true) return playerInfo
            delay(delayMs)
        }
        return null
    }

    private suspend fun fetchSelectedGameFromServer(gameId: Int): GameListItem? {
        val game = serverClient.fetchGameDetail(gameId) ?: return null
        val appConfig = ServerMapper.toGameConfig(game)
        val phase = OnChainPhase.fromInt(game.phase)
        val address = walletManager.getAddress()
        val playerInfo = if (address.isNotEmpty()) {
            try { serverClient.fetchPlayerInfo(gameId, address) } catch (_: Exception) { null }
        } else null

        return GameListItem(
            config = appConfig,
            currentPlayers = game.playerCount,
            locationName = appConfig.name,
            startTime = appConfig.gameDate,
            onChainPhase = phase,
            totalCollected = game.totalCollected.toBigIntegerOrNull() ?: BigInteger.ZERO,
            isPlayerRegistered = playerInfo?.registered == true,
            playerNumber = playerInfo?.playerNumber ?: 0
        )
    }

    private fun computeClaimableWei(game: ServerGame, playerInfo: ServerPlayerInfo): BigInteger {
        if (!playerInfo.registered || playerInfo.claimed) return BigInteger.ZERO

        val phase = OnChainPhase.fromInt(game.phase)
        if (phase == OnChainPhase.CANCELLED) {
            return game.entryFee.toBigIntegerOrNull() ?: BigInteger.ZERO
        }

        if (phase != OnChainPhase.ENDED) return BigInteger.ZERO

        val totalCollected = game.totalCollected.toBigIntegerOrNull() ?: BigInteger.ZERO
        if (totalCollected <= BigInteger.ZERO) return BigInteger.ZERO

        val p = playerInfo.playerNumber
        if (p <= 0) return BigInteger.ZERO

        var totalBps = 0
        if (p == game.winner1) totalBps += game.bps1st
        if (p == game.winner2) totalBps += game.bps2nd
        if (p == game.winner3) totalBps += game.bps3rd
        if (p == game.topKiller) totalBps += game.bpsKills
        if (totalBps <= 0) return BigInteger.ZERO

        return totalCollected
            .multiply(BigInteger.valueOf(totalBps.toLong()))
            .divide(BigInteger.valueOf(10_000L))
    }

    private fun mapTxError(error: Throwable, defaultMessage: String): String {
        val raw = (error.message ?: defaultMessage).trim()
        if (raw.isBlank()) return defaultMessage

        val lower = raw.lowercase()
        return when {
            "insufficient funds" in lower ->
                "Insufficient funds for value + gas."
            "nonce too low" in lower ->
                "Transaction nonce too low. Please retry."
            "replacement transaction underpriced" in lower ->
                "Replacement transaction underpriced. Please retry."
            "user denied" in lower || "rejected" in lower ->
                "Transaction was rejected."
            "execution reverted" in lower ->
                extractRevertReason(raw)
            else -> raw
        }
    }

    private fun extractRevertReason(raw: String): String {
        val marker = "execution reverted"
        val idx = raw.lowercase().indexOf(marker)
        if (idx < 0) return raw
        val suffix = raw.substring(idx + marker.length).trimStart(' ', ':')
        return if (suffix.isBlank()) "Transaction reverted on-chain." else "Transaction reverted: $suffix"
    }

    fun clearError() {
        _error.value = null
    }
}
