package app.fintrack.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.fintrack.data.db.FinTrackDatabase
import app.fintrack.data.db.Migrations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Миграция 1 → 2 сохраняет счета и операции, а итоговая схема совпадает с той,
 * что ожидает Room. Схемы берутся из app/schemas (создаются при сборке).
 * Запуск: `./gradlew connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        FinTrackDatabase::class.java,
    )

    @Test
    fun migrate1To2KeepsData() {
        helper.createDatabase(dbName, 1).apply {
            execSQL(
                "INSERT INTO accounts (id, name, type, currencyCode, initialBalanceMinor, color, sortOrder, isArchived, createdAt) " +
                    "VALUES ('a', 'Наличные', 'CASH', 'BYN', 10000, 0, 0, 0, 0)",
            )
            execSQL(
                "INSERT INTO categories (id, name, kind, parentId, iconKey, color, sortOrder, isArchived, isSystem) " +
                    "VALUES ('c', 'Продукты', 'EXPENSE', NULL, 'groceries', 0, 0, 0, 0)",
            )
            execSQL(
                "INSERT INTO transactions (id, type, accountId, toAccountId, amountMinor, toAmountMinor, feeMinor, categoryId, " +
                    "dateTime, payee, comment, isFavorite, createdAt, updatedAt) " +
                    "VALUES ('t', 'EXPENSE', 'a', NULL, 2500, NULL, 0, 'c', 1, '', '', 0, 1, 1)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 2, true, *Migrations.ALL)

        db.query("SELECT COUNT(*) FROM transactions").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
        db.execSQL("INSERT INTO budget_limits VALUES ('b', 'c', 'BYN', 50000, 1, 202611, NULL, 0)")
        db.query("SELECT amountMinor FROM budget_limits WHERE id = 'b'").use { c ->
            c.moveToFirst()
            assertEquals(50000L, c.getLong(0))
        }
        db.close()
    }

    @Test
    fun migrate2To3AddsDeposits() {
        helper.createDatabase(dbName, 2).apply {
            execSQL(
                "INSERT INTO accounts (id, name, type, currencyCode, initialBalanceMinor, color, sortOrder, isArchived, createdAt) " +
                    "VALUES ('s', 'Вклад', 'SAVINGS', 'BYN', 100000, 0, 0, 0, 0)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 3, true, *Migrations.ALL)
        db.execSQL("INSERT INTO deposits VALUES ('s', 1200, 20000, 12, NULL, 0, 0)")
        db.query("SELECT rateBp FROM deposits WHERE accountId = 's'").use { c ->
            c.moveToFirst()
            assertEquals(1200L, c.getLong(0))
        }
        db.close()
    }

    @Test
    fun migrate3To4AddsPiggyBanks() {
        helper.createDatabase(dbName, 3).apply {
            execSQL(
                "INSERT INTO accounts (id, name, type, currencyCode, initialBalanceMinor, color, sortOrder, isArchived, createdAt) " +
                    "VALUES ('c', 'Карта', 'CARD', 'BYN', 100000, 0, 0, 0, 0)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 4, true, *Migrations.ALL)
        db.execSQL(
            "INSERT INTO accounts (id, name, type, currencyCode, initialBalanceMinor, color, sortOrder, isArchived, createdAt) " +
                "VALUES ('p', 'Отпуск', 'PIGGY', 'BYN', 0, 0, 1, 0, 0)",
        )
        db.execSQL("INSERT INTO piggy_banks VALUES ('p', 500000, 'beach', NULL, 0)")
        db.query("SELECT COUNT(*) FROM accounts").use { c ->
            c.moveToFirst()
            assertEquals(2, c.getInt(0))
        }
        db.close()
    }

    @Test
    fun migrate4To5KeepsAccountsInTotal() {
        helper.createDatabase(dbName, 4).apply {
            execSQL(
                "INSERT INTO accounts (id, name, type, currencyCode, initialBalanceMinor, color, sortOrder, isArchived, createdAt) " +
                    "VALUES ('c', 'Карта', 'CARD', 'BYN', 100000, 0, 0, 0, 0)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 5, true, *Migrations.ALL)
        db.query("SELECT excludeFromTotal FROM accounts WHERE id = 'c'").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
        db.close()
    }

    @Test
    fun migrate5To6AddsLoans() {
        helper.createDatabase(dbName, 5).apply {
            execSQL(
                "INSERT INTO accounts (id, name, type, currencyCode, initialBalanceMinor, color, sortOrder, isArchived, createdAt, excludeFromTotal) " +
                    "VALUES ('c', 'Карта', 'CARD', 'BYN', 100000, 0, 0, 0, 0, 1)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 6, true, *Migrations.ALL)
        db.execSQL(
            "INSERT INTO accounts (id, name, type, currencyCode, initialBalanceMinor, color, sortOrder, isArchived, createdAt, excludeFromTotal) " +
                "VALUES ('l', 'Ипотека', 'LOAN', 'BYN', -5000000, 0, 1, 0, 0, 1)",
        )
        db.execSQL("INSERT INTO loans VALUES ('l', 'MORTGAGE', 'ANNUITY', 5000000, 1200, 20000, 240, 0, NULL, 0)")
        db.query("SELECT excludeFromTotal FROM accounts WHERE id = 'c'").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
        db.execSQL("DELETE FROM accounts WHERE id = 'l'")
        db.query("SELECT COUNT(*) FROM loans").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
        db.close()
    }

    @Test
    fun migrate6To7AddsRecurring() {
        helper.createDatabase(dbName, 6).apply {
            execSQL(
                "INSERT INTO accounts (id, name, type, currencyCode, initialBalanceMinor, color, sortOrder, isArchived, createdAt, excludeFromTotal) " +
                    "VALUES ('c', 'Карта', 'CARD', 'BYN', 100000, 0, 0, 0, 0, 0)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 7, true, *Migrations.ALL)
        db.execSQL(
            "INSERT INTO recurring VALUES ('r', 'EXPENSE', 'c', NULL, 2500, NULL, 0, NULL, 'Интернет', '', 'MONTH', 1, " +
                "20000, 0, NULL, NULL, 720, 0, 'CONFIRM', 0, 0)",
        )
        db.query("SELECT COUNT(*) FROM recurring").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
        db.execSQL("DELETE FROM accounts WHERE id = 'c'")
        db.query("SELECT COUNT(*) FROM recurring").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
        db.close()
    }

    @Test
    fun migrate7To8AddsOriginalAmount() {
        helper.createDatabase(dbName, 7).apply {
            execSQL(
                "INSERT INTO accounts (id, name, type, currencyCode, initialBalanceMinor, color, sortOrder, isArchived, createdAt, excludeFromTotal) " +
                    "VALUES ('c', 'Карта', 'CARD', 'BYN', 100000, 0, 0, 0, 0, 0)",
            )
            execSQL(
                "INSERT INTO transactions (id, type, accountId, toAccountId, amountMinor, toAmountMinor, feeMinor, categoryId, " +
                    "dateTime, payee, comment, isFavorite, createdAt, updatedAt) " +
                    "VALUES ('t', 'INCOME', 'c', NULL, 5000, NULL, 0, NULL, 1, '', '', 0, 1, 1)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 8, true, *Migrations.ALL)
        db.query("SELECT amountMinor, originalAmountMinor, originalCurrency FROM transactions WHERE id = 't'").use { c ->
            c.moveToFirst()
            assertEquals(5000, c.getInt(0))
            assertTrue(c.isNull(1))
            assertTrue(c.isNull(2))
        }
        db.close()
    }

    @Test
    fun migrate8To9AddsRecurringCurrency() {
        helper.createDatabase(dbName, 8).apply {
            execSQL(
                "INSERT INTO accounts (id, name, type, currencyCode, initialBalanceMinor, color, sortOrder, isArchived, createdAt, excludeFromTotal) " +
                    "VALUES ('c', 'Карта', 'CARD', 'BYN', 100000, 0, 0, 0, 0, 0)",
            )
            execSQL(
                "INSERT INTO recurring VALUES ('r', 'EXPENSE', 'c', NULL, 2500, NULL, 0, NULL, 'Интернет', '', 'MONTH', 1, " +
                    "20000, 0, NULL, NULL, 720, 0, 'CONFIRM', 0, 0)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 9, true, *Migrations.ALL)
        db.query("SELECT amountMinor, foreignCurrency FROM recurring WHERE id = 'r'").use { c ->
            c.moveToFirst()
            assertEquals(2500, c.getInt(0))
            assertTrue(c.isNull(1))
        }
        db.close()
    }
}
