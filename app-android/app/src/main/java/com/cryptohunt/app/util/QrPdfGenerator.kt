package com.cryptohunt.app.util

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.DateFormat
import java.util.Date

object QrPdfGenerator {

    // US Letter at 72 DPI: 612 x 792 points
    private const val PAGE_WIDTH = 612
    private const val PAGE_HEIGHT = 792
    // Keep a small safety margin so QR remains printable on most printers.
    private const val PRINT_MARGIN = 18f
    private const val HEADER_NAME_TEXT_SIZE = 12f
    private const val HEADER_META_TEXT_SIZE = 10f
    private const val HEADER_LINE_GAP = 2f
    private const val HEADER_QR_GAP = 8f
    private const val NUMBER_MIN_SIZE = 72f
    private const val NUMBER_MAX_SIZE = 420f

    /**
     * Generate a single-page PDF with the player's QR code as large as possible.
     * This page is printed TWICE and attached to front and back of shirt.
     */
    fun generatePdf(
        context: Context,
        gameId: Int,
        playerNumber: Int,
        gameName: String,
        gameStartTimeMillis: Long = 0L
    ): Uri {
        val file = generatePdfFile(
            context = context,
            gameId = gameId,
            playerNumber = playerNumber,
            gameName = gameName,
            gameStartTimeMillis = gameStartTimeMillis
        )

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    /**
     * Generate and save the player QR PDF and return the underlying file.
     */
    fun generatePdfFile(
        context: Context,
        gameId: Int,
        playerNumber: Int,
        gameName: String,
        gameStartTimeMillis: Long = 0L,
        outputDirectory: File = context.cacheDir
    ): File {
        val payload = QrGenerator.buildPayload(gameId, playerNumber)

        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas

        // White background
        canvas.drawColor(Color.WHITE)

        // Small centered header above QR: game name + local date/time.
        val headerNamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            color = Color.DKGRAY
            textAlign = Paint.Align.CENTER
            textSize = HEADER_NAME_TEXT_SIZE
        }
        val headerMetaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT
            color = Color.DKGRAY
            textAlign = Paint.Align.CENTER
            textSize = HEADER_META_TEXT_SIZE
        }
        val headerName = ellipsizeEnd(
            text = gameName,
            paint = headerNamePaint,
            maxWidth = PAGE_WIDTH - (PRINT_MARGIN * 2f)
        )
        val headerMeta = formatLocalDateTime(gameStartTimeMillis)

        val headerNameBounds = textBounds(headerNamePaint, headerName)
        val headerMetaBounds = textBounds(headerMetaPaint, headerMeta)
        val headerNameTop = PRINT_MARGIN
        val headerMetaTop = headerNameTop + headerNameBounds.height() + HEADER_LINE_GAP
        val headerBottom = headerMetaTop + headerMetaBounds.height()

        canvas.drawText(
            headerName,
            PAGE_WIDTH / 2f,
            baselineForTop(headerNameTop, headerNameBounds),
            headerNamePaint
        )
        canvas.drawText(
            headerMeta,
            PAGE_WIDTH / 2f,
            baselineForTop(headerMetaTop, headerMetaBounds),
            headerMetaPaint
        )

        // Max square QR that still fits within printable margins on Letter.
        val qrSize = minOf(
            (PAGE_WIDTH - PRINT_MARGIN * 2f).toInt(),
            (PAGE_HEIGHT - PRINT_MARGIN * 2f).toInt()
        ).coerceAtLeast(1)
        val qrLeft = (PAGE_WIDTH - qrSize) / 2f
        val qrTop = maxOf(PRINT_MARGIN, headerBottom + HEADER_QR_GAP)
        val qrBitmap = QrGenerator.generateQrBitmap(
            content = payload,
            size = qrSize,
            foreground = Color.BLACK,
            background = Color.WHITE
        )
        val bitmapPaint = Paint().apply { isFilterBitmap = false }
        canvas.drawBitmap(qrBitmap, qrLeft, qrTop, bitmapPaint)

