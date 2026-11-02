package app.fintrack.data.repository

import androidx.room.withTransaction
import app.fintrack.data.db.BudgetLimitEntity
import app.fintrack.data.db.FinTrackDatabase
import app.fintrack.domain.logic.BudgetCalculator
import app.fintrack.domain.logic.BudgetChanges
import app.fintrack.domain.logic.BudgetLimit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.YearMonth
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BudgetRepository @Inject constructor(
    private val db: FinTrackDatabase,
) {
    private val dao = db.budgetDao()

    fun observeLimits(): Flow<List<BudgetLimit>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    /**
     * Установить лимит с месяца [month]. Прошлые месяцы сохраняют прежние лимиты.
     * Чтение и запись — в одной транзакции БД.
     */
    suspend fun setLimit(categoryId: String?, currency: String, month: YearMonth, amountMinor: Long, rollover: Boolean) =
        db.withTransaction {
            val existing = dao.getAll().map { it.toDomain() }
            apply(BudgetCalculator.planSet(existing, categoryId, currency, month, amountMinor, rollover) { UUID.randomUUID().toString() })
        }

    /** Убрать лимит начиная с месяца [month]. */
    suspend fun removeLimit(categoryId: String?, currency: String, month: YearMonth) = db.withTransaction {
        val existing = dao.getAll().map { it.toDomain() }
        apply(BudgetCalculator.planRemove(existing, categoryId, currency, month))
    }

    private suspend fun apply(changes: BudgetChanges) {
        val now = System.currentTimeMillis()
        changes.deletes.forEach { dao.deleteById(it) }
        changes.updates.forEach { u ->
            val createdAt = dao.getAll().firstOrNull { it.id == u.id }?.createdAt ?: now
            dao.update(u.toEntity(createdAt))
        }
        changes.inserts.forEach { dao.insert(it.toEntity(now)) }
    }

    private fun BudgetLimitEntity.toDomain() = BudgetLimit(
        id = id,
        categoryId = categoryId,
        currency = currencyCode,
        amountMinor = amountMinor,
        rollover = rollover,
        startMonth = BudgetCalculator.fromKey(startMonth),
        endMonth = endMonth?.let { BudgetCalculator.fromKey(it) },
    )

    private fun BudgetLimit.toEntity(createdAt: Long) = BudgetLimitEntity(
        id = id,
        categoryId = categoryId,
        currencyCode = currency,
        amountMinor = amountMinor,
        rollover = rollover,
        startMonth = BudgetCalculator.toKey(startMonth),
        endMonth = endMonth?.let { BudgetCalculator.toKey(it) },
        createdAt = createdAt,
    )
}
