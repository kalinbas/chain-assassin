package com.cryptohunt.app.domain.chain

import com.cryptohunt.app.BuildConfig

object ChainConfig {
    val CONTRACT_ADDRESS: String = BuildConfig.CHAIN_CONTRACT_ADDRESS
    val RPC_URL: String = BuildConfig.CHAIN_RPC_URL
    val RPC_WS_URL: String = BuildConfig.CHAIN_RPC_WS_URL
    val CHAIN_ID: Long = BuildConfig.CHAIN_ID
    val EXPLORER_URL: String = BuildConfig.CHAIN_EXPLORER_URL
    val CHAIN_NAME: String = BuildConfig.CHAIN_NAME

    const val GAS_LIMIT_REGISTER = 200_000L
    const val GAS_LIMIT_CLAIM = 100_000L
    const val GAS_LIMIT_TRIGGER = 100_000L
}
