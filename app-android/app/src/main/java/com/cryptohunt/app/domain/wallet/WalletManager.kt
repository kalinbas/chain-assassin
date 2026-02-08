package com.cryptohunt.app.domain.wallet

import com.cryptohunt.app.domain.model.WalletState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.web3j.crypto.Credentials
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign
import java.math.BigInteger
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WalletManager @Inject constructor() {

    private val _state = MutableStateFlow(WalletState())
    val state: StateFlow<WalletState> = _state.asStateFlow()

    private var credentials: Credentials? = null

    val isConnected: Boolean get() = credentials != null

    fun createWallet() {
        try {
            // Try web3j's built-in key generation first
            val keyPair = Keys.createEcKeyPair()
            setupCredentials(keyPair)
        } catch (e: Exception) {
            // Fallback: generate key manually with SecureRandom
            try {
                val random = SecureRandom()
                val privateKeyBytes = ByteArray(32)
                random.nextBytes(privateKeyBytes)
                val privateKey = BigInteger(1, privateKeyBytes)
                val keyPair = ECKeyPair.create(privateKey)
                setupCredentials(keyPair)
            } catch (e2: Exception) {
                // Last resort: use a deterministic mock wallet for demo
                setupMockWallet()
            }
        }
    }

    fun importWallet(privateKeyHex: String): Boolean {
        return try {
            val cleaned = privateKeyHex.removePrefix("0x").trim()
            val keyPair = ECKeyPair.create(BigInteger(cleaned, 16))
            setupCredentials(keyPair)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun setupCredentials(keyPair: ECKeyPair) {
        credentials = Credentials.create(keyPair)
        _state.value = WalletState(
            isConnected = true,
            address = credentials!!.address,
            balanceEth = 0.5,
            balanceUsd = 1250.0,
            chainName = "Base"
        )
    }

    private fun setupMockWallet() {
        // Generate a fake address when crypto libs fail entirely
        val random = SecureRandom()
        val bytes = ByteArray(20)
        random.nextBytes(bytes)
        val address = "0x" + bytes.joinToString("") { "%02x".format(it) }
        _state.value = WalletState(
            isConnected = true,
            address = address,
            balanceEth = 0.5,
            balanceUsd = 1250.0,
            chainName = "Base"
        )
    }

    fun getAddress(): String = credentials?.address ?: _state.value.address

    fun getPrivateKeyHex(): String? {
        return credentials?.ecKeyPair?.privateKey?.toString(16)?.let { "0x$it" }
    }

    fun signMessage(message: String): String {
        val creds = credentials ?: return "0xmock_signature"
        return try {
            val messageBytes = message.toByteArray()
            val signatureData = Sign.signPrefixedMessage(messageBytes, creds.ecKeyPair)
            val r = signatureData.r.toHex()
            val s = signatureData.s.toHex()
            val v = signatureData.v.toHex()
            "0x$r$s$v"
        } catch (e: Exception) {
            "0xmock_signature"
        }
    }

    fun payEntryFee(amount: Double): Boolean {
        val current = _state.value
        if (current.balanceEth < amount) return false
        _state.value = current.copy(
            balanceEth = current.balanceEth - amount,
            balanceUsd = (current.balanceEth - amount) * 2500.0
        )
        return true
    }

    fun withdrawAll(): Double {
        val current = _state.value
        val amount = current.balanceEth
        _state.value = current.copy(balanceEth = 0.0, balanceUsd = 0.0)
        return amount
    }

    fun addBalance(ethAmount: Double) {
        val current = _state.value
        _state.value = current.copy(
            balanceEth = current.balanceEth + ethAmount,
            balanceUsd = (current.balanceEth + ethAmount) * 2500.0
        )
    }

    fun shortenedAddress(): String {
        val addr = getAddress()
        if (addr.length < 10) return addr
        return "${addr.take(6)}...${addr.takeLast(4)}"
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
