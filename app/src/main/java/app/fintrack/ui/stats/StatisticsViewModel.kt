package app.fintrack.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fintrack.data.repository.AccountRepository
import app.fintrack.data.repository.CategoryRepository
import app.fintrack.data.repository.SettingsRepository
import app.fintrack.data.repository.StatsRepository
import app.fintrack.domain.logic.BalancePoint
import app.fintrack.domain.logic.Insight
import app.fintrack.domain.logic.Insights
import app.fintrack.domain.logic.MonthPoint
import app.fintrack.domain.logic.Period
import app.fintrack.domain.logic.PeriodStats
import app.fintrack.domain.logic.Periods
import app.fintrack.domain.logic.StatisticsCalculator
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

/** Всё, что нужно экрану статистики; суммы — в выбранной валюте. */
data class StatsData(
    val currency: String,
    val start: LocalDate,
    val endExclusive: LocalDate,
    val current: PeriodStats,
    val previous: PeriodStats?,
    val previousLabel: String?,
    val monthly: List<MonthPoint>,
    val balance: List<BalancePoint>,
    val insights: List<Insight>,
    /** Валюты без курса: их операции не попали в пересчёт. */
    val missingRates: Set<String> = emptySet(),
)

data class StatisticsUiState(
    val loading: Boolean = true,
    val period: Period = Period.Month(YearMonth.now()),
    val currency: String = "BYN",
    val currencies: List<String> = emptyList(),
    val kind: CategoryKind = CategoryKind.EXPENSE,
    val data: StatsData? = null,
    val categories: Map<String, Category> = emptyMap(),
    val hideAmounts: Boolean = false,
) {
    fun categoryName(id: String?): String = when {
        id == null && kind == CategoryKind.EXPENSE -> "Комиссии переводов"
        id == null -> "Без категории"
        else -> categories[id]?.name ?: "Без категории"
    }
}

private data class Params(val period: Period, val currency: String, val today: LocalDate)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatisticsViewModel @Inject constructor(
    private val stats: StatsRepository,
    private val accounts: AccountRepository,
    private val categories: CategoryRepository,
    settings: SettingsRepository,
    private val rates: app.fintrack.data.repository.RatesRepository,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val period = MutableStateFlow<Period>(Period.Month(YearMonth.now()))
    private val chosenCurrency = MutableStateFlow<String?>(null)
    private val kind = MutableStateFlow(CategoryKind.EXPENSE)

    private val currencies: Flow<List<String>> = combine(accounts.observeAccounts(), settings.settings) { list, s ->
        app.fintrack.domain.model.displayCurrencyOptions(s.baseCurrency, list.filter { !it.account.isArchived }.map { it.account.currencyCode })
    }.distinctUntilChanged()

    /** Валюта статистики: все счета пересчитываются в неё; по умолчанию — валюта итога на главной. */
    private val currency: Flow<String> = combine(chosenCurrency, settings.settings) { chosen, s ->
        chosen ?: s.totalCurrency
    }.distinctUntilChanged()

    private val data: Flow<StatsData?> = combine(period, currency) { p, c -> Params(p, c, LocalDate.now()) }
        .flatMapLatest { params -> load(params) }
        .flowOn(Dispatchers.Default)

    val state: StateFlow<StatisticsUiState> = combine(
        combine(period, currency, currencies, kind) { p, c, cs, k -> listOf<Any>(p, c, cs, k) },
        data,
        categories.observeAll(),
        settings.settings,
    ) { head, d, cats, s ->
        @Suppress("UNCHECKED_CAST")
        StatisticsUiState(
            loading = false,
            period = head[0] as Period,
            currency = head[1] as String,
            currencies = head[2] as List<String>,
            kind = head[3] as CategoryKind,
            data = d,
            categories = cats.associateBy { it.id },
            hideAmounts = s.hideAmounts,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatisticsUiState())

    fun setPeriod(p: Period) = period.update { p }
    fun setCurrency(c: String) = chosenCurrency.update { c }
    fun setKind(k: CategoryKind) = kind.update { k }

    private fun millis(d: LocalDate): Long = d.atStartOfDay(zone).toInstant().toEpochMilli()
    private fun date(m: Long): LocalDate = Instant.ofEpochMilli(m).atZone(zone).toLocalDate()

    private fun load(p: Params): Flow<StatsData?> = stats.observeEarliestDate().flatMapLatest { earliest ->
        val today = p.today
        val range = Periods.range(p.period, today, zone)
            ?: TimeRange(millis(earliest?.let { date(it) } ?: today), millis(today.plusDays(1)))
        val start = date(range.startMillis)
        val endExclusive = date(range.endMillis)

        val prevPeriod = Periods.previous(p.period, today)
        val prevRange = prevPeriod?.let { Periods.range(it, today, zone) }

        // Шесть месяцев, заканчивающихся последним месяцем периода (не позже текущего)
        val lastMonth = YearMonth.from(minOf(endExclusive.minusDays(1), today))
        val months = (5 downTo 0).map { lastMonth.minusMonths(it.toLong()) }
        val monthsStart = millis(months.first().atDay(1))

        val fetch = TimeRange(
            startMillis = minOf(range.startMillis, prevRange?.startMillis ?: Long.MAX_VALUE, monthsStart),
            endMillis = maxOf(range.endMillis, millis(lastMonth.plusMonths(1).atDay(1))),
        )

        combine(
            stats.observeEntries(fetch),
            stats.observeBalancesBefore(range.startMillis),
            excludedAccountIds(),
            rates.state,
        ) { rawEntries, rawOpening, excluded, r ->
            // Все операции — в выбранной валюте по текущему курсу.
            val converter = r.converter
            val (entries, missing) = converter.convertEntries(rawEntries, p.currency)
            val opening = mapOf(p.currency to converter.total(rawOpening, p.currency).totalMinor)
            val current = StatisticsCalculator.compute(entries, p.currency, start, endExclusive, today, zone)
            val previous = prevRange?.let {
                StatisticsCalculator.compute(entries, p.currency, date(it.startMillis), date(it.endMillis), today, zone)
            }
            val previousLabel = prevPeriod?.let { Periods.label(it, today) }
            val balance = StatisticsCalculator.balanceSeries(
                entries.filter { it.dateTime >= range.startMillis },
                p.currency,
                opening[p.currency] ?: 0L,
                current.days.map { it.date },
                zone,
                excluded,
            )
            StatsData(
                currency = p.currency,
                start = start,
                endExclusive = endExclusive,
                current = current,
                previous = previous,
                previousLabel = previousLabel,
                monthly = StatisticsCalculator.monthly(entries, p.currency, months, zone),
                balance = balance,
                insights = emptyList(),
                missingRates = missing,
            )
        }
    }.combine(categoriesNames()) { d, names ->
        d.copy(
            insights = Insights.build(d.current, d.previous, d.previousLabel) { id ->
                if (id == null) "Комиссии переводов" else names[id] ?: "Без категории"
            },
        )
    }

    /** Счета «не учитывать в общем балансе» — их нет и на графике баланса. */
    private fun excludedAccountIds(): Flow<Set<String>> = accounts.observeAccounts()
        .map { list -> list.filter { it.account.excludeFromTotal }.map { it.account.id }.toSet() }
        .distinctUntilChanged()

    private fun categoriesNames(): Flow<Map<String, String>> =
        categories.observeAll().map { list -> list.associate { it.id to it.name } }
}
