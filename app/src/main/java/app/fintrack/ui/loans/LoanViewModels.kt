package app.fintrack.ui.loans

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fintrack.data.repository.AccountRepository
import app.fintrack.data.repository.LoanInput
import app.fintrack.data.repository.LoanPayResult
import app.fintrack.data.repository.LoanRepository
import app.fintrack.data.repository.SettingsRepository
import app.fintrack.data.repository.TransactionRepository
import app.fintrack.domain.logic.DepositCalculator
import app.fintrack.domain.logic.Loan
import app.fintrack.domain.logic.LoanCalculator
import app.fintrack.domain.logic.LoanKind
import app.fintrack.domain.logic.LoanRow
import app.fintrack.domain.logic.LoanSchedule
import app.fintrack.domain.logic.LoanTotals
import app.fintrack.domain.logic.Money
import app.fintrack.domain.model.AccountType
import app.fintrack.domain.model.AccountWithBalance
import app.fintrack.domain.model.TransactionDetails
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** Счета, с которых можно платить: та же валюта, не архив, не копилка и не кредит. */
private fun payable(list: List<AccountWithBalance>, currency: String) = list.filter {
    !it.account.isArchived && it.account.currencyCode == currency &&
        it.account.type != AccountType.PIGGY && it.account.type != AccountType.LOAN
}

// ---------- Список ----------

data class LoanDebt(val currency: String, val debtMinor: Long, val monthlyMinor: Long)

data class LoansUiState(
    val loading: Boolean = true,
    val active: List<Loan> = emptyList(),
    val closed: List<Loan> = emptyList(),
    /** Долг и платежи месяца по валютам. */
    val debts: List<LoanDebt> = emptyList(),
    /** Ближайший неоплаченный платёж среди всех кредитов. */
    val nearest: Loan? = null,
    val hideAmounts: Boolean = false,
)

