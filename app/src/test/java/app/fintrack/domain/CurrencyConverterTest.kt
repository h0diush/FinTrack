package app.fintrack.domain

import app.fintrack.domain.logic.CurrencyConverter
import app.fintrack.domain.logic.NbrbParser
import app.fintrack.domain.logic.StatEntry
import app.fintrack.domain.logic.StatisticsCalculator
import app.fintrack.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

class CurrencyConverterTest {

    // 1 USD = 3,2741 BYN; 100 RUB = 3,5120 BYN; 1 EUR = 3,5500 BYN
    private val conv = CurrencyConverter(
        mapOf("USD" to BigDecimal("3.2741"), "RUB" to BigDecimal("0.035120"), "EUR" to BigDecimal("3.55")),
    )

    @Test
    fun convertsThroughByn() {
        assertEquals(327_41L, conv.convert(100_00, "USD", "BYN"))
        assertEquals(30_54L, conv.convert(100_00, "BYN", "USD")) // 100 / 3,2741 = 30,5427…
        assertEquals(108_43L, conv.convert(100_00, "EUR", "USD")) // 3,55 / 3,2741 = 1,08427…
        assertEquals(500_00L, conv.convert(500_00, "USD", "USD"))
        assertNull(conv.convert(100, "GBP", "BYN"))
    }

    @Test
    fun respectsCurrencyScale() {
        val jpy = CurrencyConverter(mapOf("JPY" to BigDecimal("0.0215")))
        // 10 000 JPY (без копеек) = 215,00 BYN
        assertEquals(215_00L, jpy.convert(10_000, "JPY", "BYN"))
        assertEquals(10_000L, jpy.convert(215_00, "BYN", "JPY"))
    }

    @Test
    fun totalReportsMissingRates() {
        val t = conv.total(mapOf("BYN" to 1_000_00, "USD" to 100_00, "GBP" to 50_00, "CHF" to 0), "BYN")
        assertEquals(1_327_41L, t.totalMinor)
        assertEquals(listOf("GBP"), t.missing)
    }

    @Test
    fun convertedEntriesKeepLedgerConsistency() {
        val zone = ZoneId.of("Europe/Minsk")
        val day = LocalDate.of(2026, 11, 3)
        val at = day.atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000
        fun e(type: TransactionType, acc: String, cur: String, amount: Long, to: String? = null, toCur: String? = null, toAmount: Long? = null) =
            StatEntry(type, acc, to, amount, toAmount, 0, at, cur, toCur, if (type == TransactionType.TRANSFER) null else "c", "c")
        val entries = listOf(
            e(TransactionType.EXPENSE, "usd", "USD", 10_00),
            e(TransactionType.INCOME, "byn", "BYN", 100_00),
            e(TransactionType.ADJUSTMENT, "usd", "USD", -1_00),
            // Купили 100 USD за 330 BYN: по курсу 327,41 — «потеря» 2,59 BYN видна в балансе, но это не расход
            e(TransactionType.TRANSFER, "byn", "BYN", 330_00, to = "usd", toCur = "USD", toAmount = 100_00),
        )
        val (converted, missing) = conv.convertEntries(entries, "BYN")
        assertTrue(missing.isEmpty())
        val s = StatisticsCalculator.compute(converted, "BYN", day, day.plusDays(1), day, zone)
        assertEquals(100_00L, s.incomeMinor)
        assertEquals(32_74L, s.expenseMinor)
        val delta = converted.sumOf { StatisticsCalculator.balanceDeltaOf(it, "BYN") }
        assertEquals(100_00L - 32_74L - 3_27L - 330_00L + 327_41L, delta)
    }

    @Test
    fun entriesWithoutRateStayInTheirCurrency() {
        val gbp = StatEntry(TransactionType.EXPENSE, "g", null, 5_00, null, 0, 0, "GBP", null, "c", "c")
        val (converted, missing) = conv.convertEntries(listOf(gbp), "BYN")
        assertEquals("GBP", converted.single().currency)
        assertEquals(setOf("GBP"), missing)
    }

    @Test
    fun parsesNbrbResponse() {
        val json = """[{"Cur_ID":431,"Date":"2026-11-03T00:00:00","Cur_Abbreviation":"USD","Cur_Scale":1,"Cur_Name":"Доллар США","Cur_OfficialRate":3.2741},
            {"Cur_ID":456,"Date":"2026-11-03T00:00:00","Cur_Abbreviation":"RUB","Cur_Scale":100,"Cur_Name":"Российских рублей","Cur_OfficialRate":3.5120},
            {"Cur_ID":1,"Date":"bad","Cur_Abbreviation":"XXX","Cur_Scale":1,"Cur_OfficialRate":1}]"""
        val rates = NbrbParser.parse(json)
        assertEquals(listOf("USD", "RUB"), rates.map { it.code })
        assertEquals(LocalDate.of(2026, 11, 3), rates[0].date)
        assertEquals(0, BigDecimal("0.03512").compareTo(rates[1].bynPerUnit))
        assertTrue(NbrbParser.parse("not json").isEmpty())
    }
}
