package app.fintrack.ui.lock

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.fintrack.domain.logic.PinHasher
import app.fintrack.ui.components.IconBadge
import app.fintrack.ui.components.Motion
import app.fintrack.ui.components.bounceClick
import app.fintrack.ui.components.shakeOnTrigger
import app.fintrack.ui.theme.FinTheme

/** Биометрия (отпечаток, лицо) — доступна ли и запуск системного окна. */
object Biometrics {
    fun available(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(activity: FragmentActivity, subtitle: String, onSuccess: () -> Unit) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Вход в FinTrack")
            .setSubtitle(subtitle)
            .setNegativeButtonText("Ввести PIN-код")
            .setAllowedAuthenticators(BIOMETRIC_WEAK)
            .build()
        prompt.authenticate(info)
    }
}

/**
 * Экран ввода PIN на градиенте: иконка, заголовок, точки, сообщение, цифровая клавиатура.
 * [onBiometric] — кнопка отпечатка в левом нижнем углу (null — не показывать).
 */
@Composable
fun PinScreen(
    title: String,
    entered: Int,
    message: String?,
    isError: Boolean,
    shake: Int,
    enabled: Boolean,
    icon: ImageVector,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onBiometric: (() -> Unit)?,
    footer: (@Composable () -> Unit)? = null,
) {
    val fin = FinTheme.colors
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(fin.heroStart, fin.heroMid, fin.heroEnd)))
            .drawBehind {
                drawCircle(Color.White.copy(alpha = 0.07f), radius = size.width * 0.55f, center = Offset(size.width * 0.95f, 0f))
                drawCircle(Color.White.copy(alpha = 0.05f), radius = size.width * 0.45f, center = Offset(0f, size.height))
            }
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconBadge(icon, Color.White, size = 64.dp)
            Spacer(Modifier.height(18.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, color = fin.onHero, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            PinDots(entered, isError, Modifier.shakeOnTrigger(shake))
            Spacer(Modifier.height(14.dp))
            AnimatedContent(
                targetState = message.orEmpty(),
                transitionSpec = { fadeIn(tween(Motion.SHORT_MS)).togetherWith(fadeOut(tween(Motion.SHORT_MS))) },
                label = "pinMessage",
            ) { m ->
                Text(
                    m.ifEmpty { " " },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isError) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isError) Color(0xFFFFD1D1) else fin.onHeroMuted,
                    textAlign = TextAlign.Center,
                    minLines = 2,
                )
            }
            Spacer(Modifier.height(20.dp))
            PinKeypad(enabled = enabled, onDigit = onDigit, onBackspace = onBackspace, onBiometric = onBiometric)
            if (footer != null) {
                Spacer(Modifier.height(12.dp))
                footer()
            }
        }
    }
}

@Composable
private fun PinDots(entered: Int, isError: Boolean, modifier: Modifier = Modifier) {
    val fin = FinTheme.colors
    Row(
        modifier.semantics { contentDescription = "Введено цифр: $entered из ${PinHasher.PIN_LENGTH}" },
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        repeat(PinHasher.PIN_LENGTH) { i ->
            val filled = i < entered
            val scale by animateFloatAsState(if (filled) 1.15f else 1f, Motion.bouncy(), label = "dotScale")
            val color by animateColorAsState(
                when {
                    isError -> Color(0xFFFF8A8A)
                    filled -> fin.onHero
                    else -> Color.Transparent
                },
                tween(Motion.SHORT_MS),
                label = "dotColor",
            )
            Box(
                Modifier
                    .size(16.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(color)
                    .border(2.dp, if (isError) Color(0xFFFF8A8A) else fin.onHero.copy(alpha = 0.8f), CircleShape),
            )
        }
    }
}

@Composable
private fun PinKeypad(enabled: Boolean, onDigit: (Char) -> Unit, onBackspace: () -> Unit, onBiometric: (() -> Unit)?) {
    val rows = listOf("123", "456", "789")
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                r.forEach { d -> DigitKey(d.toString(), enabled) { onDigit(d) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBiometric != null) {
                IconKey(Icons.Rounded.Fingerprint, "Войти по отпечатку", enabled = true, onClick = onBiometric)
            } else {
                Spacer(Modifier.size(KEY))
            }
            DigitKey("0", enabled) { onDigit('0') }
            IconKey(Icons.AutoMirrored.Rounded.Backspace, "Стереть цифру", enabled = enabled, onClick = onBackspace)
        }
    }
}

private val KEY = 74.dp

@Composable
private fun DigitKey(label: String, enabled: Boolean, onClick: () -> Unit) {
    val fin = FinTheme.colors
    val haptic = LocalHapticFeedback.current
    Box(
        Modifier
            .size(KEY)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.14f else 0.06f))
            .bounceClick(enabled = enabled, pressedScale = 0.88f, onClickLabel = "Цифра $label") {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Medium,
            color = fin.onHero.copy(alpha = if (enabled) 1f else 0.4f),
        )
    }
}

@Composable
private fun IconKey(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val fin = FinTheme.colors
    Box(
        Modifier
            .size(KEY)
            .clip(CircleShape)
            .bounceClick(enabled = enabled, pressedScale = 0.88f, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = fin.onHero.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(30.dp))
    }
}
