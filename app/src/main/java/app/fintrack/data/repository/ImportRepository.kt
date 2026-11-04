package app.fintrack.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import app.fintrack.data.db.FinTrackDatabase
import app.fintrack.data.db.TransactionEntity
import app.fintrack.data.toDomain
import app.fintrack.domain.logic.CategoryGuesser
import app.fintrack.domain.logic.CsvReader
import app.fintrack.domain.logic.ImportDedup
import app.fintrack.domain.logic.Money
import app.fintrack.domain.logic.StatementRow
import app.fintrack.domain.logic.TransactionValidator
import app.fintrack.domain.model.Category
import app.fintrack.domain.model.TransactionType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Что будет со строкой выписки. */
enum class ImportStatus {
    /** Новая операция. */
    NEW,

    /** Такая строка уже загружалась раньше — повторно не записывается. */
    ALREADY_IMPORTED,

    /** Похожая операция внесена вручную: тот же день, сумма и направление. */
    SIMILAR,

    /** Валюта строки не совпадает с валютой счёта, а курса нет. */
    NO_RATE,
}

data class ImportItem(
    val id: String,
    val row: StatementRow,
    val status: ImportStatus,
    /** Сумма в валюте счёта. */
    val amountMinor: Long,
    val category: Category?,
    /** Сумма в валюте файла, если она не валюта счёта. */
    val originalMinor: Long?,
    val originalCurrency: String?,
)

sealed interface ImportFile {
    data class Text(val name: String, val content: String) : ImportFile
    data class Failure(val message: String) : ImportFile
}

/** Загрузка операций из CSV: чтение файла, поиск дублей, подбор категорий, запись. */
@Singleton
class ImportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: FinTrackDatabase,
    private val rates: RatesRepository,
) {
    private val txDao = db.transactionDao()
    private val accountDao = db.accountDao()
    private val categoryDao = db.categoryDao()

    suspend fun read(uri: Uri): ImportFile = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "файл"
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val data = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    data.write(buffer, 0, n)
                    if (data.size() > MAX_BYTES) return@withContext ImportFile.Failure("Файл больше 20 МБ — разбейте выписку на части")
                }
                data.toByteArray()
            } ?: return@withContext ImportFile.Failure("Не удалось открыть файл")
            // XLSX и XLS — это ZIP и OLE, текстом их не прочитать
            val binary = bytes.size > 4 && (
                (bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) ||
                    (bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte())
                )
            if (binary) return@withContext ImportFile.Failure("Это файл Excel. Сохраните его как CSV: «Файл → Сохранить как → CSV»")
            ImportFile.Text(name, CsvReader.decode(bytes))
        } catch (e: IOException) {
            ImportFile.Failure("Не удалось прочитать файл")
        } catch (e: SecurityException) {
            ImportFile.Failure("Нет доступа к файлу")
        }
    }

    /** Определить судьбу каждой строки: дубли, категории, пересчёт в валюту счёта. */
    suspend fun prepare(accountId: String, rows: List<StatementRow>): List<ImportItem> = withContext(Dispatchers.Default) {
        val account = accountDao.getById(accountId) ?: return@withContext emptyList()
        val accCurrency = account.currencyCode
        val converter = rates.state.first().converter
        val zone = ZoneId.systemDefault()
        val categories = categoryDao.getAllOnce().map { it.toDomain() }
        val all = txDao.getAll()
        val known = all.mapTo(HashSet()) { it.id }

        // Как вы уже относили получателей: самая частая категория на каждое название
        val learned = all.asSequence()
            .filter { it.payee.isNotBlank() && it.categoryId != null }
            .groupBy { CategoryGuesser.normalize(it.payee) }
            .mapValues { (_, list) -> list.groupingBy { it.categoryId!! }.eachCount().maxBy { it.value }.key }

        // Внесённые вручную операции этого счёта: каждая «гасит» только одну строку выписки
        val manual = HashMap<String, Int>()
        all.filter { it.accountId == accountId && !it.id.startsWith("imp:") && (it.type == TransactionType.EXPENSE || it.type == TransactionType.INCOME) }
            .forEach { t ->
                val day = Instant.ofEpochMilli(t.dateTime).atZone(zone).toLocalDate()
                manual.merge(ImportDedup.similarKey(day, t.amountMinor, t.type == TransactionType.INCOME), 1, Int::plus)
            }

        val ids = ImportDedup.ids(accountId, rows)
        rows.mapIndexed { i, row ->
            val currency = row.currency?.takeIf { it != accCurrency }
            val original = currency?.let { Money.toMinor(row.amount, it) }
            val amount = if (currency != null) {
                original?.let { converter.convert(it, currency, accCurrency) }
            } else {
                Money.toMinor(row.amount, accCurrency)
            }
            val similarKey = amount?.let { ImportDedup.similarKey(row.dateTime.toLocalDate(), it, row.isIncome) }
            val status = when {
                ids[i] in known -> ImportStatus.ALREADY_IMPORTED
                amount == null -> ImportStatus.NO_RATE
                similarKey != null && (manual[similarKey] ?: 0) > 0 -> {
                    manual[similarKey] = manual.getValue(similarKey) - 1
                    ImportStatus.SIMILAR
                }
                else -> ImportStatus.NEW
            }
            ImportItem(
                id = ids[i],
                row = row,
                status = status,
                amountMinor = amount ?: 0L,
                category = CategoryGuesser.guess(row, categories, learned),
                originalMinor = original,
                originalCurrency = currency,
            )
        }
    }

    /** Записать операции одной транзакцией БД: при сбое не останется половины выписки. */
    suspend fun commit(accountId: String, items: List<ImportItem>): Int = db.withTransaction {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        var count = 0
        for (item in items) {
            val categoryId = item.category?.id ?: continue
            if (item.amountMinor <= 0 || item.amountMinor > TransactionValidator.MAX_AMOUNT_MINOR) continue
            if (txDao.getById(item.id) != null) continue
            val r = item.row
            txDao.insert(
                TransactionEntity(
                    id = item.id,
                    type = if (r.isIncome) TransactionType.INCOME else TransactionType.EXPENSE,
                    accountId = accountId,
                    toAccountId = null,
                    amountMinor = item.amountMinor,
                    toAmountMinor = null,
                    feeMinor = 0L,
                    categoryId = categoryId,
                    dateTime = r.dateTime.atZone(zone).toInstant().toEpochMilli(),
                    payee = r.description.take(100),
                    comment = r.comment.take(500),
                    isFavorite = false,
                    createdAt = now,
                    updatedAt = now,
                    originalAmountMinor = item.originalMinor,
                    originalCurrency = item.originalCurrency,
                ),
            )
            count++
        }
        count
    }

    private companion object {
        const val MAX_BYTES = 20 * 1024 * 1024
    }
}
