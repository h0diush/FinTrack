@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.fintrack.ui.home

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.rounded.Add
import app.fintrack.ui.piggy.MotivationTicker
import app.fintrack.ui.piggy.NewPiggyCard
import app.fintrack.ui.piggy.PiggyCard
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.fintrack.domain.logic.BudgetLevel
import app.fintrack.domain.logic.Money
import app.fintrack.ui.budget.BudgetProgress
import app.fintrack.ui.budget.LevelChip
import app.fintrack.ui.charts.BarChart
import app.fintrack.ui.charts.BarGroup
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fintrack.domain.model.TransactionType
import app.fintrack.ui.components.AmountText
import app.fintrack.ui.components.AppCard
import app.fintrack.ui.components.EmptyState
import app.fintrack.ui.components.IconBadge
import app.fintrack.ui.components.IconSize
import app.fintrack.ui.components.Motion
import app.fintrack.ui.components.SectionTitle
import app.fintrack.ui.components.StaggeredAppear
import app.fintrack.ui.components.TransactionItem
import app.fintrack.ui.components.bounceClick
import app.fintrack.ui.theme.FinTheme
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val RU: Locale = Locale.forLanguageTag("ru")

private fun greeting(now: LocalTime = LocalTime.now()) = when (now.hour) {
    in 5..11 -> "Доброе утро"
    in 12..17 -> "Добрый день"
    in 18..22 -> "Добрый вечер"
    else -> "Доброй ночи"
}

