@file:OptIn(ExperimentalMaterial3Api::class)

package app.fintrack.ui.rates

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CurrencyExchange
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.fintrack.data.repository.AccountRepository
import app.fintrack.data.repository.RatesRepository
import app.fintrack.data.repository.SettingsRepository
import app.fintrack.domain.logic.CurrencyConverter
import app.fintrack.domain.logic.ExchangeRate
import app.fintrack.domain.logic.RateSource
import app.fintrack.ui.components.AppCard
import app.fintrack.ui.components.CurrencyPickerDialog
import app.fintrack.ui.components.IconBadge
import app.fintrack.ui.components.SectionTitle
import app.fintrack.ui.components.StaggeredAppear
import app.fintrack.ui.components.bounceClick
import app.fintrack.ui.components.currencyTitle
import app.fintrack.ui.piggy.readableOn
import app.fintrack.ui.theme.FinTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

private val RU: Locale = Locale.forLanguageTag("ru")
private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", RU)
private val DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM, HH:mm", RU)

/** «1 USD = 3,2741 BYN»; мелкие валюты — за 100 единиц: «100 RUB = 3,512 BYN». */
fun formatRate(from: String, to: String, rate: BigDecimal): String {
    val units = if (rate < BigDecimal("0.1")) 100 else 1
    val v = rate.multiply(BigDecimal(units)).round(MathContext(6)).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros()
    val text = (if (v.scale() < 0) v.setScale(0) else v).toPlainString().replace('.', ',')
    return "$units $from = $text $to"
}

data class RateRow(
    val currency: String,
    /** Сколько основной валюты за 1 единицу; null — курса нет. */
    val rateToBase: BigDecimal?,
    val rate: ExchangeRate?,
    /** Курс НБРБ, если действует ручной. */
    val nbrbToBase: BigDecimal?,
)

data class RatesUiState(
    val loading: Boolean = true,
    val base: String = "BYN",
    val rows: List<RateRow> = emptyList(),
    val updatedAt: Long? = null,
    val refreshing: Boolean = false,
)

@HiltViewModel
class RatesViewModel @Inject constructor(
    private val rates: RatesRepository,
    accounts: AccountRepository,
    settings: SettingsRepository,
) : ViewModel() {

    private val extra = MutableStateFlow<Set<String>>(emptySet())
    private val refreshing = MutableStateFlow(false)
    private val events = Channel<String>(Channel.BUFFERED)
    val messages = events.receiveAsFlow()

    val state: StateFlow<RatesUiState> = combine(
        rates.state,
        accounts.observeAccounts(),
        settings.settings,
        extra,
        refreshing,
    ) { r, accs, s, added, busy ->
        val base = s.baseCurrency
        val conv = r.converter
        val nbrbConv = CurrencyConverter(r.nbrb.mapValues { it.value.bynPerUnit })
        val used = accs.filter { !it.account.isArchived }.map { it.account.currencyCode }
        val codes = (used + listOfNotNull(s.displayCurrency) + r.rates.values.filter { it.source == RateSource.MANUAL }.map { it.currency } + added)
            .distinct().filter { it != base }.sorted()
        RatesUiState(
            loading = false,
            base = base,
            rows = codes.map { c ->
                val manual = r.rates[c]?.source == RateSource.MANUAL
                RateRow(c, conv.rate(c, base), r.rates[c], if (manual) nbrbConv.rate(c, base) else null)
            },
            updatedAt = r.updatedAt,
            refreshing = busy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RatesUiState())

    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            val r = rates.refresh(force = true)
            refreshing.value = false
            events.send(r.message)
        }
    }

    fun addCurrency(code: String) = extra.update { it + code }

    /** [text] — сколько основной валюты за 1 единицу [currency]. Возвращает ошибку или null. */
    fun setManual(currency: String, text: String): String? {
        val value = text.trim().replace(" ", "").replace(',', '.').toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
            ?: return "Введите курс больше нуля, например 3,27"
        val base = state.value.base
        viewModelScope.launch {
            // Курсы хранятся к BYN: ввод «сколько основной валюты» пересчитывается через курс основной валюты.
            val baseRate = if (base == CurrencyConverter.PIVOT) BigDecimal.ONE else rates.state.first().rates[base]?.bynPerUnit
            if (baseRate == null) {
                events.send("Сначала нужен курс $base к BYN — обновите курсы НБРБ")
                return@launch
            }
            rates.setManual(currency, value.multiply(baseRate, MathContext.DECIMAL64))
            events.send("Курс $currency сохранён")
        }
        return null
    }

    fun clearManual(currency: String) {
        viewModelScope.launch {
            rates.clearManual(currency)
            events.send("Для $currency снова действует курс НБРБ")
        }
    }
}

