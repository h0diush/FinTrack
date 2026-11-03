package app.fintrack.domain

import app.fintrack.domain.logic.RecurrenceCalculator
import app.fintrack.domain.logic.RepeatRule
import app.fintrack.domain.logic.RepeatUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class RecurrenceCalculatorTest {

    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    @Test
    fun monthlyKeepsDayAfterShortMonth() {
        val r = RepeatRule(RepeatUnit.MONTH, 1, d(2026, 1, 31))
        assertEquals(listOf(d(2026, 1, 31), d(2026, 2, 28), d(2026, 3, 31), d(2026, 4, 30)), RecurrenceCalculator.upcoming(r, 0, 4))
    }

    @Test
    fun lastDayOfMonth() {
        val r = RepeatRule(RepeatUnit.MONTH, 1, d(2026, 1, 15), lastDayOfMonth = true)
        assertEquals(listOf(d(2026, 1, 31), d(2026, 2, 28), d(2026, 3, 31)), RecurrenceCalculator.upcoming(r, 0, 3))
    }

    @Test
    fun intervalsForAllUnits() {
        val s = d(2026, 11, 3)
        assertEquals(d(2026, 11, 9), RecurrenceCalculator.occurrence(RepeatRule(RepeatUnit.DAY, 3, s), 2))
        assertEquals(d(2026, 12, 1), RecurrenceCalculator.occurrence(RepeatRule(RepeatUnit.WEEK, 2, s), 2))
        assertEquals(d(2027, 5, 3), RecurrenceCalculator.occurrence(RepeatRule(RepeatUnit.MONTH, 3, s), 2))
        assertEquals(d(2028, 2, 29), RecurrenceCalculator.occurrence(RepeatRule(RepeatUnit.YEAR, 1, d(2024, 2, 29)), 4))
    }

    @Test
    fun limitsByCountAndEndDate() {
        val byCount = RepeatRule(RepeatUnit.MONTH, 1, d(2026, 1, 5), maxCount = 3)
        assertEquals(d(2026, 3, 5), RecurrenceCalculator.dateOf(byCount, 2))
        assertNull(RecurrenceCalculator.dateOf(byCount, 3))
        val byDate = RepeatRule(RepeatUnit.MONTH, 1, d(2026, 1, 5), endDate = d(2026, 3, 5))
        assertEquals(d(2026, 3, 5), RecurrenceCalculator.dateOf(byDate, 2))
        assertNull(RecurrenceCalculator.dateOf(byDate, 3))
    }

    @Test
    fun dueIndicesCatchUpMissedPeriods() {
        val r = RepeatRule(RepeatUnit.MONTH, 1, d(2026, 7, 10))
        // Приложение не открывали с июля: наступили июль…октябрь, ноябрьский ещё впереди
        assertEquals(0 until 4, RecurrenceCalculator.dueIndices(r, 0, d(2026, 11, 3)))
        assertEquals(2 until 4, RecurrenceCalculator.dueIndices(r, 2, d(2026, 11, 3)))
        assertTrue(RecurrenceCalculator.dueIndices(r, 4, d(2026, 11, 3)).isEmpty())
        // В день платежа он уже наступил
        assertEquals(4 until 5, RecurrenceCalculator.dueIndices(r, 4, d(2026, 11, 10)))
    }

    @Test
    fun describeInRussian() {
        val s = d(2026, 11, 6) // пятница
        assertEquals("Каждый месяц, 6-го числа", RecurrenceCalculator.describe(RepeatRule(RepeatUnit.MONTH, 1, s)))
        assertEquals("Каждые 2 недели, по пятницам", RecurrenceCalculator.describe(RepeatRule(RepeatUnit.WEEK, 2, s)))
        assertEquals("Каждый день", RecurrenceCalculator.describe(RepeatRule(RepeatUnit.DAY, 1, s)))
        assertEquals("Каждый год, 6 ноября", RecurrenceCalculator.describe(RepeatRule(RepeatUnit.YEAR, 1, s)))
        assertEquals(
            "Каждый месяц, в последний день · ещё 2 раза",
            RecurrenceCalculator.describe(RepeatRule(RepeatUnit.MONTH, 1, s, lastDayOfMonth = true, maxCount = 5), processed = 3),
        )
        assertEquals(
            "Каждые 5 дней · до 1 января 2027",
            RecurrenceCalculator.describe(RepeatRule(RepeatUnit.DAY, 5, s, endDate = d(2027, 1, 1))),
        )
    }

    @Test
    fun perMonthEquivalent() {
        val s = d(2026, 1, 1)
        assertEquals(10_000L, RecurrenceCalculator.perMonthMinor(10_000, RepeatRule(RepeatUnit.MONTH, 1, s)))
        assertEquals(5_000L, RecurrenceCalculator.perMonthMinor(10_000, RepeatRule(RepeatUnit.MONTH, 2, s)))
        assertEquals(1_000L, RecurrenceCalculator.perMonthMinor(12_000, RepeatRule(RepeatUnit.YEAR, 1, s)))
        assertEquals(43_333L, RecurrenceCalculator.perMonthMinor(10_000, RepeatRule(RepeatUnit.WEEK, 1, s)))
        assertEquals(30_417L, RecurrenceCalculator.perMonthMinor(1_000, RepeatRule(RepeatUnit.DAY, 1, s)))
    }

    @Test
    fun nextWeekday() {
        assertEquals(d(2026, 11, 6), RecurrenceCalculator.nextWeekday(d(2026, 11, 3), DayOfWeek.FRIDAY))
        assertEquals(d(2026, 11, 3), RecurrenceCalculator.nextWeekday(d(2026, 11, 3), DayOfWeek.TUESDAY))
        assertEquals(d(2026, 11, 9), RecurrenceCalculator.nextWeekday(d(2026, 11, 3), DayOfWeek.MONDAY))
    }
}