@Composable
fun HomeScreen(
    onAdd: (type: String) -> Unit,
    onOpenTransaction: (String) -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBudget: () -> Unit,
    onOpenPiggy: (String) -> Unit,
    onNewPiggy: () -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val piggies by vm.piggies.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item {
            StaggeredAppear(0) {
                Header(hidden = state.hideAmounts, onToggleHidden = vm::toggleHidden, onOpenSettings = onOpenSettings)
            }
        }
        item {
            StaggeredAppear(1) { BalanceCard(state, onClick = onOpenAccounts) }
        }
        item {
            StaggeredAppear(2) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val fin = FinTheme.colors
                    QuickAction("Расход", Icons.Rounded.ArrowUpward, fin.expense, Modifier.weight(1f)) {
                        onAdd(TransactionType.EXPENSE.name)
                    }
                    QuickAction("Доход", Icons.Rounded.ArrowDownward, fin.income, Modifier.weight(1f)) {
                        onAdd(TransactionType.INCOME.name)
                    }
                    QuickAction("Перевод", Icons.Rounded.SwapHoriz, fin.transfer, Modifier.weight(1f)) {
                        onAdd(TransactionType.TRANSFER.name)
                    }
                }
            }
        }
        item {
            StaggeredAppear(3) {
                SummaryRow(state, onOpenBudget = onOpenBudget)
            }
        }
        item {
            StaggeredAppear(4) {
                PiggySection(piggies, state.hideAmounts, onOpenPiggy, onNewPiggy)
            }
        }
        item {
            StaggeredAppear(5) {
                Column {
                    SectionTitle("Последние операции") {
                        if (state.recent.isNotEmpty()) {
                            TextButton(onClick = onOpenHistory) {
                                Text("Все")
                                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(IconSize.sm))
                            }
                        }
                    }
                    if (!state.loading && state.recent.isEmpty()) {
                        EmptyState("Пока нет операций.\nНажмите «+», чтобы добавить первую.")
                    } else {
                        AppCard(
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .animateContentSize(),
                        ) {
                            Column(Modifier.padding(vertical = 6.dp)) {
                                state.recent.forEachIndexed { index, tx ->
                                    if (index > 0) {
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

/** Копилки: горизонтальная лента карточек и сменяющиеся мотивационные фразы. */
@Composable
private fun PiggySection(
    piggies: HomePiggies,
    hidden: Boolean,
    onOpenPiggy: (String) -> Unit,
    onNewPiggy: () -> Unit,
) {
    if (piggies.loading) return
    Column(Modifier.padding(top = 4.dp)) {
        SectionTitle("Копилки") {
            if (piggies.items.isNotEmpty()) {
                TextButton(onClick = onNewPiggy) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(IconSize.sm))
                    Spacer(Modifier.width(4.dp))
                    Text("Новая")
                }
            }
        }
        if (piggies.items.isEmpty()) {
            AppCard(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(), onClick = onNewPiggy) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.Savings, Color(0xFFEC4899), size = 48.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Заведите копилку", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Отпуск, телефон или подушка безопасности — задайте цель и срок, а приложение подскажет, сколько откладывать",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(piggies.items, key = { it.piggy.accountId }) { item ->
                    PiggyCard(
                        piggy = item.piggy,
                        progress = item.progress,
                        hidden = hidden,
                        onClick = { onOpenPiggy(item.piggy.accountId) },
                        modifier = Modifier.animateItem(),
                    )
                }
                item(key = "new") { NewPiggyCard(onClick = onNewPiggy) }
            }
            if (piggies.phrases.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                MotivationTicker(
                    phrases = piggies.phrases,
                    accent = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    compact = true,
                )
            }
        }
    }
}

@Composable
private fun Header(hidden: Boolean, onToggleHidden: () -> Unit, onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", RU)).replaceFirstChar { it.titlecase(RU) },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(greeting(), style = MaterialTheme.typography.headlineSmall)
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .bounceClick(
                    pressedScale = 0.88f,
                    onClickLabel = if (hidden) "Показать суммы" else "Скрыть суммы",
                    onClick = onToggleHidden,
                ),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = hidden,
                transitionSpec = {
                    (scaleIn(Motion.bouncy(), initialScale = 0.5f) + fadeIn()).togetherWith(scaleOut(tween(Motion.SHORT_MS), targetScale = 0.5f) + fadeOut())
                },
                label = "eye",
            ) { isHidden ->
                Icon(
                    if (isHidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (isHidden) "Показать суммы" else "Скрыть суммы",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .bounceClick(pressedScale = 0.88f, onClickLabel = "Настройки", onClick = onOpenSettings),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Settings, contentDescription = "Настройки", tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Главная карточка: градиент, мягкие «блики» и стеклянные плашки доходов/расходов. */
@Composable
private fun BalanceCard(state: HomeUiState, onClick: () -> Unit) {
    val fin = FinTheme.colors
    val main = state.lines.firstOrNull()
    val others = state.lines.drop(1)
    val glow = Color.White
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(Brush.linearGradient(listOf(fin.heroStart, fin.heroMid, fin.heroEnd)))
            .drawBehind {
                drawCircle(glow.copy(alpha = 0.10f), radius = size.width * 0.42f, center = Offset(size.width * 0.95f, size.height * 0.05f))
                drawCircle(glow.copy(alpha = 0.06f), radius = size.width * 0.30f, center = Offset(size.width * 0.05f, size.height * 1.05f))
            }
            .bounceClick(pressedScale = 0.98f, onClickLabel = "Открыть счета", onClick = onClick)
            .padding(22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Общий баланс", color = fin.onHeroMuted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                "${state.accountsCount} ${accountsWord(state.accountsCount)}",
                color = fin.onHeroMuted,
                style = MaterialTheme.typography.labelMedium,
            )
            Icon(Icons.Rounded.ChevronRight, null, tint = fin.onHeroMuted, modifier = Modifier.size(IconSize.sm))
        }
        Spacer(Modifier.height(6.dp))
        if (main != null) {
            AmountText(
                minor = main.balanceMinor,
                currency = main.currency,
                hidden = state.hideAmounts,
                color = fin.onHero,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                animate = true,
            )
        }
        others.filter { it.balanceMinor != 0L }.forEach { line ->
            AmountText(
                minor = line.balanceMinor,
                currency = line.currency,
                hidden = state.hideAmounts,
                color = fin.onHero.copy(alpha = 0.9f),
                style = MaterialTheme.typography.titleMedium,
                animate = true,
            )
        }
        if (state.excludedCount > 0) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.VisibilityOff, null, tint = fin.onHeroMuted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "Без учёта: ${state.excludedCount} ${accountsWord(state.excludedCount)}",
                    color = fin.onHeroMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        if (main != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                GlassStat("Доходы", Icons.Rounded.ArrowDownward, main.totals.incomeMinor, main.currency, state.hideAmounts, Modifier.weight(1f))
                GlassStat("Расходы", Icons.Rounded.ArrowUpward, main.totals.expenseMinor, main.currency, state.hideAmounts, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(verticalArrangement = Arrangement.Center) {
                Text("${state.monthLabel}: итог ", color = fin.onHeroMuted, style = MaterialTheme.typography.bodySmall)
                AmountText(
                    minor = main.totals.netMinor,
                    currency = main.currency,
                    hidden = state.hideAmounts,
                    color = fin.onHero,
                    style = MaterialTheme.typography.bodySmall,
                    forceSign = true,
                    animate = true,
                )
            }
        }
        val otherActivity = others.filter { it.totals.incomeMinor != 0L || it.totals.expenseMinor != 0L }
        if (otherActivity.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Операции в других валютах учтены отдельно: " + otherActivity.joinToString { it.currency },
                color = fin.onHeroMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun accountsWord(n: Int): String {
    val mod10 = n % 10
    val mod100 = n % 100
    return when {
        mod10 == 1 && mod100 != 11 -> "счёт"
        mod10 in 2..4 && mod100 !in 12..14 -> "счёта"
        else -> "счетов"
    }
}

@Composable
private fun GlassStat(
    label: String,
    icon: ImageVector,
    minor: Long,
    currency: String,
    hidden: Boolean,
    modifier: Modifier,
) {
    val fin = FinTheme.colors
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(Color.White.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = fin.onHero, modifier = Modifier.size(IconSize.sm))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, color = fin.onHeroMuted, style = MaterialTheme.typography.labelSmall)
            AmountText(
                minor = minor,
                currency = currency,
                hidden = hidden,
                color = fin.onHero,
                style = MaterialTheme.typography.bodyMedium,
                animate = true,
            )
        }
    }
}

@Composable
private fun QuickAction(label: String, icon: ImageVector, color: Color, modifier: Modifier, onClick: () -> Unit) {
    AppCard(modifier = modifier, onClick = onClick) {
        Column(
            modifier = Modifier
                .padding(vertical = 16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconBadge(icon, color, size = 44.dp)
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Сводка: бюджет месяца и расходы за неделю (ТЗ, п. 3.4). */
@Composable
private fun SummaryRow(state: HomeUiState, onOpenBudget: () -> Unit) {
    val sum = state.summary
    val cur = state.baseCurrency
    val hidden = state.hideAmounts
    Column(
        Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val budget = sum.budget
        AppCard(modifier = Modifier.fillMaxWidth(), onClick = onOpenBudget) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.Savings, MaterialTheme.colorScheme.primary, size = 32.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Бюджет месяца", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(IconSize.sm))
                }
                Spacer(Modifier.height(10.dp))
                if (budget == null) {
                    Text(
                        "Задайте лимит расходов — здесь появится остаток и сколько можно тратить в день",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val st = budget.status
                    // FlowRow: если сумма длинная, подпись целиком уходит на следующую строку,
                    // а не сжимается в столбик по одной букве.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AmountText(
                            minor = st.remainingMinor.coerceAtLeast(0),
                            currency = st.currency,
                            hidden = hidden,
                            style = MaterialTheme.typography.titleLarge,
                            animate = true,
                            modifier = Modifier.align(Alignment.Bottom),
                        )
                        Text(
                            "осталось из ${if (hidden) Money.masked(st.currency) else Money.format(st.availableMinor, st.currency)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .align(Alignment.Bottom)
                                .padding(bottom = 3.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    BudgetProgress(st.fraction, budget.level, sum.thresholds, height = 8, showTicks = false)
                    val f = budget.forecast
                    if (f != null && st.remainingMinor > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Можно тратить до ${if (hidden) Money.masked(st.currency) else Money.format(f.dailyAllowanceMinor, st.currency)} в день",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (budget.level == BudgetLevel.EXCEEDED) {
                        Spacer(Modifier.height(8.dp))
                        LevelChip(budget.level, sum.thresholds)
                    }
                }
            }
        }

        if (sum.week.isNotEmpty()) {
            val fin = FinTheme.colors
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Расходы за 7 дней", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        AmountText(sum.weekTotalMinor, cur, hidden, style = MaterialTheme.typography.titleSmall, animate = true)
                    }
                    Spacer(Modifier.height(8.dp))
                    var selected by remember(sum.week) { mutableStateOf<Int?>(null) }
                    BarChart(
                        groups = sum.week.map {
                            BarGroup(it.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT_STANDALONE, RU), listOf(it.expenseMinor))
                        },
                        colors = listOf(fin.chartExpense),
                        currency = cur,
                        selectedIndex = selected,
                        onSelect = { selected = it },
                        contentDescription = "Расходы за 7 дней: " + sum.week.joinToString { Money.format(it.expenseMinor, cur) },
                        hideValues = hidden,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp),
                    )
                    val day = selected?.let { sum.week.getOrNull(it) }
                    if (day != null) {
                        Text(
                            "${day.date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", RU))}: " +
                                if (hidden) Money.masked(cur) else Money.format(day.expenseMinor, cur),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
