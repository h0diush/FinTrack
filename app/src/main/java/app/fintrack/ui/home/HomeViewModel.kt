package app.fintrack.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fintrack.data.repository.AccountRepository
import app.fintrack.data.repository.BudgetRepository
import app.fintrack.data.repository.CategoryRepository
import app.fintrack.data.repository.DepositRepository
import app.fintrack.data.repository.PiggyRepository
import app.fintrack.domain.logic.Motivation
import app.fintrack.domain.logic.PiggyBank
import app.fintrack.domain.logic.PiggyCalculator
import app.fintrack.domain.logic.PiggyProgress
import app.fintrack.data.repository.SettingsRepository
import app.fintrack.data.repository.StatsRepository
import app.fintrack.data.repository.TransactionRepository
import app.fintrack.domain.logic.BudgetThresholds
import app.fintrack.domain.logic.DayPoint
import app.fintrack.domain.logic.Period
import app.fintrack.domain.logic.Periods
import app.fintrack.domain.logic.StatisticsCalculator
import app.fintrack.domain.model.PeriodTotals
import app.fintrack.domain.model.TransactionDetails
import app.fintrack.ui.budget.BudgetItem
import app.fintrack.ui.budget.BudgetOverviewBuilder
import app.fintrack.data.repository.RecurringRepository
import app.fintrack.ui.recurring.RecurringActions
import app.fintrack.ui.recurring.RecurringItem
import app.fintrack.ui.recurring.RecurringItems
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

data class CurrencyLine(val currency: String, val balanceMinor: Long, val totals: PeriodTotals)

/** Сводка для главной: бюджет месяца и расходы за 7 дней в основной валюте. */
data class HomeSummary(
    val budget: BudgetItem? = null,
    val week: List<DayPoint> = emptyList(),
    val thresholds: BudgetThresholds = BudgetThresholds(),
) {
    val weekTotalMinor: Long get() = week.sumOf { it.expenseMinor }
}

data class HomePiggy(val piggy: PiggyBank, val progress: PiggyProgress)

/** Копилки на главной и фразы для бегущей строки (по самой близкой к цели). */
data class HomePiggies(
    val loading: Boolean = true,
    val items: List<HomePiggy> = emptyList(),
    val phrases: List<String> = emptyList(),
)

/** Кредиты на главной: ближайшие платежи. */
data class HomeLoans(
    val loading: Boolean = true,
    val upcoming: List<app.fintrack.domain.logic.Loan> = emptyList(),
    val count: Int = 0,
)

/** Регулярные платежи на главной: ждут подтверждения и ближайшие на неделю. */
data class HomeRecurring(
    val loading: Boolean = true,
    val pending: List<RecurringItem> = emptyList(),
    val soon: List<RecurringItem> = emptyList(),
    val count: Int = 0,
)

