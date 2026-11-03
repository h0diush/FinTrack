package app.fintrack.data.repository

import androidx.room.withTransaction
import app.fintrack.data.db.FinTrackDatabase
import app.fintrack.data.db.RecurringEntity
import app.fintrack.data.db.TransactionEntity
import app.fintrack.domain.logic.RecurrenceCalculator
import app.fintrack.domain.logic.RecurringMode
import app.fintrack.domain.logic.RecurringPayment
import app.fintrack.domain.logic.RepeatRule
import app.fintrack.domain.logic.TransactionValidator
import app.fintrack.domain.logic.ValidTransaction
import app.fintrack.domain.model.AccountType
import app.fintrack.domain.model.TransactionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class RecurringPostResult(val message: String?) {
    OK(null),
    NOT_DUE("Срок этого платежа ещё не наступил"),
    ACCOUNT_INVALID("Счёт платежа в архиве или удалён — измените платёж"),
    CATEGORY_INVALID("Категория платежа удалена — выберите другую"),
    AMOUNT_INVALID("Введите сумму больше нуля"),
    NOT_FOUND("Платёж не найден"),
}

/** Проведённый автоматически повтор — для уведомления. */
data class PostedOccurrence(val recurringId: String, val title: String, val amountMinor: Long, val currency: String, val type: TransactionType)

/** Повтор, ждущий подтверждения (самый ранний у шаблона) — для уведомления. */
data class DueOccurrence(val recurringId: String, val title: String, val amountMinor: Long, val currency: String, val date: LocalDate, val count: Int)

/**
 * Регулярные платежи. Шаблон хранит данные операции и правило повторения; каждый
 * проведённый повтор — обычная операция с id «rec:<шаблон>:<день эпохи>», поэтому
 * балансы, история и статистика работают без особых случаев, а повторное проведение
 * той же даты невозможно. Повторы обрабатываются по порядку: processedCount — сколько
 * первых повторов уже проведено или пропущено.
 */
