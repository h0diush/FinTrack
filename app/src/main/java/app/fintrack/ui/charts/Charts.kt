package app.fintrack.ui.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.fintrack.domain.logic.Money
import app.fintrack.ui.components.Motion
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Графики на Canvas по правилам визуализации:
 *  - одна ось Y, шкала столбцов начинается с нуля;
 *  - тонкие метки, скругление 4dp только у «вершины» столбца, зазор 2dp между заливками;
 *  - сетка и подписи осей приглушены, текст — цветом текста, а не цветом серии;
 *  - касание выделяет элемент и показывает подсказку со значением;
 *  - у каждого графика есть текстовое описание для TalkBack;
 *  - анимируются только прорисовка, прозрачность и масштаб — без перерасчёта разметки.
 */
object ChartFormat {
    private val US = Locale.US

    /** Краткая подпись оси: 950, 1,2 тыс, 15 тыс, 2,5 млн. */
    fun short(minor: Long, currency: String): String {
        val v = Money.fromMinor(minor, currency).toDouble()
        val a = abs(v)
        return when {
            a >= 1_000_000 -> trim(v / 1_000_000) + " млн"
            a >= 10_000 -> (v / 1_000).roundToLong().toString() + " тыс"
            a >= 1_000 -> trim(v / 1_000) + " тыс"
            else -> v.roundToLong().toString()
        }
    }

    private fun trim(x: Double): String = "%.1f".format(US, x).removeSuffix(".0").replace('.', ',')

    /** «Красивая» верхняя граница шкалы: 1, 2, 2,5, 5 × 10ⁿ. */
    fun niceCeil(v: Double): Double {
        if (v <= 0.0) return 1.0
        val exp = 10.0.pow(floor(log10(v)))
        val f = v / exp
        val nf = listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { it >= f - 1e-9 }
        return nf * exp
    }
}

private val GUTTER = 44.dp

@Composable
private fun axisStyle(): TextStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun bubbleStyle(): TextStyle = MaterialTheme.typography.labelMedium.copy(
    color = MaterialTheme.colorScheme.inverseOnSurface,
    fontWeight = FontWeight.SemiBold,
)

private fun DrawScope.label(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    x: Float,
    y: Float,
    alignCenter: Boolean = false,
    alignRight: Boolean = false,
) {
    val layout = measurer.measure(text, style)
    val left = when {
        alignCenter -> x - layout.size.width / 2f
        alignRight -> x - layout.size.width
        else -> x
    }
    drawText(layout, topLeft = Offset(left.coerceIn(0f, max(0f, size.width - layout.size.width)), y))
}

/**
 * Подсказка-«пузырь» над точкой: появляется с пружинным масштабом от точки привязки.
 */
private fun DrawScope.bubble(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    anchor: Offset,
    background: Color,
    maxRight: Float,
    appear: Float,
) {
    if (appear <= 0f) return
    val layout = measurer.measure(text, style)
    val padH = 8.dp.toPx()
    val padV = 5.dp.toPx()
    val w = layout.size.width + padH * 2
    val h = layout.size.height + padV * 2
    val left = (anchor.x - w / 2f).coerceIn(0f, max(0f, maxRight - w))
    val top = (anchor.y - h - 8.dp.toPx()).coerceAtLeast(0f)
    scale(appear, appear, pivot = Offset(anchor.x, top + h)) {
        drawRoundRect(background, Offset(left, top), Size(w, h), CornerRadius(8.dp.toPx()))
        drawText(layout, topLeft = Offset(left + padH, top + padV))
    }
}

/** Прямоугольник со скруглёнными верхними углами: «вершина» столбца, низ стоит на базовой линии. */
private fun topRounded(left: Float, top: Float, width: Float, height: Float, radius: Float): Path {
    val r = CornerRadius(radius, radius)
    return Path().apply {
        addRoundRect(
            RoundRect(
                left = left,
                top = top,
                right = left + width,
                bottom = top + height,
                topLeftCornerRadius = r,
                topRightCornerRadius = r,
                bottomRightCornerRadius = CornerRadius.Zero,
                bottomLeftCornerRadius = CornerRadius.Zero,
            ),
        )
    }
}

/** Прогресс 0→1 при каждой смене данных. */
@Composable
private fun rememberGrowth(key: Any?, durationMs: Int = 750, linear: Boolean = false): Float {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(key) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMs, easing = if (linear) LinearEasing else FastOutSlowInEasing))
    }
    return progress.value
}

/** Пружинное появление подсказки при выборе элемента. */
@Composable
private fun rememberBubble(selected: Int?): Float {
    val a = remember { Animatable(0f) }
    LaunchedEffect(selected) {
        if (selected == null) {
            a.animateTo(0f, tween(Motion.SHORT_MS))
        } else {
            a.snapTo(0.6f)
            a.animateTo(1f, Motion.bouncy())
        }
    }
    return a.value
}

