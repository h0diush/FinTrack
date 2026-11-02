@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.fintrack.ui.piggy

import androidx.compose.ui.unit.sp
import app.fintrack.ui.components.FitText
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.TrendingUp
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fintrack.domain.logic.Money
import app.fintrack.domain.logic.PiggyBank
import app.fintrack.domain.logic.PiggyProgress
import app.fintrack.domain.logic.ruPlural
import app.fintrack.domain.model.AccountWithBalance
import app.fintrack.ui.components.AccountIcons
import app.fintrack.ui.components.AppCard
import app.fintrack.ui.components.CategoryIcons
import app.fintrack.ui.components.CountUpAmount
import app.fintrack.ui.components.EmptyState
import app.fintrack.ui.components.IconBadge
import app.fintrack.ui.components.SectionTitle
import app.fintrack.ui.components.SlidingSegmented
import app.fintrack.ui.components.StaggeredAppear
import app.fintrack.ui.components.TransactionItem
import app.fintrack.ui.components.contentOn
import app.fintrack.ui.components.lighten
import app.fintrack.ui.components.popOnChange
import app.fintrack.ui.theme.FinTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale

private val RU: Locale = Locale.forLanguageTag("ru")
private val DEADLINE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", RU)

@Composable
fun PiggyDetailScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onOpenTransaction: (String) -> Unit,
    onAddAccount: () -> Unit,
    vm: PiggyDetailViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var sheetWithdraw by remember { mutableStateOf<Boolean?>(null) }
    var confirmBreak by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confetti by remember { mutableIntStateOf(0) }
    // Закрытие может прийти и событием, и из состояния — уходим назад только один раз.
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
                is PiggyEvent.Message -> scope.launch { snackbar.showSnackbar(e.text) }
                is PiggyEvent.Milestone -> {
                    confetti++
                    scope.launch {
                        snackbar.showSnackbar(if (e.percent >= 100) "Цель достигнута! Поздравляем!" else "Пройдено ${e.percent} % пути — так держать!")
                    }
                }
                PiggyEvent.Closed -> leave()
            }
        }
    }
    // Копилку разбили на другом экране — закрываемся.
    LaunchedEffect(state.loading, state.piggy) {
        if (!state.loading && state.piggy == null) leave()
    }

    val p = state.piggy
    val pr = state.progress
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(p?.name ?: "Копилка", maxLines = 1) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
                    actions = {
                        if (p != null) {
                            IconButton(onClick = { onEdit(p.accountId) }) { Icon(Icons.Rounded.Edit, "Изменить копилку") }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Ещё") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Разбить копилку") },
                                        leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                                        onClick = { menu = false; confirmBreak = true },
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            if (p == null || pr == null) return@Scaffold
            val accent = Color(p.color)
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { StaggeredAppear(0) { Hero(p, pr, state.hideAmounts) } }
                item {
                    StaggeredAppear(1) {
                        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { sheetWithdraw = false },
                                modifier = Modifier.weight(1f).height(52.dp),
                                shape = MaterialTheme.shapes.medium,
                                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = accent.contentOn()),
                            ) {
                                Icon(Icons.Rounded.Savings, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Пополнить")
                            }
                            OutlinedButton(
                                onClick = { sheetWithdraw = true },
                                enabled = p.balanceMinor > 0,
                                modifier = Modifier.weight(1f).height(52.dp),
                                shape = MaterialTheme.shapes.medium,
                            ) { Text("Забрать") }
                        }
                    }
                }
                item {
                    StaggeredAppear(2) {
                        MotivationTicker(state.phrases, accent.readableOn(MaterialTheme.colorScheme.background), Modifier.padding(horizontal = 16.dp))
                    }
                }
                if (pr.hasTarget) {
                    item {
                        StaggeredAppear(3) {
                            Column(Modifier.padding(horizontal = 16.dp)) {
                                Text("Этапы", style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(8.dp))
                                MilestonesRow(pr.percent, accent)
                            }
                        }
                    }
                }
                item {
                    StaggeredAppear(4) {
                        Column {
                            SectionTitle("История")
                            if (state.history.isEmpty()) {
                                EmptyState("Пополнений пока не было.\nНачните с любой суммы — даже небольшой.", icon = Icons.Rounded.Savings)
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
            colors = listOf(Color(p?.color ?: 0xFFEC4899L), Color(0xFFF59E0B), Color(0xFF10B981), Color(0xFF3B82F6), Color(0xFFEC4899)),
        )
    }

    val mode = sheetWithdraw
    if (mode != null && p != null && pr != null) {
        MoveMoneySheet(
            piggy = p,
            progress = pr,
            withdrawInitially = mode,
            accounts = state.accounts,
            defaultAccountId = state.defaultAccountId,
            suggestions = state.suggestions,
            onDismiss = { sheetWithdraw = null },
            onSubmit = { withdraw, accountId, text -> vm.move(withdraw, accountId, text) },
            onAddAccount = { sheetWithdraw = null; onAddAccount() },
        )
    }

    if (confirmBreak && p != null) {
        BreakDialog(
            piggy = p,
            accounts = state.accounts,
            defaultAccountId = state.defaultAccountId,
            onDismiss = { confirmBreak = false },
            onConfirm = { to -> confirmBreak = false; vm.breakPiggy(to) },
        )
    }
}

/** Шапка: кольцо прогресса с иконкой, сумма со счётчиком, остаток и срок. */
@Composable
private fun Hero(p: PiggyBank, pr: PiggyProgress, hidden: Boolean) {
    val base = Color(p.color)
    val dark = lerp(base, Color.Black, 0.28f)
    val on = lerp(base, dark, 0.5f).contentOn()
    val muted = on.copy(alpha = 0.78f)
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
        ProgressRing(
            fraction = if (pr.hasTarget) pr.fraction else 1f,
            color = on,
            trackColor = on.copy(alpha = 0.22f),
            size = 156.dp,
            stroke = 12.dp,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(CategoryIcons.get(p.iconKey), null, tint = on, modifier = Modifier.size(34.dp))
                if (pr.hasTarget) {
                    AnimatedContent(
                        targetState = pr.percent,
                        transitionSpec = { fadeIn(tween(220)).togetherWith(fadeOut(tween(120))) },
                        label = "percent",
                    ) { value ->
                        Text(
                            "$value %",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = on,
                            modifier = Modifier.popOnChange(value),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(if (pr.reached) "Цель достигнута" else "Накоплено", style = MaterialTheme.typography.labelLarge, color = muted)
        CountUpAmount(
            minor = p.balanceMinor,
            currency = p.currency,
            hidden = hidden,
            style = MaterialTheme.typography.displaySmall,
            color = on,
            fontWeight = FontWeight.Bold,
        )
        if (pr.hasTarget) {
            Text(
                "из " + if (hidden) Money.masked(p.currency) else Money.format(p.targetMinor, p.currency),
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )
        } else {
            Text("Цель не задана — копите сколько хочется", style = MaterialTheme.typography.bodyMedium, color = muted, textAlign = TextAlign.Center)
        }

        val chips = buildList {
            if (pr.hasTarget && !pr.reached) {
                add(Triple(Icons.Rounded.Savings, "Осталось", if (hidden) Money.masked(p.currency) else Money.format(pr.remainingMinor, p.currency)))
            }
            val perMonth = pr.perMonthMinor
            if (perMonth != null) {
                add(Triple(Icons.Rounded.TrendingUp, "В месяц", if (hidden) Money.masked(p.currency) else Money.format(perMonth, p.currency)))
            }
            val d = p.deadline
            val days = pr.daysLeft
            if (d != null && days != null) {
                val left = when {
                    pr.reached -> d.format(DEADLINE)
                    days > 0 -> "$days ${ruPlural(days, "день", "дня", "дней")}"
                    days == 0L -> "сегодня"
                    else -> "срок прошёл"
                }
                add(Triple(Icons.Rounded.Event, if (pr.reached || days <= 0) "Срок" else "До ${d.format(DEADLINE)}", left))
            }
        }
        if (chips.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
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
}

/** Пополнение или снятие: выбор счёта, сумма, быстрые суммы. */
@Composable
private fun MoveMoneySheet(
    piggy: PiggyBank,
    progress: PiggyProgress,
    withdrawInitially: Boolean,
    accounts: List<AccountWithBalance>,
    defaultAccountId: String?,
    suggestions: List<Long>,
    onDismiss: () -> Unit,
    onSubmit: (withdraw: Boolean, accountId: String?, amountText: String) -> String?,
    onAddAccount: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var withdraw by rememberSaveable { mutableStateOf(withdrawInitially) }
    var accountId by rememberSaveable { mutableStateOf(defaultAccountId) }
    var text by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val fin = FinTheme.colors
    val accent = Color(piggy.color)
    val cur = piggy.currency

    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    fun submit() {
        val e = onSubmit(withdraw, accountId, text)
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
            Text(piggy.name, style = MaterialTheme.typography.titleLarge)
            SlidingSegmented(
                options = listOf("Пополнить", "Забрать"),
                selectedIndex = if (withdraw) 1 else 0,
                onSelect = { withdraw = it == 1; error = null },
                pillColors = listOf(accent, fin.neutral),
            )

            val amount = Money.parse(text, cur)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(16); error = null },
                label = { Text("Сумма") },
                suffix = { Text(cur) },
                isError = error != null,
                supportingText = {
                    val selected = accounts.firstOrNull { it.account.id == accountId }
                    val hint = when {
                        error != null -> error!!
                        withdraw -> "В копилке ${Money.format(piggy.balanceMinor.coerceAtLeast(0), cur)}"
                        selected != null && amount != null && amount > selected.balanceMinor ->
                            "На счёте «${selected.account.name}» станет ${Money.format(selected.balanceMinor - amount, cur)}"
                        progress.hasTarget && !progress.reached && amount != null && amount >= progress.remainingMinor ->
                            "Этого хватит, чтобы достичь цели"
                        else -> " "
                    }
                    Text(hint)
                },
                textStyle = MaterialTheme.typography.headlineSmall,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
            LaunchedEffect(Unit) {
                delay(300)
                runCatching { focus.requestFocus() }
            }

            val quick: List<Pair<String, Long>> = if (withdraw) {
                val b = piggy.balanceMinor
                listOfNotNull(
                    if (b > 1) "Половину" to b / 2 else null,
                    if (b > 0) "Всё" to b else null,
                )
            } else {
                suggestions.map { v ->
                    val label = when (v) {
                        progress.perMonthMinor -> "${Money.format(v, cur, withCode = false)} · в месяц"
                        progress.remainingMinor -> "${Money.format(v, cur, withCode = false)} · до цели"
                        else -> Money.format(v, cur, withCode = false)
                    }
                    label to v
                }
            }
            if (quick.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    quick.forEach { (label, v) ->
                        SuggestionChip(
                            onClick = { text = Money.toInput(v, cur); error = null },
                            label = { Text(label) },
                        )
                    }
                }
            }

            Text(if (withdraw) "На счёт" else "Со счёта", style = MaterialTheme.typography.titleSmall)
            if (accounts.isEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Нет счетов в валюте $cur. Копилка пополняется переводом со счёта в той же валюте.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = onAddAccount) { Text("Добавить счёт") }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    accounts.forEach { a ->
                        AccountChoice(a, selected = a.account.id == accountId, accent = accent) {
                            accountId = a.account.id
                            error = null
                        }
                    }
                }
            }

            Button(
                onClick = { submit() },
                enabled = accounts.isNotEmpty() && amount != null && amount > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = accent.contentOn()),
            ) {
                val sum = amount?.takeIf { it > 0 }?.let { " " + Money.format(it, cur) } ?: ""
                Text((if (withdraw) "Забрать" else "Пополнить") + sum)
            }
        }
    }
}

