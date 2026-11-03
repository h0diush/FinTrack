package app.fintrack.domain.logic

import app.fintrack.domain.model.TransactionType
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Единица повторения. Хранится в БД текстом — константы не переименовывать. */
enum class RepeatUnit { DAY, WEEK, MONTH, YEAR }

/** Как проводить платёж в его дату. Хранится в БД текстом — константы не переименовывать. */
enum class RecurringMode {
    /** Платёж ждёт подтверждения: провести, изменить сумму или пропустить. */
    CONFIRM,

    /** Проводится сам. */
    AUTO,
}

/**
 * Правило повторения. Даты считаются от [start]: k-й повтор — start + k × [interval] единиц
 * (для месяцев 31-е число в коротком месяце становится последним днём, но следующий месяц снова 31-е).
 *
 * @param lastDayOfMonth только для MONTH: всегда последний день месяца.
 * @param endDate последняя допустимая дата (включительно), null — бессрочно.
 * @param maxCount сколько всего повторов от [start], null — без ограничения.
 */
data class RepeatRule(
    val unit: RepeatUnit,
    val interval: Int = 1,
    val start: LocalDate,
    val lastDayOfMonth: Boolean = false,
    val endDate: LocalDate? = null,
    val maxCount: Int? = null,
)

/**
 * Шаблон регулярного платежа: данные операции + правило.
 * [processedCount] — сколько первых повторов уже проведено или пропущено.
 */
data class RecurringPayment(
    val id: String,
    val type: TransactionType,
    val accountId: String,
    val toAccountId: String?,
    val amountMinor: Long,
    val toAmountMinor: Long?,
    val feeMinor: Long,
    val categoryId: String?,
    val payee: String,
    val comment: String,
    val rule: RepeatRule,
    /** Время проведения: минуты от полуночи. */
    val minuteOfDay: Int,
    val processedCount: Int,
    val mode: RecurringMode,
    val isPaused: Boolean,
) {
    /** Дата следующего (ещё не обработанного) повтора; null — повторы закончились. */
    val nextDate: LocalDate? get() = RecurrenceCalculator.dateOf(rule, processedCount)
    val isFinished: Boolean get() = nextDate == null

    /** Сколько повторов уже наступило и ждёт обработки. */
    fun dueCount(today: LocalDate): Int = RecurrenceCalculator.dueIndices(rule, processedCount, today).count()
}

object RecurrenceCalculator {

    const val MAX_INTERVAL = 999

    /** Дата k-го повтора (k ≥ 0) без учёта ограничений. */
    fun occurrence(rule: RepeatRule, k: Int): LocalDate {
        val n = k.toLong() * rule.interval.coerceAtLeast(1)
        return when (rule.unit) {
            RepeatUnit.DAY -> rule.start.plusDays(n)
            RepeatUnit.WEEK -> rule.start.plusWeeks(n)
            RepeatUnit.MONTH ->
                if (rule.lastDayOfMonth) YearMonth.from(rule.start).plusMonths(n).atEndOfMonth()
                else rule.start.plusMonths(n)
            RepeatUnit.YEAR -> rule.start.plusYears(n)
        }
    }

    /** Дата k-го повтора с учётом даты окончания и числа повторов; null — такого повтора не будет. */
    fun dateOf(rule: RepeatRule, k: Int): LocalDate? {
        if (k < 0) return null
        if (rule.maxCount != null && k >= rule.maxCount) return null
        val d = occurrence(rule, k)
        val end = rule.endDate
        return if (end != null && d.isAfter(end)) null else d
    }

    /** Номера повторов, чья дата наступила (≤ [today]), начиная с [from]. */
    fun dueIndices(rule: RepeatRule, from: Int, today: LocalDate, limit: Int = 1000): IntRange {
        var k = from
        while (k - from < limit) {
            val d = dateOf(rule, k) ?: break
            if (d.isAfter(today)) break
            k++
        }
        return from until k
    }

