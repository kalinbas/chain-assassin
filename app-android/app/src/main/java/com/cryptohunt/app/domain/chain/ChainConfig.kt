package com.cryptohunt.app.domain.chain

object ChainConfig {
    const val CONTRACT_ADDRESS = "0x94E2A4cA986b87e7534BFbeeC64bFfDe0528038F"
    const val RPC_URL = "https://base-sepolia.g.alchemy.com/v2/gwRYWylWRij2jXTnPXR90v-YqXh96PDX"
    const val CHAIN_ID = 84532L
    const val EXPLORER_URL = "https://sepolia.basescan.org"
    const val CHAIN_NAME = "Base Sepolia"

    const val GAS_LIMIT_REGISTER = 200_000L
    const val GAS_LIMIT_CLAIM = 100_000L
    const val GAS_LIMIT_TRIGGER = 100_000L
}
