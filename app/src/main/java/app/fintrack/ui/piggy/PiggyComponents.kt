package app.fintrack.ui.piggy

import app.fintrack.ui.components.FitText
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.fintrack.domain.logic.Money
import app.fintrack.domain.logic.PiggyBank
import app.fintrack.domain.logic.PiggyCalculator
import app.fintrack.domain.logic.PiggyProgress
import app.fintrack.ui.components.AppCard
import app.fintrack.ui.components.CategoryIcons
import app.fintrack.ui.components.IconBadge
import app.fintrack.ui.components.IconSize
import app.fintrack.ui.components.Motion
import app.fintrack.ui.components.bounceClick
import app.fintrack.ui.components.contentOn
import app.fintrack.ui.components.lighten
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Готовые цели: иконка + название. Выбор подставляет название, если оно пустое. */
val PiggyPresets: List<Pair<String, String>> = listOf(
    "savings" to "Копилка",
    "beach" to "Отпуск",
    "flight" to "Путешествие",
    "phone" to "Новый телефон",
    "laptop" to "Ноутбук",
    "car" to "Автомобиль",
    "home" to "Своё жильё",
    "furniture" to "Ремонт и мебель",
    "security" to "Подушка безопасности",
    "school" to "Учёба",
    "kids" to "Детям",
    "gift" to "Подарок",
    "party" to "Праздник",
    "love" to "Свадьба",
    "jewelry" to "Украшение",
    "games" to "Приставка",
    "camera" to "Фототехника",
    "bike" to "Велосипед",
    "pets" to "Питомец",
    "sport" to "Спорт",
)

/** Системные анимации выключены — тогда фразы не листаются сами. */
@Composable
fun animationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
}

/**
 * Кольцо прогресса: дорожка + дуга с градиентом и скруглёнными концами.
 * При первом показе и при изменении дуга плавно дорастает до значения.
 */
@Composable
fun ProgressRing(
    fraction: Float,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    stroke: Dp = 6.dp,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(fraction) {
        anim.animateTo(fraction.coerceIn(0f, 1f), tween(1100, easing = FastOutSlowInEasing))
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val inset = w / 2
            val arcSize = Size(this.size.width - w, this.size.height - w)
            drawArc(trackColor, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w))
            val sweep = 360f * anim.value
            if (sweep > 0.5f) {
                rotate(-90f) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            0f to color.lighten(0.35f),
                            (anim.value).coerceAtLeast(0.01f) to color,
                            1f to color,
                        ),
                        startAngle = 0f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(w, cap = StrokeCap.Round),
                    )
                }
            }
        }
        content()
    }
}

/**
 * Сменяющиеся мотивационные фразы. Листаются сами каждые ~6 секунд,
 * по нажатию — следующая (после нажатия автопрокрутка останавливается,
 * чтобы текст не менялся, пока его читают). При выключенных анимациях — только по нажатию.
 */
@Composable
fun MotivationTicker(
    phrases: List<String>,
    accent: Color,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    if (phrases.isEmpty()) return
    var index by remember(phrases.size) { mutableIntStateOf(0) }
    var manual by remember { mutableStateOf(false) }
    val auto = animationsEnabled() && !manual && phrases.size > 1
    LaunchedEffect(index, auto, phrases.size) {
        if (!auto) return@LaunchedEffect
        delay(6_500)
        index = (index + 1) % phrases.size
    }
    val current = phrases[index.coerceIn(0, phrases.lastIndex)]
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(accent.copy(alpha = 0.10f))
            .bounceClick(pressedScale = 0.98f, onClickLabel = "Следующая фраза") {
                manual = true
                index = (index + 1) % phrases.size
            }
            .semantics { contentDescription = current }
            .padding(horizontal = 14.dp, vertical = if (compact) 10.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(if (compact) 30.dp else 36.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = accent, modifier = Modifier.size(IconSize.sm))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            AnimatedContent(
                targetState = current,
                transitionSpec = {
                    (slideInVertically(tween(Motion.LONG_MS)) { it / 2 } + fadeIn(tween(Motion.LONG_MS)))
                        .togetherWith(slideOutVertically(tween(Motion.SHORT_MS)) { -it / 2 } + fadeOut(tween(Motion.SHORT_MS)))
                },
                label = "motivation",
            ) { text ->
                Text(
                    text,
                    style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    modifier = Modifier.heightIn(min = if (compact) 18.dp else 40.dp).clearAndSetSemantics { },
                )
            }
            if (!compact && phrases.size > 1) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    phrases.indices.forEach { i ->
                        val active = i == index
                        val dot by animateColorAsState(
                            if (active) accent else accent.copy(alpha = 0.25f),
                            tween(Motion.MEDIUM_MS),
                            label = "dot",
                        )
                        Box(
                            Modifier
                                .height(4.dp)
                                .width(if (active) 14.dp else 4.dp)
                                .clip(CircleShape)
                                .background(dot),
                        )
                    }
                }
            }
        }
    }
}

