package app.fintrack.domain

import app.fintrack.domain.logic.BalanceCalculator
import app.fintrack.domain.logic.Insights
import app.fintrack.domain.logic.LedgerEntry
import app.fintrack.domain.logic.Period
import app.fintrack.domain.logic.Periods
import app.fintrack.domain.logic.StatEntry
import app.fintrack.domain.logic.StatisticsCalculator
import app.fintrack.domain.model.TransactionType
import app.fintrack.domain.model.TransactionType.ADJUSTMENT
import app.fintrack.domain.model.TransactionType.EXPENSE
import app.fintrack.domain.model.TransactionType.INCOME
import app.fintrack.domain.model.TransactionType.TRANSFER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.random.Random

class StatisticsCalculatorTest {

    private val zone: ZoneId = ZoneId.of("Europe/Minsk")
    private val currencyOf = mapOf("cash" to "BYN", "card" to "BYN", "usd" to "USD")

    private fun at(date: LocalDate, hour: Int = 12) = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun e(
        type: TransactionType,
        acc: String,
        amount: Long,
        date: LocalDate,
        to: String? = null,
        toAmount: Long? = null,
        fee: Long = 0,
        cat: String? = null,
        root: String? = cat,
    ) = StatEntry(type, acc, to, amount, toAmount, fee, at(date), currencyOf.getValue(acc), to?.let { currencyOf[it] }, cat, root)

    private val d1 = LocalDate.of(2026, 10, 1)
    private val d2 = LocalDate.of(2026, 10, 2)
    private val d5 = LocalDate.of(2026, 10, 5)

    private val sample = listOf(
        e(INCOME, "card", 200_000, d1, cat = "salary"),
        e(EXPENSE, "cash", 3_000, d1, cat = "veg", root = "food"),
        e(EXPENSE, "card", 7_000, d2, cat = "food"),
        e(EXPENSE, "card", 50_000, d5, cat = "car"),
        e(TRANSFER, "card", 10_000, d2, to = "cash", toAmount = 10_000, fee = 150),
        e(TRANSFER, "usd", 1_000, d2, to = "card", toAmount = 3_250),
        e(ADJUSTMENT, "cash", -500, d5),
        e(EXPENSE, "usd", 900, d5, cat = "food"),
    )

    private fun stats(entries: List<StatEntry> = sample, today: LocalDate = LocalDate.of(2026, 10, 31)) =
        StatisticsCalculator.compute(entries, "BYN", d1, LocalDate.of(2026, 11, 1), today, zone)

    @Test
    fun totalsIgnoreTransfersButCountFees() {
        val s = stats()
        assertEquals(200_000L, s.incomeMinor)
        assertEquals(3_000L + 7_000L + 50_000L + 150L, s.expenseMinor)
        assertEquals(200_000L - 60_150L, s.netMinor)
    }

    @Test
    fun categoriesRollUpToParentAndFeesAreSeparate() {
        val byRoot = stats().expenseByCategory.associate { it.rootCategoryId to it.amountMinor }
        assertEquals(50_000L, byRoot["car"])
        assertEquals(10_000L, byRoot["food"]) // 3 000 подкатегории + 7 000 родителя; USD не входит
        assertEquals(150L, byRoot[null])
        assertEquals("car", stats().topExpenseCategory!!.rootCategoryId)
        assertEquals(1.0, stats().expenseByCategory.sumOf { it.share }, 1e-9)
    }

    @Test
    fun daysAreFilledAndAveragesUseElapsedDaysOnly() {
        val s = stats(today = LocalDate.of(2026, 10, 10))
        assertEquals(10, s.days.size) // 1–10 октября, будущее не считается
        assertEquals(0L, s.days[2].expenseMinor)
        assertEquals(StatisticsCalculator.divRound(60_150, 10), s.avgDailyExpenseMinor)
        assertEquals(d5, s.maxExpenseDay!!.date)
        assertEquals(200_000L, s.avgMonthlyIncomeMinor)
    }

    @Test
    fun percentagesOfIncome() {
        val s = stats()
        assertEquals(60_150 * 100.0 / 200_000, s.expenseToIncomePercent!!, 1e-9)
        assertEquals(100 - 60_150 * 100.0 / 200_000, s.savingsRatePercent!!, 1e-9)
        val noIncome = stats(sample.filter { it.type != INCOME })
        assertNull(noIncome.expenseToIncomePercent)
    }

    @Test
    fun monthlyBuckets() {
        val entries = sample + e(EXPENSE, "cash", 1_000, LocalDate.of(2026, 9, 30), cat = "food")
        val months = listOf(YearMonth.of(2026, 9), YearMonth.of(2026, 10), YearMonth.of(2026, 11))
        val m = StatisticsCalculator.monthly(entries, "BYN", months, zone)
        assertEquals(listOf(0L, 200_000L, 0L), m.map { it.incomeMinor })
        assertEquals(listOf(1_000L, 60_150L, 0L), m.map { it.expenseMinor })
    }

    @Test
    fun dayBoundaryUsesLocalTimeZone() {
        // 23:30 по Минску — это ещё 1 октября, хотя по UTC уже 20:30 того же дня; 00:30 — уже 2-е.
        val late = d1.atTime(23, 30).atZone(zone).toInstant().toEpochMilli()
        val early = d2.atTime(0, 30).atZone(zone).toInstant().toEpochMilli()
        val entries = listOf(
            StatEntry(EXPENSE, "cash", null, 100, null, 0, late, "BYN", null, "food", "food"),
            StatEntry(EXPENSE, "cash", null, 200, null, 0, early, "BYN", null, "food", "food"),
        )
        val s = StatisticsCalculator.compute(entries, "BYN", d1, d5, d5, zone)
        assertEquals(100L, s.days[0].expenseMinor)
        assertEquals(200L, s.days[1].expenseMinor)
    }

