package app.fintrack.domain.logic

import app.fintrack.domain.model.TransactionType
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Лимит бюджета. Действует с [startMonth] включительно до [endMonth] не включительно
 * (null — бессрочно). Изменение лимита создаёт новую «версию» с текущего месяца,
 * поэтому прошлые месяцы считаются по тем лимитам, что действовали тогда.
 *
 * @param categoryId null — общий бюджет на все расходы; иначе корневая категория.
 * @param rollover переносить неизрасходованный остаток на следующий месяц.
 */
data class BudgetLimit(
    val id: String,
    val categoryId: String?,
    val currency: String,
    val amountMinor: Long,
    val rollover: Boolean,
    val startMonth: YearMonth,
    val endMonth: YearMonth?,
) {
    fun isActive(month: YearMonth): Boolean =
        !month.isBefore(startMonth) && (endMonth == null || month.isBefore(endMonth))
}

enum class BudgetLevel { NORMAL, INFO, WARNING, EXCEEDED }

/** Пороги уведомлений, % от доступной суммы. Превышение — всегда 100 %. */
data class BudgetThresholds(val infoPercent: Int = 50, val warningPercent: Int = 80) {
    fun normalized(): BudgetThresholds {
        val info = infoPercent.coerceIn(1, 98)
        val warn = warningPercent.coerceIn(info + 1, 99)
        return BudgetThresholds(info, warn)
    }
}

data class BudgetStatus(
    val limit: BudgetLimit,
    /** Перенесено с прошлого месяца (только при включённом переносе; не бывает отрицательным). */
    val carriedMinor: Long,
    val spentMinor: Long,
) {
    val categoryId: String? get() = limit.categoryId
    val currency: String get() = limit.currency
    val limitMinor: Long get() = limit.amountMinor
    val availableMinor: Long get() = limit.amountMinor + carriedMinor
    val remainingMinor: Long get() = availableMinor - spentMinor

    /** Доля израсходованного: 0.0 … (может быть > 1 при превышении). */
    val fraction: Double
        get() = when {
            availableMinor > 0 -> spentMinor.toDouble() / availableMinor
            spentMinor > 0 -> Double.POSITIVE_INFINITY
            else -> 0.0
        }

    fun level(t: BudgetThresholds): BudgetLevel {
        val n = t.normalized()
        val pct = fraction * 100
        return when {
            pct >= 100 -> BudgetLevel.EXCEEDED
            pct >= n.warningPercent -> BudgetLevel.WARNING
            pct >= n.infoPercent -> BudgetLevel.INFO
            else -> BudgetLevel.NORMAL
        }
    }
}

data class BudgetForecast(
    val daysInMonth: Int,
    val daysElapsed: Int,
    /** Сколько дней осталось, включая сегодня. */
    val daysLeft: Int,
    /** Можно тратить в день до конца месяца, чтобы уложиться. */
    val dailyAllowanceMinor: Long,
    /** Прогноз расходов на весь месяц при текущем темпе. */
    val projectedSpendMinor: Long,
    /** На сколько дней хватит остатка при текущем темпе; null — тратить не начинали. */
    val daysUntilExhausted: Int?,
) {
    val lastsWholeMonth: Boolean get() = daysUntilExhausted == null || daysUntilExhausted >= daysLeft
}

/** Расходы по месяцам: ключ null — все расходы (вместе с комиссиями), иначе корневая категория. */
class MonthlySpending(private val data: Map<YearMonth, Map<String?, Long>>) {
    fun spent(month: YearMonth, categoryId: String?): Long = data[month]?.get(categoryId) ?: 0L
}

/** Изменения в таблице лимитов, которые нужно применить в одной транзакции. */
data class BudgetChanges(
    val updates: List<BudgetLimit> = emptyList(),
    val inserts: List<BudgetLimit> = emptyList(),
    val deletes: List<String> = emptyList(),
)

object BudgetCalculator {

    /** Сколько месяцев назад максимум прослеживается цепочка переносов. */
    const val MAX_CARRY_MONTHS = 36

    fun spending(entries: List<StatEntry>, currency: String, zone: ZoneId): MonthlySpending {
        val data = HashMap<YearMonth, HashMap<String?, Long>>()
        for (e in entries) {
            val amount = StatisticsCalculator.expenseOf(e, currency)
            if (amount == 0L) continue
            val m = YearMonth.from(StatisticsCalculator.dateOf(e, zone))
            val bucket = data.getOrPut(m) { HashMap() }
            bucket[null] = (bucket[null] ?: 0L) + amount
            if (e.type == TransactionType.EXPENSE && e.rootCategoryId != null) {
                bucket[e.rootCategoryId] = (bucket[e.rootCategoryId] ?: 0L) + amount
            }
        }
        return MonthlySpending(data)
    }

