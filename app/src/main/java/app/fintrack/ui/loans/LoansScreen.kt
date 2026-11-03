@file:OptIn(ExperimentalMaterial3Api::class)

package app.fintrack.ui.loans

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.RequestQuote
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fintrack.domain.logic.Loan
import app.fintrack.domain.logic.LoanKind
import app.fintrack.domain.logic.Money
import app.fintrack.ui.components.AppCard
import app.fintrack.ui.components.CountUpAmount
import app.fintrack.ui.components.FitText
import app.fintrack.ui.components.IconBadge
import app.fintrack.ui.components.SectionTitle
import app.fintrack.ui.components.StaggeredAppear
import app.fintrack.ui.theme.FinTheme

@Composable
fun LoansScreen(
    onBack: () -> Unit,
    onOpenLoan: (String) -> Unit,
    onNewLoan: (kind: String?) -> Unit,
    vm: LoansViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showClosed by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Кредиты") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
                actions = { IconButton(onClick = { onNewLoan(null) }) { Icon(Icons.Rounded.Add, "Новый кредит") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.loading) return@LazyColumn
            if (state.active.isEmpty()) {
                item { StaggeredAppear(0) { EmptyLoans(onNewLoan) } }
            } else {
                item { StaggeredAppear(0) { DebtHero(state) } }
                item { SectionTitle("Активные") }
                items(state.active, key = { it.accountId }) { loan ->
                    val index = state.active.indexOf(loan)
                    StaggeredAppear(index + 1) {
                        LoanCard(
                            loan = loan,
                            hidden = state.hideAmounts,
                            onClick = { onOpenLoan(loan.accountId) },
                            modifier = Modifier.padding(horizontal = 16.dp).animateItem(),
                        )
                    }
                }
            }
            if (state.closed.isNotEmpty()) {
                item {
                    SectionTitle("Закрытые (${state.closed.size})") {
                        TextButton(onClick = { showClosed = !showClosed }) { Text(if (showClosed) "Скрыть" else "Показать") }
                    }
                }
                if (showClosed) {
                    items(state.closed, key = { "c:" + it.accountId }) { loan ->
                        ClosedLoanRow(loan, onClick = { onOpenLoan(loan.accountId) }, modifier = Modifier.animateItem())
                    }
                }
            }
        }
    }
}

/** Шапка: общий долг по валютам и сколько платить в ближайший месяц. */
@Composable
private fun DebtHero(state: LoansUiState) {
    val fin = FinTheme.colors
    val h = state.hideAmounts
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(Brush.linearGradient(listOf(fin.heroStart, fin.heroMid, fin.heroEnd)))
            .drawBehind {
                drawCircle(Color.White.copy(alpha = 0.08f), radius = size.width * 0.38f, center = Offset(size.width * 0.95f, 0f))
            }
            .padding(20.dp),
    ) {
        Text("Общий долг", style = MaterialTheme.typography.labelLarge, color = fin.onHeroMuted)
        if (state.debts.isEmpty()) {
            Text("Все долги погашены", style = MaterialTheme.typography.headlineSmall, color = fin.onHero, fontWeight = FontWeight.Bold)
        }
        state.debts.forEachIndexed { i, d ->
            CountUpAmount(
                minor = d.debtMinor,
                currency = d.currency,
                hidden = h,
                style = if (i == 0) MaterialTheme.typography.displaySmall else MaterialTheme.typography.titleLarge,
                color = fin.onHero,
                fontWeight = FontWeight.Bold,
            )
        }
        val nearest = state.nearest
        if (state.debts.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroTile(
                    label = "Платежи за месяц",
                    value = state.debts.joinToString("\n") { if (h) Money.masked(it.currency) else Money.format(it.monthlyMinor, it.currency) },
                    modifier = Modifier.weight(1f),
                )
                if (nearest != null) {
                    HeroTile(
                        label = "Ближайший · ${nearest.nextDate.format(LOAN_DAY)}",
                        value = nearest.name,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
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
        FitText(value, style = MaterialTheme.typography.titleSmall, color = fin.onHero, fontWeight = FontWeight.SemiBold)
    }
}

/** Пустой список: три плитки — сразу выбрать вид долга. */
@Composable
private fun EmptyLoans(onNewLoan: (String?) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconBadge(Icons.Rounded.RequestQuote, MaterialTheme.colorScheme.primary, size = 64.dp, selected = true)
            Spacer(Modifier.height(14.dp))
            Text("Кредитов пока нет", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "Добавьте кредит, рассрочку или ипотеку — приложение построит график, напомнит о дате платежа " +
                    "и разделит каждый платёж на проценты и погашение долга",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        LoanKind.entries.forEach { kind ->
            val accent = Color(LoanStyle.defaultColor(kind))
            AppCard(Modifier.fillMaxWidth(), onClick = { onNewLoan(kind.name) }) {
                Row(Modifier.padding(16.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(LoanStyle.icon(kind), accent, size = 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(kind.title, style = MaterialTheme.typography.titleSmall)
                        Text(LoanStyle.hint(kind), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

@Composable
private fun ClosedLoanRow(loan: Loan, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AppCard(modifier.padding(horizontal = 16.dp).fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(LoanStyle.icon(loan.kind), Color(loan.color).copy(alpha = 0.5f), size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(loan.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(
                    if (loan.isPaidOff) "Погашен полностью" else "Закрыт · долг ${Money.format(loan.debtMinor, loan.currency)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
