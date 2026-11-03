package app.fintrack.data.repository

import androidx.room.withTransaction
import app.fintrack.data.db.AccountEntity
import app.fintrack.data.db.CategoryEntity
import app.fintrack.data.db.FinTrackDatabase
import app.fintrack.data.db.LoanEntity
import app.fintrack.data.db.LoanTxRow
import app.fintrack.data.db.TransactionEntity
import app.fintrack.domain.logic.Loan
import app.fintrack.domain.logic.LoanCalculator
import app.fintrack.domain.logic.LoanKind
import app.fintrack.domain.logic.LoanSchedule
import app.fintrack.domain.logic.TransactionValidator
import app.fintrack.domain.model.AccountType
import app.fintrack.domain.model.CategoryKind
import app.fintrack.domain.model.TransactionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class LoanPayResult(val message: String?) {
    OK(null),
    AMOUNT_INVALID("Введите сумму больше нуля"),
    TOO_MUCH("Сумма больше, чем нужно для полного погашения"),
    ACCOUNT_INVALID("Выберите счёт в валюте кредита"),
    PAID_OFF("Кредит уже погашен"),
}

/** Условия кредита из формы. */
data class LoanInput(
    val name: String,
    val kind: LoanKind,
    val schedule: LoanSchedule,
    val currency: String,
    val principalMinor: Long,
    val rateBp: Long,
    val startDate: LocalDate,
    val termMonths: Int,
    val initialPaid: Int,
    val color: Long,
    val excludeFromTotal: Boolean,
    /** Куда зачислить сумму кредита (наличными на карту); null — деньги ушли продавцу/банку. */
    val disburseToAccountId: String? = null,
)

/**
 * Кредиты, рассрочки, ипотека. Каждый — счёт типа LOAN плюс строка loans с условиями.
 *
 * Остаток счёта кредита отрицательный и равен долгу. Ежемесячный платёж — две операции
 * в одной транзакции БД: перевод «счёт → кредит» (погашение тела, не расход) и расход
 * в категории «Кредиты» (проценты). Id операций «loan:<кредит>:<номер платежа>:<p|i>»,
 * поэтому число внесённых платежей считается по журналу: удалили платёж в истории —
 * график сам вернётся назад. Досрочные платежи имеют номер «x…» и в счёт не идут.
 */
