package app.fintrack.domain.logic

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.math.pow

/**
 * Настройки вклада на накопительном счёте.
 * Проценты начисляются раз в месяц в день открытия и добавляются к счёту (капитализация).
 *
 * @param rateBp годовая ставка в сотых долях процента: 12,5 % → 1250.
 * @param postedMonths сколько начислений уже проведено (1…termMonths).
 */
data class Deposit(
    val accountId: String,
    val rateBp: Long,
    val startDate: LocalDate,
    val termMonths: Int,
    val interestCategoryId: String?,
    val postedMonths: Int,
) {
    val endDate: LocalDate get() = DepositCalculator.postingDate(startDate, termMonths)
    val isFinished: Boolean get() = postedMonths >= termMonths
}

/** Строка графика: начисление №[index] в дату [date]. */
data class DepositRow(
    val index: Int,
    val date: LocalDate,
    val interestMinor: Long,
    val balanceMinor: Long,
)

data class DepositYear(val year: Int, val interestMinor: Long, val endBalanceMinor: Long)

object DepositCalculator {

    const val MAX_TERM_MONTHS = 600
    const val MAX_RATE_BP = 100_000L // 1000 % годовых — защита от опечаток

    /** Дата начисления №k: тот же день месяца, что и открытие (31 янв → 28/29 фев → 31 мар). */
    fun postingDate(start: LocalDate, k: Int): LocalDate = start.plusMonths(k.toLong())

    /**
     * Проценты за месяц: остаток × ставка / 12, округление до копейки (половина вверх).
     * На нулевой или отрицательный остаток проценты не начисляются.
     */
    fun monthlyInterest(balanceMinor: Long, rateBp: Long): Long {
        if (balanceMinor <= 0 || rateBp <= 0) return 0L
        return BigDecimal.valueOf(balanceMinor)
            .multiply(BigDecimal.valueOf(rateBp))
            .divide(BigDecimal.valueOf(120_000L), 0, RoundingMode.HALF_UP)
            .longValueExact()
    }

    /** Номера начислений, срок которых уже наступил, но которые ещё не проведены. */
    fun dueIndices(d: Deposit, today: LocalDate): IntRange {
        var last = d.postedMonths
        while (last < d.termMonths && !postingDate(d.startDate, last + 1).isAfter(today)) last++
        return (d.postedMonths + 1)..last
    }

    /**
     * Прогноз с месяца [fromIndex] до конца срока при неизменном остатке
     * (без пополнений и снятий).
     */
    fun schedule(startBalanceMinor: Long, rateBp: Long, startDate: LocalDate, fromIndex: Int, termMonths: Int): List<DepositRow> {
        val rows = ArrayList<DepositRow>()
        var balance = startBalanceMinor
        for (k in fromIndex..termMonths) {
            val interest = monthlyInterest(balance, rateBp)
            balance += interest
            rows += DepositRow(k, postingDate(startDate, k), interest, balance)
        }
        return rows
    }

    /** Итоги по календарным годам — для компактной таблицы. */
    fun byYear(rows: List<DepositRow>): List<DepositYear> =
        rows.groupBy { it.date.year }.map { (year, list) ->
            DepositYear(year, list.sumOf { it.interestMinor }, list.last().balanceMinor)
        }

    /** Эффективная годовая ставка с ежемесячной капитализацией, %: (1 + r/12)^12 − 1. */
    fun effectiveRatePercent(rateBp: Long): Double {
        val r = rateBp / 10_000.0
        return ((1 + r / 12).pow(12) - 1) * 100
    }

    /** «12,5» → 1250. Не больше двух знаков после запятой, 0 < ставка ≤ 1000 %. */
    fun parseRate(text: String): Long? {
        val t = text.trim().replace(" ", "").replace(',', '.').removeSuffix("%")
        if (t.isEmpty() || !t.matches(Regex("""\d+(\.\d{0,2})?"""))) return null
        val bp = BigDecimal(t).multiply(BigDecimal(100), MathContext.DECIMAL64).setScale(0, RoundingMode.HALF_UP).toLong()
        return bp.takeIf { it in 1..MAX_RATE_BP }
    }

    /** Срок: число лет (можно дробное: «1,5» → 18 мес.) или месяцев. */
    fun parseTerm(text: String, inYears: Boolean): Int? {
        val t = text.trim().replace(" ", "").replace(',', '.')
        if (t.isEmpty() || !t.matches(Regex("""\d+(\.\d+)?"""))) return null
        val v = BigDecimal(t)
        val months = if (inYears) v.multiply(BigDecimal(12)) else v
        if (months.stripTrailingZeros().scale() > 0) return null // не целое число месяцев
        val m = months.toInt()
        return m.takeIf { it in 1..MAX_TERM_MONTHS }
    }

    fun formatRate(rateBp: Long): String {
        val v = BigDecimal.valueOf(rateBp, 2).stripTrailingZeros()
        return (if (v.scale() < 0) v.setScale(0) else v).toPlainString().replace('.', ',')
    }

    /** «18 мес.», «2 года», «1 год 6 мес.» */
    fun formatTerm(months: Int): String {
        val y = months / 12
        val m = months % 12
        fun years(n: Int) = when {
            n % 10 == 1 && n % 100 != 11 -> "$n год"
            n % 10 in 2..4 && n % 100 !in 12..14 -> "$n года"
            else -> "$n лет"
        }
        return when {
            y == 0 -> "$m мес."
            m == 0 -> years(y)
            else -> "${years(y)} $m мес."
        }
    }
}
