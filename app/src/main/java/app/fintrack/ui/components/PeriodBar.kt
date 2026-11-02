@file:OptIn(ExperimentalMaterial3Api::class)

package app.fintrack.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.fintrack.domain.logic.Period
import app.fintrack.domain.logic.Periods
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * Период: ‹ Ноябрь ›. Стрелки листают месяцы и годы, по нажатию на название —
 * список периодов. Подпись сменяется со сдвигом в сторону листания.
 */
@Composable
fun PeriodBar(
    period: Period,
    onPeriod: (Period) -> Unit,
    modifier: Modifier = Modifier,
    allowAll: Boolean = true,
) {
    val today = LocalDate.now()
    var expanded by remember { mutableStateOf(false) }
    var showCustom by remember { mutableStateOf(false) }
    var direction by remember { mutableIntStateOf(1) }
    val canShift = Periods.shift(period, 0) != null

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = {
                direction = -1
                Periods.shift(period, -1)?.let(onPeriod)
            },
            enabled = canShift,
        ) { Icon(Icons.Rounded.ChevronLeft, "Предыдущий период") }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            TextButton(onClick = { expanded = true }) {
                AnimatedContent(
                    targetState = Periods.label(period, today),
                    transitionSpec = {
                        (slideInHorizontally(tween(Motion.MEDIUM_MS)) { it / 3 * direction } + fadeIn())
                            .togetherWith(slideOutHorizontally(tween(Motion.SHORT_MS)) { -it / 3 * direction } + fadeOut())
                    },
                    label = "periodLabel",
                ) { text ->
                    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                }
                Icon(Icons.Rounded.ExpandMore, null, Modifier.size(IconSize.sm), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                val ym = YearMonth.from(today)
                val options: List<Pair<String, Period?>> = buildList {
                    add("Сегодня" to Period.Today)
                    add("Вчера" to Period.Yesterday)
                    add("Эта неделя" to Period.ThisWeek)
                    add("Прошлая неделя" to Period.LastWeek)
                    add("Этот месяц" to Period.Month(ym))
                    add("Прошлый месяц" to Period.Month(ym.minusMonths(1)))
                    add("Последние 3 месяца" to Period.Last3Months)
                    add("Последние 6 месяцев" to Period.Last6Months)
                    add("Этот год" to Period.Year(today.year))
                    if (allowAll) add("Всё время" to Period.All)
                    add("Выбрать даты…" to null)
                }
                options.forEach { (title, p) ->
                    DropdownMenuItem(
                        text = { Text(title) },
                        onClick = {
                            expanded = false
                            direction = 1
                            if (p == null) showCustom = true else onPeriod(p)
                        },
                    )
                }
            }
        }
        IconButton(
            onClick = {
                direction = 1
                Periods.shift(period, 1)?.let(onPeriod)
            },
            enabled = canShift,
        ) { Icon(Icons.Rounded.ChevronRight, "Следующий период") }
    }

    if (showCustom) {
        val state = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { showCustom = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        val start = state.selectedStartDateMillis
                        if (start != null) {
                            val end = state.selectedEndDateMillis ?: start
                            onPeriod(Period.Custom(utcDate(start), utcDate(end)))
                        }
                        showCustom = false
                    },
                ) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { showCustom = false }) { Text("Отмена") } },
        ) {
            DateRangePicker(state = state, modifier = Modifier.weight(1f))
        }
    }
}

private fun utcDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
