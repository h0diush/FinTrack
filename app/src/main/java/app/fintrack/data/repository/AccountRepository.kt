package app.fintrack.data.repository

import androidx.room.withTransaction
import app.fintrack.data.db.FinTrackDatabase
import app.fintrack.data.db.TransactionEntity
import app.fintrack.data.toDomain
import app.fintrack.data.toEntity
import app.fintrack.domain.logic.BalanceCalculator
import app.fintrack.domain.model.Account
import app.fintrack.domain.model.AccountType
import app.fintrack.domain.model.AccountWithBalance
import app.fintrack.domain.model.TransactionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class AccountRemoval { DELETED, ARCHIVED, NONZERO_BALANCE }

@Singleton
class AccountRepository @Inject constructor(
    private val db: FinTrackDatabase,
) {
    private val dao = db.accountDao()
    private val txDao = db.transactionDao()

    fun observeAccounts(): Flow<List<AccountWithBalance>> =
        dao.observeWithBalances().map { rows ->
            rows.map { AccountWithBalance(it.account.toDomain(), it.balanceMinor) }
        }

    fun observeAccount(id: String): Flow<Account?> = dao.observeById(id).map { it?.toDomain() }

    suspend fun get(id: String): Account? = dao.getById(id)?.toDomain()

    suspend fun balanceOf(id: String): Long = dao.balanceOf(id) ?: 0L

    suspend fun hasTransactions(id: String): Boolean = txDao.countForAccount(id) > 0

    suspend fun create(
        name: String,
        type: AccountType,
        currencyCode: String,
        initialBalanceMinor: Long,
        color: Long,
        excludeFromTotal: Boolean = false,
    ): String = db.withTransaction {
        val id = UUID.randomUUID().toString()
        dao.insert(
            Account(
                id = id,
                name = name.trim(),
                type = type,
                currencyCode = currencyCode,
                initialBalanceMinor = initialBalanceMinor,
                color = color,
                sortOrder = dao.maxSortOrder() + 1,
                isArchived = false,
                createdAt = System.currentTimeMillis(),
                excludeFromTotal = excludeFromTotal,
            ).toEntity(),
        )
        id
    }

    /**
     * Обновляет название, тип, цвет, участие в общем балансе. Валюту — только если по счёту нет операций,
     * иначе исторические суммы стали бы неверными.
     */
    suspend fun update(account: Account) = db.withTransaction {
        val current = dao.getById(account.id) ?: return@withTransaction
        val currencyChanged = current.currencyCode != account.currencyCode
        check(!currencyChanged || txDao.countForAccount(account.id) == 0) {
            "Нельзя менять валюту счёта с операциями"
        }
        dao.update(
            current.copy(
                name = account.name.trim(),
                type = account.type,
                color = account.color,
                excludeFromTotal = account.excludeFromTotal,
                currencyCode = account.currencyCode,
                initialBalanceMinor = if (currencyChanged) account.initialBalanceMinor else current.initialBalanceMinor,
            ),
        )
    }

    /** Без операций — удаляет; с операциями — архивирует (только при нулевом балансе). */
    suspend fun removeOrArchive(id: String): AccountRemoval = db.withTransaction {
        val entity = dao.getById(id) ?: return@withTransaction AccountRemoval.DELETED
        if (txDao.countForAccount(id) == 0) {
            dao.deleteById(id)
            AccountRemoval.DELETED
        } else if ((dao.balanceOf(id) ?: 0L) != 0L) {
            AccountRemoval.NONZERO_BALANCE
        } else {
            dao.update(entity.copy(isArchived = true))
            AccountRemoval.ARCHIVED
        }
    }

    suspend fun restore(id: String) = db.withTransaction {
        val entity = dao.getById(id) ?: return@withTransaction
        dao.update(entity.copy(isArchived = false, sortOrder = dao.maxSortOrder() + 1))
    }

    /**
     * Сдвигает счёт вверх (direction = -1) или вниз (+1) среди активных.
     * Копилки и обычные счета упорядочиваются раздельно — они показываются в разных списках.
     */
    suspend fun move(id: String, direction: Int) = db.withTransaction {
        val all = dao.getWithBalances().map { it.account }.filter { !it.isArchived }
        val piggy = all.firstOrNull { it.id == id }?.type == AccountType.PIGGY
        val active = all.filter { (it.type == AccountType.PIGGY) == piggy }.toMutableList()
        val from = active.indexOfFirst { it.id == id }
        val to = from + direction
        if (from < 0 || to !in active.indices) return@withTransaction
        val item = active.removeAt(from)
        active.add(to, item)
        active.forEachIndexed { index, acc -> dao.setSortOrder(acc.id, index) }
    }

    /**
     * Корректировка: создаёт операцию ADJUSTMENT на разницу между фактическим
     * и расчётным балансом. История корректировок сохраняется в журнале.
     * Чтение баланса и запись выполняются в одной транзакции БД.
     */
    suspend fun adjustBalance(id: String, targetMinor: Long, comment: String): Long = db.withTransaction {
        val current = dao.balanceOf(id) ?: 0L
        val delta = BalanceCalculator.adjustmentFor(current, targetMinor)
        if (delta != 0L) {
            val now = System.currentTimeMillis()
            txDao.insert(
                TransactionEntity(
                    id = UUID.randomUUID().toString(),
                    type = TransactionType.ADJUSTMENT,
                    accountId = id,
                    toAccountId = null,
                    amountMinor = delta,
                    toAmountMinor = null,
                    feeMinor = 0L,
                    categoryId = null,
                    dateTime = now,
                    payee = "",
                    comment = comment.trim(),
                    isFavorite = false,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        delta
    }
}
