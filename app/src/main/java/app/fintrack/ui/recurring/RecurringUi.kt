@file:OptIn(ExperimentalLayoutApi::class)

package app.fintrack.ui.recurring

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fintrack.domain.logic.Money
import app.fintrack.domain.logic.RecurrenceCalculator
import app.fintrack.domain.logic.RecurringMode
import app.fintrack.domain.logic.RecurringPayment
import app.fintrack.domain.logic.ruPlural
import app.fintrack.domain.model.AccountType
import app.fintrack.domain.model.AccountWithBalance
import app.fintrack.domain.model.Category
import app.fintrack.domain.model.TransactionType
import app.fintrack.ui.components.AppCard
import app.fintrack.ui.components.CategoryIcons
import app.fintrack.ui.components.FitText
import app.fintrack.ui.components.IconBadge
import app.fintrack.ui.components.contentOn
import app.fintrack.ui.piggy.readableOn
import app.fintrack.ui.theme.FinTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"))

/** Регулярный платёж вместе с тем, что нужно для показа. */
data class RecurringItem(
    val payment: RecurringPayment,
    val title: String,
    /** «Карта → Наличные» или «Продукты · Карта». */
    val subtitle: String,
    val categoryIconKey: String?,
    val color: Long?,
    val toPiggy: Boolean,
    /** Валюта суммы шаблона: валюта платежа (USD) или счёта. */
    val currency: String,
    val accountCurrency: String,
    /** Платёж в другой валюте: сколько это в валюте счёта по текущему курсу (null — курса нет). */
    val approxMinor: Long?,
    /** Валюта, в которой посчитан [perMonthMinor]: валюта счёта, если есть курс. */
    val monthlyCurrency: String,
    val nextDate: LocalDate?,
    val dueCount: Int,
    val perMonthMinor: Long,
    /** Счёт в архиве или удалена категория — провести нельзя, нужно изменить платёж. */
    val broken: Boolean,
) {
    val id: String get() = payment.id
    val isPending: Boolean get() = dueCount > 0 && !payment.isPaused
    val isForeign: Boolean get() = currency != accountCurrency
}

/** Сводка «в месяц» по валюте. */
data class MonthlyLine(val currency: String, val expenseMinor: Long, val incomeMinor: Long)

object RecurringItems {

    fun build(
        list: List<RecurringPayment>,
        accounts: List<AccountWithBalance>,
        categories: List<Category>,
        today: LocalDate = LocalDate.now(),
        converter: app.fintrack.domain.logic.CurrencyConverter? = null,
    ): List<RecurringItem> {
        val accById = accounts.associateBy { it.account.id }
        val catById = categories.associateBy { it.id }
        return list.map { r ->
            val acc = accById[r.accountId]?.account
            val to = r.toAccountId?.let { accById[it]?.account }
            val cat = r.categoryId?.let { catById[it] }
            val transfer = r.type == TransactionType.TRANSFER
            val title = when {
                r.payee.isNotBlank() -> r.payee
                transfer && to?.type == AccountType.PIGGY -> "Копилка «${to.name}»"
                transfer -> "Перевод на «${to?.name ?: "счёт"}»"
                else -> cat?.name ?: "Без категории"
            }
            val subtitle = when {
                transfer -> "${acc?.name ?: "?"} → ${to?.name ?: "?"}"
                r.payee.isNotBlank() -> listOfNotNull(cat?.name, acc?.name).joinToString(" · ")
                else -> acc?.name ?: ""
            }
            val broken = acc == null || acc.isArchived ||
                (transfer && (to == null || to.isArchived)) ||
                (!transfer && cat == null)
            val accCur = acc?.currencyCode ?: ""
            val cur = r.foreignCurrency?.takeIf { it != accCur && !transfer } ?: accCur
            val approx = if (cur != accCur) converter?.convert(r.amountMinor, cur, accCur) else null
            val perMonth = RecurrenceCalculator.perMonthMinor(r.amountMinor, r.rule)
            val perMonthAcc = if (cur != accCur) converter?.convert(perMonth, cur, accCur) else perMonth
            RecurringItem(
                payment = r,
                title = title,
                subtitle = subtitle,
                categoryIconKey = cat?.iconKey,
                color = if (transfer) null else cat?.color,
                toPiggy = to?.type == AccountType.PIGGY,
                currency = cur,
                accountCurrency = accCur,
                approxMinor = approx,
                monthlyCurrency = if (perMonthAcc != null) accCur else cur,
                nextDate = r.nextDate,
                dueCount = r.dueCount(today),
                perMonthMinor = perMonthAcc ?: perMonth,
                broken = broken,
            )
        }
    }