/** Отметки 25 / 50 / 75 / 100 %: пройденные заливаются цветом копилки и получают галочку. */
@Composable
fun MilestonesRow(percent: Int, accent: Color, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PiggyCalculator.MILESTONES.forEach { m ->
            val done = percent >= m
            val bg by animateColorAsState(
                if (done) accent else MaterialTheme.colorScheme.surfaceContainerHigh,
                tween(Motion.MEDIUM_MS),
                label = "milestoneBg",
            )
            val fg = if (done) accent.contentOn() else MaterialTheme.colorScheme.onSurfaceVariant
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(CircleShape)
                    .background(bg)
                    .semantics { contentDescription = "$m %" + if (done) ", пройдено" else ", впереди" },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (done) {
                    Icon(Icons.Rounded.Check, null, tint = fg, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                }
                FitText("$m %", style = MaterialTheme.typography.labelMedium, color = fg, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Карточка копилки для горизонтального списка на главной. */
@Composable
fun PiggyCard(
    piggy: PiggyBank,
    progress: PiggyProgress,
    hidden: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = Color(piggy.color)
    AppCard(modifier = modifier.width(176.dp), onClick = onClick) {
        Column(
            Modifier
                .padding(14.dp)
                .heightIn(min = 134.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressRing(
                    fraction = if (progress.hasTarget) progress.fraction else 0f,
                    color = accent,
                    trackColor = accent.copy(alpha = 0.16f),
                    size = 52.dp,
                    stroke = 5.dp,
                ) {
                    Icon(CategoryIcons.get(piggy.iconKey), null, tint = accent, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.weight(1f))
                if (progress.hasTarget) {
                    Text(
                        "${progress.percent} %",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = accent.readableOn(MaterialTheme.colorScheme.surfaceContainer),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(piggy.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (hidden) Money.masked(piggy.currency) else Money.format(piggy.balanceMinor, piggy.currency),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                when {
                    !progress.hasTarget -> "Без цели"
                    progress.reached -> "Цель достигнута"
                    hidden -> "Цель скрыта"
                    else -> "из " + Money.format(piggy.targetMinor, piggy.currency)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Последняя карточка ряда — «Новая копилка». */
@Composable
fun NewPiggyCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    AppCard(modifier = modifier.width(120.dp), onClick = onClick) {
        Column(
            Modifier
                .padding(14.dp)
                .height(134.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconBadge(Icons.Rounded.Add, MaterialTheme.colorScheme.primary, size = 44.dp)
            Spacer(Modifier.height(10.dp))
            Text("Новая\nкопилка", style = MaterialTheme.typography.labelLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/**
 * Акцентный цвет, достаточно контрастный для текста на фоне [background]:
 * светлые цвета копилки затемняются в светлой теме и наоборот.
 */
fun Color.readableOn(background: Color): Color {
    val bgLight = background.luminance() > 0.5f
    var c = this
    repeat(6) {
        val ratio = contrast(c, background)
        if (ratio >= 4.5f) return c
        c = if (bgLight) lerp(c, Color.Black, 0.18f) else lerp(c, Color.White, 0.18f)
    }
    return c
}

private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return if (la > lb) la / lb else lb / la
}

private class Particle(
    val angle: Float,
    val speed: Float,
    val spin: Float,
    val color: Color,
    val w: Float,
    val h: Float,
)

/**
 * Праздничный «салют» из конфетти при достижении отметки. Запускается при каждом
 * новом значении [trigger] (> 0), длится ~1,8 с, касания не перехватывает.
 * При выключенных системных анимациях не показывается.
 */
@Composable
fun ConfettiBurst(trigger: Int, colors: List<Color>, modifier: Modifier = Modifier) {
    if (trigger <= 0) return
    val t = remember(trigger) { Animatable(0f) }
    val particles = remember(trigger) {
        val rnd = Random(trigger)
        List(70) {
            Particle(
                angle = (-90f + rnd.nextFloat() * 120f - 60f),
                speed = 0.55f + rnd.nextFloat() * 0.75f,
                spin = rnd.nextFloat() * 720f - 360f,
                color = colors[rnd.nextInt(colors.size)],
                w = 6f + rnd.nextFloat() * 6f,
                h = 10f + rnd.nextFloat() * 8f,
            )
        }
    }
    LaunchedEffect(trigger) { t.animateTo(1f, tween(1_800, easing = LinearEasing)) }
    if (t.value >= 1f) return
    Canvas(modifier.fillMaxSize()) {
        val origin = Offset(size.width / 2, size.height * 0.32f)
        val time = t.value
        val dist = size.minDimension * 0.9f
        particles.forEach { p ->
            val rad = Math.toRadians(p.angle.toDouble())
            val x = origin.x + (cos(rad) * p.speed * dist * time).toFloat()
            val y = origin.y + (sin(rad) * p.speed * dist * time).toFloat() + 1.1f * dist * time * time
            val alpha = (1f - time).coerceIn(0f, 1f)
            val pw = p.w * density * 0.6f
            val ph = p.h * density * 0.6f
            rotate(p.spin * time, Offset(x, y)) {
                drawRect(
                    color = p.color.copy(alpha = alpha),
                    topLeft = Offset(x - pw / 2, y - ph / 2),
                    size = Size(pw, ph),
                )
            }
        }
    }
}
