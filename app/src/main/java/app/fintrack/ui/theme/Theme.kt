package app.fintrack.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fintrack.R

/**
 * Дизайн-система FinTrack: «доверительный синий + зелёный для прибыли».
 * Все пары текст/фон проверены на контраст WCAG ≥ 4.5:1 в обеих темах.
 */

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** Финансовые цвета, которых нет в Material ColorScheme. */
@Immutable
data class FinColors(
    val income: Color,
    val expense: Color,
    val transfer: Color,
    val neutral: Color,
    val heroStart: Color,
    val heroMid: Color,
    val heroEnd: Color,
    val onHero: Color,
    val onHeroMuted: Color,
    /** Цвета серий графиков: бирюзовый/коралловый различимы при дальтонизме (ΔE ≥ 9). */
    val chartIncome: Color,
    val chartExpense: Color,
    /** Статусы бюджета — только вместе с иконкой и подписью. */
    val statusGood: Color,
    val statusWarning: Color,
    val statusCritical: Color,
)

private val LightFin = FinColors(
    income = Color(0xFF047857),
    expense = Color(0xFFD92D3A),
    transfer = Color(0xFF2F55E4),
    neutral = Color(0xFF64748B),
    heroStart = Color(0xFF2440D8),
    heroMid = Color(0xFF4B3FDE),
    heroEnd = Color(0xFF6D3FE0),
    onHero = Color(0xFFFFFFFF),
    onHeroMuted = Color(0xFFD5D9FA),
    chartIncome = Color(0xFF0D9488),
    chartExpense = Color(0xFFDC3545),
    statusGood = Color(0xFF047857),
    statusWarning = Color(0xFFB45309),
    statusCritical = Color(0xFFD92D3A),
)

private val DarkFin = FinColors(
    income = Color(0xFF34D399),
    expense = Color(0xFFFF7B7B),
    transfer = Color(0xFF8EA8FF),
    neutral = Color(0xFF94A3B8),
    heroStart = Color(0xFF1E2E9A),
    heroMid = Color(0xFF332DA2),
    heroEnd = Color(0xFF4A2FA8),
    onHero = Color(0xFFFFFFFF),
    onHeroMuted = Color(0xFFCBD0F5),
    chartIncome = Color(0xFF0EA5A0),
    chartExpense = Color(0xFFE8606E),
    statusGood = Color(0xFF34D399),
    statusWarning = Color(0xFFFBBF24),
    statusCritical = Color(0xFFFF7B7B),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF2F55E4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E9FF),
    onPrimaryContainer = Color(0xFF0B1E66),
    secondary = Color(0xFF4A5670),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9EDF6),
    onSecondaryContainer = Color(0xFF1B2335),
    tertiary = Color(0xFF047857),
    background = Color(0xFFF3F5FA),
    onBackground = Color(0xFF0E1525),
    surface = Color(0xFFF3F5FA),
    onSurface = Color(0xFF0E1525),
    surfaceVariant = Color(0xFFE9EDF6),
    onSurfaceVariant = Color(0xFF5A6478),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEDF0F7),
    surfaceContainerHighest = Color(0xFFE4E8F1),
    outline = Color(0xFFC5CCDA),
    outlineVariant = Color(0xFFE6E9F1),
    error = Color(0xFFD92D3A),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8EA8FF),
    onPrimary = Color(0xFF0A1A5C),
    primaryContainer = Color(0xFF2A3A8C),
    onPrimaryContainer = Color(0xFFDCE3FF),
    secondary = Color(0xFFAEB8CE),
    onSecondary = Color(0xFF1B2335),
    secondaryContainer = Color(0xFF252D45),
    onSecondaryContainer = Color(0xFFE2E7F2),
    tertiary = Color(0xFF34D399),
    background = Color(0xFF0B1020),
    onBackground = Color(0xFFE8ECF5),
    surface = Color(0xFF0B1020),
    onSurface = Color(0xFFE8ECF5),
    surfaceVariant = Color(0xFF1C2338),
    onSurfaceVariant = Color(0xFF9AA5BD),
    surfaceContainerLowest = Color(0xFF0E1426),
    surfaceContainerLow = Color(0xFF121829),
    surfaceContainer = Color(0xFF151B2E),
    surfaceContainerHigh = Color(0xFF1C2338),
    surfaceContainerHighest = Color(0xFF252D45),
    outline = Color(0xFF3A4360),
    outlineVariant = Color(0xFF242C42),
    error = Color(0xFFFF7B7B),
)

private fun TextStyle.inter(weight: FontWeight? = null, tracking: Float? = null) = copy(
    fontFamily = Inter,
    fontWeight = weight ?: fontWeight,
    letterSpacing = tracking?.sp ?: letterSpacing,
)

private val AppTypography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.inter(FontWeight.Bold, -1.5f),
        displayMedium = t.displayMedium.inter(FontWeight.Bold, -1.2f),
        displaySmall = t.displaySmall.inter(FontWeight.Bold, -1f),
        headlineLarge = t.headlineLarge.inter(FontWeight.Bold, -0.8f),
        headlineMedium = t.headlineMedium.inter(FontWeight.SemiBold, -0.6f),
        headlineSmall = t.headlineSmall.inter(FontWeight.SemiBold, -0.4f),
        titleLarge = t.titleLarge.inter(FontWeight.SemiBold, -0.3f),
        titleMedium = t.titleMedium.inter(FontWeight.SemiBold, -0.1f),
        titleSmall = t.titleSmall.inter(FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.inter(tracking = 0f),
        bodyMedium = t.bodyMedium.inter(tracking = 0f),
        bodySmall = t.bodySmall.inter(tracking = 0.1f),
        labelLarge = t.labelLarge.inter(FontWeight.SemiBold, 0f),
        labelMedium = t.labelMedium.inter(FontWeight.Medium, 0.2f),
        labelSmall = t.labelSmall.inter(FontWeight.Medium, 0.2f),
    )
}

/** Моноширинные цифры для сумм: колонки не «прыгают» при изменении. */
val AmountStyle = TextStyle(fontFeatureSettings = "tnum")

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

val LocalFinColors = staticCompositionLocalOf { LightFin }

object FinTheme {
    val colors: FinColors
        @Composable get() = LocalFinColors.current
}

@Composable
fun FinTrackTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalFinColors provides if (darkTheme) DarkFin else LightFin) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
