package app.fintrack.domain

import app.fintrack.domain.logic.BalanceCalculator
import app.fintrack.domain.logic.LedgerEntry
import app.fintrack.domain.model.PeriodTotals
import app.fintrack.domain.model.TransactionType.ADJUSTMENT
import app.fintrack.domain.model.TransactionType.EXPENSE
import app.fintrack.domain.model.TransactionType.INCOME
import app.fintrack.domain.model.TransactionType.TRANSFER
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class BalanceCalculatorTest {

    private val initial = mapOf("cash" to 10_000L, "card" to 50_000L, "usd" to 2_000L)
    private val currency = mapOf("cash" to "BYN", "card" to "BYN", "usd" to "USD")

    private fun income(acc: String, a: Long) = LedgerEntry(INCOME, acc, null, a, null, 0)
    private fun expense(acc: String, a: Long) = LedgerEntry(EXPENSE, acc, null, a, null, 0)
    private fun transfer(from: String, to: String, a: Long, toA: Long = a, fee: Long = 0) =
        LedgerEntry(TRANSFER, from, to, a, toA, fee)

    @Test
    fun incomeIncreasesExpenseDecreases() {
        val b = BalanceCalculator.balances(initial, listOf(income("cash", 2_500), expense("cash", 1_000)))
        assertEquals(11_500L, b["cash"])
        assertEquals(50_000L, b["card"])
    }

    @Test
    fun transferKeepsTotalUnchanged() {
        val before = initial.filterKeys { currency[it] == "BYN" }.values.sum()
        val b = BalanceCalculator.balances(initial, listOf(transfer("cash", "card", 10_000)))
        assertEquals(0L, b["cash"])
        assertEquals(60_000L, b["card"])
        assertEquals(before, b["cash"]!! + b["card"]!!)
    }

    @Test
    fun transferIsNeitherIncomeNorExpense() {
        val totals = BalanceCalculator.totals(listOf(transfer("cash", "card", 10_000))) { currency.getValue(it) }
        assertEquals(PeriodTotals(0, 0), totals["BYN"])
    }

    @Test
    fun transferFeeIsChargedAndCountedAsExpense() {
        val entries = listOf(transfer("cash", "card", 5_000, fee = 150))
        val b = BalanceCalculator.balances(initial, entries)
        assertEquals(10_000L - 5_150L, b["cash"])
        assertEquals(55_000L, b["card"])
        val totals = BalanceCalculator.totals(entries) { currency.getValue(it) }
        assertEquals(PeriodTotals(0, 150), totals["BYN"])
    }

    @Test
    fun crossCurrencyTransferUsesBothAmounts() {
        // 10 USD -> 32,50 BYN
        val b = BalanceCalculator.balances(initial, listOf(transfer("usd", "card", 1_000, toA = 3_250)))
        assertEquals(1_000L, b["usd"])
        assertEquals(53_250L, b["card"])
    }

    @Test
    fun adjustmentIsSignedAndExcludedFromTotals() {
        val delta = BalanceCalculator.adjustmentFor(currentMinor = 10_000, targetMinor = 9_300)
        assertEquals(-700L, delta)
        val entries = listOf(LedgerEntry(ADJUSTMENT, "cash", null, delta, null, 0))
        assertEquals(9_300L, BalanceCalculator.balances(initial, entries)["cash"])
        assertEquals(emptyMap<String, PeriodTotals>(), BalanceCalculator.totals(entries) { currency.getValue(it) })
    }

    @Test
    fun deletingOperationRestoresBalance() {
        val e = expense("card", 4_200)
        val with = BalanceCalculator.balances(initial, listOf(e))
        val without = BalanceCalculator.balances(initial, emptyList())
        assertEquals(45_800L, with["card"])
        assertEquals(50_000L, without["card"])
    }

    @Test
    fun editingOperationAccountMovesTheEffect() {
        val edited = BalanceCalculator.balances(initial, listOf(expense("cash", 1_000)))
        assertEquals(9_000L, edited["cash"])
        assertEquals(50_000L, edited["card"])
        val moved = BalanceCalculator.balances(initial, listOf(expense("card", 1_000)))
        assertEquals(10_000L, moved["cash"])
        assertEquals(49_000L, moved["card"])
    }

    @Test
    fun totalsSplitByCurrency() {
        val entries = listOf(income("cash", 100_000), expense("card", 30_000), expense("usd", 500))
        val t = BalanceCalculator.totals(entries) { currency.getValue(it) }
        assertEquals(PeriodTotals(100_000, 30_000), t["BYN"])
        assertEquals(PeriodTotals(0, 500), t["USD"])
        assertEquals(70_000L, t.getValue("BYN").netMinor)
    }

    /** Большой случайный журнал: сумма дельт по каждой операции = итоговые балансы. */
    @Test
    fun randomLedgerIsConsistent() {
        val rnd = Random(42)
        val ids = initial.keys.toList()
        val bynIds = ids.filter { currency[it] == "BYN" }
        val entries = List(50_000) {
            val acc = ids[rnd.nextInt(ids.size)]
            when (rnd.nextInt(4)) {
                0 -> income(acc, rnd.nextLong(1, 1_000_000))
                1 -> expense(acc, rnd.nextLong(1, 1_000_000))
                2 -> {
                    val from = bynIds[rnd.nextInt(bynIds.size)]
                    val to = bynIds.first { it != from }
                    transfer(from, to, rnd.nextLong(1, 1_000_000), fee = rnd.nextLong(0, 500))
                }
                else -> LedgerEntry(ADJUSTMENT, acc, null, rnd.nextLong(-50_000, 50_000), null, 0)
            }
        }
        val balances = BalanceCalculator.balances(initial, entries)
        for (id in ids) {
            val expected = initial.getValue(id) + entries.sumOf { BalanceCalculator.delta(it, id) }
            assertEquals(expected, balances[id])
        }
        // Изменение общего BYN-баланса = доходы − расходы (вкл. комиссии) + корректировки.
        val t = BalanceCalculator.totals(entries) { currency.getValue(it) }.getValue("BYN")
        val adjustments = entries.filter { it.type == ADJUSTMENT && currency[it.accountId] == "BYN" }
            .sumOf { it.amountMinor }
        val bynBefore = bynIds.sumOf { initial.getValue(it) }
        val bynAfter = bynIds.sumOf { balances.getValue(it) }
        assertEquals(bynBefore + t.netMinor + adjustments, bynAfter)
    }
}
