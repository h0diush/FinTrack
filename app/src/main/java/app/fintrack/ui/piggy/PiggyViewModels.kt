package app.fintrack.ui.piggy

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fintrack.data.repository.AccountRepository
import app.fintrack.data.repository.PiggyMoveResult
import app.fintrack.data.repository.PiggyRepository
import app.fintrack.data.repository.SettingsRepository
import app.fintrack.data.repository.TransactionRepository
import app.fintrack.domain.logic.Money
import app.fintrack.domain.logic.Motivation
import app.fintrack.domain.logic.PiggyBank
import app.fintrack.domain.logic.PiggyCalculator
import app.fintrack.domain.logic.PiggyProgress
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

// ---------- Экран копилки ----------

data class PiggyDetailUiState(
    val loading: Boolean = true,
    /** null после загрузки — копилку разбили или удалили. */
    val piggy: PiggyBank? = null,
    val progress: PiggyProgress? = null,
    val phrases: List<String> = emptyList(),
    val suggestions: List<Long> = emptyList(),
    /** Счета для пополнения и снятия: та же валюта, не архив, не копилки. */
    val accounts: List<AccountWithBalance> = emptyList(),
    val defaultAccountId: String? = null,
    val history: List<TransactionDetails> = emptyList(),
    val hideAmounts: Boolean = false,
)

sealed interface PiggyEvent {
    data class Message(val text: String) : PiggyEvent
    /** Пройдена отметка 25/50/75/100 % — праздничная анимация. */
    data class Milestone(val percent: Int) : PiggyEvent
    data object Closed : PiggyEvent
}

@HiltViewModel
class PiggyDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val piggies: PiggyRepository,
    accounts: AccountRepository,
    transactions: TransactionRepository,
    settings: SettingsRepository,
) : ViewModel() {

    private val id: String = checkNotNull(savedState["id"])
    private val events = Channel<PiggyEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    val state: StateFlow<PiggyDetailUiState> = combine(
        piggies.observe(id),
        accounts.observeAccounts(),
        transactions.observeFiltered(null, null, id, null, false),
        settings.settings,
    ) { p, accs, history, s ->
        if (p == null) return@combine PiggyDetailUiState(loading = false)
        val today = LocalDate.now()
        val progress = PiggyCalculator.progress(p, today)
        val eligible = accs.filter {
            !it.account.isArchived && it.account.type != AccountType.PIGGY && it.account.currencyCode == p.currency
        }
        // По умолчанию — счёт последнего пополнения, иначе последний использованный, иначе с наибольшим остатком.
        val lastFrom = history.firstOrNull { it.transaction.toAccountId == id }?.transaction?.accountId
        val default = listOf(lastFrom, s.lastAccountId).firstOrNull { c -> eligible.any { it.account.id == c } }
            ?: eligible.maxByOrNull { it.balanceMinor }?.account?.id
        PiggyDetailUiState(
            loading = false,
            piggy = p,
            progress = progress,
            phrases = Motivation.phrases(p, today),
            suggestions = PiggyCalculator.suggestAmounts(p, progress),
            accounts = eligible,
            defaultAccountId = default,
            history = history,
            hideAmounts = s.hideAmounts,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PiggyDetailUiState())

    /** Возвращает текст ошибки для поля суммы или null, если операция принята. */
    fun move(withdraw: Boolean, accountId: String?, amountText: String): String? {
        val s = state.value
        val p = s.piggy ?: return "Копилка не найдена"
        if (accountId == null) return "Выберите счёт"
        val amount = Money.parse(amountText, p.currency)
        if (amount == null || amount <= 0) return "Введите сумму больше нуля"
        if (withdraw && amount > p.balanceMinor) return "В копилке только ${Money.format(p.balanceMinor.coerceAtLeast(0), p.currency)}"
        val before = s.progress?.percent ?: 0
        viewModelScope.launch {
            val result = if (withdraw) piggies.withdraw(id, accountId, amount) else piggies.topUp(id, accountId, amount)
            if (result != PiggyMoveResult.OK) {
                events.send(PiggyEvent.Message(result.message ?: "Не получилось"))
                return@launch
            }
            if (withdraw) {
                events.send(PiggyEvent.Message("Из копилки снято ${Money.format(amount, p.currency)}"))
            } else {
                val after = PiggyCalculator.progress(p.copy(balanceMinor = p.balanceMinor + amount), LocalDate.now()).percent
                val crossed = if (p.hasTarget) PiggyCalculator.crossedMilestones(before, after) else emptyList()
                if (crossed.isNotEmpty()) {
                    events.send(PiggyEvent.Milestone(crossed.last()))
                } else {
                    events.send(PiggyEvent.Message("Копилка пополнена на ${Money.format(amount, p.currency)}"))
                }
            }
        }
        return null
    }

    fun breakPiggy(toAccountId: String?) {
        viewModelScope.launch {
            val r = piggies.breakPiggy(id, toAccountId)
            if (r == PiggyMoveResult.OK) {
                events.send(PiggyEvent.Message("Копилка закрыта, деньги на счёте"))
                events.send(PiggyEvent.Closed)
            } else {
                events.send(PiggyEvent.Message(r.message ?: "Не получилось"))
            }
        }
    }
}

// ---------- Создание и редактирование ----------

data class PiggyEditUiState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val name: String = "",
    val targetText: String = "",
    val currency: String = "BYN",
    val currencyEditable: Boolean = true,
    val iconKey: String = "savings",
    val color: Long = 0xFFEC4899L,
    val deadline: LocalDate? = null,
    /** Уже накоплено — для подсказки «сколько в месяц» при редактировании. */
    val balanceMinor: Long = 0,
    val includeInTotal: Boolean = true,
    val nameError: Boolean = false,
    val targetError: Boolean = false,
    val saving: Boolean = false,
) {
    val targetMinor: Long? get() = if (targetText.isBlank()) 0L else Money.parse(targetText, currency)
}

