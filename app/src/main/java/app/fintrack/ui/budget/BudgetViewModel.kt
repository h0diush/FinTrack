package app.fintrack.ui.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fintrack.data.repository.BudgetRepository
import app.fintrack.data.repository.CategoryRepository
import app.fintrack.data.repository.SettingsRepository
import app.fintrack.data.repository.StatsRepository
import app.fintrack.domain.logic.BudgetCalculator
import app.fintrack.domain.logic.BudgetForecast
import app.fintrack.domain.logic.BudgetLevel
import app.fintrack.domain.logic.BudgetLimit
import app.fintrack.domain.logic.BudgetStatus
import app.fintrack.domain.logic.BudgetThresholds
import app.fintrack.domain.logic.ExpressionEvaluator
import app.fintrack.domain.logic.Money
import app.fintrack.domain.logic.MonthlySpending
import app.fintrack.domain.logic.StatEntry
import app.fintrack.domain.logic.TimeRange
import app.fintrack.domain.model.Category
import app.fintrack.domain.model.CategoryKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

/** Строка бюджета для отображения. */
data class BudgetItem(
    val status: BudgetStatus,
    val category: Category?,
    val level: BudgetLevel,
    val forecast: BudgetForecast?,
)

data class UnbudgetedSpend(val category: Category, val spentMinor: Long)

data class BudgetOverview(
    val overall: BudgetItem?,
    val categories: List<BudgetItem>,
    /** Категории с расходами в этом месяце, но без лимита. */
    val unbudgeted: List<UnbudgetedSpend>,
)

/**
 * Общий расчёт бюджета месяца — используется экраном «Бюджет» и главной,
 * чтобы цифры везде совпадали.
 */
object BudgetOverviewBuilder {

    /** Сколько месяцев назад загружать операции: глубина цепочки переносов. */
    const val HISTORY_MONTHS = BudgetCalculator.MAX_CARRY_MONTHS.toLong()

    fun fetchRange(month: YearMonth, zone: ZoneId): TimeRange = TimeRange(
        month.minusMonths(HISTORY_MONTHS).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
    )

    fun build(
        limits: List<BudgetLimit>,
        entries: List<StatEntry>,
        categories: List<Category>,
        month: YearMonth,
        baseCurrency: String,
        thresholds: BudgetThresholds,
        today: LocalDate,
        zone: ZoneId,
    ): BudgetOverview {
        val cache = HashMap<String, MonthlySpending>()
        val spendingOf = { c: String -> cache.getOrPut(c) { BudgetCalculator.spending(entries, c, zone) } }
        val byId = categories.associateBy { it.id }

        val items = BudgetCalculator.statuses(limits, spendingOf, month).map { s ->
            BudgetItem(
                status = s,
                category = s.categoryId?.let { byId[it] },
                level = s.level(thresholds),
                forecast = BudgetCalculator.forecast(s, month, today),
            )
        }
        val overall = items.firstOrNull { it.status.categoryId == null && it.status.currency == baseCurrency }
            ?: items.firstOrNull { it.status.categoryId == null }
        val categoryItems = items.filter { it.status.categoryId != null }
            .sortedByDescending { it.status.fraction.takeIf { f -> f.isFinite() } ?: Double.MAX_VALUE }

        val withLimit = categoryItems.filter { it.status.currency == baseCurrency }.mapNotNull { it.status.categoryId }.toSet()
        val baseSpending = spendingOf(baseCurrency)
        val unbudgeted = categories
            .filter { it.kind == CategoryKind.EXPENSE && it.parentId == null && it.id !in withLimit }
            .map { UnbudgetedSpend(it, baseSpending.spent(month, it.id)) }
            .filter { it.spentMinor > 0 }
            .sortedByDescending { it.spentMinor }

        return BudgetOverview(overall, categoryItems, unbudgeted)
    }
}

/** Редактор лимита (нижняя панель). categoryId == null — общий бюджет. */
data class LimitEditor(
    val isNew: Boolean,
    /** Общий бюджет на все расходы. */
    val overall: Boolean,
    val categoryId: String?,
    val amountText: String,
    val rollover: Boolean,
    val error: String? = null,
)

