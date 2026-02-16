package com.cryptohunt.app.domain.ble

import android.os.ParcelUuid
import java.util.UUID

/**
 * BLE payload protocol for Chain Assassin proximity checks.
 * The token value is the same numeric payload used in player QR codes.
 */
object BleTokenProtocol {
    val serviceUuid: UUID = UUID.fromString("ea9c6f4a-3f5e-4cf5-9d46-7c5e3f03a21b")
    val serviceParcelUuid: ParcelUuid = ParcelUuid(serviceUuid)

    fun normalizeToken(value: String?): String? {
        if (value == null) return null
        val token = value.trim()
        return token.takeIf { it.isNotEmpty() }
    }

    fun encodeToken(value: String): ByteArray {
        return value.toByteArray(Charsets.UTF_8)
    }

    fun decodeToken(bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        return normalizeToken(bytes.toString(Charsets.UTF_8))
    }
}

