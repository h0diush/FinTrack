package app.fintrack.domain.model

/**
 * Доменные модели. Здесь нет зависимостей от Android — этот пакет
 * компилируется и тестируется на обычной JVM.
 *
 * Все денежные суммы хранятся в минимальных единицах валюты (копейки, центы)
 * в виде Long. Это точная целочисленная арифметика без ошибок округления;
 * для ввода и отображения используется BigDecimal (см. [Money]).
 */

enum class TransactionType {
    INCOME,
    EXPENSE,

    /** Перевод между своими счетами. Не доход и не расход. Комиссия — расход. */
    TRANSFER,

    /** Корректировка баланса. Сумма со знаком. Не доход и не расход. */
    ADJUSTMENT,
}

enum class CategoryKind { EXPENSE, INCOME }

enum class AccountType(val title: String) {
    CASH("Наличные"),
    CARD("Банковская карта"),
    SALARY_CARD("Зарплатная карта"),
    SAVINGS("Накопительный счёт"),
    E_WALLET("Электронный кошелёк"),
    CURRENCY("Валютный счёт"),
    CUSTOM("Другой"),

    /** Копилка с целью. Показывается отдельно от обычных счетов. */
    PIGGY("Копилка"),

    /** Кредит, рассрочка или ипотека: остаток — долг со знаком минус. Показывается в разделе «Кредиты». */
    LOAN("Кредит"),
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class Account(
    val id: String,
    val name: String,
    val type: AccountType,
    val currencyCode: String,
    val initialBalanceMinor: Long,
    val color: Long,
    val sortOrder: Int,
    val isArchived: Boolean,
    val createdAt: Long,
    /** Счёт не входит в общий баланс (кредитка, долг, чужие деньги). Операции учитываются как обычно. */
    val excludeFromTotal: Boolean = false,
)

data class AccountWithBalance(
    val account: Account,
    val balanceMinor: Long,
)

data class Category(
    val id: String,
    val name: String,
    val kind: CategoryKind,
    val parentId: String?,
    val iconKey: String,
    val color: Long,
    val sortOrder: Int,
    val isArchived: Boolean,
    val isSystem: Boolean,
)

data class Transaction(
    val id: String,
    val type: TransactionType,
    /** Счёт операции; для перевода — счёт списания. */
    val accountId: String,
    /** Счёт зачисления (только для перевода). */
    val toAccountId: String?,
    /** Всегда > 0, кроме ADJUSTMENT (там со знаком). В валюте [accountId]. */
    val amountMinor: Long,
    /** Сумма зачисления в валюте [toAccountId] (только для перевода). */
    val toAmountMinor: Long?,
    /** Комиссия перевода, в валюте счёта списания. */
    val feeMinor: Long,
    val categoryId: String?,
    /** Момент операции, миллисекунды UTC. */
    val dateTime: Long,
    val payee: String,
    val comment: String,
    val isFavorite: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Операция вместе с данными для отображения в списках. */
data class TransactionDetails(
    val transaction: Transaction,
    val accountName: String,
    val accountCurrency: String,
    val toAccountName: String?,
    val toAccountCurrency: String?,
    val categoryName: String?,
    val categoryIcon: String?,
    val categoryColor: Long?,
    val parentCategoryName: String?,
)

/** Итоги за период в одной валюте. */
data class PeriodTotals(
    val incomeMinor: Long,
    val expenseMinor: Long,
) {
    val netMinor: Long get() = incomeMinor - expenseMinor

    companion object {
        val ZERO = PeriodTotals(0, 0)
    }
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val baseCurrency: String = "BYN",
    val hideAmounts: Boolean = false,
    val lastAccountId: String? = null,
    val lastExpenseCategoryId: String? = null,
    val lastIncomeCategoryId: String? = null,
    /** Порог «информация» для бюджетов, % (по ТЗ — 50). */
    val budgetInfoPercent: Int = 50,
    /** Порог «предупреждение» для бюджетов, % (по ТЗ — 80). Превышение — всегда 100 %. */
    val budgetWarningPercent: Int = 80,
    /** Уведомлять, когда расходы пересекают пороги бюджета. */
    val budgetAlerts: Boolean = true,
)
