@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.fintrack.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.fintrack.domain.logic.RecurrenceCalculator
import app.fintrack.domain.logic.RecurringMode
import app.fintrack.domain.logic.RepeatRule
import app.fintrack.domain.logic.RepeatUnit
import app.fintrack.domain.logic.ruPlural
import app.fintrack.ui.components.Motion
import app.fintrack.ui.components.SlidingSegmented
import app.fintrack.ui.components.contentOn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val RU: Locale = Locale.forLanguageTag("ru")
private val SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", RU)
private val FULL: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", RU)

enum class RepeatEnd { NEVER, DATE, COUNT }

/** Настройки повтора в форме операции. Первая дата — дата операции. */
data class RepeatDraft(
    val unit: RepeatUnit = RepeatUnit.MONTH,
    val interval: Int = 1,
    val lastDayOfMonth: Boolean = false,
    val end: RepeatEnd = RepeatEnd.NEVER,
    val endDate: LocalDate? = null,
    val count: Int = 12,
    val mode: RecurringMode = RecurringMode.CONFIRM,
) {
    fun toRule(start: LocalDate) = RepeatRule(
        unit = unit,
        interval = interval,
        start = start,
        lastDayOfMonth = unit == RepeatUnit.MONTH && lastDayOfMonth,
        endDate = endDate?.takeIf { end == RepeatEnd.DATE },
        maxCount = count.takeIf { end == RepeatEnd.COUNT },
    )
}

private fun unitWord(unit: RepeatUnit, n: Int): String = when (unit) {
    RepeatUnit.DAY -> ruPlural(n.toLong(), "день", "дня", "дней")
    RepeatUnit.WEEK -> ruPlural(n.toLong(), "неделю", "недели", "недель")
    RepeatUnit.MONTH -> ruPlural(n.toLong(), "месяц", "месяца", "месяцев")
    RepeatUnit.YEAR -> ruPlural(n.toLong(), "год", "года", "лет")
}

private val UNIT_CHIPS = listOf(
    RepeatUnit.DAY to "Ежедневно",
    RepeatUnit.WEEK to "Еженедельно",
    RepeatUnit.MONTH to "Ежемесячно",
    RepeatUnit.YEAR to "Ежегодно",
)

private val WEEKDAYS = listOf(
    DayOfWeek.MONDAY to "Пн", DayOfWeek.TUESDAY to "Вт", DayOfWeek.WEDNESDAY to "Ср", DayOfWeek.THURSDAY to "Чт",
    DayOfWeek.FRIDAY to "Пт", DayOfWeek.SATURDAY to "Сб", DayOfWeek.SUNDAY to "Вс",
)

/**
 * Нижняя панель «Повтор»: периодичность, окончание, режим проведения и ближайшие даты.
 * [onDone] с null — «Не повторять».
 */
