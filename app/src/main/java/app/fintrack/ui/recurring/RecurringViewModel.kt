package app.fintrack.ui.recurring

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.fintrack.data.repository.AccountRepository
import app.fintrack.data.repository.CategoryRepository
import app.fintrack.data.repository.RecurringPostResult
import app.fintrack.data.repository.RecurringRepository
import app.fintrack.data.repository.SettingsRepository
import app.fintrack.domain.logic.Money
import app.fintrack.notify.RecurringNotifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class RecurringUiState(
    val loading: Boolean = true,
    /** Наступили и ждут решения. */
    val pending: List<RecurringItem> = emptyList(),
    /** Активные, по ближайшей дате. */
    val active: List<RecurringItem> = emptyList(),
    /** На паузе и завершённые. */
    val inactive: List<RecurringItem> = emptyList(),
    val monthly: List<MonthlyLine> = emptyList(),
    val hideAmounts: Boolean = false,
) {
    val isEmpty: Boolean get() = pending.isEmpty() && active.isEmpty() && inactive.isEmpty()
}

/** Проведение и пропуск платежа — общие для списка и главной. */
class RecurringActions(
    private val app: android.content.Context,
    private val repo: RecurringRepository,
    private val message: suspend (String) -> Unit,
) {
    /** Возвращает текст ошибки суммы или null, если платёж принят. */
    fun confirm(scope: kotlinx.coroutines.CoroutineScope, item: RecurringItem, amountText: String?): String? {
        val amount = amountText?.let { Money.parse(it, item.currency) }
        if (amountText != null && (amount == null || amount <= 0)) return "Введите сумму больше нуля"
        scope.launch {
            val r = repo.confirmNext(item.id, amount)
            RecurringNotifier.cancel(app, item.id)
            message(
                if (r == RecurringPostResult.OK) "Проведено: ${item.title}, ${Money.format(amount ?: item.payment.amountMinor, item.currency)}"
                else r.message ?: "Не получилось",
            )
        }
        return null
    }

    fun skip(scope: kotlinx.coroutines.CoroutineScope, item: RecurringItem) {
        scope.launch {
            repo.skipNext(item.id)
            RecurringNotifier.cancel(app, item.id)
            message("Пропущено: ${item.title}")
        }
    }
}

@HiltViewModel
class RecurringViewModel @Inject constructor(
    app: Application,
    private val repo: RecurringRepository,
    accounts: AccountRepository,
    categories: CategoryRepository,
    settings: SettingsRepository,
) : AndroidViewModel(app) {

    private val events = Channel<String>(Channel.BUFFERED)
    val messages = events.receiveAsFlow()
    private val actions = RecurringActions(app, repo) { events.send(it) }

    val state: StateFlow<RecurringUiState> = combine(
        repo.observeAll(),
        accounts.observeAccounts(),
        categories.observeAll(),
        settings.settings,
    ) { list, accs, cats, s ->
        val today = LocalDate.now()
        val items = RecurringItems.build(list, accs, cats, today)
        val live = items.filter { !it.payment.isPaused && it.nextDate != null }
        RecurringUiState(
            loading = false,
            pending = live.filter { it.isPending }.sortedBy { it.nextDate },
            active = live.filter { !it.isPending }.sortedBy { it.nextDate },
            inactive = items.filter { it.payment.isPaused || it.nextDate == null },
            monthly = RecurringItems.monthly(items, s.baseCurrency),
            hideAmounts = s.hideAmounts,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecurringUiState())

    init {
        // Автоплатежи, наступившие с прошлого запуска, — до показа списка.
        viewModelScope.launch { repo.processDue() }
    }

    fun confirm(item: RecurringItem, amountText: String?): String? = actions.confirm(viewModelScope, item, amountText)
    fun skip(item: RecurringItem) = actions.skip(viewModelScope, item)

    fun setPaused(item: RecurringItem, paused: Boolean) {
        viewModelScope.launch {
            repo.setPaused(item.id, paused)
            events.send(if (paused) "«${item.title}» на паузе — платежи не появятся" else "«${item.title}» снова активен")
        }
    }

    fun delete(item: RecurringItem) {
        viewModelScope.launch {
            repo.delete(item.id)
            RecurringNotifier.cancel(getApplication(), item.id)
            events.send("Регулярный платёж удалён. Проведённые операции остались в истории")
        }
    }
}