// ---------------------------------------------------------------------------
// Кольцевая диаграмма

data class DonutSlice(val value: Float, val color: Color)

/**
 * Кольцевая диаграмма: сегменты прорисовываются по кругу поверх приглушённой
 * дорожки; выбранный выдвигается наружу и утолщается на пружине, остальные
 * приглушаются. Касание вне кольца снимает выбор.
 */
@Composable
fun DonutChart(
    slices: List<DonutSlice>,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    thickness: Dp = 26.dp,
    center: @Composable () -> Unit = {},
) {
    val progress = rememberGrowth(slices, durationMs = 900)
    val expansions = slices.indices.map { i ->
        key(i) {
            animateFloatAsState(if (i == selectedIndex) 1f else 0f, Motion.bouncy(), label = "slice").value
        }
    }
    val dims = slices.indices.map { i ->
        key(i) {
            animateFloatAsState(
                if (selectedIndex == null || i == selectedIndex) 1f else 0.3f,
                tween(Motion.MEDIUM_MS),
                label = "sliceAlpha",
            ).value
        }
    }
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val currentSelected by rememberUpdatedState(selectedIndex)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val total = slices.sumOf { it.value.toDouble() }.toFloat()

    Box(modifier.semantics { this.contentDescription = contentDescription }, contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(slices) {
                    detectTapGestures { pos ->
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val dx = pos.x - cx
                        val dy = pos.y - cy
                        val dist = sqrt(dx * dx + dy * dy)
                        val outer = min(size.width, size.height) / 2f
                        val inner = outer - thickness.toPx() * 2f
                        if (dist < inner || dist > outer || total <= 0f) {
                            currentOnSelect(null)
                            return@detectTapGestures
                        }
                        var angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 90f
                        if (angle < 0f) angle += 360f
                        var acc = 0f
                        val idx = slices.indexOfFirst { s ->
                            acc += s.value / total * 360f
                            angle <= acc
                        }
                        currentOnSelect(if (idx < 0 || idx == currentSelected) null else idx)
                    }
                },
        ) {
            val stroke = thickness.toPx()
            val extra = stroke * 0.25f
            val pop = 7.dp.toPx()
            val diameter = min(size.width, size.height) - stroke - extra * 2 - pop * 2
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)

            // Дорожка — видна, пока сегменты прорисовываются
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(width = stroke))
            if (total <= 0f) return@Canvas

            val gapDeg = if (slices.size > 1) Math.toDegrees((2.dp.toPx() / (diameter / 2f)).toDouble()).toFloat() else 0f
            val drawn = 360f * progress
            var start = 0f
            slices.forEachIndexed { i, s ->
                val sweep = s.value / total * 360f
                val visible = (drawn - start).coerceIn(0f, sweep)
                if (visible > gapDeg) {
                    val e = expansions.getOrElse(i) { 0f }
                    val mid = Math.toRadians((start + sweep / 2f - 90f).toDouble())
                    val shift = Offset((cos(mid) * pop * e).toFloat(), (sin(mid) * pop * e).toFloat())
                    drawArc(
                        color = s.color.copy(alpha = dims.getOrElse(i) { 1f }),
                        startAngle = -90f + start + gapDeg / 2f,
                        sweepAngle = visible - gapDeg,
                        useCenter = false,
                        topLeft = topLeft + shift,
                        size = arcSize,
                        style = Stroke(width = stroke + extra * e, cap = StrokeCap.Butt),
                    )
                }
                start += sweep
            }
        }
        center()
    }
}

// ---------------------------------------------------------------------------
// Столбчатая диаграмма (одна или несколько серий)

data class BarGroup(val label: String, val values: List<Long>)

/**
 * Столбцы вырастают от базовой линии «волной» слева направо (сдвиг 30–45 мс).
 * Несколько серий — группы рядом на одной шкале.
 * @param tooltip текст подсказки для выбранной группы.
 * @param averageLine пунктирная линия среднего значения.
 * @param labelEvery подписывать каждую N-ю группу (для дней месяца).
 */
