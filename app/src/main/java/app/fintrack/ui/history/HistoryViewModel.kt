package app.fintrack.ui.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fintrack.data.repository.AccountRepository
import app.fintrack.data.repository.CategoryRepository
import app.fintrack.data.repository.SettingsRepository
import app.fintrack.data.repository.TransactionRepository
import app.fintrack.domain.logic.Money
import app.fintrack.domain.logic.Period
import app.fintrack.domain.logic.Periods
import app.fintrack.domain.model.AccountWithBalance
import app.fintrack.domain.model.Category
import app.fintrack.domain.model.TransactionDetails
import app.fintrack.domain.model.TransactionType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

enum class SortOrder(val title: String) {
    DATE_DESC("Сначала новые"),
    DATE_ASC("Сначала старые"),
    AMOUNT_DESC("Сначала крупные"),
    AMOUNT_ASC("Сначала мелкие"),
}

data class HistoryFilter(
    val period: Period,
    val type: TransactionType? = null,
    val accountId: String? = null,
    val categoryId: String? = null,
    val minAmount: BigDecimal? = null,
    val maxAmount: BigDecimal? = null,
    val favoritesOnly: Boolean = false,
    val sort: SortOrder = SortOrder.DATE_DESC,
)

sealed interface HistoryRow {
    val key: String

    data class MonthHeader(val label: String) : HistoryRow {
        override val key = "m:$label"
    }

    /** Итоги дня по валютам: расход и доход. */
    data class DayHeader(val date: LocalDate, val label: String, val expense: Map<String, Long>, val income: Map<String, Long>) : HistoryRow {
        override val key = "d:$date"
    }

    data class Entry(val item: TransactionDetails) : HistoryRow {
        override val key = "t:${item.transaction.id}"
    }
}

