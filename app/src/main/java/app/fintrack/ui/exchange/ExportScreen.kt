@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)

package app.fintrack.ui.exchange

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.fintrack.data.repository.AccountRepository
import app.fintrack.data.repository.ExportFormat
import app.fintrack.data.repository.ExportRepository
import app.fintrack.data.repository.SecurityRepository
import app.fintrack.data.repository.SettingsRepository
import app.fintrack.data.repository.TransactionRepository
import app.fintrack.domain.logic.CurrencySummary
import app.fintrack.domain.logic.ExportTable
import app.fintrack.domain.logic.Money
import app.fintrack.domain.logic.Period
import app.fintrack.domain.logic.Periods
import app.fintrack.domain.logic.ruPlural
import app.fintrack.domain.model.AccountWithBalance
import app.fintrack.ui.components.AppCard
import app.fintrack.ui.components.IconBadge
import app.fintrack.ui.components.PeriodBar
import app.fintrack.ui.components.SectionTitle
import app.fintrack.ui.piggy.readableOn
import app.fintrack.ui.theme.FinTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

data class ExportUiState(
    val period: Period = Period.Month(YearMonth.now()),
    val accountId: String? = null,
    val format: ExportFormat = ExportFormat.XLSX,
    val accounts: List<AccountWithBalance> = emptyList(),
    val count: Int = 0,
    val summary: List<CurrencySummary> = emptyList(),
    val saving: Boolean = false,
    val hideAmounts: Boolean = false,
)

