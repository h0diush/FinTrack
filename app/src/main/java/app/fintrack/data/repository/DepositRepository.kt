package app.fintrack.data.repository

import androidx.room.withTransaction
import app.fintrack.data.db.DepositEntity
import app.fintrack.data.db.FinTrackDatabase
import app.fintrack.data.db.TransactionEntity
import app.fintrack.domain.logic.Deposit
import app.fintrack.domain.logic.DepositCalculator
import app.fintrack.domain.model.CategoryKind
import app.fintrack.domain.model.TransactionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Вклады на накопительных счетах и ежемесячное начисление процентов.
 *
 * Начисление — обычная операция «доход» в категории «Проценты и инвестиции»
 * с детерминированным id «dep:<счёт>:<номер месяца>». Поэтому повторный запуск
 * не создаёт дублей, а пропущенные месяцы (телефон был выключен, приложение
 * не открывали) догоняются при следующем запуске.
 */
@Singleton
class DepositRepository @Inject constructor(
    private val db: FinTrackDatabase,
) {
    private val dao = db.depositDao()
    private val txDao = db.transactionDao()
    private val accountDao = db.accountDao()
    private val categoryDao = db.categoryDao()

    fun observeAll(): Flow<List<Deposit>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun get(accountId: String): Deposit? = dao.get(accountId)?.toDomain()

    /** Сумма уже начисленных процентов по вкладу. */
    fun observeInterestEarned(accountId: String): Flow<Long> = txDao.observeSumByIdPrefix(idPrefix(accountId))

    /**
     * Сохранить настройки вклада. Дату открытия можно менять, пока не было
     * ни одного начисления; ставка и срок применяются к будущим начислениям.
     */
    suspend fun save(accountId: String, rateBp: Long, startDate: LocalDate, termMonths: Int) = db.withTransaction {
        val existing = dao.get(accountId)
        val posted = existing?.postedMonths ?: 0
        dao.upsert(
            DepositEntity(
                accountId = accountId,
                rateBp = rateBp,
                startDay = if (posted > 0 && existing != null) existing.startDay else startDate.toEpochDay(),
                termMonths = termMonths.coerceAtLeast(posted.coerceAtLeast(1)),
                interestCategoryId = existing?.interestCategoryId ?: resolveInterestCategory(),
                postedMonths = posted,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            ),
        )
    }

    /** Отключить вклад. Уже начисленные проценты остаются в истории как доход. */
    suspend fun remove(accountId: String) = dao.delete(accountId)

    /**
     * Провести все наступившие начисления по всем вкладам. Проценты считаются
     * от фактического остатка счёта на начало дня начисления (с учётом пополнений,
     * снятий и прошлых начислений). Всё в одной транзакции БД.
     * @return сколько начислений проведено.
     */
    suspend fun postDueInterest(today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Int =
        db.withTransaction {
            var count = 0
            for (entity in dao.getAll()) {
                val deposit = entity.toDomain()
                val account = accountDao.getById(deposit.accountId) ?: continue
                if (account.isArchived) continue
                val due = DepositCalculator.dueIndices(deposit, today)
                if (due.isEmpty()) continue
                val categoryId = validCategory(deposit.interestCategoryId) ?: continue
                var posted = deposit.postedMonths
                for (k in due) {
                    val id = idPrefix(deposit.accountId) + k
                    if (txDao.getById(id) == null) {
                        val date = DepositCalculator.postingDate(deposit.startDate, k)
                        val at = date.atStartOfDay(zone).toInstant().toEpochMilli()
                        val balance = txDao.balanceBefore(deposit.accountId, at) ?: 0L
                        val interest = DepositCalculator.monthlyInterest(balance, deposit.rateBp)
                        if (interest > 0) {
                            txDao.insert(
                                TransactionEntity(
                                    id = id,
                                    type = TransactionType.INCOME,
                                    accountId = deposit.accountId,
                                    toAccountId = null,
                                    amountMinor = interest,
                                    toAmountMinor = null,
                                    feeMinor = 0L,
                                    categoryId = categoryId,
                                    dateTime = at,
                                    payee = account.name,
                                    comment = "Проценты по вкладу, ${DepositCalculator.formatRate(deposit.rateBp)} % · месяц $k из ${deposit.termMonths}",
                                    isFavorite = false,
                                    createdAt = System.currentTimeMillis(),
                                    updatedAt = System.currentTimeMillis(),
                                ),
                            )
                            count++
                        }
                    }
                    posted = k
                }
                dao.setPosted(deposit.accountId, posted)
            }
            count
        }

    /** Категория процентов: «Проценты и инвестиции», иначе системная «Прочее» доходов. */
    private suspend fun resolveInterestCategory(): String? {
        val incomes = categoryDao.getAllOnce().filter { it.kind == CategoryKind.INCOME && it.parentId == null }
        return (
            incomes.firstOrNull { it.iconKey == "invest" && !it.isArchived }
                ?: incomes.firstOrNull { it.name.contains("Процент", ignoreCase = true) && !it.isArchived }
                ?: incomes.firstOrNull { it.isSystem }
                ?: incomes.firstOrNull()
            )?.id
    }

    private suspend fun validCategory(id: String?): String? =
        id?.let { categoryDao.getById(it) }?.id ?: resolveInterestCategory()

    private fun idPrefix(accountId: String) = "dep:$accountId:"

    private fun DepositEntity.toDomain() = Deposit(
        accountId = accountId,
        rateBp = rateBp,
        startDate = LocalDate.ofEpochDay(startDay),
        termMonths = termMonths,
        interestCategoryId = interestCategoryId,
        postedMonths = postedMonths,
    )
}
