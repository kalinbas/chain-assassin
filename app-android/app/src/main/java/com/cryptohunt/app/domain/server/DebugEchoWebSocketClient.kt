package com.cryptohunt.app.domain.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DebugEchoWsClient"
private const val ECHO_WS_URL = "wss://echo.websocket.org"

@Singleton
class DebugEchoWebSocketClient @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val reconnectScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MINUTES)
        .build()

    private var webSocket: WebSocket? = null
    private var shouldReconnect = false
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    @Volatile private var networkAvailable = true
    private var networkCallbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            networkAvailable = true
            tryImmediateReconnect("network became available")
        }

        override fun onLost(network: Network) {
            networkAvailable = isNetworkAvailable()
            if (networkAvailable) return
            Log.w(TAG, "Network lost")

            if (webSocket != null) {
                webSocket?.cancel()
                webSocket = null
            }

            if (shouldReconnect) {
                cancelReconnectJob()
                _connectionState.value = ConnectionState.RECONNECTING
            } else {
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    init {
        networkAvailable = isNetworkAvailable()
        registerNetworkCallback()
    }

    fun start() {
        shouldReconnect = true
        if (_connectionState.value == ConnectionState.CONNECTED ||
            _connectionState.value == ConnectionState.CONNECTING ||
            _connectionState.value == ConnectionState.AUTHENTICATING
        ) {
            return
        }

        reconnectAttempts = 0
        cancelReconnectJob()

        if (!networkAvailable) {
            _connectionState.value = ConnectionState.RECONNECTING
            return
        }
        doConnect()
    }

    fun stop() {
        shouldReconnect = false
        cancelReconnectJob()
        webSocket?.close(1000, "Debug page closed")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private fun doConnect() {
        if (!shouldReconnect || webSocket != null) return

        _connectionState.value = ConnectionState.CONNECTING
        val request = Request.Builder()
            .url(ECHO_WS_URL)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempts = 0
                _connectionState.value = ConnectionState.CONNECTED
                webSocket.send("debug-echo:${System.currentTimeMillis()}")
                Log.d(TAG, "Connected to echo websocket")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.v(TAG, "Echo message: ${text.take(80)}")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Echo websocket closed: $code $reason")
                handleDisconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Echo websocket failure: ${t.message}")
                handleDisconnect()
            }
        })
    }

    private fun handleDisconnect() {
        networkAvailable = isNetworkAvailable()
        webSocket = null

        if (!shouldReconnect) {
            cancelReconnectJob()
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }

        reconnectAttempts++
        if (!networkAvailable) {
            _connectionState.value = ConnectionState.RECONNECTING
            return
        }

        val delayMs = calculateReconnectDelay()
        _connectionState.value = ConnectionState.RECONNECTING
        scheduleReconnect(delayMs)
    }

    private fun scheduleReconnect(delayMs: Long) {
        cancelReconnectJob()
        reconnectJob = reconnectScope.launch {
            delay(delayMs)
            if (!shouldReconnect || webSocket != null) return@launch
            networkAvailable = isNetworkAvailable()
            if (!networkAvailable) {
                _connectionState.value = ConnectionState.RECONNECTING
                return@launch
            }
            doConnect()
        }
    }

    private fun cancelReconnectJob() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun calculateReconnectDelay(): Long {
        val delayMs = 1_000L * (1L shl reconnectAttempts.coerceAtMost(5))
        return delayMs.coerceAtMost(15_000L)
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        val cm = connectivityManager ?: return
        try {
            cm.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing ACCESS_NETWORK_STATE permission; network callback disabled", e)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback", e)
        }
    }

    private fun tryImmediateReconnect(reason: String) {
        if (!shouldReconnect || webSocket != null || !networkAvailable) return
        if (_connectionState.value == ConnectionState.CONNECTING || _connectionState.value == ConnectionState.AUTHENTICATING) {
            return
        }
        reconnectAttempts = 0
        Log.i(TAG, "Immediate debug echo reconnect: $reason")
        doConnect()
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = connectivityManager ?: return true
        return try {
            val activeNetwork = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot query network state (permission denied)", e)
            true
        }
    }
}
