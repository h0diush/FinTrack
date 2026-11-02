package app.fintrack.domain.logic

import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Копилка: отдельный счёт (тип PIGGY) с целью. Деньги попадают в неё переводом
 * с обычного счёта, поэтому общий баланс не меняется, а история видна в журнале.
 *
 * @param targetMinor цель в копейках; 0 — копилка без цели.
 * @param topUps сколько раз копилку пополняли переводом.
 * @param lastTopUpMinor сумма последнего пополнения (для подсказок «ещё N раз по …»).
 */
data class PiggyBank(
    val accountId: String,
    val name: String,
    val currency: String,
    val color: Long,
    val iconKey: String,
    val targetMinor: Long,
    val deadline: LocalDate?,
    val balanceMinor: Long,
    val topUps: Int = 0,
    val lastTopUpMinor: Long? = null,
    val createdAt: Long = 0,
    val excludeFromTotal: Boolean = false,
) {
    val hasTarget: Boolean get() = targetMinor > 0
}

data class PiggyProgress(
    val hasTarget: Boolean,
    /** 0…1 для кольца прогресса. */
    val fraction: Float,
    /** Целый процент. До достижения цели не больше 99, чтобы не показать «100 %» раньше времени. */
    val percent: Int,
    val remainingMinor: Long,
    val reached: Boolean,
    /** Дней до срока (может быть отрицательным), null — срок не задан. */
    val daysLeft: Long?,
    /** Сколько месяцев осталось на накопление (минимум 1), null — срок не задан или прошёл. */
    val monthsLeft: Int?,
    /** Сколько откладывать в месяц, чтобы успеть к сроку (округлено вверх до целых). */
    val perMonthMinor: Long?,
    val overdue: Boolean,
)

enum class PiggyStage { NO_TARGET, EMPTY, START, QUARTER, HALF, FINAL, DONE }

object PiggyCalculator {

    val MILESTONES = listOf(25, 50, 75, 100)

    /** Одна целая единица валюты в копейках (100 для BYN, 1 для JPY). */
    private fun unit(currency: String): Long = BigDecimal.TEN.pow(Money.scale(currency)).toLong()

    private fun ceilDiv(a: Long, b: Long): Long = if (a <= 0) 0 else (a + b - 1) / b

    fun progress(p: PiggyBank, today: LocalDate): PiggyProgress {
        val balance = p.balanceMinor.coerceAtLeast(0)
        if (!p.hasTarget) {
            return PiggyProgress(false, 0f, 0, 0, false, p.deadline?.let { ChronoUnit.DAYS.between(today, it) }, null, null, false)
        }
        val reached = balance >= p.targetMinor
        val remaining = (p.targetMinor - balance).coerceAtLeast(0)
        val rawPercent = BigDecimal(balance).multiply(BigDecimal(100)).divide(BigDecimal(p.targetMinor), 0, java.math.RoundingMode.DOWN).toInt()
        val percent = if (reached) 100 else rawPercent.coerceAtMost(99)
        val fraction = if (reached) 1f else (balance.toDouble() / p.targetMinor).toFloat().coerceIn(0f, 1f)

        val days = p.deadline?.let { ChronoUnit.DAYS.between(today, it) }
        val overdue = !reached && days != null && days <= 0
        var months: Int? = null
        var perMonth: Long? = null
        if (p.deadline != null && !reached && !overdue) {
            months = monthsUntil(today, p.deadline)
            val u = unit(p.currency)
            val exact = ceilDiv(remaining, months.toLong())
            perMonth = (ceilDiv(exact, u) * u).coerceAtMost(remaining)
        }
        return PiggyProgress(true, fraction, percent, remaining, reached, days, months, perMonth, overdue)
    }

    /** Сколько месяцев (с неполным — вверх) от сегодня до срока; минимум 1. */
    fun monthsUntil(today: LocalDate, deadline: LocalDate): Int {
        if (!deadline.isAfter(today)) return 1
        var m = ChronoUnit.MONTHS.between(today, deadline).toInt()
        if (today.plusMonths(m.toLong()).isBefore(deadline)) m++
        return m.coerceAtLeast(1)
    }

    /**
     * Быстрые суммы для пополнения: «сколько нужно в месяц», затем круглые
     * суммы, соизмеримые с остатком. Не больше остатка, без повторов, по возрастанию.
     */
    fun suggestAmounts(p: PiggyBank, progress: PiggyProgress): List<Long> {
        val u = unit(p.currency)
        val round = listOf(5L, 10, 20, 50, 100, 200, 500, 1_000, 2_000, 5_000, 10_000, 20_000, 50_000).map { it * u }
        if (!progress.hasTarget || progress.reached) return listOf(10 * u, 50 * u, 100 * u)
        val remaining = progress.remainingMinor
        val picked = round.filter { it * 20 >= remaining && it * 2 <= remaining }.let { list ->
            if (list.size <= 3) list else listOf(list.first(), list[list.size / 2], list.last())
        }
        val result = (listOfNotNull(progress.perMonthMinor) + picked + remaining)
            .filter { it in 1..remaining }
            .distinct()
            .sorted()
        return if (result.size <= 4) result else result.takeLast(4)
    }