    /** Баланс на конец периода из ряда = баланс из эталонного BalanceCalculator. */
    @Test
    fun balanceSeriesMatchesBalanceCalculatorOnRandomLedger() {
        val rnd = Random(11)
        val ids = currencyOf.keys.toList()
        val start = LocalDate.of(2026, 1, 1)
        val entries = List(5_000) {
            val acc = ids[rnd.nextInt(3)]
            val date = start.plusDays(rnd.nextLong(0, 120))
            when (rnd.nextInt(4)) {
                0 -> e(INCOME, acc, rnd.nextLong(1, 100_000), date, cat = "salary")
                1 -> e(EXPENSE, acc, rnd.nextLong(1, 100_000), date, cat = "food")
                2 -> {
                    val to = ids.filter { it != acc }[rnd.nextInt(2)]
                    val amount = rnd.nextLong(1, 100_000)
                    val toAmount = if (currencyOf[to] == currencyOf[acc]) amount else rnd.nextLong(1, 300_000)
                    e(TRANSFER, acc, amount, date, to = to, toAmount = toAmount, fee = rnd.nextLong(0, 300))
                }
                else -> e(ADJUSTMENT, acc, rnd.nextLong(-5_000, 5_000), date)
            }
        }
        val initial = mapOf("cash" to 1_000L, "card" to 50_000L, "usd" to 7_000L)
        val ledger = entries.map { LedgerEntry(it.type, it.accountId, it.toAccountId, it.amountMinor, it.toAmountMinor, it.feeMinor) }
        val finalBalances = BalanceCalculator.balances(initial, ledger)

        val days = (0L until 120L).map { start.plusDays(it) }
        for (cur in listOf("BYN", "USD")) {
            val opening = initial.filterKeys { currencyOf[it] == cur }.values.sum()
            val series = StatisticsCalculator.balanceSeries(entries, cur, opening, days, zone)
            val expected = finalBalances.filterKeys { currencyOf[it] == cur }.values.sum()
            assertEquals(expected, series.last().balanceMinor)
        }

        // Счёт вне общего баланса: ряд сходится с суммой остальных счетов
        val excluded = setOf("card")
        val openingIncluded = initial.filterKeys { currencyOf[it] == "BYN" && it !in excluded }.values.sum()
        val seriesIncluded = StatisticsCalculator.balanceSeries(entries, "BYN", openingIncluded, days, zone, excluded)
        val expectedIncluded = finalBalances.filterKeys { currencyOf[it] == "BYN" && it !in excluded }.values.sum()
        assertEquals(expectedIncluded, seriesIncluded.last().balanceMinor)

        // Доходы − расходы + корректировки + чистые переводы = изменение баланса
        val s = StatisticsCalculator.compute(entries, "BYN", start, start.plusDays(120), start.plusDays(200), zone)
        val totals = BalanceCalculator.totals(ledger) { currencyOf.getValue(it) }.getValue("BYN")
        assertEquals(totals.incomeMinor, s.incomeMinor)
        assertEquals(totals.expenseMinor, s.expenseMinor)
    }

    @Test
    fun compareWithPreviousPeriod() {
        val prev = listOf(
            app.fintrack.domain.logic.CategoryAmount("food", 10_000, 0.5),
            app.fintrack.domain.logic.CategoryAmount("car", 10_000, 0.5),
        )
        val cur = listOf(
            app.fintrack.domain.logic.CategoryAmount("food", 11_800, 0.8),
            app.fintrack.domain.logic.CategoryAmount("fun", 3_000, 0.2),
        )
        val c = StatisticsCalculator.compare(cur, prev).associateBy { it.rootCategoryId }
        assertEquals(18.0, c["food"]!!.changePercent!!, 1e-9)
        assertEquals(-100.0, c["car"]!!.changePercent!!, 1e-9)
        assertNull(c["fun"]!!.changePercent)
    }

    @Test
    fun insightsAreFactual() {
        val current = stats()
        val previous = StatisticsCalculator.compute(
            listOf(e(INCOME, "card", 100_000, LocalDate.of(2026, 9, 3), cat = "salary"), e(EXPENSE, "card", 30_000, LocalDate.of(2026, 9, 3), cat = "car")),
            "BYN", LocalDate.of(2026, 9, 1), d1, d1, zone,
        )
        val texts = Insights.build(current, previous, "Сентябрь") { it ?: "Комиссии" }.map { it.text }
        assertTrue(texts.any { it.startsWith("Больше всего потрачено на «car»") })
        assertTrue(texts.any { it.contains("выросли") })
        assertTrue(texts.any { it.startsWith("Удалось сохранить") })
        assertEquals("18 %", Insights.percent(18.0))
        assertEquals("2,5 %", Insights.percent(2.5).replace('.', ','))
    }

    @Test
    fun previousPeriods() {
        val today = LocalDate.of(2026, 11, 2)
        assertEquals(Period.Month(YearMonth.of(2026, 10)), Periods.previous(Period.Month(YearMonth.of(2026, 11)), today))
        assertEquals(Period.LastWeek, Periods.previous(Period.ThisWeek, today))
        assertEquals(Period.Year(2025), Periods.previous(Period.Year(2026), today))
        assertNull(Periods.previous(Period.All, today))
        val custom = Periods.previous(Period.Custom(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 19)), today)
        assertEquals(Period.Custom(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 9)), custom)
    }
}