@Composable
fun RatesScreen(onBack: () -> Unit, vm: RatesViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<RateRow?>(null) }
    var picking by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.messages.collect { scope.launch { snackbar.showSnackbar(it) } } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Курсы валют") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
                actions = {
                    val spin by rememberInfiniteTransition(label = "spin").animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
                        label = "spinAngle",
                    )
                    IconButton(onClick = vm::refresh, enabled = !state.refreshing) {
                        Icon(Icons.Rounded.Refresh, "Обновить курсы НБРБ", Modifier.rotate(if (state.refreshing) spin else 0f))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                StaggeredAppear(0) {
                    AppCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(Icons.Rounded.CurrencyExchange, MaterialTheme.colorScheme.primary, size = 44.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Официальные курсы НБРБ", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    state.updatedAt?.let {
                                        "Обновлено " + Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DAY_TIME) +
                                            ". Загружаются раз в день, без сети — последние сохранённые"
                                    } ?: "Ещё не загружались — нажмите «Обновить» вверху",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            item { SectionTitle("К основной валюте · ${state.base}") }
            if (!state.loading && state.rows.isEmpty()) {
                item {
                    Text(
                        "Все счета в ${state.base} — пересчитывать нечего. Добавьте валюту, чтобы смотреть итоги в ней.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
            items(state.rows, key = { it.currency }) { row ->
                RateCard(row, state.base, Modifier.animateItem()) { editing = row }
            }
            item {
                TextButton(onClick = { picking = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Icon(Icons.Rounded.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Добавить валюту")
                }
            }
        }
    }

    editing?.let { row ->
        RateDialog(
            row = row,
            base = state.base,
            onDismiss = { editing = null },
            onSave = { text -> vm.setManual(row.currency, text).also { if (it == null) editing = null } },
            onReset = { vm.clearManual(row.currency); editing = null },
        )
    }
    if (picking) {
        CurrencyPickerDialog(
            selected = state.base,
            onSelect = { vm.addCurrency(it); picking = false },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun RateCard(row: RateRow, base: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val fin = FinTheme.colors
    val manual = row.rate?.source == RateSource.MANUAL
    AppCard(modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        Row(
            Modifier
                .bounceClick(pressedScale = 0.98f, onClickLabel = "Изменить курс", onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(currencyTitle(row.currency), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(
                    row.rateToBase?.let { formatRate(row.currency, base, it) } ?: "Нет курса — суммы в ${row.currency} не пересчитываются",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (row.rateToBase == null) fin.statusCritical.readableOn(MaterialTheme.colorScheme.surfaceContainer)
                    else MaterialTheme.colorScheme.onSurface,
                )
                val r = row.rate
                if (r != null) {
                    Text(
                        if (manual) {
                            "Вручную, ${r.date.format(DAY)}" + (row.nbrbToBase?.let { " · НБРБ: " + formatRate(row.currency, base, it) } ?: "")
                        } else {
                            "НБРБ на ${r.date.format(DAY)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (manual) fin.statusWarning.readableOn(MaterialTheme.colorScheme.surfaceContainer)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(Icons.Rounded.Edit, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RateDialog(row: RateRow, base: String, onDismiss: () -> Unit, onSave: (String) -> String?, onReset: () -> Unit) {
    var text by remember {
        mutableStateOf(row.rateToBase?.round(MathContext(6))?.stripTrailingZeros()?.toPlainString()?.replace('.', ',') ?: "")
    }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Курс ${row.currency}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(14); error = null },
                    label = { Text("$base за 1 ${row.currency}") },
                    suffix = { Text(base) },
                    isError = error != null,
                    supportingText = { Text(error ?: "Например, курс вашего банка. Ручной курс важнее курса НБРБ") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (row.rate?.source == RateSource.MANUAL) {
                    TextButton(onClick = onReset) { Text("Вернуть курс НБРБ") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { error = onSave(text) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
