package app.fintrack.domain

import app.fintrack.domain.logic.Period
import app.fintrack.domain.logic.Periods
import app.fintrack.domain.logic.TransactionDraft
import app.fintrack.domain.logic.TransactionValidator
import app.fintrack.domain.logic.ValidationError
import app.fintrack.domain.logic.ValidationResult
import app.fintrack.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class ValidatorAndPeriodsTest {

    private fun draft(
        type: TransactionType = TransactionType.EXPENSE,
        account: String? = "a",
        cur: String? = "BYN",
        to: String? = null,
        toCur: String? = null,
        amount: Long? = 1000,
        toAmount: Long? = null,
        fee: Long? = null,
        category: String? = "c",
    ) = TransactionDraft(type, account, cur, to, toCur, amount, toAmount, fee, category)

    private fun errors(d: TransactionDraft) =
        (TransactionValidator.validate(d) as? ValidationResult.Invalid)?.errors ?: emptySet()

    @Test
    fun validExpense() {
        val r = TransactionValidator.validate(draft()) as ValidationResult.Valid
        assertEquals(1000L, r.value.amountMinor)
        assertNull(r.value.toAccountId)
        assertEquals(0L, r.value.feeMinor)
    }

    @Test
    fun expenseNeedsAmountAccountCategory() {
        val e = errors(draft(amount = 0, account = null, category = null))
        assertEquals(
            setOf(ValidationError.AMOUNT_REQUIRED, ValidationError.ACCOUNT_REQUIRED, ValidationError.CATEGORY_REQUIRED),
            e,
        )
        assertTrue(ValidationError.AMOUNT_REQUIRED in errors(draft(amount = -5)))
        assertTrue(ValidationError.AMOUNT_TOO_LARGE in errors(draft(amount = Long.MAX_VALUE)))
    }

    @Test
    fun sameCurrencyTransferCopiesAmountAndDropsCategory() {
        val r = TransactionValidator.validate(
            draft(TransactionType.TRANSFER, to = "b", toCur = "BYN", toAmount = 999, fee = 20),
        ) as ValidationResult.Valid
        assertEquals(1000L, r.value.toAmountMinor)
        assertEquals(20L, r.value.feeMinor)
        assertNull(r.value.categoryId)
    }

    @Test
    fun transferRules() {
        assertTrue(ValidationError.SAME_ACCOUNT in errors(draft(TransactionType.TRANSFER, to = "a", toCur = "BYN")))
        assertTrue(ValidationError.TO_ACCOUNT_REQUIRED in errors(draft(TransactionType.TRANSFER)))
        assertTrue(
            ValidationError.TO_AMOUNT_REQUIRED in errors(draft(TransactionType.TRANSFER, to = "b", toCur = "USD")),
        )
        assertTrue(
            ValidationError.FEE_INVALID in errors(draft(TransactionType.TRANSFER, to = "b", toCur = "BYN", fee = -1)),
        )
        val ok = TransactionValidator.validate(
            draft(TransactionType.TRANSFER, to = "b", toCur = "USD", toAmount = 310),
        ) as ValidationResult.Valid
        assertEquals(310L, ok.value.toAmountMinor)
    }

    private val zone: ZoneId = ZoneId.of("Europe/Minsk")
    private val today: LocalDate = LocalDate.of(2026, 11, 2) // понедельник

    private fun millis(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun monthAndWeekRanges() {
        val m = Periods.range(Period.Month(YearMonth.of(2026, 10)), today, zone)!!
        assertEquals(millis(LocalDate.of(2026, 10, 1)), m.startMillis)
        assertEquals(millis(LocalDate.of(2026, 11, 1)), m.endMillis)

        val w = Periods.range(Period.ThisWeek, today, zone)!!
        assertEquals(millis(LocalDate.of(2026, 11, 2)), w.startMillis)
        assertEquals(millis(LocalDate.of(2026, 11, 9)), w.endMillis)

        val lw = Periods.range(Period.LastWeek, LocalDate.of(2026, 11, 8), zone)!!
        assertEquals(millis(LocalDate.of(2026, 10, 26)), lw.startMillis)

        assertNull(Periods.range(Period.All, today, zone))
    }

    @Test
    fun customRangeIsInclusiveAndOrderInsensitive() {
        val a = LocalDate.of(2026, 10, 10)
        val b = LocalDate.of(2026, 10, 5)
        val r = Periods.range(Period.Custom(a, b), today, zone)!!
        assertEquals(millis(b), r.startMillis)
        assertEquals(millis(a.plusDays(1)), r.endMillis)
    }

    @Test
    fun shiftAndLabels() {
        assertEquals(Period.Month(YearMonth.of(2026, 10)), Periods.shift(Period.Month(YearMonth.of(2026, 11)), -1))
        assertEquals(Period.Year(2027), Periods.shift(Period.Year(2026), 1))
        assertNull(Periods.shift(Period.Today, 1))
        assertEquals("Ноябрь", Periods.label(Period.Month(YearMonth.of(2026, 11)), today))
        assertEquals("Декабрь 2025", Periods.label(Period.Month(YearMonth.of(2025, 12)), today))
    }
}
