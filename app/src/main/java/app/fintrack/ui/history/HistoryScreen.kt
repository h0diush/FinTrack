@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.fintrack.ui.history

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fintrack.domain.logic.Money
import app.fintrack.domain.logic.Period
import app.fintrack.domain.logic.Periods
import app.fintrack.domain.model.CategoryKind
import app.fintrack.domain.model.TransactionType
import app.fintrack.ui.components.EmptyState
import app.fintrack.ui.components.TransactionItem
import app.fintrack.ui.theme.AmountStyle
import app.fintrack.ui.theme.FinTheme
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

@Composable
fun HistoryScreen(
    onOpenTransaction: (String) -> Unit,
    onBack: (() -> Unit)?,
    vm: HistoryViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val f = state.filter
    val today = LocalDate.now()
    var showCustomRange by remember { mutableStateOf(false) }
    var showAmountDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    Scaffold(
        // Вкладка: нижнюю панель учитывает внешний Scaffold. Экран счёта: обычные отступы.
        contentWindowInsets = if (onBack == null) WindowInsets(0, 0, 0, 0) else androidx.compose.material3.ScaffoldDefaults.contentWindowInsets,
        topBar = {
            TopAppBar(
                title = { Text(state.title) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                placeholder = { Text("Поиск по категории, комментарию, магазину") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Rounded.Close, "Очистить") }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
            )

            // Период: ‹ Ноябрь ›
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val canShift = Periods.shift(f.period, 0) != null
                IconButton(onClick = { vm.shiftPeriod(-1) }, enabled = canShift) {
                    Icon(Icons.Rounded.ChevronLeft, "Предыдущий период")
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    PeriodMenu(
                        label = Periods.label(f.period, today),
                        onSelect = vm::setPeriod,
                        onCustom = { showCustomRange = true },
                    )
                }
                IconButton(onClick = { vm.shiftPeriod(1) }, enabled = canShift) {
                    Icon(Icons.Rounded.ChevronRight, "Следующий период")
                }
            }

            // Фильтры
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MenuChip(
                    label = when (f.type) {
                        TransactionType.EXPENSE -> "Расходы"
                        TransactionType.INCOME -> "Доходы"
                        TransactionType.TRANSFER -> "Переводы"
                        TransactionType.ADJUSTMENT -> "Корректировки"
                        null -> "Все типы"
                    },
                    active = f.type != null,
                    options = listOf(
                        "Все типы" to { vm.setType(null) },
                        "Расходы" to { vm.setType(TransactionType.EXPENSE) },
                        "Доходы" to { vm.setType(TransactionType.INCOME) },
                        "Переводы" to { vm.setType(TransactionType.TRANSFER) },
                        "Корректировки" to { vm.setType(TransactionType.ADJUSTMENT) },
                    ),
                )
                if (!state.lockedAccount) {
                    MenuChip(
                        label = state.accounts.firstOrNull { it.account.id == f.accountId }?.account?.name ?: "Все счета",
                        active = f.accountId != null,
                        options = listOf<Pair<String, () -> Unit>>("Все счета" to { vm.setAccount(null) }) +
                            state.accounts.map { a -> a.account.name to { vm.setAccount(a.account.id) } },
                    )
                }
                val roots = state.categories.filter { it.parentId == null }
                MenuChip(
                    label = state.categories.firstOrNull { it.id == f.categoryId }?.name ?: "Все категории",
                    active = f.categoryId != null,
                    options = listOf<Pair<String, () -> Unit>>("Все категории" to { vm.setCategory(null) }) +
                        roots.sortedBy { it.kind != CategoryKind.EXPENSE }.flatMap { root ->
                            val prefix = if (root.kind == CategoryKind.INCOME) "Доход: " else ""
                            listOf<Pair<String, () -> Unit>>(prefix + root.name to { vm.setCategory(root.id) }) +
                                state.categories.filter { it.parentId == root.id }
                                    .map { child -> "    ${child.name}" to { vm.setCategory(child.id) } }
                        },
                )
                FilterChip(
                    selected = f.minAmount != null || f.maxAmount != null,
                    onClick = { showAmountDialog = true },
                    label = { Text(amountLabel(f.minAmount, f.maxAmount)) },
                )
                FilterChip(
                    selected = f.favoritesOnly,
                    onClick = vm::toggleFavorites,
                    label = { Text("Избранное") },
                    leadingIcon = { Icon(Icons.Rounded.Star, null, Modifier.size(16.dp), tint = Color(0xFFF5B301)) },
                )
                MenuChip(
                    label = f.sort.title,
                    active = f.sort != SortOrder.DATE_DESC,
                    leading = { Icon(Icons.Rounded.SwapVert, null, Modifier.size(16.dp)) },
                    options = SortOrder.entries.map { s -> s.title to { vm.setSort(s) } },
                )
            }

            Text(
                text = "Найдено: ${state.count}",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!state.loading && state.rows.isEmpty()) {
                EmptyState("Операций не найдено. Измените период или фильтры.")
                val hasFilters = f.type != null || f.categoryId != null || f.favoritesOnly ||
                    f.minAmount != null || f.maxAmount != null || (!state.lockedAccount && f.accountId != null)
                if (hasFilters) {
                    TextButton(onClick = vm::resetFilters, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Сбросить фильтры")
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(state.rows, key = { it.key }, contentType = { it::class }) { row ->
                        Box(Modifier.animateItem()) {
                        when (row) {
                            is HistoryRow.MonthHeader -> Text(
                                row.label,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 2.dp),
                            )
                            is HistoryRow.DayHeader -> DayHeaderRow(row, state.hideAmounts)
                            is HistoryRow.Entry -> TransactionItem(
                                item = row.item,
                                hidden = state.hideAmounts,
                                showDate = f.sort == SortOrder.AMOUNT_DESC || f.sort == SortOrder.AMOUNT_ASC,
                                onClick = { onOpenTransaction(row.item.transaction.id) },
                            )
                        }
                        }
                    }
                }
            }
        }
    }

    if (showCustomRange) {
        val pickerState = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { showCustomRange = false },
            confirmButton = {
                TextButton(
                    enabled = pickerState.selectedStartDateMillis != null,
                    onClick = {
                        val start = pickerState.selectedStartDateMillis
                        if (start != null) {
                            val end = pickerState.selectedEndDateMillis ?: start
                            vm.setPeriod(Period.Custom(utcDate(start), utcDate(end)))
                        }
                        showCustomRange = false
                    },
                ) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { showCustomRange = false }) { Text("Отмена") } },
        ) {
            DateRangePicker(state = pickerState, modifier = Modifier.weight(1f))
        }
    }

    if (showAmountDialog) {
        AmountRangeDialog(
            min = f.minAmount,
            max = f.maxAmount,
            onApply = { a, b ->
                vm.setAmountRange(a, b)
                showAmountDialog = false
            },
            onDismiss = { showAmountDialog = false },
        )
    }
}

