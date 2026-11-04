package app.fintrack.domain

import app.fintrack.domain.logic.CategoryGuesser
import app.fintrack.domain.logic.ColumnMap
import app.fintrack.domain.logic.CsvReader
import app.fintrack.domain.logic.ImportDedup
import app.fintrack.domain.logic.SignRule
import app.fintrack.domain.logic.StatementParser
import app.fintrack.domain.logic.StatementRow
import app.fintrack.domain.model.Category
import app.fintrack.domain.model.CategoryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.nio.charset.Charset
import java.time.LocalDate
import java.time.LocalDateTime

class StatementImportTest {

    private fun amount(s: String): String? = StatementParser.parseAmount(s)?.stripTrailingZeros()?.toPlainString()

    @Test
    fun decodesCommonEncodings() {
        val text = "Дата;Сумма"
        assertEquals(text, CsvReader.decode(text.toByteArray(Charsets.UTF_8)))
        assertEquals(text, CsvReader.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)))
        assertEquals(text, CsvReader.decode(text.toByteArray(Charset.forName("windows-1251"))))
        assertEquals(text, CsvReader.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)))
    }

    @Test
    fun detectsDelimiter() {
        assertEquals(';', CsvReader.detectDelimiter("a;b;c\n1,5;2,5;3\n4;5;6"))
        assertEquals(',', CsvReader.detectDelimiter("a,b,c\n1,2,3\n4,5,6"))
        assertEquals('\t', CsvReader.detectDelimiter("a\tb\tc\n1\t2\t3"))
    }

    @Test
    fun parsesQuotes() {
        val rows = CsvReader.parse("a;\"b;c\";\"d\"\"e\"\n\"строка\nвторая\";2;3\r\n\r\n;;\n", ';')
        assertEquals(listOf("a", "b;c", "d\"e"), rows[0])
        assertEquals("строка\nвторая", rows[1][0])
        assertEquals(2, rows.size) // пустые строки выброшены
    }

    @Test
    fun amountFormats() {
        assertEquals("1234.56", amount("1 234,56"))
        assertEquals("1234.56", amount("1 234,56"))
        assertEquals("1234.56", amount("1,234.56"))
        assertEquals("1234.56", amount("1.234,56"))
        assertEquals("-12.5", amount("-12.50"))
        assertEquals("-12.5", amount("−12,50"))
        assertEquals("-12.5", amount("(12,50)"))
        assertEquals("-12.5", amount("12,50-"))
        assertEquals("12.5", amount("+12,50"))
        assertEquals("12.5", amount("12,50 BYN"))
        assertEquals("1234", amount("1,234"))
        assertEquals("1234567", amount("1.234.567"))
        assertEquals("5", amount("5"))
        assertNull(StatementParser.parseAmount(""))
        assertNull(StatementParser.parseAmount("сумма"))
    }

    @Test
    fun dateFormats() {
        val noon = LocalDate.of(2026, 11, 3).atTime(12, 0)
        assertEquals(noon, StatementParser.parseDate("03.11.2026"))
        assertEquals(noon, StatementParser.parseDate("2026-11-03"))
        assertEquals(noon, StatementParser.parseDate("03/11/2026"))
        assertEquals(noon, StatementParser.parseDate("03.11.26"))
        assertEquals(LocalDateTime.of(2026, 11, 3, 14, 30), StatementParser.parseDate("03.11.2026 14:30"))
        assertEquals(LocalDateTime.of(2026, 11, 3, 14, 30, 5), StatementParser.parseDate("2026-11-03T14:30:05"))
        assertNull(StatementParser.parseDate("вчера"))
        assertNull(StatementParser.parseDate("32.13.2026"))
    }

    @Test
    fun findsHeaderBelowBankPreamble() {
        val text = """
            Выписка по счёту
            Счёт: BY00ALFA0000;Период: 01.11.2026 - 03.11.2026
            Дата операции;Описание;Сумма;Валюта
            01.11.2026;Евроопт;-12,50;BYN
            02.11.2026;Зарплата;1000,00;BYN
            Итого;;987,50;
        """.trimIndent()
        val rows = CsvReader.parse(text, ';')
        val h = StatementParser.headerRow(rows)
        assertEquals("Дата операции", rows[h][0])
        val map = StatementParser.guess(rows[h])
        assertEquals(ColumnMap(date = 0, amount = 2, description = 1, currency = 3), map)
        val parsed = StatementParser.parse(rows, h, map, SignRule.SIGNED)
        assertEquals(2, parsed.rows.size)
        assertEquals(listOf(6), parsed.errorLines) // «Итого» с суммой, но без даты — показать как непонятую
        assertFalse(parsed.rows[0].isIncome)
        assertTrue(parsed.rows[1].isIncome)
    }

    @Test
    fun separateCreditAndDebitColumns() {
        val rows = CsvReader.parse("Дата;Приход;Расход;Назначение\n01.11.2026;;12,50;Кафе\n02.11.2026;100,00;;Возврат\n03.11.2026;0;0;Запрос баланса", ';')
        val map = StatementParser.guess(rows[0])
        assertEquals(1, map.credit)
        assertEquals(2, map.debit)
        assertNull(map.amount)
        val parsed = StatementParser.parse(rows, 0, map, SignRule.SIGNED).rows
        assertEquals(2, parsed.size)
        assertFalse(parsed[0].isIncome)
        assertEquals(0, BigDecimal("12.5").compareTo(parsed[0].amount))
        assertTrue(parsed[1].isIncome)
    }

    @Test
    fun signRules() {
        val rows = listOf(listOf("Дата", "Сумма"), listOf("01.11.2026", "10"), listOf("02.11.2026", "-20"))
        val map = ColumnMap(date = 0, amount = 1)
        fun income(rule: SignRule) = StatementParser.parse(rows, 0, map, rule).rows.map { it.isIncome }
        assertEquals(listOf(true, false), income(SignRule.SIGNED))
        assertEquals(listOf(false, true), income(SignRule.INVERTED))
        assertEquals(listOf(false, false), income(SignRule.ALL_EXPENSES))
        assertEquals(listOf(true, true), income(SignRule.ALL_INCOME))
    }

    private fun row(desc: String, amount: String = "10", income: Boolean = false, category: String? = null, sub: String? = null) = StatementRow(
        line = 1, dateTime = LocalDate.of(2026, 11, 3).atTime(12, 0), isIncome = income, amount = BigDecimal(amount),
        currency = null, description = desc, comment = "", category = category, subcategory = sub,
    )

    @Test
    fun idsAreStableAndSeparateIdenticalPurchases() {
        val rows = listOf(row("Кофе"), row("Кофе"), row("Чай"))
        val ids = ImportDedup.ids("acc", rows)
        assertEquals(3, ids.toSet().size)
        assertEquals(ids, ImportDedup.ids("acc", rows)) // повторная загрузка — те же id
        assertTrue(ids.all { it.startsWith("imp:") })
        assertNotEquals(ids, ImportDedup.ids("other", rows))
        // Выписка с новыми строками не меняет id старых
        assertEquals(ids, ImportDedup.ids("acc", rows + row("Новое")).take(3))
        // 10 и 10.00 — одна и та же сумма
        assertEquals(ImportDedup.ids("acc", listOf(row("Кофе", "10"))), ImportDedup.ids("acc", listOf(row("Кофе", "10.00"))))
    }

    private fun cat(id: String, name: String, icon: String, kind: CategoryKind = CategoryKind.EXPENSE, parent: String? = null, system: Boolean = false, archived: Boolean = false) =
        Category(id, name, kind, parent, icon, 0, 0, archived, system)

    private val cats = listOf(
        cat("food", "Продукты", "groceries"),
        cat("veg", "Овощи", "groceries", parent = "food"),
        cat("cafe", "Кафе и рестораны", "restaurant"),
        cat("other", "Прочее", "other", system = true),
        cat("sal", "Зарплата", "salary", CategoryKind.INCOME),
        cat("oin", "Прочее", "other", CategoryKind.INCOME, system = true),
    )

    @Test
    fun categoryOrderOfPreference() {
        assertEquals("veg", CategoryGuesser.guess(row("x", category = "продукты", sub = "Овощи"), cats, emptyMap())?.id)
        assertEquals("food", CategoryGuesser.guess(row("x", category = "Продукты"), cats, emptyMap())?.id)
        // Ваш выбор важнее словаря
        assertEquals("cafe", CategoryGuesser.guess(row("ЕВРООПТ 1234567"), cats, mapOf("евроопт" to "cafe"))?.id)
        assertEquals("food", CategoryGuesser.guess(row("Покупка ЕВРООПТ Минск"), cats, emptyMap())?.id)
        assertEquals("cafe", CategoryGuesser.guess(row("MCDONALDS 42"), cats, emptyMap())?.id)
        assertEquals("other", CategoryGuesser.guess(row("что-то непонятное"), cats, emptyMap())?.id)
        assertEquals("sal", CategoryGuesser.guess(row("Заработная плата", income = true), cats, emptyMap())?.id)
        assertEquals("oin", CategoryGuesser.guess(row("Евроопт", income = true), cats, emptyMap())?.id)
    }

    @Test
    fun archivedCategoryIsNotPicked() {
        val list = cats.map { if (it.id == "food") it.copy(isArchived = true) else it }
        assertEquals("other", CategoryGuesser.guess(row("Евроопт"), list, emptyMap())?.id)
    }
}
