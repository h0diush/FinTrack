package app.fintrack.domain.logic

import java.math.BigDecimal
import java.math.MathContext

/**
 * Калькулятор для поля суммы: + − × ÷, скобки, запятая или точка.
 * Рекурсивный спуск на BigDecimal — без ошибок двоичной плавающей точки.
 */
object ExpressionEvaluator {

    private val MC = MathContext.DECIMAL128

    /** Результат выражения или null, если выражение неполное или некорректное. */
    fun evaluate(input: String): BigDecimal? {
        val s = normalize(input)
        if (s.isEmpty()) return null
        return try {
            val parser = Parser(s)
            val result = parser.parseExpression()
            if (parser.pos != s.length) null else result
        } catch (e: ArithmeticException) {
            null
        } catch (e: IllegalStateException) {
            null
        }
    }

    /** Предпросмотр во время ввода: хвостовые операторы и запятая игнорируются. */
    fun preview(input: String): BigDecimal? {
        var s = normalize(input)
        while (s.isNotEmpty() && s.last() in "+-*/.(") s = s.dropLast(1)
        return evaluate(s)
    }

    /** Есть ли в выражении операции (тогда показываем «= результат»). */
    fun hasOperators(input: String): Boolean {
        val s = normalize(input)
        return s.drop(1).any { it in "+-*/" }
    }

    fun normalize(input: String): String = buildString {
        for (c in input) {
            when (c) {
                ' ', ' ' -> Unit
                ',' -> append('.')
                '×', 'x', 'X' -> append('*')
                '÷', ':' -> append('/')
                '−', '–' -> append('-')
                else -> append(c)
            }
        }
    }

    private class Parser(private val s: String) {
        var pos = 0

        fun parseExpression(): BigDecimal {
            var value = parseTerm()
            while (pos < s.length && (s[pos] == '+' || s[pos] == '-')) {
                val op = s[pos++]
                val rhs = parseTerm()
                value = if (op == '+') value.add(rhs, MC) else value.subtract(rhs, MC)
            }
            return value
        }

        private fun parseTerm(): BigDecimal {
            var value = parseFactor()
            while (pos < s.length && (s[pos] == '*' || s[pos] == '/')) {
                val op = s[pos++]
                val rhs = parseFactor()
                value = if (op == '*') {
                    value.multiply(rhs, MC)
                } else {
                    if (rhs.signum() == 0) throw ArithmeticException("division by zero")
                    value.divide(rhs, MC)
                }
            }
            return value
        }

        private fun parseFactor(): BigDecimal {
            check(pos < s.length) { "unexpected end" }
            return when (s[pos]) {
                '-' -> {
                    pos++
                    parseFactor().negate()
                }
                '(' -> {
                    pos++
                    val v = parseExpression()
                    check(pos < s.length && s[pos] == ')') { "missing )" }
                    pos++
                    v
                }
                else -> parseNumber()
            }
        }

        private fun parseNumber(): BigDecimal {
            val start = pos
            var dots = 0
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) {
                if (s[pos] == '.') dots++
                pos++
            }
            val token = s.substring(start, pos)
            check(token.isNotEmpty() && dots <= 1 && token != ".") { "bad number" }
            return BigDecimal(if (token.endsWith(".")) token.dropLast(1) else token)
        }
    }
}
