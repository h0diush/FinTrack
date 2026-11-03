@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.fintrack.ui.loans

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Percent
import androidx.compose.material.icons.rounded.RequestQuote
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fintrack.domain.logic.DepositCalculator
import app.fintrack.domain.logic.Loan
import app.fintrack.domain.logic.LoanCalculator
import app.fintrack.domain.logic.LoanRow
import app.fintrack.domain.logic.Money
import app.fintrack.domain.model.AccountWithBalance
import app.fintrack.ui.components.AppCard
import app.fintrack.ui.components.CountUpAmount
import app.fintrack.ui.components.EmptyState
import app.fintrack.ui.components.FitText
import app.fintrack.ui.components.SectionTitle
import app.fintrack.ui.components.SlidingSegmented
import app.fintrack.ui.components.StaggeredAppear
import app.fintrack.ui.components.TransactionItem
import app.fintrack.ui.components.contentOn
import app.fintrack.ui.components.lighten
import app.fintrack.ui.components.popOnChange
import app.fintrack.ui.piggy.ConfettiBurst
import app.fintrack.ui.piggy.ProgressRing
import app.fintrack.ui.piggy.readableOn
import app.fintrack.ui.theme.FinTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Сколько строк графика видно до «Показать весь график». */
private const val SCHEDULE_PREVIEW = 6

