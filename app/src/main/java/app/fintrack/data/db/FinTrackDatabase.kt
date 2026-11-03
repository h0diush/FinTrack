package app.fintrack.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Версия 8: 1→2 — лимиты бюджета, 2→3 — вклады, 3→4 — копилки,
 * 4→5 — флаг «не учитывать в общем балансе» у счёта, 5→6 — кредиты,
 * 6→7 — регулярные платежи, 7→8 — исходная сумма операции в другой валюте (Migrations.kt).
 * При изменении схемы: увеличить version, добавить Migration в Migrations.kt
 * и тест миграции (схемы экспортируются в app/schemas).
 * fallbackToDestructiveMigration намеренно НЕ используется — данные не теряются.
 */
@Database(
    entities = [AccountEntity::class, CategoryEntity::class, TransactionEntity::class, BudgetLimitEntity::class, DepositEntity::class, PiggyEntity::class, LoanEntity::class, RecurringEntity::class],
    version = 8,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class FinTrackDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun transactionDao(): TransactionDao
    abstract fun budgetDao(): BudgetDao
    abstract fun depositDao(): DepositDao
    abstract fun piggyDao(): PiggyDao
    abstract fun loanDao(): LoanDao
    abstract fun recurringDao(): RecurringDao

    companion object {
        const val NAME = "fintrack.db"
    }
}
