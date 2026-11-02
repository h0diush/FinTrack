package app.fintrack.ui.accounts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fintrack.data.repository.AccountRemoval
import app.fintrack.data.repository.AccountRepository
import app.fintrack.data.repository.DepositRepository
import app.fintrack.domain.logic.Deposit
import app.fintrack.domain.logic.DepositCalculator
import java.time.LocalDate
import app.fintrack.data.repository.SettingsRepository
import app.fintrack.domain.logic.Money
import app.fintrack.domain.model.Account
import app.fintrack.domain.model.AccountType
import app.fintrack.domain.model.AccountWithBalance
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
import javax.inject.Inject

// ---------- Список счетов ----------

data class AccountsUiState(
    val loading: Boolean = true,
    val active: List<AccountWithBalance> = emptyList(),
    /** Копилки показываются отдельным разделом и открывают свой экран. */
    val piggies: List<AccountWithBalance> = emptyList(),
    val archived: List<AccountWithBalance> = emptyList(),
    /** Итог по валютам, основная валюта первой. */
    val totals: List<Pair<String, Long>> = emptyList(),
    val hideAmounts: Boolean = false,
    /** Сколько активных счетов не входит в общий баланс. */
    val excludedCount: Int = 0,
    /** Вклады по id счёта — для подписи «12 % годовых · до …». */
    val deposits: Map<String, Deposit> = emptyMap(),
)

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val accounts: AccountRepository,
    settings: SettingsRepository,
    depositRepo: DepositRepository,
) : ViewModel() {

    val state: StateFlow<AccountsUiState> = combine(
        accounts.observeAccounts(),
        settings.settings,
        depositRepo.observeAll(),
    ) { list, s, deps ->
        val active = list.filter { !it.account.isArchived }
        val sums = app.fintrack.domain.logic.BalanceCalculator.totalByCurrency(active)
        val order = (listOf(s.baseCurrency) + sums.keys).distinct().filter { it in sums }
        AccountsUiState(
            loading = false,
            active = active.filter { it.account.type != AccountType.PIGGY },
            piggies = active.filter { it.account.type == AccountType.PIGGY },
            archived = list.filter { it.account.isArchived },
            totals = order.map { it to sums.getValue(it) },
            hideAmounts = s.hideAmounts,
            excludedCount = active.count { it.account.excludeFromTotal },
            deposits = deps.associateBy { it.accountId },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUiState())

    fun move(id: String, direction: Int) {
        viewModelScope.launch { accounts.move(id, direction) }
    }
}

// ---------- Создание и редактирование счёта ----------

data class AccountEditUiState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val id: String? = null,
    val name: String = "",
    val type: AccountType = AccountType.CARD,
    val currency: String = "BYN",
    val initialText: String = "",
    val color: Long = 0xFF3B82F6L,
    /** Входит ли счёт в общий баланс на главной и в «Счетах». */
    val includeInTotal: Boolean = true,
    val isArchived: Boolean = false,
    val hasTransactions: Boolean = false,
    val balanceMinor: Long = 0,
    val nameError: Boolean = false,
    val initialError: Boolean = false,
    val saving: Boolean = false,
    // --- Вклад (только для накопительного счёта) ---
    val depositEnabled: Boolean = false,
    val rateText: String = "",
    val termText: String = "1",
    val termInYears: Boolean = true,
    val depositStart: LocalDate = LocalDate.now(),
    val depositPosted: Int = 0,
    val interestEarnedMinor: Long = 0,
    val rateError: Boolean = false,
    val termError: Boolean = false,
) {
    /** Валюту можно менять, пока по счёту нет операций. */
    val currencyEditable: Boolean get() = !hasTransactions
    val isSavings: Boolean get() = type == AccountType.SAVINGS
    val rateBp: Long? get() = DepositCalculator.parseRate(rateText)
    val termMonths: Int? get() = DepositCalculator.parseTerm(termText, termInYears)
    /** Дату открытия можно менять, пока не было начислений. */
    val depositStartEditable: Boolean get() = depositPosted == 0
}

sealed interface AccountEditEvent {
    data object Closed : AccountEditEvent
    data class Message(val text: String) : AccountEditEvent
}