    fun stage(p: PiggyBank, progress: PiggyProgress): PiggyStage = when {
        !progress.hasTarget -> PiggyStage.NO_TARGET
        progress.reached -> PiggyStage.DONE
        p.balanceMinor <= 0 -> PiggyStage.EMPTY
        progress.percent < 25 -> PiggyStage.START
        progress.percent < 50 -> PiggyStage.QUARTER
        progress.percent < 75 -> PiggyStage.HALF
        else -> PiggyStage.FINAL
    }

    /** Новые отметки, пройденные при переходе от [before] к [after] процентам. */
    fun crossedMilestones(before: Int, after: Int): List<Int> = MILESTONES.filter { it in (before + 1)..after }
}

/** Мотивационные фразы. Набор зависит от этапа накопления и дополняется живыми цифрами. */
object Motivation {

    private val RU: Locale = Locale.forLanguageTag("ru")
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", RU)

    private val byStage: Map<PiggyStage, List<String>> = mapOf(
        PiggyStage.NO_TARGET to listOf(
            "Копилка растёт — и вместе с ней ваша финансовая подушка.",
            "Регулярность важнее суммы: понемногу, но часто.",
            "Платите сначала себе: откладывайте в день зарплаты.",
            "Задайте цель — с ней копить в два раза интереснее.",
        ),
        PiggyStage.EMPTY to listOf(
            "Каждая большая цель начинается с первой монеты.",
            "Даже небольшой взнос сегодня лучше, чем крупный «когда-нибудь».",
            "Первый шаг — самый важный. Сделайте его сейчас.",
        ),
        PiggyStage.START to listOf(
            "Хорошее начало! Главное — регулярность, а не размер взноса.",
            "Маленькие пополнения складываются в большие суммы.",
            "Вы уже в пути. Продолжайте в том же темпе.",
        ),
        PiggyStage.QUARTER to listOf(
            "Четверть пути позади — отличный темп!",
            "Привычка копить уже работает на вас.",
            "Каждое пополнение приближает мечту на шаг.",
        ),
        PiggyStage.HALF to listOf(
            "Больше половины! Теперь цель ближе, чем старт.",
            "Половина пути — самое время не сбавлять обороты.",
            "Вы доказали, что умеете копить. Осталось довести до конца.",
        ),
        PiggyStage.FINAL to listOf(
            "Финишная прямая! Осталось совсем немного.",
            "Цель уже на горизонте — ещё пара пополнений.",
            "Не останавливайтесь в шаге от мечты.",
        ),
        PiggyStage.DONE to listOf(
            "Цель достигнута! Вы сделали это.",
            "Отличная работа: дисциплина окупилась.",
            "Можно ставить новую цель — у вас отлично получается.",
        ),
    )

    fun phrases(p: PiggyBank, today: LocalDate): List<String> {
        val pr = PiggyCalculator.progress(p, today)
        val base = byStage.getValue(PiggyCalculator.stage(p, pr))
        val dynamic = buildList {
            if (pr.hasTarget && !pr.reached && p.balanceMinor > 0) {
                add("До цели осталось ${Money.format(pr.remainingMinor, p.currency)}.")
            }
            val last = p.lastTopUpMinor
            if (pr.hasTarget && !pr.reached && last != null && last > 0) {
                val n = (pr.remainingMinor + last - 1) / last
                if (n in 1L..60L) {
                    add("Ещё $n ${ruPlural(n, "пополнение", "пополнения", "пополнений")} по ${Money.format(last, p.currency)} — и цель достигнута.")
                }
            }
            val perMonth = pr.perMonthMinor
            if (perMonth != null && p.deadline != null) {
                add("Откладывайте по ${Money.format(perMonth, p.currency)} в месяц — и к ${p.deadline.format(DATE)} всё получится.")
            }
            val days = pr.daysLeft
            if (pr.hasTarget && !pr.reached && days != null && days in 1L..30L) {
                add("До срока $days ${ruPlural(days, "день", "дня", "дней")} — время для финишного рывка!")
            }
            if (pr.overdue) {
                add("Срок прошёл, но цель рядом: осталось ${Money.format(pr.remainingMinor, p.currency)}. Перенести срок — нормально.")
            }
            if (p.topUps >= 3) {
                add("Уже ${p.topUps} ${ruPlural(p.topUps.toLong(), "пополнение", "пополнения", "пополнений")} — привычка копить работает!")
            }
        }
        // Чередуем: общая фраза, затем цифры, затем остальные общие.
        return (base.take(1) + dynamic + base.drop(1)).distinct()
    }
}

/** Русское склонение после числа: 1 день, 2 дня, 5 дней. */
fun ruPlural(n: Long, one: String, few: String, many: String): String {
    val a = kotlin.math.abs(n)
    val m10 = a % 10
    val m100 = a % 100
    return when {
        m10 == 1L && m100 != 11L -> one
        m10 in 2L..4L && m100 !in 12L..14L -> few
        else -> many
    }
}
