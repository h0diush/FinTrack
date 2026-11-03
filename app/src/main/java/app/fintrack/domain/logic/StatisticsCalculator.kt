package app.fintrack.domain.logic

import app.fintrack.domain.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Минимум данных операции для статистики. Суммы — в валюте соответствующего счёта. */
data class StatEntry(
    val type: TransactionType,
    val accountId: String,
    val toAccountId: String?,
    val amountMinor: Long,
    val toAmountMinor: Long?,
    val feeMinor: Long,
    val dateTime: Long,
    val currency: String,
    val toCurrency: String?,
    val categoryId: String?,
    /** Корневая категория (для подкатегории — её родитель). */
    val rootCategoryId: String?,
)

/** Доля категории. rootCategoryId == null — комиссии переводов. */
data class CategoryAmount(val rootCategoryId: String?, val amountMinor: Long, val share: Double)

data class DayPoint(val date: LocalDate, val incomeMinor: Long, val expenseMinor: Long)

data class MonthPoint(val month: YearMonth, val incomeMinor: Long, val expenseMinor: Long)

data class BalancePoint(val date: LocalDate, val balanceMinor: Long)

data class CategoryChange(
    val rootCategoryId: String?,
    val currentMinor: Long,
    val previousMinor: Long,
    /** Изменение в процентах; null, если в прошлом периоде расходов не было. */
    val changePercent: Double?,
)

data class PeriodStats(
    val currency: String,
    val incomeMinor: Long,
    val expenseMinor: Long,
    val operationsCount: Int,
    val expenseByCategory: List<CategoryAmount>,
    val incomeByCategory: List<CategoryAmount>,
    /** Каждый прошедший день периода, включая дни без операций. */
    val days: List<DayPoint>,
    val avgDailyExpenseMinor: Long,
    val avgMonthlyIncomeMinor: Long,
    val maxExpenseDay: DayPoint?,
    /** Расходы в процентах от доходов; null, если доходов не было. */
    val expenseToIncomePercent: Double?,
    /** Доля сохранённых доходов; null, если доходов не было. */
    val savingsRatePercent: Double?,
) {
    val netMinor: Long get() = incomeMinor - expenseMinor
    val topExpenseCategory: CategoryAmount? get() = expenseByCategory.firstOrNull()
}

/**
 * Статистика за период в одной валюте. Чистые функции — вся логика проверяется
 * юнит-тестами. Правила совпадают с BalanceCalculator:
 *  - перевод не доход и не расход, его комиссия — расход;
 *  - корректировка меняет баланс, но не входит ни в доходы, ни в расходы.
 */
object StatisticsCalculator {

    fun incomeOf(e: StatEntry, currency: String): Long =
        if (e.type == TransactionType.INCOME && e.currency == currency) e.amountMinor else 0L

    fun expenseOf(e: StatEntry, currency: String): Long = when {
        e.currency != currency -> 0L
        e.type == TransactionType.EXPENSE -> e.amountMinor
        e.type == TransactionType.TRANSFER -> e.feeMinor
        else -> 0L
    }

    /**
     * Изменение суммарного баланса счетов в валюте [currency].
     * Счета из [excluded] не учитываются: перевод на такой счёт уменьшает общий баланс.
     */
    fun balanceDeltaOf(e: StatEntry, currency: String, excluded: Set<String> = emptySet()): Long {
        var d = 0L
        if (e.currency == currency && e.accountId !in excluded) {
            d += when (e.type) {
                TransactionType.INCOME -> e.amountMinor
                TransactionType.EXPENSE -> -e.amountMinor
                TransactionType.TRANSFER -> -(e.amountMinor + e.feeMinor)
                TransactionType.ADJUSTMENT -> e.amountMinor
            }
        }
        if (e.type == TransactionType.TRANSFER && e.toCurrency == currency && e.toAccountId !in excluded) {
            d += e.toAmountMinor ?: 0L
        }
        return d
    }

    fun dateOf(e: StatEntry, zone: ZoneId): LocalDate = Instant.ofEpochMilli(e.dateTime).atZone(zone).toLocalDate()

