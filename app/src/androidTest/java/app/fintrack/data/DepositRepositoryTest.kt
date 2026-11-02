package app.fintrack.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.fintrack.data.db.AccountEntity
import app.fintrack.data.db.CategoryEntity
import app.fintrack.data.db.FinTrackDatabase
import app.fintrack.data.repository.AccountRepository
import app.fintrack.data.repository.DepositRepository
import app.fintrack.domain.logic.DepositCalculator
import app.fintrack.domain.model.AccountType
import app.fintrack.domain.model.CategoryKind
import app.fintrack.domain.model.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/** Начисление процентов по вкладу на настоящей Room. */
@RunWith(AndroidJUnit4::class)
class DepositRepositoryTest {

    private lateinit var db: FinTrackDatabase
    private lateinit var deposits: DepositRepository
    private lateinit var accounts: AccountRepository
    private val zone = ZoneId.of("Europe/Minsk")
    private val start = LocalDate.of(2026, 1, 15)

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FinTrackDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        deposits = DepositRepository(db)
        accounts = AccountRepository(db)
        db.accountDao().insert(AccountEntity("s", "Вклад", AccountType.SAVINGS, "BYN", 100_000, 0, 0, false, 0))
        db.categoryDao().insert(CategoryEntity("inv", "Проценты и инвестиции", CategoryKind.INCOME, null, "invest", 0, 0, false, false))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun catchUpPostsCompoundInterestOnceAndMatchesProjection() = runTest {
        deposits.save("s", rateBp = 1_200, startDate = start, termMonths = 12)
        // Приложение не открывали полгода: начисляются 6 месяцев подряд
        assertEquals(6, deposits.postDueInterest(LocalDate.of(2026, 7, 20), zone))
        // Повторный запуск в тот же день ничего не добавляет
        assertEquals(0, deposits.postDueInterest(LocalDate.of(2026, 7, 20), zone))

        val projected = DepositCalculator.schedule(100_000, 1_200, start, 1, 6).last().balanceMinor
        assertEquals(projected, accounts.balanceOf("s"))
        assertEquals(projected - 100_000, deposits.observeInterestEarned("s").first())
        val incomes = db.transactionDao().getAll().filter { it.type == TransactionType.INCOME }
        assertEquals(6, incomes.size)
        assertEquals("inv", incomes.first().categoryId)

        // После окончания срока начислений больше нет
        assertEquals(6, deposits.postDueInterest(LocalDate.of(2030, 1, 1), zone))
        assertEquals(0, deposits.postDueInterest(LocalDate.of(2031, 1, 1), zone))
        assertEquals(12, deposits.get("s")!!.postedMonths)
    }

    @Test
    fun interestFollowsActualBalanceWithTopUps() = runTest {
        deposits.save("s", rateBp = 1_200, startDate = start, termMonths = 3)
        // Пополнение на 1 000 BYN до первого начисления
        accounts.adjustBalance("s", 200_000, "пополнение")
        db.transactionDao().getAll().single().let { t ->
            db.transactionDao().update(t.copy(dateTime = start.atStartOfDay(zone).toInstant().toEpochMilli()))
        }
        deposits.postDueInterest(start.plusMonths(1), zone)
        assertEquals(202_000L, accounts.balanceOf("s")) // 1 % от 2 000 BYN
    }
}
