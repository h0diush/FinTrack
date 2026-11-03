@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package app.fintrack.ui.editor

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fintrack.domain.logic.KeypadInput
import app.fintrack.domain.logic.KeypadKey
import app.fintrack.domain.logic.Money
import app.fintrack.domain.logic.RecurrenceCalculator
import app.fintrack.domain.logic.RecurringMode
import app.fintrack.domain.logic.ValidationError
import app.fintrack.domain.model.AccountWithBalance
import app.fintrack.domain.model.TransactionType
import app.fintrack.ui.components.AccountIcons
import app.fintrack.ui.components.CategoryIcons
import app.fintrack.ui.components.ConfirmDialog
import app.fintrack.ui.components.DateText
import app.fintrack.ui.components.FitText
import app.fintrack.ui.components.IconBadge
import app.fintrack.ui.components.IconSize
import app.fintrack.ui.components.Motion
import app.fintrack.ui.components.bounceClick
import app.fintrack.ui.components.contentOn
import app.fintrack.ui.components.popOnChange
import app.fintrack.ui.components.pressScale
import app.fintrack.ui.components.shakeOnTrigger
import app.fintrack.ui.theme.AmountStyle
import app.fintrack.ui.piggy.readableOn
import app.fintrack.ui.theme.FinTheme
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@Composable
private fun typeColor(type: TransactionType): Color {
    val fin = FinTheme.colors
    return when (type) {
        TransactionType.EXPENSE -> fin.expense
        TransactionType.INCOME -> fin.income
        TransactionType.TRANSFER -> fin.transfer
        TransactionType.ADJUSTMENT -> fin.neutral
    }
}

