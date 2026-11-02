package app.fintrack.domain.logic

import kotlin.math.abs
import kotlin.math.roundToInt

enum class InsightTone { POSITIVE, NEUTRAL, WARNING }

data class Insight(val text: String, val tone: InsightTone)

/**
 * Наблюдения по фактическим данным пользователя. Каждое сообщение — прямое
 * следствие посчитанных сумм; прогнозов и предположений здесь нет.
 * Работает полностью локально, без облачных сервисов.
 */
object Insights {

    /** Не сообщать об изменении категории, если она мала: меньше 5 % расходов. */
    private const val MIN_SHARE_FOR_CHANGE = 0.05
    private const val MIN_CHANGE_PERCENT = 10.0

    fun build(
        current: PeriodStats,
        previous: PeriodStats?,
        previousLabel: String?,
        categoryName: (String?) -> String,
    ): List<Insight> {
        val cur = current.currency
        val out = ArrayList<Insight>()

        current.topExpenseCategory?.let { top ->
            out += Insight(
                "Больше всего потрачено на «${categoryName(top.rootCategoryId)}»: " +
                    "${Money.format(top.amountMinor, cur)} (${percent(top.share * 100)} расходов)",
                InsightTone.NEUTRAL,
            )
        }

        if (previous != null && previousLabel != null && previous.expenseMinor > 0 && current.expenseMinor > 0) {
            val change = (current.expenseMinor - previous.expenseMinor) * 100.0 / previous.expenseMinor
            if (abs(change) >= 1) {
                val word = if (change > 0) "выросли" else "снизились"
                out += Insight(
                    "Общие расходы $word на ${percent(abs(change))} по сравнению с периодом «$previousLabel»",
                    if (change > 0) InsightTone.WARNING else InsightTone.POSITIVE,
                )
            }
            val biggest = StatisticsCalculator.compare(current.expenseByCategory, previous.expenseByCategory)
                .filter { it.rootCategoryId != null && it.changePercent != null }
                .filter { it.currentMinor >= current.expenseMinor * MIN_SHARE_FOR_CHANGE }
                .filter { abs(it.changePercent!!) >= MIN_CHANGE_PERCENT }
                .maxByOrNull { abs(it.currentMinor - it.previousMinor) }
            if (biggest != null) {
                val pct = biggest.changePercent!!
                val word = if (pct > 0) "выросли" else "снизились"
                out += Insight(
                    "Расходы на «${categoryName(biggest.rootCategoryId)}» $word на ${percent(abs(pct))}",
                    if (pct > 0) InsightTone.WARNING else InsightTone.POSITIVE,
                )
            }
        }

        val savings = current.savingsRatePercent
        if (savings != null) {
            out += if (savings >= 0) {
                Insight("Удалось сохранить ${percent(savings)} доходов", InsightTone.POSITIVE)
            } else {
                Insight("Расходы превысили доходы на ${Money.format(-current.netMinor, cur)}", InsightTone.WARNING)
            }
        }

        current.maxExpenseDay?.let { day ->
            if (current.days.size > 1) {
                out += Insight(
                    "Самый затратный день — ${day.date.dayOfMonth}.${"%02d".format(day.date.monthValue)}: " +
                        Money.format(day.expenseMinor, cur),
                    InsightTone.NEUTRAL,
                )
            }
        }
        return out
    }

    fun percent(value: Double): String {
        val v = if (value.isFinite()) value else 0.0
        return if (abs(v) < 10 && abs(v - v.roundToInt()) > 0.05) {
            "%.1f %%".format(v).replace('.', ',')
        } else {
            "${v.roundToInt()} %"
        }
    }
}
