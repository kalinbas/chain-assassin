package com.cryptohunt.app.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

object QrGenerator {

    fun generateQrBitmap(
        content: String,
        size: Int = 512,
        foreground: Int = Color.WHITE,
        background: Int = Color.TRANSPARENT
    ): Bitmap {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(
            content,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(EncodeHintType.MARGIN to 2)
        )
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) foreground else background)
            }
        }
        return bitmap
    }

    // QR encoding: gameId * 10000 + playerNumber, obfuscated with multiplicative cipher
    private const val QR_MULTIPLIER = 10000L
    private const val QR_PRIME = 2147483647L       // 2^31 - 1
    private const val QR_SCRAMBLE = 1588635695L    // multiplier
    private const val QR_UNSCRAMBLE = 1799631288L  // modular inverse

    /**
     * Build an obfuscated pure numeric QR payload.
     * Output is a random-looking 9-10 digit number in QR numeric mode.
     */
    fun buildPayload(gameId: Int, playerNumber: Int): String {
        val n = gameId.toLong() * QR_MULTIPLIER + playerNumber
        val scrambled = (n * QR_SCRAMBLE) % QR_PRIME
        return scrambled.toString()
    }

    /**
     * Parse an obfuscated numeric QR payload.
     * Returns (gameId, playerNumber) as strings, or null if invalid.
     */
    fun parsePayload(raw: String): Pair<String, String>? {
        val scrambled = raw.toLongOrNull() ?: return null
        if (scrambled < 1) return null
        val original = (scrambled * QR_UNSCRAMBLE) % QR_PRIME
        val playerNumber = (original % QR_MULTIPLIER).toInt()
        val gameId = (original / QR_MULTIPLIER).toInt()
        if (gameId < 1 || playerNumber < 1) return null
        return gameId.toString() to playerNumber.toString()
    }
}