@HiltViewModel
class ExportViewModel @Inject constructor(
    private val export: ExportRepository,
    private val security: SecurityRepository,
    transactions: TransactionRepository,
    accounts: AccountRepository,
    settings: SettingsRepository,
) : ViewModel() {

    private val zone = ZoneId.systemDefault()
    private val period = MutableStateFlow<Period>(Period.Month(YearMonth.now()))
    private val accountId = MutableStateFlow<String?>(null)
    private val format = MutableStateFlow(ExportFormat.XLSX)
    private val saving = MutableStateFlow(false)
    private val events = Channel<String>(Channel.BUFFERED)
    val messages = events.receiveAsFlow()

    private val items = combine(period, accountId) { p, a -> p to a }.flatMapLatest { (p, a) ->
        transactions.observeFiltered(Periods.range(p, LocalDate.now(), zone), null, a, null, false)
    }

    val state: StateFlow<ExportUiState> = combine(
        combine(period, accountId, format, saving) { p, a, f, s -> listOf<Any?>(p, a, f, s) },
        items.map { list -> list.size to ExportTable.summarize(list) },
        accounts.observeAccounts(),
        settings.settings,
    ) { head, (count, summary), accs, s ->
        ExportUiState(
            period = head[0] as Period,
            accountId = head[1] as String?,
            format = head[2] as ExportFormat,
            saving = head[3] as Boolean,
            accounts = accs,
            count = count,
            summary = summary,
            hideAmounts = s.hideAmounts,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExportUiState())

    fun setPeriod(p: Period) = period.update { p }
    fun setAccount(id: String?) = accountId.update { id }
    fun setFormat(f: ExportFormat) = format.update { f }

    /** Системное окно сохранения уводит приложение в фон — PIN при возврате не нужен. */
    fun beforePicker() = security.allowExternalScreen()

    fun fileName(): String {
        val s = state.value
        return "FinTrack_${LocalDate.now()}.${s.format.extension}"
    }

    fun save(uri: Uri) {
        val s = state.value
        saving.value = true
        viewModelScope.launch {
            val range = Periods.range(s.period, LocalDate.now(), zone)
            val list = export.load(range, s.accountId)
            val ok = export.write(uri, s.format, list, Periods.label(s.period, LocalDate.now()))
            saving.value = false
            events.send(
                if (ok) "Сохранено: ${list.size} ${ruPlural(list.size.toLong(), "операция", "операции", "операций")}"
                else "Не удалось сохранить файл",
            )
        }
    }
}

private data class FormatOption(val format: ExportFormat, val icon: ImageVector, val color: Color, val hint: String)

private val FORMATS = listOf(
    FormatOption(ExportFormat.XLSX, Icons.Rounded.TableChart, Color(0xFF059669), "Таблица для Excel и Google Таблиц: операции и лист с итогами"),
    FormatOption(ExportFormat.CSV, Icons.Rounded.Description, Color(0xFF2F55E4), "Простой текстовый файл. Его же можно загрузить обратно через импорт"),
    FormatOption(ExportFormat.PDF, Icons.Rounded.PictureAsPdf, Color(0xFFDC3545), "Готовый отчёт для чтения и печати: итоги, категории, операции"),
)

@Composable
fun ExportScreen(onBack: () -> Unit, vm: ExportViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.messages.collect { scope.launch { snackbar.showSnackbar(it) } } }

    val onPicked = { uri: Uri? -> if (uri != null) vm.save(uri) }
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.CSV.mime), onPicked)
    val saveXlsx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.XLSX.mime), onPicked)
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.PDF.mime), onPicked)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Экспорт операций") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PeriodBar(period = state.period, onPeriod = vm::setPeriod)

            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(selected = state.accountId == null, onClick = { vm.setAccount(null) }, label = { Text("Все счета") })
                }
                items(state.accounts, key = { it.account.id }) { a ->
                    FilterChip(
                        selected = state.accountId == a.account.id,
                        onClick = { vm.setAccount(a.account.id) },
                        label = { Text(a.account.name, maxLines = 1) },
                    )
                }
            }

            SummaryCard(state)

            SectionTitle("Формат файла")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FORMATS.forEach { option ->
                    FormatRow(option, selected = state.format == option.format) { vm.setFormat(option.format) }
                }
            }

            Spacer(Modifier.height(6.dp))
            Button(
                onClick = {
                    vm.beforePicker()
                    when (state.format) {
                        ExportFormat.CSV -> saveCsv.launch(vm.fileName())
                        ExportFormat.XLSX -> saveXlsx.launch(vm.fileName())
                        ExportFormat.PDF -> savePdf.launch(vm.fileName())
                    }
                },
                enabled = state.count > 0 && !state.saving,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                if (state.saving) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text("Сохраняем…")
                } else {
                    Text(if (state.count > 0) "Сохранить файл ${state.format.title}" else "За этот период операций нет")
                }
            }
            Text(
                "Файл создаётся только на этом телефоне и никуда не отправляется. Место сохранения выбираете вы. " +
                    "В нём ваши финансовые данные — храните его аккуратно.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

@Composable
private fun SummaryCard(state: ExportUiState) {
    val fin = FinTheme.colors
    val surface = MaterialTheme.colorScheme.surfaceContainer
    fun m(v: Long, c: String) = if (state.hideAmounts) Money.masked(c) else Money.format(v, c)
    AppCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${state.count} ${ruPlural(state.count.toLong(), "операция", "операции", "операций")} в выгрузке",
                style = MaterialTheme.typography.titleSmall,
            )
            state.summary.forEach { s ->
                Row(Modifier.fillMaxWidth()) {
                    Text("Доходы " + m(s.incomeMinor, s.currency), style = MaterialTheme.typography.bodySmall, color = fin.income.readableOn(surface), modifier = Modifier.weight(1f))
                    Text("Расходы " + m(s.expenseMinor, s.currency), style = MaterialTheme.typography.bodySmall, color = fin.expense.readableOn(surface), modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun FormatRow(option: FormatOption, selected: Boolean, onClick: () -> Unit) {
    val border = if (selected) option.color else MaterialTheme.colorScheme.outlineVariant
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) option.color.copy(alpha = 0.08f) else Color.Transparent)
            .border(if (selected) 2.dp else 1.dp, border, MaterialTheme.shapes.medium)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(option.icon, option.color, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(option.format.title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            Text(option.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            null,
            tint = if (selected) option.color else MaterialTheme.colorScheme.outline,
        )
    }
}