@HiltViewModel
class LoansViewModel @Inject constructor(
    private val loans: LoanRepository,
    settings: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<LoansUiState> = combine(loans.observeAll(includeArchived = true), settings.settings) { all, s ->
        val active = all.filter { !it.isArchived }.sortedWith(compareBy<Loan> { it.isPaidOff }.thenBy { it.nextDate })
        LoansUiState(
            loading = false,
            active = active,
            closed = all.filter { it.isArchived },
            debts = debts(active, s.baseCurrency),
            nearest = active.filter { !it.isPaidOff }.minByOrNull { it.nextDate },
            hideAmounts = s.hideAmounts,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoansUiState())

    fun restore(id: String) {
        viewModelScope.launch { loans.restore(id) }
    }

    companion object {
        fun debts(active: List<Loan>, base: String): List<LoanDebt> =
            active.filter { !it.isPaidOff }.groupBy { it.currency }
                .map { (c, list) -> LoanDebt(c, list.sumOf { it.debtMinor }, list.sumOf { it.nextPayment()?.paymentMinor ?: 0L }) }
                .sortedBy { if (it.currency == base) 0 else 1 }
    }
}

// ---------- Экран кредита ----------

data class LoanDetailUiState(
    val loading: Boolean = true,
    /** null после загрузки — кредит удалён. */
    val loan: Loan? = null,
    val upcoming: List<LoanRow> = emptyList(),
    /** Остаток процентов по текущему графику. */
    val remaining: LoanTotals? = null,
    val accounts: List<AccountWithBalance> = emptyList(),
    val defaultAccountId: String? = null,
    val history: List<TransactionDetails> = emptyList(),
    /** Удалить можно, пока не было платежей. */
    val canDelete: Boolean = false,
    val hideAmounts: Boolean = false,
)

sealed interface LoanEvent {
    data class Message(val text: String) : LoanEvent
    /** Долг закрыт — праздник. */
    data object PaidOff : LoanEvent
    data object Closed : LoanEvent
}

@HiltViewModel
class LoanDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val loans: LoanRepository,
    accounts: AccountRepository,
    transactions: TransactionRepository,
    settings: SettingsRepository,
) : ViewModel() {

    private val id: String = checkNotNull(savedState["id"])
    private val events = Channel<LoanEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    val state: StateFlow<LoanDetailUiState> = combine(
        loans.observe(id),
        accounts.observeAccounts(),
        transactions.observeForLoan(id, LoanRepository.prefix(id)),
        settings.settings,
    ) { loan, accs, history, s ->
        if (loan == null) return@combine LoanDetailUiState(loading = false)
        val eligible = payable(accs, loan.currency)
        // По умолчанию — счёт прошлого платежа, иначе последний использованный, иначе с наибольшим остатком.
        val lastFrom = history.firstOrNull { it.transaction.id.startsWith(LoanRepository.prefix(id)) && !it.transaction.id.endsWith(":d") }
            ?.transaction?.accountId
        val default = listOf(lastFrom, s.lastAccountId).firstOrNull { c -> eligible.any { it.account.id == c } }
            ?: eligible.maxByOrNull { it.balanceMinor }?.account?.id
        val upcoming = loan.upcoming()
        LoanDetailUiState(
            loading = false,
            loan = loan,
            upcoming = upcoming,
            remaining = LoanCalculator.totals(upcoming),
            accounts = eligible,
            defaultAccountId = default,
            history = history,
            canDelete = history.all { it.transaction.id == LoanRepository.prefix(id) + "0:d" },
            hideAmounts = s.hideAmounts,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoanDetailUiState())

    /** Возвращает текст ошибки для поля суммы или null, если платёж принят. */
    fun pay(accountId: String?, amountText: String, early: Boolean): String? {
        val loan = state.value.loan ?: return "Кредит не найден"
        if (accountId == null) return "Выберите счёт"
        val amount = Money.parse(amountText, loan.currency)
        if (amount == null || amount <= 0) return "Введите сумму больше нуля"
        val max = LoanCalculator.payoffAmount(loan.debtMinor, loan.rateBp, early)
        if (amount > max) return "Для полного погашения хватит ${Money.format(max, loan.currency)}"
        viewModelScope.launch {
            val r = loans.pay(id, accountId, amount, early)
            when {
                r != LoanPayResult.OK -> events.send(LoanEvent.Message(r.message ?: "Не получилось"))
                amount == max -> events.send(LoanEvent.PaidOff)
                else -> events.send(LoanEvent.Message("Платёж ${Money.format(amount, loan.currency)} внесён"))
            }
        }
        return null
    }

    fun close() {
        viewModelScope.launch {
            loans.close(id)
            events.send(LoanEvent.Closed)
        }
    }

    fun delete() {
        viewModelScope.launch {
            if (loans.delete(id)) {
                events.send(LoanEvent.Closed)
            } else {
                events.send(LoanEvent.Message("По кредиту уже есть платежи — его можно только закрыть"))
            }
        }
    }

    fun restore() {
        viewModelScope.launch { loans.restore(id) }
    }
}

// ---------- Создание и редактирование ----------

data class LoanEditUiState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val kind: LoanKind = LoanKind.CREDIT,
    val schedule: LoanSchedule = LoanSchedule.ANNUITY,
    val name: String = "",
    val amountText: String = "",
    val currency: String = "BYN",
    val rateText: String = "",
    val termText: String = "1",
    val termInYears: Boolean = true,
    val startDate: LocalDate = LocalDate.now(),
    val paidText: String = "0",
    val color: Long = LoanStyle.defaultColor(LoanKind.CREDIT),
    val includeInTotal: Boolean = false,
    /** Срок задан вручную — смена вида кредита его не трогает. */
    val termEdited: Boolean = false,
    /** Счёт, куда зачислить сумму кредита; null — не зачислять. */
    val disburseTo: String? = null,
    /** Сумму, валюту и «уже внесено» можно менять до первого платежа. */
    val termsLocked: Boolean = false,
    val currencyLocked: Boolean = false,
    val accounts: List<AccountWithBalance> = emptyList(),
    val nameError: Boolean = false,
    val amountError: Boolean = false,
    val rateError: Boolean = false,
    val termError: Boolean = false,
    val paidError: Boolean = false,
    val saving: Boolean = false,
) {
    val principalMinor: Long? get() = Money.parse(amountText, currency)?.takeIf { it > 0 }
    val rateBp: Long? get() = LoanCalculator.parseRate(rateText.ifBlank { "0" })
    val termMonths: Int? get() = DepositCalculator.parseTerm(termText, termInYears)?.takeIf { it <= LoanCalculator.MAX_TERM_MONTHS }
    val paid: Int? get() = paidText.trim().ifBlank { "0" }.toIntOrNull()?.takeIf { p -> p >= 0 && p < (termMonths ?: Int.MAX_VALUE) }

    /** Сколько дат платежей уже прошло — подсказка для «уже внесено». */
    val dueByToday: Int get() = termMonths?.let { LoanCalculator.dueCount(startDate, it, LocalDate.now()) } ?: 0

    /** Предпросмотр полного графика от выдачи. */
    val preview: List<LoanRow>
        get() {
            val p = principalMinor ?: return emptyList()
            val r = rateBp ?: return emptyList()
            val t = termMonths ?: return emptyList()
            return LoanCalculator.schedule(p, r, schedule, startDate, 1, t)
        }
}

sealed interface LoanEditEvent {
    /** id созданного кредита или null при редактировании. */
    data class Saved(val createdId: String?) : LoanEditEvent
}

