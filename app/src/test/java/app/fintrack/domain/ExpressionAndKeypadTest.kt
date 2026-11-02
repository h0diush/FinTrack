package app.fintrack.domain

import app.fintrack.domain.logic.ExpressionEvaluator
import app.fintrack.domain.logic.KeypadInput
import app.fintrack.domain.logic.KeypadKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class ExpressionAndKeypadTest {

    private fun eval(s: String) = ExpressionEvaluator.evaluate(s)

    @Test
    fun evaluatesArithmeticWithPrecedence() {
        assertEquals(0, BigDecimal("8").compareTo(eval("2+3*2")))
        assertEquals(0, BigDecimal("10").compareTo(eval("(2+3)*2")))
        assertEquals(0, BigDecimal("15.5").compareTo(eval("12,5+3")))
        assertEquals(0, BigDecimal("0.3").compareTo(eval("0.1+0.2")))
        assertEquals(0, BigDecimal("6").compareTo(eval("12÷2")))
        assertEquals(0, BigDecimal("4").compareTo(eval("10−3×2")))
        assertEquals(0, BigDecimal("-3").compareTo(eval("-3")))
    }

    @Test
    fun rejectsBrokenExpressions() {
        assertNull(eval(""))
        assertNull(eval("2+"))
        assertNull(eval("5/0"))
        assertNull(eval("(2+3"))
        assertNull(eval("1.2.3"))
        assertNull(eval("abc"))
    }

    @Test
    fun previewIgnoresTrailingOperator() {
        assertEquals(0, BigDecimal("12").compareTo(ExpressionEvaluator.preview("12+")))
        assertEquals(0, BigDecimal("12").compareTo(ExpressionEvaluator.preview("12,")))
        assertTrue(ExpressionEvaluator.hasOperators("12+3"))
        assertFalse(ExpressionEvaluator.hasOperators("12"))
    }

    private fun type(keys: String, scale: Int = 2): String {
        var s = ""
        for (c in keys) {
            val key = when {
                c.isDigit() -> KeypadKey.Digit(c)
                c == ',' -> KeypadKey.Comma
                c == '<' -> KeypadKey.Backspace
                else -> KeypadKey.Operator(c)
            }
            s = KeypadInput.apply(s, key, scale)
        }
        return s
    }

    @Test
    fun keypadLimitsDecimalsToCurrencyScale() {
        assertEquals("12,34", type("12,345"))
        // Для JPY запятая не вводится вовсе.
        assertEquals("125", type("12,5", scale = 0))
    }

    @Test
    fun keypadNormalizesLeadingZeroAndComma() {
        assertEquals("5", type("05"))
        assertEquals("0,5", type(",5"))
        assertEquals("1,5", type("1,,5"))
    }

    @Test
    fun keypadReplacesRepeatedOperators() {
        assertEquals("12×", type("12+×"))
        assertEquals("", type("+"))
        assertEquals("12+", type("12,+"))
        assertEquals("12+3,45", type("12+3,456"))
    }

    @Test
    fun keypadBackspaceAndClear() {
        assertEquals("12", type("123<"))
        assertEquals("", KeypadInput.apply("123", KeypadKey.Clear, 2))
    }

    @Test
    fun keypadResultEvaluates() {
        val s = type("12,5+7,5×2")
        assertEquals(0, BigDecimal("27.5").compareTo(eval(s)))
    }
}