@Composable
private fun AccountChoice(item: AccountWithBalance, selected: Boolean, accent: Color, onClick: () -> Unit) {
    val a = item.account
    val border = if (selected) accent else MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .border(if (selected) 2.dp else 1.dp, border, MaterialTheme.shapes.medium)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(AccountIcons.get(a.type), Color(a.color), size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                Money.format(item.balanceMinor, a.currencyCode),
                style = MaterialTheme.typography.bodySmall,
                color = if (item.balanceMinor < 0) FinTheme.colors.expense else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) accent.readableOn(MaterialTheme.colorScheme.surface) else MaterialTheme.colorScheme.outline,
        )
    }
}

/** «Разбить копилку»: куда вернуть деньги, затем копилка закрывается (история остаётся). */
@Composable
private fun BreakDialog(
    piggy: PiggyBank,
    accounts: List<AccountWithBalance>,
    defaultAccountId: String?,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var to by remember { mutableStateOf(defaultAccountId) }
    val hasMoney = piggy.balanceMinor > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Разбить копилку?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (hasMoney) {
                        "${Money.format(piggy.balanceMinor, piggy.currency)} вернутся на выбранный счёт, копилка закроется. История операций сохранится."
                    } else {
                        "Копилка закроется. История операций сохранится."
                    },
                )
                if (hasMoney) {
                    accounts.forEach { a ->
                        AccountChoice(a, selected = a.account.id == to, accent = Color(piggy.color)) { to = a.account.id }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(to) }, enabled = !hasMoney || to != null) { Text("Разбить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