@Composable
fun BarChart(
    groups: List<BarGroup>,
    colors: List<Color>,
    currency: String,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    averageLine: Long? = null,
    labelEvery: Int = 1,
    hideValues: Boolean = false,
    tooltip: ((Int) -> String)? = null,
) {
    val perBar = 600
    val stagger = if (groups.size > 1) min(45, 450 / groups.size) else 0
    val total = perBar + stagger * (groups.size - 1).coerceAtLeast(0)
    val progress = rememberGrowth(groups, durationMs = total, linear = true)
    val bubbleIn = rememberBubble(selectedIndex)
    val measurer = rememberTextMeasurer()
    val style = axisStyle()
    val selectedStyle = style.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
    val tipStyle = bubbleStyle()
    val tipBackground = MaterialTheme.colorScheme.inverseSurface
    val grid = MaterialTheme.colorScheme.outlineVariant
    val avgColor = MaterialTheme.colorScheme.onSurfaceVariant
    val currentSelected by rememberUpdatedState(selectedIndex)
    val currentOnSelect by rememberUpdatedState(onSelect)

    Canvas(
        modifier
            .semantics { this.contentDescription = contentDescription }
            .pointerInput(groups) {
                detectTapGestures { pos ->
                    val plotWidth = size.width - GUTTER.toPx()
                    if (groups.isEmpty() || pos.x > plotWidth) {
                        currentOnSelect(null)
                        return@detectTapGestures
                    }
                    val idx = (pos.x / (plotWidth / groups.size)).toInt().coerceIn(0, groups.lastIndex)
                    currentOnSelect(if (idx == currentSelected) null else idx)
                }
            },
    ) {
        if (groups.isEmpty()) return@Canvas
        val gutter = GUTTER.toPx()
        val bottomPad = 20.dp.toPx()
        val topPad = if (tooltip != null) 34.dp.toPx() else 8.dp.toPx()
        val w = size.width - gutter
        val h = size.height - bottomPad - topPad
        val maxValue = max(groups.maxOf { g -> g.values.maxOrNull() ?: 0L }, averageLine ?: 0L)
        val scaleMax = ChartFormat.niceCeil(maxValue.toDouble()).toFloat()

        for (f in listOf(0f, 0.5f, 1f)) {
            val y = topPad + h * (1f - f)
            drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1.dp.toPx())
            val text = if (hideValues) "•••" else ChartFormat.short((scaleMax * f).toLong(), currency)
            label(measurer, text, style, w + 6.dp.toPx(), y - 7.dp.toPx())
        }

        val n = colors.size.coerceAtLeast(1)
        val gap = 2.dp.toPx()
        val slot = w / groups.size
        val barWidth = ((slot * 0.7f - gap * (n - 1)) / n).coerceIn(2f, 18.dp.toPx())
        val radius = 4.dp.toPx()
        val elapsed = progress * total
        var bubbleAnchor: Offset? = null

        groups.forEachIndexed { gi, g ->
            val local = ((elapsed - gi * stagger) / perBar).coerceIn(0f, 1f)
            val grow = FastOutSlowInEasing.transform(local)
            val groupWidth = n * barWidth + (n - 1) * gap
            var x = slot * gi + (slot - groupWidth) / 2f
            val dim = selectedIndex != null && selectedIndex != gi
            var groupTop = topPad + h
            g.values.forEachIndexed { si, v ->
                val barHeight = (v / scaleMax) * h * grow
                if (barHeight > 0.5f) {
                    drawPath(
                        topRounded(x, topPad + h - barHeight, barWidth, barHeight, min(radius, min(barWidth / 2f, barHeight))),
                        colors.getOrElse(si) { colors.first() }.copy(alpha = if (dim) 0.3f else 1f),
                    )
                }
                groupTop = min(groupTop, topPad + h - barHeight)
                x += barWidth + gap
            }
            val isSelected = gi == selectedIndex
            if (isSelected) bubbleAnchor = Offset(slot * gi + slot / 2f, groupTop)
            if (isSelected || (gi % labelEvery == 0 && selectedIndex == null)) {
                label(
                    measurer, g.label, if (isSelected) selectedStyle else style,
                    slot * gi + slot / 2f, topPad + h + 4.dp.toPx(), alignCenter = true,
                )
            }
        }

        if (averageLine != null && averageLine > 0) {
            val y = topPad + h * (1f - averageLine / scaleMax)
            drawLine(
                avgColor.copy(alpha = progress),
                Offset(0f, y),
                Offset(w, y),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
            )
        }

        val sel = selectedIndex
        val anchor = bubbleAnchor
        if (tooltip != null && sel != null && anchor != null) {
            bubble(measurer, tooltip(sel), tipStyle, anchor, tipBackground, w, bubbleIn)
        }
    }
}

// ---------------------------------------------------------------------------
// Линейный график (баланс)

/**
 * Плавная кривая 2dp с мягкой заливкой, прорисовывается слева направо.
 * Касание или ведение пальцем показывает точку и подсказку со значением.
 */
