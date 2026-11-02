package app.fintrack.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * Переключатель-«пилюля»: подложка перетекает к выбранному пункту на пружине
 * и меняет цвет. Цвет текста подбирается по яркости подложки.
 */
@Composable
fun SlidingSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    pillColors: List<Color>,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
) {
    val haptic = LocalHapticFeedback.current
    val index = selectedIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0))
    val pill by animateColorAsState(pillColors.getOrElse(index) { pillColors.first() }, tween(Motion.MEDIUM_MS), label = "segPill")
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(4.dp),
    ) {
        val segment = maxWidth / options.size.coerceAtLeast(1)
        val x by animateDpAsState(segment * index, Motion.indicator(), label = "segX")
        val density = LocalDensity.current
        Box(
            Modifier
                .offset { IntOffset(with(density) { x.roundToPx() }, 0) }
                .width(segment)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(pill),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                val selected = i == index
                val textColor by animateColorAsState(
                    if (selected) pill.contentOn() else MaterialTheme.colorScheme.onSurfaceVariant,
                    tween(Motion.MEDIUM_MS),
                    label = "segText",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = {
                                if (!selected) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(i)
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    FitText(
                        label,
                        color = textColor,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }
        }
    }
}
