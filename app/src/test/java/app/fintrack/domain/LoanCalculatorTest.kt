package app.fintrack.domain

import app.fintrack.data.repository.LoanRepository
import app.fintrack.domain.logic.Loan
import app.fintrack.domain.logic.LoanCalculator
import app.fintrack.domain.logic.LoanKind
import app.fintrack.domain.logic.LoanSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LoanCalculatorTest {

    private val start = LocalDate.of(2026, 1, 31)

    @Test
    fun annuityPaymentMatchesFormulaRoundedUp() {
        // 3000,00 под 15 % на 12 мес.: 270,7749… → 270,78
        assertEquals(27_078L, LoanCalculator.annuityPayment(300_000, 1500, 12))
        // Ипотека 5 000 000,00 под 12 % на 20 лет: 55 054,3066… → 55 054,31
        assertEquals(5_505_431L, LoanCalculator.annuityPayment(500_000_000, 1200, 240))
        // Без процентов — поровну, вверх до копейки
        assertEquals(33_334L, LoanCalculator.annuityPayment(100_000, 0, 3))
    }

    @Test
    fun annuityScheduleClosesDebtExactly() {
        val rows = LoanCalculator.schedule(300_000, 1500, LoanSchedule.ANNUITY, start, 1, 12)
        assertEquals(12, rows.size)
        assertEquals(300_000L, rows.sumOf { it.principalMinor })
        assertEquals(0L, rows.last().remainingMinor)
        rows.dropLast(1).forEach { assertEquals(27_078L, it.paymentMinor) }
        assertTrue(rows.last().paymentMinor <= 27_078L)
        // Проценты первого месяца: 3000 × 15 % / 12 = 37,50
        assertEquals(3_750L, rows.first().interestMinor)
        assertEquals((1..12).toList(), rows.map { it.index })
    }

    @Test
    fun differentiatedPaymentsDecreaseAndOverpayLess() {
        val diff = LoanCalculator.schedule(1_200_000, 1200, LoanSchedule.DIFFERENTIATED, start, 1, 12)
        val ann = LoanCalculator.schedule(1_200_000, 1200, LoanSchedule.ANNUITY, start, 1, 12)
        assertEquals(1_200_000L, diff.sumOf { it.principalMinor })
        assertTrue(diff.zipWithNext().all { (a, b) -> a.paymentMinor >= b.paymentMinor })
        assertEquals(100_000L, diff.first().principalMinor)
        assertEquals(12_000L, diff.first().interestMinor)
        assertTrue(LoanCalculator.totals(diff).interestMinor < LoanCalculator.totals(ann).interestMinor)
    }

    @Test
    fun zeroRateInstallmentHasNoInterest() {
        val rows = LoanCalculator.schedule(100_000, 0, LoanSchedule.ANNUITY, start, 1, 3)
        assertEquals(listOf(33_334L, 33_334L, 33_332L), rows.map { it.paymentMinor })
        assertEquals(0L, LoanCalculator.totals(rows).interestMinor)
    }

    @Test
    fun paymentDatesKeepDayOfMonth() {
        assertEquals(LocalDate.of(2026, 2, 28), LoanCalculator.paymentDate(start, 1))
        assertEquals(LocalDate.of(2026, 3, 31), LoanCalculator.paymentDate(start, 2))
    }

    @Test
    fun splitTakesInterestFirstAndRejectsOverpayment() {
        val s = LoanCalculator.split(300_000, 1500, 27_078, early = false)!!
        assertEquals(3_750L, s.interestMinor)
        assertEquals(23_328L, s.principalMinor)
        // Меньше процентов — всё уходит в проценты
        val small = LoanCalculator.split(300_000, 1500, 1_000, early = false)!!
        assertEquals(1_000L, small.interestMinor)
        assertEquals(0L, small.principalMinor)
        // Досрочно — всё в долг
        val early = LoanCalculator.split(300_000, 1500, 50_000, early = true)!!
        assertEquals(0L, early.interestMinor)
        assertEquals(50_000L, early.principalMinor)
        // Полное погашение и больше
        assertEquals(303_750L, LoanCalculator.payoffAmount(300_000, 1500, early = false))
        assertEquals(300_000L, LoanCalculator.split(300_000, 1500, 303_750, early = false)!!.principalMinor)
        assertNull(LoanCalculator.split(300_000, 1500, 303_751, early = false))
        assertNull(LoanCalculator.split(300_000, 1500, 0, early = false))
    }

    @Test
    fun debtAfterPaidMonthsFollowsSchedule() {
        val rows = LoanCalculator.schedule(300_000, 1500, LoanSchedule.ANNUITY, start, 1, 12)
        assertEquals(300_000L, LoanCalculator.debtAfter(300_000, 1500, LoanSchedule.ANNUITY, start, 12, 0))
        assertEquals(rows[4].remainingMinor, LoanCalculator.debtAfter(300_000, 1500, LoanSchedule.ANNUITY, start, 12, 5))
        assertEquals(0L, LoanCalculator.debtAfter(300_000, 1500, LoanSchedule.ANNUITY, start, 12, 12))
    }

    @Test
    fun dueCountCountsPastPaymentDates() {
        assertEquals(0, LoanCalculator.dueCount(start, 12, LocalDate.of(2026, 2, 27)))
        assertEquals(1, LoanCalculator.dueCount(start, 12, LocalDate.of(2026, 2, 28)))
        assertEquals(12, LoanCalculator.dueCount(start, 12, LocalDate.of(2030, 1, 1)))
    }

    @Test
    fun earlyRepaymentLowersNextPayments() {
        val base = loan(debt = 300_000, paid = 0)
        val after = loan(debt = 200_000, paid = 0)
        assertTrue(after.nextPayment()!!.paymentMinor < base.nextPayment()!!.paymentMinor)
        assertEquals(12, after.upcoming().size)
        assertEquals(200_000L, after.upcoming().sumOf { it.principalMinor })
    }

    @Test
    fun loanStateDerivedFields() {
        val l = loan(debt = 150_000, paid = 6)
        assertEquals(0.5f, l.paidFraction, 1e-6f)
        assertEquals(6, l.monthsLeft)
        assertEquals(LoanCalculator.paymentDate(start, 7), l.nextDate)
        assertEquals(7, l.nextPayment()!!.index)
        val overdue = loan(debt = 150_000, paid = 6, termMonths = 6)
        assertEquals(1, overdue.monthsLeft) // срок вышел, а долг остался — весь к оплате
        assertTrue(loan(debt = 0, paid = 12).upcoming().isEmpty())
    }

    @Test
    fun parseRateAllowsZero() {
        assertEquals(0L, LoanCalculator.parseRate("0"))
        assertEquals(1490L, LoanCalculator.parseRate("14,9"))
        assertNull(LoanCalculator.parseRate("abc"))
        assertNull(LoanCalculator.parseRate("1000,01"))
    }

    @Test
    fun transactionIdsEncodeLoanAndPaymentNumber() {
        assertEquals("abc", LoanRepository.loanIdOf("loan:abc:12:p"))
        assertEquals(12, LoanRepository.paymentNumberOf("loan:abc:12:i"))
        assertNull(LoanRepository.paymentNumberOf("loan:abc:0:d"))
        assertNull(LoanRepository.paymentNumberOf("loan:abc:x1f2e3d4:p"))
    }

    private fun loan(debt: Long, paid: Int, termMonths: Int = 12) = Loan(
        accountId = "l",
        name = "Кредит",
        currency = "BYN",
        color = 0,
        kind = LoanKind.CREDIT,
        schedule = LoanSchedule.ANNUITY,
        principalMinor = 300_000,
        rateBp = 1500,
        startDate = start,
        termMonths = termMonths,
        debtMinor = debt,
        paidCount = paid,
    )
}
