@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.fintrack.ui.loans

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fintrack.domain.logic.DepositCalculator
import app.fintrack.domain.logic.LoanCalculator
import app.fintrack.domain.logic.LoanKind
import app.fintrack.domain.logic.LoanSchedule
import app.fintrack.domain.logic.Money
import app.fintrack.domain.logic.ruPlural
import app.fintrack.ui.components.ColorPicker
import app.fintrack.ui.components.CurrencyPickerDialog
import app.fintrack.ui.components.FitText
import app.fintrack.ui.components.IconBadge
import app.fintrack.ui.components.IncludeInTotalRow
import app.fintrack.ui.components.Motion
import app.fintrack.ui.components.SlidingSegmented
import app.fintrack.ui.components.contentOn
import app.fintrack.ui.components.currencyTitle
import app.fintrack.ui.theme.FinTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun LoanEditScreen(
    onClose: () -> Unit,
    onCreated: (String) -> Unit,
    vm: LoanEditViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showCurrency by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    val fin = FinTheme.colors

    LaunchedEffect(Unit) {
        vm.eventFlow.collect { e ->
            when (e) {
                is LoanEditEvent.Saved -> e.createdId?.let(onCreated) ?: onClose()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "Новый кредит" else "Условия кредита") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (state.loading) return@Scaffold
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
            // Вид долга — три крупные плитки
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LoanKind.entries.forEach { k ->
                    KindTile(k, selected = state.kind == k, modifier = Modifier.weight(1f)) { vm.setKind(k) }
                }
            }

            OutlinedTextField(
                value = state.name,
                onValueChange = vm::setName,
                label = { Text("Название") },
                placeholder = { Text("Например, «Ипотека Альфа»") },
                isError = state.nameError,
                supportingText = if (state.nameError) ({ Text("Введите название") }) else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.termsLocked) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Платежи уже вносились: сумму кредита и валюту изменить нельзя. Ставка, срок и схема применятся к следующим платежам.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedTextField(
                value = state.amountText,
                onValueChange = vm::setAmount,
                label = { Text(if (state.kind == LoanKind.INSTALLMENT) "Стоимость покупки" else "Сумма кредита") },
                suffix = { Text(state.currency) },
                enabled = !state.termsLocked,
                isError = state.amountError,
                supportingText = if (state.amountError) ({ Text("Введите сумму больше нуля") }) else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = currencyTitle(state.currency),
                onValueChange = {},
                readOnly = true,
                enabled = !state.currencyLocked,
                label = { Text("Валюта") },
                trailingIcon = {
                    if (!state.currencyLocked) TextButton(onClick = { showCurrency = true }) { Text("Изменить") }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                OutlinedTextField(
                    value = state.rateText,
                    onValueChange = vm::setRate,
                    label = { Text("Ставка") },
                    suffix = { Text("%") },
                    isError = state.rateError,
                    supportingText = { Text(if (state.rateError) "Например, 14,9" else "0 — без процентов") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.termText,
                    onValueChange = vm::setTerm,
                    label = { Text("Срок") },
                    suffix = { Text(if (state.termInYears) "лет" else "мес.") },
                    isError = state.termError,
                    supportingText = { Text(state.termMonths?.let(DepositCalculator::formatTerm) ?: "До 50 лет") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }
            SlidingSegmented(
                options = listOf("Срок в годах", "В месяцах"),
                selectedIndex = if (state.termInYears) 0 else 1,
                onSelect = { vm.setTermInYears(it == 0) },
                pillColors = listOf(accent, accent),
                height = 40.dp,
            )

            OutlinedTextField(
                value = state.startDate.format(LOAN_DATE),
                onValueChange = {},
                readOnly = true,
                label = { Text("Дата выдачи") },
                supportingText = { Text("Платёж каждый месяц в этот же день, первый — ${LoanCalculator.paymentDate(state.startDate, 1).format(LOAN_DATE)}") },
                trailingIcon = { IconButton(onClick = { showDate = true }) { Icon(Icons.Rounded.Event, "Выбрать дату выдачи") } },
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Схема платежей", style = MaterialTheme.typography.titleSmall)
            SlidingSegmented(
                options = LoanSchedule.entries.map { it.title },
                selectedIndex = state.schedule.ordinal,
                onSelect = { vm.setSchedule(LoanSchedule.entries[it]) },
                pillColors = listOf(accent, accent),
            )
            Text(
                when (state.schedule) {
                    LoanSchedule.ANNUITY -> "Одинаковый платёж каждый месяц. Так считают большинство кредитов и ипотек."
                    LoanSchedule.DIFFERENTIATED -> "Долг гасится равными частями, проценты — на остаток: первый платёж самый большой, дальше меньше. Переплата ниже."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Кредит взят раньше — сколько платежей уже сделано
            val showPaid = state.dueByToday > 0 || (state.paid ?: 0) > 0
            AnimatedVisibility(
                visible = showPaid,
                enter = expandVertically(tween(Motion.MEDIUM_MS)) + fadeIn(),
                exit = shrinkVertically(tween(Motion.SHORT_MS)) + fadeOut(tween(Motion.SHORT_MS)),
            ) {
                OutlinedTextField(
                    value = state.paidText,
                    onValueChange = vm::setPaid,
                    label = { Text("Уже внесено платежей") },
                    enabled = !state.termsLocked,
                    isError = state.paidError,
                    supportingText = {
                        Text(
                            if (state.paidError) "Меньше числа платежей за весь срок"
                            else "По дате выдачи прошло ${state.dueByToday} ${ruPlural(state.dueByToday.toLong(), "платёж", "платежа", "платежей")}. " +
                                "Остаток долга посчитается по графику",
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            PreviewCard(state, accent)

            // Куда пришли деньги — только для нового кредита без прошлых платежей
            AnimatedVisibility(
                visible = state.isNew && (state.paid ?: 0) == 0 && state.accounts.isNotEmpty(),
                enter = expandVertically(tween(Motion.MEDIUM_MS)) + fadeIn(),
                exit = shrinkVertically(tween(Motion.SHORT_MS)) + fadeOut(tween(Motion.SHORT_MS)),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Сумма кредита пришла на счёт?", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Если деньги выдали наличными или на карту — выберите счёт, его остаток вырастет. " +
                            "Для рассрочки и ипотеки деньги обычно уходят продавцу — оставьте «Нет».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = state.disburseTo == null, onClick = { vm.setDisburseTo(null) }, label = { Text("Нет") })
                    }
                    state.accounts.forEach { a ->
                        LoanAccountChoice(a, selected = state.disburseTo == a.account.id, accent = accent) { vm.setDisburseTo(a.account.id) }
                    }
                }
            }

            IncludeInTotalRow(state.includeInTotal, vm::setIncludeInTotal, what = "кредит")
            if (state.includeInTotal) {
                Text(
                    "Остаток долга будет вычитаться из общего баланса на главной.",
                    style = MaterialTheme.typography.bodySmall,
                    color = fin.statusWarning,
                )
            }

            Text("Цвет", style = MaterialTheme.typography.titleSmall)
            ColorPicker(state.color, vm::setColor)

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = vm::save,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = accent.contentOn()),
            ) { Text(if (state.isNew) "Добавить ${state.kind.title.lowercase()}" else "Сохранить") }
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
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { vm.setStartDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    showDate = false
                }) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Отмена") } },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun KindTile(kind: LoanKind, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val color = Color(LoanStyle.defaultColor(kind))
    val border = if (selected) color else MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier
            .clip(MaterialTheme.shapes.large)
            .border(if (selected) 2.dp else 1.dp, border, MaterialTheme.shapes.large)
            .background(if (selected) color.copy(alpha = 0.08f) else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 96.dp)
            .padding(vertical = 14.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconBadge(LoanStyle.icon(kind), color, size = 44.dp, selected = selected)
        Spacer(Modifier.height(8.dp))
        FitText(kind.title, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** Живой расчёт: платёж в месяц, переплата, последний платёж. */
@Composable
private fun PreviewCard(state: LoanEditUiState, accent: Color) {
    val rows = state.preview
    val fin = FinTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(accent.copy(alpha = 0.10f))
            .animateContentSize()
            .padding(16.dp),
    ) {
        if (rows.isEmpty()) {
            Text("Введите сумму, ставку и срок — здесь появится расчёт платежа", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        val totals = LoanCalculator.totals(rows)
        val cur = state.currency
        Text("Ежемесячный платёж", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (state.schedule == LoanSchedule.ANNUITY || totals.firstMinor == totals.lastMinor) {
                Money.format(totals.firstMinor, cur)
            } else {
                "${Money.format(totals.firstMinor, cur)} → ${Money.format(totals.lastMinor, cur)}"
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))
        SplitBar(state.principalMinor ?: 0L, totals.interestMinor, accent, fin.expense.copy(alpha = 0.75f))
        Spacer(Modifier.height(10.dp))
        PreviewLine("Переплата (проценты)", Money.format(totals.interestMinor, cur))
        PreviewLine("Всего заплатить", Money.format(totals.paymentsMinor, cur))
        PreviewLine("Последний платёж", rows.last().date.format(LOAN_DATE))
        val paid = state.paid ?: 0
        if (paid > 0 && paid < rows.size) {
            PreviewLine("Остаток долга сейчас", Money.format(rows[paid - 1].remainingMinor, cur))
        }
    }
}

@Composable
private fun PreviewLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
