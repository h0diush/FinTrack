package app.fintrack.domain

import app.fintrack.domain.logic.Deposit
import app.fintrack.domain.logic.DepositCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

class DepositCalculatorTest {

    @Test
    fun monthlyInterestRoundsToKopeck() {
        // 1 000,00 BYN под 12 % → 1 % в месяц = 10,00
        assertEquals(1_000L, DepositCalculator.monthlyInterest(100_000, 1_200))
        // 1 234,56 под 10,5 %: 123456 × 1050 / 120000 = 1080,24 → 1080 копеек
        assertEquals(1_080L, DepositCalculator.monthlyInterest(123_456, 1_050))
        // половина копейки округляется вверх: 1000 × 6 / 120000 = 0,05 → 0; 10000 × 6 / 120000 = 0,5 → 1
        assertEquals(1L, DepositCalculator.monthlyInterest(10_000, 6))
        assertEquals(0L, DepositCalculator.monthlyInterest(0, 1_200))
        assertEquals(0L, DepositCalculator.monthlyInterest(-5_000, 1_200))
    }

    @Test
    fun scheduleCompoundsMonthly() {
        val start = LocalDate.of(2026, 1, 15)
        val rows = DepositCalculator.schedule(100_000, 1_200, start, 1, 12)
        assertEquals(12, rows.size)
        // Эталон: тот же процесс с округлением каждого месяца на BigDecimal
        var b = BigDecimal("1000.00")
        repeat(12) { b = b.add(b.multiply(BigDecimal("0.01")).setScale(2, RoundingMode.HALF_UP)) }
        assertEquals(b.movePointRight(2).toLong(), rows.last().balanceMinor)
        // 1 126,84 BYN: каждое начисление округляется до копейки (без округления было бы 1 126,825)
        assertEquals(112_684L, rows.last().balanceMinor)
        assertEquals(rows.last().balanceMinor - 100_000, rows.sumOf { it.interestMinor })
        assertEquals(LocalDate.of(2027, 1, 15), rows.last().date)
    }

    @Test
    fun postingDatesKeepDayOfMonth() {
        val start = LocalDate.of(2026, 1, 31)
        assertEquals(LocalDate.of(2026, 2, 28), DepositCalculator.postingDate(start, 1))
        assertEquals(LocalDate.of(2026, 3, 31), DepositCalculator.postingDate(start, 2))
        assertEquals(LocalDate.of(2028, 2, 29), DepositCalculator.postingDate(start, 25))
    }

    @Test
    fun dueIndicesCatchUpWithoutDuplicates() {
        val d = Deposit("a", 1_200, LocalDate.of(2026, 5, 10), termMonths = 12, interestCategoryId = null, postedMonths = 0)
        assertEquals(1..5, DepositCalculator.dueIndices(d, LocalDate.of(2026, 10, 10)))
        assertEquals(1..4, DepositCalculator.dueIndices(d, LocalDate.of(2026, 10, 9)))
        assertTrue(DepositCalculator.dueIndices(d.copy(postedMonths = 5), LocalDate.of(2026, 10, 10)).isEmpty())
        // После окончания срока — не больше termMonths
        assertEquals(6..12, DepositCalculator.dueIndices(d.copy(postedMonths = 5), LocalDate.of(2030, 1, 1)))
        assertTrue(d.copy(postedMonths = 12).isFinished)
        assertEquals(LocalDate.of(2027, 5, 10), d.endDate)
    }

    @Test
    fun parsingAndFormatting() {
        assertEquals(1_250L, DepositCalculator.parseRate("12,5"))
        assertEquals(1_250L, DepositCalculator.parseRate("12.50 %"))
        assertNull(DepositCalculator.parseRate("0"))
        assertNull(DepositCalculator.parseRate("1,234"))
        assertNull(DepositCalculator.parseRate("abc"))
        assertNull(DepositCalculator.parseRate("2000"))
        assertEquals(18, DepositCalculator.parseTerm("1,5", inYears = true))
        assertEquals(36, DepositCalculator.parseTerm("3", inYears = true))
        assertEquals(7, DepositCalculator.parseTerm("7", inYears = false))
        assertNull(DepositCalculator.parseTerm("1,1", inYears = true)) // 13,2 мес.
        assertNull(DepositCalculator.parseTerm("0", inYears = false))
        assertEquals("12,5", DepositCalculator.formatRate(1_250))
        assertEquals("12", DepositCalculator.formatRate(1_200))
        assertEquals("1 год 6 мес.", DepositCalculator.formatTerm(18))
        assertEquals("2 года", DepositCalculator.formatTerm(24))
        assertEquals("5 лет", DepositCalculator.formatTerm(60))
        assertEquals("3 мес.", DepositCalculator.formatTerm(3))
    }

    @Test
    fun effectiveRate() {
        assertEquals(12.6825, DepositCalculator.effectiveRatePercent(1_200), 1e-3)
    }

    @Test
    fun yearlySummary() {
        val rows = DepositCalculator.schedule(100_000, 1_200, LocalDate.of(2026, 11, 2), 1, 24)
        val years = DepositCalculator.byYear(rows)
        assertEquals(listOf(2026, 2027, 2028), years.map { it.year })
        assertEquals(rows.sumOf { it.interestMinor }, years.sumOf { it.interestMinor })
        assertEquals(rows.last().balanceMinor, years.last().endBalanceMinor)
    }
}
