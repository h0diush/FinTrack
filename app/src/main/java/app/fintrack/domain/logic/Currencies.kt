package app.fintrack.domain.logic

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate

/** Откуда взят курс. */
enum class RateSource { NBRB, MANUAL }

/**
 * Курс валюты к белорусскому рублю: сколько BYN стоит 1 единица [currency].
 * НБРБ публикует курсы к BYN, поэтому любые пары считаются через BYN.
 */
data class ExchangeRate(
    val currency: String,
    val bynPerUnit: BigDecimal,
    val source: RateSource,
    /** Дата курса НБРБ или дата ручного ввода. */
    val date: LocalDate,
)

/** Итог по нескольким валютам в одной; [missing] — валюты без курса, они не вошли в сумму. */
data class ConvertedTotal(val currency: String, val totalMinor: Long, val missing: List<String>)

/**
 * Пересчёт сумм между валютами по текущим курсам.
 * Суммы — в минимальных единицах; округление до точности целевой валюты (половина вверх).
 */
class CurrencyConverter(rates: Map<String, BigDecimal>) {

    private val bynPer: Map<String, BigDecimal> = rates + (PIVOT to BigDecimal.ONE)

    fun has(currency: String): Boolean = currency in bynPer

    fun canConvert(from: String, to: String): Boolean = from == to || (has(from) && has(to))

    /** Сколько [to] стоит 1 [from]; null — нет курса. */
    fun rate(from: String, to: String): BigDecimal? {
        if (from == to) return BigDecimal.ONE
        val a = bynPer[from] ?: return null
        val b = bynPer[to] ?: return null
        return a.divide(b, MC)
    }

    /** Пересчитать [minor] из [from] в [to]; null — нет курса. */
    fun convert(minor: Long, from: String, to: String): Long? {
        if (from == to) return minor
        val r = rate(from, to) ?: return null
        val value = Money.fromMinor(minor, from).multiply(r, MC)
        return Money.toMinor(value.setScale(Money.scale(to), RoundingMode.HALF_UP), to)
    }

    /** Сложить остатки по валютам в одной валюте. */
    fun total(byCurrency: Map<String, Long>, target: String): ConvertedTotal {
        var sum = 0L
        val missing = ArrayList<String>()
        for ((c, minor) in byCurrency) {
            val v = convert(minor, c, target)
            if (v == null) {
                if (minor != 0L) missing += c
            } else {
                sum += v
            }
        }
        return ConvertedTotal(target, sum, missing.sorted())
    }

    /**
     * Операции для статистики в одной валюте: суммы пересчитаны, валюта заменена на [target].
     * Операции в валютах без курса остаются как есть (в статистике [target] они не учитываются),
     * их валюты — во втором значении.
     */
    fun convertEntries(entries: List<StatEntry>, target: String): Pair<List<StatEntry>, Set<String>> {
        val missing = HashSet<String>()
        val out = entries.map { e ->
            val fromOk = canConvert(e.currency, target)
            val toOk = e.toCurrency == null || canConvert(e.toCurrency, target)
            if (!fromOk) missing += e.currency
            if (!toOk) missing += e.toCurrency!!
            e.copy(
                amountMinor = if (fromOk) convertSigned(e.amountMinor, e.currency, target) else e.amountMinor,
                feeMinor = if (fromOk) convertSigned(e.feeMinor, e.currency, target) else e.feeMinor,
                currency = if (fromOk) target else e.currency,
                toAmountMinor = if (toOk) e.toAmountMinor?.let { convertSigned(it, e.toCurrency!!, target) } else e.toAmountMinor,
                toCurrency = if (toOk && e.toCurrency != null) target else e.toCurrency,
            )
        }
        return out to missing
    }

    /** Знак сохраняется (корректировки бывают отрицательными), округление симметричное. */
    private fun convertSigned(minor: Long, from: String, to: String): Long {
        val v = convert(kotlin.math.abs(minor), from, to) ?: return minor
        return if (minor < 0) -v else v
    }

    companion object {
        const val PIVOT = "BYN"
        private val MC = MathContext.DECIMAL64
    }
}

/** Курс из ответа НБРБ: [scale] единиц валюты стоят [officialRate] BYN. */
data class NbrbRate(val code: String, val scale: Int, val officialRate: BigDecimal, val date: LocalDate) {
    val bynPerUnit: BigDecimal get() = officialRate.divide(BigDecimal(scale.coerceAtLeast(1)), MathContext.DECIMAL64)
}

/**
 * Разбор ответа https://api.nbrb.by/exrates/rates?periodicity=0 — массив объектов вида
 * {"Cur_ID":431,"Date":"2026-11-03T00:00:00","Cur_Abbreviation":"USD","Cur_Scale":1,"Cur_Name":"…","Cur_OfficialRate":3.2741}.
 * Без библиотек JSON, чтобы разбор проверялся обычными тестами.
 */
object NbrbParser {
    private val OBJECT = Regex("""\{[^{}]*\}""")
    private val CODE = Regex(""""Cur_Abbreviation"\s*:\s*"([A-Z]{3})"""")
    private val SCALE = Regex(""""Cur_Scale"\s*:\s*(\d+)""")
    private val RATE = Regex(""""Cur_OfficialRate"\s*:\s*([0-9]+(?:\.[0-9]+)?)""")
    private val DATE = Regex(""""Date"\s*:\s*"(\d{4}-\d{2}-\d{2})""")

    fun parse(json: String): List<NbrbRate> = OBJECT.findAll(json).mapNotNull { m ->
        val o = m.value
        val code = CODE.find(o)?.groupValues?.get(1) ?: return@mapNotNull null
        val scale = SCALE.find(o)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
        val rate = RATE.find(o)?.groupValues?.get(1)?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return@mapNotNull null
        val date = DATE.find(o)?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
        NbrbRate(code, scale, rate, date)
    }.toList()
}
