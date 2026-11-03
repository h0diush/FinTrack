package app.fintrack.domain.logic

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Вид долга. Хранится в БД текстом — константы не переименовывать. */
enum class LoanKind(val title: String) {
    CREDIT("Кредит"),
    INSTALLMENT("Рассрочка"),
    MORTGAGE("Ипотека"),
}

/** Схема платежей. Хранится в БД текстом — константы не переименовывать. */
enum class LoanSchedule(val title: String) {
    /** Равные платежи весь срок. */
    ANNUITY("Аннуитетный"),

    /** Долг гасится равными частями, проценты — на остаток: платежи уменьшаются. */
    DIFFERENTIATED("Дифференцированный"),
}

/**
 * Кредит, рассрочка или ипотека. Сам долг — счёт типа LOAN с отрицательным
 * остатком: −(сумма кредита) в начале, к нулю по мере погашения.
 * Платёж — перевод со счёта на счёт кредита (тело долга) плюс расход «Кредиты» (проценты).
 *
 * @param debtMinor текущий остаток долга (≥ 0) = −баланс счёта кредита.
 * @param paidCount сколько ежемесячных платежей внесено, включая сделанные до начала учёта.
 */
data class Loan(
    val accountId: String,
    val name: String,
    val currency: String,
    val color: Long,
    val kind: LoanKind,
    val schedule: LoanSchedule,
    val principalMinor: Long,
    val rateBp: Long,
    val startDate: LocalDate,
    val termMonths: Int,
    val debtMinor: Long,
    val paidCount: Int,
    val interestPaidMinor: Long = 0,
    val isArchived: Boolean = false,
    val excludeFromTotal: Boolean = true,
) {
    val isPaidOff: Boolean get() = debtMinor <= 0
    val endDate: LocalDate get() = LoanCalculator.paymentDate(startDate, termMonths)
    val nextIndex: Int get() = paidCount + 1
    val nextDate: LocalDate get() = LoanCalculator.paymentDate(startDate, nextIndex)

    /** Сколько платежей осталось по графику (не меньше одного, пока есть долг). */
    val monthsLeft: Int get() = if (isPaidOff) 0 else (termMonths - paidCount).coerceAtLeast(1)

    /** Доля погашенного тела долга, 0…1. */
    val paidFraction: Float
        get() = if (principalMinor <= 0) 1f else ((principalMinor - debtMinor).toFloat() / principalMinor).coerceIn(0f, 1f)

    fun upcoming(): List<LoanRow> = if (isPaidOff) emptyList()
    else LoanCalculator.schedule(debtMinor, rateBp, schedule, startDate, nextIndex, monthsLeft)

    /** Следующий платёж по графику. */
    fun nextPayment(): LoanRow? = upcoming().firstOrNull()

    /** Дней до следующего платежа; отрицательное — просрочка. */
    fun daysUntilNext(today: LocalDate): Long = ChronoUnit.DAYS.between(today, nextDate)
}

/** Строка графика: платёж №[index] в дату [date]. */
data class LoanRow(
    val index: Int,
    val date: LocalDate,
    val paymentMinor: Long,
    val interestMinor: Long,
    val principalMinor: Long,
    /** Остаток долга после платежа. */
    val remainingMinor: Long,
)

/** Как платёж делится на проценты и погашение долга. */
data class PaymentSplit(val interestMinor: Long, val principalMinor: Long) {
    val totalMinor: Long get() = interestMinor + principalMinor
}

/** Итог по графику: сколько всего заплатить и сколько из этого — проценты. */
data class LoanTotals(val paymentsMinor: Long, val interestMinor: Long, val firstMinor: Long, val lastMinor: Long)

object LoanCalculator {

    const val MAX_TERM_MONTHS = 600
    const val MAX_RATE_BP = 100_000L

    /** Дата платежа №k: тот же день месяца, что и выдача (31 янв → 28/29 фев). */
    fun paymentDate(start: LocalDate, k: Int): LocalDate = start.plusMonths(k.toLong())

    /** Проценты за месяц на остаток долга: долг × ставка / 12, до копейки (половина вверх). */
    fun monthlyInterest(debtMinor: Long, rateBp: Long): Long {
        if (debtMinor <= 0 || rateBp <= 0) return 0L
        return BigDecimal.valueOf(debtMinor)
            .multiply(BigDecimal.valueOf(rateBp))
            .divide(BigDecimal.valueOf(120_000L), 0, RoundingMode.HALF_UP)
            .longValueExact()
    }

