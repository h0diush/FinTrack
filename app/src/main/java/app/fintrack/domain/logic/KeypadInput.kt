package app.fintrack.domain.logic

/** Клавиши встроенной клавиатуры-калькулятора. */
sealed interface KeypadKey {
    data class Digit(val d: Char) : KeypadKey
    data object Comma : KeypadKey
    data class Operator(val op: Char) : KeypadKey
    data object Backspace : KeypadKey
    data object Clear : KeypadKey
}

/**
 * Правила набора суммы. Чистая функция: принимает текущую строку и клавишу,
 * возвращает новую строку. Ограничивает знаки после запятой точностью валюты.
 */
object KeypadInput {

    const val PLUS = '+'
    const val MINUS = '−'
    const val TIMES = '×'
    const val DIVIDE = '÷'
    private val OPERATORS = setOf(PLUS, MINUS, TIMES, DIVIDE)
    private const val MAX_LENGTH = 40
    private const val MAX_INT_DIGITS = 12

    fun apply(current: String, key: KeypadKey, scale: Int): String = when (key) {
        is KeypadKey.Digit -> digit(current, key.d, scale)
        KeypadKey.Comma -> comma(current, scale)
        is KeypadKey.Operator -> operator(current, key.op)
        KeypadKey.Backspace -> current.dropLast(1)
        KeypadKey.Clear -> ""
    }

    private fun segment(s: String): String {
        val idx = s.indexOfLast { it in OPERATORS }
        return if (idx < 0) s else s.substring(idx + 1)
    }

    private fun digit(s: String, d: Char, scale: Int): String {
        if (!d.isDigit() || s.length >= MAX_LENGTH) return s
        val seg = segment(s)
        if (seg.contains(',')) {
            val decimals = seg.substringAfter(',').length
            return if (decimals >= scale) s else s + d
        }
        if (seg == "0") return s.dropLast(1) + d
        if (seg.length >= MAX_INT_DIGITS) return s
        return s + d
    }

    private fun comma(s: String, scale: Int): String {
        if (scale == 0 || s.length >= MAX_LENGTH) return s
        val seg = segment(s)
        if (seg.contains(',')) return s
        return if (seg.isEmpty()) s + "0," else s + ","
    }

    private fun operator(s: String, op: Char): String {
        if (op !in OPERATORS || s.isEmpty() || s.length >= MAX_LENGTH) return s
        var base = s
        if (base.last() == ',') base = base.dropLast(1)
        if (base.isEmpty()) return s
        if (base.last() in OPERATORS) base = base.dropLast(1)
        if (base.isEmpty()) return s
        return base + op
    }
}
