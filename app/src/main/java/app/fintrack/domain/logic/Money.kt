package app.fintrack.domain.logic

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

/**
 * Преобразования между минимальными единицами (Long) и десятичными значениями,
 * а также форматирование сумм. Точность берётся из ISO 4217:
 * BYN/USD/EUR — 2 знака, JPY — 0, KWD — 3.
 */
object Money {

    private const val NBSP = ' '
    private const val MINUS = '−'

    fun scale(currencyCode: String): Int = try {
        val digits = Currency.getInstance(currencyCode).defaultFractionDigits
        if (digits < 0) 2 else digits
    } catch (e: IllegalArgumentException) {
        2
    }

    /** Округляет до точности валюты (HALF_UP). Null — если не помещается в Long. */
    fun toMinor(value: BigDecimal, currencyCode: String): Long? = try {
        value.setScale(scale(currencyCode), RoundingMode.HALF_UP).unscaledValue().longValueExact()
    } catch (e: ArithmeticException) {
        null
    }

    fun fromMinor(minor: Long, currencyCode: String): BigDecimal =
        BigDecimal.valueOf(minor, scale(currencyCode))

    /**
     * Строгий разбор пользовательского ввода: «1 234,56» или «1234.56».
     * Отклоняет отрицательные значения и лишние знаки после запятой.
     */
    fun parse(text: String, currencyCode: String): Long? {
        val normalized = text.trim()
            .replace(" ", "")
            .replace(NBSP.toString(), "")
            .replace(',', '.')
        if (normalized.isEmpty()) return null
        if (!normalized.matches(Regex("""\d+(\.\d*)?|\.\d+"""))) return null
        val value = try {
            BigDecimal(normalized)
        } catch (e: NumberFormatException) {
            return null
        }
        if (value.stripTrailingZeros().scale() > scale(currencyCode)) return null
        return toMinor(value, currencyCode)
    }

    /** Значение для поля ввода без группировки: «1234,5». */
    fun toInput(minor: Long, currencyCode: String): String {
        val plain = fromMinor(minor, currencyCode).stripTrailingZeros()
        val text = if (plain.scale() < 0) plain.setScale(0).toPlainString() else plain.toPlainString()
        return text.replace('.', ',')
    }

    /**
     * «1 234,56 BYN», «−12,00 USD», «+500,00 BYN».
     * @param forceSign показывать «+» у положительных сумм.
     * @param withCode добавлять код валюты.
     */
    fun format(
        minor: Long,
        currencyCode: String,
        forceSign: Boolean = false,
        withCode: Boolean = true,
    ): String {
        val value = fromMinor(minor, currencyCode)
        val abs = value.abs().toPlainString()
        val intPart = abs.substringBefore('.')
        val fracPart = if (abs.contains('.')) abs.substringAfter('.') else ""

        val grouped = StringBuilder()
        intPart.reversed().forEachIndexed { index, c ->
            if (index > 0 && index % 3 == 0) grouped.append(NBSP)
            grouped.append(c)
        }
        val body = grouped.reverse().toString() + if (fracPart.isNotEmpty()) ",$fracPart" else ""

        val sign = when {
            value.signum() < 0 -> MINUS.toString()
            forceSign && value.signum() > 0 -> "+"
            else -> ""
        }
        return if (withCode) "$sign$body$NBSP$currencyCode" else "$sign$body"
    }

    /** Замаскированная сумма для режима «скрыть суммы». */
    fun masked(currencyCode: String): String = "••••$NBSP$currencyCode"
}