private fun utcDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

private fun amountLabel(min: BigDecimal?, max: BigDecimal?): String {
    fun s(v: BigDecimal) = v.stripTrailingZeros().toPlainString().replace('.', ',')
    return when {
        min != null && max != null -> "${s(min)} – ${s(max)}"
        min != null -> "от ${s(min)}"
        max != null -> "до ${s(max)}"
        else -> "Сумма"
    }
}

@Composable
private fun PeriodMenu(label: String, onSelect: (Period) -> Unit, onCustom: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val options: List<Pair<String, Period?>> = listOf(
                "Сегодня" to Period.Today,
                "Вчера" to Period.Yesterday,
                "Эта неделя" to Period.ThisWeek,
                "Прошлая неделя" to Period.LastWeek,
                "Этот месяц" to Period.Month(YearMonth.from(today)),
                "Прошлый месяц" to Period.Month(YearMonth.from(today).minusMonths(1)),
                "Последние 3 месяца" to Period.Last3Months,
                "Последние 6 месяцев" to Period.Last6Months,
                "Этот год" to Period.Year(today.year),
                "Всё время" to Period.All,
                "Выбрать даты…" to null,
            )
            options.forEach { (title, period) ->
                DropdownMenuItem(
                    text = { Text(title) },
                    onClick = {
                        expanded = false
                        if (period == null) onCustom() else onSelect(period)
                    },
                )
            }
        }
    }
}

@Composable
private fun MenuChip(
    label: String,
    active: Boolean,
    options: List<Pair<String, () -> Unit>>,
    leading: (@Composable () -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = active,
            onClick = { expanded = true },
            label = { Text(label, maxLines = 1) },
            leadingIcon = leading,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (title, action) ->
                DropdownMenuItem(
                    text = { Text(title) },
                    onClick = {
                        expanded = false
                        action()
                    },
                )
            }
        }
    }
}

@Composable
private fun DayHeaderRow(row: HistoryRow.DayHeader, hidden: Boolean) {
    val fin = FinTheme.colors
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                row.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!hidden) {
                val parts = buildList {
                    row.income.forEach { (c, v) -> add(Money.format(v, c, forceSign = true) to fin.income) }
                    row.expense.forEach { (c, v) -> add(Money.format(-v, c) to fin.expense) }
                }
                // Суммы не шире половины строки: при нехватке места переносятся целиком,
                // а дата не сжимается в столбик по буквам.
                FlowRow(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(start = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    parts.forEach { (text, color) ->
                        Text(
                            text,
                            style = MaterialTheme.typography.labelMedium.merge(AmountStyle),
                            color = color,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 20.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun AmountRangeDialog(
    min: BigDecimal?,
    max: BigDecimal?,
    onApply: (BigDecimal?, BigDecimal?) -> Unit,
    onDismiss: () -> Unit,
) {
    fun toText(v: BigDecimal?) = v?.stripTrailingZeros()?.toPlainString()?.replace('.', ',') ?: ""
    fun parse(t: String): BigDecimal? = t.trim().replace(" ", "").replace(',', '.').takeIf { it.isNotEmpty() }
        ?.let { runCatching { BigDecimal(it) }.getOrNull() }?.takeIf { it.signum() >= 0 }

    var minText by remember { mutableStateOf(toText(min)) }
    var maxText by remember { mutableStateOf(toText(max)) }
    val minValue = parse(minText)
    val maxValue = parse(maxText)
    val invalid = (minText.isNotBlank() && minValue == null) || (maxText.isNotBlank() && maxValue == null) ||
        (minValue != null && maxValue != null && minValue > maxValue)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сумма операции") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = minText,
                    onValueChange = { minText = it.take(20) },
                    label = { Text("От") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = maxText,
                    onValueChange = { maxText = it.take(20) },
                    label = { Text("До") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                if (invalid) {
                    Text("Проверьте значения", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !invalid, onClick = { onApply(minValue, maxValue) }) { Text("Применить") }
        },
        dismissButton = {
            TextButton(onClick = { onApply(null, null) }) { Text("Сбросить") }
        },
    )
}
