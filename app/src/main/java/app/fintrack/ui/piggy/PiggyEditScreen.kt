@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.fintrack.ui.piggy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fintrack.domain.logic.Money
import app.fintrack.domain.logic.PiggyBank
import app.fintrack.domain.logic.PiggyCalculator
import app.fintrack.ui.components.CategoryIcons
import app.fintrack.ui.components.ColorPicker
import app.fintrack.ui.components.CurrencyPickerDialog
import app.fintrack.ui.components.IconBadge
import app.fintrack.ui.components.IncludeInTotalRow
import app.fintrack.ui.components.Motion
import app.fintrack.ui.components.contentOn
import app.fintrack.ui.components.currencyTitle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val RU: Locale = Locale.forLanguageTag("ru")
private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", RU)

/** Быстрый выбор срока: подпись → месяцев от сегодня. */
private val TERM_CHIPS = listOf("3 мес." to 3L, "6 мес." to 6L, "1 год" to 12L, "2 года" to 24L)

@Composable
fun PiggyEditScreen(
    onClose: () -> Unit,
    onCreated: (String) -> Unit,
    vm: PiggyEditViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showCurrency by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.eventFlow.collect { e ->
            when (e) {
                is PiggyEditEvent.Saved -> e.createdId?.let(onCreated) ?: onClose()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "Новая копилка" else "Копилка") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        val accent = Color(state.color)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Живое превью: иконка, название, цвет
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(CategoryIcons.get(state.iconKey), accent, size = 56.dp, selected = true)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.name.ifBlank { "Название копилки" }, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                    Text(
                        state.targetMinor?.takeIf { it > 0 }?.let { "Цель: " + Money.format(it, state.currency) } ?: "Без суммы цели",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text("На что копим", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PiggyPresets.forEach { (key, title) ->
                    val selected = state.iconKey == key
                    FilterChip(
                        selected = selected,
                        onClick = { vm.pickPreset(key, title) },
                        label = { Text(title) },
                        leadingIcon = { Icon(CategoryIcons.get(key), null, Modifier.size(18.dp)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accent,
                            selectedLabelColor = accent.contentOn(),
                            selectedLeadingIconColor = accent.contentOn(),
                        ),
                    )
                }
            }

            OutlinedTextField(
                value = state.name,
                onValueChange = vm::setName,
                label = { Text("Название") },
                isError = state.nameError,
                supportingText = if (state.nameError) ({ Text("Введите название") }) else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.targetText,
                onValueChange = vm::setTarget,
                label = { Text("Сумма цели") },
                suffix = { Text(state.currency) },
                isError = state.targetError,
                supportingText = { Text(if (state.targetError) "Некорректная сумма" else "Можно оставить пустым — копилка без цели") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = currencyTitle(state.currency),
                onValueChange = {},
                readOnly = true,
                enabled = state.currencyEditable,
                label = { Text("Валюта") },
                supportingText = if (!state.currencyEditable) ({ Text("Валюту нельзя изменить: в копилке есть операции") }) else null,
                trailingIcon = {
                    if (state.currencyEditable) TextButton(onClick = { showCurrency = true }) { Text("Изменить") }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Срок", style = MaterialTheme.typography.titleSmall)
            val today = LocalDate.now()
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = state.deadline == null, onClick = { vm.setDeadline(null) }, label = { Text("Без срока") })
                TERM_CHIPS.forEach { (label, months) ->
                    val date = today.plusMonths(months)
                    FilterChip(selected = state.deadline == date, onClick = { vm.setDeadline(date) }, label = { Text(label) })
                }
                val custom = state.deadline != null && TERM_CHIPS.none { today.plusMonths(it.second) == state.deadline }
                FilterChip(
                    selected = custom,
                    onClick = { showDate = true },
                    label = { Text(if (custom) state.deadline!!.format(DATE) else "Дата…") },
                    leadingIcon = { Icon(Icons.Rounded.Event, null, Modifier.size(18.dp)) },
                )
            }

            // Подсказка: сколько откладывать в месяц
            val target = state.targetMinor ?: 0L
            val deadline = state.deadline
            AnimatedVisibility(
                visible = target > 0 && deadline != null,
                enter = expandVertically(tween(Motion.MEDIUM_MS)) + fadeIn(),
                exit = shrinkVertically(tween(Motion.SHORT_MS)) + fadeOut(tween(Motion.SHORT_MS)),
            ) {
                if (deadline != null) {
                    val preview = PiggyBank("", state.name, state.currency, state.color, state.iconKey, target, deadline, state.balanceMinor)
                    val pr = PiggyCalculator.progress(preview, today)
                    val perMonth = pr.perMonthMinor
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.large)
                            .background(accent.copy(alpha = 0.10f))
                            .padding(16.dp),
                    ) {
                        when {
                            pr.reached -> Text("Цель уже достигнута — можно поднять планку.", style = MaterialTheme.typography.bodyMedium)
                            perMonth != null -> {
                                Text("Откладывайте в месяц", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    Money.format(perMonth, state.currency),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    "${pr.monthsLeft} мес. до ${deadline.format(DATE)} — и цель будет достигнута",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            else -> Text("Срок уже прошёл — выберите дату позже сегодняшней.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            IncludeInTotalRow(state.includeInTotal, vm::setIncludeInTotal, what = "копилка")

            Text("Цвет", style = MaterialTheme.typography.titleSmall)
            ColorPicker(state.color, vm::setColor)

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = vm::save,
                enabled = !state.saving && !state.loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = accent.contentOn()),
            ) { Text(if (state.isNew) "Создать копилку" else "Сохранить") }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showCurrency) {
        CurrencyPickerDialog(
            selected = state.currency,
            onSelect = { vm.setCurrency(it); showCurrency = false },
            onDismiss = { showCurrency = false },
        )
    }

    if (showDate) {
        val tomorrowUtc = LocalDate.now().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = (state.deadline ?: LocalDate.now().plusMonths(6)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis >= tomorrowUtc
            },
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { vm.setDeadline(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    showDate = false
                }) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Отмена") } },
        ) {
            DatePicker(state = pickerState)
        }
    }
}
