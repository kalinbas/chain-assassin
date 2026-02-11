package com.cryptohunt.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.cryptohunt.app.CryptoHuntApp
import com.cryptohunt.app.MainActivity
import com.cryptohunt.app.R

class GameLocationService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val playersRemaining = intent?.getIntExtra("players_remaining", 0) ?: 0
        startForeground(NOTIFICATION_ID, buildNotification(playersRemaining))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun updateNotification(playersRemaining: Int, zoneTimer: String) {
        val notification = buildNotification(playersRemaining, zoneTimer)
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(playersRemaining: Int = 0, zoneTimer: String = ""): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val text = buildString {
            append("Game Active")
            if (playersRemaining > 0) append(" \u2022 $playersRemaining players remaining")
            if (zoneTimer.isNotEmpty()) append(" \u2022 Zone: $zoneTimer")
        }

        return NotificationCompat.Builder(this, CryptoHuntApp.GAME_CHANNEL_ID)
            .setContentTitle("Chain Assassin")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1001
    }
}
