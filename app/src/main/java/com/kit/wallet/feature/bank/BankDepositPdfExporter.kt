package com.kit.wallet.feature.bank

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.kit.wallet.data.repository.BankDeposit
import com.kit.wallet.ui.model.Money
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Creates a private, branded instruction sheet and grants access only to the chosen share target. */
internal object BankDepositPdfExporter {
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val LEFT = 44f
    private const val RIGHT = PAGE_WIDTH - 44f
    private const val KIT_NAVY = 0xFF071A33.toInt()
    private const val KIT_GREEN = 0xFF19C787.toInt()
    private const val TEXT = 0xFF122033.toInt()
    private const val MUTED = 0xFF5D6877.toInt()
    private const val PALE_GREEN = 0xFFE9FAF3.toInt()

    suspend fun exportAndShare(context: Context, deposit: BankDeposit) {
        val file = withContext(Dispatchers.IO) { render(context, deposit) }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.chatmedia",
            file,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_SUBJECT,
                "Kit Pay deposit ${deposit.reference}",
            )
            clipData = ClipData.newUri(context.contentResolver, "Deposit instructions", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share deposit instructions")
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    private fun render(context: Context, deposit: BankDeposit): File {
        val directory = File(context.cacheDir, "bank-deposit-documents").apply { mkdirs() }
        check(directory.isDirectory) { "The PDF export folder is unavailable" }
        val safeReference = deposit.reference.filter { it.isLetterOrDigit() || it == '-' }
        val output = File(directory, "Kit-Pay-deposit-$safeReference.pdf")
        val document = PdfDocument()
        try {
            var pageNumber = 1
            var page = document.startPage(
                PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create(),
            )
            var canvas = page.canvas
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            canvas.drawColor(Color.WHITE)
            paint.color = KIT_NAVY
            canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), 154f, paint)
            paint.color = KIT_GREEN
            canvas.drawRect(0f, 146f, PAGE_WIDTH.toFloat(), 154f, paint)

            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = 25f
            paint.color = Color.WHITE
            canvas.drawText("KIT PAY", LEFT, 54f, paint)
            paint.textSize = 20f
            canvas.drawText("Bank deposit instructions", LEFT, 96f, paint)
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            paint.textSize = 10f
            paint.color = 0xFFD7E1EC.toInt()
            canvas.drawText("Securely add money to your Kit Pay wallet", LEFT, 119f, paint)

            var y = 184f
            paint.color = PALE_GREEN
            canvas.drawRoundRect(LEFT, y, RIGHT, y + 106f, 14f, 14f, paint)
            paint.color = MUTED
            paint.textSize = 10f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText("EXACT AMOUNT", LEFT + 18f, y + 25f, paint)
            paint.color = TEXT
            paint.textSize = 20f
            canvas.drawText(
                Money.format(
                    deposit.amountMinor,
                    deposit.currencyCode,
                    deposit.currencyScale,
                ),
                LEFT + 18f,
                y + 52f,
                paint,
            )
            paint.color = MUTED
            paint.textSize = 10f
            canvas.drawText("PAYMENT REFERENCE", LEFT + 18f, y + 75f, paint)
            paint.color = KIT_NAVY
            paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            paint.textSize = 16f
            canvas.drawText(deposit.reference, LEFT + 18f, y + 97f, paint)

            y += 137f
            y = sectionTitle(canvas, paint, "Transfer to this account", y)
            val account = deposit.fundingAccount
            y = fact(canvas, paint, "Bank", account.bankName, y)
            y = fact(canvas, paint, "Account name", account.accountName, y)
            y = fact(canvas, paint, "Account number", account.accountNumber, y, monospace = true)
            account.branchName?.takeIf(String::isNotBlank)?.let {
                y = fact(canvas, paint, "Branch", it, y)
            }
            account.branchCode?.takeIf(String::isNotBlank)?.let {
                y = fact(canvas, paint, "Branch code", it, y, monospace = true)
            }
            account.swiftCode?.takeIf(String::isNotBlank)?.let {
                y = fact(canvas, paint, "SWIFT / BIC", it, y, monospace = true)
            }

            y += 12f
            if (y > 570f) {
                drawFooter(canvas, paint, deposit)
                document.finishPage(page)
                pageNumber += 1
                page = document.startPage(
                    PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create(),
                )
                canvas = page.canvas
                y = drawContinuationHeader(canvas, paint, deposit.reference)
            }
            y = sectionTitle(canvas, paint, "How to complete the deposit", y)
            y = paragraph(
                canvas,
                paint,
                "1. Send the exact amount shown above to the receiving account.",
                y,
            )
            y = paragraph(
                canvas,
                paint,
                "2. Enter ${deposit.reference} as the bank payment reference. This links the transfer to your wallet.",
                y,
            )
            y = paragraph(
                canvas,
                paint,
                "3. Return to Kit Pay and upload the bank receipt. Your wallet is credited only after approval.",
                y,
            )
            account.instructions?.takeIf(String::isNotBlank)?.let {
                y += 4f
                paint.textSize = 10f
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                val requiredHeight = wrappedLines(it, paint, RIGHT - LEFT).size * 15f + 3f
                if (y + requiredHeight > PAGE_HEIGHT - 78f) {
                    drawFooter(canvas, paint, deposit)
                    document.finishPage(page)
                    pageNumber += 1
                    page = document.startPage(
                        PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create(),
                    )
                    canvas = page.canvas
                    y = drawContinuationHeader(canvas, paint, deposit.reference)
                    y = sectionTitle(canvas, paint, "Additional bank instructions", y)
                }
                y = paragraph(canvas, paint, it, y)
            }

            val expiry = formatTime(deposit.expiresAt)
            y += 8f
            paint.color = MUTED
            paint.textSize = 9f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            canvas.drawText("Reference valid until $expiry", LEFT, y, paint)

            drawFooter(canvas, paint, deposit)
            document.finishPage(page)
            FileOutputStream(output).use(document::writeTo)
        } finally {
            document.close()
        }
        check(output.isFile && output.length() > 0L) { "The PDF could not be created" }
        return output
    }

    private fun sectionTitle(canvas: android.graphics.Canvas, paint: Paint, text: String, y: Float): Float {
        paint.color = KIT_NAVY
        paint.textSize = 14f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText(text, LEFT, y, paint)
        paint.color = KIT_GREEN
        canvas.drawRect(LEFT, y + 8f, LEFT + 38f, y + 11f, paint)
        return y + 29f
    }

    private fun fact(
        canvas: android.graphics.Canvas,
        paint: Paint,
        label: String,
        value: String,
        y: Float,
        monospace: Boolean = false,
    ): Float {
        paint.color = MUTED
        paint.textSize = 9.5f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        canvas.drawText(label, LEFT, y, paint)
        paint.color = TEXT
        paint.textSize = 11f
        paint.typeface = Typeface.create(
            if (monospace) Typeface.MONOSPACE else Typeface.DEFAULT,
            Typeface.BOLD,
        )
        val lines = wrappedLines(value, paint, RIGHT - (LEFT + 154f))
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, LEFT + 154f, y + index * 14f, paint)
        }
        return y + maxOf(23f, lines.size * 14f + 9f)
    }

    private fun paragraph(
        canvas: android.graphics.Canvas,
        paint: Paint,
        value: String,
        startY: Float,
    ): Float {
        paint.color = TEXT
        paint.textSize = 10f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        var y = startY
        wrappedLines(value, paint, RIGHT - LEFT).forEach { line ->
            canvas.drawText(line, LEFT, y, paint)
            y += 15f
        }
        return y + 3f
    }

    private fun wrappedLines(value: String, paint: Paint, maxWidth: Float): List<String> {
        val result = mutableListOf<String>()
        var line = ""
        value.trim().split(Regex("\\s+")).filter(String::isNotEmpty).forEach { word ->
            val pieces = if (paint.measureText(word) <= maxWidth) {
                listOf(word)
            } else {
                buildList {
                    var remaining = word
                    while (remaining.isNotEmpty()) {
                        var count = paint.breakText(remaining, true, maxWidth, null).coerceAtLeast(1)
                        count = count.coerceAtMost(remaining.length)
                        add(remaining.take(count))
                        remaining = remaining.drop(count)
                    }
                }
            }
            pieces.forEach { piece ->
                val candidate = if (line.isEmpty()) piece else "$line $piece"
                if (paint.measureText(candidate) <= maxWidth) {
                    line = candidate
                } else {
                    if (line.isNotEmpty()) result += line
                    line = piece
                }
            }
        }
        if (line.isNotEmpty()) result += line
        return result
    }

    private fun drawContinuationHeader(
        canvas: android.graphics.Canvas,
        paint: Paint,
        reference: String,
    ): Float {
        canvas.drawColor(Color.WHITE)
        paint.color = KIT_NAVY
        canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), 74f, paint)
        paint.color = Color.WHITE
        paint.textSize = 18f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("KIT PAY", LEFT, 35f, paint)
        paint.textSize = 10f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        canvas.drawText("Deposit $reference • continued", LEFT, 55f, paint)
        paint.color = KIT_GREEN
        canvas.drawRect(0f, 69f, PAGE_WIDTH.toFloat(), 74f, paint)
        return 106f
    }

    private fun drawFooter(
        canvas: android.graphics.Canvas,
        paint: Paint,
        deposit: BankDeposit,
    ) {
        paint.color = 0xFFF0F3F6.toInt()
        canvas.drawRect(0f, PAGE_HEIGHT - 58f, PAGE_WIDTH.toFloat(), PAGE_HEIGHT.toFloat(), paint)
        paint.color = MUTED
        paint.textSize = 8.5f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        canvas.drawText(
            "Generated by Kit Pay • Keep this document until your wallet is credited.",
            LEFT,
            PAGE_HEIGHT - 31f,
            paint,
        )
        canvas.drawText("Reference: ${deposit.reference}", LEFT, PAGE_HEIGHT - 17f, paint)
    }

    private fun formatTime(value: String): String = runCatching {
        Instant.parse(value).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
    }.getOrDefault(value)
}
