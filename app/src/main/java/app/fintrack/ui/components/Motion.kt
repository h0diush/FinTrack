package app.fintrack.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role

/**
 * Токены движения. Все анимации Compose автоматически учитывают системную
 * настройку «Масштаб анимации» — при её отключении переходы выполняются мгновенно.
 */
object Motion {
    /** Быстрый отклик на нажатие: 80–150 мс. */
    const val PRESS_MS = 120
    const val SHORT_MS = 180
    const val MEDIUM_MS = 260
    const val LONG_MS = 380
    /** Задержка между элементами при каскадном появлении. */
    const val STAGGER_MS = 45

    /** Пружина для выбора: лёгкий «отскок», ощущается живо, но не игриво. */
    fun <T> bouncy() = spring<T>(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)

    /** Плавная пружина без перелёта — для цветов, позиций индикаторов. */
    fun <T> smooth() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

    /** Пружина индикатора вкладок: лёгкий перелёт, быстро успокаивается. */
    fun <T> indicator() = spring<T>(dampingRatio = 0.72f, stiffness = 420f)
}

/**
 * Масштаб при нажатии. Не меняет размеры в разметке (graphicsLayer),
 * поэтому соседние элементы не «дрожат».
 */
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.95f,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = if (pressed) tween(Motion.PRESS_MS) else Motion.bouncy(),
        label = "pressScale",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** Нажатие с рипплом и упругим масштабом. */
fun Modifier.bounceClick(
    enabled: Boolean = true,
    pressedScale: Float = 0.95f,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    this
        .pressScale(source, pressedScale)
        .clickable(
            interactionSource = source,
            indication = LocalIndication.current,
            enabled = enabled,
            role = role,
            onClickLabel = onClickLabel,
            onClick = onClick,
        )
}

/** «Пружинка» при смене значения: короткое увеличение и возврат. */
fun Modifier.popOnChange(key: Any?, peak: Float = 1.06f): Modifier = composed {
    val scale = remember { Animatable(1f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(key) {
        if (first[0]) {
            first[0] = false
            return@LaunchedEffect
        }
        scale.snapTo(peak)
        scale.animateTo(1f, Motion.bouncy())
    }
    graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/** Горизонтальная «встряска» при ошибке ввода. */
fun Modifier.shakeOnTrigger(trigger: Int): Modifier = composed {
    val offset = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        for (x in listOf(14f, -12f, 9f, -6f, 3f, 0f)) {
            offset.animateTo(x, tween(45))
        }
    }
    graphicsLayer { translationX = offset.value * density }
}

/** Каскадное появление блока при первом показе экрана. */
@Composable
fun StaggeredAppear(index: Int, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = state,
        enter = fadeIn(tween(Motion.LONG_MS, delayMillis = index * Motion.STAGGER_MS)) +
            slideInVertically(tween(Motion.LONG_MS, delayMillis = index * Motion.STAGGER_MS)) { it / 5 },
    ) {
        content()
    }
}
