package com.cryptohunt.app.domain.chain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Bool
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Type
import org.web3j.abi.datatypes.generated.Uint16
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.crypto.Credentials
import org.web3j.tx.RawTransactionManager
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.protocol.http.HttpService
import java.math.BigInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContractService @Inject constructor() {

    private val web3j: Web3j = Web3j.build(HttpService(ChainConfig.RPC_URL))

    // ── Read functions ──────────────────────────────────────────────

    suspend fun getPlayerInfo(gameId: Int, address: String): PlayerInfo = withContext(Dispatchers.IO) {
        val function = Function(
            "getPlayerInfo",
            listOf(Uint256(gameId.toLong()), Address(address)),
            listOf(
                object : TypeReference<Bool>() {},   // registered
                object : TypeReference<Bool>() {},   // alive
                object : TypeReference<Uint16>() {}, // kills
                object : TypeReference<Bool>() {},   // claimed
                object : TypeReference<Uint16>() {}, // number
            )
        )
        val r = ethCall(function)
        PlayerInfo(
            registered = r[0].value as Boolean,
            alive = r[1].value as Boolean,
            kills = (r[2].value as BigInteger).toInt(),
            claimed = r[3].value as Boolean,
            number = (r[4].value as BigInteger).toInt()
        )
    }

    suspend fun getClaimableAmount(gameId: Int, address: String): BigInteger = withContext(Dispatchers.IO) {
        val function = Function(
            "getClaimableAmount",
            listOf(Uint256(gameId.toLong()), Address(address)),
            listOf(object : TypeReference<Uint256>() {})
        )
        val r = ethCall(function)
        r[0].value as BigInteger
    }

    suspend fun getBalance(address: String): BigInteger = withContext(Dispatchers.IO) {
        web3j.ethGetBalance(address, DefaultBlockParameterName.LATEST).send().balance
    }

    // ── Write functions ─────────────────────────────────────────────

    suspend fun register(gameId: Int, entryFeeWei: BigInteger, credentials: Credentials): String {
        return sendTransaction(
            credentials = credentials,
            functionName = "register",
            inputParams = listOf(Uint256(gameId.toLong())),
            value = entryFeeWei,
            gasLimit = ChainConfig.GAS_LIMIT_REGISTER
        )
    }

    suspend fun claimPrize(gameId: Int, credentials: Credentials): String {
        return sendTransaction(
            credentials = credentials,
            functionName = "claimPrize",
            inputParams = listOf(Uint256(gameId.toLong())),
            gasLimit = ChainConfig.GAS_LIMIT_CLAIM
        )
    }

    suspend fun claimRefund(gameId: Int, credentials: Credentials): String {
        return sendTransaction(
            credentials = credentials,
            functionName = "claimRefund",
            inputParams = listOf(Uint256(gameId.toLong())),
            gasLimit = ChainConfig.GAS_LIMIT_CLAIM
        )
    }

    suspend fun triggerCancellation(gameId: Int, credentials: Credentials): String {
        return sendTransaction(
            credentials = credentials,
            functionName = "triggerCancellation",
            inputParams = listOf(Uint256(gameId.toLong())),
            gasLimit = ChainConfig.GAS_LIMIT_TRIGGER
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────

    suspend fun waitForReceipt(txHash: String, timeoutMs: Long = 60_000): TransactionReceipt? {
        return withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < timeoutMs) {
                val receipt = web3j.ethGetTransactionReceipt(txHash).send()
                if (receipt.transactionReceipt.isPresent) {
                    return@withContext receipt.transactionReceipt.get()
                }
                delay(2000)
            }
            null
        }
    }

    private fun ethCall(function: Function): List<Type<*>> {
        val encoded = FunctionEncoder.encode(function)
        val response = web3j.ethCall(
            Transaction.createEthCallTransaction(null, ChainConfig.CONTRACT_ADDRESS, encoded),
            DefaultBlockParameterName.LATEST
        ).send()
        if (response.hasError()) {
            throw RuntimeException("eth_call failed: ${response.error.message}")
        }
        return FunctionReturnDecoder.decode(response.value, function.outputParameters)
    }

    private suspend fun sendTransaction(
        credentials: Credentials,
        functionName: String,
        inputParams: List<Type<*>>,
        value: BigInteger = BigInteger.ZERO,
        gasLimit: Long
    ): String = withContext(Dispatchers.IO) {
        val function = Function(functionName, inputParams, emptyList())
        val encodedFunction = FunctionEncoder.encode(function)
        val txManager = RawTransactionManager(web3j, credentials, ChainConfig.CHAIN_ID)
        val gasPrice = web3j.ethGasPrice().send().gasPrice
        val response = txManager.sendTransaction(
            gasPrice,
            BigInteger.valueOf(gasLimit),
            ChainConfig.CONTRACT_ADDRESS,
            encodedFunction,
            value
        )
        if (response.hasError()) {
            throw RuntimeException("Transaction failed: ${response.error.message}")
        }
        response.transactionHash
    }

}
