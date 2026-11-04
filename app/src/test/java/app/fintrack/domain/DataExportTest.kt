package app.fintrack.domain

import app.fintrack.domain.logic.Cell
import app.fintrack.domain.logic.CsvReader
import app.fintrack.domain.logic.CsvWriter
import app.fintrack.domain.logic.ExportTable
import app.fintrack.domain.logic.Sheet
import app.fintrack.domain.logic.SignRule
import app.fintrack.domain.logic.StatementParser
import app.fintrack.domain.logic.XlsxWriter
import app.fintrack.domain.model.Transaction
import app.fintrack.domain.model.TransactionDetails
import app.fintrack.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class DataExportTest {

    private val zone = ZoneId.of("Europe/Minsk")
    private val at = LocalDateTime.of(2026, 11, 3, 14, 30).atZone(zone).toInstant().toEpochMilli()

    private fun item(
        type: TransactionType,
        amount: Long,
        category: String? = "Продукты",
        parent: String? = null,
        toAccount: String? = null,
        toAmount: Long? = null,
        fee: Long = 0,
        payee: String = "",
        comment: String = "",
        currency: String = "BYN",
        original: Pair<Long, String>? = null,
    ) = TransactionDetails(
        transaction = Transaction(
            id = "t", type = type, accountId = "a", toAccountId = toAccount?.let { "b" }, amountMinor = amount, toAmountMinor = toAmount,
            feeMinor = fee, categoryId = null, dateTime = at, payee = payee, comment = comment, isFavorite = false, createdAt = 0, updatedAt = 0,
            originalAmountMinor = original?.first, originalCurrency = original?.second,
        ),
        accountName = "Карта", accountCurrency = currency, toAccountName = toAccount, toAccountCurrency = toAccount?.let { "USD" },
        categoryName = category, categoryIcon = null, categoryColor = null, parentCategoryName = parent,
    )

    @Test
    fun tableKeepsSignsAndSplitsSubcategory() {
        val rows = ExportTable.rows(
            listOf(
                item(TransactionType.EXPENSE, 12_50, category = "Овощи", parent = "Продукты", payee = "Евроопт"),
                item(TransactionType.INCOME, 100_00, category = "Зарплата"),
                item(TransactionType.TRANSFER, 30_00, category = null, toAccount = "Доллары", toAmount = 10_00, fee = 1_00),
                item(TransactionType.ADJUSTMENT, -5_00, category = null),
            ),
            zone,
        )
        assertEquals(Cell.Amount(-12_50, "BYN"), rows[0][6])
        assertEquals(Cell.Text("Продукты"), rows[0][4])
        assertEquals(Cell.Text("Овощи"), rows[0][5])
        assertEquals(Cell.Amount(100_00, "BYN"), rows[1][6])
        assertEquals(Cell.Amount(-30_00, "BYN"), rows[2][6])
        assertEquals(Cell.Amount(10_00, "USD"), rows[2][8])
        assertEquals(Cell.Amount(1_00, "BYN"), rows[2][10])
        assertEquals(Cell.Amount(-5_00, "BYN"), rows[3][6])
        assertEquals(Cell.Moment(LocalDateTime.of(2026, 11, 3, 14, 30)), rows[0][0])
    }

    @Test
    fun summaryFollowsStatisticsRules() {
        val s = ExportTable.summarize(
            listOf(
                item(TransactionType.EXPENSE, 10_00, category = "Овощи", parent = "Продукты"),
                item(TransactionType.EXPENSE, 5_00, category = "Продукты"),
                item(TransactionType.EXPENSE, 7_00, category = "Кафе"),
                item(TransactionType.INCOME, 100_00, category = "Зарплата"),
                item(TransactionType.TRANSFER, 50_00, category = null, toAccount = "Доллары", toAmount = 15_00, fee = 2_00),
                item(TransactionType.ADJUSTMENT, 999_00, category = null),
                item(TransactionType.EXPENSE, 3_00, category = "Кафе", currency = "USD"),
            ),
        )
        assertEquals(listOf("BYN", "USD"), s.map { it.currency })
        val byn = s[0]
        assertEquals(100_00L, byn.incomeMinor)
        assertEquals(24_00L, byn.expenseMinor) // 10 + 5 + 7 + комиссия 2; перевод и корректировка не считаются
        assertEquals(76_00L, byn.netMinor)
        assertEquals(listOf("Продукты" to 15_00L, "Кафе" to 7_00L, ExportTable.FEES to 2_00L), byn.categories.map { it.name to it.minor })
    }

    @Test
    fun csvIsReadableByRussianExcel() {
        val csv = CsvWriter.write(ExportTable.operationsSheet(listOf(item(TransactionType.EXPENSE, 1_234_50, payee = "ООО \"Рога; и копыта\"")), zone))
        assertTrue(csv.startsWith("﻿"))
        assertTrue(csv.contains("03.11.2026 14:30;Расход;Карта;"))
        assertTrue(csv.contains(";-1234.50;".replace('.', ',')))
        assertTrue(csv.contains("\"ООО \"\"Рога; и копыта\"\"\""))
        assertTrue(csv.endsWith("\r\n"))
    }

    @Test
    fun csvEscapesFormulas() {
        val sheet = Sheet("x", listOf("a"), listOf(listOf(Cell.Text("=cmd|calc"))), listOf(5))
        assertTrue(CsvWriter.write(sheet).contains("'=cmd|calc"))
    }

    @Test
    fun ownCsvImportsBack() {
        val items = listOf(
            item(TransactionType.EXPENSE, 12_50, category = "Овощи", parent = "Продукты", payee = "Евроопт", comment = "на неделю"),
            item(TransactionType.INCOME, 100_00, category = "Зарплата"),
            item(TransactionType.TRANSFER, 30_00, category = null, toAccount = "Доллары", toAmount = 10_00),
        )
        val text = CsvReader.decode(CsvWriter.write(ExportTable.operationsSheet(items, zone)).toByteArray())
        val rows = CsvReader.parse(text, CsvReader.detectDelimiter(text))
        val header = StatementParser.headerRow(rows)
        assertEquals(0, header)
        val map = StatementParser.guess(rows[header])
        val parsed = StatementParser.parse(rows, header, map, SignRule.SIGNED)
        assertEquals(1, parsed.skippedTransfers)
        assertTrue(parsed.errorLines.isEmpty())
        assertEquals(2, parsed.rows.size)
        val e = parsed.rows[0]
        assertFalse(e.isIncome)
        assertEquals(0, BigDecimal("12.50").compareTo(e.amount))
        assertEquals(LocalDateTime.of(2026, 11, 3, 14, 30), e.dateTime)
        assertEquals("Евроопт", e.description)
        assertEquals("на неделю", e.comment)
        assertEquals("Продукты", e.category)
        assertEquals("Овощи", e.subcategory)
        assertEquals("BYN", e.currency)
        assertTrue(parsed.rows[1].isIncome)
    }

    @Test
    fun columnReferences() {
        assertEquals("A1", XlsxWriter.ref(0, 1))
        assertEquals("Z2", XlsxWriter.ref(25, 2))
        assertEquals("AA3", XlsxWriter.ref(26, 3))
        assertEquals("AB10", XlsxWriter.ref(27, 10))
    }

    @Test
    fun excelSerialDate() {
        assertEquals("46329.6041666667", XlsxWriter.serial(LocalDateTime.of(2026, 11, 3, 14, 30)))
        assertEquals("1.0000000000", XlsxWriter.serial(LocalDateTime.of(1899, 12, 31, 0, 0)))
    }

    @Test
    fun xlsxIsWellFormedPackage() {
        val items = listOf(item(TransactionType.EXPENSE, 12_50, payee = "A&B <\"кафе\">\u0001", comment = "тест"))
        val out = ByteArrayOutputStream()
        XlsxWriter.write(
            out,
            listOf(ExportTable.operationsSheet(items, zone), ExportTable.summarySheet(ExportTable.summarize(items), "Ноябрь 2026")),
        )
        val files = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z ->
            generateSequence { z.nextEntry }.forEach { files[it.name] = z.readBytes() }
        }
        assertEquals(
            setOf(
                "[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml",
                "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml",
            ),
            files.keys,
        )
        val parser = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        files.values.forEach { assertNotNull(parser.parse(ByteArrayInputStream(it))) } // каждый XML разбирается
        val sheet = parser.parse(ByteArrayInputStream(files.getValue("xl/worksheets/sheet1.xml")))
        val cells = sheet.getElementsByTagName("c")
        val byRef = (0 until cells.length).associate { i -> (cells.item(i) as Element).let { it.getAttribute("r") to it } }
        assertEquals("Дата", byRef.getValue("A1").textContent)
        assertEquals("46329.6041666667", byRef.getValue("A2").textContent)
        assertEquals("-12.50", byRef.getValue("G2").textContent)
        assertEquals("A&B <\"кафе\">", byRef.getValue("L2").textContent)
        assertEquals("A1:O2", (sheet.getElementsByTagName("autoFilter").item(0) as Element).getAttribute("ref"))
    }
}
