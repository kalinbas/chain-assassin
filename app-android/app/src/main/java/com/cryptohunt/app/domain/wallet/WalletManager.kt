package com.cryptohunt.app.domain.wallet

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.cryptohunt.app.domain.chain.ChainConfig
import com.cryptohunt.app.domain.chain.ContractService
import com.cryptohunt.app.domain.chain.WalletDepositWatcher
import com.cryptohunt.app.domain.game.GameEngine
import com.cryptohunt.app.domain.model.WalletState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.web3j.crypto.Credentials
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign
import org.web3j.utils.Convert
import java.math.BigDecimal
import java.math.BigInteger
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WalletManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contractService: ContractService,
    private val depositWatcher: WalletDepositWatcher,
    private val gameEngine: GameEngine
) {

    private val _state = MutableStateFlow(WalletState())
    val state: StateFlow<WalletState> = _state.asStateFlow()

    private var credentials: Credentials? = null

    val isConnected: Boolean get() = credentials != null

    private val encryptedPrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "cryptohunt_wallet",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    init {
        // Try to restore saved wallet on startup
        val savedKey = encryptedPrefs.getString("private_key", null)
        if (savedKey != null) {
            importWallet(savedKey)
        }
    }

    fun createWallet() {
        try {
            val keyPair = Keys.createEcKeyPair()
            setupCredentials(keyPair)
        } catch (e: Exception) {
            try {
                val random = SecureRandom()
                val privateKeyBytes = ByteArray(32)
                random.nextBytes(privateKeyBytes)
                val privateKey = BigInteger(1, privateKeyBytes)
                val keyPair = ECKeyPair.create(privateKey)
                setupCredentials(keyPair)
            } catch (e2: Exception) {
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
        val privateKeyHex = keyPair.privateKey.toString(16)
        encryptedPrefs.edit().putString("private_key", privateKeyHex).apply()
        _state.value = WalletState(
            isConnected = true,
            address = credentials!!.address,
            balanceEth = 0.0,
            balanceUsd = 0.0,
            chainName = ChainConfig.CHAIN_NAME
        )
        startDepositMonitoring(credentials!!.address)
    }

    private fun setupMockWallet() {
        val random = SecureRandom()
        val bytes = ByteArray(20)
        random.nextBytes(bytes)
        val address = "0x" + bytes.joinToString("") { "%02x".format(it) }
        _state.value = WalletState(
            isConnected = true,
            address = address,
            balanceEth = 0.0,
            balanceUsd = 0.0,
            chainName = ChainConfig.CHAIN_NAME
        )
    }

    fun getAddress(): String = credentials?.address ?: _state.value.address

    fun getCredentials(): Credentials? = credentials

    fun getPrivateKeyHex(): String? {
        return credentials?.ecKeyPair?.privateKey?.toString(16)?.let { "0x$it" }
    }

    suspend fun refreshBalance() {
        val addr = getAddress()
        if (addr.isEmpty()) return
        try {
            val balanceWei = contractService.getBalance(addr)
            applyBalance(balanceWei)
        } catch (e: Exception) {
            // Silently fail — balance stays at previous value
        }
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

    fun shortenedAddress(): String {
        val addr = getAddress()
        if (addr.length < 10) return addr
        return "${addr.take(6)}...${addr.takeLast(4)}"
    }

    fun logout() {
        encryptedPrefs.edit().remove("private_key").apply()
        depositWatcher.stop()
        credentials = null
        _state.value = WalletState()
        gameEngine.reset()
    }

    private fun startDepositMonitoring(address: String) {
        if (address.isBlank()) return
        depositWatcher.start(
            address = address,
            onDeposit = { _deltaWei, newBalanceWei ->
                applyBalance(newBalanceWei)
                Log.i("WalletManager", "Deposit detected for ${shortenedAddress()}")
            },
            onError = { error ->
                Log.w("WalletManager", error)
            }
        )
    }

    private fun applyBalance(balanceWei: BigInteger) {
        val balanceEth = Convert.fromWei(BigDecimal(balanceWei), Convert.Unit.ETHER).toDouble()
        _state.value = _state.value.copy(
            balanceEth = balanceEth,
            balanceUsd = balanceEth * 2500.0
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