    /** Сколько в среднем уходит и приходит в месяц по активным платежам. Переводы не считаются. */
    fun monthly(items: List<RecurringItem>, baseCurrency: String): List<MonthlyLine> =
        items.filter { !it.payment.isPaused && it.nextDate != null && it.payment.type != TransactionType.TRANSFER }
            .groupBy { it.monthlyCurrency }
            .map { (c, list) ->
                MonthlyLine(
                    currency = c,
                    expenseMinor = list.filter { it.payment.type == TransactionType.EXPENSE }.sumOf { it.perMonthMinor },
                    incomeMinor = list.filter { it.payment.type == TransactionType.INCOME }.sumOf { it.perMonthMinor },
                )
            }
            .sortedBy { if (it.currency == baseCurrency) 0 else 1 }
}

/** «сегодня», «завтра», «через 5 дней», «просрочен на 2 дня». */
fun whenText(date: LocalDate, today: LocalDate = LocalDate.now()): String {
    val days = ChronoUnit.DAYS.between(today, date)
    return when {
        days < 0 -> "просрочен на ${-days} ${ruPlural(-days, "день", "дня", "дней")}"
        days == 0L -> "сегодня"
        days == 1L -> "завтра"
        days <= 7 -> "через $days ${ruPlural(days, "день", "дня", "дней")}"
        else -> date.format(DAY)
    }
}

@Composable
fun recurringIcon(item: RecurringItem): Pair<ImageVector, Color> {
    val fin = FinTheme.colors
    return when {
        item.payment.type == TransactionType.TRANSFER && item.toPiggy -> Icons.Rounded.Savings to Color(0xFFEC4899)
        item.payment.type == TransactionType.TRANSFER -> Icons.Rounded.SwapHoriz to fin.transfer
        else -> CategoryIcons.get(item.categoryIconKey) to (item.color?.let(::Color) ?: MaterialTheme.colorScheme.primary)
    }
}