@Composable
fun LoanDetailScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onOpenTransaction: (String) -> Unit,
    onAddAccount: () -> Unit,
    vm: LoanDetailViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var paySheet by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confetti by remember { mutableIntStateOf(0) }
    var left by remember { mutableStateOf(false) }
    val leave = {
        if (!left) {
            left = true
            onBack()
        }
    }

    LaunchedEffect(Unit) {
        vm.eventFlow.collect { e ->
            when (e) {
                is LoanEvent.Message -> scope.launch { snackbar.showSnackbar(e.text) }
                LoanEvent.PaidOff -> {
                    confetti++
                    scope.launch { snackbar.showSnackbar("Долг погашен полностью! Поздравляем!") }
                }
                LoanEvent.Closed -> leave()
            }
        }
    }
    LaunchedEffect(state.loading, state.loan) {
        if (!state.loading && state.loan == null) leave()
    }

    val loan = state.loan
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(loan?.name ?: "Кредит", maxLines = 1) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
                    actions = {
                        if (loan != null) {
                            IconButton(onClick = { onEdit(loan.accountId) }) { Icon(Icons.Rounded.Edit, "Изменить кредит") }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Ещё") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    if (loan.isArchived) {
                                        DropdownMenuItem(
                                            text = { Text("Вернуть из архива") },
                                            leadingIcon = { Icon(Icons.Rounded.Unarchive, null) },
                                            onClick = { menu = false; vm.restore() },
                                        )
                                    } else {
                                        DropdownMenuItem(
                                            text = { Text("Закрыть кредит") },
                                            leadingIcon = { Icon(Icons.Rounded.Archive, null) },
                                            onClick = { menu = false; confirmClose = true },
                                        )
                                    }
                                    if (state.canDelete) {
                                        DropdownMenuItem(
                                            text = { Text("Удалить") },
                                            leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                                            onClick = { menu = false; confirmDelete = true },
                                        )
                                    }
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            if (loan == null) return@Scaffold
            val accent = Color(loan.color)
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { StaggeredAppear(0) { Hero(loan, state.hideAmounts) } }
                item {
                    StaggeredAppear(1) {
                        NextPaymentCard(loan, state.upcoming.firstOrNull(), state.hideAmounts, accent) { paySheet = true }
                    }
                }
                item { StaggeredAppear(2) { CostCard(loan, state, accent) } }
                if (state.upcoming.isNotEmpty()) {
                    item { StaggeredAppear(3) { ScheduleCard(loan, state.upcoming, state.hideAmounts) } }
                }
                item {
                    StaggeredAppear(4) {
                        Column {
                            SectionTitle("Платежи")
                            if (state.history.isEmpty()) {
                                EmptyState("Платежей пока не было.\nНажмите «Внести платёж», когда заплатите банку.", icon = Icons.Rounded.Payments)
                            } else {
                                AppCard(Modifier.padding(horizontal = 16.dp)) {
                                    Column(Modifier.padding(vertical = 6.dp)) {
                                        state.history.forEachIndexed { i, tx ->
                                            if (i > 0) {
                                                HorizontalDivider(
                                                    modifier = Modifier.padding(start = 74.dp, end = 16.dp),
                                                    color = MaterialTheme.colorScheme.outlineVariant,
                                                )
                                            }
                                            TransactionItem(
                                                item = tx,
                                                hidden = state.hideAmounts,
                                                showDate = true,
                                                onClick = { onOpenTransaction(tx.transaction.id) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        ConfettiBurst(
            trigger = confetti,
            colors = listOf(Color(loan?.color ?: 0xFF059669L), Color(0xFFF59E0B), Color(0xFF10B981), Color(0xFF3B82F6), Color(0xFFEC4899)),
        )
    }

    if (paySheet && loan != null) {
        PaySheet(
            loan = loan,
            accounts = state.accounts,
            defaultAccountId = state.defaultAccountId,
            onDismiss = { paySheet = false },
            onSubmit = vm::pay,
            onAddAccount = { paySheet = false; onAddAccount() },
        )
    }
    if (confirmClose && loan != null) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("Закрыть кредит?") },
            text = {
                Text(
                    if (loan.isPaidOff) {
                        "Кредит уйдёт в архив раздела «Кредиты». История платежей сохранится."
                    } else {
                        "Остаток долга ${Money.format(loan.debtMinor, loan.currency)} останется на счёте кредита. " +
                            "Закрывайте, если долг погашен вне приложения. История платежей сохранится."
                    },
                )
            },
            confirmButton = { TextButton(onClick = { confirmClose = false; vm.close() }) { Text("Закрыть") } },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Отмена") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить кредит?") },
            text = { Text("Кредит и запись о получении суммы будут удалены. Действие нельзя отменить.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

/** Шапка: кольцо «выплачено %», остаток долга со счётчиком, ставка, платёж, срок. */
@Composable
private fun Hero(loan: Loan, hidden: Boolean) {
    val base = Color(loan.color)
    val dark = lerp(base, Color.Black, 0.30f)
    val on = lerp(base, dark, 0.5f).contentOn()
    val muted = on.copy(alpha = 0.78f)
    val percent = (loan.paidFraction * 100).toInt()
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(Brush.linearGradient(listOf(base.lighten(0.08f), dark)))
            .drawBehind {
                drawCircle(Color.White.copy(alpha = 0.10f), radius = size.width * 0.40f, center = Offset(size.width * 0.92f, size.height * 0.06f))
                drawCircle(Color.White.copy(alpha = 0.06f), radius = size.width * 0.28f, center = Offset(size.width * 0.04f, size.height * 1.02f))
            }
            .padding(vertical = 24.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ProgressRing(fraction = loan.paidFraction, color = on, trackColor = on.copy(alpha = 0.22f), size = 148.dp, stroke = 12.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(LoanStyle.icon(loan.kind), null, tint = on, modifier = Modifier.size(30.dp))
                AnimatedContent(
                    targetState = percent,
                    transitionSpec = { fadeIn(tween(220)).togetherWith(fadeOut(tween(120))) },
                    label = "paidPercent",
                ) { value ->
                    Text("$value %", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = on, modifier = Modifier.popOnChange(value))
                }
                Text("выплачено", style = MaterialTheme.typography.labelSmall, color = muted)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(if (loan.isPaidOff) "Долг погашен" else "Осталось выплатить", style = MaterialTheme.typography.labelLarge, color = muted)
        CountUpAmount(
            minor = loan.debtMinor,
            currency = loan.currency,
            hidden = hidden,
            style = MaterialTheme.typography.displaySmall,
            color = on,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "из " + (if (hidden) Money.masked(loan.currency) else Money.format(loan.principalMinor, loan.currency)) + " · " + loan.kind.title.lowercase(),
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        val chips = listOf(
            Triple(Icons.Rounded.Percent, "Ставка", if (loan.rateBp == 0L) "0 %" else "${DepositCalculator.formatRate(loan.rateBp)} %"),
            Triple(Icons.Rounded.CalendarMonth, "Платежей", "${loan.paidCount} из ${loan.termMonths}"),
            Triple(Icons.Rounded.Event, "До", loan.endDate.format(LOAN_DATE)),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            chips.forEach { (icon, label, value) ->
                Column(
                    Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.medium)
                        .background(Color.White.copy(alpha = 0.14f))
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, tint = muted, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        FitText(label, style = MaterialTheme.typography.labelSmall, color = muted, minFontSize = 9.sp, modifier = Modifier.weight(1f, fill = false))
                    }
                    Spacer(Modifier.height(2.dp))
                    FitText(value, style = MaterialTheme.typography.labelLarge, color = on, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Ближайший платёж: дата, статус, сумма и из чего она состоит; кнопка оплаты. */
@Composable
private fun NextPaymentCard(loan: Loan, next: LoanRow?, hidden: Boolean, accent: Color, onPay: () -> Unit) {
    val fin = FinTheme.colors
    fun m(v: Long) = if (hidden) Money.masked(loan.currency) else Money.format(v, loan.currency)
    AppCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if (next == null) {
                Text("Кредит погашен", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Все платежи внесены. Закройте кредит в меню «⋮», чтобы убрать его в архив.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Платёж №${next.index} · ${next.date.format(LOAN_DAY)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(m(next.paymentMinor), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                StatusPill(loanStatus(loan))
            }
            Spacer(Modifier.height(12.dp))
            SplitBar(next.principalMinor, next.interestMinor, accent, fin.expense.copy(alpha = 0.75f))
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Legend(accent, "Долг ${m(next.principalMinor)}")
                Legend(fin.expense.copy(alpha = 0.75f), "Проценты ${m(next.interestMinor)}")
            }
            if (!loan.isArchived) {
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onPay,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = accent.contentOn()),
                ) {
                    Icon(Icons.Rounded.Payments, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Внести платёж")
                }
            }
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Во что обходится кредит: уплаченные и оставшиеся проценты, переплата. */
@Composable
private fun CostCard(loan: Loan, state: LoanDetailUiState, accent: Color) {
    val fin = FinTheme.colors
    val h = state.hideAmounts
    fun m(v: Long) = if (h) Money.masked(loan.currency) else Money.format(v, loan.currency)
    val remainingInterest = state.remaining?.interestMinor ?: 0L
    val overpay = loan.interestPaidMinor + remainingInterest
    AppCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.RequestQuote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Стоимость кредита", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(12.dp))
            SplitBar(loan.principalMinor, overpay, accent, fin.expense.copy(alpha = 0.75f))
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Legend(accent, "Сумма кредита")
                Legend(fin.expense.copy(alpha = 0.75f), "Проценты")
            }
            Spacer(Modifier.height(12.dp))
            InfoLine("Проценты уже уплачены", m(loan.interestPaidMinor))
            InfoLine("Осталось процентов по графику", m(remainingInterest))
            InfoLine("Переплата всего", m(overpay), bold = true)
            if (loan.rateBp > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Досрочные платежи сразу уменьшают долг — проценты дальше начисляются на меньшую сумму.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium)
    }
}

/** Оставшийся график: первые строки, остальное — по кнопке. */
@Composable
private fun ScheduleCard(loan: Loan, rows: List<LoanRow>, hidden: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    fun m(v: Long) = if (hidden) Money.masked(loan.currency) else Money.format(v, loan.currency)
    Column {
        SectionTitle("График платежей")
        AppCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth().animateContentSize()) {
            Column(Modifier.padding(vertical = 6.dp)) {
                val shown = if (expanded) rows else rows.take(SCHEDULE_PREVIEW)
                shown.forEachIndexed { i, r ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (i == 0) Color(loan.color).copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            FitText(
                                r.index.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (i == 0) Color(loan.color).readableOn(MaterialTheme.colorScheme.surfaceContainer) else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.date.format(LOAN_DATE), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "долг ${m(r.principalMinor)} · проценты ${m(r.interestMinor)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(m(r.paymentMinor), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("ост. ${m(r.remainingMinor)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (rows.size > SCHEDULE_PREVIEW) {
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        Text(if (expanded) "Свернуть" else "Показать весь график (${rows.size})")
                    }
                }
            }
        }
    }
}

/** Платёж: по графику или досрочно, счёт списания, разбивка на проценты и долг. */
@Composable
private fun PaySheet(
    loan: Loan,
    accounts: List<AccountWithBalance>,
    defaultAccountId: String?,
    onDismiss: () -> Unit,
    onSubmit: (accountId: String?, amountText: String, early: Boolean) -> String?,
    onAddAccount: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val cur = loan.currency
    val scheduled = loan.nextPayment()?.paymentMinor ?: 0L
    var early by rememberSaveable { mutableStateOf(false) }
    var accountId by rememberSaveable { mutableStateOf(defaultAccountId) }
    var text by rememberSaveable { mutableStateOf(if (scheduled > 0) Money.toInput(scheduled, cur) else "") }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val accent = Color(loan.color)
    val fin = FinTheme.colors

    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    fun submit() {
        val e = onSubmit(accountId, text, early)
        if (e == null) close() else error = e
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(loan.name, style = MaterialTheme.typography.titleLarge)
            SlidingSegmented(
                options = listOf("По графику", "Досрочно"),
                selectedIndex = if (early) 1 else 0,
                onSelect = {
                    early = it == 1
                    error = null
                    text = if (early) "" else Money.toInput(scheduled, cur)
                },
                pillColors = listOf(accent, fin.transfer),
            )

            val amount = Money.parse(text, cur)
            val split = amount?.let { LoanCalculator.split(loan.debtMinor, loan.rateBp, it, early) }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(16); error = null },
                label = { Text("Сумма платежа") },
                suffix = { Text(cur) },
                isError = error != null,
                supportingText = {
                    Text(
                        when {
                            error != null -> error!!
                            early -> "Вся сумма пойдёт в погашение долга, следующие платежи уменьшатся"
                            else -> "Сначала гасятся проценты за месяц, остальное — долг"
                        },
                    )
                },
                textStyle = MaterialTheme.typography.headlineSmall,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            LaunchedEffect(Unit) {
                delay(300)
                runCatching { focus.requestFocus() }
            }

            val payoff = LoanCalculator.payoffAmount(loan.debtMinor, loan.rateBp, early)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!early && scheduled > 0) {
                    SuggestionChip(onClick = { text = Money.toInput(scheduled, cur); error = null }, label = { Text("По графику · ${Money.format(scheduled, cur, withCode = false)}") })
                }
                SuggestionChip(onClick = { text = Money.toInput(payoff, cur); error = null }, label = { Text("Весь долг · ${Money.format(payoff, cur, withCode = false)}") })
            }

            // Разбивка платежа обновляется на лету
            AnimatedContent(
                targetState = split,
                transitionSpec = { fadeIn(tween(180)).togetherWith(fadeOut(tween(120))) },
                label = "split",
            ) { s ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(14.dp),
                ) {
                    if (s == null) {
                        Text(
                            if (amount != null && amount > payoff) "Больше, чем нужно: для полного погашения хватит ${Money.format(payoff, cur)}" else "Введите сумму",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        SplitBar(s.principalMinor, s.interestMinor, accent, fin.expense.copy(alpha = 0.75f))
                        Spacer(Modifier.height(8.dp))
                        InfoLine("В погашение долга", Money.format(s.principalMinor, cur))
                        InfoLine("Проценты (расход «Кредиты»)", Money.format(s.interestMinor, cur))
                        InfoLine("Долг после платежа", Money.format(loan.debtMinor - s.principalMinor, cur), bold = true)
                    }
                }
            }

            Text("Со счёта", style = MaterialTheme.typography.titleSmall)
            if (accounts.isEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Нет счетов в валюте $cur. Платёж списывается со счёта в той же валюте.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = onAddAccount) { Text("Добавить счёт") }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    accounts.forEach { a ->
                        LoanAccountChoice(a, selected = a.account.id == accountId, accent = accent) {
                            accountId = a.account.id
                            error = null
                        }
                    }
                }
            }

            Button(
                onClick = { submit() },
                enabled = accounts.isNotEmpty() && split != null,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = accent.contentOn()),
            ) {
                Text("Заплатить" + (amount?.takeIf { it > 0 }?.let { " " + Money.format(it, cur) } ?: ""))
            }
        }
    }
}
