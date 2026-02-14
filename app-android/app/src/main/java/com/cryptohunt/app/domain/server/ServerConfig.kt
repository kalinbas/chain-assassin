package com.cryptohunt.app.domain.server

import android.os.Build
import com.cryptohunt.app.BuildConfig

object ServerConfig {
    private const val PROD_SERVER_URL = "https://chain-assassin.fly.dev"
    private const val PROD_SERVER_WS_URL = "wss://chain-assassin.fly.dev/ws"
    private const val DEV_EMULATOR_SERVER_URL = "http://10.0.2.2:3000"
    private const val DEV_EMULATOR_SERVER_WS_URL = "ws://10.0.2.2:3000/ws"

    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.startsWith("generic")
            || Build.FINGERPRINT.startsWith("unknown")
            || Build.MODEL.contains("google_sdk")
            || Build.MODEL.contains("Emulator")
            || Build.MODEL.contains("Android SDK built for x86")
            || Build.MANUFACTURER.contains("Genymotion")
            || Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")
            || "google_sdk" == Build.PRODUCT
            || Build.HARDWARE.contains("goldfish")
            || Build.HARDWARE.contains("ranchu")
            || Build.PRODUCT.contains("sdk")
            || Build.PRODUCT.contains("emulator")
            || Build.PRODUCT.contains("simulator")
            || Build.DEVICE.contains("emulator")
    }

    private val compileTimeServerUrl = BuildConfig.SERVER_URL.trim()
    private val compileTimeServerWsUrl = BuildConfig.SERVER_WS_URL.trim()

    // Priority:
    // 1) Compile-time override (used by local e2e tooling)
    // 2) Debug emulator default
    // 3) Production
    val SERVER_URL: String = when {
        compileTimeServerUrl.isNotEmpty() -> compileTimeServerUrl
        BuildConfig.DEBUG && isEmulator() -> DEV_EMULATOR_SERVER_URL
        else -> PROD_SERVER_URL
    }

    val SERVER_WS_URL: String = when {
        compileTimeServerWsUrl.isNotEmpty() -> compileTimeServerWsUrl
        BuildConfig.DEBUG && isEmulator() -> DEV_EMULATOR_SERVER_WS_URL
        else -> PROD_SERVER_WS_URL
    }

    // Auth message prefix (must match server's crypto.ts validateAuthMessage)
    const val AUTH_PREFIX = "chain-assassin"

    // Reconnection
    const val RECONNECT_DELAY_MIN_MS = 1_000L
    const val RECONNECT_DELAY_MAX_MS = 30_000L

    // OkHttp ping interval (matches server's 30s ping)
    const val PING_INTERVAL_SECONDS = 30L
}