data class HomeUiState(
    val loading: Boolean = true,
    val hideAmounts: Boolean = false,
    val monthLabel: String = "",
    val baseCurrency: String = "BYN",
    /** Первой идёт основная валюта, затем остальные, где есть счета или движение. */
    val lines: List<CurrencyLine> = emptyList(),
    val recent: List<TransactionDetails> = emptyList(),
    val accountsCount: Int = 0,
    /** Счета (и копилки) с выключенным «Учитывать в общем балансе». */
    val excludedCount: Int = 0,
    val summary: HomeSummary = HomeSummary(),
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    accounts: AccountRepository,
    transactions: TransactionRepository,
    stats: StatsRepository,
    budgets: BudgetRepository,
    categories: CategoryRepository,
    private val deposits: DepositRepository,
    piggyRepo: PiggyRepository,
    loanRepo: app.fintrack.data.repository.LoanRepository,
    private val recurringRepo: RecurringRepository,
    @ApplicationContext context: android.content.Context,
    private val settings: SettingsRepository,
) : ViewModel() {

    init {
        // Начислить проценты по вкладам за наступившие (и пропущенные) месяцы.
        viewModelScope.launch { deposits.postDueInterest() }
        // Провести автоматические регулярные платежи, наступившие с прошлого запуска.
        viewModelScope.launch { recurringRepo.processDue() }
    }

    private val zone: ZoneId = ZoneId.systemDefault()
    private val today = LocalDate.now()
    private val month = Period.Month(YearMonth.from(today))
    private val monthRange = Periods.range(month, today, zone)!!

    /** Тот же расчёт, что на экране «Бюджет», — цифры совпадают. */
    private val summary: Flow<HomeSummary> = combine(
        stats.observeEntries(BudgetOverviewBuilder.fetchRange(month.month, zone)),
        budgets.observeLimits(),
        categories.observeAll(),
        settings.settings,
    ) { entries, limits, cats, s ->
        val thresholds = BudgetThresholds(s.budgetInfoPercent, s.budgetWarningPercent).normalized()
        val overview = BudgetOverviewBuilder.build(limits, entries, cats, month.month, s.baseCurrency, thresholds, today, zone)
        val weekStart = today.minusDays(6)
        val week = StatisticsCalculator.compute(entries, s.baseCurrency, weekStart, today.plusDays(1), today, zone).days
        HomeSummary(budget = overview.overall, week = week, thresholds = thresholds)
    }.flowOn(Dispatchers.Default)

    val state: StateFlow<HomeUiState> = combine(
        settings.settings,
        accounts.observeAccounts(),
        transactions.observeTotals(monthRange),
        transactions.observeRecent(RECENT_LIMIT),
        summary,
    ) { s, accs, totals, recent, sum ->
        val active = accs.filter { !it.account.isArchived }
        val regular = active.count {
            it.account.type != app.fintrack.domain.model.AccountType.PIGGY && it.account.type != app.fintrack.domain.model.AccountType.LOAN
        }
        val balances = app.fintrack.domain.logic.BalanceCalculator.totalByCurrency(active)
        val currencies = (listOf(s.baseCurrency) + balances.keys + totals.keys).distinct()
        HomeUiState(
            loading = false,
            hideAmounts = s.hideAmounts,
            monthLabel = Periods.label(month, today),
            baseCurrency = s.baseCurrency,
            lines = currencies.map { c -> CurrencyLine(c, balances[c] ?: 0L, totals[c] ?: PeriodTotals.ZERO) },
            recent = recent,
            accountsCount = regular,
            excludedCount = active.count { it.account.excludeFromTotal },
            summary = sum,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    val piggies: StateFlow<HomePiggies> = piggyRepo.observeAll().map { list ->
        val items = list.map { HomePiggy(it, PiggyCalculator.progress(it, today)) }
            .sortedWith(compareBy<HomePiggy> { it.progress.reached }.thenByDescending { it.progress.fraction })
        val focus = items.firstOrNull { it.progress.hasTarget && !it.progress.reached } ?: items.firstOrNull()
        val phrases = focus?.let { f ->
            val raw = Motivation.phrases(f.piggy, today)
            if (items.size > 1) raw.map { "«${f.piggy.name}»: $it" } else raw
        } ?: emptyList()
        HomePiggies(loading = false, items = items, phrases = phrases)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomePiggies())

    val loans: StateFlow<HomeLoans> = loanRepo.observeAll().map { list ->
        val open = list.filter { !it.isPaidOff }.sortedBy { it.nextDate }
        HomeLoans(loading = false, upcoming = open.take(3), count = open.size)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeLoans())

    private val recurringEvents = Channel<String>(Channel.BUFFERED)
    val messages = recurringEvents.receiveAsFlow()
    private val recurringActions = RecurringActions(context, recurringRepo) { recurringEvents.send(it) }

    val recurring: StateFlow<HomeRecurring> = combine(
        recurringRepo.observeAll(),
        accounts.observeAccounts(),
        categories.observeAll(),
    ) { list, accs, cats ->
        val items = RecurringItems.build(list, accs, cats, today).filter { !it.payment.isPaused && it.nextDate != null }
        val pending = items.filter { it.isPending }.sortedBy { it.nextDate }
        val soon = items.filter { !it.isPending && it.nextDate!! <= today.plusDays(7) }.sortedBy { it.nextDate }.take(3)
        HomeRecurring(loading = false, pending = pending, soon = soon, count = items.size)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeRecurring())

    fun confirmRecurring(item: RecurringItem, amountText: String?): String? = recurringActions.confirm(viewModelScope, item, amountText)
    fun skipRecurring(item: RecurringItem) = recurringActions.skip(viewModelScope, item)

    fun toggleHidden() {
        viewModelScope.launch { settings.setHideAmounts(!state.value.hideAmounts) }
    }

    private companion object {
        const val RECENT_LIMIT = 10
    }
}