    /**
     * @param start первый день периода
     * @param endExclusive день после последнего дня периода
     * @param today сегодня — будущие дни периода не учитываются в средних
     */
    fun compute(
        entries: List<StatEntry>,
        currency: String,
        start: LocalDate,
        endExclusive: LocalDate,
        today: LocalDate,
        zone: ZoneId,
    ): PeriodStats {
        val inRange = entries.filter {
            val d = dateOf(it, zone)
            d >= start && d < endExclusive
        }

        var income = 0L
        var expense = 0L
        var count = 0
        val expenseByRoot = LinkedHashMap<String?, Long>()
        val incomeByRoot = LinkedHashMap<String?, Long>()
        val byDayIncome = HashMap<LocalDate, Long>()
        val byDayExpense = HashMap<LocalDate, Long>()

        for (e in inRange) {
            val inc = incomeOf(e, currency)
            val exp = expenseOf(e, currency)
            val touches = e.currency == currency || e.toCurrency == currency
            if (touches) count++
            if (inc == 0L && exp == 0L) continue
            val day = dateOf(e, zone)
            if (inc != 0L) {
                income += inc
                incomeByRoot[e.rootCategoryId] = (incomeByRoot[e.rootCategoryId] ?: 0L) + inc
                byDayIncome[day] = (byDayIncome[day] ?: 0L) + inc
            }
            if (exp != 0L) {
                expense += exp
                val key = if (e.type == TransactionType.TRANSFER) null else e.rootCategoryId
                expenseByRoot[key] = (expenseByRoot[key] ?: 0L) + exp
                byDayExpense[day] = (byDayExpense[day] ?: 0L) + exp
            }
        }

        val lastDay = minOf(endExclusive.minusDays(1), today)
        val days = ArrayList<DayPoint>()
        var d = start
        while (!d.isAfter(lastDay)) {
            days += DayPoint(d, byDayIncome[d] ?: 0L, byDayExpense[d] ?: 0L)
            d = d.plusDays(1)
        }
        val elapsedDays = days.size.coerceAtLeast(1)
        val elapsedMonths = days.map { YearMonth.from(it.date) }.distinct().size.coerceAtLeast(1)

        return PeriodStats(
            currency = currency,
            incomeMinor = income,
            expenseMinor = expense,
            operationsCount = count,
            expenseByCategory = shares(expenseByRoot, expense),
            incomeByCategory = shares(incomeByRoot, income),
            days = days,
            avgDailyExpenseMinor = divRound(expense, elapsedDays.toLong()),
            avgMonthlyIncomeMinor = divRound(income, elapsedMonths.toLong()),
            maxExpenseDay = days.filter { it.expenseMinor > 0 }.maxByOrNull { it.expenseMinor },
            expenseToIncomePercent = if (income > 0) expense * 100.0 / income else null,
            savingsRatePercent = if (income > 0) (income - expense) * 100.0 / income else null,
        )
    }

    private fun shares(map: Map<String?, Long>, total: Long): List<CategoryAmount> =
        map.entries
            .filter { it.value != 0L }
            .sortedByDescending { it.value }
            .map { CategoryAmount(it.key, it.value, if (total > 0) it.value.toDouble() / total else 0.0) }

    /** Доходы и расходы по месяцам — для столбчатой диаграммы. */
    fun monthly(entries: List<StatEntry>, currency: String, months: List<YearMonth>, zone: ZoneId): List<MonthPoint> {
        val income = HashMap<YearMonth, Long>()
        val expense = HashMap<YearMonth, Long>()
        for (e in entries) {
            val m = YearMonth.from(dateOf(e, zone))
            val inc = incomeOf(e, currency)
            val exp = expenseOf(e, currency)
            if (inc != 0L) income[m] = (income[m] ?: 0L) + inc
            if (exp != 0L) expense[m] = (expense[m] ?: 0L) + exp
        }
        return months.map { MonthPoint(it, income[it] ?: 0L, expense[it] ?: 0L) }
    }

    /**
     * Баланс на конец каждого дня.
     * @param opening суммарный баланс в валюте на начало первого дня
     */
    fun balanceSeries(
        entries: List<StatEntry>,
        currency: String,
        opening: Long,
        days: List<LocalDate>,
        zone: ZoneId,
        excluded: Set<String> = emptySet(),
    ): List<BalancePoint> {
        if (days.isEmpty()) return emptyList()
        val deltaByDay = HashMap<LocalDate, Long>()
        for (e in entries) {
            val delta = balanceDeltaOf(e, currency, excluded)
            if (delta != 0L) {
                val day = dateOf(e, zone)
                deltaByDay[day] = (deltaByDay[day] ?: 0L) + delta
            }
        }
        var running = opening
        return days.map { day ->
            running += deltaByDay[day] ?: 0L
            BalancePoint(day, running)
        }
    }

    /** Сравнение расходов по категориям с прошлым периодом, по убыванию текущих сумм. */
    fun compare(current: List<CategoryAmount>, previous: List<CategoryAmount>): List<CategoryChange> {
        val prev = previous.associate { it.rootCategoryId to it.amountMinor }
        val keys = (current.map { it.rootCategoryId } + previous.map { it.rootCategoryId }).distinct()
        val cur = current.associate { it.rootCategoryId to it.amountMinor }
        return keys.map { k ->
            val c = cur[k] ?: 0L
            val p = prev[k] ?: 0L
            CategoryChange(k, c, p, if (p > 0) (c - p) * 100.0 / p else null)
        }.sortedByDescending { it.currentMinor }
    }

    /** Целочисленное деление с округлением половины вверх (для неотрицательных). */
    fun divRound(value: Long, by: Long): Long {
        if (by <= 0) return 0
        return if (value >= 0) (value + by / 2) / by else -((-value + by / 2) / by)
    }
}
