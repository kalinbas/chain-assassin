package com.cryptohunt.app.domain.chain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Bool
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Type
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Int32
import org.web3j.abi.datatypes.generated.Uint128
import org.web3j.abi.datatypes.generated.Uint16
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.abi.datatypes.generated.Uint32
import org.web3j.abi.datatypes.generated.Uint40
import org.web3j.abi.datatypes.generated.Uint8
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

    suspend fun getNextGameId(): Int = withContext(Dispatchers.IO) {
        val function = Function(
            "nextGameId",
            emptyList(),
            listOf(object : TypeReference<Uint256>() {})
        )
        val result = ethCall(function)
        (result[0].value as BigInteger).toInt()
    }

    suspend fun getGameConfig(gameId: Int): OnChainGameConfig = withContext(Dispatchers.IO) {
        // Manual hex decoding — web3j FunctionReturnDecoder can't handle struct with dynamic string
        val function = Function(
            "getGameConfig",
            listOf(Uint256(gameId.toLong())),
            emptyList()
        )
        val encoded = FunctionEncoder.encode(function)
        val response = web3j.ethCall(
            Transaction.createEthCallTransaction(null, ChainConfig.CONTRACT_ADDRESS, encoded),
            DefaultBlockParameterName.LATEST
        ).send()
        if (response.hasError()) throw RuntimeException("eth_call failed: ${response.error.message}")

        val hex = response.value.removePrefix("0x")

        // The return is a tuple. First 32 bytes = offset to the tuple data.
        val tupleOffset = readUint(hex, 0).toInt() * 2 // convert byte offset to char offset

        // Within the tuple, slot layout (each 32 bytes = 64 hex chars):
        //  0: offset to string data (relative to tuple start)
        //  1: entryFee (uint128)
        //  2: minPlayers (uint16)
        //  3: maxPlayers (uint16)
        //  4: registrationDeadline (uint40)
        //  5: gameDate (uint40)
        //  6: maxDuration (uint32)
        //  7: createdAt (uint40)
        //  8: creator (address)
        //  9: centerLat (int32, signed)
        // 10: centerLng (int32, signed)
        // 11: meetingLat (int32, signed)
        // 12: meetingLng (int32, signed)
        // 13: bps1st (uint16)
        // 14: bps2nd (uint16)
        // 15: bps3rd (uint16)
        // 16: bpsKills (uint16)
        // 17: bpsCreator (uint16)
        // 18: baseReward (uint128)
        // Then the string data at the offset

        val stringOffset = readUint(hex, tupleOffset).toInt() * 2
        val stringDataStart = tupleOffset + stringOffset
        val stringLen = readUint(hex, stringDataStart).toInt()
        val titleBytes = hexToBytes(hex.substring(stringDataStart + 64, stringDataStart + 64 + stringLen * 2))
        val title = String(titleBytes, Charsets.UTF_8)

        OnChainGameConfig(
            title = title,
            entryFee = readUint(hex, tupleOffset + 64),
            minPlayers = readUint(hex, tupleOffset + 64 * 2).toInt(),
            maxPlayers = readUint(hex, tupleOffset + 64 * 3).toInt(),
            registrationDeadline = readUint(hex, tupleOffset + 64 * 4).toLong(),
            gameDate = readUint(hex, tupleOffset + 64 * 5).toLong(),
            maxDuration = readUint(hex, tupleOffset + 64 * 6).toLong(),
            createdAt = readUint(hex, tupleOffset + 64 * 7).toLong(),
            creator = "0x" + hex.substring(tupleOffset + 64 * 8 + 24, tupleOffset + 64 * 9),
            centerLat = readInt32(hex, tupleOffset + 64 * 9),
            centerLng = readInt32(hex, tupleOffset + 64 * 10),
            meetingLat = readInt32(hex, tupleOffset + 64 * 11),
            meetingLng = readInt32(hex, tupleOffset + 64 * 12),
            bps1st = readUint(hex, tupleOffset + 64 * 13).toInt(),
            bps2nd = readUint(hex, tupleOffset + 64 * 14).toInt(),
            bps3rd = readUint(hex, tupleOffset + 64 * 15).toInt(),
            bpsKills = readUint(hex, tupleOffset + 64 * 16).toInt(),
            bpsCreator = readUint(hex, tupleOffset + 64 * 17).toInt(),
            baseReward = readUint(hex, tupleOffset + 64 * 18)
        )
    }

    suspend fun getGameState(gameId: Int): OnChainGameState = withContext(Dispatchers.IO) {
        // Manual hex decoding — struct return has tuple offset wrapper
        val function = Function(
            "getGameState",
            listOf(Uint256(gameId.toLong())),
            emptyList()
        )
        val encoded = FunctionEncoder.encode(function)
        val response = web3j.ethCall(
            Transaction.createEthCallTransaction(null, ChainConfig.CONTRACT_ADDRESS, encoded),
            DefaultBlockParameterName.LATEST
        ).send()
        if (response.hasError()) throw RuntimeException("eth_call failed: ${response.error.message}")

        val hex = response.value.removePrefix("0x")

        // GameState is a static-only struct, so ABI encodes it as flat slots (no tuple offset)
        // Slot 0: phase (uint8)
        // Slot 1: playerCount (uint16)
        // Slot 2: totalCollected (uint128)
        // Slot 3: winner1 (address)
        // Slot 4: winner2 (address)
        // Slot 5: winner3 (address)
        // Slot 6: topKiller (address)
        OnChainGameState(
            phase = OnChainPhase.fromInt(readUint(hex, 0).toInt()),
            playerCount = readUint(hex, 64).toInt(),
            totalCollected = readUint(hex, 64 * 2),
            winner1 = "0x" + hex.substring(64 * 3 + 24, 64 * 4),
            winner2 = "0x" + hex.substring(64 * 4 + 24, 64 * 5),
            winner3 = "0x" + hex.substring(64 * 5 + 24, 64 * 6),
            topKiller = "0x" + hex.substring(64 * 6 + 24, 64 * 7)
        )
    }

    suspend fun getZoneShrinks(gameId: Int): List<OnChainZoneShrink> = withContext(Dispatchers.IO) {
        val function = Function(
            "getZoneShrinks",
            listOf(Uint256(gameId.toLong())),
            listOf(object : TypeReference<DynamicArray<ZoneShrinkTuple>>() {})
        )
        val encoded = FunctionEncoder.encode(function)
        val response = web3j.ethCall(
            Transaction.createEthCallTransaction(null, ChainConfig.CONTRACT_ADDRESS, encoded),
            DefaultBlockParameterName.LATEST
        ).send()

        if (response.hasError()) throw RuntimeException(response.error.message)

        // Manual decoding: the response is an ABI-encoded dynamic array of (uint32, uint32) tuples
        val hex = response.value.removePrefix("0x")
        if (hex.length < 128) return@withContext emptyList()

        // offset (32 bytes) + length (32 bytes) + N * 2 * 32 bytes
        val offset = BigInteger(hex.substring(0, 64), 16).toInt() * 2 // char offset
        val length = BigInteger(hex.substring(offset, offset + 64), 16).toInt()
        val shrinks = mutableListOf<OnChainZoneShrink>()
        for (i in 0 until length) {
            val base = offset + 64 + i * 128
            val atSecond = BigInteger(hex.substring(base, base + 64), 16).toInt()
            val radiusMeters = BigInteger(hex.substring(base + 64, base + 128), 16).toInt()
            shrinks.add(OnChainZoneShrink(atSecond, radiusMeters))
        }
        shrinks
    }

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

    suspend fun isRegistered(gameId: Int, address: String): Boolean = withContext(Dispatchers.IO) {
        val function = Function(
            "isRegistered",
            listOf(Uint256(gameId.toLong()), Address(address)),
            listOf(object : TypeReference<Bool>() {})
        )
        val r = ethCall(function)
        r[0].value as Boolean
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

    // ── Hex decoding helpers ──────────────────────────────────────

    /** Read a 32-byte unsigned integer from hex string at char offset */
    private fun readUint(hex: String, charOffset: Int): BigInteger {
        return BigInteger(hex.substring(charOffset, charOffset + 64), 16)
    }

    /** Read a 32-byte signed int32 from hex string at char offset */
    private fun readInt32(hex: String, charOffset: Int): Int {
        val value = BigInteger(hex.substring(charOffset, charOffset + 64), 16)
        // If the int32 value is negative (bit 31 set), convert from two's complement
        return if (value > BigInteger.valueOf(Int.MAX_VALUE.toLong())) {
            value.subtract(BigInteger.ONE.shiftLeft(256)).toInt()
        } else {
            value.toInt()
        }
    }

    /** Convert hex string to byte array */
    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length / 2
        val bytes = ByteArray(len)
        for (i in 0 until len) {
            bytes[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return bytes
    }

    // Placeholder type for zone shrink tuple decoding (manual decoding used instead)
    private class ZoneShrinkTuple : org.web3j.abi.datatypes.StaticStruct(
        Uint32(BigInteger.ZERO),
        Uint32(BigInteger.ZERO)
    )
}
