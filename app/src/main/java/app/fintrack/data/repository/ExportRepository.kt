package app.fintrack.data.repository

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.res.ResourcesCompat
import app.fintrack.R
import app.fintrack.domain.logic.CsvWriter
import app.fintrack.domain.logic.CurrencySummary
import app.fintrack.domain.logic.ExportTable
import app.fintrack.domain.logic.Money
import app.fintrack.domain.logic.TimeRange
import app.fintrack.domain.logic.XlsxWriter
import app.fintrack.domain.model.TransactionDetails
import app.fintrack.domain.model.TransactionType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

enum class ExportFormat(val title: String, val extension: String, val mime: String) {
    CSV("CSV", "csv", "text/csv"),
    XLSX("Excel", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    PDF("PDF", "pdf", "application/pdf"),
}

/** Выгрузка операций в файл, выбранный пользователем (системное окно «Сохранить»). */
@Singleton
class ExportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transactions: TransactionRepository,
) {
    /** Операции периода по порядку дат: в таблице старые сверху. */
    suspend fun load(range: TimeRange?, accountId: String?): List<TransactionDetails> =
        transactions.observeFiltered(range, null, accountId, null, false).first().asReversed()

    /** Записать файл. false — не удалось открыть или записать. */
    suspend fun write(uri: Uri, format: ExportFormat, items: List<TransactionDetails>, periodLabel: String): Boolean =
        withContext(Dispatchers.IO) {
            val zone = ZoneId.systemDefault()
            try {
                val out = context.contentResolver.openOutputStream(uri, "wt") ?: return@withContext false
                out.use { stream ->
                    when (format) {
                        ExportFormat.CSV -> stream.write(CsvWriter.write(ExportTable.operationsSheet(items, zone)).toByteArray(Charsets.UTF_8))
                        ExportFormat.XLSX -> XlsxWriter.write(
                            stream,
                            listOf(
                                ExportTable.operationsSheet(items, zone),
                                ExportTable.summarySheet(ExportTable.summarize(items), periodLabel),
                            ),
                        )
                        ExportFormat.PDF -> PdfReport(context, zone).write(stream, items, periodLabel)
                    }
                }
                true
            } catch (e: IOException) {
                false
            } catch (e: SecurityException) {
                false
            }
        }
}

/** Отчёт PDF на A4: итоги по валютам, расходы по категориям, список операций. Всегда светлый. */
private class PdfReport(context: Context, private val zone: ZoneId) {

    private val regular: Typeface = ResourcesCompat.getFont(context, R.font.inter_regular) ?: Typeface.DEFAULT
    private val bold: Typeface = ResourcesCompat.getFont(context, R.font.inter_semibold) ?: Typeface.DEFAULT_BOLD

    private val pageWidth = 595
    private val pageHeight = 842
    private val margin = 40f
    private val contentWidth = pageWidth - margin * 2
    private val bottom = pageHeight - 48f

    private val ink = Color.rgb(17, 24, 39)
    private val muted = Color.rgb(100, 112, 130)
    private val line = Color.rgb(226, 230, 238)
    private val accent = Color.rgb(47, 85, 228)
    private val income = Color.rgb(13, 148, 136)
    private val expense = Color.rgb(220, 53, 69)
    private val track = Color.rgb(238, 241, 247)

    private val doc = PdfDocument()
    private var page: PdfDocument.Page? = null
    private var canvas: Canvas = Canvas()
    private var pageNumber = 0
    private var y = margin

