package com.cryptohunt.app.domain.chain

import com.cryptohunt.app.BuildConfig

object ChainConfig {
    private const val PROD_CONTRACT_ADDRESS = "0x991a415B644E84A8a1F12944C6817bf2117e18D7"
    private const val PROD_RPC_URL = "https://base-sepolia.g.alchemy.com/v2/gwRYWylWRij2jXTnPXR90v-YqXh96PDX"
    private const val PROD_RPC_WS_URL = "wss://base-sepolia.g.alchemy.com/v2/gwRYWylWRij2jXTnPXR90v-YqXh96PDX"
    private const val PROD_CHAIN_ID = 84532L
    private const val PROD_EXPLORER_URL = "https://sepolia.basescan.org"
    private const val PROD_CHAIN_NAME = "Base Sepolia"

    private fun isLocalEndpoint(url: String): Boolean {
        val normalized = url.trim().lowercase()
        if (normalized.isEmpty()) return false
        return normalized.contains("10.0.2.2")
            || normalized.contains("127.0.0.1")
            || normalized.contains("localhost")
    }

    private val useLocalStack = BuildConfig.USE_LOCAL_STACK
    private val hasLocalChainOverride =
        BuildConfig.CHAIN_ID == 31337L
            || isLocalEndpoint(BuildConfig.CHAIN_RPC_URL)
            || isLocalEndpoint(BuildConfig.CHAIN_RPC_WS_URL)
    private val useCompileTimeChainConfig = !hasLocalChainOverride || useLocalStack

    val CONTRACT_ADDRESS: String =
        if (useCompileTimeChainConfig) BuildConfig.CHAIN_CONTRACT_ADDRESS else PROD_CONTRACT_ADDRESS
    val RPC_URL: String =
        if (useCompileTimeChainConfig) BuildConfig.CHAIN_RPC_URL else PROD_RPC_URL
    val RPC_WS_URL: String =
        if (useCompileTimeChainConfig) BuildConfig.CHAIN_RPC_WS_URL else PROD_RPC_WS_URL
    val CHAIN_ID: Long =
        if (useCompileTimeChainConfig) BuildConfig.CHAIN_ID else PROD_CHAIN_ID
    val EXPLORER_URL: String =
        if (useCompileTimeChainConfig) BuildConfig.CHAIN_EXPLORER_URL else PROD_EXPLORER_URL
    val CHAIN_NAME: String =
        if (useCompileTimeChainConfig) BuildConfig.CHAIN_NAME else PROD_CHAIN_NAME

    const val GAS_LIMIT_REGISTER = 200_000L
    const val GAS_LIMIT_CLAIM = 100_000L
    const val GAS_LIMIT_TRIGGER = 100_000L
}
