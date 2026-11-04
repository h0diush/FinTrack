@file:OptIn(ExperimentalMaterial3Api::class)

package app.fintrack.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.fintrack.BuildConfig
import app.fintrack.ui.components.IconBadge
import app.fintrack.ui.components.StaggeredAppear

private class PlannedFeature(val icon: ImageVector, val color: Color, val title: String)

/** Что появится в следующих версиях. Сделанное отсюда убирать. */
private val PLANNED = listOf(
    PlannedFeature(Icons.Rounded.Backup, Color(0xFF2563EB), "Автоматические резервные копии"),
    PlannedFeature(Icons.Rounded.NotificationsActive, Color(0xFF059669), "Напоминания о платежах по кредитам"),
    PlannedFeature(Icons.Rounded.Savings, Color(0xFFD97706), "Налог на проценты и пополнение вклада"),
    PlannedFeature(Icons.Rounded.Bolt, Color(0xFF7C3AED), "Шаблоны частых операций"),
    PlannedFeature(Icons.Rounded.PhotoCamera, Color(0xFFDB2777), "Фото чеков к операциям"),
)

/** Окно «О приложении»: версия, планы, разработчики. */
@Composable
fun AboutSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            StaggeredAppear(0) {
                // Отступ сверху: выбранный значок чуть увеличен, а появление обрезает всё за границами блока
                Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    IconBadge(Icons.Rounded.AccountBalanceWallet, MaterialTheme.colorScheme.primary, size = 72.dp, selected = true)
                    Spacer(Modifier.height(12.dp))
                    Text("FinTrack", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Версия ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Данные хранятся только на этом устройстве. Без рекламы, аккаунтов и передачи данных.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            StaggeredAppear(1) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("В будущем будет добавлено", style = MaterialTheme.typography.titleMedium)
                    PLANNED.forEach { feature ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(feature.icon, feature.color, size = 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(feature.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))
            StaggeredAppear(2) {
                Text(
                    "Разработали Ходюш А.С. и Claude",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
