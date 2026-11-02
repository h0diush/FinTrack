package app.fintrack.data.repository

import androidx.room.withTransaction
import app.fintrack.data.db.AccountEntity
import app.fintrack.data.db.FinTrackDatabase
import app.fintrack.data.db.PiggyEntity
import app.fintrack.data.db.TransactionEntity
import app.fintrack.domain.logic.PiggyBank
import app.fintrack.domain.logic.TransactionValidator
import app.fintrack.domain.model.AccountType
import app.fintrack.domain.model.TransactionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class PiggyMoveResult(val message: String?) {
    OK(null),
    AMOUNT_INVALID("Введите сумму больше нуля"),
    ACCOUNT_INVALID("Выберите счёт в той же валюте"),
    NOT_ENOUGH_IN_PIGGY("В копилке нет такой суммы"),
    NEGATIVE_BALANCE("Баланс копилки отрицательный — сначала пополните её до нуля"),
}

/**
 * Копилки. Каждая — счёт типа PIGGY плюс строка piggy_banks с целью.
 * Пополнение и снятие — обычные переводы, поэтому балансы, история и
 * статистика работают без особых случаев, а запись атомарна.
 */
@Singleton
class PiggyRepository @Inject constructor(
    private val db: FinTrackDatabase,
) {
    private val accountDao = db.accountDao()
    private val txDao = db.transactionDao()
    private val dao = db.piggyDao()

    fun observeAll(): Flow<List<PiggyBank>> = combine(
        accountDao.observeWithBalances(),
        dao.observeAll(),
        dao.observeTopUps(),
    ) { accounts, goals, topUps ->
        val goalById = goals.associateBy { it.accountId }
        val topById = topUps.associateBy { it.accountId }
        accounts
            .filter { it.account.type == AccountType.PIGGY && !it.account.isArchived }
            .map { row ->
                val a = row.account
                val g = goalById[a.id]
                val t = topById[a.id]
                PiggyBank(
                    accountId = a.id,
                    name = a.name,
                    currency = a.currencyCode,
                    color = a.color,
                    iconKey = g?.iconKey ?: "savings",
                    targetMinor = g?.targetMinor ?: 0L,
                    deadline = g?.deadlineDay?.let(LocalDate::ofEpochDay),
                    balanceMinor = row.balanceMinor,
                    topUps = t?.topUps ?: 0,
                    lastTopUpMinor = t?.lastAmountMinor,
                    createdAt = a.createdAt,
                    excludeFromTotal = a.excludeFromTotal,
                )
            }
    }

    fun observe(id: String): Flow<PiggyBank?> = observeAll().map { list -> list.firstOrNull { it.accountId == id } }

    suspend fun create(
        name: String,
        currency: String,
        targetMinor: Long,
        iconKey: String,
        color: Long,
        deadline: LocalDate?,
        excludeFromTotal: Boolean = false,
    ): String = db.withTransaction {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        accountDao.insert(
            AccountEntity(
                id = id,
                name = name.trim(),
                type = AccountType.PIGGY,
                currencyCode = currency,
                initialBalanceMinor = 0L,
                color = color,
                sortOrder = accountDao.maxSortOrder() + 1,
                isArchived = false,
                createdAt = now,
                excludeFromTotal = excludeFromTotal,
            ),
        )
        dao.upsert(PiggyEntity(id, targetMinor.coerceAtLeast(0), iconKey, deadline?.toEpochDay(), now))
        id
    }

    /** Валюту копилки менять нельзя, если в ней уже есть движения. */
    suspend fun update(
        id: String,
        name: String,
        currency: String,
        targetMinor: Long,
        iconKey: String,
        color: Long,
        deadline: LocalDate?,
        excludeFromTotal: Boolean = false,
    ) = db.withTransaction {
        val acc = accountDao.getById(id) ?: return@withTransaction
        val canChangeCurrency = txDao.countForAccount(id) == 0
        accountDao.update(
            acc.copy(
                name = name.trim(),
                color = color,
                excludeFromTotal = excludeFromTotal,
                currencyCode = if (canChangeCurrency) currency else acc.currencyCode,
            ),
        )
        val createdAt = dao.get(id)?.createdAt ?: System.currentTimeMillis()
        dao.upsert(PiggyEntity(id, targetMinor.coerceAtLeast(0), iconKey, deadline?.toEpochDay(), createdAt))
    }

    suspend fun hasTransactions(id: String): Boolean = txDao.countForAccount(id) > 0

    /** Пополнить копилку переводом со счёта [fromAccountId]. */
    suspend fun topUp(piggyId: String, fromAccountId: String, amountMinor: Long, comment: String = ""): PiggyMoveResult =
        transfer(from = fromAccountId, to = piggyId, piggyId = piggyId, amountMinor = amountMinor, comment = comment, fromPiggy = false)

    /** Забрать деньги из копилки на счёт [toAccountId]. Не больше, чем в копилке. */
    suspend fun withdraw(piggyId: String, toAccountId: String, amountMinor: Long, comment: String = ""): PiggyMoveResult =
        transfer(from = piggyId, to = toAccountId, piggyId = piggyId, amountMinor = amountMinor, comment = comment, fromPiggy = true)

    private suspend fun transfer(
        from: String,
        to: String,
        piggyId: String,
        amountMinor: Long,
        comment: String,
        fromPiggy: Boolean,
    ): PiggyMoveResult = db.withTransaction {
        if (amountMinor <= 0 || amountMinor > TransactionValidator.MAX_AMOUNT_MINOR) return@withTransaction PiggyMoveResult.AMOUNT_INVALID
        val piggy = accountDao.getById(piggyId)
        val other = accountDao.getById(if (fromPiggy) to else from)
        if (piggy == null || other == null || other.id == piggy.id || other.isArchived ||
            other.type == AccountType.PIGGY || other.currencyCode != piggy.currencyCode
        ) {
            return@withTransaction PiggyMoveResult.ACCOUNT_INVALID
        }
        // Проверка остатка и запись — в одной транзакции БД, двойное снятие невозможно.
        if (fromPiggy && (accountDao.balanceOf(piggyId) ?: 0L) < amountMinor) {
            return@withTransaction PiggyMoveResult.NOT_ENOUGH_IN_PIGGY
        }
        val now = System.currentTimeMillis()
        txDao.insert(
            TransactionEntity(
                id = UUID.randomUUID().toString(),
                type = TransactionType.TRANSFER,
                accountId = from,
                toAccountId = to,
                amountMinor = amountMinor,
                toAmountMinor = amountMinor,
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
        PiggyMoveResult.OK
    }

    /**
     * «Разбить копилку»: всё содержимое переводится на [toAccountId], затем копилка
     * удаляется (если операций не было) или уходит в архив — история сохраняется.
     */
    suspend fun breakPiggy(piggyId: String, toAccountId: String?): PiggyMoveResult = db.withTransaction {
        val balance = accountDao.balanceOf(piggyId) ?: 0L
        if (balance < 0) return@withTransaction PiggyMoveResult.NEGATIVE_BALANCE
        if (balance > 0) {
            val r = withdraw(piggyId, toAccountId ?: return@withTransaction PiggyMoveResult.ACCOUNT_INVALID, balance, "")
            if (r != PiggyMoveResult.OK) return@withTransaction r
        }
        val acc = accountDao.getById(piggyId) ?: return@withTransaction PiggyMoveResult.OK
        if (txDao.countForAccount(piggyId) == 0) {
            accountDao.deleteById(piggyId)
        } else {
            accountDao.update(acc.copy(isArchived = true))
        }
        PiggyMoveResult.OK
    }
}