sealed interface PiggyEditEvent {
    /** id созданной копилки (для перехода на её экран) или null при редактировании. */
    data class Saved(val createdId: String?) : PiggyEditEvent
}

@HiltViewModel
class PiggyEditViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val piggies: PiggyRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val argId: String? = savedState["id"]
    private val _state = MutableStateFlow(PiggyEditUiState())
    val state: StateFlow<PiggyEditUiState> = _state.asStateFlow()
    private val events = Channel<PiggyEditEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    init {
        viewModelScope.launch {
            val id = argId
            if (id == null) {
                _state.update { it.copy(loading = false, currency = settings.settings.first().baseCurrency) }
            } else {
                val p = piggies.observe(id).first() ?: return@launch
                _state.update {
                    it.copy(
                        loading = false,
                        isNew = false,
                        name = p.name,
                        targetText = if (p.targetMinor > 0) Money.toInput(p.targetMinor, p.currency) else "",
                        currency = p.currency,
                        currencyEditable = !piggies.hasTransactions(id),
                        iconKey = p.iconKey,
                        color = p.color,
                        deadline = p.deadline,
                        balanceMinor = p.balanceMinor,
                        includeInTotal = !p.excludeFromTotal,
                    )
                }
            }
        }
    }

    fun setName(v: String) = _state.update { it.copy(name = v.take(40), nameError = false) }
    fun setTarget(v: String) = _state.update { it.copy(targetText = v.take(16), targetError = false) }
    fun setCurrency(v: String) = _state.update { if (it.currencyEditable) it.copy(currency = v, targetError = false) else it }
    fun setIcon(v: String) = _state.update { it.copy(iconKey = v) }
    fun setColor(v: Long) = _state.update { it.copy(color = v) }
    fun setDeadline(v: LocalDate?) = _state.update { it.copy(deadline = v) }
    fun setIncludeInTotal(v: Boolean) = _state.update { it.copy(includeInTotal = v) }

    /** Выбор иконки подсказывает название, если оно ещё пустое. */
    fun pickPreset(iconKey: String, name: String) = _state.update {
        it.copy(iconKey = iconKey, name = if (it.name.isBlank()) name else it.name, nameError = false)
    }

    fun save() {
        val s = _state.value
        if (s.saving || s.loading) return
        val target = s.targetMinor
        if (s.name.isBlank() || target == null) {
            _state.update { it.copy(nameError = s.name.isBlank(), targetError = target == null) }
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val id = argId
            if (id == null) {
                val created = piggies.create(s.name, s.currency, target, s.iconKey, s.color, s.deadline, excludeFromTotal = !s.includeInTotal)
                events.send(PiggyEditEvent.Saved(created))
            } else {
                piggies.update(id, s.name, s.currency, target, s.iconKey, s.color, s.deadline, excludeFromTotal = !s.includeInTotal)
                events.send(PiggyEditEvent.Saved(null))
            }
        }
    }
}