data class HistoryUiState(
    val loading: Boolean = true,
    val filter: HistoryFilter,
    val query: String = "",
    val rows: List<HistoryRow> = emptyList(),
    val count: Int = 0,
    val accounts: List<AccountWithBalance> = emptyList(),
    val categories: List<Category> = emptyList(),
    val hideAmounts: Boolean = false,
    val title: String = "История",
    val lockedAccount: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val transactions: TransactionRepository,
    accounts: AccountRepository,
    categories: CategoryRepository,
    settings: SettingsRepository,
) : ViewModel() {

    private val lockedAccountId: String? = savedState["accountId"]
    private val zone: ZoneId = ZoneId.systemDefault()

    private val filter = MutableStateFlow(
        HistoryFilter(
            period = if (lockedAccountId != null) Period.All else Period.Month(YearMonth.now()),
            accountId = lockedAccountId,
        ),
    )
    private val query = MutableStateFlow("")

    private val rows = combine(filter, query.debounce { if (it.isEmpty()) 0L else 200L }) { f, q -> f to q }
        .flatMapLatest { (f, q) ->
            val range = Periods.range(f.period, LocalDate.now(), zone)
            transactions.observeFiltered(range, f.type, f.accountId, f.categoryId, f.favoritesOnly)
                .map { list -> buildRows(list, f, q) }
        }
        .flowOn(Dispatchers.Default)

    val state: StateFlow<HistoryUiState> = combine(
        filter,
        query,
        rows,
        accounts.observeAccounts(),
        combine(categories.observeAll(), settings.settings) { c, s -> c to s },
    ) { f, q, r, accs, (cats, s) ->
        val lockedName = lockedAccountId?.let { id -> accs.firstOrNull { it.account.id == id }?.account?.name }
        HistoryUiState(
            loading = false,
            filter = f,
            query = q,
            rows = r.first,
            count = r.second,
            accounts = accs,
            categories = cats,
            hideAmounts = s.hideAmounts,
            title = lockedName ?: "История",
            lockedAccount = lockedAccountId != null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState(filter = filter.value))

    fun setQuery(q: String) = query.update { q.take(100) }
    fun setPeriod(p: Period) = filter.update { it.copy(period = p) }
    fun shiftPeriod(delta: Int) = filter.update { f -> Periods.shift(f.period, delta)?.let { f.copy(period = it) } ?: f }
    fun setType(t: TransactionType?) = filter.update { it.copy(type = t) }
    fun setAccount(id: String?) = filter.update { if (lockedAccountId != null) it else it.copy(accountId = id) }
    fun setCategory(id: String?) = filter.update { it.copy(categoryId = id) }
    fun setAmountRange(min: BigDecimal?, max: BigDecimal?) = filter.update { it.copy(minAmount = min, maxAmount = max) }
    fun toggleFavorites() = filter.update { it.copy(favoritesOnly = !it.favoritesOnly) }
    fun setSort(s: SortOrder) = filter.update { it.copy(sort = s) }
    fun resetFilters() = filter.update {
        HistoryFilter(period = it.period, accountId = lockedAccountId, sort = it.sort)
    }

    private val ru = Locale.forLanguageTag("ru")

    /** Поиск, фильтр суммы, сортировка и группировка — на фоновом потоке. */
    private fun buildRows(list: List<TransactionDetails>, f: HistoryFilter, q: String): Pair<List<HistoryRow>, Int> {
        val needle = q.trim().lowercase(ru)
        val filtered = list.filter { d ->
            val tx = d.transaction
            val matchesQuery = needle.isEmpty() || listOfNotNull(
                tx.payee, tx.comment, d.categoryName, d.parentCategoryName, d.accountName, d.toAccountName,
            ).any { it.lowercase(ru).contains(needle) }
            val amount = Money.fromMinor(kotlin.math.abs(tx.amountMinor), d.accountCurrency)
            val matchesMin = f.minAmount == null || amount >= f.minAmount
            val matchesMax = f.maxAmount == null || amount <= f.maxAmount
            matchesQuery && matchesMin && matchesMax
        }
        val sorted = when (f.sort) {
            SortOrder.DATE_DESC -> filtered // уже отсортировано в SQL
            SortOrder.DATE_ASC -> filtered.asReversed()
            SortOrder.AMOUNT_DESC -> filtered.sortedByDescending { Money.fromMinor(kotlin.math.abs(it.transaction.amountMinor), it.accountCurrency) }
            SortOrder.AMOUNT_ASC -> filtered.sortedBy { Money.fromMinor(kotlin.math.abs(it.transaction.amountMinor), it.accountCurrency) }
        }
        if (f.sort == SortOrder.AMOUNT_DESC || f.sort == SortOrder.AMOUNT_ASC) {
            return sorted.map { HistoryRow.Entry(it) } to sorted.size
        }

        val today = LocalDate.now()
        val rows = ArrayList<HistoryRow>(sorted.size + 64)
        val byDay = sorted.groupBy { Instant.ofEpochMilli(it.transaction.dateTime).atZone(zone).toLocalDate() }
        var lastMonth: YearMonth? = null
        for ((day, items) in byDay) {
            val ym = YearMonth.from(day)
            if (ym != lastMonth) {
                rows += HistoryRow.MonthHeader(Periods.monthLabel(ym, today))
                lastMonth = ym
            }
            val expense = HashMap<String, Long>()
            val income = HashMap<String, Long>()
            for (d in items) {
                val tx = d.transaction
                when (tx.type) {
                    TransactionType.EXPENSE -> expense.add(d.accountCurrency, tx.amountMinor)
                    TransactionType.INCOME -> income.add(d.accountCurrency, tx.amountMinor)
                    TransactionType.TRANSFER -> if (tx.feeMinor > 0) expense.add(d.accountCurrency, tx.feeMinor)
                    TransactionType.ADJUSTMENT -> Unit
                }
            }
            rows += HistoryRow.DayHeader(day, dayLabel(day, today), expense, income)
            items.forEach { rows += HistoryRow.Entry(it) }
        }
        return rows to sorted.size
    }

    private fun HashMap<String, Long>.add(currency: String, minor: Long) {
        this[currency] = (this[currency] ?: 0L) + minor
    }

    private fun dayLabel(day: LocalDate, today: LocalDate): String {
        val base = when (day) {
            today -> "Сегодня"
            today.minusDays(1) -> "Вчера"
            else -> day.dayOfWeek.getDisplayName(TextStyle.FULL, ru).replaceFirstChar { it.titlecase(ru) }
        }
        val month = day.month.getDisplayName(TextStyle.FULL, ru) // «ноября»
        val year = if (day.year != today.year) " ${day.year}" else ""
        return "$base, ${day.dayOfMonth} $month$year"
    }
}
