package com.cryptohunt.app.domain.server

import android.util.Log
import com.cryptohunt.app.domain.wallet.WalletManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GameServerClient"

@Singleton
class GameServerClient @Inject constructor(
    private val walletManager: WalletManager
) {
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _serverMessages = MutableSharedFlow<ServerMessage>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val serverMessages: SharedFlow<ServerMessage> = _serverMessages

    private val _authState = MutableStateFlow<ServerMessage.AuthSuccess?>(null)
    val authState: StateFlow<ServerMessage.AuthSuccess?> = _authState

    private var webSocket: WebSocket? = null
    private var currentGameId: Int? = null
    private var reconnectAttempts = 0
    private var shouldReconnect = false

    private val client = OkHttpClient.Builder()
        .pingInterval(ServerConfig.PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MINUTES) // No read timeout for WebSocket
        .build()

    /**
     * Connect to the game server WebSocket and authenticate.
     */
    fun connect(gameId: Int) {
        // Same game already connected → no-op
        if (currentGameId == gameId &&
            (_connectionState.value == ConnectionState.CONNECTED ||
             _connectionState.value == ConnectionState.CONNECTING ||
             _connectionState.value == ConnectionState.AUTHENTICATING)
        ) {
            Log.d(TAG, "Already connected to game $gameId, ignoring")
            return
        }

        // Different game connected → disconnect old first
        if (currentGameId != null && currentGameId != gameId &&
            _connectionState.value != ConnectionState.DISCONNECTED
        ) {
            Log.d(TAG, "Disconnecting from game $currentGameId before connecting to game $gameId")
            disconnect()
        }

        currentGameId = gameId
        shouldReconnect = true
        reconnectAttempts = 0
        doConnect()
    }

    /**
     * Disconnect from the server.
     */
    fun disconnect() {
        shouldReconnect = false
        currentGameId = null
        _authState.value = null
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        Log.d(TAG, "Disconnected")
    }

    /**
     * Send a location update to the server.
     */
    fun sendLocation(lat: Double, lng: Double) {
        if (_connectionState.value != ConnectionState.CONNECTED) return
        val json = JSONObject().apply {
            put("type", "location")
            put("lat", lat)
            put("lng", lng)
            put("timestamp", System.currentTimeMillis() / 1000)
        }
        send(json.toString())
    }

    /**
     * Submit a kill claim to the server via REST API.
     * Returns true on success, false on failure.
     */
    fun submitKill(gameId: Int, qrPayload: String, lat: Double, lng: Double): Boolean {
        val address = walletManager.getAddress()
        val timestamp = System.currentTimeMillis() / 1000
        val message = "${ServerConfig.AUTH_PREFIX}:$timestamp"
        val signature = walletManager.signMessage(message)

        val body = JSONObject().apply {
            put("qrPayload", qrPayload)
            put("hunterLat", lat)
            put("hunterLng", lng)
        }.toString()

        val request = Request.Builder()
            .url("${ServerConfig.SERVER_URL}/api/games/$gameId/kill")
            .header("X-Address", address)
            .header("X-Signature", signature)
            .header("X-Message", message)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            if (!success) {
                Log.e(TAG, "Kill submit failed: ${response.code} ${response.body?.string()}")
            } else {
                Log.i(TAG, "Kill submitted for game $gameId")
            }
            response.close()
            success
        } catch (e: Exception) {
            Log.e(TAG, "Kill submit error: ${e.message}")
            false
        }
    }

    /**
     * Submit a check-in to the server via REST API.
     * Returns true on success, false on failure.
     */
    fun submitCheckin(gameId: Int, lat: Double, lng: Double, qrPayload: String? = null, bluetoothId: String? = null): Boolean {
        val address = walletManager.getAddress()
        val timestamp = System.currentTimeMillis() / 1000
        val message = "${ServerConfig.AUTH_PREFIX}:$timestamp"
        val signature = walletManager.signMessage(message)

        val body = JSONObject().apply {
            put("lat", lat)
            put("lng", lng)
            if (qrPayload != null) {
                put("qrPayload", qrPayload)
            }
            if (bluetoothId != null) {
                put("bluetoothId", bluetoothId)
            }
        }.toString()

        val request = Request.Builder()
            .url("${ServerConfig.SERVER_URL}/api/games/$gameId/checkin")
            .header("X-Address", address)
            .header("X-Signature", signature)
            .header("X-Message", message)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            if (!success) {
                Log.e(TAG, "Checkin failed: ${response.code} ${response.body?.string()}")
            } else {
                Log.i(TAG, "Checkin submitted for game $gameId")
            }
            response.close()
            success
        } catch (e: Exception) {
            Log.e(TAG, "Checkin error: ${e.message}")
            false
        }
    }

    /**
     * Submit a heartbeat scan to the server via REST API.
     * Returns true on success, false on failure.
     */
    fun submitHeartbeat(gameId: Int, qrPayload: String, lat: Double, lng: Double, bleAddresses: List<String> = emptyList()): Boolean {
        val address = walletManager.getAddress()
        val timestamp = System.currentTimeMillis() / 1000
        val message = "${ServerConfig.AUTH_PREFIX}:$timestamp"
        val signature = walletManager.signMessage(message)

        val body = JSONObject().apply {
            put("qrPayload", qrPayload)
            put("lat", lat)
            put("lng", lng)
            if (bleAddresses.isNotEmpty()) {
                put("bleNearbyAddresses", JSONArray(bleAddresses))
            }
        }.toString()

        val request = Request.Builder()
            .url("${ServerConfig.SERVER_URL}/api/games/$gameId/heartbeat")
            .header("X-Address", address)
            .header("X-Signature", signature)
            .header("X-Message", message)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            if (!success) {
                Log.e(TAG, "Heartbeat failed: ${response.code} ${response.body?.string()}")
            } else {
                Log.i(TAG, "Heartbeat submitted for game $gameId")
            }
            response.close()
            success
        } catch (e: Exception) {
            Log.e(TAG, "Heartbeat error: ${e.message}")
            false
        }
    }

    /**
     * Upload a photo to the server via HTTP multipart POST.
     * Returns true on success, false on failure.
     */
    fun uploadPhoto(gameId: Int, photoFile: File, caption: String?): Boolean {
        val address = walletManager.getAddress()
        val timestamp = System.currentTimeMillis() / 1000
        val message = "${ServerConfig.AUTH_PREFIX}:$timestamp"
        val signature = walletManager.signMessage(message)

        val mediaType = "image/jpeg".toMediaType()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("photo", photoFile.name, photoFile.asRequestBody(mediaType))
            .apply {
                if (!caption.isNullOrBlank()) {
                    addFormDataPart("caption", caption)
                }
            }
            .build()

        val request = Request.Builder()
            .url("${ServerConfig.SERVER_URL}/api/games/$gameId/photos")
            .header("X-Address", address)
            .header("X-Signature", signature)
            .header("X-Message", message)
            .post(body)
            .build()

        return try {
            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            if (!success) {
                Log.e(TAG, "Photo upload failed: ${response.code} ${response.body?.string()}")
            } else {
                Log.i(TAG, "Photo uploaded for game $gameId")
            }
            response.close()
            success
        } catch (e: Exception) {
            Log.e(TAG, "Photo upload error: ${e.message}")
            false
        }
    }

    // --- Public REST API (no auth) ---

    /**
     * GET /api/games — fetch all games from server.
     */
    fun fetchAllGames(): List<ServerGame>? {
        val request = Request.Builder()
            .url("${ServerConfig.SERVER_URL}/api/games")
            .get()
            .build()
        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) { response.close(); return null }
            val body = response.body?.string() ?: return null
            response.close()
            val array = JSONArray(body)
            val games = mutableListOf<ServerGame>()
            for (i in 0 until array.length()) {
                games.add(parseServerGame(array.getJSONObject(i)))
            }
            games
        } catch (e: Exception) {
            Log.e(TAG, "fetchAllGames error: ${e.message}")
            null
        }
    }

    /**
     * GET /api/games/:gameId — fetch single game detail.
     */
    fun fetchGameDetail(gameId: Int): ServerGame? {
        val request = Request.Builder()
            .url("${ServerConfig.SERVER_URL}/api/games/$gameId")
            .get()
            .build()
        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) { response.close(); return null }
            val body = response.body?.string() ?: return null
            response.close()
            parseServerGame(JSONObject(body))
        } catch (e: Exception) {
            Log.e(TAG, "fetchGameDetail error: ${e.message}")
            null
        }
    }

    /**
     * GET /api/games/:gameId/player/:address — fetch player info.
     */
    fun fetchPlayerInfo(gameId: Int, address: String): ServerPlayerInfo? {
        val request = Request.Builder()
            .url("${ServerConfig.SERVER_URL}/api/games/$gameId/player/$address")
            .get()
            .build()
        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) { response.close(); return null }
            val body = response.body?.string() ?: return null
            response.close()
            val json = JSONObject(body)
            ServerPlayerInfo(
                registered = json.optBoolean("registered", false),
                alive = json.optBoolean("alive", false),
                kills = json.optInt("kills", 0),
                claimed = json.optBoolean("claimed", false),
                playerNumber = json.optInt("playerNumber", 0),
                checkedIn = json.optBoolean("checkedIn", false)
            )
        } catch (e: Exception) {
            Log.e(TAG, "fetchPlayerInfo error: ${e.message}")
            null
        }
    }

    private fun parseServerGame(obj: JSONObject): ServerGame {
        val shrinksArray = obj.optJSONArray("zoneShrinks")
        val shrinks = mutableListOf<ServerZoneShrink>()
        if (shrinksArray != null) {
            for (i in 0 until shrinksArray.length()) {
                val s = shrinksArray.getJSONObject(i)
                shrinks.add(ServerZoneShrink(s.optInt("atSecond"), s.optInt("radiusMeters")))
            }
        }
        return ServerGame(
            gameId = obj.optInt("gameId"),
            title = obj.optString("title", ""),
            entryFee = obj.optString("entryFee", "0"),
            baseReward = obj.optString("baseReward", "0"),
            minPlayers = obj.optInt("minPlayers"),
            maxPlayers = obj.optInt("maxPlayers"),
            registrationDeadline = obj.optLong("registrationDeadline"),
            gameDate = obj.optLong("gameDate"),
            expiryDeadline = obj.optLong("expiryDeadline"),
            maxDuration = obj.optLong("maxDuration"),
            createdAt = obj.optLong("createdAt"),
            creator = obj.optString("creator", ""),
            centerLat = obj.optInt("centerLat"),
            centerLng = obj.optInt("centerLng"),
            meetingLat = obj.optInt("meetingLat"),
            meetingLng = obj.optInt("meetingLng"),
            bps1st = obj.optInt("bps1st"),
            bps2nd = obj.optInt("bps2nd"),
            bps3rd = obj.optInt("bps3rd"),
            bpsKills = obj.optInt("bpsKills"),
            bpsCreator = obj.optInt("bpsCreator"),
            totalCollected = obj.optString("totalCollected", "0"),
            playerCount = obj.optInt("playerCount"),
            phase = obj.optInt("phase"),
            subPhase = if (obj.isNull("subPhase")) null else obj.optString("subPhase"),
            winner1 = obj.optInt("winner1"),
            winner2 = obj.optInt("winner2"),
            winner3 = obj.optInt("winner3"),
            topKiller = obj.optInt("topKiller"),
            zoneShrinks = shrinks
        )
    }

    // --- Internal ---

    private fun doConnect() {
        val gameId = currentGameId ?: return
        _connectionState.value = ConnectionState.CONNECTING
        Log.d(TAG, "Connecting to ${ServerConfig.SERVER_WS_URL} for game $gameId")

        val request = Request.Builder()
            .url(ServerConfig.SERVER_WS_URL)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened, authenticating...")
                _connectionState.value = ConnectionState.AUTHENTICATING
                authenticate(ws, gameId)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
                ws.close(code, reason)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                handleDisconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                handleDisconnect()
            }
        })
    }

    private fun authenticate(ws: WebSocket, gameId: Int) {
        val timestamp = System.currentTimeMillis() / 1000
        val message = "${ServerConfig.AUTH_PREFIX}:$gameId:$timestamp"
        val address = walletManager.getAddress()
        val signature = walletManager.signMessage(message)

        val json = JSONObject().apply {
            put("type", "auth")
            put("gameId", gameId)
            put("address", address)
            put("signature", signature)
            put("message", message)
        }

        Log.d(TAG, "Sending auth for game $gameId, address ${address.take(10)}...")
        ws.send(json.toString())
    }

    private fun handleMessage(text: String) {
        val msg = parseServerMessage(text)
        if (msg == null) {
            Log.d(TAG, "Unparsed message: ${text.take(100)}")
            return
        }

        // Handle auth success specially
        if (msg is ServerMessage.AuthSuccess) {
            _authState.value = msg
            _connectionState.value = ConnectionState.CONNECTED
            reconnectAttempts = 0
            Log.i(TAG, "Authenticated: player #${msg.playerNumber}, alive=${msg.isAlive}, subPhase=${msg.subPhase}")
        }

        // Handle server errors
        if (msg is ServerMessage.Error) {
            Log.e(TAG, "Server error: ${msg.error}")
        }

        // Emit to all collectors
        _serverMessages.tryEmit(msg)
    }

    private fun handleDisconnect() {
        webSocket = null
        _authState.value = null

        if (shouldReconnect && currentGameId != null) {
            reconnectAttempts++
            val delay = calculateReconnectDelay()
            _connectionState.value = ConnectionState.RECONNECTING
            Log.d(TAG, "Reconnecting in ${delay}ms (attempt $reconnectAttempts)")

            // Schedule reconnect on a background thread
            Thread {
                try {
                    Thread.sleep(delay)
                    if (shouldReconnect) {
                        doConnect()
                    }
                } catch (_: InterruptedException) {
                    // Cancelled
                }
            }.start()
        } else {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    private fun calculateReconnectDelay(): Long {
        val base = ServerConfig.RECONNECT_DELAY_MIN_MS
        val max = ServerConfig.RECONNECT_DELAY_MAX_MS
        val delay = base * (1L shl reconnectAttempts.coerceAtMost(5))
        return delay.coerceAtMost(max)
    }

    private fun send(text: String): Boolean {
        return webSocket?.send(text) ?: false
    }
}