        // Render a large player number beneath the QR code (without '#').
        val numberText = playerNumber.toString()
        val numberBottom = PAGE_HEIGHT - PRINT_MARGIN
        val numberAreaHeight = (numberBottom - (qrTop + qrSize)).coerceAtLeast(1f)
        val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
        }
        numberPaint.textSize = fitTextSize(
            paint = numberPaint,
            text = numberText,
            maxWidth = PAGE_WIDTH - (PRINT_MARGIN * 2f),
            maxHeight = numberAreaHeight,
            minSize = NUMBER_MIN_SIZE,
            maxSize = NUMBER_MAX_SIZE
        )
        val textBounds = textBounds(numberPaint, numberText)
        canvas.drawText(
            numberText,
            PAGE_WIDTH / 2f,
            baselineForBottom(numberBottom, textBounds),
            numberPaint
        )

        document.finishPage(page)

        // Write to output directory
        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs()
        }
        val file = File(outputDirectory, "cryptohunt_qr_${gameId}_p${playerNumber}.pdf")
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()
        qrBitmap.recycle()

        return file
    }

    /** Launch a share/print intent for the PDF. */
    fun sharePdf(context: Context, uri: Uri) {
        val printManager = context.getSystemService(PrintManager::class.java)
        if (printManager != null) {
            val printAttributes = PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.NA_LETTER)
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
                .build()

            val adapter = object : PrintDocumentAdapter() {
                override fun onLayout(
                    oldAttributes: PrintAttributes?,
                    newAttributes: PrintAttributes?,
                    cancellationSignal: CancellationSignal?,
                    callback: LayoutResultCallback,
                    extras: Bundle?
                ) {
                    if (cancellationSignal?.isCanceled == true) {
                        callback.onLayoutCancelled()
                        return
                    }

                    val info = PrintDocumentInfo.Builder("cryptohunt-qr.pdf")
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(1)
                        .build()
                    callback.onLayoutFinished(info, oldAttributes != newAttributes)
                }

                override fun onWrite(
                    pages: Array<PageRange>,
                    destination: ParcelFileDescriptor,
                    cancellationSignal: CancellationSignal,
                    callback: WriteResultCallback
                ) {
                    if (cancellationSignal.isCanceled) {
                        callback.onWriteCancelled()
                        return
                    }

                    try {
                        context.contentResolver.openInputStream(uri).use { input ->
                            if (input == null) {
                                callback.onWriteFailed("Could not open PDF source")
                                return
                            }
                            FileOutputStream(destination.fileDescriptor).use { output ->
                                copyStream(input, output)
                            }
                        }
                        callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                    } catch (e: IOException) {
                        callback.onWriteFailed(e.message ?: "Print failed")
                    }
                }
            }

            printManager.print("CryptoHunt QR", adapter, printAttributes)
            return
        }

        // Fallback for environments without PrintManager.
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Print QR Code"))
    }

    private fun baselineForBottom(bottom: Float, bounds: Rect): Float {
        return bottom - bounds.bottom
    }

    private fun baselineForTop(top: Float, bounds: Rect): Float {
        return top - bounds.top
    }

    private fun textHeight(paint: Paint, text: String): Float {
        val bounds = textBounds(paint, text)
        return bounds.height().toFloat()
    }

    private fun textBounds(paint: Paint, text: String): Rect {
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        return bounds
    }

    private fun ellipsizeEnd(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        if (maxWidth <= 0f) return ""

        val suffix = "..."
        val suffixWidth = paint.measureText(suffix)
        if (suffixWidth >= maxWidth) return suffix

        var end = text.length
        while (end > 0) {
            val candidate = text.substring(0, end) + suffix
            if (paint.measureText(candidate) <= maxWidth) {
                return candidate
            }
            end--
        }
        return suffix
    }

    private fun fitTextSize(
        paint: Paint,
        text: String,
        maxWidth: Float,
        maxHeight: Float,
        minSize: Float,
        maxSize: Float
    ): Float {
        var low = minSize
        var high = maxSize
        repeat(14) {
            val mid = (low + high) / 2f
            paint.textSize = mid
            val fitsWidth = paint.measureText(text) <= maxWidth
            val fitsHeight = textHeight(paint, text) <= maxHeight
            if (fitsWidth && fitsHeight) {
                low = mid
            } else {
                high = mid
            }
        }
        return low
    }

    private fun copyStream(input: InputStream, output: FileOutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead <= 0) break
            output.write(buffer, 0, bytesRead)
        }
        output.flush()
    }

    private fun formatLocalDateTime(epochMillis: Long): String {
        if (epochMillis <= 0L) return "Local date/time TBD"
        val date = Date(epochMillis)
        val datePart = DateFormat.getDateInstance(DateFormat.MEDIUM).format(date)
        val timePart = DateFormat.getTimeInstance(DateFormat.SHORT).format(date)
        return "$datePart • $timePart (local)"
    }
}