@Composable
fun LineChart(
    values: List<Long>,
    labels: List<String>,
    color: Color,
    currency: String,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    hideValues: Boolean = false,
    tooltip: ((Int) -> String)? = null,
) {
    val progress = rememberGrowth(values, durationMs = 1000)
    val bubbleIn = rememberBubble(selectedIndex)
    val markerIn by animateFloatAsState(if (selectedIndex != null) 1f else 0f, Motion.bouncy(), label = "marker")
    val measurer = rememberTextMeasurer()
    val style = axisStyle()
    val tipStyle = bubbleStyle()
    val tipBackground = MaterialTheme.colorScheme.inverseSurface
    val grid = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val zeroColor = MaterialTheme.colorScheme.outline
    val currentOnSelect by rememberUpdatedState(onSelect)

    fun indexAt(x: Float, plotWidth: Float): Int? {
        if (values.size < 2) return values.indices.firstOrNull()
        val i = kotlin.math.round(x / plotWidth * (values.size - 1)).toInt()
        return i.coerceIn(0, values.lastIndex)
    }

    Canvas(
        modifier
            .semantics { this.contentDescription = contentDescription }
            .pointerInput(values) {
                detectTapGestures { pos ->
                    val plotWidth = size.width - GUTTER.toPx()
                    currentOnSelect(if (pos.x > plotWidth) null else indexAt(pos.x, plotWidth))
                }
            }
            .pointerInput(values) {
                detectHorizontalDragGestures { change, _ ->
                    val plotWidth = size.width - GUTTER.toPx()
                    currentOnSelect(indexAt(change.position.x.coerceIn(0f, plotWidth), plotWidth))
                }
            },
    ) {
        if (values.isEmpty()) return@Canvas
        val gutter = GUTTER.toPx()
        val bottomPad = 20.dp.toPx()
        val topPad = if (tooltip != null) 34.dp.toPx() else 10.dp.toPx()
        val w = size.width - gutter
        val h = size.height - bottomPad - topPad

        var lo = values.min().toDouble()
        var hi = values.max().toDouble()
        if (hi - lo < 1.0) {
            hi += 1.0
            lo -= 1.0
        }
        val pad = (hi - lo) * 0.12
        hi += pad
        lo -= pad
        fun yOf(v: Long): Float = topPad + h * (1f - ((v - lo) / (hi - lo)).toFloat())
        fun xOf(i: Int): Float = if (values.size == 1) w / 2f else w * i / (values.size - 1)

        for (f in listOf(0f, 0.5f, 1f)) {
            val y = topPad + h * (1f - f)
            drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1.dp.toPx())
            val v = (lo + (hi - lo) * f).roundToLong()
            label(measurer, if (hideValues) "•••" else ChartFormat.short(v, currency), style, w + 6.dp.toPx(), y - 7.dp.toPx())
        }
        if (lo < 0 && hi > 0) {
            val y0 = yOf(0)
            drawLine(zeroColor, Offset(0f, y0), Offset(w, y0), strokeWidth = 1.dp.toPx())
        }

        // Плавная кривая: сегменты-кубики с контрольными точками на середине по X —
        // без «перелётов» выше максимума и ниже минимума.
        val points = values.mapIndexed { i, v -> Offset(xOf(i), yOf(v)) }
        val line = Path()
        val area = Path()
        points.forEachIndexed { i, p ->
            if (i == 0) {
                line.moveTo(p.x, p.y)
                area.moveTo(p.x, topPad + h)
                area.lineTo(p.x, p.y)
            } else {
                val prev = points[i - 1]
                val midX = (prev.x + p.x) / 2f
                line.cubicTo(midX, prev.y, midX, p.y, p.x, p.y)
                area.cubicTo(midX, prev.y, midX, p.y, p.x, p.y)
            }
        }
        area.lineTo(points.last().x, topPad + h)
        area.close()

        clipRect(right = w * progress + 2.dp.toPx()) {
            drawPath(
                area,
                Brush.verticalGradient(listOf(color.copy(alpha = 0.26f), color.copy(alpha = 0f)), startY = topPad, endY = topPad + h),
            )
            drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        if (labels.size == values.size && labels.isNotEmpty()) {
            val idx = listOf(0, labels.lastIndex / 2, labels.lastIndex).distinct()
            idx.forEach { i ->
                label(
                    measurer, labels[i], style, xOf(i), topPad + h + 4.dp.toPx(),
                    alignCenter = i != 0 && i != labels.lastIndex,
                    alignRight = i == labels.lastIndex && i != 0,
                )
            }
        }

        val sel = selectedIndex
        if (sel != null && sel in values.indices && progress >= 0.99f) {
            val p = points[sel]
            drawLine(grid.copy(alpha = markerIn.coerceIn(0f, 1f)), Offset(p.x, topPad), Offset(p.x, topPad + h), strokeWidth = 1.dp.toPx())
            drawCircle(surface, radius = 7.dp.toPx() * markerIn, center = p)
            drawCircle(color, radius = 5.dp.toPx() * markerIn, center = p)
            if (tooltip != null) bubble(measurer, tooltip(sel), tipStyle, Offset(p.x, p.y - 6.dp.toPx()), tipBackground, w, bubbleIn)
        }
    }
}
