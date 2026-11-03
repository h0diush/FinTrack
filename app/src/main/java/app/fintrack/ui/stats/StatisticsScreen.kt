@file:OptIn(ExperimentalMaterial3Api::class)

package app.fintrack.ui.stats

import androidx.compose.ui.unit.sp
import app.fintrack.ui.components.FitText
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.DonutLarge
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fintrack.domain.logic.CategoryAmount
import app.fintrack.domain.logic.InsightTone
import app.fintrack.domain.logic.Insights
import app.fintrack.domain.logic.Money
import app.fintrack.domain.logic.Periods
import app.fintrack.domain.logic.StatisticsCalculator
import app.fintrack.domain.model.CategoryKind
import app.fintrack.ui.charts.BarChart
import app.fintrack.ui.charts.BarGroup
import app.fintrack.ui.charts.DonutChart
import app.fintrack.ui.charts.DonutSlice
import app.fintrack.ui.charts.LineChart
import app.fintrack.ui.components.AppCard
import app.fintrack.ui.components.CategoryIcons
import app.fintrack.ui.components.CountUpAmount
import app.fintrack.ui.components.EmptyState
import app.fintrack.ui.components.IconBadge
import app.fintrack.ui.components.IconSize
import app.fintrack.ui.components.Motion
import app.fintrack.ui.components.PeriodBar
import app.fintrack.ui.components.SlidingSegmented
import app.fintrack.ui.components.StaggeredAppear
import app.fintrack.ui.components.bounceClick
import app.fintrack.ui.components.lighten
import app.fintrack.ui.theme.AmountStyle
import app.fintrack.ui.theme.FinTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

private val RU: Locale = Locale.forLanguageTag("ru")
private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", RU)
private val DAY_MONTH_FULL = DateTimeFormatter.ofPattern("d MMMM", RU)

/** Сколько категорий показывать на кольце; остальные — в «Остальное». */
private const val MAX_SLICES = 6

private fun amount(minor: Long, currency: String, hidden: Boolean, sign: Boolean = false) =
    if (hidden) Money.masked(currency) else Money.format(minor, currency, forceSign = sign)

