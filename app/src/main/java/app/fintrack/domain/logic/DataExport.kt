package app.fintrack.domain.logic

import app.fintrack.domain.model.TransactionDetails
import app.fintrack.domain.model.TransactionType
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Ячейка таблицы экспорта: CSV и Excel записывают её по-разному. */
sealed interface Cell {
    data object Empty : Cell
    data class Text(val value: String) : Cell
    data class Amount(val minor: Long, val currency: String) : Cell
    data class Moment(val value: LocalDateTime) : Cell
}

/** Лист таблицы; [widths] — ширина колонок в символах (для Excel). */
data class Sheet(val name: String, val headers: List<String>, val rows: List<List<Cell>>, val widths: List<Int>)

data class CategoryTotal(val name: String, val minor: Long)

/** Итоги периода в одной валюте. Переводы не доход и не расход; комиссия перевода — расход. */
data class CurrencySummary(
    val currency: String,
    val incomeMinor: Long,
    val expenseMinor: Long,
    /** Расходы по корневым категориям, крупные первыми. */
    val categories: List<CategoryTotal>,
) {
    val netMinor: Long get() = incomeMinor - expenseMinor
}

object ExportTable {

    const val FEES = "Комиссии переводов"
    private const val NO_CATEGORY = "Без категории"

    val HEADERS = listOf(
        "Дата", "Тип", "Счёт", "Счёт зачисления", "Категория", "Подкатегория", "Сумма", "Валюта",
        "Сумма зачисления", "Валюта зачисления", "Комиссия", "Получатель", "Комментарий", "Исходная сумма", "Исходная валюта",
    )
    private val WIDTHS = listOf(17, 14, 20, 20, 24, 20, 14, 8, 14, 10, 11, 26, 32, 14, 10)

    fun typeTitle(type: TransactionType): String = when (type) {
        TransactionType.INCOME -> "Доход"
        TransactionType.EXPENSE -> "Расход"
        TransactionType.TRANSFER -> "Перевод"
        TransactionType.ADJUSTMENT -> "Корректировка"
    }

    /** Сумма со знаком: со счёта — минус, на счёт — плюс; у корректировки знак свой. */
    fun signedAmount(item: TransactionDetails): Long {
        val t = item.transaction
        return when (t.type) {
            TransactionType.EXPENSE, TransactionType.TRANSFER -> -t.amountMinor
            else -> t.amountMinor
        }
    }

    private fun text(v: String?): Cell = if (v.isNullOrBlank()) Cell.Empty else Cell.Text(v)

