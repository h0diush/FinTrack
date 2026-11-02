package app.fintrack.domain

import app.fintrack.domain.logic.Motivation
import app.fintrack.domain.logic.PiggyBank
import app.fintrack.domain.logic.PiggyCalculator
import app.fintrack.domain.logic.PiggyStage
import app.fintrack.domain.logic.ruPlural
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PiggyTest {

    private val today = LocalDate.of(2026, 11, 2)

    private fun piggy(target: Long, balance: Long, deadline: LocalDate? = null, topUps: Int = 0, last: Long? = null) =
        PiggyBank("p", "Отпуск", "BYN", 0, "beach", target, deadline, balance, topUps, last)

    @Test
    fun percentIsFlooredAndNeverShows100BeforeGoal() {
        val pr = PiggyCalculator.progress(piggy(100_000, 99_990), today)
        assertEquals(99, pr.percent)
        assertFalse(pr.reached)
        assertEquals(10L, pr.remainingMinor)
        val done = PiggyCalculator.progress(piggy(100_000, 120_000), today)
        assertEquals(100, done.percent)
        assertTrue(done.reached)
        assertEquals(1f, done.fraction)
        assertEquals(0L, done.remainingMinor)
    }

    @Test
    fun perMonthRoundsUpToWholeUnits() {
        // Осталось 1000 BYN, срок через 3 месяца → 333,34 → 334 BYN в месяц.
        val pr = PiggyCalculator.progress(piggy(150_000, 50_000, today.plusMonths(3)), today)
        assertEquals(3, pr.monthsLeft)
        assertEquals(33_400L, pr.perMonthMinor)
        assertFalse(pr.overdue)
    }

    @Test
    fun partialMonthCountsAsWholeAndOverdueDetected() {
        assertEquals(2, PiggyCalculator.monthsUntil(today, today.plusMonths(1).plusDays(3)))
        assertEquals(1, PiggyCalculator.monthsUntil(today, today.plusDays(5)))
        val late = PiggyCalculator.progress(piggy(100_000, 10_000, today.minusDays(1)), today)
        assertTrue(late.overdue)
        assertNull(late.perMonthMinor)
    }

    @Test
    fun perMonthNeverExceedsRemaining() {
        val pr = PiggyCalculator.progress(piggy(100_000, 99_950, today.plusMonths(6)), today)
        assertEquals(50L, pr.perMonthMinor)
    }

    @Test
    fun noTargetHasNoProgress() {
        val pr = PiggyCalculator.progress(piggy(0, 5_000), today)
        assertFalse(pr.hasTarget)
        assertEquals(PiggyStage.NO_TARGET, PiggyCalculator.stage(piggy(0, 5_000), pr))
    }

    @Test
    fun suggestionsAreSortedDistinctAndWithinRemaining() {
        val p = piggy(500_000, 120_000, today.plusMonths(10))
        val pr = PiggyCalculator.progress(p, today)
        val s = PiggyCalculator.suggestAmounts(p, pr)
        assertTrue(s.isNotEmpty())
        assertEquals(s.sorted(), s)
        assertEquals(s.distinct(), s)
        assertTrue(s.all { it in 1..pr.remainingMinor })
        assertTrue(pr.perMonthMinor in s)
        assertTrue(pr.remainingMinor in s)
    }

    @Test
    fun stagesFollowPercent() {
        fun st(b: Long) = piggy(100_000, b).let { PiggyCalculator.stage(it, PiggyCalculator.progress(it, today)) }
        assertEquals(PiggyStage.EMPTY, st(0))
        assertEquals(PiggyStage.START, st(10_000))
        assertEquals(PiggyStage.QUARTER, st(25_000))
        assertEquals(PiggyStage.HALF, st(60_000))
        assertEquals(PiggyStage.FINAL, st(80_000))
        assertEquals(PiggyStage.DONE, st(100_000))
    }

    @Test
    fun milestonesCrossed() {
        assertEquals(listOf(25, 50), PiggyCalculator.crossedMilestones(20, 55))
        assertEquals(emptyList<Int>(), PiggyCalculator.crossedMilestones(50, 50))
        assertEquals(listOf(100), PiggyCalculator.crossedMilestones(99, 100))
    }

    @Test
    fun phrasesIncludeLiveNumbers() {
        val p = piggy(100_000, 40_000, today.plusMonths(6), topUps = 4, last = 10_000)
        val list = Motivation.phrases(p, today)
        assertTrue(list.size >= 5)
        assertEquals(list.distinct(), list)
        assertTrue(list.any { it.contains("Ещё 6 пополнений по 100,00") })
        assertTrue(list.any { it.contains("Уже 4 пополнения") })
        assertTrue(list.any { it.startsWith("Откладывайте по 100,00") })
    }

    @Test
    fun pluralRu() {
        assertEquals("день", ruPlural(21, "день", "дня", "дней"))
        assertEquals("дня", ruPlural(3, "день", "дня", "дней"))
        assertEquals("дней", ruPlural(12, "день", "дня", "дней"))
        assertEquals("дней", ruPlural(0, "день", "дня", "дней"))
    }
}
