package app.fintrack.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fintrack.domain.logic.Money
import app.fintrack.ui.theme.AmountStyle
import java.util.Currency
import java.util.Locale

/**
 * Иконка в «сквиркле». Обычное состояние — мягкая подложка цвета категории;
 * выбранное — насыщенный градиент с белой иконкой и упругим «отскоком».
 */
@Composable
fun IconBadge(
    icon: ImageVector,
    color: Color,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val fill by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(Motion.MEDIUM_MS),
        label = "badgeFill",
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1f,
        animationSpec = Motion.bouncy(),
        label = "badgeScale",
    )
    val shape = RoundedCornerShape(size * 0.32f)
    val tint = lerp(color, color.contentOn(), fill)
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        if (fill > 0f) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = fill }
                    .background(Brush.linearGradient(listOf(color.lighten(0.18f), color))),
            )
        }
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.54f))
    }
}

/** Осветление цвета для градиентов. */
fun Color.lighten(amount: Float): Color = lerp(this, Color.White, amount)

/**
 * Цвет текста поверх акцентного фона: белый на тёмных, тёмный на светлых.
 * В тёмной теме акценты светлые, и белый текст на них не проходит контраст 4.5:1.
 */
fun Color.contentOn(): Color = if (luminance() > 0.3f) Color(0xFF0B1020) else Color.White

/** Сумма с учётом режима скрытия. Смена значения анимируется «прокруткой». */
@Composable
fun AmountText(
    minor: Long,
    currency: String,
    hidden: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    forceSign: Boolean = false,
    fontWeight: FontWeight? = FontWeight.SemiBold,
    animate: Boolean = false,
) {
    val text = if (hidden) Money.masked(currency) else Money.format(minor, currency, forceSign)
    if (!animate) {
        FitText(text, modifier = modifier, color = color, style = style.merge(AmountStyle), fontWeight = fontWeight, minFontSize = 11.sp)
        return
    }
    AnimatedContent(
        targetState = text,
        modifier = modifier,
        transitionSpec = {
            (slideInVertically(tween(Motion.MEDIUM_MS)) { it / 2 } + fadeIn(tween(Motion.MEDIUM_MS)))
                .togetherWith(slideOutVertically(tween(Motion.SHORT_MS)) { -it / 2 } + fadeOut(tween(Motion.SHORT_MS)))
                .using(SizeTransform(clip = false))
        },
        label = "amount",
    ) { value ->
        FitText(value, color = color, style = style.merge(AmountStyle), fontWeight = fontWeight, minFontSize = 11.sp)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
        action?.invoke()
    }
}

/** Карточка: без теней, тонкая граница, упругое нажатие. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    Surface(
        modifier = modifier
            .clip(shape)
            .then(if (onClick != null) Modifier.bounceClick(pressedScale = 0.98f, onClick = onClick) else Modifier),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
    ) {
        content()
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Rounded.Inbox) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(icon, MaterialTheme.colorScheme.primary, size = 64.dp)
        Spacer(Modifier.height(14.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Выбор цвета: выбранный кружок увеличивается с отскоком, появляется галочка. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPicker(selected: Long, onSelect: (Long) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PaletteColors.forEach { c ->
            val isSelected = c == selected
            val scale by animateFloatAsState(if (isSelected) 1.12f else 1f, Motion.bouncy(), label = "colorScale")
            val ring by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                tween(Motion.SHORT_MS),
                label = "colorRing",
            )
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .border(2.dp, ring, CircleShape)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(c).lighten(0.15f), Color(c))))
                    .semantics {
                        this.selected = isSelected
                        contentDescription = "Цвет"
                    }
                    .bounceClick(pressedScale = 0.88f, role = Role.RadioButton) { onSelect(c) },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = isSelected,
                    enter = scaleIn(Motion.bouncy()) + fadeIn(),
                    exit = scaleOut(tween(Motion.SHORT_MS)) + fadeOut(tween(Motion.SHORT_MS)),
                ) {
                    Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(IconSize.sm))
                }
            }
        }
    }
}

private val RU: Locale = Locale.forLanguageTag("ru")
private val POPULAR = listOf("BYN", "USD", "EUR", "RUB", "PLN", "UAH", "KZT", "GBP", "CNY", "CHF", "TRY", "GEL")

fun currencyTitle(code: String): String = try {
    val name = Currency.getInstance(code).getDisplayName(RU).replaceFirstChar { it.titlecase(RU) }
    "$code — $name"
} catch (e: IllegalArgumentException) {
    code
}

/** Диалог выбора валюты ISO 4217 с поиском; популярные — сверху. */
@Composable
fun CurrencyPickerDialog(
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val all = remember {
        val codes = Currency.getAvailableCurrencies().map { it.currencyCode }.toSortedSet()
        POPULAR.filter { it in codes } + codes.filter { it !in POPULAR }
    }
    val filtered = remember(query) {
        val q = query.trim().lowercase(RU)
        if (q.isEmpty()) all else all.filter { currencyTitle(it).lowercase(RU).contains(q) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Валюта") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Поиск: BYN, доллар…") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .height(360.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(filtered, key = { it }) { code ->
                        val isSelected = code == selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { onSelect(code) }
                                .padding(vertical = 13.dp, horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = currencyTitle(code),
                                modifier = Modifier.weight(1f),
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            )
                            if (isSelected) {
                                Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(IconSize.sm))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
