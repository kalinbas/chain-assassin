package com.cryptohunt.app.domain.server

import android.util.Log
import org.json.JSONObject

private const val TAG = "MessageParser"

/**
 * Parse a JSON string from the server WebSocket into a typed [ServerMessage].
 * Returns null for unknown or malformed message types (forward-compatible).
 */
fun parseServerMessage(json: String): ServerMessage? {
    return try {
        val obj = JSONObject(json)
        val type = obj.optString("type", "")

        when (type) {
            "auth:success" -> ServerMessage.AuthSuccess(
                gameId = obj.optInt("gameId"),
                playerNumber = obj.optInt("playerNumber"),
                isAlive = obj.optBoolean("isAlive", true),
                kills = obj.optInt("kills", 0),
                target = obj.optJSONObject("target")?.let { parseTargetInfo(it) },
                hunterPlayerNumber = obj.optIntOrNull("hunterPlayerNumber"),
                lastHeartbeatAt = obj.optLongOrNull("lastHeartbeatAt"),
                subPhase = obj.optStringOrNull("subPhase"),
                pregameEndsAt = obj.optLongOrNull("pregameEndsAt")
            )

            "kill:recorded" -> ServerMessage.KillRecorded(
                hunter = obj.optString("hunter", ""),
                target = obj.optString("target", ""),
                hunterKills = obj.optInt("hunterKills", 0)
            )

            "player:eliminated" -> ServerMessage.PlayerEliminated(
                player = obj.optString("player", ""),
                eliminator = obj.optString("eliminator", ""),
                reason = obj.optString("reason", "")
            )

            "target:assigned" -> ServerMessage.TargetAssigned(
                target = parseTargetInfo(obj.getJSONObject("target")),
                hunterPlayerNumber = obj.optInt("hunterPlayerNumber", 0)
            )

            "hunter:updated" -> ServerMessage.HunterUpdated(
                hunterPlayerNumber = obj.optInt("hunterPlayerNumber", 0)
            )

            "zone:shrink" -> ServerMessage.ZoneShrink(
                zone = parseZoneState(obj)
            )

            "zone:warning" -> ServerMessage.ZoneWarning(
                secondsRemaining = obj.optInt("secondsRemaining", 0),
                inZone = obj.optBoolean("inZone", false)
            )

            "leaderboard:update" -> ServerMessage.LeaderboardUpdate(
                entries = parseLeaderboard(obj)
            )

            "heartbeat:refreshed" -> ServerMessage.HeartbeatRefreshed(
                refreshedUntil = obj.optLong("refreshedUntil", 0)
            )

            "heartbeat:scan_success" -> ServerMessage.HeartbeatScanSuccess(
                scannedPlayerNumber = obj.optInt("scannedPlayerNumber", 0)
            )

            "heartbeat:error" -> ServerMessage.HeartbeatError(
                error = obj.optString("error", "Unknown error")
            )

            "game:pregame_started" -> ServerMessage.GamePregameStarted(
                pregameDurationSeconds = obj.optInt("pregameDurationSeconds", 180),
                checkedInCount = obj.optInt("checkedInCount", 0),
                playerCount = obj.optInt("playerCount", 0)
            )

            "game:started" -> ServerMessage.GameStarted(
                target = parseTargetInfo(obj.getJSONObject("target")),
                hunterPlayerNumber = obj.optInt("hunterPlayerNumber", 0),
                heartbeatDeadline = obj.optLong("heartbeatDeadline", 0),
                heartbeatIntervalSeconds = obj.optInt("heartbeatIntervalSeconds", 600),
                zone = obj.optJSONObject("zone")?.let { parseZoneState(it) }
            )

            "game:started_broadcast" -> ServerMessage.GameStartedBroadcast(
                playerCount = obj.optInt("playerCount", 0)
            )

            "game:ended" -> ServerMessage.GameEnded(
                winner1 = obj.optString("winner1", ""),
                winner2 = obj.optString("winner2", ""),
                winner3 = obj.optString("winner3", ""),
                topKiller = obj.optString("topKiller", "")
            )

            "game:cancelled" -> ServerMessage.GameCancelled(
                gameId = obj.optInt("gameId", 0)
            )

            "checkin:update" -> ServerMessage.CheckinUpdate(
                checkedInCount = obj.optInt("checkedInCount", 0),
                totalPlayers = obj.optInt("totalPlayers", 0),
                player = obj.optString("player", "")
            )

            "game:checkin_started" -> ServerMessage.CheckinStarted(
                checkinDurationSeconds = obj.optInt("checkinDurationSeconds", 300)
            )

            "player:registered" -> ServerMessage.PlayerRegistered(
                address = obj.optString("address", ""),
                playerCount = obj.optInt("playerCount", 0)
            )

            "error" -> ServerMessage.Error(
                error = obj.optString("error", obj.optString("message", "Unknown error"))
            )

            else -> {
                Log.d(TAG, "Unknown server message type: $type")
                null
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to parse server message: ${e.message}")
        null
    }
}

// --- Helpers ---

private fun parseTargetInfo(obj: JSONObject): TargetInfo {
    return TargetInfo(
        address = obj.optString("address", ""),
        playerNumber = obj.optInt("playerNumber", 0)
    )
}

private fun parseZoneState(obj: JSONObject): ZoneState {
    return ZoneState(
        centerLat = obj.optDouble("centerLat", 0.0),
        centerLng = obj.optDouble("centerLng", 0.0),
        currentRadiusMeters = obj.optDouble("currentRadiusMeters", 0.0),
        nextShrinkAt = obj.optLongOrNull("nextShrinkAt"),
        nextRadiusMeters = obj.optDoubleOrNull("nextRadiusMeters")
    )
}

private fun parseLeaderboard(obj: JSONObject): List<ServerLeaderboardEntry> {
    val arr = obj.optJSONArray("entries") ?: return emptyList()
    return (0 until arr.length()).map { i ->
        val entry = arr.getJSONObject(i)
        ServerLeaderboardEntry(
            address = entry.optString("address", ""),
            playerNumber = entry.optInt("playerNumber", 0),
            kills = entry.optInt("kills", 0),
            isAlive = entry.optBoolean("isAlive", true),
            eliminatedAt = entry.optLongOrNull("eliminatedAt")
        )
    }
}

// Extension helpers for nullable JSON values
private fun JSONObject.optIntOrNull(key: String): Int? =
    if (isNull(key)) null else optInt(key)

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (isNull(key)) null else optLong(key)

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (isNull(key)) null else optDouble(key)

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key)
