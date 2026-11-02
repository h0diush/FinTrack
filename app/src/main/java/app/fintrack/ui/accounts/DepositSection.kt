@file:OptIn(ExperimentalMaterial3Api::class)

package app.fintrack.ui.accounts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.fintrack.domain.logic.Deposit
import app.fintrack.domain.logic.DepositCalculator
import app.fintrack.domain.logic.Money
import app.fintrack.ui.charts.LineChart
import app.fintrack.ui.components.AppCard
import app.fintrack.ui.components.CountUpAmount
import app.fintrack.ui.components.IconBadge
import app.fintrack.ui.components.IconSize
import app.fintrack.ui.components.Motion
import app.fintrack.ui.components.SlidingSegmented
import app.fintrack.ui.components.bounceClick
import app.fintrack.ui.theme.AmountStyle
import app.fintrack.ui.theme.FinTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val RU: Locale = Locale.forLanguageTag("ru")
private val DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", RU)
private val SHORT = DateTimeFormatter.ofPattern("MMM yy", RU)

private val QUICK_TERMS = listOf(6, 12, 24, 36, 60)

/**
 * Вклад на накопительном счёте: ставка, срок, дата открытия и живой прогноз —
 * сколько начислится в следующем месяце, сколько будет к концу срока, рост по месяцам.
 */