@Singleton
class LoanRepository @Inject constructor(
    private val db: FinTrackDatabase,
) {
    private val dao = db.loanDao()
    private val accountDao = db.accountDao()
    private val txDao = db.transactionDao()
    private val categoryDao = db.categoryDao()

    fun observeAll(includeArchived: Boolean = false): Flow<List<Loan>> = combine(
        accountDao.observeWithBalances(),
        dao.observeAll(),
        dao.observeLoanTransactions(),
    ) { accounts, terms, txs ->
        val termsById = terms.associateBy { it.accountId }
        val txByLoan = txs.groupBy { loanIdOf(it.id) }
        accounts
            .filter { it.account.type == AccountType.LOAN && (includeArchived || !it.account.isArchived) }
            .mapNotNull { row ->
                val t = termsById[row.account.id] ?: return@mapNotNull null
                toDomain(row.account, row.balanceMinor, t, txByLoan[row.account.id].orEmpty())
            }
    }

    fun observe(id: String): Flow<Loan?> = observeAll(includeArchived = true).map { list -> list.firstOrNull { it.accountId == id } }

    suspend fun create(input: LoanInput): String = db.withTransaction {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val disburse = input.disburseToAccountId?.takeIf { input.initialPaid == 0 }
            ?.let { accountDao.getById(it) }
            ?.takeIf { it.currencyCode == input.currency && !it.isArchived && it.type != AccountType.LOAN }
        accountDao.insert(
            AccountEntity(
                id = id,
                name = input.name.trim(),
                type = AccountType.LOAN,
                currencyCode = input.currency,
                initialBalanceMinor = if (disburse != null) 0L else -startDebt(input),
                color = input.color,
                sortOrder = accountDao.maxSortOrder() + 1,
                isArchived = false,
                createdAt = now,
                excludeFromTotal = input.excludeFromTotal,
            ),
        )
        dao.upsert(input.toEntity(id, resolveCategory(), now))
        if (disburse != null) {
            // Выдача: деньги «уходят» со счёта кредита (долг) на выбранный счёт.
            insertTx(
                id = prefix(id) + "0:d",
                type = TransactionType.TRANSFER,
                accountId = id,
                toAccountId = disburse.id,
                amountMinor = input.principalMinor,
                categoryId = null,
                at = now,
                payee = input.name.trim(),
                comment = "${input.kind.title}: получение суммы",
            )
        }
        id
    }

    /**
     * Сумма, остаток на начало и «уже внесено» меняются только до первого платежа в приложении;
     * ставка, срок, схема и дата применяются к будущему графику.
     */
    suspend fun update(id: String, input: LoanInput) = db.withTransaction {
        val acc = accountDao.getById(id) ?: return@withTransaction
        val old = dao.get(id) ?: return@withTransaction
        val txs = dao.loanTransactions().filter { loanIdOf(it.id) == id }
        val hasPayments = txs.any { !it.id.endsWith(":d") }
        val disbursement = txs.firstOrNull { it.id.endsWith(":d") }
        val fixed = if (hasPayments) {
            input.copy(principalMinor = old.principalMinor, initialPaid = old.initialPaid, currency = acc.currencyCode)
        } else {
            input.copy(currency = if (txs.isEmpty()) input.currency else acc.currencyCode)
        }
        accountDao.update(
            acc.copy(
                name = fixed.name.trim(),
                color = fixed.color,
                excludeFromTotal = fixed.excludeFromTotal,
                currencyCode = fixed.currency,
                initialBalanceMinor = when {
                    hasPayments -> acc.initialBalanceMinor
                    disbursement != null -> 0L
                    else -> -startDebt(fixed)
                },
            ),
        )
        if (!hasPayments && disbursement != null) {
            txDao.getById(disbursement.id)?.let {
                txDao.update(it.copy(amountMinor = fixed.principalMinor, toAmountMinor = fixed.principalMinor, updatedAt = System.currentTimeMillis()))
            }
        }
        dao.upsert(fixed.toEntity(id, old.interestCategoryId ?: resolveCategory(), old.createdAt))
    }

    /**
     * Внести платёж со счёта [fromAccountId]. Ежемесячный: проценты за месяц — расход,
     * остальное гасит долг. Досрочный ([early]): всё в долг, номер платежа не растёт,
     * следующие платежи уменьшаются. Проверка долга и запись — в одной транзакции.
     */
    suspend fun pay(loanId: String, fromAccountId: String, amountMinor: Long, early: Boolean): LoanPayResult = db.withTransaction {
        if (amountMinor <= 0 || amountMinor > TransactionValidator.MAX_AMOUNT_MINOR) return@withTransaction LoanPayResult.AMOUNT_INVALID
        val loanAcc = accountDao.getById(loanId) ?: return@withTransaction LoanPayResult.ACCOUNT_INVALID
        val terms = dao.get(loanId) ?: return@withTransaction LoanPayResult.ACCOUNT_INVALID
        val from = accountDao.getById(fromAccountId)
        if (from == null || from.isArchived || from.type == AccountType.LOAN || from.currencyCode != loanAcc.currencyCode) {
            return@withTransaction LoanPayResult.ACCOUNT_INVALID
        }
        val debt = -(accountDao.balanceOf(loanId) ?: 0L)
        if (debt <= 0) return@withTransaction LoanPayResult.PAID_OFF
        val split = LoanCalculator.split(debt, terms.rateBp, amountMinor, early) ?: return@withTransaction LoanPayResult.TOO_MUCH

        val txs = dao.loanTransactions().filter { loanIdOf(it.id) == loanId }
        val number = if (early) {
            "x" + UUID.randomUUID().toString().take(8)
        } else {
            ((txs.mapNotNull { paymentNumberOf(it.id) }.maxOrNull() ?: 0).coerceAtLeast(terms.initialPaid) + 1).toString()
        }
        val now = System.currentTimeMillis()
        val kind = terms.kind.title.lowercase()
        if (split.principalMinor > 0) {
            insertTx(
                id = prefix(loanId) + "$number:p",
                type = TransactionType.TRANSFER,
                accountId = from.id,
                toAccountId = loanId,
                amountMinor = split.principalMinor,
                categoryId = null,
                at = now,
                payee = loanAcc.name,
                comment = if (early) "Досрочное погашение: $kind" else "Платёж №$number: погашение долга",
            )
        }
        if (split.interestMinor > 0) {
            val category = terms.interestCategoryId?.let { categoryDao.getById(it) }?.id ?: resolveCategory()
            insertTx(
                id = prefix(loanId) + "$number:i",
                type = TransactionType.EXPENSE,
                accountId = from.id,
                toAccountId = null,
                amountMinor = split.interestMinor,
                categoryId = category,
                at = now,
                payee = loanAcc.name,
                comment = "Платёж №$number: проценты, ${app.fintrack.domain.logic.DepositCalculator.formatRate(terms.rateBp)} %",
            )
        }
        LoanPayResult.OK
    }

    /** Были ли платежи, внесённые в приложении (выдача не считается). */
    suspend fun hasPayments(loanId: String): Boolean =
        dao.loanTransactions().any { loanIdOf(it.id) == loanId && !it.id.endsWith(":d") }

    suspend fun hasTransactions(loanId: String): Boolean = txDao.countForAccount(loanId) > 0

    /** Платежи, внесённые до начала учёта (для формы редактирования). */
    suspend fun initialPaid(loanId: String): Int = dao.get(loanId)?.initialPaid ?: 0

    /** Закрыть: без операций кредит удаляется, иначе уходит в архив — история сохраняется. */
    suspend fun close(loanId: String) = db.withTransaction {
        val acc = accountDao.getById(loanId) ?: return@withTransaction
        if (txDao.countForAccount(loanId) == 0) {
            accountDao.deleteById(loanId)
        } else {
            accountDao.update(acc.copy(isArchived = true))
        }
    }

    /** Удалить кредит, заведённый по ошибке: только если по нему нет платежей. Выдача удаляется вместе с ним. */
    suspend fun delete(loanId: String): Boolean = db.withTransaction {
        val txs = dao.loanTransactions().filter { loanIdOf(it.id) == loanId }
        if (txs.any { !it.id.endsWith(":d") }) return@withTransaction false
        if (txDao.countForAccount(loanId) > txs.size) return@withTransaction false
        txs.forEach { txDao.deleteById(it.id) }
        accountDao.deleteById(loanId)
        true
    }

    suspend fun restore(loanId: String) = db.withTransaction {
        val acc = accountDao.getById(loanId) ?: return@withTransaction
        accountDao.update(acc.copy(isArchived = false))
    }

    private fun toDomain(acc: AccountEntity, balance: Long, t: LoanEntity, txs: List<LoanTxRow>): Loan {
        val numbers = txs.mapNotNull { paymentNumberOf(it.id) }.toSet()
        return Loan(
            accountId = acc.id,
            name = acc.name,
            currency = acc.currencyCode,
            color = acc.color,
            kind = t.kind,
            schedule = t.schedule,
            principalMinor = t.principalMinor,
            rateBp = t.rateBp,
            startDate = LocalDate.ofEpochDay(t.startDay),
            termMonths = t.termMonths,
            debtMinor = (-balance).coerceAtLeast(0),
            paidCount = t.initialPaid + numbers.count { it > t.initialPaid },
            interestPaidMinor = txs.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountMinor },
            isArchived = acc.isArchived,
            excludeFromTotal = acc.excludeFromTotal,
        )
    }

    private suspend fun insertTx(
        id: String,
        type: TransactionType,
        accountId: String,
        toAccountId: String?,
        amountMinor: Long,
        categoryId: String?,
        at: Long,
        payee: String,
        comment: String,
    ) = txDao.insert(
        TransactionEntity(
            id = id,
            type = type,
            accountId = accountId,
            toAccountId = toAccountId,
            amountMinor = amountMinor,
            toAmountMinor = if (type == TransactionType.TRANSFER) amountMinor else null,
            feeMinor = 0L,
            categoryId = categoryId,
            dateTime = at,
            payee = payee,
            comment = comment,
            isFavorite = false,
            createdAt = at,
            updatedAt = at,
        ),
    )

    /** Категория процентов: «Кредиты» (иконка credit), иначе по названию, иначе создаётся. */
    private suspend fun resolveCategory(): String {
        val roots = categoryDao.getAllOnce().filter { it.kind == CategoryKind.EXPENSE && it.parentId == null && !it.isArchived }
        roots.firstOrNull { it.iconKey == "credit" }?.let { return it.id }
        roots.firstOrNull { it.name.contains("Кредит", ignoreCase = true) }?.let { return it.id }
        val id = UUID.randomUUID().toString()
        categoryDao.insert(
            CategoryEntity(
                id = id,
                name = "Кредиты",
                kind = CategoryKind.EXPENSE,
                parentId = null,
                iconKey = "credit",
                color = 0xFF64748BL,
                sortOrder = categoryDao.maxSortOrder(CategoryKind.EXPENSE.name, null) + 1,
                isArchived = false,
                isSystem = false,
            ),
        )
        return id
    }

    private fun startDebt(i: LoanInput): Long =
        LoanCalculator.debtAfter(i.principalMinor, i.rateBp, i.schedule, i.startDate, i.termMonths, i.initialPaid)

    private fun LoanInput.toEntity(id: String, categoryId: String?, createdAt: Long) = LoanEntity(
        accountId = id,
        kind = kind,
        schedule = schedule,
        principalMinor = principalMinor,
        rateBp = rateBp,
        startDay = startDate.toEpochDay(),
        termMonths = termMonths.coerceIn(1, LoanCalculator.MAX_TERM_MONTHS),
        initialPaid = initialPaid.coerceIn(0, termMonths),
        interestCategoryId = categoryId,
        createdAt = createdAt,
    )

    companion object {
        private const val PREFIX = "loan:"

        fun prefix(loanId: String) = "$PREFIX$loanId:"

        /** «loan:<id>:12:p» → id кредита. */
        fun loanIdOf(txId: String): String = txId.removePrefix(PREFIX).substringBefore(':')

        /** Номер ежемесячного платежа; null — выдача или досрочный платёж. */
        fun paymentNumberOf(txId: String): Int? =
            txId.removePrefix(PREFIX).substringAfter(':').substringBefore(':').toIntOrNull()?.takeIf { it > 0 }
    }
}