    /** Ближайшие [count] дат начиная с повтора [from]. */
    fun upcoming(rule: RepeatRule, from: Int, count: Int): List<LocalDate> =
        (from until from + count).mapNotNull { dateOf(rule, it) }

    /** Сумма в пересчёте на месяц — для сводки «в месяц уходит ≈». */
    fun perMonthMinor(amountMinor: Long, rule: RepeatRule): Long {
        val i = BigDecimal(rule.interval.coerceAtLeast(1))
        val perMonth = when (rule.unit) {
            RepeatUnit.DAY -> BigDecimal(365).divide(BigDecimal(12), MathContext.DECIMAL64)
            RepeatUnit.WEEK -> BigDecimal(52).divide(BigDecimal(12), MathContext.DECIMAL64)
            RepeatUnit.MONTH -> BigDecimal.ONE
            RepeatUnit.YEAR -> BigDecimal.ONE.divide(BigDecimal(12), MathContext.DECIMAL64)
        }.divide(i, MathContext.DECIMAL64)
        return BigDecimal(amountMinor).multiply(perMonth).setScale(0, RoundingMode.HALF_UP).toLong()
    }

    private val RU = Locale.forLanguageTag("ru")
    private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMMM", RU)
    private val FULL = DateTimeFormatter.ofPattern("d MMMM yyyy", RU)

    /** «Каждый месяц, 5-го числа», «Каждые 2 недели, по пятницам», «Ежегодно, 12 марта · ещё 3 раза». */
    fun describe(rule: RepeatRule, processed: Int = 0): String {
        val n = rule.interval.coerceAtLeast(1)
        val every = when (rule.unit) {
            RepeatUnit.DAY -> if (n == 1) "Каждый день" else "Каждые $n ${ruPlural(n.toLong(), "день", "дня", "дней")}"
            RepeatUnit.WEEK -> if (n == 1) "Каждую неделю" else "Каждые $n ${ruPlural(n.toLong(), "неделю", "недели", "недель")}"
            RepeatUnit.MONTH -> if (n == 1) "Каждый месяц" else "Каждые $n ${ruPlural(n.toLong(), "месяц", "месяца", "месяцев")}"
            RepeatUnit.YEAR -> if (n == 1) "Каждый год" else "Каждые $n ${ruPlural(n.toLong(), "год", "года", "лет")}"
        }
        val detail = when (rule.unit) {
            RepeatUnit.DAY -> null
            RepeatUnit.WEEK -> weekdayPlural(rule.start.dayOfWeek)
            RepeatUnit.MONTH -> if (rule.lastDayOfMonth) "в последний день" else "${rule.start.dayOfMonth}-го числа"
            RepeatUnit.YEAR -> rule.start.format(DAY_MONTH)
        }
        val end = when {
            rule.maxCount != null -> {
                val left = (rule.maxCount - processed).coerceAtLeast(0)
                "ещё $left ${ruPlural(left.toLong(), "раз", "раза", "раз")}"
            }
            rule.endDate != null -> "до ${rule.endDate.format(FULL)}"
            else -> null
        }
        return listOfNotNull(listOfNotNull(every, detail).joinToString(", "), end).joinToString(" · ")
    }

    /** «по понедельникам», «по средам»… */
    fun weekdayPlural(d: DayOfWeek): String = when (d) {
        DayOfWeek.MONDAY -> "по понедельникам"
        DayOfWeek.TUESDAY -> "по вторникам"
        DayOfWeek.WEDNESDAY -> "по средам"
        DayOfWeek.THURSDAY -> "по четвергам"
        DayOfWeek.FRIDAY -> "по пятницам"
        DayOfWeek.SATURDAY -> "по субботам"
        DayOfWeek.SUNDAY -> "по воскресеньям"
    }

    /** Ближайшая дата с днём недели [d], не раньше [from]. */
    fun nextWeekday(from: LocalDate, d: DayOfWeek): LocalDate {
        val shift = (d.value - from.dayOfWeek.value + 7) % 7
        return from.plusDays(shift.toLong())
    }
}
