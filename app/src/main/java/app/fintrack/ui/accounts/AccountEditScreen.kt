@file:OptIn(ExperimentalMaterial3Api::class)

package app.fintrack.ui.accounts

import app.fintrack.ui.components.Motion
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fintrack.domain.logic.Money
import app.fintrack.domain.model.AccountType
import app.fintrack.ui.components.AmountText
import app.fintrack.ui.components.AppCard
import app.fintrack.ui.components.ColorPicker
import app.fintrack.ui.components.IncludeInTotalRow
import app.fintrack.ui.components.ConfirmDialog
import app.fintrack.ui.components.CurrencyPickerDialog
import app.fintrack.ui.components.currencyTitle

@Composable
fun AccountEditScreen(
    onClose: () -> Unit,
    onOpenHistory: (String) -> Unit,
    vm: AccountEditViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showCurrency by remember { mutableStateOf(false) }
    var showAdjust by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.eventFlow.collect { e ->
            when (e) {
                AccountEditEvent.Closed -> onClose()
                is AccountEditEvent.Message -> Toast.makeText(context, e.text, Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "Новый счёт" else "Счёт") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.isNew) {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Текущий баланс", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        AmountText(state.balanceMinor, state.currency, hidden = false, style = MaterialTheme.typography.headlineMedium)
                        if (state.isArchived) {
                            Text("Счёт в архиве", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
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

            TypeDropdown(state.type, vm::setType)

            OutlinedTextField(
                value = currencyTitle(state.currency),
                onValueChange = {},
                readOnly = true,
                enabled = state.currencyEditable,
                label = { Text("Валюта") },
                supportingText = if (!state.currencyEditable) ({ Text("Валюту нельзя изменить: по счёту есть операции") }) else null,
                trailingIcon = {
                    if (state.currencyEditable) TextButton(onClick = { showCurrency = true }) { Text("Изменить") }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.currencyEditable) {
                OutlinedTextField(
                    value = state.initialText,
                    onValueChange = vm::setInitial,
                    label = { Text("Начальный остаток") },
                    suffix = { Text(state.currency) },
                    isError = state.initialError,
                    supportingText = {
                        Text(if (state.initialError) "Некорректная сумма" else "Можно со знаком минус — для кредитной карты или долга")
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            IncludeInTotalRow(state.includeInTotal, vm::setIncludeInTotal)

            // Вклад с процентами — для накопительного счёта
            AnimatedVisibility(
                visible = state.isSavings,
                enter = expandVertically(tween(Motion.MEDIUM_MS)) + fadeIn(),
                exit = shrinkVertically(tween(Motion.SHORT_MS)) + fadeOut(tween(Motion.SHORT_MS)),
            ) {
                DepositSection(
                    state = state,
                    previewBalanceMinor = if (state.isNew) {
                        state.initialText.trim().takeIf { it.isNotEmpty() && !it.startsWith("-") && !it.startsWith("−") }
                            ?.let { Money.parse(it, state.currency) } ?: 0L
                    } else {
                        state.balanceMinor
                    },
                    onEnabled = vm::setDepositEnabled,
                    onRate = vm::setRate,
                    onTerm = vm::setTerm,
                    onTermUnit = vm::setTermUnit,
                    onTermMonths = vm::setTermMonths,
                    onStart = vm::setDepositStart,
                )
            }

            Text("Цвет", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ColorPicker(state.color, vm::setColor)

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = vm::save,
                enabled = !state.saving && !state.loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.medium,
            ) { Text(if (state.isNew) "Создать счёт" else "Сохранить") }

            if (!state.isNew) {
                val id = state.id
                OutlinedButton(onClick = { showAdjust = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Tune, null)
                    Spacer(Modifier.padding(4.dp))
                    Text("Корректировка баланса")
                }
                if (id != null) {
                    OutlinedButton(onClick = { onOpenHistory(id) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.History, null)
                        Spacer(Modifier.padding(4.dp))
                        Text("Операции по счёту")
                    }
                }
                if (state.isArchived) {
                    OutlinedButton(onClick = vm::restore, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Unarchive, null)
                        Spacer(Modifier.padding(4.dp))
                        Text("Вернуть из архива")
                    }
                } else {
                    TextButton(onClick = { confirmRemove = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Archive, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.padding(4.dp))
                        Text(
                            if (state.hasTransactions) "В архив" else "Удалить счёт",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showCurrency) {
        CurrencyPickerDialog(
            selected = state.currency,
            onSelect = {
                vm.setCurrency(it)
                showCurrency = false
            },
            onDismiss = { showCurrency = false },
        )
    }

    if (confirmRemove) {
        ConfirmDialog(
            title = if (state.hasTransactions) "Переместить в архив?" else "Удалить счёт?",
            text = if (state.hasTransactions) {
                "По счёту есть операции, поэтому он будет перемещён в архив. История сохранится, " +
                    "счёт пропадёт из выбора при вводе операций."
            } else {
                "Операций по счёту нет, он будет удалён."
            },
            confirm = if (state.hasTransactions) "В архив" else "Удалить",
            onConfirm = {
                confirmRemove = false
                vm.removeOrArchive()
            },
            onDismiss = { confirmRemove = false },
        )
    }

    if (showAdjust) {
        AdjustDialog(
            currency = state.currency,
            current = state.balanceMinor,
            onApply = { target, comment -> vm.adjust(target, comment) },
            onDismiss = { showAdjust = false },
        )
    }
}

@Composable
private fun TypeDropdown(selected: AccountType, onSelect: (AccountType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.title,
            onValueChange = {},
            readOnly = true,
            label = { Text("Тип счёта") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AccountType.entries.filter { it != AccountType.PIGGY }.forEach { t ->
                DropdownMenuItem(
                    text = { Text(t.title) },
                    onClick = {
                        onSelect(t)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun AdjustDialog(
    currency: String,
    current: Long,
    onApply: (target: String, comment: String) -> String?,
    onDismiss: () -> Unit,
) {
    var target by remember { mutableStateOf(Money.toInput(current, currency)) }
    var comment by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Корректировка баланса") },
        text = {
            val err = error
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Укажите фактический остаток. Разница будет записана в историю как корректировка " +
                        "и не попадёт в доходы и расходы.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Сейчас: " + Money.format(current, currency), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it.take(20); error = null },
                    label = { Text("Фактический остаток") },
                    suffix = { Text(currency) },
                    isError = err != null,
                    supportingText = if (err != null) ({ Text(err) }) else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                )
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it.take(200) },
                    label = { Text("Комментарий") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val result = onApply(target, comment)
                if (result == null) {
                    onDismiss()
                } else {
                    error = result
                }
            }) { Text("Применить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