@HiltViewModel
class LoanEditViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val loans: LoanRepository,
    private val accounts: AccountRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val argId: String? = savedState["id"]
    private val argKind: LoanKind? = savedState.get<String>("kind")?.let { k -> LoanKind.entries.firstOrNull { it.name == k } }
    private val _state = MutableStateFlow(LoanEditUiState())
    val state: StateFlow<LoanEditUiState> = _state.asStateFlow()
    private val events = Channel<LoanEditEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    init {
        viewModelScope.launch {
            val all = accounts.observeAccounts().first()
            val id = argId
            if (id == null) {
                val base = settings.settings.first().baseCurrency
                _state.update {
                    it.copy(
                        loading = false,
                        name = LoanKind.CREDIT.title,
                        termText = LoanStyle.defaultTerm(LoanKind.CREDIT),
                        currency = base,
                        accounts = payable(all, base),
                    )
                }
                argKind?.let(::setKind)
            } else {
                val l = loans.observe(id).first() ?: return@launch
                val years = l.termMonths % 12 == 0
                val hasPayments = loans.hasPayments(id)
                _state.update {
                    it.copy(
                        loading = false,
                        isNew = false,
                        kind = l.kind,
                        schedule = l.schedule,
                        name = l.name,
                        amountText = Money.toInput(l.principalMinor, l.currency),
                        currency = l.currency,
                        rateText = DepositCalculator.formatRate(l.rateBp),
                        termText = if (years) (l.termMonths / 12).toString() else l.termMonths.toString(),
                        termInYears = years,
                        startDate = l.startDate,
                        paidText = loans.initialPaid(id).toString(),
                        color = l.color,
                        includeInTotal = !l.excludeFromTotal,
                        termsLocked = hasPayments,
                        currencyLocked = hasPayments || loans.hasTransactions(id),
                        accounts = payable(all, l.currency),
                    )
                }
            }
        }
    }

    fun setKind(k: LoanKind) = _state.update {
        val autoName = it.name.isBlank() || LoanKind.entries.any { e -> e.title == it.name }
        val autoColor = LoanKind.entries.any { e -> LoanStyle.defaultColor(e) == it.color }
        it.copy(
            kind = k,
            name = if (autoName) k.title else it.name,
            color = if (autoColor) LoanStyle.defaultColor(k) else it.color,
            rateText = if (k == LoanKind.INSTALLMENT && it.rateText.isBlank()) "0" else it.rateText,
            termInYears = if (it.isNew && !it.termEdited) k != LoanKind.INSTALLMENT else it.termInYears,
            termText = if (it.isNew && !it.termEdited) LoanStyle.defaultTerm(k) else it.termText,
            nameError = false,
        )
    }

    fun setSchedule(v: LoanSchedule) = _state.update { it.copy(schedule = v) }
    fun setName(v: String) = _state.update { it.copy(name = v.take(40), nameError = false) }
    fun setAmount(v: String) = _state.update { if (it.termsLocked) it else it.copy(amountText = v.take(16), amountError = false) }
    fun setRate(v: String) = _state.update { it.copy(rateText = v.take(7), rateError = false) }
    fun setTerm(v: String) = _state.update { it.copy(termText = v.take(5), termEdited = true, termError = false, paidError = false) }
    fun setTermInYears(v: Boolean) = _state.update { it.copy(termInYears = v, termEdited = true, termError = false) }
    fun setPaid(v: String) = _state.update { if (it.termsLocked) it else it.copy(paidText = v.filter(Char::isDigit).take(3), paidError = false) }
    fun setColor(v: Long) = _state.update { it.copy(color = v) }
    fun setIncludeInTotal(v: Boolean) = _state.update { it.copy(includeInTotal = v) }
    fun setDisburseTo(v: String?) = _state.update { it.copy(disburseTo = v) }

    fun setStartDate(v: LocalDate) = _state.update {
        // Для нового кредита «уже внесено» следует за датой выдачи, пока пользователь его не менял.
        val auto = it.isNew && (it.paidText.toIntOrNull() ?: 0) == it.dueByToday
        val next = it.copy(startDate = v)
        if (auto) next.copy(paidText = next.dueByToday.toString(), disburseTo = if (next.dueByToday > 0) null else it.disburseTo) else next
    }

    fun setCurrency(v: String) {
        if (_state.value.currencyLocked) return
        viewModelScope.launch {
            val all = accounts.observeAccounts().first()
            _state.update { it.copy(currency = v, accounts = payable(all, v), disburseTo = null, amountError = false) }
        }
    }

    fun save() {
        val s = _state.value
        if (s.saving || s.loading) return
        val principal = s.principalMinor
        val rate = s.rateBp
        val term = s.termMonths
        val paid = s.paid
        if (s.name.isBlank() || principal == null || rate == null || term == null || paid == null) {
            _state.update {
                it.copy(
                    nameError = s.name.isBlank(),
                    amountError = principal == null,
                    rateError = rate == null,
                    termError = term == null,
                    paidError = paid == null,
                )
            }
            return
        }
        _state.update { it.copy(saving = true) }
        val input = LoanInput(
            name = s.name,
            kind = s.kind,
            schedule = s.schedule,
            currency = s.currency,
            principalMinor = principal,
            rateBp = rate,
            startDate = s.startDate,
            termMonths = term,
            initialPaid = paid,
            color = s.color,
            excludeFromTotal = !s.includeInTotal,
            disburseToAccountId = s.disburseTo.takeIf { paid == 0 },
        )
        viewModelScope.launch {
            val id = argId
            if (id == null) {
                events.send(LoanEditEvent.Saved(loans.create(input)))
            } else {
                loans.update(id, input)
                events.send(LoanEditEvent.Saved(null))
            }
        }
    }
}
