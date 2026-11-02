package app.fintrack.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.fintrack.data.db.AccountEntity
import app.fintrack.data.db.CategoryEntity
import app.fintrack.data.db.FinTrackDatabase
import app.fintrack.data.db.TransactionEntity
import app.fintrack.data.repository.AccountRemoval
import app.fintrack.data.repository.AccountRepository
import app.fintrack.data.repository.CategoryRemoval
import app.fintrack.data.repository.CategoryRepository
import app.fintrack.data.repository.TransactionRepository
import app.fintrack.domain.logic.BalanceCalculator
import app.fintrack.domain.logic.LedgerEntry
import app.fintrack.domain.logic.TimeRange
import app.fintrack.domain.logic.ValidTransaction
import app.fintrack.domain.model.AccountType
import app.fintrack.domain.model.CategoryKind
import app.fintrack.domain.model.PeriodTotals
import app.fintrack.domain.model.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Интеграционные тесты финансовой логики на настоящей Room/SQLite устройства.
 * Запуск: эмулятор или телефон, `./gradlew connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class LedgerDaoTest {

    private lateinit var db: FinTrackDatabase
    private lateinit var accounts: AccountRepository
    private lateinit var categories: CategoryRepository
    private lateinit var transactions: TransactionRepository

    private val currency = mapOf("cash" to "BYN", "card" to "BYN", "usd" to "USD")
    private val initial = mapOf("cash" to 10_000L, "card" to 250_000L, "usd" to 50_000L)

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FinTrackDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        accounts = AccountRepository(db)
        categories = CategoryRepository(db)
        transactions = TransactionRepository(db)
        initial.entries.forEachIndexed { i, (id, init) ->
            db.accountDao().insert(AccountEntity(id, id, AccountType.CARD, currency.getValue(id), init, 0, i, false, i.toLong()))
        }
        db.categoryDao().insert(CategoryEntity("food", "Продукты", CategoryKind.EXPENSE, null, "groceries", 0, 0, false, false))
        db.categoryDao().insert(CategoryEntity("salary", "Зарплата", CategoryKind.INCOME, null, "salary", 0, 0, false, false))
        db.categoryDao().insert(CategoryEntity("other", "Прочее", CategoryKind.EXPENSE, null, "other", 0, 1, false, true))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun dbBalances() = db.accountDao().getWithBalances().associate { it.account.id to it.balanceMinor }

    private suspend fun ledger() = db.transactionDao().getAll().map {
        LedgerEntry(it.type, it.accountId, it.toAccountId, it.amountMinor, it.toAmountMinor, it.feeMinor)
    }

    private suspend fun save(v: ValidTransaction, at: Long = 1_000L, id: String? = null) =
        transactions.save(id, v, at, "", "", false)

    private fun expense(acc: String, a: Long) = ValidTransaction(TransactionType.EXPENSE, acc, null, a, null, 0, "food")
    private fun income(acc: String, a: Long) = ValidTransaction(TransactionType.INCOME, acc, null, a, null, 0, "salary")
    private fun transfer(from: String, to: String, a: Long, toA: Long = a, fee: Long = 0) =
        ValidTransaction(TransactionType.TRANSFER, from, to, a, toA, fee, null)

    @Test
    fun incomeExpenseAndBalance() = runTest {
        save(income("cash", 5_000))
        save(expense("cash", 1_250))
        assertEquals(13_750L, accounts.balanceOf("cash"))
    }

    @Test
    fun transferKeepsTotalAndIsNotIncomeOrExpense() = runTest {
        val before = dbBalances().filterKeys { currency[it] == "BYN" }.values.sum()
        save(transfer("card", "cash", 20_000))
        val after = dbBalances()
        assertEquals(30_000L, after["cash"])
        assertEquals(230_000L, after["card"])
        assertEquals(before, after.filterKeys { currency[it] == "BYN" }.values.sum())
        val totals = transactions.observeTotals(TimeRange(0, Long.MAX_VALUE)).first()
        assertEquals(PeriodTotals(0, 0), totals["BYN"])
    }

    @Test
    fun crossCurrencyTransferWithFee() = runTest {
        save(transfer("usd", "card", 10_000, toA = 32_500, fee = 100))
        assertEquals(39_900L, accounts.balanceOf("usd"))
        assertEquals(282_500L, accounts.balanceOf("card"))
        val totals = transactions.observeTotals(TimeRange(0, Long.MAX_VALUE)).first()
        assertEquals(PeriodTotals(0, 100), totals["USD"])
    }

    @Test
    fun editAndDeleteRecalculate() = runTest {
        val id = save(expense("cash", 3_000))
        assertEquals(7_000L, accounts.balanceOf("cash"))
        save(expense("card", 1_000), id = id) // перенос на другой счёт и смена суммы
        assertEquals(10_000L, accounts.balanceOf("cash"))
        assertEquals(249_000L, accounts.balanceOf("card"))
        transactions.delete(id)
        assertEquals(250_000L, accounts.balanceOf("card"))
        assertNull(transactions.get(id))
    }

    @Test
    fun repeatCreatesIndependentCopy() = runTest {
        val id = save(expense("cash", 500))
        val copy = transactions.repeatNow(id)!!
        assertEquals(9_000L, accounts.balanceOf("cash"))
        transactions.delete(copy)
        assertEquals(9_500L, accounts.balanceOf("cash"))
    }

    @Test
    fun adjustmentReachesTargetAndKeepsHistory() = runTest {
        save(expense("cash", 1_000))
        val delta = accounts.adjustBalance("cash", 12_345, "Пересчитал кошелёк")
        assertEquals(3_345L, delta)
        assertEquals(12_345L, accounts.balanceOf("cash"))
        assertEquals(0L, accounts.adjustBalance("cash", 12_345, ""))
        val adjustments = db.transactionDao().getAll().filter { it.type == TransactionType.ADJUSTMENT }
        assertEquals(1, adjustments.size)
        assertEquals("Пересчитал кошелёк", adjustments.single().comment)
    }

    @Test
    fun accountWithOperationsIsArchivedNotDeleted() = runTest {
        save(expense("cash", 10_000)) // баланс 0
        save(income("card", 1))
        assertEquals(AccountRemoval.ARCHIVED, accounts.removeOrArchive("cash"))
        assertEquals(AccountRemoval.NONZERO_BALANCE, accounts.removeOrArchive("card"))
        assertEquals(true, accounts.get("cash")!!.isArchived)
        try {
            db.accountDao().deleteById("card")
            fail("Внешний ключ должен запретить удаление")
        } catch (e: SQLiteConstraintException) {
            // ожидаемо
        }
    }

    @Test
    fun categoryRemovalRules() = runTest {
        save(expense("cash", 100))
        assertEquals(CategoryRemoval.ARCHIVED, categories.removeOrArchive("food"))
        assertEquals(CategoryRemoval.PROTECTED, categories.removeOrArchive("other"))
        val unused = categories.create("Хобби", CategoryKind.EXPENSE, null, "games", 0)
        assertEquals(CategoryRemoval.DELETED, categories.removeOrArchive(unused))
    }

    /** SQL-балансы совпадают с эталонным BalanceCalculator на большом журнале. */
    @Test
    fun sqlMatchesCalculatorOnLargeLedger() = runTest {
        val rnd = Random(2026)
        val ids = initial.keys.toList()
        db.withTransaction {
            repeat(20_000) { i ->
                val acc = ids[rnd.nextInt(ids.size)]
                val type = TransactionType.entries[rnd.nextInt(4)]
                var to: String? = null
                var toAmount: Long? = null
                var fee = 0L
                var amount = rnd.nextLong(1, 1_000_000)
                var cat: String? = null
                when (type) {
                    TransactionType.TRANSFER -> {
                        to = ids.filter { it != acc }[rnd.nextInt(2)]
                        toAmount = if (currency[acc] == currency[to]) amount else rnd.nextLong(1, 3_000_000)
                        fee = rnd.nextLong(0, 300)
                    }
                    TransactionType.ADJUSTMENT -> amount = rnd.nextLong(-50_000, 50_000)
                    TransactionType.INCOME -> cat = "salary"
                    TransactionType.EXPENSE -> cat = "food"
                }
                val e = TransactionEntity(
                    "t$i", type, acc, to, amount, toAmount, fee, cat,
                    rnd.nextLong(0, 1_000_000), "", "", false, i.toLong(), i.toLong(),
                )
                db.transactionDao().insert(e)
            }
        }
        val expected = BalanceCalculator.balances(initial, ledger())
        assertEquals(expected, dbBalances())

        val range = TimeRange(250_000, 750_000)
        val inRange = db.transactionDao().getAll().filter { it.dateTime >= range.startMillis && it.dateTime < range.endMillis }
            .map { LedgerEntry(it.type, it.accountId, it.toAccountId, it.amountMinor, it.toAmountMinor, it.feeMinor) }
        val expectedTotals = BalanceCalculator.totals(inRange) { currency.getValue(it) }
        assertEquals(expectedTotals, transactions.observeTotals(range).first())
    }
}
