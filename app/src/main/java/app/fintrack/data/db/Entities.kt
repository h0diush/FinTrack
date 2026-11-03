package app.fintrack.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import app.fintrack.domain.logic.LoanKind
import app.fintrack.domain.logic.LoanSchedule
import app.fintrack.domain.logic.RecurringMode
import app.fintrack.domain.logic.RepeatUnit
import app.fintrack.domain.model.AccountType
import app.fintrack.domain.model.CategoryKind
import app.fintrack.domain.model.TransactionType

@Entity(tableName = "accounts", indices = [Index("sortOrder")])
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: AccountType,
    val currencyCode: String,
    val initialBalanceMinor: Long,
    val color: Long,
    val sortOrder: Int,
    val isArchived: Boolean,
    val createdAt: Long,
    /** Не учитывать в общем балансе. Добавлено в версии 5 (DEFAULT 0 — как в миграции). */
    @ColumnInfo(defaultValue = "0") val excludeFromTotal: Boolean = false,
)

@Entity(tableName = "categories", indices = [Index("parentId"), Index("kind")])
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: CategoryKind,
    val parentId: String?,
    val iconKey: String,
    val color: Long,
    val sortOrder: Int,
    val isArchived: Boolean,
    val isSystem: Boolean,
)

/**
 * Одна строка = одна операция. Перевод — тоже одна строка с обоими счетами,
 * поэтому «половина перевода» физически невозможна.
 * Внешние ключи с RESTRICT не дают удалить счёт или категорию, на которые
 * ссылаются операции (вместо удаления — архивирование).
 */
@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["toAccountId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("accountId"),
        Index("toAccountId"),
        Index("categoryId"),
        Index("dateTime"),
    ],
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val type: TransactionType,
    val accountId: String,
    val toAccountId: String?,
    val amountMinor: Long,
    val toAmountMinor: Long?,
    val feeMinor: Long,
    val categoryId: String?,
    val dateTime: Long,
    val payee: String,
    val comment: String,
    val isFavorite: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    /** Сумма и валюта покупки, если она не в валюте счёта (справочно). Добавлено в версии 8. */
    val originalAmountMinor: Long? = null,
    val originalCurrency: String? = null,
)

/**
 * Лимит бюджета (версия). Месяцы хранятся числом yyyymm: 202611.
 * Удаление категории удаляет и её лимиты (CASCADE); категории с операциями
 * не удаляются, а архивируются, поэтому история бюджетов не теряется.
 */
@Entity(
    tableName = "budget_limits",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("categoryId")],
)
data class BudgetLimitEntity(
    @PrimaryKey val id: String,
    val categoryId: String?,
    val currencyCode: String,
    val amountMinor: Long,
    val rollover: Boolean,
    val startMonth: Int,
    val endMonth: Int?,
    val createdAt: Long,
)

/**
 * Вклад на накопительном счёте (один на счёт). Удаляется вместе со счётом.
 * startDay — дата открытия в днях эпохи (LocalDate.toEpochDay).
 */
