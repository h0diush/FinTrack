package app.fintrack.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.fintrack.data.db.AccountEntity
import app.fintrack.data.db.FinTrackDatabase
import app.fintrack.data.repository.AccountRepository
import app.fintrack.data.repository.PiggyMoveResult
import app.fintrack.data.repository.PiggyRepository
import app.fintrack.domain.model.AccountType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Копилка на настоящей Room: пополнение, снятие, «разбить». */
@RunWith(AndroidJUnit4::class)
class PiggyRepositoryTest {

    private lateinit var db: FinTrackDatabase
    private lateinit var piggies: PiggyRepository
    private lateinit var accounts: AccountRepository

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FinTrackDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        piggies = PiggyRepository(db)
        accounts = AccountRepository(db)
        db.accountDao().insert(AccountEntity("card", "Карта", AccountType.CARD, "BYN", 100_000, 0, 0, false, 0))
        db.accountDao().insert(AccountEntity("usd", "Доллары", AccountType.CURRENCY, "USD", 100_000, 0, 1, false, 0))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun topUpMovesMoneyAndTotalStaysTheSame() = runTest {
        val id = piggies.create("Отпуск", "BYN", 50_000, "beach", 0, null)
        assertEquals(PiggyMoveResult.OK, piggies.topUp(id, "card", 10_000))
        assertEquals(PiggyMoveResult.OK, piggies.topUp(id, "card", 15_000))
        assertEquals(25_000L, accounts.balanceOf(id))
        assertEquals(75_000L, accounts.balanceOf("card"))
        val p = piggies.observe(id).first()!!
        assertEquals(2, p.topUps)
        assertEquals(15_000L, p.lastTopUpMinor)
        assertEquals(50_000L, p.targetMinor)
    }

    @Test
    fun rejectsOtherCurrencyAndOverdraftFromPiggy() = runTest {
        val id = piggies.create("Отпуск", "BYN", 0, "beach", 0, null)
        assertEquals(PiggyMoveResult.ACCOUNT_INVALID, piggies.topUp(id, "usd", 1_000))
        assertEquals(PiggyMoveResult.AMOUNT_INVALID, piggies.topUp(id, "card", 0))
        piggies.topUp(id, "card", 5_000)
        assertEquals(PiggyMoveResult.NOT_ENOUGH_IN_PIGGY, piggies.withdraw(id, "card", 5_001))
        assertEquals(PiggyMoveResult.OK, piggies.withdraw(id, "card", 5_000))
        assertEquals(0L, accounts.balanceOf(id))
    }

    @Test
    fun breakReturnsMoneyAndArchives() = runTest {
        val id = piggies.create("Телефон", "BYN", 100_000, "phone", 0, null)
        piggies.topUp(id, "card", 30_000)
        assertEquals(PiggyMoveResult.OK, piggies.breakPiggy(id, "card"))
        assertEquals(100_000L, accounts.balanceOf("card"))
        assertTrue(accounts.get(id)!!.isArchived)
        assertNull(piggies.observe(id).first())
        // Пустую копилку без операций просто удаляет
        val empty = piggies.create("Пустая", "BYN", 0, "savings", 0, null)
        assertEquals(PiggyMoveResult.OK, piggies.breakPiggy(empty, null))
        assertNull(accounts.get(empty))
    }
}