@Composable
fun StatisticsScreen(vm: StatisticsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val data = state.data

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Статистика") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column {
                    PeriodBar(period = state.period, onPeriod = vm::setPeriod)
                    if (state.currencies.size > 1) {
                        Row(
                            Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            state.currencies.forEach { c ->
                                FilterChip(
                                    selected = c == state.currency,
                                    onClick = { vm.setCurrency(c) },
                                    label = { Text(c) },
                                    shape = MaterialTheme.shapes.small,
                                )
                            }
                        }
                    }
                }
            }

            if (data == null) return@LazyColumn
            val s = data.current
            val hasData = s.incomeMinor != 0L || s.expenseMinor != 0L

            item { StaggeredAppear(0) { HeroCard(state) } }
            item { StaggeredAppear(1) { QuickStats(state) } }

            if (!hasData) {
                item {
                    EmptyState(
                        "За этот период нет доходов и расходов в ${data.currency}.\nВыберите другой период.",
                        icon = Icons.Rounded.BarChart,
                    )
                }
            } else {
                item { StaggeredAppear(2) { BreakdownCard(state, vm::setKind) } }
            }
            if (data.insights.isNotEmpty()) {
                item { StaggeredAppear(3) { InsightsCarousel(state) } }
            }
            item { StaggeredAppear(4) { MonthlyCard(state) } }
            if (hasData && s.days.size in 2..92) {
                item { StaggeredAppear(5) { DailyCard(state) } }
            }
            if (data.balance.size >= 2) {
                item { StaggeredAppear(6) { BalanceTrendCard(state) } }
            }
            if (hasData && data.previous != null && data.previousLabel != null) {
                item { StaggeredAppear(7) { ComparisonCard(state) } }
            }
            if (hasData) {
                item { StaggeredAppear(8) { IndicatorsGrid(state) } }
            }
            item {
                Text(
                    "Учитываются операции по счетам в ${data.currency}. Пересчёт между валютами появится вместе с курсами валют.",
                    modifier = Modifier.padding(horizontal = 28.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Общие элементы

/** Карточка раздела: цветной значок, заголовок, подзаголовок, плавное изменение высоты. */
@Composable
private fun SectionCard(
    title: String,
    icon: ImageVector,
    iconColor: Color,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    AppCard(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .animateContentSize(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon, iconColor, size = 34.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Горизонтальная полоса доли: заливка прорисовывается от нуля с задержкой
 * (каскад по строкам). Рисуется в drawBehind — разметка не пересчитывается.
 */
@Composable
private fun ShareBar(fraction: Float, color: Color, track: Color, delayMs: Int, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(fraction) {
        anim.animateTo(fraction.coerceIn(0f, 1f), tween(Motion.LONG_MS + 300, delayMillis = delayMs, easing = FastOutSlowInEasing))
    }
    Box(
        modifier
            .height(height)
            .drawBehind {
                val r = CornerRadius(size.height / 2f)
                drawRoundRect(track, cornerRadius = r)
                val w = size.width * anim.value
                if (w > 0f) drawRoundRect(color, size = Size(max(w, size.height), size.height), cornerRadius = r)
            },
    )
}

private fun max(a: Float, b: Float) = if (a > b) a else b

// ---------------------------------------------------------------------------
// Главная карточка периода

@Composable
private fun HeroCard(state: StatisticsUiState) {
    val d = state.data ?: return
    val s = d.current
    val fin = FinTheme.colors
    val h = state.hideAmounts
    val glow = Color.White
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(Brush.linearGradient(listOf(fin.heroStart, fin.heroMid, fin.heroEnd)))
            .drawBehind {
                drawCircle(glow.copy(alpha = 0.10f), radius = size.width * 0.45f, center = Offset(size.width * 0.98f, 0f))
                drawCircle(glow.copy(alpha = 0.05f), radius = size.width * 0.28f, center = Offset(0f, size.height))
            }
            .padding(22.dp),
    ) {
        Text(
            "Денежный поток · ${Periods.label(state.period, LocalDate.now())}",
            color = fin.onHeroMuted,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        CountUpAmount(
            minor = s.netMinor,
            currency = d.currency,
            hidden = h,
            sign = true,
            style = MaterialTheme.typography.displaySmall,
            color = fin.onHero,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                s.incomeMinor == 0L && s.expenseMinor == 0L -> "Операций за период нет"
                s.expenseToIncomePercent == null -> "Доходов за период не было"
                else -> "Расходы составили ${Insights.percent(s.expenseToIncomePercent!!)} доходов"
            },
            color = fin.onHeroMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(18.dp))

        // Соотношение доходов и расходов — одна полоса, две доли
        val totalFlow = (s.incomeMinor + s.expenseMinor).coerceAtLeast(1L)
        val incomeShare = s.incomeMinor.toFloat() / totalFlow
        val split = remember { Animatable(0f) }
        LaunchedEffect(incomeShare) {
            split.animateTo(incomeShare, tween(Motion.LONG_MS + 400, delayMillis = 150, easing = FastOutSlowInEasing))
        }
        val expenseTone = Color.White.copy(alpha = 0.38f)
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .drawBehind {
                    val r = CornerRadius(size.height / 2f)
                    drawRoundRect(Color.White.copy(alpha = 0.14f), cornerRadius = r)
                    if (s.incomeMinor + s.expenseMinor > 0) {
                        val gap = 2.dp.toPx()
                        val incW = size.width * split.value
                        val expW = (size.width - incW - gap).coerceAtLeast(0f)
                        if (incW > 0f) drawRoundRect(Color.White, size = Size(incW, size.height), cornerRadius = r)
                        if (s.expenseMinor > 0 && split.value > 0f) {
                            drawRoundRect(expenseTone, topLeft = Offset(incW + gap, 0f), size = Size(expW, size.height), cornerRadius = r)
                        }
                    }
                },
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HeroPill("Доходы", Color.White, s.incomeMinor, d.currency, h, Modifier.weight(1f), delayMs = 120)
            HeroPill("Расходы", expenseTone, s.expenseMinor, d.currency, h, Modifier.weight(1f), delayMs = 200)
        }
    }
}

@Composable
private fun HeroPill(label: String, dot: Color, minor: Long, currency: String, hidden: Boolean, modifier: Modifier, delayMs: Int) {
    val fin = FinTheme.colors
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(Color.White.copy(alpha = 0.13f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dot),
            )
            Spacer(Modifier.width(6.dp))
            Text(label, color = fin.onHeroMuted, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(2.dp))
        CountUpAmount(
            minor = minor,
            currency = currency,
            hidden = hidden,
            style = MaterialTheme.typography.titleMedium,
            color = fin.onHero,
            delayMs = delayMs,
        )
    }
}

/** Три быстрых показателя под главной карточкой. */
@Composable
private fun QuickStats(state: StatisticsUiState) {
    val d = state.data ?: return
    val s = d.current
    val fin = FinTheme.colors
    Row(
        Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        QuickTile(
            Icons.Rounded.Today, MaterialTheme.colorScheme.primary, "Расход в день", Modifier.weight(1f),
            hint = "Средний расход за прошедшие дни периода: все расходы ÷ число прошедших дней. Будущие дни месяца не учитываются.",
        ) {
            CountUpAmount(s.avgDailyExpenseMinor, d.currency, state.hideAmounts, style = MaterialTheme.typography.titleSmall)
        }
        QuickTile(
            Icons.Rounded.Savings, fin.statusGood, "Сохранено", Modifier.weight(1f),
            hint = "Какая часть доходов осталась после расходов: (доходы − расходы) ÷ доходы. Отрицательное значение — расходы превысили доходы.",
        ) {
            FitText(
                s.savingsRatePercent?.let { Insights.percent(it) } ?: "—",
                style = MaterialTheme.typography.titleSmall.merge(AmountStyle),
                fontWeight = FontWeight.SemiBold,
            )
        }
        QuickTile(
            Icons.Rounded.Receipt, fin.chartIncome, "Операций", Modifier.weight(1f),
            hint = "Сколько операций за период по счетам в выбранной валюте, включая переводы и корректировки.",
        ) {
            FitText(s.operationsCount.toString(), style = MaterialTheme.typography.titleSmall.merge(AmountStyle), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun QuickTile(icon: ImageVector, color: Color, label: String, modifier: Modifier, hint: String, value: @Composable () -> Unit) {
    var showHint by remember { mutableStateOf(false) }
    if (showHint) {
        AlertDialog(
            onDismissRequest = { showHint = false },
            icon = { IconBadge(icon, color, size = 40.dp) },
            title = { Text(label) },
            text = { Text(hint) },
            confirmButton = { TextButton(onClick = { showHint = false }) { Text("Понятно") } },
        )
    }
    AppCard(modifier = modifier, onClick = { showHint = true }) {
        Column(Modifier.padding(12.dp)) {
            IconBadge(icon, color, size = 30.dp)
            Spacer(Modifier.height(8.dp))
            FitText(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, minFontSize = 9.sp)
            value()
        }
    }
}

// ---------------------------------------------------------------------------
// Структура по категориям

private data class Slice(val key: String?, val name: String, val amountMinor: Long, val share: Double, val color: Color, val iconKey: String?)

@Composable
private fun BreakdownCard(state: StatisticsUiState, onKind: (CategoryKind) -> Unit) {
    val d = state.data ?: return
    val fin = FinTheme.colors
    val other = MaterialTheme.colorScheme.outline
    val source: List<CategoryAmount> =
        if (state.kind == CategoryKind.EXPENSE) d.current.expenseByCategory else d.current.incomeByCategory
    val total = if (state.kind == CategoryKind.EXPENSE) d.current.expenseMinor else d.current.incomeMinor

    val slices = remember(source, state.categories, state.kind) {
        val top = source.take(MAX_SLICES).map { a ->
            val c = a.rootCategoryId?.let { state.categories[it] }
            Slice(
                key = a.rootCategoryId,
                name = state.categoryName(a.rootCategoryId),
                amountMinor = a.amountMinor,
                share = a.share,
                color = c?.let { Color(it.color) } ?: fin.neutral,
                iconKey = c?.iconKey,
            )
        }
        val rest = source.drop(MAX_SLICES)
        if (rest.isEmpty()) {
            top
        } else {
            top + Slice("__rest", "Остальное (${rest.size})", rest.sumOf { it.amountMinor }, rest.sumOf { it.share }, other, "other")
        }
    }
    var selected by remember(slices) { mutableStateOf<Int?>(null) }

    SectionCard(title = "Куда уходят деньги", icon = Icons.Rounded.DonutLarge, iconColor = MaterialTheme.colorScheme.primary) {
        SlidingSegmented(
            options = listOf("Расходы", "Доходы"),
            selectedIndex = if (state.kind == CategoryKind.EXPENSE) 0 else 1,
            onSelect = { onKind(if (it == 0) CategoryKind.EXPENSE else CategoryKind.INCOME) },
            pillColors = listOf(fin.chartExpense, fin.chartIncome),
        )
        Spacer(Modifier.height(18.dp))
        if (slices.isEmpty()) {
            Text(
                if (state.kind == CategoryKind.EXPENSE) "Расходов за период нет" else "Доходов за период нет",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(vertical = 24.dp)
                    .fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            return@SectionCard
        }
        DonutChart(
            slices = slices.map { DonutSlice(it.amountMinor.toFloat(), it.color) },
            selectedIndex = selected,
            onSelect = { selected = it },
            contentDescription = slices.joinToString(prefix = "Структура: ") { "${it.name} ${Insights.percent(it.share * 100)}" },
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
        ) {
            AnimatedContent(
                targetState = selected,
                transitionSpec = {
                    (fadeIn(tween(Motion.MEDIUM_MS)) + scaleIn(Motion.bouncy(), initialScale = 0.85f))
                        .togetherWith(fadeOut(tween(Motion.SHORT_MS)) + scaleOut(tween(Motion.SHORT_MS), targetScale = 0.9f))
                },
                label = "donutCenter",
            ) { idx ->
                val sl = idx?.let { slices.getOrNull(it) }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(132.dp)) {
                    if (sl != null) {
                        IconBadge(CategoryIcons.get(sl.iconKey), sl.color, size = 30.dp, selected = true)
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(
                        sl?.name ?: if (state.kind == CategoryKind.EXPENSE) "Все расходы" else "Все доходы",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (sl == null) {
                        CountUpAmount(total, d.currency, state.hideAmounts, style = MaterialTheme.typography.titleMedium)
                    } else {
                        Text(
                            amount(sl.amountMinor, d.currency, state.hideAmounts),
                            style = MaterialTheme.typography.titleMedium.merge(AmountStyle),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        Text(Insights.percent(sl.share * 100), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Коснитесь сегмента или строки, чтобы выделить категорию",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        slices.forEachIndexed { i, sl ->
            key(state.kind, sl.key) {
                StaggeredAppear(i) {
                    LegendRow(
                        slice = sl,
                        index = i,
                        currency = d.currency,
                        hidden = state.hideAmounts,
                        selected = selected == i,
                        dimmed = selected != null && selected != i,
                        onClick = { selected = if (selected == i) null else i },
                    )
                }
            }
        }
    }
}

@Composable
private fun LegendRow(slice: Slice, index: Int, currency: String, hidden: Boolean, selected: Boolean, dimmed: Boolean, onClick: () -> Unit) {
    val alpha by animateFloatAsState(if (dimmed) 0.4f else 1f, tween(Motion.MEDIUM_MS), label = "legendAlpha")
    val bg by androidx.compose.animation.animateColorAsState(
        if (selected) slice.color.copy(alpha = 0.10f) else Color.Transparent,
        tween(Motion.MEDIUM_MS),
        label = "legendBg",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(bg)
            .bounceClick(pressedScale = 0.98f, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(CategoryIcons.get(slice.iconKey), slice.color.copy(alpha = alpha), size = 36.dp, selected = selected)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    slice.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    amount(slice.amountMinor, currency, hidden),
                    style = MaterialTheme.typography.bodyMedium.merge(AmountStyle),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShareBar(
                    fraction = slice.share.toFloat(),
                    color = slice.color.copy(alpha = alpha),
                    track = MaterialTheme.colorScheme.surfaceContainerHigh,
                    delayMs = 200 + index * Motion.STAGGER_MS,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    Insights.percent(slice.share * 100),
                    style = MaterialTheme.typography.labelMedium.merge(AmountStyle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(44.dp),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Наблюдения — горизонтальная лента карточек

@Composable
private fun InsightsCarousel(state: StatisticsUiState) {
    val d = state.data ?: return
    val fin = FinTheme.colors
    Column {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Наблюдения", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("по вашим операциям", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(d.insights, key = { i, ins -> "$i:${ins.text}" }) { i, insight ->
                val (icon, color) = when (insight.tone) {
                    InsightTone.POSITIVE -> Icons.Rounded.CheckCircle to fin.statusGood
                    InsightTone.WARNING -> Icons.Rounded.Warning to fin.statusWarning
                    InsightTone.NEUTRAL -> Icons.Rounded.Lightbulb to MaterialTheme.colorScheme.primary
                }
                StaggeredAppear(i) {
                    Column(
                        Modifier
                            .width(250.dp)
                            .height(132.dp)
                            .clip(MaterialTheme.shapes.large)
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .background(Brush.linearGradient(listOf(color.copy(alpha = 0.16f), color.copy(alpha = 0.03f))))
                            .padding(16.dp),
                    ) {
                        IconBadge(icon, color, size = 32.dp)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (state.hideAmounts) maskAmounts(insight.text) else insight.text,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** В режиме скрытия сумм прячем числа с валютой в тексте наблюдений. */
private fun maskAmounts(text: String): String =
    text.replace(Regex("""[−-]?\d[\d  ]*(,\d+)? [A-Z]{3}"""), "••••")

// ---------------------------------------------------------------------------
// По месяцам

@Composable
private fun MonthlyCard(state: StatisticsUiState) {
    val d = state.data ?: return
    val fin = FinTheme.colors
    val h = state.hideAmounts
    var selected by remember(d.monthly) { mutableStateOf<Int?>(null) }
    SectionCard(
        title = "Доходы и расходы",
        subtitle = "Последние 6 месяцев",
        icon = Icons.Rounded.CalendarMonth,
        iconColor = fin.chartIncome,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendItem(fin.chartIncome, "Доходы")
            LegendItem(fin.chartExpense, "Расходы")
        }
        Spacer(Modifier.height(6.dp))
        BarChart(
            groups = d.monthly.map { m ->
                BarGroup(m.month.month.getDisplayName(TextStyle.SHORT_STANDALONE, RU).trimEnd('.'), listOf(m.incomeMinor, m.expenseMinor))
            },
            colors = listOf(fin.chartIncome, fin.chartExpense),
            currency = d.currency,
            selectedIndex = selected,
            onSelect = { selected = it },
            contentDescription = d.monthly.joinToString(prefix = "Доходы и расходы по месяцам: ") {
                "${it.month.month.getDisplayName(TextStyle.FULL_STANDALONE, RU)}: доходы ${Money.format(it.incomeMinor, d.currency)}, расходы ${Money.format(it.expenseMinor, d.currency)}"
            },
            hideValues = h,
            tooltip = { i ->
                val m = d.monthly[i]
                "Итог ${amount(m.incomeMinor - m.expenseMinor, d.currency, h, sign = true)}"
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
        )
        val point = d.monthly.getOrNull(selected ?: d.monthly.lastIndex)
        if (point != null) {
            Spacer(Modifier.height(12.dp))
            DetailLine(
                title = point.month.month.getDisplayName(TextStyle.FULL_STANDALONE, RU).replaceFirstChar { it.titlecase(RU) },
                parts = listOf(
                    "Доходы" to amount(point.incomeMinor, d.currency, h),
                    "Расходы" to amount(point.expenseMinor, d.currency, h),
                ),
            )
        }
    }
}

/** Подробности под графиком — текстом, а не только цветом. */
@Composable
private fun DetailLine(title: String, parts: List<Pair<String, String>>) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        AnimatedContent(
            targetState = title to parts,
            transitionSpec = { fadeIn(tween(Motion.MEDIUM_MS)).togetherWith(fadeOut(tween(Motion.SHORT_MS))) },
            label = "detail",
        ) { (t, ps) ->
            Column {
                Text(t, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ps.forEach { (label, value) ->
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                value,
                                style = MaterialTheme.typography.bodyMedium.merge(AmountStyle),
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// По дням

@Composable
private fun DailyCard(state: StatisticsUiState) {
    val d = state.data ?: return
    val fin = FinTheme.colors
    val h = state.hideAmounts
    val days = d.current.days
    var selected by remember(days) { mutableStateOf<Int?>(null) }
    SectionCard(
        title = "Расходы по дням",
        subtitle = "Пунктир — в среднем ${amount(d.current.avgDailyExpenseMinor, d.currency, h)} в день",
        icon = Icons.Rounded.BarChart,
        iconColor = fin.chartExpense,
    ) {
        BarChart(
            groups = days.map { BarGroup(it.date.dayOfMonth.toString(), listOf(it.expenseMinor)) },
            colors = listOf(fin.chartExpense),
            currency = d.currency,
            selectedIndex = selected,
            onSelect = { selected = it },
            contentDescription = "Расходы по дням. Средний расход в день ${Money.format(d.current.avgDailyExpenseMinor, d.currency)}",
            averageLine = d.current.avgDailyExpenseMinor,
            labelEvery = ceil(days.size / 7.0).toInt().coerceAtLeast(1),
            hideValues = h,
            tooltip = { i ->
                val day = days[i]
                "${day.date.format(DAY_MONTH).replace(".", "")}: ${amount(day.expenseMinor, d.currency, h)}"
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp),
        )
        d.current.maxExpenseDay?.let { max ->
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Star, null, tint = fin.statusWarning, modifier = Modifier.size(IconSize.sm))
                Spacer(Modifier.width(6.dp))
                Text(
                    "Самый затратный день — ${max.date.format(DAY_MONTH_FULL)}: ${amount(max.expenseMinor, d.currency, h)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Баланс

@Composable
private fun BalanceTrendCard(state: StatisticsUiState) {
    val d = state.data ?: return
    val h = state.hideAmounts
    val points = d.balance
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val change = points.last().balanceMinor - points.first().balanceMinor
    val fin = FinTheme.colors
    SectionCard(
        title = "Баланс",
        subtitle = "Счета общего баланса в ${d.currency} на конец дня",
        icon = Icons.Rounded.ShowChart,
        iconColor = MaterialTheme.colorScheme.primary,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CountUpAmount(points.last().balanceMinor, d.currency, h, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(10.dp))
            ChangeBadge(
                text = amount(change, d.currency, h, sign = true),
                icon = if (change >= 0) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                color = if (change >= 0) fin.statusGood else fin.statusCritical,
            )
        }
        Spacer(Modifier.height(10.dp))
        LineChart(
            values = points.map { it.balanceMinor },
            labels = points.map { it.date.format(DAY_MONTH).replace(".", "") },
            color = MaterialTheme.colorScheme.primary,
            currency = d.currency,
            selectedIndex = selected,
            onSelect = { selected = it },
            contentDescription = "Баланс: с ${Money.format(points.first().balanceMinor, d.currency)} до ${Money.format(points.last().balanceMinor, d.currency)}",
            hideValues = h,
            tooltip = { i -> "${points[i].date.format(DAY_MONTH).replace(".", "")}: ${amount(points[i].balanceMinor, d.currency, h)}" },
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Ведите пальцем по графику, чтобы увидеть баланс на любой день",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Плашка изменения: иконка + текст, цвет — только дополнение. */
@Composable
private fun ChangeBadge(text: String, icon: ImageVector, color: Color) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.13f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelMedium.merge(AmountStyle), color = MaterialTheme.colorScheme.onSurface)
    }
}

// ---------------------------------------------------------------------------
// Сравнение с прошлым периодом — парные полосы

@Composable
private fun ComparisonCard(state: StatisticsUiState) {
    val d = state.data ?: return
    val prev = d.previous ?: return
    val fin = FinTheme.colors
    val changes = StatisticsCalculator.compare(d.current.expenseByCategory, prev.expenseByCategory)
        .filter { it.currentMinor > 0 || it.previousMinor > 0 }
        .take(5)
    val scale = (listOf(d.current.expenseMinor, prev.expenseMinor)).max().coerceAtLeast(1L).toFloat()
    val catScale = changes.maxOfOrNull { maxOf(it.currentMinor, it.previousMinor) }?.coerceAtLeast(1L)?.toFloat() ?: 1f
    SectionCard(
        title = "Сравнение расходов",
        subtitle = "С периодом «${d.previousLabel}»",
        icon = Icons.Rounded.CompareArrows,
        iconColor = fin.statusWarning,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendItem(fin.chartExpense, "Сейчас")
            LegendItem(MaterialTheme.colorScheme.outline, "Раньше")
        }
        Spacer(Modifier.height(8.dp))
        PairedRow(
            title = "Все расходы",
            current = d.current.expenseMinor,
            previous = prev.expenseMinor,
            scale = scale,
            currency = d.currency,
            hidden = state.hideAmounts,
            index = 0,
            bold = true,
        )
        changes.forEachIndexed { i, c ->
            PairedRow(
                title = state.copy(kind = CategoryKind.EXPENSE).categoryName(c.rootCategoryId),
                current = c.currentMinor,
                previous = c.previousMinor,
                scale = catScale,
                currency = d.currency,
                hidden = state.hideAmounts,
                index = i + 1,
            )
        }
    }
}

@Composable
private fun PairedRow(
    title: String,
    current: Long,
    previous: Long,
    scale: Float,
    currency: String,
    hidden: Boolean,
    index: Int,
    bold: Boolean = false,
) {
    val fin = FinTheme.colors
    val pct = if (previous > 0) (current - previous) * 100.0 / previous else null
    val (icon, color, text) = when {
        pct == null && current > 0 -> Triple(Icons.Rounded.ArrowUpward, fin.statusWarning, "новое")
        pct == null || abs(pct) < 1 -> Triple(Icons.Rounded.Remove, MaterialTheme.colorScheme.onSurfaceVariant, "≈")
        pct > 0 -> Triple(Icons.Rounded.ArrowUpward, fin.statusWarning, "+" + Insights.percent(pct))
        else -> Triple(Icons.Rounded.ArrowDownward, fin.statusGood, "−" + Insights.percent(-pct))
    }
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ChangeBadge(text, icon, color)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShareBar(current / scale, fin.chartExpense, track, delayMs = 150 + index * Motion.STAGGER_MS, modifier = Modifier.weight(1f), height = 8.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                amount(current, currency, hidden),
                style = MaterialTheme.typography.labelMedium.merge(AmountStyle),
                modifier = Modifier.width(96.dp),
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShareBar(previous / scale, MaterialTheme.colorScheme.outline, track, delayMs = 220 + index * Motion.STAGGER_MS, modifier = Modifier.weight(1f), height = 8.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                amount(previous, currency, hidden),
                style = MaterialTheme.typography.labelMedium.merge(AmountStyle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(96.dp),
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Показатели — сетка плиток

@Composable
private fun IndicatorsGrid(state: StatisticsUiState) {
    val d = state.data ?: return
    val s = d.current
    val h = state.hideAmounts
    val fin = FinTheme.colors
    val top = s.topExpenseCategory
    val topCategory = top?.rootCategoryId?.let { state.categories[it] }
    data class Tile(val icon: ImageVector, val color: Color, val label: String, val value: String)
    val tiles = buildList {
        add(Tile(Icons.Rounded.Speed, MaterialTheme.colorScheme.primary, "Средний расход в день", amount(s.avgDailyExpenseMinor, d.currency, h)))
        add(Tile(Icons.Rounded.CalendarMonth, fin.chartIncome, "Средний доход в месяц", amount(s.avgMonthlyIncomeMinor, d.currency, h)))
        if (top != null) {
            add(
                Tile(
                    CategoryIcons.get(topCategory?.iconKey),
                    topCategory?.let { Color(it.color) } ?: fin.neutral,
                    "Самая затратная категория",
                    "${state.copy(kind = CategoryKind.EXPENSE).categoryName(top.rootCategoryId)} · ${Insights.percent(top.share * 100)}",
                ),
            )
        }
        s.maxExpenseDay?.let {
            add(Tile(Icons.Rounded.Event, fin.chartExpense, "Самый дорогой день", "${it.date.format(DAY_MONTH_FULL)}\n${amount(it.expenseMinor, d.currency, h)}"))
        }
        s.savingsRatePercent?.let { add(Tile(Icons.Rounded.Savings, fin.statusGood, "Доля сбережений", Insights.percent(it))) }
        val change = d.balance.takeIf { it.size >= 2 }?.let { it.last().balanceMinor - it.first().balanceMinor }
        if (change != null) {
            add(Tile(Icons.Rounded.ShowChart, MaterialTheme.colorScheme.primary, "Изменение накоплений", amount(change, d.currency, h, sign = true)))
        }
    }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text("Показатели", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
        tiles.chunked(2).forEachIndexed { row, pair ->
            if (row > 0) Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEachIndexed { col, t ->
                    Box(Modifier.weight(1f)) {
                        StaggeredAppear(row * 2 + col) {
                            AppCard(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier
                                        .padding(14.dp)
                                        .height(96.dp),
                                ) {
                                    IconBadge(t.icon, t.color, size = 30.dp)
                                    Spacer(Modifier.height(8.dp))
                                    FitText(
                                        t.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        minFontSize = 9.sp,
                                    )
                                    FitText(
                                        t.value,
                                        style = MaterialTheme.typography.bodyMedium.merge(AmountStyle),
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                    )
                                }
                            }
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