    fun activeLimit(limits: List<BudgetLimit>, categoryId: String?, currency: String, month: YearMonth): BudgetLimit? =
        limits.firstOrNull { it.categoryId == categoryId && it.currency == currency && it.isActive(month) }

    /** Перенос в [month] = положительный остаток прошлого месяца, если там был включён перенос. */
    fun carryInto(
        limits: List<BudgetLimit>,
        spending: MonthlySpending,
        categoryId: String?,
        currency: String,
        month: YearMonth,
        depth: Int = 0,
    ): Long {
        if (depth >= MAX_CARRY_MONTHS) return 0L
        val prev = month.minusMonths(1)
        val prevLimit = activeLimit(limits, categoryId, currency, prev) ?: return 0L
        if (!prevLimit.rollover) return 0L
        val available = prevLimit.amountMinor + carryInto(limits, spending, categoryId, currency, prev, depth + 1)
        return (available - spending.spent(prev, categoryId)).coerceAtLeast(0L)
    }

    fun status(
        limits: List<BudgetLimit>,
        spending: MonthlySpending,
        categoryId: String?,
        currency: String,
        month: YearMonth,
    ): BudgetStatus? {
        val limit = activeLimit(limits, categoryId, currency, month) ?: return null
        return BudgetStatus(
            limit = limit,
            carriedMinor = carryInto(limits, spending, categoryId, currency, month),
            spentMinor = spending.spent(month, categoryId),
        )
    }

    /** Все лимиты, действующие в [month]: общий (если есть) и по категориям. */
    fun statuses(
        limits: List<BudgetLimit>,
        spendingByCurrency: (String) -> MonthlySpending,
        month: YearMonth,
    ): List<BudgetStatus> =
        limits.filter { it.isActive(month) }
            .map { l -> status(limits, spendingByCurrency(l.currency), l.categoryId, l.currency, month)!! }

    fun forecast(status: BudgetStatus, month: YearMonth, today: LocalDate): BudgetForecast? {
        if (YearMonth.from(today) != month) return null
        val daysInMonth = month.lengthOfMonth()
        val elapsed = today.dayOfMonth
        val left = daysInMonth - elapsed + 1
        val spent = status.spentMinor
        val remaining = status.remainingMinor
        val projected = StatisticsCalculator.divRound(spent * daysInMonth, elapsed.toLong())
        val exhausted = when {
            spent <= 0 -> null
            remaining <= 0 -> 0
            else -> (remaining * elapsed / spent).toInt()
        }
        return BudgetForecast(
            daysInMonth = daysInMonth,
            daysElapsed = elapsed,
            daysLeft = left,
            dailyAllowanceMinor = if (remaining > 0) remaining / left else 0L,
            projectedSpendMinor = projected,
            daysUntilExhausted = exhausted,
        )
    }

    /**
     * Установить лимит с месяца [month]. Прошлые месяцы не меняются,
     * запланированные будущие версии сохраняются.
     */
    fun planSet(
        existing: List<BudgetLimit>,
        categoryId: String?,
        currency: String,
        month: YearMonth,
        amountMinor: Long,
        rollover: Boolean,
        newId: () -> String,
    ): BudgetChanges {
        val same = existing.filter { it.categoryId == categoryId && it.currency == currency }
        val current = same.firstOrNull { it.isActive(month) }
        if (current != null && current.startMonth == month) {
            return BudgetChanges(updates = listOf(current.copy(amountMinor = amountMinor, rollover = rollover)))
        }
        val nextStart = same.filter { it.startMonth.isAfter(month) }.minOfOrNull { it.startMonth }
        val inserted = BudgetLimit(
            id = newId(),
            categoryId = categoryId,
            currency = currency,
            amountMinor = amountMinor,
            rollover = rollover,
            startMonth = month,
            endMonth = current?.endMonth ?: nextStart,
        )
        return BudgetChanges(
            updates = listOfNotNull(current?.copy(endMonth = month)),
            inserts = listOf(inserted),
        )
    }

    /** Убрать лимит начиная с месяца [month]. */
    fun planRemove(existing: List<BudgetLimit>, categoryId: String?, currency: String, month: YearMonth): BudgetChanges {
        val current = existing.firstOrNull { it.categoryId == categoryId && it.currency == currency && it.isActive(month) }
            ?: return BudgetChanges()
        return if (current.startMonth == month) {
            BudgetChanges(deletes = listOf(current.id))
        } else {
            BudgetChanges(updates = listOf(current.copy(endMonth = month)))
        }
    }

    /** Месяц в компактном целом виде для хранения в базе: 2026-11 → 202611. */
    fun toKey(month: YearMonth): Int = month.year * 100 + month.monthValue

    fun fromKey(key: Int): YearMonth = YearMonth.of(key / 100, key % 100)
}
