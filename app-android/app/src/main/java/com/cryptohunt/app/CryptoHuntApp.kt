package com.cryptohunt.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.cryptohunt.app.domain.server.GameRealtimeSync
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class CryptoHuntApp : Application() {
    @Inject
    lateinit var gameRealtimeSync: GameRealtimeSync

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // Touch singleton so stream bridges are initialized once at app start.
        gameRealtimeSync.hashCode()
    }

    private fun createNotificationChannels() {
        val gameChannel = NotificationChannel(
            GAME_CHANNEL_ID,
            "Game Active",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when a game is in progress"
        }

        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "Game Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Kill alerts and zone warnings"
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(gameChannel)
        nm.createNotificationChannel(alertChannel)
    }

    companion object {
        const val GAME_CHANNEL_ID = "cryptohunt_game"
        const val ALERT_CHANNEL_ID = "cryptohunt_alerts"
    }
}