@HiltViewModel
class AccountEditViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val accounts: AccountRepository,
    private val settings: SettingsRepository,
    private val deposits: DepositRepository,
) : ViewModel() {

    private val argId: String? = savedState["id"]
    private val _state = MutableStateFlow(AccountEditUiState())
    val state: StateFlow<AccountEditUiState> = _state.asStateFlow()
    private val events = Channel<AccountEditEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    init {
        viewModelScope.launch {
            val id = argId
            if (id == null) {
                val base = settings.settings.first().baseCurrency
                _state.update { it.copy(loading = false, currency = base) }
            } else {
                deposits.get(id)?.let { d ->
                    val years = d.termMonths % 12 == 0
                    _state.update {
                        it.copy(
                            depositEnabled = true,
                            rateText = DepositCalculator.formatRate(d.rateBp),
                            termText = if (years) (d.termMonths / 12).toString() else d.termMonths.toString(),
                            termInYears = years,
                            depositStart = d.startDate,
                            depositPosted = d.postedMonths,
                        )
                    }
                }
                launch {
                    deposits.observeInterestEarned(id).collect { earned -> _state.update { it.copy(interestEarnedMinor = earned) } }
                }
                // Баланс и статус обновляются вживую (после корректировки, архивации).
                accounts.observeAccounts().collect { list ->
                    val item = list.firstOrNull { it.account.id == id } ?: return@collect
                    val hasTx = accounts.hasTransactions(id)
                    _state.update { s ->
                        if (s.loading) {
                            val a = item.account
                            s.copy(
                                loading = false, isNew = false, id = a.id, name = a.name, type = a.type,
                                currency = a.currencyCode, initialText = Money.toInput(a.initialBalanceMinor, a.currencyCode),
                                color = a.color, isArchived = a.isArchived, hasTransactions = hasTx,
                                includeInTotal = !a.excludeFromTotal,
                                balanceMinor = item.balanceMinor,
                            )
                        } else {
                            s.copy(isArchived = item.account.isArchived, hasTransactions = hasTx, balanceMinor = item.balanceMinor)
                        }
                    }
                }
            }
        }
    }

    fun setName(v: String) = _state.update { it.copy(name = v.take(60), nameError = false) }
    fun setType(v: AccountType) = _state.update { it.copy(type = v) }
    fun setCurrency(v: String) = _state.update { if (it.currencyEditable) it.copy(currency = v, initialError = false) else it }
    fun setInitial(v: String) = _state.update { it.copy(initialText = v.take(20), initialError = false) }
    fun setColor(v: Long) = _state.update { it.copy(color = v) }
    fun setIncludeInTotal(v: Boolean) = _state.update { it.copy(includeInTotal = v) }

    fun setDepositEnabled(v: Boolean) = _state.update { it.copy(depositEnabled = v) }
    fun setRate(v: String) = _state.update { it.copy(rateText = v.take(8), rateError = false) }
    fun setTerm(v: String) = _state.update { it.copy(termText = v.take(6), termError = false) }
    fun setTermUnit(years: Boolean) = _state.update { it.copy(termInYears = years, termError = false) }
    fun setTermMonths(months: Int) = _state.update {
        if (months % 12 == 0) {
            it.copy(termText = (months / 12).toString(), termInYears = true, termError = false)
        } else {
            it.copy(termText = months.toString(), termInYears = false, termError = false)
        }
    }
    fun setDepositStart(d: LocalDate) = _state.update { if (it.depositStartEditable) it.copy(depositStart = d) else it }

    /** Начальный остаток разбирается со знаком: допускается отрицательный (кредитка, долг). */
    private fun parseSigned(text: String, currency: String): Long? {
        val t = text.trim()
        if (t.isEmpty()) return 0L
        val negative = t.startsWith("-") || t.startsWith("−")
        val v = Money.parse(t.trimStart('-', '−'), currency) ?: return null
        return if (negative) -v else v
    }

    fun save() {
        val s = _state.value
        if (s.saving) return
        val initial = parseSigned(s.initialText, s.currency)
        val wantsDeposit = s.isSavings && s.depositEnabled
        val rateBad = wantsDeposit && s.rateBp == null
        val termBad = wantsDeposit && (s.termMonths == null || s.termMonths!! < s.depositPosted)
        if (s.name.isBlank() || (s.currencyEditable && initial == null) || rateBad || termBad) {
            _state.update {
                it.copy(
                    nameError = s.name.isBlank(),
                    initialError = s.currencyEditable && initial == null,
                    rateError = rateBad,
                    termError = termBad,
                )
            }
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val accountId: String
            if (s.isNew) {
                accountId = accounts.create(s.name, s.type, s.currency, initial ?: 0L, s.color, excludeFromTotal = !s.includeInTotal)
            } else {
                accountId = s.id!!
                val current = accounts.get(s.id!!) ?: return@launch
                accounts.update(
                    current.copy(
                        name = s.name,
                        type = s.type,
                        color = s.color,
                        excludeFromTotal = !s.includeInTotal,
                        currencyCode = s.currency,
                        initialBalanceMinor = if (s.currencyEditable) initial ?: current.initialBalanceMinor else current.initialBalanceMinor,
                    ),
                )
            }
            if (wantsDeposit) {
                deposits.save(accountId, s.rateBp!!, s.depositStart, s.termMonths!!)
                val posted = deposits.postDueInterest()
                if (posted > 0) events.send(AccountEditEvent.Message("Начислены проценты за прошедшие месяцы: $posted"))
            } else if (deposits.get(accountId) != null) {
                deposits.remove(accountId)
            }
            events.send(AccountEditEvent.Closed)
        }
    }

    fun removeOrArchive() {
        val id = _state.value.id ?: return
        viewModelScope.launch {
            when (accounts.removeOrArchive(id)) {
                AccountRemoval.DELETED -> {
                    events.send(AccountEditEvent.Message("Счёт удалён"))
                    events.send(AccountEditEvent.Closed)
                }
                AccountRemoval.ARCHIVED -> events.send(AccountEditEvent.Message("Счёт перемещён в архив, история сохранена"))
                AccountRemoval.NONZERO_BALANCE -> events.send(
                    AccountEditEvent.Message("Сначала обнулите баланс: переведите деньги на другой счёт или сделайте корректировку"),
                )
            }
        }
    }

    fun restore() {
        val id = _state.value.id ?: return
        viewModelScope.launch { accounts.restore(id) }
    }

    /** Возвращает текст ошибки или null при успехе. */
    fun adjust(targetText: String, comment: String): String? {
        val s = _state.value
        val id = s.id ?: return "Счёт не найден"
        val target = parseSigned(targetText, s.currency) ?: return "Некорректная сумма"
        viewModelScope.launch {
            val delta = accounts.adjustBalance(id, target, comment)
            events.send(
                AccountEditEvent.Message(
                    if (delta == 0L) "Баланс уже совпадает" else "Корректировка " + Money.format(delta, s.currency, forceSign = true),
                ),
            )
        }
        return null
    }
}

fun Account.subtitle(): String = "${type.title} · $currencyCode" + if (excludeFromTotal) " · вне баланса" else ""
