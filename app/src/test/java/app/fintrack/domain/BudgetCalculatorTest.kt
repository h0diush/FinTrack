package app.fintrack.domain

import app.fintrack.domain.logic.BudgetCalculator
import app.fintrack.domain.logic.BudgetLevel
import app.fintrack.domain.logic.BudgetLimit
import app.fintrack.domain.logic.BudgetThresholds
import app.fintrack.domain.logic.StatEntry
import app.fintrack.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class BudgetCalculatorTest {

    private val zone: ZoneId = ZoneId.of("Europe/Minsk")
    private val sep = YearMonth.of(2026, 9)
    private val oct = YearMonth.of(2026, 10)
    private val nov = YearMonth.of(2026, 11)

    private fun spend(month: YearMonth, day: Int, amount: Long, root: String? = "food", type: TransactionType = TransactionType.EXPENSE, fee: Long = 0) =
        StatEntry(
            type, "card", if (type == TransactionType.TRANSFER) "cash" else null, amount, amount, fee,
            month.atDay(day).atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
            "BYN", if (type == TransactionType.TRANSFER) "BYN" else null, root, root,
        )

    private fun limit(cat: String?, amount: Long, rollover: Boolean, start: YearMonth, end: YearMonth? = null, id: String = "$cat-$start") =
        BudgetLimit(id, cat, "BYN", amount, rollover, start, end)

    @Test
    fun overallBudgetCountsAllExpensesAndFees() {
        val spending = BudgetCalculator.spending(
            listOf(spend(nov, 1, 85_000), spend(nov, 2, 10_000, root = "car"), spend(nov, 3, 5_000, type = TransactionType.TRANSFER, fee = 200)),
            "BYN", zone,
        )
        val s = BudgetCalculator.status(listOf(limit(null, 200_000, false, nov)), spending, null, "BYN", nov)!!
        assertEquals(95_200L, s.spentMinor)
        assertEquals(104_800L, s.remainingMinor)
        // Категорийный бюджет не включает комиссии и чужие категории
        assertEquals(85_000L, spending.spent(nov, "food"))
    }

    @Test
    fun levelsFollowThresholds() {
        val t = BudgetThresholds(50, 80)
        fun level(spent: Long) = BudgetCalculator.status(
            listOf(limit(null, 1_000, false, nov)),
            BudgetCalculator.spending(listOf(spend(nov, 1, spent)), "BYN", zone), null, "BYN", nov,
        )!!.level(t)
        assertEquals(BudgetLevel.NORMAL, level(499))
        assertEquals(BudgetLevel.INFO, level(500))
        assertEquals(BudgetLevel.WARNING, level(800))
        assertEquals(BudgetLevel.EXCEEDED, level(1_000))
        assertEquals(BudgetLevel.EXCEEDED, level(1_500))
        assertEquals(BudgetThresholds(98, 99), BudgetThresholds(150, 10).normalized())
    }

    @Test
    fun rolloverCarriesOnlyPositiveRemainderAcrossMonths() {
        val limits = listOf(limit("food", 50_000, true, sep))
        val entries = listOf(spend(sep, 5, 30_000), spend(oct, 5, 80_000), spend(nov, 5, 10_000))
        val spending = BudgetCalculator.spending(entries, "BYN", zone)
        // Сентябрь: 50 000 − 30 000 = 20 000 переносится
        val octStatus = BudgetCalculator.status(limits, spending, "food", "BYN", oct)!!
        assertEquals(20_000L, octStatus.carriedMinor)
        assertEquals(70_000L, octStatus.availableMinor)
        assertEquals(-10_000L, octStatus.remainingMinor)
        // Перерасход октября не уменьшает ноябрь
        val novStatus = BudgetCalculator.status(limits, spending, "food", "BYN", nov)!!
        assertEquals(0L, novStatus.carriedMinor)
    }

    @Test
    fun noRolloverResetsEachMonth() {
        val limits = listOf(limit("food", 50_000, false, sep))
        val spending = BudgetCalculator.spending(listOf(spend(sep, 1, 10_000)), "BYN", zone)
        assertEquals(0L, BudgetCalculator.status(limits, spending, "food", "BYN", oct)!!.carriedMinor)
    }

    @Test
    fun versionedLimitsKeepHistory() {
        var limits = listOf(limit(null, 100_000, false, sep))
        var n = 0
        val changes = BudgetCalculator.planSet(limits, null, "BYN", nov, 150_000, true) { "new${n++}" }
        limits = limits.map { l -> changes.updates.firstOrNull { it.id == l.id } ?: l } + changes.inserts
        assertEquals(100_000L, BudgetCalculator.activeLimit(limits, null, "BYN", oct)!!.amountMinor)
        assertEquals(150_000L, BudgetCalculator.activeLimit(limits, null, "BYN", nov)!!.amountMinor)
        assertEquals(150_000L, BudgetCalculator.activeLimit(limits, null, "BYN", nov.plusMonths(5))!!.amountMinor)

        // Повторное изменение в том же месяце — обновление, а не новая версия
        val again = BudgetCalculator.planSet(limits, null, "BYN", nov, 120_000, false) { "x" }
        assertTrue(again.inserts.isEmpty())
        assertEquals(120_000L, again.updates.single().amountMinor)

        // Удаление с ноября: октябрь остаётся
        val removed = BudgetCalculator.planRemove(limits, null, "BYN", nov)
        assertEquals(listOf("new0"), removed.deletes)
        val fromDec = BudgetCalculator.planRemove(limits, null, "BYN", nov.plusMonths(1))
        assertEquals(nov.plusMonths(1), fromDec.updates.single().endMonth)
    }

    @Test
    fun setInPastKeepsFutureVersion() {
        val limits = listOf(limit(null, 100_000, false, sep, end = nov, id = "a"), limit(null, 200_000, false, nov, id = "b"))
        val c = BudgetCalculator.planSet(limits, null, "BYN", oct, 90_000, false) { "c" }
        assertEquals(oct, c.updates.single { it.id == "a" }.endMonth)
        assertEquals(nov, c.inserts.single().endMonth)
    }

    @Test
    fun forecastForCurrentMonth() {
        val today = LocalDate.of(2026, 11, 10)
        val spending = BudgetCalculator.spending(listOf(spend(nov, 2, 50_000)), "BYN", zone)
        val s = BudgetCalculator.status(listOf(limit(null, 200_000, false, nov)), spending, null, "BYN", nov)!!
        val f = BudgetCalculator.forecast(s, nov, today)!!
        assertEquals(30, f.daysInMonth)
        assertEquals(21, f.daysLeft)
        assertEquals(150_000L / 21, f.dailyAllowanceMinor)
        assertEquals(150_000L, f.projectedSpendMinor) // 5 000 в день × 30
        assertEquals(30, f.daysUntilExhausted) // 150 000 / 5 000
        assertTrue(f.lastsWholeMonth)
        assertNull(BudgetCalculator.forecast(s, oct, today))
    }

    @Test
    fun monthKeyRoundTrip() {
        assertEquals(202611, BudgetCalculator.toKey(nov))
        assertEquals(nov, BudgetCalculator.fromKey(202611))
    }
}
