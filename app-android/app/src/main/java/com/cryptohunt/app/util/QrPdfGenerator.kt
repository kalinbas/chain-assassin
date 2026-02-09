package com.cryptohunt.app.util

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

object QrPdfGenerator {

    // A4 at 72 DPI: 595 x 842 points
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 40

    /**
     * Generate a single-page PDF with the player's QR code as large as possible.
     * This page is printed TWICE and attached to front and back of shirt.
     */
    fun generatePdf(
        context: Context,
        gameId: Int,
        playerNumber: Int,
        gameName: String
    ): Uri {
        val payload = QrGenerator.buildPayload(gameId, playerNumber)
        val qrBitmap = QrGenerator.generateQrBitmap(
            payload, 1024,
            foreground = Color.BLACK,
            background = Color.WHITE
        )

        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas

        // White background
        canvas.drawColor(Color.WHITE)

        // Small title at top
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT
            color = Color.DKGRAY
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            "Chain-Assassin \u2014 $gameName",
            PAGE_WIDTH / 2f, MARGIN.toFloat() + 14f, titlePaint
        )

        // Large player number
        val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 140f
            typeface = Typeface.DEFAULT_BOLD
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            "#$playerNumber",
            PAGE_WIDTH / 2f, MARGIN + 150f, numberPaint
        )

        // QR code as big as possible
        val qrTop = MARGIN + 175f
        val availableWidth = PAGE_WIDTH - 2 * MARGIN
        val availableHeight = PAGE_HEIGHT - qrTop - MARGIN - 30f // leave room for bottom text
        val qrSize = minOf(availableWidth, availableHeight.toInt())
        val qrLeft = (PAGE_WIDTH - qrSize) / 2f

        val qrRect = RectF(qrLeft, qrTop, qrLeft + qrSize, qrTop + qrSize)
        canvas.drawBitmap(qrBitmap, null, qrRect, null)

        // Small instruction at bottom
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10f
            color = Color.GRAY
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            "Print this page twice. Attach to front and back of shirt.",
            PAGE_WIDTH / 2f, PAGE_HEIGHT - MARGIN.toFloat(), footerPaint
        )

        document.finishPage(page)

        // Write to cache directory
        val file = File(context.cacheDir, "cryptohunt_qr_${gameId}_p${playerNumber}.pdf")
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()
        qrBitmap.recycle()

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    /** Launch a share/print intent for the PDF. */
    fun sharePdf(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Print QR Code"))
    }
}