@Composable
fun EditorScreen(
    onClose: () -> Unit,
    onCopy: (String) -> Unit,
    onAddCategory: (kind: String) -> Unit,
    vm: EditorViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    var showKeypad by rememberSaveable { mutableStateOf(true) }
    var keypadModeApplied by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showRepeat by remember { mutableStateOf(false) }
    // Разрешение на уведомления спрашиваем, когда платёж впервые становится регулярным.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        vm.eventFlow.collect { e ->
            when (e) {
                EditorEvent.Closed -> onClose()
                is EditorEvent.Message -> Toast.makeText(context, e.text, Toast.LENGTH_SHORT).show()
                is EditorEvent.OpenCopy -> onCopy(e.id)
            }
        }
    }

    val isAdjustment = state.type == TransactionType.ADJUSTMENT

    // Новая операция — клавиатура открыта (начинаем с суммы).
    // Редактирование — свёрнута: чаще меняют категорию или дату, чем сумму.
    LaunchedEffect(state.loading) {
        if (!state.loading && !keypadModeApplied) {
            keypadModeApplied = true
            if (state.mode == EditorMode.EDIT || state.mode == EditorMode.TEMPLATE) showKeypad = false
        }
    }
    // «Назад» сначала сворачивает клавиатуру — как у системной клавиатуры Android.
    BackHandler(enabled = showKeypad && !isAdjustment) { showKeypad = false }
    // Прокрутка полей сворачивает клавиатуру и освобождает место для выбора.
    val collapseOnScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (showKeypad && abs(available.y) > 4f) showKeypad = false
                return Offset.Zero
            }
        }
    }

    val accent by animateColorAsState(typeColor(state.type), tween(Motion.MEDIUM_MS), label = "accent")
    val title = when {
        isAdjustment -> "Корректировка баланса"
        state.mode == EditorMode.TEMPLATE -> "Регулярный платёж"
        state.repeat != null -> "Новый регулярный платёж"
        state.mode == EditorMode.EDIT -> "Операция"
        state.mode == EditorMode.COPY -> "Копия операции"
        else -> "Новая операция"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
                },
                actions = {
                    if (state.mode == EditorMode.EDIT) {
                        if (!isAdjustment) {
                            IconButton(onClick = vm::toggleFavorite) {
                                AnimatedContent(
                                    targetState = state.isFavorite,
                                    transitionSpec = {
                                        (scaleIn(Motion.bouncy(), initialScale = 0.3f) + fadeIn())
                                            .togetherWith(scaleOut(tween(Motion.SHORT_MS)) + fadeOut())
                                    },
                                    label = "favorite",
                                ) { fav ->
                                    if (fav) {
                                        Icon(Icons.Rounded.Star, "Убрать из избранного", tint = Color(0xFFF5B301))
                                    } else {
                                        Icon(Icons.Rounded.StarBorder, "В избранное")
                                    }
                                }
                            }
                            IconButton(onClick = vm::copy) { Icon(Icons.Rounded.ContentCopy, "Копировать") }
                            IconButton(onClick = vm::repeatNow) { Icon(Icons.Rounded.Repeat, "Повторить сейчас") }
                        }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, "Удалить") }
                    }
                    if (state.mode == EditorMode.TEMPLATE) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, "Удалить регулярный платёж") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            if (!isAdjustment) {
                TypeSelector(state.type, vm::setType)
            }
            AmountDisplay(
                state = state,
                accent = accent,
                isAdjustment = isAdjustment,
                keypadVisible = showKeypad,
                onClick = {
                    focusManager.clearFocus()
                    showKeypad = true
                },
                onCurrency = vm::setForeignCurrency,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .nestedScroll(collapseOnScroll)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 12.dp),
            ) {
                // Покупка в другой валюте: сколько на самом деле списано со счёта.
                androidx.compose.animation.AnimatedVisibility(
                    visible = state.showsDebit,
                    enter = expandVertically(tween(Motion.MEDIUM_MS)) + fadeIn(),
                    exit = shrinkVertically(tween(Motion.SHORT_MS)) + fadeOut(tween(Motion.SHORT_MS)),
                ) {
                    val converted = state.convertedMinor
                    val debitError = ValidationError.DEBIT_REQUIRED in state.errors
                    OutlinedTextField(
                        value = state.debitText,
                        onValueChange = vm::setDebit,
                        label = { Text(if (state.type == TransactionType.INCOME) "Зачислено на счёт" else "Списано со счёта") },
                        placeholder = { Text(converted?.let { Money.toInput(it, state.currency) } ?: "Сумма в ${state.currency}") },
                        suffix = { Text(state.currency) },
                        isError = debitError,
                        supportingText = {
                            Text(
                                when {
                                    debitError -> ValidationError.DEBIT_REQUIRED.message
                                    converted != null && state.repeat != null ->
                                        "За первый платёж по курсу — ${Money.format(converted, state.currency)}. Дальше — по курсу на день платежа"
                                    converted != null -> "По курсу — ${Money.format(converted, state.currency)}. Если банк списал другую сумму, впишите её"
                                    else -> "Курса ${state.entryCurrency} нет — впишите сумму из банка или задайте курс в настройках"
                                },
                            )
                        },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .onFocusChanged { if (it.isFocused) showKeypad = false },
                    )
                }

                // Блок полей мягко сменяется при переключении типа операции.
                AnimatedContent(
                    targetState = state.type,
                    transitionSpec = {
                        fadeIn(tween(Motion.MEDIUM_MS, delayMillis = 60))
                            .togetherWith(fadeOut(tween(Motion.SHORT_MS)))
                            .using(SizeTransform(clip = false))
                    },
                    label = "typeFields",
                ) { type ->
                    Column {
                        when (type) {
                            TransactionType.TRANSFER -> TransferFields(state, vm, onKeypadHide = { showKeypad = false })
                            TransactionType.ADJUSTMENT -> {
                                FieldLabel("Счёт")
                                Text(
                                    state.account?.account?.name ?: "",
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    "Корректировка изменяет баланс без учёта в доходах и расходах. " +
                                        "Сумму изменить нельзя — удалите корректировку и создайте новую на экране счёта.",
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            else -> {
                                FieldLabel("Категория")
                                CategoryGrid(
                                    state = state,
                                    accent = accent,
                                    onSelect = vm::selectCategory,
                                    onAdd = { state.kind?.let { onAddCategory(it.name) } },
                                )
                                ErrorText(state.errors, ValidationError.CATEGORY_REQUIRED)
                                FieldLabel("Счёт")
                                AccountSelector(state.selectableAccounts, state.accountId, state.hideAmounts, accent, vm::setAccount)
                            }
                        }
                    }
                }
                ErrorText(state.errors, ValidationError.ACCOUNT_REQUIRED)

                FieldLabel(
                    when {
                        state.mode == EditorMode.TEMPLATE -> "Следующий платёж"
                        state.repeat != null -> "Первый платёж"
                        else -> "Дата и время"
                    },
                )
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AssistChip(
                        onClick = { showDatePicker = true },
                        label = { Text(DateText.date(state.date)) },
                        leadingIcon = { Icon(Icons.Rounded.CalendarToday, null, Modifier.size(IconSize.sm)) },
                        shape = MaterialTheme.shapes.small,
                        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    )
                    AssistChip(
                        onClick = { showTimePicker = true },
                        label = { Text(state.time.format(DateTimeFormatter.ofPattern("HH:mm"))) },
                        leadingIcon = { Icon(Icons.Rounded.Schedule, null, Modifier.size(IconSize.sm)) },
                        shape = MaterialTheme.shapes.small,
                        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    )
                }

                if (state.canRepeat) {
                    RepeatRow(state.repeat, state.date, accent) {
                        focusManager.clearFocus()
                        showKeypad = false
                        showRepeat = true
                    }
                }

                if (!isAdjustment && state.type != TransactionType.TRANSFER) {
                    OutlinedTextField(
                        value = state.payee,
                        onValueChange = vm::setPayee,
                        label = { Text(if (state.type == TransactionType.INCOME) "Источник (необязательно)" else "Магазин или получатель (необязательно)") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .onFocusChanged { if (it.isFocused) showKeypad = false },
                    )
                }
                OutlinedTextField(
                    value = state.comment,
                    onValueChange = vm::setComment,
                    label = { Text("Комментарий") },
                    maxLines = 4,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .onFocusChanged { if (it.isFocused) showKeypad = false },
                )
            }

            AnimatedVisibility(
                visible = showKeypad && !isAdjustment,
                enter = expandVertically(tween(Motion.MEDIUM_MS)) + fadeIn(),
                exit = shrinkVertically(tween(Motion.SHORT_MS)) + fadeOut(tween(Motion.SHORT_MS)),
            ) {
                Keypad(onKey = vm::onKey, onCollapse = { showKeypad = false })
            }
            val saveSource = remember { MutableInteractionSource() }
            Button(
                onClick = {
                    focusManager.clearFocus()
                    vm.save()
                },
                enabled = !state.saving && !state.loading,
                interactionSource = saveSource,
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = accent.contentOn()),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .navigationBarsPadding()
                    .height(56.dp)
                    .pressScale(saveSource, 0.97f),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    when {
                        state.mode == EditorMode.EDIT -> "Сохранить изменения"
                        state.mode == EditorMode.TEMPLATE -> "Сохранить платёж"
                        state.repeat != null && !state.date.isAfter(LocalDate.now()) -> "Провести и повторять"
                        state.repeat != null -> "Запланировать"
                        else -> "Сохранить"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        vm.setDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    showDatePicker = false
                }) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Отмена") } },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showTimePicker) {
        val timeState = rememberTimePickerState(
            initialHour = state.time.hour,
            initialMinute = state.time.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    vm.setTime(LocalTime.of(timeState.hour, timeState.minute))
                    showTimePicker = false
                }) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Отмена") } },
            text = { TimePicker(state = timeState) },
        )
    }

    if (showRepeat) {
        RepeatSheet(
            initial = state.repeat,
            date = state.date,
            accent = accent,
            allowNone = state.mode != EditorMode.TEMPLATE,
            onDateChange = vm::setDate,
            onDone = { r ->
                if (r != null && state.repeat == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                vm.setRepeat(r)
                showRepeat = false
            },
            onDismiss = { showRepeat = false },
        )
    }

    if (confirmDelete) {
        val template = state.mode == EditorMode.TEMPLATE
        ConfirmDialog(
            title = if (template) "Удалить регулярный платёж?" else "Удалить операцию?",
            text = if (template) {
                "Новые платежи больше не появятся. Уже проведённые операции останутся в истории."
            } else {
                "Баланс счёта будет пересчитан. Действие нельзя отменить."
            },
            confirm = "Удалить",
            onConfirm = {
                confirmDelete = false
                vm.delete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** «Повтор: не повторять» или правило с режимом; нажатие открывает настройку. */
@Composable
private fun RepeatRow(repeat: RepeatDraft?, date: LocalDate, accent: Color, onClick: () -> Unit) {
    val active = repeat != null
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(if (active) accent.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainer)
            .bounceClick(pressedScale = 0.98f, onClickLabel = "Настроить повтор", onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Repeat,
            contentDescription = null,
            tint = if (active) accent.readableOn(MaterialTheme.colorScheme.surface) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (repeat == null) "Не повторять" else RecurrenceCalculator.describe(repeat.toRule(date)),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                when (repeat?.mode) {
                    null -> "Сделать платёж регулярным: подписка, аренда, зарплата"
                    RecurringMode.AUTO -> "Проводится автоматически"
                    RecurringMode.CONFIRM -> "С подтверждением в день платежа"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.Rounded.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TransferFields(state: EditorUiState, vm: EditorViewModel, onKeypadHide: () -> Unit) {
    val accent = FinTheme.colors.transfer
    FieldLabel("Откуда")
    AccountSelector(state.selectableAccounts, state.accountId, state.hideAmounts, accent, vm::setAccount)
    FieldLabel("Куда")
    AccountSelector(state.selectableAccounts, state.toAccountId, state.hideAmounts, accent, vm::setToAccount)
    ErrorText(state.errors, ValidationError.TO_ACCOUNT_REQUIRED, ValidationError.SAME_ACCOUNT)
    AnimatedVisibility(
        visible = state.needsToAmount,
        enter = expandVertically(tween(Motion.MEDIUM_MS)) + fadeIn(),
        exit = shrinkVertically(tween(Motion.SHORT_MS)) + fadeOut(),
    ) {
        Column {
            OutlinedTextField(
                value = state.toAmountText,
                onValueChange = vm::setToAmount,
                label = { Text("Зачислено") },
                suffix = { Text(state.toCurrency ?: "") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                isError = ValidationError.TO_AMOUNT_REQUIRED in state.errors,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .onFocusChanged { if (it.isFocused) onKeypadHide() },
            )
            ErrorText(state.errors, ValidationError.TO_AMOUNT_REQUIRED)
        }
    }
    OutlinedTextField(
        value = state.feeText,
        onValueChange = vm::setFee,
        label = { Text("Комиссия (необязательно)") },
        suffix = { Text(state.currency) },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        isError = ValidationError.FEE_INVALID in state.errors,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .onFocusChanged { if (it.isFocused) onKeypadHide() },
    )
    ErrorText(state.errors, ValidationError.FEE_INVALID)
}

/**
 * Переключатель «Расход / Доход / Перевод»: цветная «пилюля» перетекает
 * к выбранному пункту на пружине и меняет цвет под тип операции.
 */
@Composable
private fun TypeSelector(selected: TransactionType, onSelect: (TransactionType) -> Unit) {
    val options = listOf(
        TransactionType.EXPENSE to "Расход",
        TransactionType.INCOME to "Доход",
        TransactionType.TRANSFER to "Перевод",
    )
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val pillColor by animateColorAsState(typeColor(selected), tween(Motion.MEDIUM_MS), label = "pillColor")
    val haptic = LocalHapticFeedback.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .height(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(4.dp),
    ) {
        val segment = maxWidth / options.size
        val pillX by animateDpAsState(segment * index, Motion.indicator(), label = "pillX")
        val density = LocalDensity.current
        Box(
            Modifier
                .offset { IntOffset(with(density) { pillX.roundToPx() }, 0) }
                .width(segment)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(pillColor),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEach { (type, label) ->
                val isSelected = type == selected
                val textColor by animateColorAsState(
                    if (isSelected) pillColor.contentOn() else MaterialTheme.colorScheme.onSurfaceVariant,
                    tween(Motion.MEDIUM_MS),
                    label = "segText",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .selectable(
                            selected = isSelected,
                            role = Role.Tab,
                            onClick = {
                                if (!isSelected) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(type)
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = textColor, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun AmountDisplay(
    state: EditorUiState,
    accent: Color,
    isAdjustment: Boolean,
    keypadVisible: Boolean,
    onClick: () -> Unit,
    onCurrency: (String) -> Unit,
) {
    val amountError = ValidationError.AMOUNT_REQUIRED in state.errors || ValidationError.AMOUNT_TOO_LARGE in state.errors
    var shake by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.errors) { if (amountError) shake++ }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (!isAdjustment) Modifier.bounceClick(pressedScale = 0.99f, role = null, onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.End,
    ) {
        if (isAdjustment) {
            Text(
                Money.format(state.adjustmentMinor, state.currency, forceSign = true),
                style = MaterialTheme.typography.displaySmall.merge(AmountStyle),
            )
            return@Column
        }
        val color by animateColorAsState(
            when {
                amountError -> MaterialTheme.colorScheme.error
                state.expression.isEmpty() -> accent.copy(alpha = 0.35f)
                else -> accent
            },
            tween(Motion.MEDIUM_MS),
            label = "amountColor",
        )
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.shakeOnTrigger(shake)) {
            Text(
                text = state.expression.ifEmpty { "0" },
                style = MaterialTheme.typography.displayMedium.merge(AmountStyle),
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .popOnChange(state.expression, peak = 1.04f),
                textAlign = TextAlign.End,
            )
            Spacer(Modifier.width(8.dp))
            if (state.canForeign) {
                EntryCurrencyChip(state, accent, onCurrency, Modifier.padding(bottom = 6.dp))
            } else {
                Text(
                    state.currency,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
        AnimatedVisibility(
            visible = !keypadVisible,
            enter = expandVertically(tween(Motion.MEDIUM_MS)) + fadeIn(),
            exit = shrinkVertically(tween(Motion.SHORT_MS)) + fadeOut(tween(Motion.SHORT_MS)),
        ) {
            Row(
                Modifier
                    .padding(top = 2.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Keyboard, null, tint = accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Изменить сумму", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        val preview = state.preview
        val converted = state.convertedMinor
        val hint: Pair<String, Color>? = when {
            amountError -> state.errors.first {
                it == ValidationError.AMOUNT_REQUIRED || it == ValidationError.AMOUNT_TOO_LARGE
            }.message to MaterialTheme.colorScheme.error
            state.hasOperators && preview != null -> {
                val minor = Money.toMinor(preview, state.entryCurrency)
                (if (minor != null) "= " + Money.format(minor, state.entryCurrency) else "= слишком большое число") to
                    MaterialTheme.colorScheme.onSurfaceVariant
            }
            // Покупка в другой валюте — сразу видно, сколько это в валюте счёта.
            state.showsDebit && state.debitText.isNotBlank() ->
                (Money.parse(state.debitText, state.currency)?.let { "Со счёта: " + Money.format(it, state.currency) } ?: "Проверьте сумму списания") to
                    MaterialTheme.colorScheme.onSurfaceVariant
            state.isForeign && converted != null ->
                "≈ ${Money.format(converted, state.currency)} по курсу" + (if (state.repeat != null) " сегодня" else "") to
                    MaterialTheme.colorScheme.onSurfaceVariant
            state.isForeign && state.foreignMinor != null && state.repeat != null ->
                "Курса ${state.entryCurrency} пока нет — при проведении спросим сумму" to MaterialTheme.colorScheme.onSurfaceVariant
            state.isForeign && state.foreignMinor != null ->
                "Нет курса ${state.entryCurrency} — укажите сумму списания" to MaterialTheme.colorScheme.error
            else -> null
        }
        AnimatedVisibility(
            visible = hint != null,
            enter = expandVertically(tween(Motion.SHORT_MS)) + fadeIn(),
            exit = shrinkVertically(tween(Motion.SHORT_MS)) + fadeOut(),
        ) {
            val (text, hintColor) = hint ?: ("" to Color.Unspecified)
            Text(text, color = hintColor, style = MaterialTheme.typography.titleMedium.merge(AmountStyle))
        }
    }
}

/** «BYN ▾» у суммы: валюта покупки. Валюта счёта — по умолчанию; другая — пересчёт по курсу. */
@Composable
private fun EntryCurrencyChip(state: EditorUiState, accent: Color, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    var pickOther by remember { mutableStateOf(false) }
    val foreign = state.isForeign
    Box(modifier) {
        Row(
            Modifier
                .heightIn(min = 36.dp)
                .clip(CircleShape)
                .background(if (foreign) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh)
                .bounceClick(pressedScale = 0.94f, role = androidx.compose.ui.semantics.Role.DropdownList, onClickLabel = "Валюта суммы") { open = true }
                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                state.entryCurrency,
                style = MaterialTheme.typography.titleMedium,
                color = if (foreign) accent.readableOn(MaterialTheme.colorScheme.background) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                "Валюта покупки",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            val options = app.fintrack.domain.model.displayCurrencyOptions(state.currency, state.accounts.map { it.account.currencyCode }) +
                listOfNotNull(state.foreignCurrency)
            options.distinct().forEach { c ->
                DropdownMenuItem(
                    text = { Text(if (c == state.currency) "$c — валюта счёта" else app.fintrack.ui.components.currencyTitle(c)) },
                    trailingIcon = { if (c == state.entryCurrency) Icon(Icons.Rounded.CheckCircle, null) },
                    onClick = { open = false; onSelect(c) },
                )
            }
            DropdownMenuItem(text = { Text("Другая валюта…") }, onClick = { open = false; pickOther = true })
        }
    }
    if (pickOther) {
        app.fintrack.ui.components.CurrencyPickerDialog(
            selected = state.entryCurrency,
            onSelect = { onSelect(it); pickOther = false },
            onDismiss = { pickOther = false },
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ErrorText(errors: Set<ValidationError>, vararg which: ValidationError) {
    val e = which.firstOrNull { it in errors }
    AnimatedVisibility(
        visible = e != null,
        enter = expandVertically(tween(Motion.SHORT_MS)) + fadeIn(),
        exit = shrinkVertically(tween(Motion.SHORT_MS)) + fadeOut(),
    ) {
        Text(
            e?.message ?: "",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
    }
}

/** Карточки счетов: выбранная плавно окрашивается, получает рамку, галочку и «отскок». */
@Composable
private fun AccountSelector(
    accounts: List<AccountWithBalance>,
    selectedId: String?,
    hidden: Boolean,
    accent: Color,
    onSelect: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(accounts, key = { it.account.id }) { item ->
            val acc = item.account
            val selected = acc.id == selectedId
            val shape = MaterialTheme.shapes.medium
            val bg by animateColorAsState(
                if (selected) accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainer,
                tween(Motion.MEDIUM_MS),
                label = "accBg",
            )
            val borderColor by animateColorAsState(
                if (selected) accent else MaterialTheme.colorScheme.outlineVariant,
                tween(Motion.MEDIUM_MS),
                label = "accBorder",
            )
            val scale by animateFloatAsState(if (selected) 1.03f else 1f, Motion.bouncy(), label = "accScale")
            Surface(
                shape = shape,
                color = bg,
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .border(if (selected) 2.dp else 1.dp, borderColor, shape)
                    .clip(shape)
                    .bounceClick(pressedScale = 0.95f, role = Role.RadioButton) {
                        if (!selected) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(acc.id)
                    },
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        IconBadge(AccountIcons.get(acc.type), Color(acc.color), size = 36.dp, selected = selected)
                        androidx.compose.animation.AnimatedVisibility(
                            visible = selected,
                            enter = scaleIn(Motion.bouncy()) + fadeIn(),
                            exit = scaleOut(tween(Motion.SHORT_MS)) + fadeOut(),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .offset(x = 5.dp, y = 5.dp),
                        ) {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                null,
                                tint = accent,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainer),
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(acc.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(
                            if (hidden) Money.masked(acc.currencyCode) else Money.format(item.balanceMinor, acc.currencyCode),
                            style = MaterialTheme.typography.bodySmall.merge(AmountStyle),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
    if (accounts.isEmpty()) {
        Text(
            "Нет счетов. Создайте счёт на вкладке «Счета».",
            modifier = Modifier.padding(horizontal = 20.dp),
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun CategoryGrid(state: EditorUiState, accent: Color, onSelect: (String) -> Unit, onAdd: () -> Unit) {
    val selectedParent = state.selectedParentId
    val haptic = LocalHapticFeedback.current
    FlowRow(
        modifier = Modifier.padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        state.rootCategories.forEach { c ->
            CategoryCell(
                name = c.name,
                icon = CategoryIcons.get(c.iconKey),
                color = Color(c.color),
                accent = accent,
                selected = c.id == selectedParent,
                onClick = {
                    if (c.id != selectedParent) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSelect(c.id)
                },
            )
        }
        CategoryCell(
            name = "Новая",
            icon = Icons.Rounded.Add,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            accent = accent,
            selected = false,
            onClick = onAdd,
        )
    }
    val parent = selectedParent?.let { id -> state.categories.firstOrNull { it.id == id } }
    val children = parent?.let { state.childrenOf(it.id) }.orEmpty()
    AnimatedVisibility(
        visible = parent != null && children.isNotEmpty(),
        enter = expandVertically(tween(Motion.MEDIUM_MS)) + fadeIn(tween(Motion.MEDIUM_MS)),
        exit = shrinkVertically(tween(Motion.SHORT_MS)) + fadeOut(tween(Motion.SHORT_MS)),
    ) {
        Column {
            FieldLabel("Подкатегория")
            FlowRow(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (parent != null) {
                    FilterChip(
                        selected = state.categoryId == parent.id,
                        onClick = { onSelect(parent.id) },
                        label = { Text("Без подкатегории") },
                        shape = MaterialTheme.shapes.small,
                    )
                }
                children.forEach { child ->
                    FilterChip(
                        selected = state.categoryId == child.id,
                        onClick = { onSelect(child.id) },
                        label = { Text(child.name) },
                        shape = MaterialTheme.shapes.small,
                    )
                }
            }
        }
    }
}

/** Ячейка категории: иконка заливается градиентом и подпрыгивает, подпись окрашивается. */
@Composable
private fun CategoryCell(
    name: String,
    icon: ImageVector,
    color: Color,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val labelColor by animateColorAsState(
        if (selected) accent else MaterialTheme.colorScheme.onSurface,
        tween(Motion.MEDIUM_MS),
        label = "catLabel",
    )
    Column(
        modifier = Modifier
            .width(82.dp)
            .clip(MaterialTheme.shapes.medium)
            .bounceClick(pressedScale = 0.9f, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(icon, color, size = 50.dp, selected = selected)
        Spacer(Modifier.height(6.dp))
        FitText(
            name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = labelColor,
            textAlign = TextAlign.Center,
            maxLines = 2,
            minLines = 2,
            minFontSize = 9.sp,
        )
    }
}

/** Клавиатура-калькулятор: клавиши «проседают» при нажатии. Долгое нажатие ⌫ очищает поле. */
@Composable
private fun Keypad(onKey: (KeypadKey) -> Unit, onCollapse: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val rows = listOf(
        listOf("7", "8", "9", KeypadInput.DIVIDE.toString()),
        listOf("4", "5", "6", KeypadInput.TIMES.toString()),
        listOf("1", "2", "3", KeypadInput.MINUS.toString()),
        listOf(",", "0", "⌫", KeypadInput.PLUS.toString()),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            // Свайп вниз по клавиатуре сворачивает её
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(onDragStart = { total = 0f }) { _, dragAmount ->
                    total += dragAmount
                    if (total > 36.dp.toPx()) onCollapse()
                }
            }
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Ручка и кнопка «свернуть» — видимая альтернатива жесту
        Box(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clickable(onClickLabel = "Свернуть клавиатуру", onClick = onCollapse),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
            )
            IconButton(onClick = onCollapse, modifier = Modifier.align(Alignment.CenterEnd)) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Свернуть клавиатуру")
            }
        }
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { label ->
                    val isOperator = label.length == 1 && label[0] in "+−×÷"
                    val key: KeypadKey = when {
                        label == "⌫" -> KeypadKey.Backspace
                        label == "," -> KeypadKey.Comma
                        isOperator -> KeypadKey.Operator(label[0])
                        else -> KeypadKey.Digit(label[0])
                    }
                    val source = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .pressScale(source, 0.9f)
                            .clip(MaterialTheme.shapes.small)
                            .background(
                                if (isOperator) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                            )
                            .combinedClickable(
                                interactionSource = source,
                                indication = LocalIndication.current,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onKey(key)
                                },
                                onLongClick = if (key == KeypadKey.Backspace) {
                                    {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onKey(KeypadKey.Clear)
                                    }
                                } else {
                                    null
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (key == KeypadKey.Backspace) {
                            Icon(Icons.Rounded.Backspace, "Стереть", modifier = Modifier.size(IconSize.md))
                        } else {
                            Text(
                                label,
                                fontSize = 22.sp,
                                fontWeight = if (isOperator) FontWeight.SemiBold else FontWeight.Medium,
                                fontFamily = MaterialTheme.typography.titleLarge.fontFamily,
                                color = if (isOperator) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}
