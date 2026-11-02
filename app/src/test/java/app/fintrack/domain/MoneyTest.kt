package app.fintrack.domain

import app.fintrack.domain.logic.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class MoneyTest {

    private val nb = ' '

    @Test
    fun scaleFollowsIso4217() {
        assertEquals(2, Money.scale("BYN"))
        assertEquals(2, Money.scale("USD"))
        assertEquals(0, Money.scale("JPY"))
        assertEquals(3, Money.scale("KWD"))
        assertEquals(2, Money.scale("???"))
    }

    @Test
    fun toMinorAndBackIsExact() {
        assertEquals(12345L, Money.toMinor(BigDecimal("123.45"), "BYN"))
        assertEquals(BigDecimal("123.45"), Money.fromMinor(12345, "BYN"))
        assertEquals(123L, Money.toMinor(BigDecimal("123"), "JPY"))
        // 0.1 + 0.2 — классическая ошибка Double. Здесь её нет.
        val sum = Money.toMinor(BigDecimal("0.1"), "BYN")!! + Money.toMinor(BigDecimal("0.2"), "BYN")!!
        assertEquals(30L, sum)
    }

    @Test
    fun toMinorRoundsHalfUp() {
        assertEquals(334L, Money.toMinor(BigDecimal("3.335"), "BYN"))
        assertEquals(333L, Money.toMinor(BigDecimal("3.3349"), "BYN"))
    }

    @Test
    fun toMinorRejectsOverflow() {
        assertNull(Money.toMinor(BigDecimal("1e30"), "BYN"))
    }

    @Test
    fun parseAcceptsCommaAndSpaces() {
        assertEquals(123456L, Money.parse("1 234,56", "BYN"))
        assertEquals(123450L, Money.parse("1234.5", "BYN"))
        assertEquals(500L, Money.parse("5", "BYN"))
        assertEquals(1000L, Money.parse("1000", "JPY"))
    }

    @Test
    fun parseRejectsInvalid() {
        assertNull(Money.parse("", "BYN"))
        assertNull(Money.parse("-5", "BYN"))
        assertNull(Money.parse("1,234", "BYN"))
        assertNull(Money.parse("1.5", "JPY"))
        assertNull(Money.parse("abc", "BYN"))
        assertNull(Money.parse("1..2", "BYN"))
    }

    @Test
    fun parseAllowsTrailingZeros() {
        assertEquals(150L, Money.parse("1,500", "BYN"))
    }

    @Test
    fun formatGroupsAndSigns() {
        assertEquals("1${nb}234,56${nb}BYN", Money.format(123456, "BYN"))
        assertEquals("−12,00${nb}USD", Money.format(-1200, "USD"))
        assertEquals("+500,00${nb}BYN", Money.format(50000, "BYN", forceSign = true))
        assertEquals("0,00${nb}BYN", Money.format(0, "BYN", forceSign = true))
        assertEquals("1${nb}000${nb}000${nb}JPY", Money.format(1_000_000, "JPY"))
        assertEquals("0,05", Money.format(5, "BYN", withCode = false))
    }

    @Test
    fun toInputDropsTrailingZeros() {
        assertEquals("1234,5", Money.toInput(123450, "BYN"))
        assertEquals("100", Money.toInput(10000, "BYN"))
        assertEquals("0", Money.toInput(0, "BYN"))
    }
}