@Composable
fun RepeatSheet(
    initial: RepeatDraft?,
    date: LocalDate,
    accent: Color,
    allowNone: Boolean,
    onDateChange: (LocalDate) -> Unit,
    onDone: (RepeatDraft?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(initial != null || !allowNone) }
    var d by remember { mutableStateOf(initial ?: RepeatDraft()) }
    var pickEnd by remember { mutableStateOf(false) }

    fun finish(result: RepeatDraft?) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDone(result) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Повторять", style = MaterialTheme.typography.titleLarge)

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (allowNone) {
                    FilterChip(
                        selected = !enabled,
                        onClick = { enabled = false },
                        label = { Text("Не повторять") },
                        colors = chipColors(accent),
                    )
                }
                UNIT_CHIPS.forEach { (unit, title) ->
                    FilterChip(
                        selected = enabled && d.unit == unit,
                        onClick = { enabled = true; d = d.copy(unit = unit) },
                        label = { Text(title) },
                        colors = chipColors(accent),
                    )
                }
            }

            AnimatedVisibility(
                visible = enabled,
                enter = expandVertically(tween(Motion.MEDIUM_MS)) + fadeIn(),
                exit = shrinkVertically(tween(Motion.SHORT_MS)) + fadeOut(tween(Motion.SHORT_MS)),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Каждые N единиц
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Интервал", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        FilledTonalIconButton(
                            onClick = { d = d.copy(interval = (d.interval - 1).coerceAtLeast(1)) },
                            enabled = d.interval > 1,
                        ) { Icon(Icons.Rounded.Remove, "Реже") }
                        Text(
                            "${d.interval} ${unitWord(d.unit, d.interval)}",
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(112.dp),
                        )
                        FilledTonalIconButton(
                            onClick = { d = d.copy(interval = (d.interval + 1).coerceAtMost(RecurrenceCalculator.MAX_INTERVAL)) },
                        ) { Icon(Icons.Rounded.Add, "Чаще") }
                    }

                    if (d.unit == RepeatUnit.WEEK) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            WEEKDAYS.forEach { (day, label) ->
                                FilterChip(
                                    selected = date.dayOfWeek == day,
                                    onClick = { onDateChange(RecurrenceCalculator.nextWeekday(date, day)) },
                                    label = { Text(label, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                                    colors = chipColors(accent),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    if (d.unit == RepeatUnit.MONTH) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("В последний день месяца", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    if (d.lastDayOfMonth) "30-е, 31-е или 28-е — по месяцу" else "Иначе — ${date.dayOfMonth}-го числа каждого месяца",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = d.lastDayOfMonth, onCheckedChange = { d = d.copy(lastDayOfMonth = it) })
                        }
                    }

                    Text("Окончание", style = MaterialTheme.typography.titleSmall)
                    SlidingSegmented(
                        options = listOf("Бессрочно", "До даты", "Число раз"),
                        selectedIndex = d.end.ordinal,
                        onSelect = { i ->
                            val e = RepeatEnd.entries[i]
                            d = d.copy(end = e, endDate = d.endDate ?: date.plusYears(1))
                            if (e == RepeatEnd.DATE) pickEnd = true
                        },
                        pillColors = listOf(accent, accent, accent),
                        height = 40.dp,
                    )
                    when (d.end) {
                        RepeatEnd.DATE -> TextButton(onClick = { pickEnd = true }) {
                            Icon(Icons.Rounded.Event, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Последний платёж не позже ${(d.endDate ?: date).format(FULL)}")
                        }
                        RepeatEnd.COUNT -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Всего платежей", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            FilledTonalIconButton(onClick = { d = d.copy(count = (d.count - 1).coerceAtLeast(1)) }, enabled = d.count > 1) {
                                Icon(Icons.Rounded.Remove, "Меньше")
                            }
                            Text("${d.count}", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.width(56.dp))
                            FilledTonalIconButton(onClick = { d = d.copy(count = (d.count + 1).coerceAtMost(999)) }) { Icon(Icons.Rounded.Add, "Больше") }
                        }
                        RepeatEnd.NEVER -> Unit
                    }

                    Text("Когда наступит дата", style = MaterialTheme.typography.titleSmall)
                    ModeOption(
                        selected = d.mode == RecurringMode.CONFIRM,
                        icon = Icons.Rounded.NotificationsActive,
                        title = "Спрашивать",
                        text = "Напомним уведомлением — проведёте, измените сумму или пропустите",
                        accent = accent,
                    ) { d = d.copy(mode = RecurringMode.CONFIRM) }
                    ModeOption(
                        selected = d.mode == RecurringMode.AUTO,
                        icon = Icons.Rounded.AutoMode,
                        title = "Проводить автоматически",
                        text = "Операция появится сама, пришлём уведомление. Подойдёт для подписок и зарплаты",
                        accent = accent,
                    ) { d = d.copy(mode = RecurringMode.AUTO) }

                    // Ближайшие даты — сразу видно, правильно ли настроено
                    val next = RecurrenceCalculator.upcoming(d.toRule(date), 0, 4)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(accent.copy(alpha = 0.10f))
                            .padding(14.dp),
                    ) {
                        Text(RecurrenceCalculator.describe(d.toRule(date)), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (next.isEmpty()) "Нет ни одной даты — проверьте окончание"
                            else "Ближайшие: " + next.joinToString(", ") { it.format(SHORT).replace(".", "") },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Button(
                onClick = { finish(if (enabled) d else null) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = accent.contentOn()),
            ) { Text("Готово") }
        }
    }

    if (pickEnd) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (d.endDate ?: date.plusYears(1)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickEnd = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { d = d.copy(endDate = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    pickEnd = false
                }) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { pickEnd = false }) { Text("Отмена") } },
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun chipColors(accent: Color) = FilterChipDefaults.filterChipColors(
    selectedContainerColor = accent,
    selectedLabelColor = accent.contentOn(),
)

@Composable
private fun ModeOption(selected: Boolean, icon: ImageVector, title: String, text: String, accent: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .border(if (selected) 2.dp else 1.dp, if (selected) accent else MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            null,
            tint = if (selected) accent else MaterialTheme.colorScheme.outline,
        )
    }
}
