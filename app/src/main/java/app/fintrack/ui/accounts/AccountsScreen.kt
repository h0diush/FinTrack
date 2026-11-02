@file:OptIn(ExperimentalMaterial3Api::class)

package app.fintrack.ui.accounts

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fintrack.domain.logic.Deposit
import app.fintrack.domain.logic.DepositCalculator
import app.fintrack.domain.model.AccountWithBalance
import app.fintrack.ui.components.AccountIcons
import app.fintrack.ui.components.AmountText
import app.fintrack.ui.components.AppCard
import app.fintrack.ui.components.EmptyState
import app.fintrack.ui.components.IconBadge
import app.fintrack.ui.components.SectionTitle
import app.fintrack.ui.theme.FinTheme

private val DEPOSIT_END: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")

@Composable
fun AccountsScreen(
    onBack: () -> Unit,
    onAddAccount: () -> Unit,
    onOpenAccount: (String) -> Unit,
    onOpenHistory: (String) -> Unit,
    onOpenPiggy: (String) -> Unit,
    vm: AccountsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showArchive by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Счета") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
                actions = {
                    IconButton(onClick = onAddAccount) { Icon(Icons.Rounded.Add, "Новый счёт") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                AppCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Всего на счетах", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.totals.isEmpty()) {
                            Text("—", style = MaterialTheme.typography.headlineMedium)
                        }
                        state.totals.forEachIndexed { i, (currency, sum) ->
                            AmountText(
                                minor = sum,
                                currency = currency,
                                hidden = state.hideAmounts,
                                animate = true,
                                style = if (i == 0) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
                            )
                        }
                        if (state.excludedCount > 0) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Не входят в сумму: ${state.excludedCount} — отмечены «вне баланса»",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (!state.loading && state.active.isEmpty()) {
                item { EmptyState("Счетов пока нет. Нажмите «+» вверху, чтобы добавить.") }
            }
            items(state.active, key = { it.account.id }) { item ->
                val index = state.active.indexOf(item)
                AccountRow(
                    item = item,
                    hidden = state.hideAmounts,
                    deposit = state.deposits[item.account.id],
                    modifier = Modifier.animateItem(),
                    onClick = { onOpenAccount(item.account.id) },
                    menu = { close ->
                        DropdownMenuItem(
                            text = { Text("Операции") },
                            leadingIcon = { Icon(Icons.Rounded.History, null) },
                            onClick = { close(); onOpenHistory(item.account.id) },
                        )
                        if (index > 0) {
                            DropdownMenuItem(
                                text = { Text("Выше") },
                                leadingIcon = { Icon(Icons.Rounded.ArrowUpward, null) },
                                onClick = { close(); vm.move(item.account.id, -1) },
                            )
                        }
                        if (index < state.active.lastIndex) {
                            DropdownMenuItem(
                                text = { Text("Ниже") },
                                leadingIcon = { Icon(Icons.Rounded.ArrowDownward, null) },
                                onClick = { close(); vm.move(item.account.id, 1) },
                            )
                        }
                    },
                )
            }
            if (state.piggies.isNotEmpty()) {
                item { SectionTitle("Копилки") }
                items(state.piggies, key = { "p:" + it.account.id }) { item ->
                    val index = state.piggies.indexOf(item)
                    AccountRow(
                        item = item,
                        hidden = state.hideAmounts,
                        modifier = Modifier.animateItem(),
                        onClick = { onOpenPiggy(item.account.id) },
                        menu = { close ->
                            DropdownMenuItem(
                                text = { Text("Операции") },
                                leadingIcon = { Icon(Icons.Rounded.History, null) },
                                onClick = { close(); onOpenHistory(item.account.id) },
                            )
                            if (index > 0) {
                                DropdownMenuItem(
                                    text = { Text("Выше") },
                                    leadingIcon = { Icon(Icons.Rounded.ArrowUpward, null) },
                                    onClick = { close(); vm.move(item.account.id, -1) },
                                )
                            }
                            if (index < state.piggies.lastIndex) {
                                DropdownMenuItem(
                                    text = { Text("Ниже") },
                                    leadingIcon = { Icon(Icons.Rounded.ArrowDownward, null) },
                                    onClick = { close(); vm.move(item.account.id, 1) },
                                )
                            }
                        },
                    )
                }
            }
            if (state.archived.isNotEmpty()) {
                item {
                    SectionTitle("Архив (${state.archived.size})") {
                        TextButton(onClick = { showArchive = !showArchive }) { Text(if (showArchive) "Скрыть" else "Показать") }
                    }
                }
                if (showArchive) {
                    items(state.archived, key = { "a:" + it.account.id }) { item ->
                        AccountRow(
                            item = item,
                            hidden = state.hideAmounts,
                            modifier = Modifier.animateItem(),
                            onClick = { onOpenAccount(item.account.id) },
                            menu = { close ->
                                DropdownMenuItem(
                                    text = { Text("Операции") },
                                    onClick = { close(); onOpenHistory(item.account.id) },
                                )
                            },
                            dimmed = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountRow(
    item: AccountWithBalance,
    hidden: Boolean,
    onClick: () -> Unit,
    menu: @Composable (close: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    deposit: Deposit? = null,
    dimmed: Boolean = false,
) {
    val a = item.account
    var expanded by remember { mutableStateOf(false) }
    val fin = FinTheme.colors
    AppCard(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(AccountIcons.get(a.type), Color(a.color).copy(alpha = if (dimmed) 0.5f else 1f), size = 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(a.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(
                    if (deposit != null) {
                        "${DepositCalculator.formatRate(deposit.rateBp)} % годовых · до ${deposit.endDate.format(DEPOSIT_END)}"
                    } else {
                        a.subtitle()
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            AmountText(
                minor = item.balanceMinor,
                currency = a.currencyCode,
                hidden = hidden,
                color = when {
                    item.balanceMinor < 0 -> fin.expense
                    a.excludeFromTotal -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
                animate = true,
            )
            Box {
                IconButton(onClick = { expanded = true }) { Icon(Icons.Rounded.MoreVert, "Действия") }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    menu { expanded = false }
                }
            }
        }
    }
}
