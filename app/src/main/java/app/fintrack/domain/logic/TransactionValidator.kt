package app.fintrack.domain.logic

import app.fintrack.domain.model.TransactionType

enum class ValidationError(val message: String) {
    AMOUNT_REQUIRED("Введите сумму больше нуля"),
    AMOUNT_TOO_LARGE("Слишком большая сумма"),
    ACCOUNT_REQUIRED("Выберите счёт"),
    CATEGORY_REQUIRED("Выберите категорию"),
    TO_ACCOUNT_REQUIRED("Выберите счёт зачисления"),
    SAME_ACCOUNT("Счета списания и зачисления совпадают"),
    TO_AMOUNT_REQUIRED("Введите сумму зачисления"),
    FEE_INVALID("Некорректная комиссия"),
}

/** Черновик операции из формы, ещё не проверенный. */
data class TransactionDraft(
    val type: TransactionType,
    val accountId: String?,
    val accountCurrency: String?,
    val toAccountId: String?,
    val toAccountCurrency: String?,
    val amountMinor: Long?,
    val toAmountMinor: Long?,
    val feeMinor: Long?,
    val categoryId: String?,
)

/** Проверенные и нормализованные значения, готовые к записи. */
data class ValidTransaction(
    val type: TransactionType,
    val accountId: String,
    val toAccountId: String?,
    val amountMinor: Long,
    val toAmountMinor: Long?,
    val feeMinor: Long,
    val categoryId: String?,
)

sealed interface ValidationResult {
    data class Valid(val value: ValidTransaction) : ValidationResult
    data class Invalid(val errors: Set<ValidationError>) : ValidationResult
}

object TransactionValidator {

    /** Верхняя граница суммы: защита от переполнения при суммировании. */
    const val MAX_AMOUNT_MINOR = 1_000_000_000_000_000L

    fun validate(d: TransactionDraft): ValidationResult {
        val errors = mutableSetOf<ValidationError>()
        val amount = d.amountMinor
        if (amount == null || amount <= 0) errors += ValidationError.AMOUNT_REQUIRED
        else if (amount > MAX_AMOUNT_MINOR) errors += ValidationError.AMOUNT_TOO_LARGE
        if (d.accountId == null) errors += ValidationError.ACCOUNT_REQUIRED

        when (d.type) {
            TransactionType.INCOME, TransactionType.EXPENSE -> {
                if (d.categoryId == null) errors += ValidationError.CATEGORY_REQUIRED
                if (errors.isNotEmpty()) return ValidationResult.Invalid(errors)
                return ValidationResult.Valid(
                    ValidTransaction(d.type, d.accountId!!, null, amount!!, null, 0L, d.categoryId),
                )
            }

            TransactionType.TRANSFER -> {
                if (d.toAccountId == null) errors += ValidationError.TO_ACCOUNT_REQUIRED
                else if (d.toAccountId == d.accountId) errors += ValidationError.SAME_ACCOUNT
                val fee = d.feeMinor ?: 0L
                if (fee < 0 || fee > MAX_AMOUNT_MINOR) errors += ValidationError.FEE_INVALID
                val sameCurrency = d.accountCurrency != null && d.accountCurrency == d.toAccountCurrency
                val toAmount = if (sameCurrency) amount else d.toAmountMinor
                if (!sameCurrency && d.toAccountId != null) {
                    if (toAmount == null || toAmount <= 0) errors += ValidationError.TO_AMOUNT_REQUIRED
                    else if (toAmount > MAX_AMOUNT_MINOR) errors += ValidationError.AMOUNT_TOO_LARGE
                }
                if (errors.isNotEmpty()) return ValidationResult.Invalid(errors)
                return ValidationResult.Valid(
                    ValidTransaction(d.type, d.accountId!!, d.toAccountId, amount!!, toAmount, fee, null),
                )
            }

            TransactionType.ADJUSTMENT ->
                // Корректировки создаются только через AccountRepository.adjustBalance.
                return ValidationResult.Invalid(setOf(ValidationError.AMOUNT_REQUIRED))
        }
    }
}