/** Сумма со знаком и цветом типа: −500 / +2 000 / 300. */
@Composable
fun RecurringAmount(item: RecurringItem, hidden: Boolean, modifier: Modifier = Modifier) {
    val fin = FinTheme.colors
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val (sign, color) = when (item.payment.type) {
        TransactionType.INCOME -> "+" to fin.income.readableOn(surface)
        TransactionType.EXPENSE -> "−" to MaterialTheme.colorScheme.onSurface
        else -> "" to fin.transfer.readableOn(surface)
    }
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text(
            if (hidden) Money.masked(item.currency) else sign + Money.format(item.payment.amountMinor, item.currency),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1,
        )
        // Платёж в другой валюте — сколько это сейчас в валюте счёта
        if (item.isForeign && !hidden) {
            Text(
                item.approxMinor?.let { "≈ " + Money.format(it, item.accountCurrency) } ?: "нет курса",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** Карточка регулярного платежа: иконка, название, правило, ближайшая дата, сумма. */
@Composable
fun RecurringCard(
    item: RecurringItem,
    hidden: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val (icon, color) = recurringIcon(item)
    val p = item.payment
    val dim = p.isPaused || item.nextDate == null
    AppCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, color.copy(alpha = if (dim) 0.5f else 1f), size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    RecurrenceCalculator.describe(p.rule, p.processedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val next = item.nextDate
                    when {
                        p.isPaused -> Pill("На паузе", MaterialTheme.colorScheme.onSurfaceVariant, Icons.Rounded.PauseCircle)
                        next == null -> Pill("Завершён", MaterialTheme.colorScheme.onSurfaceVariant, Icons.Rounded.Check)
                        else -> Pill(
                            text = (if (item.dueCount > 0) "Ждёт · " else "") + whenText(next),
                            color = when {
                                item.dueCount > 0 -> FinTheme.colors.statusWarning
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    if (p.mode == RecurringMode.AUTO && !dim) Pill("Авто", FinTheme.colors.statusGood, Icons.Rounded.AutoMode)
                    if (item.broken) Pill("Нужна правка", FinTheme.colors.statusCritical, Icons.Rounded.Warning)
                }
            }
            Spacer(Modifier.width(8.dp))
            RecurringAmount(item, hidden)
            trailing?.invoke()
        }
    }
}

@Composable
private fun Pill(text: String, color: Color, icon: ImageVector? = null) {
    val bg = MaterialTheme.colorScheme.surfaceContainer
    val fg = color.readableOn(bg)
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.13f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1)
    }
}

/**
 * Платёж, ждущий решения: сумма, дата и кнопки «Провести» / «Пропустить».
 * Нажатие на сумму — провести с другой суммой.
 */
@Composable
fun PendingCard(
    item: RecurringItem,
    hidden: Boolean,
    onConfirm: (amountText: String?) -> String?,
    onSkip: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (icon, color) = recurringIcon(item)
    var editAmount by remember { mutableStateOf(false) }
    val fin = FinTheme.colors
    AppCard(modifier = modifier.fillMaxWidth(), onClick = onOpen) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon, color, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val next = item.nextDate
                    Text(
                        listOfNotNull(
                            next?.let { whenText(it).replaceFirstChar(Char::uppercase) },
                            if (item.dueCount > 1) "ещё ${item.dueCount - 1} ${ruPlural((item.dueCount - 1).toLong(), "пропущенный", "пропущенных", "пропущенных")}" else null,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (next != null && next.isBefore(LocalDate.now())) fin.statusCritical.readableOn(MaterialTheme.colorScheme.surfaceContainer)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                RecurringAmount(item, hidden)
            }
            if (item.broken) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Счёт в архиве или категория удалена — откройте платёж и выберите другие",
                    style = MaterialTheme.typography.bodySmall,
                    color = fin.statusCritical.readableOn(MaterialTheme.colorScheme.surfaceContainer),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val accent = if (item.payment.type == TransactionType.INCOME) fin.income else MaterialTheme.colorScheme.primary
                Button(
                    onClick = { onConfirm(null) },
                    enabled = !item.broken,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = accent.contentOn()),
                ) { FitText("Провести") }
                OutlinedButton(
                    onClick = { editAmount = true },
                    enabled = !item.broken,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = MaterialTheme.shapes.medium,
                ) { FitText("Другая сумма") }
                TextButton(onClick = onSkip, modifier = Modifier.height(48.dp)) { Text("Пропустить") }
            }
        }
    }
    if (editAmount) {
        AmountDialog(
            title = item.title,
            // Платёж в другой валюте: вводится, сколько списано со счёта (в его валюте).
            initial = if (item.isForeign) item.approxMinor?.let { Money.toInput(it, item.accountCurrency) } ?: ""
            else Money.toInput(item.payment.amountMinor, item.currency),
            currency = item.accountCurrency,
            label = if (item.isForeign) {
                "Списано за ${Money.format(item.payment.amountMinor, item.currency)}"
            } else {
                "Сумма в этот раз"
            },
            onDismiss = { editAmount = false },
            onSubmit = { text -> onConfirm(text).also { if (it == null) editAmount = false } },
        )
    }
}

/** Провести с другой суммой (счёт за свет, зарплата с премией). */
@Composable
private fun AmountDialog(
    title: String,
    initial: String,
    currency: String,
    label: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> String?,
) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(16); error = null },
                label = { Text(label) },
                suffix = { Text(currency) },
                isError = error != null,
                supportingText = { Text(error ?: "Сумма в шаблоне не изменится") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { error = onSubmit(text) }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { error = onSubmit(text) }) { Text("Провести") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
