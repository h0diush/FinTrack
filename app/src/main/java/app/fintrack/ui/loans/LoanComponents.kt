@file:OptIn(ExperimentalLayoutApi::class)

package app.fintrack.ui.loans

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CreditScore
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.House
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.fintrack.domain.logic.DepositCalculator
import app.fintrack.domain.logic.Loan
import app.fintrack.domain.logic.LoanKind
import app.fintrack.domain.logic.Money
import app.fintrack.domain.logic.ruPlural
import app.fintrack.domain.model.AccountWithBalance
import app.fintrack.ui.components.AccountIcons
import app.fintrack.ui.components.AppCard
import app.fintrack.ui.components.FitText
import app.fintrack.ui.components.IconBadge
import app.fintrack.ui.components.lighten
import app.fintrack.ui.piggy.readableOn
import app.fintrack.ui.theme.FinTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val RU: Locale = Locale.forLanguageTag("ru")
val LOAN_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", RU)
val LOAN_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", RU)

/** Иконка, цвет и срок по умолчанию для каждого вида долга. */
object LoanStyle {
    fun icon(kind: LoanKind): ImageVector = when (kind) {
        LoanKind.CREDIT -> Icons.Rounded.CreditScore
        LoanKind.INSTALLMENT -> Icons.Rounded.ShoppingBag
        LoanKind.MORTGAGE -> Icons.Rounded.House
    }

    fun defaultColor(kind: LoanKind): Long = when (kind) {
        LoanKind.CREDIT -> 0xFF2F55E4L
        LoanKind.INSTALLMENT -> 0xFFF97316L
        LoanKind.MORTGAGE -> 0xFF059669L
    }

    /** Типичный срок: кредит — 3 года, рассрочка — 12 месяцев, ипотека — 20 лет. */
    fun defaultTerm(kind: LoanKind): String = when (kind) {
        LoanKind.CREDIT -> "3"
        LoanKind.INSTALLMENT -> "12"
        LoanKind.MORTGAGE -> "20"
    }

    fun hint(kind: LoanKind): String = when (kind) {
        LoanKind.CREDIT -> "Потребительский или автокредит"
        LoanKind.INSTALLMENT -> "Покупка частями, часто без процентов"
        LoanKind.MORTGAGE -> "Жильё в кредит на долгий срок"
    }
}

/** Статус ближайшего платежа: текст и цвет. */
data class LoanStatus(val text: String, val color: Color, val urgent: Boolean)

@Composable
fun loanStatus(loan: Loan, today: LocalDate = LocalDate.now()): LoanStatus {
    val fin = FinTheme.colors
    if (loan.isPaidOff) return LoanStatus("Погашен", fin.statusGood, false)
    val days = loan.daysUntilNext(today)
    return when {
        days < 0 -> LoanStatus("Просрочен на ${-days} ${ruPlural(-days, "день", "дня", "дней")}", fin.statusCritical, true)
        days == 0L -> LoanStatus("Платёж сегодня", fin.statusWarning, true)
        days == 1L -> LoanStatus("Платёж завтра", fin.statusWarning, true)
        days <= 5 -> LoanStatus("Через $days ${ruPlural(days, "день", "дня", "дней")}", fin.statusWarning, false)
        else -> LoanStatus("Через $days ${ruPlural(days, "день", "дня", "дней")}", MaterialTheme.colorScheme.onSurfaceVariant, false)
    }
}

/** Пилюля статуса: мягкий фон цвета статуса, иконка при срочности. */
@Composable
fun StatusPill(status: LoanStatus, modifier: Modifier = Modifier) {
    val bg = MaterialTheme.colorScheme.surfaceContainer
    val text = status.color.readableOn(bg)
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(status.color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status.urgent) {
            Icon(Icons.Rounded.Warning, null, tint = text, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(status.text, style = MaterialTheme.typography.labelMedium, color = text, maxLines = 1)
    }
}

/** Полоса погашения: при показе плавно «дорастает» до доли выплаченного. */
@Composable
fun LoanProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 8.dp, track: Color = color.copy(alpha = 0.16f)) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(fraction) {
        anim.animateTo(fraction.coerceIn(0f, 1f), tween(1000, easing = FastOutSlowInEasing))
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(track),
    ) {
        if (anim.value > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(anim.value)
                    .clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(color.lighten(0.3f), color))),
            )
        }
    }
}

fun Loan.subtitle(): String {
    val rate = if (rateBp == 0L) "без процентов" else "${DepositCalculator.formatRate(rateBp)} % годовых"
    return "${kind.title} · $rate"
}

/** Карточка кредита в списке: остаток долга, прогресс, ближайший платёж. */
@Composable
fun LoanCard(loan: Loan, hidden: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = Color(loan.color)
    val status = loanStatus(loan)
    val next = loan.nextPayment()
    AppCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(LoanStyle.icon(loan.kind), accent, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(loan.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        loan.subtitle(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            FlowRow(verticalArrangement = Arrangement.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (hidden) Money.masked(loan.currency) else Money.format(loan.debtMinor, loan.currency),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.alignByBaseline(),
                )
                Text(
                    "осталось из " + if (hidden) Money.masked(loan.currency) else Money.format(loan.principalMinor, loan.currency),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.alignByBaseline(),
                )
            }
            Spacer(Modifier.height(10.dp))
            LoanProgressBar(loan.paidFraction, accent)
            Spacer(Modifier.height(6.dp))
            Text(
                "Выплачено ${(loan.paidFraction * 100).toInt()} % · ${loan.paidCount} из ${loan.termMonths} ${ruPlural(loan.termMonths.toLong(), "платежа", "платежей", "платежей")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (next != null) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Event, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        FitText(
                            (if (hidden) Money.masked(loan.currency) else Money.format(next.paymentMinor, loan.currency)) +
                                " · " + next.date.format(LOAN_DAY),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    StatusPill(status)
                }
            } else {
                Spacer(Modifier.height(10.dp))
                StatusPill(status)
            }
        }
    }
}

/** Выбор счёта для платежа или зачисления. */
@Composable
fun LoanAccountChoice(item: AccountWithBalance, selected: Boolean, accent: Color, onClick: () -> Unit) {
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

/** Две доли одной суммы на одной полосе: тело долга и проценты. */
@Composable
fun SplitBar(first: Long, second: Long, firstColor: Color, secondColor: Color, modifier: Modifier = Modifier) {
    val total = (first + second).coerceAtLeast(1)
    val target = first.toFloat() / total
    val anim = remember { Animatable(0f) }
    LaunchedEffect(target) { anim.animateTo(target, tween(900, easing = FastOutSlowInEasing)) }
    Row(
        modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(50)),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        val f = anim.value.coerceIn(0.001f, 0.999f)
        Box(Modifier.weight(f).fillMaxHeight().background(firstColor))
        if (second > 0) Box(Modifier.weight(1f - f).fillMaxHeight().background(secondColor))
    }
}