@Entity(
    tableName = "deposits",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DepositEntity(
    @PrimaryKey val accountId: String,
    val rateBp: Long,
    val startDay: Long,
    val termMonths: Int,
    val interestCategoryId: String?,
    val postedMonths: Int,
    val createdAt: Long,
)

/**
 * Цель копилки (один к одному со счётом типа PIGGY). Удаляется вместе со счётом.
 * targetMinor = 0 — копилка без цели; deadlineDay — срок в днях эпохи.
 */
@Entity(
    tableName = "piggy_banks",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PiggyEntity(
    @PrimaryKey val accountId: String,
    val targetMinor: Long,
    val iconKey: String,
    val deadlineDay: Long?,
    val createdAt: Long,
)

/**
 * Условия кредита (один к одному со счётом типа LOAN). Удаляется вместе со счётом.
 * startDay — дата выдачи в днях эпохи; первый платёж — через месяц.
 * initialPaid — платежи, внесённые до начала учёта в приложении (без операций).
 */
@Entity(
    tableName = "loans",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class LoanEntity(
    @PrimaryKey val accountId: String,
    val kind: LoanKind,
    val schedule: LoanSchedule,
    val principalMinor: Long,
    val rateBp: Long,
    val startDay: Long,
    val termMonths: Int,
    val initialPaid: Int,
    val interestCategoryId: String?,
    val createdAt: Long,
)

/**
 * Шаблон регулярного платежа. Проведённые повторы — обычные операции с id «rec:<шаблон>:<день эпохи>».
 * Удаление счёта (возможно только без операций) удаляет и шаблон; категория — обнуляется.
 * Даты — дни эпохи; processedCount — сколько первых повторов проведено или пропущено.
 */
@Entity(
    tableName = "recurring",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["toAccountId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("accountId"), Index("toAccountId"), Index("categoryId")],
)
data class RecurringEntity(
    @PrimaryKey val id: String,
    val type: TransactionType,
    val accountId: String,
    val toAccountId: String?,
    val amountMinor: Long,
    val toAmountMinor: Long?,
    val feeMinor: Long,
    val categoryId: String?,
    val payee: String,
    val comment: String,
    val unit: RepeatUnit,
    val repeatInterval: Int,
    val startDay: Long,
    val lastDayOfMonth: Boolean,
    val endDay: Long?,
    val maxCount: Int?,
    val minuteOfDay: Int,
    val processedCount: Int,
    val mode: RecurringMode,
    val isPaused: Boolean,
    val createdAt: Long,
    /** Валюта платежа, если не валюта счёта; тогда amountMinor — в ней. Добавлено в версии 9. */
    val foreignCurrency: String? = null,
)

/** Операция по кредиту (id с префиксом «loan:<счёт>:»): платежи и выдача. */
data class LoanTxRow(
    val id: String,
    val type: TransactionType,
    val accountId: String,
    val amountMinor: Long,
)

/** Пополнения копилки: сколько раз, сумма последнего. */
data class PiggyTopUpRow(
    val accountId: String,
    val topUps: Int,
    val lastAmountMinor: Long?,
)

/** Строка для статистики: операция + валюты счетов + корневая категория. */
data class StatRow(
    val type: TransactionType,
    val accountId: String,
    val toAccountId: String?,
    val amountMinor: Long,
    val toAmountMinor: Long?,
    val feeMinor: Long,
    val dateTime: Long,
    val currency: String,
    val toCurrency: String?,
    val categoryId: String?,
    val rootCategoryId: String?,
)

data class CurrencyBalanceRow(
    val currencyCode: String,
    val balanceMinor: Long,
)

data class AccountBalanceRow(
    @Embedded val account: AccountEntity,
    val balanceMinor: Long,
)

data class CurrencyTotalsRow(
    val currencyCode: String,
    val incomeMinor: Long,
    val expenseMinor: Long,
)

data class TransactionDetailsRow(
    @Embedded val tx: TransactionEntity,
    val accountName: String,
    val accountCurrency: String,
    val toAccountName: String?,
    val toAccountCurrency: String?,
    val categoryName: String?,
    val categoryIcon: String?,
    val categoryColor: Long?,
    val parentCategoryName: String?,
)

/** Перечисления хранятся текстом (имя константы) — устойчиво к перестановке. */
class Converters {
    @TypeConverter fun fromTxType(v: TransactionType): String = v.name
    @TypeConverter fun toTxType(v: String): TransactionType = TransactionType.valueOf(v)
    @TypeConverter fun fromCategoryKind(v: CategoryKind): String = v.name
    @TypeConverter fun toCategoryKind(v: String): CategoryKind = CategoryKind.valueOf(v)
    @TypeConverter fun fromAccountType(v: AccountType): String = v.name
    @TypeConverter fun toAccountType(v: String): AccountType = AccountType.valueOf(v)
    @TypeConverter fun fromLoanKind(v: LoanKind): String = v.name
    @TypeConverter fun toLoanKind(v: String): LoanKind = LoanKind.valueOf(v)
    @TypeConverter fun fromRepeatUnit(v: RepeatUnit): String = v.name
    @TypeConverter fun toRepeatUnit(v: String): RepeatUnit = RepeatUnit.valueOf(v)
    @TypeConverter fun fromRecurringMode(v: RecurringMode): String = v.name
    @TypeConverter fun toRecurringMode(v: String): RecurringMode = RecurringMode.valueOf(v)
    @TypeConverter fun fromLoanSchedule(v: LoanSchedule): String = v.name
    @TypeConverter fun toLoanSchedule(v: String): LoanSchedule = LoanSchedule.valueOf(v)
}