    private fun text(size: Float, color: Int = ink, face: Typeface = regular) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = face
    }

    private fun newPage() {
        finishPage()
        pageNumber++
        val p = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        page = p
        canvas = p.canvas
        y = margin
    }

    private fun finishPage() {
        val p = page ?: return
        val footer = text(8f, muted)
        canvas.drawText("FinTrack", margin, pageHeight - 26f, footer)
        val number = "Страница $pageNumber"
        canvas.drawText(number, pageWidth - margin - footer.measureText(number), pageHeight - 26f, footer)
        doc.finishPage(p)
        page = null
    }

    private fun ensure(height: Float) {
        if (y + height > bottom) newPage()
    }

    private fun fit(s: String, paint: TextPaint, width: Float): String =
        TextUtils.ellipsize(s, paint, width, TextUtils.TruncateAt.END).toString()

    private fun right(s: String, x: Float, baseline: Float, paint: TextPaint) = canvas.drawText(s, x - paint.measureText(s), baseline, paint)

    fun write(out: OutputStream, items: List<TransactionDetails>, periodLabel: String) {
        try {
            newPage()
            header(periodLabel, items.size)
            ExportTable.summarize(items).forEach(::summary)
            operations(items)
            finishPage()
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    private fun header(periodLabel: String, count: Int) {
        canvas.drawText("Отчёт по финансам", margin, y + 20f, text(22f, ink, bold))
        y += 40f
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru")))
        canvas.drawText("$periodLabel · операций: $count · сформирован $today", margin, y, text(10f, muted))
        y += 22f
    }

    private fun summary(s: CurrencySummary) {
        ensure(96f)
        // Три плитки: доходы, расходы, итог
        val gap = 10f
        val w = (contentWidth - gap * 2) / 3
        val tiles = listOf(
            Triple("Доходы", s.incomeMinor, income),
            Triple("Расходы", s.expenseMinor, expense),
            Triple("Итог", s.netMinor, accent),
        )
        tiles.forEachIndexed { i, (label, value, color) ->
            val x = margin + i * (w + gap)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = track }
            canvas.drawRoundRect(RectF(x, y, x + w, y + 56f), 8f, 8f, fill)
            canvas.drawText(label, x + 12f, y + 20f, text(9f, muted))
            canvas.drawText(fit(Money.format(value, s.currency), text(13f, color, bold), w - 24f), x + 12f, y + 41f, text(13f, color, bold))
        }
        y += 72f

        if (s.categories.isEmpty()) return
        ensure(40f)
        canvas.drawText("Расходы по категориям, ${s.currency}", margin, y + 10f, text(11f, ink, bold))
        y += 24f
        val total = s.expenseMinor.coerceAtLeast(1)
        val top = s.categories.first().minor.coerceAtLeast(1)
        val nameWidth = 150f
        val amountWidth = 110f
        val barX = margin + nameWidth + 8f
        val barWidth = contentWidth - nameWidth - amountWidth - 16f
        for (c in s.categories) {
            ensure(20f)
            val label = text(9.5f)
            canvas.drawText(fit(c.name, label, nameWidth), margin, y + 10f, label)
            val barTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = track }
            val barFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
            canvas.drawRoundRect(RectF(barX, y + 3f, barX + barWidth, y + 11f), 4f, 4f, barTrack)
            val fillWidth = (barWidth * c.minor / top).coerceAtLeast(3f)
            canvas.drawRoundRect(RectF(barX, y + 3f, barX + fillWidth, y + 11f), 4f, 4f, barFill)
            val share = c.minor * 100 / total
            right("${Money.format(c.minor, s.currency)} · $share %", pageWidth - margin, y + 10f, text(9.5f, muted))
            y += 18f
        }
        y += 16f
    }

    private fun operations(items: List<TransactionDetails>) {
        if (items.isEmpty()) return
        ensure(60f)
        canvas.drawText("Операции", margin, y + 12f, text(13f, ink, bold))
        y += 26f
        tableHeader()
        val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
        val small = text(9f)
        val faint = text(8f, muted)
        for (d in items) {
            if (y + 28f > bottom) {
                newPage()
                tableHeader()
            }
            val t = d.transaction
            val title = when {
                t.type == TransactionType.TRANSFER -> "Перевод → ${d.toAccountName ?: ""}"
                t.type == TransactionType.ADJUSTMENT -> "Корректировка"
                else -> d.categoryName ?: "Без категории"
            }
            val note = listOf(t.payee, t.comment).filter { it.isNotBlank() }.joinToString(" · ")
            canvas.drawText(ExportTable.moment(d, zone).format(dateFormat), COL_DATE + margin, y + 10f, faint)
            canvas.drawText(fit(title, small, 190f), COL_TITLE + margin, y + 10f, small)
            if (note.isNotEmpty()) canvas.drawText(fit(note, faint, 190f), COL_TITLE + margin, y + 21f, faint)
            canvas.drawText(fit(d.accountName, faint, 90f), COL_ACCOUNT + margin, y + 10f, faint)
            val signed = ExportTable.signedAmount(d)
            val amountColor = when (t.type) {
                TransactionType.INCOME -> income
                TransactionType.EXPENSE -> expense
                else -> muted
            }
            right(Money.format(signed, d.accountCurrency, forceSign = t.type == TransactionType.INCOME), pageWidth - margin, y + 10f, text(9f, amountColor, bold))
            if (t.originalAmountMinor != null && t.originalCurrency != null) {
                right(Money.format(t.originalAmountMinor, t.originalCurrency), pageWidth - margin, y + 21f, faint)
            }
            y += 26f
            canvas.drawLine(margin, y - 2f, pageWidth - margin, y - 2f, Paint().apply { color = line; strokeWidth = 0.5f })
        }
    }

    private fun tableHeader() {
        val h = text(8f, muted, bold)
        canvas.drawText("ДАТА", COL_DATE + margin, y + 8f, h)
        canvas.drawText("КАТЕГОРИЯ", COL_TITLE + margin, y + 8f, h)
        canvas.drawText("СЧЁТ", COL_ACCOUNT + margin, y + 8f, h)
        right("СУММА", pageWidth - margin, y + 8f, h)
        y += 14f
        canvas.drawLine(margin, y, pageWidth - margin, y, Paint().apply { color = ink; strokeWidth = 0.7f })
        y += 6f
    }

    private companion object {
        const val COL_DATE = 0f
        const val COL_TITLE = 82f
        const val COL_ACCOUNT = 280f
    }
}
