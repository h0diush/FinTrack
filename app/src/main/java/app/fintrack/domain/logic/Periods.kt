package app.fintrack.domain.logic

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Период фильтрации: начало включительно, конец не включительно, мс UTC. */
data class TimeRange(val startMillis: Long, val endMillis: Long)

sealed interface Period {
    data object All : Period
    data object Today : Period
    data object Yesterday : Period
    data object ThisWeek : Period
    data object LastWeek : Period
    data class Month(val month: YearMonth) : Period
    data object Last3Months : Period
    data object Last6Months : Period
    data class Year(val year: Int) : Period
    data class Custom(val start: LocalDate, val endInclusive: LocalDate) : Period
}

object Periods {

    private val RU: Locale = Locale.forLanguageTag("ru")

    fun range(period: Period, today: LocalDate, zone: ZoneId): TimeRange? {
        fun r(start: LocalDate, endExclusive: LocalDate) = TimeRange(
            start.atStartOfDay(zone).toInstant().toEpochMilli(),
            endExclusive.atStartOfDay(zone).toInstant().toEpochMilli(),
        )
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return when (period) {
            Period.All -> null
            Period.Today -> r(today, today.plusDays(1))
            Period.Yesterday -> r(today.minusDays(1), today)
            Period.ThisWeek -> r(monday, monday.plusWeeks(1))
            Period.LastWeek -> r(monday.minusWeeks(1), monday)
            is Period.Month -> r(period.month.atDay(1), period.month.plusMonths(1).atDay(1))
            Period.Last3Months -> r(today.minusMonths(3).plusDays(1), today.plusDays(1))
            Period.Last6Months -> r(today.minusMonths(6).plusDays(1), today.plusDays(1))
            is Period.Year -> r(LocalDate.of(period.year, 1, 1), LocalDate.of(period.year + 1, 1, 1))
            is Period.Custom -> {
                val (a, b) = if (period.start <= period.endInclusive) {
                    period.start to period.endInclusive
                } else {
                    period.endInclusive to period.start
                }
                r(a, b.plusDays(1))
            }
        }
    }

    /** Сдвиг на соседний месяц/год (стрелки ‹ ›). Null — период не листается. */
    fun shift(period: Period, delta: Int): Period? = when (period) {
        is Period.Month -> Period.Month(period.month.plusMonths(delta.toLong()))
        is Period.Year -> Period.Year(period.year + delta)
        else -> null
    }

    /**
     * Предыдущий сопоставимый период — для сравнения «с прошлым периодом».
     * Месяц → прошлый месяц, неделя → прошлая неделя, произвольный — столько же дней раньше.
     */
    fun previous(period: Period, today: LocalDate): Period? {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return when (period) {
            Period.All -> null
            Period.Today -> Period.Yesterday
            Period.Yesterday -> today.minusDays(2).let { Period.Custom(it, it) }
            Period.ThisWeek -> Period.LastWeek
            Period.LastWeek -> Period.Custom(monday.minusWeeks(2), monday.minusWeeks(1).minusDays(1))
            is Period.Month -> Period.Month(period.month.minusMonths(1))
            Period.Last3Months -> Period.Custom(today.minusMonths(6).plusDays(1), today.minusMonths(3))
            Period.Last6Months -> Period.Custom(today.minusMonths(12).plusDays(1), today.minusMonths(6))
            is Period.Year -> Period.Year(period.year - 1)
            is Period.Custom -> {
                val (a, b) = if (period.start <= period.endInclusive) {
                    period.start to period.endInclusive
                } else {
                    period.endInclusive to period.start
                }
                val length = java.time.temporal.ChronoUnit.DAYS.between(a, b) + 1
                Period.Custom(a.minusDays(length), a.minusDays(1))
            }
        }
    }

    fun label(period: Period, today: LocalDate): String = when (period) {
        Period.All -> "Всё время"
        Period.Today -> "Сегодня"
        Period.Yesterday -> "Вчера"
        Period.ThisWeek -> "Эта неделя"
        Period.LastWeek -> "Прошлая неделя"
        is Period.Month -> monthLabel(period.month, today)
        Period.Last3Months -> "Последние 3 месяца"
        Period.Last6Months -> "Последние 6 месяцев"
        is Period.Year -> "${period.year} год"
        is Period.Custom -> "${shortDate(period.start)} — ${shortDate(period.endInclusive)}"
    }

    fun monthLabel(month: YearMonth, today: LocalDate): String {
        val name = month.month.getDisplayName(TextStyle.FULL_STANDALONE, RU)
            .replaceFirstChar { it.titlecase(RU) }
        return if (month.year == today.year) name else "$name ${month.year}"
    }

    fun shortDate(date: LocalDate): String {
        val m = date.month.getDisplayName(TextStyle.SHORT, RU).trimEnd('.')
        return "${date.dayOfMonth} $m ${date.year}"
    }
}
