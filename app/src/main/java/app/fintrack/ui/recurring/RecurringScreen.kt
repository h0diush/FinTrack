@file:OptIn(ExperimentalMaterial3Api::class)

package app.fintrack.ui.recurring

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fintrack.domain.logic.Money
import app.fintrack.ui.components.ConfirmDialog
import app.fintrack.ui.components.FitText
import app.fintrack.ui.components.IconBadge
import app.fintrack.ui.components.SectionTitle
import app.fintrack.ui.components.StaggeredAppear
import app.fintrack.ui.theme.FinTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecurringScreen(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    vm: RecurringViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var toDelete by remember { mutableStateOf<RecurringItem?>(null) }

    LaunchedEffect(Unit) {
        vm.messages.collect { scope.launch { snackbar.showSnackbar(it) } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Регулярные платежи") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
                actions = { IconButton(onClick = onNew) { Icon(Icons.Rounded.Add, "Новый регулярный платёж") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.loading) return@LazyColumn
            if (state.isEmpty) {
                item { StaggeredAppear(0) { EmptyRecurring(onNew) } }
                return@LazyColumn
            }
            item { StaggeredAppear(0) { MonthlyHero(state) } }
            if (state.pending.isNotEmpty()) {
                item { SectionTitle("Ждут подтверждения") }
                items(state.pending, key = { "p:" + it.id }) { item ->
                    PendingCard(
                        item = item,
                        hidden = state.hideAmounts,
                        onConfirm = { text -> vm.confirm(item, text) },
                        onSkip = { vm.skip(item) },
                        onOpen = { onOpen(item.id) },
                        modifier = Modifier.padding(horizontal = 16.dp).animateItem(),
                    )
                }
            }
            if (state.active.isNotEmpty()) {
                item { SectionTitle("Запланированы") }
                items(state.active, key = { it.id }) { item ->
                    RecurringCard(
                        item = item,
                        hidden = state.hideAmounts,
                        onClick = { onOpen(item.id) },
                        modifier = Modifier.padding(horizontal = 16.dp).animateItem(),
                        trailing = { RecurringMenu(item, onPause = { vm.setPaused(item, true) }, onResume = null, onDelete = { toDelete = item }) },
                    )
                }
            }
            if (state.inactive.isNotEmpty()) {
                item { SectionTitle("На паузе и завершённые") }
                items(state.inactive, key = { "i:" + it.id }) { item ->
                    RecurringCard(
                        item = item,
                        hidden = state.hideAmounts,
                        onClick = { onOpen(item.id) },
                        modifier = Modifier.padding(horizontal = 16.dp).animateItem(),
                        trailing = {
                            RecurringMenu(
                                item,
                                onPause = null,
                                onResume = if (item.payment.isPaused && item.nextDate != null) ({ vm.setPaused(item, false) }) else null,
                                onDelete = { toDelete = item },
                            )
                        },
                    )
                }
            }
        }
    }

    toDelete?.let { item ->
        ConfirmDialog(
            title = "Удалить «${item.title}»?",
            text = "Новые платежи больше не появятся. Уже проведённые операции останутся в истории.",
            confirm = "Удалить",
            onConfirm = { toDelete = null; vm.delete(item) },
            onDismiss = { toDelete = null },
        )
    }
}

/** Меню «⋮» карточки: пауза / возобновить / удалить. */
@Composable
private fun RecurringMenu(
    item: RecurringItem,
    onPause: (() -> Unit)?,
    onResume: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Rounded.MoreVert, "Действия с «${item.title}»", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (onPause != null) {
                DropdownMenuItem(text = { Text("Поставить на паузу") }, leadingIcon = { Icon(Icons.Rounded.Pause, null) }, onClick = { open = false; onPause() })
            }
            if (onResume != null) {
                DropdownMenuItem(text = { Text("Возобновить") }, leadingIcon = { Icon(Icons.Rounded.PlayArrow, null) }, onClick = { open = false; onResume() })
            }
            DropdownMenuItem(text = { Text("Удалить") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { open = false; onDelete() })
        }
    }
}

/** Шапка: сколько в среднем уходит и приходит в месяц по регулярным платежам. */
@Composable
private fun MonthlyHero(state: RecurringUiState) {
    val fin = FinTheme.colors
    val h = state.hideAmounts
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(Brush.linearGradient(listOf(fin.heroStart, fin.heroMid, fin.heroEnd)))
            .drawBehind { drawCircle(Color.White.copy(alpha = 0.08f), radius = size.width * 0.36f, center = Offset(size.width * 0.96f, 0f)) }
            .padding(20.dp),
    ) {
        Text("В среднем за месяц", style = MaterialTheme.typography.labelLarge, color = fin.onHeroMuted)
        Spacer(Modifier.height(10.dp))
        if (state.monthly.isEmpty()) {
            Text("Только переводы — на доходы и расходы не влияют", style = MaterialTheme.typography.bodyMedium, color = fin.onHero)
        }
        state.monthly.forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroTile("Обязательные расходы", if (h) Money.masked(line.currency) else "−" + Money.format(line.expenseMinor, line.currency), Modifier.weight(1f))
                HeroTile("Регулярные доходы", if (h) Money.masked(line.currency) else "+" + Money.format(line.incomeMinor, line.currency), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
        }
        val count = state.pending.size + state.active.size
        Text(
            "Активных платежей: $count" + if (state.pending.isNotEmpty()) " · ждут подтверждения: ${state.pending.size}" else "",
            style = MaterialTheme.typography.bodySmall,
            color = fin.onHeroMuted,
        )
    }
}

@Composable
private fun HeroTile(label: String, value: String, modifier: Modifier = Modifier) {
    val fin = FinTheme.colors
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(Color.White.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        FitText(label, style = MaterialTheme.typography.labelSmall, color = fin.onHeroMuted)
        Spacer(Modifier.height(2.dp))
        FitText(value, style = MaterialTheme.typography.titleMedium, color = fin.onHero, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EmptyRecurring(onNew: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(Icons.Rounded.EventRepeat, MaterialTheme.colorScheme.primary, size = 64.dp, selected = true)
        Spacer(Modifier.height(14.dp))
        Text("Регулярных платежей пока нет", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            "Подписки, аренда, интернет, зарплата — задайте один раз, и в день платежа приложение напомнит " +
                "или проведёт операцию само",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        Button(onClick = onNew, modifier = Modifier.height(52.dp), shape = MaterialTheme.shapes.medium) {
            Icon(Icons.Rounded.Add, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Добавить платёж")
        }
    }
}