data class BudgetUiState(
    val loading: Boolean = true,
    val month: YearMonth = YearMonth.now(),
    val currency: String = "BYN",
    val overview: BudgetOverview = BudgetOverview(null, emptyList(), emptyList()),
    val thresholds: BudgetThresholds = BudgetThresholds(),
    val hideAmounts: Boolean = false,
    /** Корневые категории расходов, на которые ещё нет лимита в этом месяце. */
    val availableCategories: List<Category> = emptyList(),
    val allCategories: Map<String, Category> = emptyMap(),
    val editor: LimitEditor? = null,
) {
    val isCurrentMonth: Boolean get() = month == YearMonth.now()
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BudgetViewModel @Inject constructor(
    private val budgets: BudgetRepository,
    private val stats: StatsRepository,
    categories: CategoryRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val month = MutableStateFlow(YearMonth.now())
    private val editor = MutableStateFlow<LimitEditor?>(null)

    private val entries: Flow<List<StatEntry>> = month.flatMapLatest { m ->
        stats.observeEntries(BudgetOverviewBuilder.fetchRange(m, zone))
    }

    private val overview: Flow<Pair<YearMonth, BudgetOverview>> = combine(
        month,
        budgets.observeLimits(),
        entries,
        categories.observeAll(),
        settings.settings,
    ) { m, limits, list, cats, s ->
        m to BudgetOverviewBuilder.build(
            limits, list, cats, m, s.baseCurrency,
            BudgetThresholds(s.budgetInfoPercent, s.budgetWarningPercent), LocalDate.now(), zone,
        )
    }.flowOn(Dispatchers.Default)

    val state: StateFlow<BudgetUiState> = combine(
        overview,
        settings.settings,
        categories.observeAll(),
        editor,
    ) { (m, o), s, cats, ed ->
        val limited = o.categories.filter { it.status.currency == s.baseCurrency }.mapNotNull { it.status.categoryId }.toSet()
        BudgetUiState(
            loading = false,
            month = m,
            currency = s.baseCurrency,
            overview = o,
            thresholds = BudgetThresholds(s.budgetInfoPercent, s.budgetWarningPercent).normalized(),
            hideAmounts = s.hideAmounts,
            availableCategories = cats.filter {
                it.kind == CategoryKind.EXPENSE && it.parentId == null && !it.isArchived && it.id !in limited
            },
            allCategories = cats.associateBy { it.id },
            editor = ed,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetUiState())

    fun shiftMonth(delta: Long) = month.update { it.plusMonths(delta) }

    /** Открыть редактор. [categoryId] null и [overall] true — общий бюджет; null и false — выбор категории. */
    fun openEditor(categoryId: String?, overall: Boolean) {
        val s = state.value
        val existing = if (overall) {
            s.overview.overall
        } else {
            s.overview.categories.firstOrNull { it.status.categoryId == categoryId && categoryId != null }
        }
        editor.value = LimitEditor(
            isNew = existing == null,
            overall = overall,
            categoryId = if (overall) null else categoryId ?: s.availableCategories.firstOrNull()?.id,
            amountText = existing?.let { Money.toInput(it.status.limitMinor, it.status.currency) } ?: "",
            rollover = existing?.status?.limit?.rollover ?: false,
        )
    }

    fun closeEditor() = editor.update { null }
    fun setEditorCategory(id: String) = editor.update { it?.copy(categoryId = id, error = null) }
    fun setEditorAmount(text: String) = editor.update { it?.copy(amountText = text.take(24), error = null) }
    fun setEditorRollover(v: Boolean) = editor.update { it?.copy(rollover = v) }

    fun saveEditor() {
        val ed = editor.value ?: return
        val s = state.value
        val currency = currencyFor(ed, s)
        val value = ExpressionEvaluator.evaluate(ed.amountText)
        val minor = value?.takeIf { it.signum() > 0 }?.let { Money.toMinor(it, currency) }
        if (minor == null || minor <= 0) {
            editor.update { it?.copy(error = "Введите сумму больше нуля") }
            return
        }
        if (!ed.overall && ed.categoryId == null) {
            editor.update { it?.copy(error = "Выберите категорию") }
            return
        }
        viewModelScope.launch {
            budgets.setLimit(if (ed.overall) null else ed.categoryId, currency, s.month, minor, ed.rollover)
            editor.value = null
        }
    }

    fun removeEditorLimit() {
        val ed = editor.value ?: return
        val s = state.value
        viewModelScope.launch {
            budgets.removeLimit(if (ed.overall) null else ed.categoryId, currencyFor(ed, s), s.month)
            editor.value = null
        }
    }

    fun setThresholds(info: Int, warning: Int) {
        val t = BudgetThresholds(info, warning).normalized()
        viewModelScope.launch { settings.setBudgetThresholds(t.infoPercent, t.warningPercent) }
    }

    /** Редактируется существующий лимит — в его валюте; новый — в основной валюте. */
    private fun currencyFor(ed: LimitEditor, s: BudgetUiState): String {
        val existing = if (ed.overall) {
            s.overview.overall
        } else {
            s.overview.categories.firstOrNull { it.status.categoryId == ed.categoryId }
        }
        return existing?.status?.currency ?: s.currency
    }
}