@Composable
fun DepositSection(
    state: AccountEditUiState,
    previewBalanceMinor: Long,
    onEnabled: (Boolean) -> Unit,
    onRate: (String) -> Unit,
    onTerm: (String) -> Unit,
    onTermUnit: (Boolean) -> Unit,
    onTermMonths: (Int) -> Unit,
    onStart: (LocalDate) -> Unit,
) {
    val fin = FinTheme.colors
    var showDatePicker by remember { mutableStateOf(false) }

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .bounceClick(pressedScale = 0.98f) { onEnabled(!state.depositEnabled) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBadge(Icons.Rounded.Savings, fin.statusGood, size = 40.dp, selected = state.depositEnabled)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Проценты по вкладу", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Ежемесячное начисление с капитализацией",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.depositEnabled, onCheckedChange = onEnabled)
            }

            AnimatedVisibility(
                visible = state.depositEnabled,
                enter = expandVertically(tween(Motion.MEDIUM_MS)) + fadeIn(),
                exit = shrinkVertically(tween(Motion.SHORT_MS)) + fadeOut(tween(Motion.SHORT_MS)),
            ) {
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = state.rateText,
                            onValueChange = onRate,
                            label = { Text("Ставка") },
                            suffix = { Text("% год.") },
                            isError = state.rateError,
                            supportingText = if (state.rateError) ({ Text("Например, 12,5") }) else null,
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = state.termText,
                            onValueChange = onTerm,
                            label = { Text("Срок") },
                            suffix = { Text(if (state.termInYears) "лет" else "мес.") },
                            isError = state.termError,
                            supportingText = if (state.termError) {
                                ({ Text(if (state.depositPosted > 0) "Не меньше ${state.depositPosted} мес." else "Целое число месяцев") })
                            } else {
                                null
                            },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    SlidingSegmented(
                        options = listOf("Годы", "Месяцы"),
                        selectedIndex = if (state.termInYears) 0 else 1,
                        onSelect = { onTermUnit(it == 0) },
                        pillColors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary),
                        height = 40.dp,
                    )
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        QUICK_TERMS.forEach { m ->
                            FilterChip(
                                selected = state.termMonths == m,
                                onClick = { onTermMonths(m) },
                                label = { Text(DepositCalculator.formatTerm(m)) },
                                shape = MaterialTheme.shapes.small,
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Дата открытия",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        AssistChip(
                            onClick = { showDatePicker = true },
                            enabled = state.depositStartEditable,
                            label = { Text(state.depositStart.format(DATE)) },
                            leadingIcon = { Icon(Icons.Rounded.CalendarToday, null, Modifier.size(IconSize.sm)) },
                            shape = MaterialTheme.shapes.small,
                            colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        )
                    }

                    val rate = state.rateBp
                    val term = state.termMonths
                    if (rate != null && term != null && term >= state.depositPosted) {
                        DepositPreview(
                            balanceMinor = previewBalanceMinor,
                            currency = state.currency,
                            rateBp = rate,
                            termMonths = term,
                            start = state.depositStart,
                            posted = state.depositPosted,
                            earnedMinor = state.interestEarnedMinor,
                        )
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.depositStart.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { onStart(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    showDatePicker = false
                }) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Отмена") } },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun DepositPreview(
    balanceMinor: Long,
    currency: String,
    rateBp: Long,
    termMonths: Int,
    start: LocalDate,
    posted: Int,
    earnedMinor: Long,
) {
    val fin = FinTheme.colors
    val today = LocalDate.now()
    val rows = remember(balanceMinor, rateBp, termMonths, start, posted) {
        DepositCalculator.schedule(balanceMinor, rateBp, start, posted + 1, termMonths)
    }
    val pending = remember(rateBp, termMonths, start, posted, today) {
        DepositCalculator.dueIndices(Deposit("", rateBp, start, termMonths, null, posted), today).count()
    }
    val next = rows.firstOrNull { it.date.isAfter(today) }
    val finalBalance = rows.lastOrNull()?.balanceMinor ?: balanceMinor
    val totalIncome = earnedMinor + rows.sumOf { it.interestMinor }
    val endDate = DepositCalculator.postingDate(start, termMonths)
    val finished = posted >= termMonths

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Итог к концу срока
        Column(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(Brush.linearGradient(listOf(fin.statusGood.copy(alpha = 0.16f), fin.statusGood.copy(alpha = 0.04f))))
                .padding(16.dp),
        ) {
            Text(
                if (finished) "Срок вклада окончен ${endDate.format(DATE)}" else "К концу срока · ${endDate.format(DATE)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CountUpAmount(finalBalance, currency, hidden = false, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Доход за весь срок: ${Money.format(totalIncome, currency, forceSign = true)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        if (next != null) {
            InfoRow("Следующее начисление · ${next.date.format(DATE)}", Money.format(next.interestMinor, currency, forceSign = true))
        }
        InfoRow(
            "Эффективная ставка",
            "%.2f %% годовых".format(RU, DepositCalculator.effectiveRatePercent(rateBp)),
        )
        InfoRow("Срок", DepositCalculator.formatTerm(termMonths))
        if (posted > 0) {
            InfoRow("Уже начислено · $posted из $termMonths мес.", Money.format(earnedMinor, currency, forceSign = true))
        }

        // Прогресс срока
        val progress = remember { Animatable(0f) }
        val target = (posted.toFloat() / termMonths).coerceIn(0f, 1f)
        LaunchedEffect(target) { progress.animateTo(target, tween(Motion.LONG_MS + 300, easing = FastOutSlowInEasing)) }
        val track = MaterialTheme.colorScheme.surfaceContainerHigh
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .drawBehind {
                    val r = CornerRadius(size.height / 2f)
                    drawRoundRect(track, cornerRadius = r)
                    if (progress.value > 0f) {
                        drawRoundRect(fin.statusGood, size = Size(size.width * progress.value, size.height), cornerRadius = r)
                    }
                },
        )

        if (pending > 0) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(IconSize.sm))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Дата открытия в прошлом: после сохранения будут начислены проценты за $pending мес.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // Рост остатка по месяцам
        if (rows.size >= 2) {
            val points = remember(rows, balanceMinor) {
                val all = listOf(start.plusMonths(posted.toLong()) to balanceMinor) + rows.map { it.date to it.balanceMinor }
                val step = ((all.size - 1) / 60).coerceAtLeast(1)
                all.filterIndexed { i, _ -> i % step == 0 || i == all.lastIndex }
            }
            var selected by remember(points) { mutableStateOf<Int?>(null) }
            Text("Рост вклада", style = MaterialTheme.typography.titleSmall)
            LineChart(
                values = points.map { it.second },
                labels = points.map { it.first.format(SHORT).replace(".", "") },
                color = fin.chartIncome,
                currency = currency,
                selectedIndex = selected,
                onSelect = { selected = it },
                contentDescription = "Рост вклада до ${Money.format(finalBalance, currency)} к ${endDate.format(DATE)}",
                tooltip = { i -> "${points[i].first.format(SHORT).replace(".", "")}: ${Money.format(points[i].second, currency)}" },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp),
            )
        }

        // По годам
        val years = remember(rows) { DepositCalculator.byYear(rows) }
        if (years.isNotEmpty()) {
            Text("По годам", style = MaterialTheme.typography.titleSmall)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                YearRow("Год", "Проценты", "Остаток", header = true)
                years.forEach { y ->
                    YearRow(
                        y.year.toString(),
                        Money.format(y.interestMinor, currency, forceSign = true),
                        Money.format(y.endBalanceMinor, currency),
                    )
                }
            }
        }

        Text(
            "Проценты начисляются раз в месяц в день открытия на фактический остаток счёта и добавляются к нему. " +
                "Прогноз — при неизменном остатке, без пополнений и снятий. В статистике проценты учитываются как доход.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium.merge(AmountStyle), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun YearRow(year: String, interest: String, balance: String, header: Boolean = false) {
    val style = if (header) {
        MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        MaterialTheme.typography.bodySmall.merge(AmountStyle)
    }
    Row(Modifier.padding(vertical = 5.dp)) {
        Text(year, style = style, modifier = Modifier.width(52.dp))
        Text(interest, style = style, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        Text(balance, style = style, modifier = Modifier.weight(1.3f), textAlign = TextAlign.End, fontWeight = if (header) null else FontWeight.SemiBold)
    }
}
