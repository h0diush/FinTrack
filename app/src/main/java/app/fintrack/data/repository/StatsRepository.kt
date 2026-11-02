package app.fintrack.data.repository

import app.fintrack.data.db.FinTrackDatabase
import app.fintrack.data.db.StatRow
import app.fintrack.domain.logic.StatEntry
import app.fintrack.domain.logic.TimeRange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Источник данных для статистики, бюджетов и сводок. Обновляется при любом изменении операций. */
@Singleton
class StatsRepository @Inject constructor(
    db: FinTrackDatabase,
) {
    private val dao = db.transactionDao()

    fun observeEntries(range: TimeRange): Flow<List<StatEntry>> =
        dao.observeStatEntries(range.startMillis, range.endMillis).map { rows -> rows.map { it.toDomain() } }

    /** Суммарный баланс по валютам на момент [beforeMillis]. */
    fun observeBalancesBefore(beforeMillis: Long): Flow<Map<String, Long>> =
        dao.observeBalancesBefore(beforeMillis).map { rows -> rows.associate { it.currencyCode to it.balanceMinor } }

    /** Дата самой ранней операции — начало периода «Всё время». */
    fun observeEarliestDate(): Flow<Long?> = dao.observeEarliestDate()

    private fun StatRow.toDomain() = StatEntry(
        type = type,
        accountId = accountId,
        toAccountId = toAccountId,
        amountMinor = amountMinor,
        toAmountMinor = toAmountMinor,
        feeMinor = feeMinor,
        dateTime = dateTime,
        currency = currency,
        toCurrency = toCurrency,
        categoryId = categoryId,
        rootCategoryId = rootCategoryId,
    )
}
