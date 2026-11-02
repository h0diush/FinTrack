package app.fintrack.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/** Слово разорвано посреди строки (последняя буква «уехала» на новую строку и т. п.). */
private fun TextLayoutResult.breaksWord(text: String): Boolean {
    for (i in 0 until lineCount - 1) {
        val end = getLineEnd(i)
        if (end <= 0 || end >= text.length) continue
        val last = text[end - 1]
        val next = text[end]
        if (!last.isWhitespace() && last != '-' && last != '/' && !next.isWhitespace()) return true
    }
    return false
}

/**
 * Текст для узких мест (ячейки сетки, плашки, сегменты, суммы рядом с подписями).
 *
 * Обычный Text в узком контейнере рвёт длинное слово по буквам: «Коммунальны / е».
 * FitText сначала уменьшает шрифт (не ниже [minFontSize]), пока слова не перестанут
 * рваться и текст не уложится в [maxLines] строк; если и этого мало — переносит по
 * слогам с дефисом (русские правила переноса) и в крайнем случае ставит «…».
 * Пока размер подбирается, текст не рисуется — без мигания.
 */
@Composable
fun FitText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    maxLines: Int = 1,
    minLines: Int = 1,
    minFontSize: TextUnit = 10.sp,
) {
    val base = style.copy(hyphens = Hyphens.Auto, localeList = RuLocale)
    var size by remember(text, style, maxLines) { mutableStateOf(style.fontSize) }
    var ready by remember(text, style, maxLines) { mutableStateOf(false) }
    Text(
        text = text,
        modifier = modifier.drawWithContent { if (ready) drawContent() },
        style = if (size.isSpecified) base.copy(fontSize = size) else base,
        color = color,
        fontWeight = fontWeight,
        textAlign = textAlign,
        maxLines = maxLines,
        minLines = minLines,
        softWrap = maxLines > 1,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { r ->
            val bad = r.hasVisualOverflow || r.didOverflowWidth || (maxLines > 1 && r.breaksWord(text))
            val canShrink = size.isSpecified && minFontSize.isSpecified && size.value > minFontSize.value + 0.01f
            if (bad && canShrink) {
                size = (size.value * 0.92f).coerceAtLeast(minFontSize.value).sp
            } else {
                ready = true
            }
        },
    )
}

private val RuLocale = LocaleList("ru")