@Singleton
class RecurringRepository @Inject constructor(
    private val db: FinTrackDatabase,
) {
    private val dao = db.recurringDao()
    private val txDao = db.transactionDao()
    private val accountDao = db.accountDao()
    private val categoryDao = db.categoryDao()

    fun observeAll(): Flow<List<RecurringPayment>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun get(id: String): RecurringPayment? = dao.get(id)?.toDomain()

    /**
     * Новый регулярный платёж из формы операции. Если первая дата уже наступила,
     * первый повтор проводится сразу (пользователь вводит его сейчас), остальные
     * наступившие — по режиму шаблона.
     */
    suspend fun create(
        tx: ValidTransaction,
        payee: String,
        comment: String,
        rule: RepeatRule,
        minuteOfDay: Int,
        mode: RecurringMode,
        today: LocalDate = LocalDate.now(),
    ): String = db.withTransaction {
        val id = UUID.randomUUID().toString()
        dao.upsert(entity(id, tx, payee, comment, rule, minuteOfDay, mode, processed = 0, paused = false, createdAt = System.currentTimeMillis()))
        if (!rule.start.isAfter(today)) {
            val first = dao.get(id)!!.toDomain()
            if (post(first, 0, null) == RecurringPostResult.OK) dao.setProcessed(id, 1)
        }
        if (mode == RecurringMode.AUTO) processOne(dao.get(id)!!.toDomain(), today)
        id
    }

    /**
     * Изменить шаблон. Правило пересчитывается от новой даты следующего платежа:
     * [rule].start — следующий повтор, счётчик обнуляется. История не меняется.
     */
    suspend fun update(
        id: String,
        tx: ValidTransaction,
        payee: String,
        comment: String,
        rule: RepeatRule,
        minuteOfDay: Int,
        mode: RecurringMode,
    ) = db.withTransaction {
        val old = dao.get(id) ?: return@withTransaction
        dao.upsert(entity(id, tx, payee, comment, rule, minuteOfDay, mode, processed = 0, paused = old.isPaused, createdAt = old.createdAt))
    }

    suspend fun setPaused(id: String, paused: Boolean) = dao.setPaused(id, paused)

    /** Удалить шаблон. Уже проведённые операции остаются в истории. */
    suspend fun delete(id: String) = dao.delete(id)

    /** Провести самый ранний наступивший повтор, при желании с другой суммой. */
    suspend fun confirmNext(id: String, amountMinor: Long? = null, today: LocalDate = LocalDate.now()): RecurringPostResult =
        db.withTransaction {
            val r = dao.get(id)?.toDomain() ?: return@withTransaction RecurringPostResult.NOT_FOUND
            val date = r.nextDate ?: return@withTransaction RecurringPostResult.NOT_DUE
            if (date.isAfter(today)) return@withTransaction RecurringPostResult.NOT_DUE
            val result = post(r, r.processedCount, amountMinor)
            if (result == RecurringPostResult.OK) dao.setProcessed(id, r.processedCount + 1)
            result
        }

    /** Пропустить самый ранний наступивший повтор (операция не создаётся). */
    suspend fun skipNext(id: String, today: LocalDate = LocalDate.now()) = db.withTransaction {
        val r = dao.get(id)?.toDomain() ?: return@withTransaction
        val date = r.nextDate ?: return@withTransaction
        if (!date.isAfter(today)) dao.setProcessed(id, r.processedCount + 1)
    }

    /**
     * Провести все наступившие повторы шаблонов с режимом AUTO (включая пропущенные дни,
     * когда приложение не открывали). Вызывается при запуске и ежедневной фоновой задачей.
     */
    suspend fun processDue(today: LocalDate = LocalDate.now()): List<PostedOccurrence> = db.withTransaction {
        dao.getAll().map { it.toDomain() }
            .filter { it.mode == RecurringMode.AUTO && !it.isPaused }
            .flatMap { processOne(it, today) }
    }

    /** Повторы, которые ждут решения пользователя (по одному на шаблон). */
    suspend fun dueForConfirmation(today: LocalDate = LocalDate.now()): List<DueOccurrence> =
        dao.getAll().map { it.toDomain() }
            .filter { !it.isPaused }
            .mapNotNull { r ->
                val count = r.dueCount(today)
                val date = r.nextDate
                if (count == 0 || date == null) null
                else DueOccurrence(r.id, title(r), r.amountMinor, currency(r), date, count)
            }

    private suspend fun processOne(r: RecurringPayment, today: LocalDate): List<PostedOccurrence> {
        val posted = ArrayList<PostedOccurrence>()
        var processed = r.processedCount
        for (k in RecurrenceCalculator.dueIndices(r.rule, r.processedCount, today)) {
            // Счёт в архиве или категория удалена — остаётся ждать подтверждения на главной.
            if (post(r, k, null) != RecurringPostResult.OK) break
            processed = k + 1
            posted += PostedOccurrence(r.id, title(r), r.amountMinor, currency(r), r.type)
        }
        if (processed != r.processedCount) dao.setProcessed(r.id, processed)
        return posted
    }

    /** Записать повтор k как операцию. Повтор той же даты не дублируется. */
    private suspend fun post(r: RecurringPayment, k: Int, amountOverride: Long?): RecurringPostResult {
        val date = RecurrenceCalculator.dateOf(r.rule, k) ?: return RecurringPostResult.NOT_DUE
        val id = "rec:${r.id}:${date.toEpochDay()}"
        if (txDao.getById(id) != null) return RecurringPostResult.OK
        val account = accountDao.getById(r.accountId)
        if (account == null || account.isArchived) return RecurringPostResult.ACCOUNT_INVALID
        val to = r.toAccountId?.let { accountDao.getById(it) }
        if (r.type == TransactionType.TRANSFER && (to == null || to.isArchived)) return RecurringPostResult.ACCOUNT_INVALID
        if (r.type != TransactionType.TRANSFER && r.categoryId?.let { categoryDao.getById(it) } == null) {
            return RecurringPostResult.CATEGORY_INVALID
        }
        val amount = amountOverride ?: r.amountMinor
        if (amount <= 0 || amount > TransactionValidator.MAX_AMOUNT_MINOR) return RecurringPostResult.AMOUNT_INVALID
        val sameCurrency = to == null || to.currencyCode == account.currencyCode
        val at = date.atStartOfDay(ZoneId.systemDefault()).plusMinutes(r.minuteOfDay.toLong()).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        txDao.insert(
            TransactionEntity(
                id = id,
                type = r.type,
                accountId = r.accountId,
                toAccountId = if (r.type == TransactionType.TRANSFER) r.toAccountId else null,
                amountMinor = amount,
                toAmountMinor = when {
                    r.type != TransactionType.TRANSFER -> null
                    sameCurrency -> amount
                    else -> r.toAmountMinor ?: amount
                },
                feeMinor = if (r.type == TransactionType.TRANSFER) r.feeMinor else 0L,
                categoryId = if (r.type == TransactionType.TRANSFER) null else r.categoryId,
                dateTime = at,
                payee = r.payee,
                comment = r.comment,
                isFavorite = false,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return RecurringPostResult.OK
    }

    /** Заголовок для уведомлений: получатель, иначе категория или «Перевод на …». */
    private suspend fun title(r: RecurringPayment): String {
        if (r.payee.isNotBlank()) return r.payee
        if (r.type == TransactionType.TRANSFER) {
            val to = r.toAccountId?.let { accountDao.getById(it) }
            return if (to?.type == AccountType.PIGGY) "Копилка «${to.name}»" else "Перевод на «${to?.name ?: "счёт"}»"
        }
        return r.categoryId?.let { categoryDao.getById(it) }?.name ?: "Регулярный платёж"
    }

    private suspend fun currency(r: RecurringPayment): String = accountDao.getById(r.accountId)?.currencyCode ?: ""

    private fun entity(
        id: String,
        tx: ValidTransaction,
        payee: String,
        comment: String,
        rule: RepeatRule,
        minuteOfDay: Int,
        mode: RecurringMode,
        processed: Int,
        paused: Boolean,
        createdAt: Long,
    ) = RecurringEntity(
        id = id,
        type = tx.type,
        accountId = tx.accountId,
        toAccountId = tx.toAccountId,
        amountMinor = tx.amountMinor,
        toAmountMinor = tx.toAmountMinor,
        feeMinor = tx.feeMinor,
        categoryId = tx.categoryId,
        payee = payee.trim(),
        comment = comment.trim(),
        unit = rule.unit,
        repeatInterval = rule.interval.coerceIn(1, RecurrenceCalculator.MAX_INTERVAL),
        startDay = rule.start.toEpochDay(),
        lastDayOfMonth = rule.lastDayOfMonth,
        endDay = rule.endDate?.toEpochDay(),
        maxCount = rule.maxCount,
        minuteOfDay = minuteOfDay.coerceIn(0, 24 * 60 - 1),
        processedCount = processed,
        mode = mode,
        isPaused = paused,
        createdAt = createdAt,
    )

    private fun RecurringEntity.toDomain() = RecurringPayment(
        id = id,
        type = type,
        accountId = accountId,
        toAccountId = toAccountId,
        amountMinor = amountMinor,
        toAmountMinor = toAmountMinor,
        feeMinor = feeMinor,
        categoryId = categoryId,
        payee = payee,
        comment = comment,
        rule = RepeatRule(
            unit = unit,
            interval = repeatInterval,
            start = LocalDate.ofEpochDay(startDay),
            lastDayOfMonth = lastDayOfMonth,
            endDate = endDay?.let(LocalDate::ofEpochDay),
            maxCount = maxCount,
        ),
        minuteOfDay = minuteOfDay,
        processedCount = processedCount,
        mode = mode,
        isPaused = isPaused,
    )
}
