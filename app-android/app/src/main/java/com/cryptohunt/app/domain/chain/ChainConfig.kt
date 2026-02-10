package com.cryptohunt.app.domain.chain

object ChainConfig {
    const val CONTRACT_ADDRESS = "0xe9cFc825a66780651A7844f470E70DfdbabC9636"
    const val RPC_URL = "https://sepolia.base.org"
    const val CHAIN_ID = 84532L
    const val EXPLORER_URL = "https://sepolia.basescan.org"
    const val CHAIN_NAME = "Base Sepolia"

    const val GAS_LIMIT_REGISTER = 200_000L
    const val GAS_LIMIT_CLAIM = 100_000L
    const val GAS_LIMIT_TRIGGER = 100_000L
}
