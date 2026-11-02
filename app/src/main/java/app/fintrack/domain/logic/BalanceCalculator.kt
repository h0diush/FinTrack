package app.fintrack.domain.logic

import app.fintrack.domain.model.AccountWithBalance
import app.fintrack.domain.model.PeriodTotals
import app.fintrack.domain.model.TransactionType

/** Минимум данных операции, влияющих на балансы. */
data class LedgerEntry(
    val type: TransactionType,
    val accountId: String,
    val toAccountId: String?,
    val amountMinor: Long,
    val toAmountMinor: Long?,
    val feeMinor: Long,
)

/**
 * Эталонные правила движения денег. Баланс счёта не хранится отдельно,
 * а всегда выводится из начального остатка и операций — поэтому удаление
 * или изменение операции автоматически даёт правильный баланс.
 *
 * Те же правила реализованы в SQL (data/db/Sql.kt); тесты сверяют оба варианта.
 *
 *  INCOME:     счёт += amount
 *  EXPENSE:    счёт −= amount
 *  TRANSFER:   откуда −= amount + fee; куда += toAmount
 *  ADJUSTMENT: счёт += amount (со знаком)
 */
object BalanceCalculator {

    /** Изменение баланса конкретного счёта от одной операции. */
    fun delta(entry: LedgerEntry, accountId: String): Long {
        var d = 0L
        if (entry.accountId == accountId) {
            d += when (entry.type) {
                TransactionType.INCOME -> entry.amountMinor
                TransactionType.EXPENSE -> -entry.amountMinor
                TransactionType.TRANSFER -> -Math.addExact(entry.amountMinor, entry.feeMinor)
                TransactionType.ADJUSTMENT -> entry.amountMinor
            }
        }
        if (entry.type == TransactionType.TRANSFER && entry.toAccountId == accountId) {
            d += entry.toAmountMinor ?: 0L
        }
        return d
    }

    fun balances(initial: Map<String, Long>, entries: Iterable<LedgerEntry>): Map<String, Long> {
        val result = initial.toMutableMap()
        for (e in entries) {
            for (id in listOfNotNull(e.accountId, e.toAccountId).distinct()) {
                result[id] = Math.addExact(result[id] ?: 0L, delta(e, id))
            }
        }
        return result
    }

    /**
     * Доход и расход за период по валютам счетов.
     * Расход включает комиссии переводов; переводы и корректировки — не доход и не расход.
     */
    fun totals(
        entries: Iterable<LedgerEntry>,
        currencyOf: (accountId: String) -> String,
    ): Map<String, PeriodTotals> {
        val income = HashMap<String, Long>()
        val expense = HashMap<String, Long>()
        for (e in entries) {
            val currency = currencyOf(e.accountId)
            when (e.type) {
                TransactionType.INCOME -> income[currency] = (income[currency] ?: 0L) + e.amountMinor
                TransactionType.EXPENSE -> expense[currency] = (expense[currency] ?: 0L) + e.amountMinor
                TransactionType.TRANSFER -> expense[currency] = (expense[currency] ?: 0L) + e.feeMinor
                TransactionType.ADJUSTMENT -> Unit
            }
        }
        return (income.keys + expense.keys).associateWith {
            PeriodTotals(income[it] ?: 0L, expense[it] ?: 0L)
        }
    }

    /** Сумма корректировки, чтобы баланс стал [targetMinor]. */
    /**
     * Общий баланс по валютам: только активные счета, у которых не выключено
     * «Учитывать в общем балансе». Порядок валют — как у счетов.
     */
    fun totalByCurrency(accounts: List<AccountWithBalance>): Map<String, Long> =
        accounts.filter { !it.account.isArchived && !it.account.excludeFromTotal }
            .groupBy { it.account.currencyCode }
            .mapValues { (_, list) -> list.sumOf { it.balanceMinor } }

    fun adjustmentFor(currentMinor: Long, targetMinor: Long): Long =
        Math.subtractExact(targetMinor, currentMinor)
}