    /**
     * Аннуитетный платёж: долг × i / (1 − (1 + i)^−n), i — месячная ставка.
     * Без процентов — долг поровну на n месяцев. Округление вверх до копейки,
     * чтобы последний платёж не оказался больше остальных.
     */
    fun annuityPayment(debtMinor: Long, rateBp: Long, months: Int): Long {
        if (debtMinor <= 0) return 0L
        val n = months.coerceAtLeast(1)
        if (rateBp <= 0) return ceilDiv(debtMinor, n.toLong())
        val mc = MathContext.DECIMAL128
        val i = BigDecimal.valueOf(rateBp).divide(BigDecimal.valueOf(120_000L), mc)
        val growth = BigDecimal.ONE.add(i).pow(n, mc)
        val payment = BigDecimal.valueOf(debtMinor).multiply(i, mc).multiply(growth, mc)
            .divide(growth.subtract(BigDecimal.ONE), mc)
        return payment.setScale(0, RoundingMode.CEILING).longValueExact()
    }

    /**
     * График с платежа №[fromIndex]: [months] платежей на остаток [debtMinor].
     * Последний платёж закрывает долг полностью (без копеечных хвостов).
     */
    fun schedule(
        debtMinor: Long,
        rateBp: Long,
        type: LoanSchedule,
        start: LocalDate,
        fromIndex: Int,
        months: Int,
    ): List<LoanRow> {
        if (debtMinor <= 0) return emptyList()
        val n = months.coerceAtLeast(1)
        val annuity = annuityPayment(debtMinor, rateBp, n)
        val part = ceilDiv(debtMinor, n.toLong())
        val rows = ArrayList<LoanRow>(n)
        var balance = debtMinor
        for (step in 0 until n) {
            if (balance <= 0) break
            val interest = monthlyInterest(balance, rateBp)
            val last = step == n - 1
            val principal = when {
                last -> balance
                type == LoanSchedule.ANNUITY -> (annuity - interest).coerceIn(0, balance)
                else -> part.coerceAtMost(balance)
            }
            balance -= principal
            rows += LoanRow(fromIndex + step, paymentDate(start, fromIndex + step), interest + principal, interest, principal, balance)
        }
        return rows
    }

    fun totals(rows: List<LoanRow>): LoanTotals = LoanTotals(
        paymentsMinor = rows.sumOf { it.paymentMinor },
        interestMinor = rows.sumOf { it.interestMinor },
        firstMinor = rows.firstOrNull()?.paymentMinor ?: 0L,
        lastMinor = rows.lastOrNull()?.paymentMinor ?: 0L,
    )

    /** Остаток долга после [paid] платежей по графику с самого начала (для кредита, взятого раньше). */
    fun debtAfter(principalMinor: Long, rateBp: Long, type: LoanSchedule, start: LocalDate, termMonths: Int, paid: Int): Long {
        if (paid <= 0) return principalMinor
        val rows = schedule(principalMinor, rateBp, type, start, 1, termMonths)
        return rows.getOrNull(paid - 1)?.remainingMinor ?: 0L
    }

    /** Сколько дат платежей уже наступило к [today] (подсказка «уже внесено платежей»). */
    fun dueCount(start: LocalDate, termMonths: Int, today: LocalDate): Int {
        var k = 0
        while (k < termMonths && !paymentDate(start, k + 1).isAfter(today)) k++
        return k
    }

    /**
     * Ежемесячный платёж [amountMinor]: сначала проценты за месяц, остальное — в погашение долга.
     * Досрочный платёж ([early]) целиком идёт в долг. null — сумма больше, чем нужно для полного погашения.
     */
    fun split(debtMinor: Long, rateBp: Long, amountMinor: Long, early: Boolean): PaymentSplit? {
        if (amountMinor <= 0) return null
        val interest = if (early) 0L else monthlyInterest(debtMinor, rateBp).coerceAtMost(amountMinor)
        val principal = amountMinor - interest
        if (principal > debtMinor.coerceAtLeast(0)) return null
        return PaymentSplit(interest, principal)
    }

    /** Сколько нужно, чтобы закрыть долг этим платежом. */
    fun payoffAmount(debtMinor: Long, rateBp: Long, early: Boolean): Long =
        debtMinor.coerceAtLeast(0) + if (early) 0L else monthlyInterest(debtMinor, rateBp)

    /** «12,5» → 1250; «0» допустим (беспроцентная рассрочка). */
    fun parseRate(text: String): Long? {
        val t = text.trim().replace(" ", "").replace(',', '.').removeSuffix("%")
        if (t.isEmpty() || !t.matches(Regex("""\d+(\.\d{0,2})?"""))) return null
        val bp = BigDecimal(t).multiply(BigDecimal(100), MathContext.DECIMAL64).setScale(0, RoundingMode.HALF_UP).toLong()
        return bp.takeIf { it in 0..MAX_RATE_BP }
    }

    private fun ceilDiv(a: Long, b: Long): Long = (a + b - 1) / b
}
