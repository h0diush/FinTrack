@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.fintrack.ui.budget

import app.fintrack.ui.components.FitText
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fintrack.domain.logic.BudgetLevel
import app.fintrack.domain.logic.BudgetThresholds
import app.fintrack.domain.logic.Insights
import app.fintrack.domain.logic.Money
import app.fintrack.domain.logic.Periods
import app.fintrack.ui.components.AppCard
import app.fintrack.ui.components.CategoryIcons
import app.fintrack.ui.components.EmptyState
import app.fintrack.ui.components.IconBadge
import app.fintrack.ui.components.IconSize
import app.fintrack.ui.components.Motion
import app.fintrack.ui.components.SectionTitle
import app.fintrack.ui.components.StaggeredAppear
import app.fintrack.ui.components.bounceClick
import app.fintrack.ui.theme.AmountStyle
import app.fintrack.ui.theme.FinTheme
import java.time.LocalDate
import kotlin.math.roundToInt

private fun amount(minor: Long, currency: String, hidden: Boolean, sign: Boolean = false) =
    if (hidden) Money.masked(currency) else Money.format(minor, currency, forceSign = sign)

fun daysWord(n: Int): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> "день"
        m10 in 2..4 && m100 !in 12..14 -> "дня"
        else -> "дней"
    }
}

@Composable
fun levelColor(level: BudgetLevel): Color {
    val fin = FinTheme.colors
    return when (level) {
        BudgetLevel.NORMAL, BudgetLevel.INFO -> fin.statusGood
        BudgetLevel.WARNING -> fin.statusWarning
        BudgetLevel.EXCEEDED -> fin.statusCritical
    }
}