    fun moment(item: TransactionDetails, zone: ZoneId): LocalDateTime =
        Instant.ofEpochMilli(item.transaction.dateTime).atZone(zone).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES)

    /** Строки по порядку [items]; у подкатегории в «Категории» — её родитель. */
    fun rows(items: List<TransactionDetails>, zone: ZoneId): List<List<Cell>> = items.map { d ->
        val t = d.transaction
        val toCurrency = d.toAccountCurrency
        listOf(
            Cell.Moment(moment(d, zone)),
            Cell.Text(typeTitle(t.type)),
            Cell.Text(d.accountName),
            text(d.toAccountName),
            text(d.parentCategoryName ?: d.categoryName),
            text(if (d.parentCategoryName != null) d.categoryName else null),
            Cell.Amount(signedAmount(d), d.accountCurrency),
            Cell.Text(d.accountCurrency),
            if (t.toAmountMinor != null && toCurrency != null) Cell.Amount(t.toAmountMinor, toCurrency) else Cell.Empty,
            text(toCurrency),
            if (t.feeMinor > 0) Cell.Amount(t.feeMinor, d.accountCurrency) else Cell.Empty,
            text(t.payee),
            text(t.comment),
            if (t.originalAmountMinor != null && t.originalCurrency != null) Cell.Amount(t.originalAmountMinor, t.originalCurrency) else Cell.Empty,
            text(t.originalCurrency),
        )
    }

    /** Итоги по валютам счетов, те же правила, что в статистике. */
    fun summarize(items: List<TransactionDetails>): List<CurrencySummary> =
        items.groupBy { it.accountCurrency }.map { (currency, list) ->
            val byCategory = HashMap<String, Long>()
            var income = 0L
            var expense = 0L
            for (d in list) {
                val t = d.transaction
                when (t.type) {
                    TransactionType.INCOME -> income += t.amountMinor
                    TransactionType.EXPENSE -> {
                        expense += t.amountMinor
                        val name = d.parentCategoryName ?: d.categoryName ?: NO_CATEGORY
                        byCategory[name] = (byCategory[name] ?: 0L) + t.amountMinor
                    }
                    TransactionType.TRANSFER -> if (t.feeMinor > 0) {
                        expense += t.feeMinor
                        byCategory[FEES] = (byCategory[FEES] ?: 0L) + t.feeMinor
                    }
                    TransactionType.ADJUSTMENT -> Unit
                }
            }
            CurrencySummary(
                currency,
                income,
                expense,
                byCategory.map { CategoryTotal(it.key, it.value) }.sortedByDescending { it.minor },
            )
        }.sortedBy { it.currency }

    fun operationsSheet(items: List<TransactionDetails>, zone: ZoneId) = Sheet("Операции", HEADERS, rows(items, zone), WIDTHS)

    fun summarySheet(summary: List<CurrencySummary>, periodLabel: String): Sheet {
        val rows = ArrayList<List<Cell>>()
        rows += listOf(Cell.Text("Период"), Cell.Text(periodLabel), Cell.Empty)
        for (s in summary) {
            rows += listOf(Cell.Empty, Cell.Empty, Cell.Empty)
            rows += listOf(Cell.Text("Доходы"), Cell.Amount(s.incomeMinor, s.currency), Cell.Text(s.currency))
            rows += listOf(Cell.Text("Расходы"), Cell.Amount(s.expenseMinor, s.currency), Cell.Text(s.currency))
            rows += listOf(Cell.Text("Итог"), Cell.Amount(s.netMinor, s.currency), Cell.Text(s.currency))
            s.categories.forEach { rows += listOf(Cell.Text(it.name), Cell.Amount(it.minor, s.currency), Cell.Text(s.currency)) }
        }
        return Sheet("Итоги", listOf("Показатель", "Сумма", "Валюта"), rows, listOf(32, 16, 9))
    }
}

/**
 * CSV для русского Excel: разделитель «;», запятая в суммах, BOM (иначе кириллица ломается), CRLF.
 * Текст, похожий на формулу, экранируется апострофом — чужая выписка не выполнится как формула.
 */
object CsvWriter {

    private const val SEP = ';'
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    fun write(sheet: Sheet): String = buildString {
        append('﻿')
        append(sheet.headers.joinToString(SEP.toString()) { quote(it) }).append("\r\n")
        sheet.rows.forEach { row -> append(row.joinToString(SEP.toString()) { cell(it) }).append("\r\n") }
    }

    private fun cell(c: Cell): String = when (c) {
        Cell.Empty -> ""
        is Cell.Amount -> Money.fromMinor(c.minor, c.currency).toPlainString().replace('.', ',')
        is Cell.Moment -> c.value.format(DATE)
        is Cell.Text -> quote(if (c.value.firstOrNull() in FORMULA_START) "'" + c.value else c.value)
    }

