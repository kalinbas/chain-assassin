package com.cryptohunt.app.domain.model

data class WalletState(
    val isConnected: Boolean = false,
    val address: String = "",
    val balanceEth: Double = 0.0,
    val balanceUsd: Double = 0.0,
    val chainName: String = "Base"
)