@Composable
fun BudgetScreen(vm: BudgetViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val notificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    var showThresholds by remember { mutableStateOf(false) }
    var direction by remember { mutableIntStateOf(1) }
    val o = state.overview

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Бюджет") },
                actions = {
                    IconButton(onClick = { showThresholds = true }) { Icon(Icons.Rounded.Tune, "Пороги уведомлений") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        direction = -1
                        vm.shiftMonth(-1)
                    }) { Icon(Icons.Rounded.ChevronLeft, "Предыдущий месяц") }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        AnimatedContent(
                            targetState = state.month,
                            transitionSpec = {
                                (slideInHorizontally(tween(Motion.MEDIUM_MS)) { it / 3 * direction } + fadeIn())
                                    .togetherWith(slideOutHorizontally(tween(Motion.SHORT_MS)) { -it / 3 * direction } + fadeOut())
                            },
                            label = "budgetMonth",
                        ) { m ->
                            Text(Periods.monthLabel(m, LocalDate.now()), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    IconButton(onClick = {
                        direction = 1
                        vm.shiftMonth(1)
                    }) { Icon(Icons.Rounded.ChevronRight, "Следующий месяц") }
                }
            }

            item {
                StaggeredAppear(0) {
                    val overall = o.overall
                    if (overall == null) {
                        SetupCard(onClick = { vm.openEditor(null, overall = true) })
                    } else {
                        OverallCard(overall, state) { vm.openEditor(null, overall = true) }
                    }
                }
            }

            item {
                SectionTitle("По категориям") {
                    if (state.availableCategories.isNotEmpty()) {
                        TextButton(onClick = { vm.openEditor(null, overall = false) }) {
                            Icon(Icons.Rounded.Add, null, Modifier.size(IconSize.sm))
                            Spacer(Modifier.width(4.dp))
                            Text("Лимит")
                        }
                    }
                }
            }
            if (o.categories.isEmpty()) {
                item {
                    Text(
                        "Задайте лимиты для отдельных категорий — например, продукты 500 BYN, кафе 150 BYN.",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(o.categories, key = { "c:" + it.status.limit.id }) { item ->
                CategoryBudgetRow(
                    item = item,
                    state = state,
                    modifier = Modifier.animateItem(),
                    onClick = { vm.openEditor(item.status.categoryId, overall = false) },
                )
            }

            if (o.unbudgeted.isNotEmpty()) {
                item { SectionTitle("Расходы без лимита") }
                items(o.unbudgeted, key = { "u:" + it.category.id }) { u ->
                    Row(
                        Modifier
                            .animateItem()
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconBadge(CategoryIcons.get(u.category.iconKey), Color(u.category.color), size = 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(u.category.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                amount(u.spentMinor, state.currency, state.hideAmounts),
                                style = MaterialTheme.typography.bodySmall.merge(AmountStyle),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { vm.openEditor(u.category.id, overall = false) }) { Text("Задать") }
                    }
                }
            }

            item {
                Text(
                    "Учитываются расходы по счетам в ${state.currency}, включая комиссии переводов (в общем бюджете). " +
                        "Переводы между своими счетами расходом не считаются. Уведомления о порогах включаются в настройках.",
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    state.editor?.let { ed ->
        LimitEditorSheet(
            editor = ed,
            state = state,
            onCategory = vm::setEditorCategory,
            onAmount = vm::setEditorAmount,
            onRollover = vm::setEditorRollover,
            onSave = {
                // Лимит — повод для уведомлений о пороге: спросить разрешение, если его ещё нет.
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                    !app.fintrack.notify.RecurringNotifier.canNotify(context)
                ) {
                    notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
                vm.saveEditor()
            },
            onRemove = vm::removeEditorLimit,
            onDismiss = vm::closeEditor,
        )
    }

    if (showThresholds) {
        ThresholdsDialog(
            current = state.thresholds,
            onApply = { info, warn ->
                vm.setThresholds(info, warn)
                showThresholds = false
            },
            onDismiss = { showThresholds = false },
        )
    }
}

@Composable
private fun SetupCard(onClick: () -> Unit) {
    AppCard(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Savings, MaterialTheme.colorScheme.primary, size = 52.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Задайте бюджет на месяц", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Общий лимит расходов: приложение покажет остаток, темп трат и сколько можно тратить в день.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Полоса прогресса: ширина и цвет анимируются, отметки — пороги уведомлений. */
@Composable
fun BudgetProgress(fraction: Double, level: BudgetLevel, thresholds: BudgetThresholds, height: Int = 10, showTicks: Boolean = true) {
    val target = if (fraction.isFinite()) fraction.toFloat().coerceIn(0f, 1f) else 1f
    val width by animateFloatAsState(target, tween(Motion.LONG_MS + 200), label = "budgetBar")
    val color by animateColorAsState(levelColor(level), tween(Motion.MEDIUM_MS), label = "budgetColor")
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val tick = MaterialTheme.colorScheme.surfaceContainer
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(CircleShape)
            .background(track)
            .semantics { contentDescription = "Израсходовано ${Insights.percent(fraction.takeIf { it.isFinite() }?.times(100) ?: 100.0)}" },
    ) {
        Box(
            Modifier
                .fillMaxWidth(width)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(color),
        )
        if (showTicks) {
            listOf(thresholds.infoPercent, thresholds.warningPercent).forEach { p ->
                Box(
                    Modifier
                        .offset(x = maxWidth * (p / 100f) - 1.dp)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(tick),
                )
            }
        }
    }
}

@Composable
fun LevelChip(level: BudgetLevel, thresholds: BudgetThresholds) {
    val (icon, text) = when (level) {
        BudgetLevel.NORMAL -> Icons.Rounded.CheckCircle to "В пределах бюджета"
        BudgetLevel.INFO -> Icons.Rounded.Info to "Израсходовано больше ${thresholds.infoPercent} %"
        BudgetLevel.WARNING -> Icons.Rounded.Warning to "Израсходовано больше ${thresholds.warningPercent} %"
        BudgetLevel.EXCEEDED -> Icons.Rounded.Error to "Бюджет превышен"
    }
    val color = levelColor(level)
    Row(
        Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun OverallCard(item: BudgetItem, state: BudgetUiState, onClick: () -> Unit) {
    val s = item.status
    val h = state.hideAmounts
    val cur = s.currency
    AppCard(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth(),
        onClick = onClick,
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Общий бюджет", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            // Длинная сумма переносит подпись на новую строку целиком, без разрыва слов.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FitText(
                    amount(s.remainingMinor.coerceAtLeast(0), cur, h),
                    style = MaterialTheme.typography.headlineMedium.merge(AmountStyle),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Bottom),
                )
                Text(
                    "осталось из ${amount(s.availableMinor, cur, h)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Bottom)
                        .padding(bottom = 4.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            BudgetProgress(s.fraction, item.level, state.thresholds, height = 12)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Потрачено ${amount(s.spentMinor, cur, h)}",
                    style = MaterialTheme.typography.bodySmall.merge(AmountStyle),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    Insights.percent(s.fraction.takeIf { it.isFinite() }?.times(100) ?: 100.0),
                    style = MaterialTheme.typography.labelLarge.merge(AmountStyle),
                )
            }
            if (s.carriedMinor > 0) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.SyncAlt, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Перенесено с прошлого месяца: ${amount(s.carriedMinor, cur, h, sign = true)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            LevelChip(item.level, state.thresholds)

            val f = item.forecast
            AnimatedVisibility(
                visible = f != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                if (f != null) {
                    Column(Modifier.padding(top = 14.dp)) {
                        val lines = buildList {
                            if (s.remainingMinor <= 0) {
                                add("Бюджет израсходован, перерасход ${amount(-s.remainingMinor, cur, h)}")
                            } else {
                                add("Можно тратить до ${amount(f.dailyAllowanceMinor, cur, h)} в день — осталось ${f.daysLeft} ${daysWord(f.daysLeft)}")
                                val days = f.daysUntilExhausted
                                add(
                                    if (f.lastsWholeMonth || days == null) {
                                        "При текущем темпе бюджета хватит до конца месяца"
                                    } else {
                                        "При текущем темпе бюджета хватит ещё на $days ${daysWord(days)}"
                                    },
                                )
                            }
                            add("Прогноз расходов за месяц: ${amount(f.projectedSpendMinor, cur, h)}")
                        }
                        lines.forEach { line ->
                            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                                Box(
                                    Modifier
                                        .padding(top = 7.dp)
                                        .size(5.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.onSurfaceVariant),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(line, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryBudgetRow(item: BudgetItem, state: BudgetUiState, modifier: Modifier, onClick: () -> Unit) {
    val s = item.status
    val c = item.category
    val h = state.hideAmounts
    AppCard(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .fillMaxWidth(),
        onClick = onClick,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(CategoryIcons.get(c?.iconKey), c?.let { Color(it.color) } ?: FinTheme.colors.neutral, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            c?.name ?: "Категория удалена",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (s.limit.rollover) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Rounded.SyncAlt,
                                contentDescription = "Остаток переносится",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    Text(
                        "${amount(s.spentMinor, s.currency, h)} из ${amount(s.availableMinor, s.currency, h)}",
                        style = MaterialTheme.typography.bodySmall.merge(AmountStyle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    val remaining = s.remainingMinor
                    FitText(
                        amount(remaining, s.currency, h),
                        style = MaterialTheme.typography.bodyMedium.merge(AmountStyle),
                        fontWeight = FontWeight.SemiBold,
                        color = if (remaining < 0) FinTheme.colors.statusCritical else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        if (remaining >= 0) "осталось" else "перерасход",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            BudgetProgress(s.fraction, item.level, state.thresholds, height = 8, showTicks = false)
            if (item.level == BudgetLevel.WARNING || item.level == BudgetLevel.EXCEEDED) {
                Spacer(Modifier.height(10.dp))
                LevelChip(item.level, state.thresholds)
            }
        }
    }
}

@Composable
private fun LimitEditorSheet(
    editor: LimitEditor,
    state: BudgetUiState,
    onCategory: (String) -> Unit,
    onAmount: (String) -> Unit,
    onRollover: (Boolean) -> Unit,
    onSave: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val monthLabel = Periods.monthLabel(state.month, LocalDate.now())
    // Клавиатура открыта — подсказки прячем, чтобы сумма и кнопка помещались над ней.
    val keyboardOpen = WindowInsets.isImeVisible
    val selected = editor.categoryId?.let { state.allCategories[it] }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Верх (заголовок и категории) прокручивается и сжимается; поле суммы и кнопка — всегда видны.
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
            Text(
                when {
                    editor.overall -> "Общий бюджет на месяц"
                    editor.isNew -> "Новый лимит категории"
                    else -> "Лимит категории"
                },
                style = MaterialTheme.typography.titleLarge,
            )

            if (!editor.overall) {
                if (editor.isNew) {
                    val options = state.availableCategories
                    if (options.isEmpty()) {
                        Text("Для всех категорий лимиты уже заданы", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            options.forEach { c ->
                                FilterChip(
                                    selected = editor.categoryId == c.id,
                                    onClick = { onCategory(c.id) },
                                    label = { Text(c.name) },
                                    leadingIcon = {
                                        Icon(CategoryIcons.get(c.iconKey), null, tint = Color(c.color), modifier = Modifier.size(IconSize.sm))
                                    },
                                    shape = MaterialTheme.shapes.small,
                                )
                            }
                        }
                    }
                } else {
                    val c = editor.categoryId?.let { state.allCategories[it] }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(CategoryIcons.get(c?.iconKey), c?.let { Color(it.color) } ?: FinTheme.colors.neutral, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(c?.name ?: "", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            }

            val err = editor.error
            OutlinedTextField(
                value = editor.amountText,
                onValueChange = onAmount,
                label = {
                    Text(
                        when {
                            editor.overall -> "Лимит на все расходы в месяц"
                            selected != null -> "Лимит «${selected.name}» в месяц"
                            else -> "Лимит в месяц"
                        },
                        maxLines = 1,
                    )
                },
                suffix = { Text(state.currency) },
                isError = err != null,
                supportingText = if (err != null) ({ Text(err) }) else ({ Text("Можно ввести выражение: 300+150") }),
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave() }),
                modifier = Modifier.fillMaxWidth(),
            )

            if (!keyboardOpen) Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .bounceClick(pressedScale = 0.98f) { onRollover(!editor.rollover) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Переносить остаток", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Неизрасходованная сумма добавится к лимиту следующего месяца. Перерасход не переносится.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = editor.rollover, onCheckedChange = onRollover)
            }

            if (!keyboardOpen) {
                Text(
                    "Действует с месяца «$monthLabel» и далее. Прошлые месяцы не изменятся.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                onClick = onSave,
                enabled = editor.overall || !editor.isNew || editor.categoryId != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    when {
                        !editor.isNew -> "Сохранить"
                        !editor.overall && editor.categoryId == null -> "Выберите категорию"
                        else -> "Добавить лимит"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (!editor.isNew && !keyboardOpen) {
                TextButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
                    Text("Убрать лимит с месяца «$monthLabel»", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ThresholdsDialog(current: BudgetThresholds, onApply: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    var info by remember { mutableIntStateOf(current.infoPercent) }
    var warn by remember { mutableIntStateOf(current.warningPercent) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Пороги бюджета") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Когда расходы достигают порога, бюджет помечается. Превышение — всегда при 100 %.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ThresholdSlider(Icons.Rounded.Info, "Информация", info, 10..90) {
                    info = it
                    if (warn <= info) warn = (info + 5).coerceAtMost(95)
                }
                ThresholdSlider(Icons.Rounded.Warning, "Предупреждение", warn, 15..95) {
                    warn = it
                    if (info >= warn) info = (warn - 5).coerceAtLeast(10)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(info, warn) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun ThresholdSlider(icon: ImageVector, label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(IconSize.sm), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("$value %", style = MaterialTheme.typography.labelLarge.merge(AmountStyle))
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange((it / 5f).roundToInt() * 5) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) / 5 - 1,
        )
    }
}