    private fun quote(v: String): String =
        if (v.any { it == SEP || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    private val FORMULA_START = setOf('=', '+', '-', '@', '\t')
}

/**
 * Минимальный файл Excel без библиотек: XLSX — это ZIP с несколькими XML. Строки записаны прямо
 * в ячейках (inlineStr), суммы и даты — настоящими числами, чтобы работали формулы и сортировка.
 */
object XlsxWriter {

    // Индексы стилей из styles(): 0 — обычный, 1 — шапка, 2 — дата, 3 — сумма
    private const val STYLE_HEADER = 1
    private const val STYLE_DATE = 2
    private const val STYLE_AMOUNT = 3

    fun write(out: OutputStream, sheets: List<Sheet>) {
        ZipOutputStream(out).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", contentTypes(sheets.size))
            put("_rels/.rels", ROOT_RELS)
            put("xl/workbook.xml", workbook(sheets))
            put("xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            put("xl/styles.xml", STYLES)
            sheets.forEachIndexed { i, s -> put("xl/worksheets/sheet${i + 1}.xml", sheetXml(s)) }
        }
    }

    private const val XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""
    private const val MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PKG = "http://schemas.openxmlformats.org/package/2006/relationships"

    private const val ROOT_RELS = XML + """<Relationships xmlns="$PKG">""" +
        """<Relationship Id="rId1" Type="$REL/officeDocument" Target="xl/workbook.xml"/></Relationships>"""

    private const val STYLES = XML + """<styleSheet xmlns="$MAIN">""" +
        """<numFmts count="2"><numFmt numFmtId="164" formatCode="dd\.mm\.yyyy\ hh:mm"/><numFmt numFmtId="165" formatCode="#,##0.00"/></numFmts>""" +
        """<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font></fonts>""" +
        """<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>""" +
        """<fill><patternFill patternType="solid"><fgColor rgb="FF2F55E4"/><bgColor indexed="64"/></patternFill></fill></fills>""" +
        """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
        """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
        """<cellXfs count="4"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
        """<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>""" +
        """<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
        """<xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs>""" +
        """<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""

    private fun contentTypes(count: Int) = XML + """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
        """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
        """<Default Extension="xml" ContentType="application/xml"/>""" +
        """<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
        """<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""" +
        (1..count).joinToString("") {
            """<Override PartName="/xl/worksheets/sheet$it.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>"""
        } + "</Types>"

    private fun workbook(sheets: List<Sheet>) = XML + """<workbook xmlns="$MAIN" xmlns:r="$REL"><sheets>""" +
        sheets.withIndex().joinToString("") { (i, s) -> """<sheet name="${esc(s.name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""" } +
        "</sheets></workbook>"

    private fun workbookRels(count: Int) = XML + """<Relationships xmlns="$PKG">""" +
        (1..count).joinToString("") { """<Relationship Id="rId$it" Type="$REL/worksheet" Target="worksheets/sheet$it.xml"/>""" } +
        """<Relationship Id="rId${count + 1}" Type="$REL/styles" Target="styles.xml"/></Relationships>"""

    private fun sheetXml(s: Sheet): String = buildString {
        append(XML).append("""<worksheet xmlns="$MAIN">""")
        append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        append("<cols>")
        s.widths.forEachIndexed { i, w -> append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""") }
        append("</cols><sheetData>")
        append("""<row r="1">""")
        s.headers.forEachIndexed { i, h -> append(textCell(ref(i, 1), h, STYLE_HEADER)) }
        append("</row>")
        s.rows.forEachIndexed { r, row ->
            val line = r + 2
            append("""<row r="$line">""")
            row.forEachIndexed { c, cell ->
                val at = ref(c, line)
                when (cell) {
                    Cell.Empty -> Unit
                    is Cell.Text -> append(textCell(at, cell.value, 0))
                    is Cell.Amount -> append("""<c r="$at" s="$STYLE_AMOUNT"><v>${Money.fromMinor(cell.minor, cell.currency).toPlainString()}</v></c>""")
                    is Cell.Moment -> append("""<c r="$at" s="$STYLE_DATE"><v>${serial(cell.value)}</v></c>""")
                }
            }
            append("</row>")
        }
        append("</sheetData>")
        if (s.rows.isNotEmpty()) append("""<autoFilter ref="A1:${ref(s.headers.lastIndex, s.rows.size + 1)}"/>""")
        append("</worksheet>")
    }

    private fun textCell(at: String, v: String, style: Int) =
        """<c r="$at" s="$style" t="inlineStr"><is><t xml:space="preserve">${esc(v)}</t></is></c>"""

    /** Колонка 0 → A, 26 → AA. */
    fun ref(col: Int, row: Int): String {
        var n = col
        var letters = ""
        do {
            letters = ('A' + n % 26) + letters
            n = n / 26 - 1
        } while (n >= 0)
        return "$letters$row"
    }

    /** Дата Excel: дни от 30.12.1899, время — дробная часть. */
    fun serial(t: LocalDateTime): String {
        val days = ChronoUnit.DAYS.between(java.time.LocalDate.of(1899, 12, 30), t.toLocalDate())
        val fraction = t.toLocalTime().toSecondOfDay() / 86_400.0
        return java.math.BigDecimal(days + fraction).setScale(10, java.math.RoundingMode.HALF_UP).toPlainString()
    }

    /** XML не допускает управляющие символы — они выбрасываются, остальное экранируется. */
    private fun esc(v: String): String = buildString(v.length) {
        for (ch in v) {
            when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                ch < ' ' && ch != '\t' && ch != '\n' && ch != '\r' -> Unit
                else -> append(ch)
            }
        }
    }
}
