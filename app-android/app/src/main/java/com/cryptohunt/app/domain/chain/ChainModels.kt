package com.cryptohunt.app.domain.chain

import java.math.BigInteger

enum class OnChainPhase(val value: Int) {
    REGISTRATION(0),
    ACTIVE(1),
    ENDED(2),
    CANCELLED(3);

    companion object {
        fun fromInt(v: Int): OnChainPhase = entries.first { it.value == v }
    }
}

data class OnChainGameConfig(
    val title: String,
    val entryFee: BigInteger,
    val minPlayers: Int,
    val maxPlayers: Int,
    val registrationDeadline: Long,
    val gameDate: Long,
    val maxDuration: Long,
    val createdAt: Long,
    val creator: String,
    val centerLat: Int,
    val centerLng: Int,
    val bps1st: Int,
    val bps2nd: Int,
    val bps3rd: Int,
    val bpsKills: Int,
    val bpsCreator: Int
)

data class OnChainGameState(
    val phase: OnChainPhase,
    val playerCount: Int,
    val totalCollected: BigInteger,
    val winner1: String,
    val winner2: String,
    val winner3: String,
    val topKiller: String
)

data class OnChainZoneShrink(
    val atSecond: Int,
    val radiusMeters: Int
)

data class PlayerInfo(
    val registered: Boolean,
    val alive: Boolean,
    val kills: Int,
    val claimed: Boolean,
    val number: Int = 0
)

sealed class TransactionState {
    data object Idle : TransactionState()
    data object Sending : TransactionState()
    data class Pending(val txHash: String) : TransactionState()
    data class Confirmed(val txHash: String) : TransactionState()
    data class Failed(val error: String) : TransactionState()
}
