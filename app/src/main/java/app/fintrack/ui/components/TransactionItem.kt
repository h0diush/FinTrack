package app.fintrack.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fintrack.domain.logic.Money
import app.fintrack.domain.model.TransactionDetails
import app.fintrack.domain.model.TransactionType
import app.fintrack.ui.theme.AmountStyle
import app.fintrack.ui.theme.FinTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val RU: Locale = Locale.forLanguageTag("ru")
private val TIME = DateTimeFormatter.ofPattern("HH:mm", RU)
private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", RU)
private val DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", RU)

object DateText {
    fun time(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(TIME)

    fun dateTime(millis: Long): String {
        val zdt = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        val today = LocalDate.now()
        val date = zdt.toLocalDate()
        val day = when (date) {
            today -> "Сегодня"
            today.minusDays(1) -> "Вчера"
            else -> zdt.format(if (date.year == today.year) DAY_MONTH else DAY_MONTH_YEAR).replace(".", "")
        }
        return "$day, ${zdt.format(TIME)}"
    }

    fun date(date: LocalDate): String {
        val today = LocalDate.now()
        return when (date) {
            today -> "Сегодня"
            today.minusDays(1) -> "Вчера"
            else -> date.format(if (date.year == today.year) DAY_MONTH else DAY_MONTH_YEAR).replace(".", "")
        }
    }
}

/** Строка операции в списках (главная, история). */
@Composable
fun TransactionItem(
    item: TransactionDetails,
    hidden: Boolean,
    showDate: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tx = item.transaction
    val fin = FinTheme.colors

    val (icon, iconColor) = when (tx.type) {
        TransactionType.TRANSFER -> Icons.Rounded.SwapHoriz to fin.transfer
        TransactionType.ADJUSTMENT -> Icons.Rounded.Tune to fin.neutral
        else -> CategoryIcons.get(item.categoryIcon) to (item.categoryColor?.let { Color(it) } ?: fin.neutral)
    }
    val title = when (tx.type) {
        TransactionType.TRANSFER -> "Перевод"
        TransactionType.ADJUSTMENT -> "Корректировка баланса"
        else -> item.categoryName ?: "Без категории"
    }
    val subtitleParts = buildList {
        if (tx.type == TransactionType.TRANSFER) {
            add("${item.accountName} → ${item.toAccountName ?: "?"}")
        } else {
            item.parentCategoryName?.let { add(it) }
            if (tx.payee.isNotBlank()) add(tx.payee)
            add(item.accountName)
        }
        if (tx.comment.isNotBlank()) add(tx.comment)
        add(if (showDate) DateText.dateTime(tx.dateTime) else DateText.time(tx.dateTime))
    }

    val amountColor = when (tx.type) {
        TransactionType.INCOME -> fin.income
        TransactionType.EXPENSE -> fin.expense
        else -> MaterialTheme.colorScheme.onSurface
    }
    val signedAmount = when (tx.type) {
        TransactionType.EXPENSE -> -tx.amountMinor
        else -> tx.amountMinor
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .bounceClick(pressedScale = 0.98f, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon = icon, color = iconColor, size = 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (tx.isFavorite) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Rounded.Star, null, tint = Color(0xFFF5B301), modifier = Modifier.size(14.dp))
                }
            }
            Text(
                subtitleParts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
            AmountText(
                minor = signedAmount,
                currency = item.accountCurrency,
                hidden = hidden,
                color = amountColor,
                forceSign = tx.type == TransactionType.INCOME || tx.type == TransactionType.ADJUSTMENT,
            )
            val secondLine = when {
                tx.type == TransactionType.TRANSFER && item.toAccountCurrency != null &&
                    item.toAccountCurrency != item.accountCurrency && !hidden ->
                    "→ " + Money.format(tx.toAmountMinor ?: 0, item.toAccountCurrency)
                tx.type == TransactionType.TRANSFER && tx.feeMinor > 0 && !hidden ->
                    "комиссия " + Money.format(tx.feeMinor, item.accountCurrency)
                // Покупка в другой валюте: исходная сумма под списанной
                tx.originalAmountMinor != null && tx.originalCurrency != null && !hidden ->
                    Money.format(tx.originalAmountMinor, tx.originalCurrency)
                else -> null
            }
            if (secondLine != null) {
                Text(
                    secondLine,
                    style = MaterialTheme.typography.bodySmall.merge(AmountStyle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
