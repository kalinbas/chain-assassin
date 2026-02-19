package com.cryptohunt.app.domain.ble

import android.os.ParcelUuid
import java.nio.ByteBuffer
import java.util.UUID

/**
 * BLE payload protocol for Chain Assassin proximity checks.
 * Token format:
 * - BLE manufacturer data company id: 0xCA11
 * - payload bytes: compact big-endian positive number (player token)
 */
object BleTokenProtocol {
    // 16-bit app marker (0xCA11) expanded to Bluetooth Base UUID.
    // Using 16-bit marker leaves enough legacy payload room to also advertise
    // explicit payload bytes in the primary advertisement packet.
    val serviceUuid: UUID = UUID.fromString("0000CA11-0000-1000-8000-00805F9B34FB")
    val serviceParcelUuid: ParcelUuid = ParcelUuid(serviceUuid)
    const val manufacturerId: Int = 0xCA11
    private val numericTokenPattern = Regex("^[0-9]{1,10}$")

    fun normalizeToken(value: String?): String? {
        if (value == null) return null
        val token = value.trim()
        return token.takeIf { numericTokenPattern.matches(it) }
    }

    fun encodeToken(value: String): ByteArray {
        val normalized = normalizeToken(value)
            ?: throw IllegalArgumentException("Invalid BLE token")
        return encodeNumericToken(normalized)
    }

    fun decodeToken(bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        return decodeNumericToken(bytes)
    }

    private fun encodeNumericToken(token: String): ByteArray {
        val value = token.toLongOrNull()
            ?: throw IllegalArgumentException("Invalid numeric BLE token")
        if (value <= 0L) {
            throw IllegalArgumentException("BLE token must be positive")
        }
        // Big-endian compact representation (no leading zero bytes).
        val full = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array()
        val firstNonZeroIndex = full.indexOfFirst { it.toInt() != 0 }.coerceAtLeast(0)
        return full.copyOfRange(firstNonZeroIndex, full.size)
    }

    private fun decodeNumericToken(bytes: ByteArray): String? {
        if (bytes.isEmpty() || bytes.size > Long.SIZE_BYTES) return null
        var value = 0L
        for (byte in bytes) {
            value = (value shl 8) or (byte.toLong() and 0xFFL)
        }
        if (value <= 0L) return null
        return normalizeToken(value.toString())
    }
}
