package com.cryptohunt.app.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

object QrGenerator {

    fun generateQrBitmap(
        content: String,
        size: Int = 512,
        foreground: Int = Color.WHITE,
        background: Int = Color.TRANSPARENT
    ): Bitmap {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) foreground else background)
            }
        }
        return bitmap
    }

    /** Build a QR payload for this player. */
    fun buildPayload(gameId: String, playerId: String): String {
        return "cryptohunt:$gameId:$playerId"
    }

    /** Parse a scanned QR payload. Returns (gameId, playerId) or null. */
    fun parsePayload(raw: String): Pair<String, String>? {
        val parts = raw.split(":")
        if (parts.size < 3 || parts[0] != "cryptohunt") return null
        return parts[1] to parts[2]
    }
}
