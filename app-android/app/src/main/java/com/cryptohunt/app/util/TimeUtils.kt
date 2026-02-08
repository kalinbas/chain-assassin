package com.cryptohunt.app.util

object TimeUtils {

    /** Format seconds as "M:SS" or "H:MM:SS". */
    fun formatCountdown(totalSeconds: Int): String {
        if (totalSeconds <= 0) return "0:00"
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    /** Format seconds as human-readable duration, e.g. "12m 34s" or "1h 05m". */
    fun formatDuration(totalSeconds: Long): String {
        if (totalSeconds <= 0) return "0s"
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> "${hours}h %02dm".format(minutes)
            minutes > 0 -> "${minutes}m %02ds".format(seconds)
            else -> "${seconds}s"
        }
    }

    /** Format a timestamp as "HH:MM" for the kill feed. */
    fun formatTimestamp(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = (totalSeconds / 3600) % 24
        val minutes = (totalSeconds % 3600) / 60
        return "%02d:%02d".format(hours, minutes)
    }
}
