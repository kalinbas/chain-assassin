package com.cryptohunt.app.domain.server

object ServerConfig {
    const val SERVER_URL = "https://chain-assassin.fly.dev"
    const val SERVER_WS_URL = "wss://chain-assassin.fly.dev/ws"

    // Auth message prefix (must match server's crypto.ts validateAuthMessage)
    const val AUTH_PREFIX = "chain-assassin"

    // Reconnection
    const val RECONNECT_DELAY_MIN_MS = 1_000L
    const val RECONNECT_DELAY_MAX_MS = 30_000L

    // OkHttp ping interval (matches server's 30s ping)
    const val PING_INTERVAL_SECONDS = 30L
}
