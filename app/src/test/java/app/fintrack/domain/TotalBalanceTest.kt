package app.fintrack.domain

import app.fintrack.domain.logic.BalanceCalculator
import app.fintrack.domain.model.Account
import app.fintrack.domain.model.AccountType
import app.fintrack.domain.model.AccountWithBalance
import org.junit.Assert.assertEquals
import org.junit.Test

class TotalBalanceTest {

    private fun acc(id: String, cur: String, balance: Long, excluded: Boolean = false, archived: Boolean = false) =
        AccountWithBalance(Account(id, id, AccountType.CARD, cur, 0, 0, 0, archived, 0, excluded), balance)

    @Test
    fun excludedAndArchivedAccountsAreNotInTotal() {
        val total = BalanceCalculator.totalByCurrency(
            listOf(
                acc("card", "BYN", 100_000),
                acc("credit", "BYN", -50_000, excluded = true),
                acc("old", "BYN", 7_000, archived = true),
                acc("usd", "USD", 2_000),
                acc("friend", "USD", 9_000, excluded = true),
            ),
        )
        assertEquals(mapOf("BYN" to 100_000L, "USD" to 2_000L), total)
    }

    @Test
    fun currencyDisappearsWhenAllItsAccountsExcluded() {
        val total = BalanceCalculator.totalByCurrency(listOf(acc("a", "EUR", 500, excluded = true)))
        assertEquals(emptyMap<String, Long>(), total)
    }
}
