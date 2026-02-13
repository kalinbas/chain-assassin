package com.cryptohunt.app.domain.chain

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WalletDepositWatcher"

@Singleton
class WalletDepositWatcher @Inject constructor(
    private val contractService: ContractService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val wsClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var watchedAddress: String? = null
    private var shouldReconnect = false
    private var reconnectAttempts = 0
    private var lastKnownBalanceWei: BigInteger? = null
    private var onDeposit: ((deltaWei: BigInteger, newBalanceWei: BigInteger) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private val balanceCheckInFlight = AtomicBoolean(false)

    fun start(
        address: String,
        onDeposit: (deltaWei: BigInteger, newBalanceWei: BigInteger) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        if (address.isBlank()) return

        stop()

        watchedAddress = address.lowercase()
        this.onDeposit = onDeposit
        this.onError = onError
        shouldReconnect = true
        reconnectAttempts = 0

        scope.launch {
            syncInitialBalance()
            connect()
        }
    }

    fun stop() {
        shouldReconnect = false
        webSocket?.close(1000, "Stopped by client")
        webSocket = null
        watchedAddress = null
        lastKnownBalanceWei = null
        onDeposit = null
        onError = null
        reconnectAttempts = 0
        balanceCheckInFlight.set(false)
    }

    private suspend fun syncInitialBalance() {
        val address = watchedAddress ?: return
        try {
            lastKnownBalanceWei = contractService.getBalance(address)
        } catch (e: Exception) {
            onError?.invoke("Deposit watcher initial sync failed: ${e.message ?: "unknown error"}")
        }
    }

    private fun connect() {
        if (!shouldReconnect) return

        val request = Request.Builder()
            .url(ChainConfig.RPC_WS_URL)
            .build()

        webSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                reconnectAttempts = 0
                val subscribe = JSONObject().apply {
                    put("id", 1)
                    put("jsonrpc", "2.0")
                    put("method", "eth_subscribe")
                    put("params", org.json.JSONArray().put("newHeads"))
                }
                ws.send(subscribe.toString())
                Log.d(TAG, "Subscribed to newHeads for deposit monitoring")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val method = json.optString("method", "")
                    if (method == "eth_subscription") {
                        scope.launch { checkBalanceDelta() }
                    } else if (json.has("error")) {
                        val errorMsg = json.getJSONObject("error").optString("message", "unknown websocket error")
                        onError?.invoke("Deposit watcher RPC error: $errorMsg")
                    }
                } catch (_: Exception) {
                    // Ignore malformed messages.
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(code, reason)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                scheduleReconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                onError?.invoke("Deposit watcher connection failed: ${t.message ?: "unknown error"}")
                scheduleReconnect()
            }
        })
    }

    private suspend fun checkBalanceDelta() {
        if (!balanceCheckInFlight.compareAndSet(false, true)) return
        try {
            val address = watchedAddress ?: return
            val current = contractService.getBalance(address)
            val previous = lastKnownBalanceWei ?: current
            if (current > previous) {
                val delta = current.subtract(previous)
                lastKnownBalanceWei = current
                onDeposit?.invoke(delta, current)
            } else {
                lastKnownBalanceWei = current
            }
        } catch (e: Exception) {
            onError?.invoke("Deposit watcher balance check failed: ${e.message ?: "unknown error"}")
        } finally {
            balanceCheckInFlight.set(false)
        }
    }

    private fun scheduleReconnect() {
        webSocket = null
        if (!shouldReconnect || watchedAddress == null) return

        reconnectAttempts += 1
        val waitMs = (2_000L * (1L shl reconnectAttempts.coerceAtMost(5))).coerceAtMost(60_000L)

        scope.launch {
            delay(waitMs)
            if (shouldReconnect && watchedAddress != null) {
                connect()
            }
        }
    }
}
