package com.cryptohunt.app.domain.chain

enum class OnChainPhase(val value: Int) {
    REGISTRATION(0),
    ACTIVE(1),
    ENDED(2),
    CANCELLED(3);

    companion object {
        fun fromInt(v: Int): OnChainPhase = entries.first { it.value == v }
    }
}

sealed class TransactionState {
    data object Idle : TransactionState()
    data object Sending : TransactionState()
    data class Pending(val txHash: String) : TransactionState()
    data class Confirmed(val txHash: String) : TransactionState()
    data class Failed(val error: String) : TransactionState()
}
