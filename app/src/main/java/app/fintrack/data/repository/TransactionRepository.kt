package app.fintrack.data.repository

import androidx.room.withTransaction
import app.fintrack.data.db.FinTrackDatabase
import app.fintrack.data.toDomain
import app.fintrack.data.toEntity
import app.fintrack.domain.logic.TimeRange
import app.fintrack.domain.logic.ValidTransaction
import app.fintrack.domain.model.PeriodTotals
import app.fintrack.domain.model.Transaction
import app.fintrack.domain.model.TransactionDetails
import app.fintrack.domain.model.TransactionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransactionRepository @Inject constructor(
    private val db: FinTrackDatabase,
) {
    private val dao = db.transactionDao()

    fun observeRecent(limit: Int): Flow<List<TransactionDetails>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    fun observeDetails(id: String): Flow<TransactionDetails?> =
        dao.observeDetails(id).map { it?.toDomain() }

    fun observeFiltered(
        range: TimeRange?,
        type: TransactionType?,
        accountId: String?,
        categoryId: String?,
        favoritesOnly: Boolean,
    ): Flow<List<TransactionDetails>> =
        dao.observeFiltered(
            start = range?.startMillis,
            end = range?.endMillis,
            type = type?.name,
            accountId = accountId,
            categoryId = categoryId,
            favoritesOnly = favoritesOnly,
        ).map { rows -> rows.map { it.toDomain() } }

    /** Итоги за период: валюта → доход/расход. */
    fun observeTotals(range: TimeRange): Flow<Map<String, PeriodTotals>> =
        dao.observeTotals(range.startMillis, range.endMillis).map { rows ->
            rows.associate { it.currencyCode to PeriodTotals(it.incomeMinor, it.expenseMinor) }
        }

    suspend fun get(id: String): Transaction? = dao.getById(id)?.toDomain()

    /**
     * Создаёт новую операцию (id == null) или обновляет существующую.
     * Одна строка — одна запись, поэтому изменение атомарно; баланс
     * пересчитывается автоматически, так как выводится из журнала.
     */
    suspend fun save(
        id: String?,
        value: ValidTransaction,
        dateTime: Long,
        payee: String,
        comment: String,
        isFavorite: Boolean,
    ): String = db.withTransaction {
        val now = System.currentTimeMillis()
        val existing = id?.let { dao.getById(it) }
        val tx = Transaction(
            id = existing?.id ?: UUID.randomUUID().toString(),
            type = value.type,
            accountId = value.accountId,
            toAccountId = value.toAccountId,
            amountMinor = value.amountMinor,
            toAmountMinor = value.toAmountMinor,
            feeMinor = value.feeMinor,
            categoryId = value.categoryId,
            dateTime = dateTime,
            payee = payee.trim(),
            comment = comment.trim(),
            isFavorite = isFavorite,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        if (existing != null) dao.update(tx.toEntity()) else dao.insert(tx.toEntity())
        tx.id
    }

    /** Для корректировок меняются только дата и комментарий — сумма задаётся через счёт. */
    suspend fun updateMeta(id: String, dateTime: Long, comment: String) = db.withTransaction {
        val existing = dao.getById(id) ?: return@withTransaction
        dao.update(existing.copy(dateTime = dateTime, comment = comment.trim(), updatedAt = System.currentTimeMillis()))
    }

    suspend fun delete(id: String) = dao.deleteById(id)

    suspend fun setFavorite(id: String, favorite: Boolean) =
        dao.setFavorite(id, favorite, System.currentTimeMillis())

    /** «Повторить»: та же операция с текущими датой и временем. */
    suspend fun repeatNow(id: String): String? = db.withTransaction {
        val source = dao.getById(id) ?: return@withTransaction null
        if (source.type == TransactionType.ADJUSTMENT) return@withTransaction null
        val now = System.currentTimeMillis()
        val copy = source.copy(
            id = UUID.randomUUID().toString(),
            dateTime = now,
            isFavorite = false,
            createdAt = now,
            updatedAt = now,
        )
        dao.insert(copy)
        copy.id
    }
}
