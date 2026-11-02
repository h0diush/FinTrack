package app.fintrack.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.isSpecified
import app.fintrack.domain.logic.Money
import app.fintrack.ui.theme.AmountStyle
import kotlin.math.roundToLong

private const val MIN_FONT_SP = 10f

/**
 * Значение, которое «досчитывает» до цели: при первом показе — от нуля,
 * при изменении — от прежнего значения. Ускорение-замедление, ~0,9 с.
 * Учитывает системный масштаб анимации: при отключённых анимациях — сразу итог.
 */
@Composable
fun rememberCountUp(target: Long, durationMs: Int = 900, delayMs: Int = 0): Long {
    var from by remember { mutableLongStateOf(0L) }
    var to by remember { mutableLongStateOf(0L) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(target) {
        from = from + ((to - from) * progress.value).roundToLong()
        to = target
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMs, delayMillis = delayMs, easing = FastOutSlowInEasing))
    }
    return from + ((to - from) * progress.value).roundToLong()
}

/**
 * Сумма со счётчиком. TalkBack сразу читает итоговое значение.
 * Если сумма не помещается по ширине, шрифт уменьшается, а не обрезается.
 */
@Composable
fun CountUpAmount(
    minor: Long,
    currency: String,
    hidden: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
    sign: Boolean = false,
    fontWeight: FontWeight? = FontWeight.SemiBold,
    delayMs: Int = 0,
) {
    val value = rememberCountUp(minor, delayMs = delayMs)
    val finalText = if (hidden) Money.masked(currency) else Money.format(minor, currency, forceSign = sign)
    var fitted by remember(finalText, style) { mutableStateOf(style) }
    Text(
        text = if (hidden) finalText else Money.format(value, currency, forceSign = sign),
        modifier = modifier.semantics { contentDescription = finalText },
        style = fitted.merge(AmountStyle),
        color = color,
        fontWeight = fontWeight,
        maxLines = 1,
        softWrap = false,
        onTextLayout = { result ->
            val size = fitted.fontSize
            if (result.didOverflowWidth && size.isSpecified && size.value > MIN_FONT_SP) {
                fitted = fitted.copy(fontSize = size * 0.9f)
            }
        },
    )
}
