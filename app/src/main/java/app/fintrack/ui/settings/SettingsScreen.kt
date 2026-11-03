@file:OptIn(ExperimentalMaterial3Api::class)

package app.fintrack.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material.icons.rounded.RequestQuote
import androidx.compose.material.icons.rounded.Euro
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.fintrack.BuildConfig
import app.fintrack.data.repository.SettingsRepository
import app.fintrack.domain.model.AppSettings
import app.fintrack.domain.model.ThemeMode
import app.fintrack.ui.components.AppCard
import app.fintrack.ui.components.IconBadge
import app.fintrack.ui.components.bounceClick
import androidx.compose.ui.graphics.Color
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockReset
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.CurrencyExchange
import androidx.compose.ui.semantics.Role
import app.fintrack.ui.components.CurrencyPickerDialog
import app.fintrack.ui.components.currencyTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val security: app.fintrack.data.repository.SecurityRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> =
        repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val securitySettings: StateFlow<app.fintrack.data.repository.SecuritySettings> =
        security.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), app.fintrack.data.repository.SecuritySettings())

    fun setBiometric(enabled: Boolean) {
        viewModelScope.launch { security.setBiometric(enabled) }
    }

    fun setLockTimeout(ms: Long) {
        viewModelScope.launch { security.setTimeout(ms) }
    }

    fun setSecureScreen(enabled: Boolean) {
        viewModelScope.launch { security.setSecureScreen(enabled) }
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { repo.setTheme(mode) }
    }

    fun setBaseCurrency(code: String) {
        viewModelScope.launch { repo.setBaseCurrency(code) }
    }

    fun setHideAmounts(hide: Boolean) {
        viewModelScope.launch { repo.setHideAmounts(hide) }
    }

    fun setBudgetAlerts(enabled: Boolean) {
        viewModelScope.launch { repo.setBudgetAlerts(enabled) }
    }
}

private fun ThemeMode.title() = when (this) {
    ThemeMode.SYSTEM -> "Как в системе"
    ThemeMode.LIGHT -> "Светлая"
    ThemeMode.DARK -> "Тёмная"
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenLoans: () -> Unit,
    onOpenRecurring: () -> Unit,
    onOpenRates: () -> Unit,
    onPin: (mode: String) -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val sec by vm.securitySettings.collectAsStateWithLifecycle()
    val biometricAvailable = app.fintrack.ui.lock.Biometrics.available(androidx.compose.ui.platform.LocalContext.current)
    var showTimeout by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val toggleBudgetAlerts = { enabled: Boolean ->
        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        vm.setBudgetAlerts(enabled)
    }
    var showTheme by remember { mutableStateOf(false) }
    var showCurrency by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingRow(Icons.Rounded.Palette, "Тема", s.themeMode.title(), Color(0xFF9B51E0)) { showTheme = true }
                    SettingsDivider()
                    SettingRow(
                        Icons.Rounded.Euro,
                        "Основная валюта",
                        currencyTitle(s.baseCurrency),
                        Color(0xFF10B981),
                    ) { showCurrency = true }
                    SettingsDivider()
                    SettingRow(Icons.Rounded.CurrencyExchange, "Курсы валют", "НБРБ каждый день, можно задать свой курс", Color(0xFF0D9488), onClick = onOpenRates)
                    SettingsDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .bounceClick(pressedScale = 0.98f, role = Role.Switch) { vm.setHideAmounts(!s.hideAmounts) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconBadge(Icons.Rounded.VisibilityOff, Color(0xFF0EA5E9), size = 40.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Скрывать суммы", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Суммы заменяются на «••••»",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = s.hideAmounts, onCheckedChange = vm::setHideAmounts)
                    }
                    SettingsDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .bounceClick(pressedScale = 0.98f, role = Role.Switch) { toggleBudgetAlerts(!s.budgetAlerts) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconBadge(Icons.Rounded.NotificationsActive, Color(0xFFF59E0B), size = 40.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Уведомления о бюджете", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "При ${s.budgetInfoPercent} %, ${s.budgetWarningPercent} % и превышении лимита",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = s.budgetAlerts, onCheckedChange = toggleBudgetAlerts)
                    }
                    SettingsDivider()
                    SettingRow(Icons.Rounded.Category, "Категории", "Создание, подкатегории, иконки, архив", Color(0xFFF97316), onClick = onOpenCategories)
                    SettingsDivider()
                    SettingRow(Icons.Rounded.AccountBalanceWallet, "Счета", "Балансы, порядок, архив, корректировка", Color(0xFF2F55E4), onClick = onOpenAccounts)
                    SettingsDivider()
                    SettingRow(Icons.Rounded.EventRepeat, "Регулярные платежи", "Подписки, аренда, зарплата — по расписанию", Color(0xFF6D4AFF), onClick = onOpenRecurring)
                    SettingsDivider()
                    SettingRow(Icons.Rounded.RequestQuote, "Кредиты", "Кредиты, рассрочки, ипотека и графики платежей", Color(0xFF059669), onClick = onOpenLoans)
                }
            }

            Spacer(Modifier.padding(8.dp))

            Text(
                "Защита",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SwitchRow(
                        icon = Icons.Rounded.Lock,
                        tint = Color(0xFF2F55E4),
                        title = "Вход по PIN-коду",
                        subtitle = if (sec.pinSet) "Приложение закрывается PIN-кодом" else "Код из 4 цифр при открытии приложения",
                        checked = sec.pinSet,
                        onChange = { on -> onPin(if (on) "SET" else "DISABLE") },
                    )
                    androidx.compose.animation.AnimatedVisibility(visible = sec.pinSet) {
                        Column {
                            if (biometricAvailable) {
                                SettingsDivider()
                                SwitchRow(
                                    icon = Icons.Rounded.Fingerprint,
                                    tint = Color(0xFF10B981),
                                    title = "Вход по отпечатку или лицу",
                                    subtitle = "Быстрее PIN-кода; PIN остаётся запасным",
                                    checked = sec.biometric,
                                    onChange = vm::setBiometric,
                                )
                            }
                            SettingsDivider()
                            SettingRow(
                                Icons.Rounded.Timer,
                                "Блокировать",
                                app.fintrack.domain.logic.LockPolicy.TIMEOUTS.firstOrNull { it.second == sec.timeoutMs }?.first?.let {
                                    if (it == "Сразу") "Сразу после сворачивания" else "$it после сворачивания"
                                } ?: "После сворачивания",
                                Color(0xFFF59E0B),
                            ) { showTimeout = true }
                            SettingsDivider()
                            SettingRow(Icons.Rounded.LockReset, "Сменить PIN-код", "Нужно ввести текущий", Color(0xFF6D4AFF)) { onPin("CHANGE") }
                        }
                    }
                    SettingsDivider()
                    SwitchRow(
                        icon = Icons.Rounded.VisibilityOff,
                        tint = Color(0xFF475569),
                        title = "Скрывать в недавних приложениях",
                        subtitle = "Суммы не видны в списке приложений; скриншоты и запись экрана запрещены",
                        checked = sec.secureScreen,
                        onChange = vm::setSecureScreen,
                    )
                }
            }

            Spacer(Modifier.padding(8.dp))

            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingRow(
                        Icons.Rounded.Info,
                        "FinTrack ${BuildConfig.VERSION_NAME}",
                        "Данные хранятся только на этом устройстве. Без рекламы, аккаунтов и передачи данных.",
                        MaterialTheme.colorScheme.primary,
                        onClick = null,
                    )
                }
            }
        }
    }

    if (showTimeout) {
        AlertDialog(
            onDismissRequest = { showTimeout = false },
            title = { Text("Блокировать") },
            text = {
                Column {
                    app.fintrack.domain.logic.LockPolicy.TIMEOUTS.forEach { (label, ms) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .bounceClick(pressedScale = 0.98f, role = Role.RadioButton) { vm.setLockTimeout(ms); showTimeout = false }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.RadioButton(selected = sec.timeoutMs == ms, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(if (ms == 0L) "Сразу после сворачивания" else "$label после сворачивания")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTimeout = false }) { Text("Закрыть") } },
        )
    }

    if (showTheme) {
        AlertDialog(
            onDismissRequest = { showTheme = false },
            title = { Text("Тема") },
            text = {
                Column {
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = s.themeMode == mode, onClick = {
                                    vm.setTheme(mode)
                                    showTheme = false
                                })
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = s.themeMode == mode, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(mode.title())
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showTheme = false }) { Text("Закрыть") } },
        )
    }

    if (showCurrency) {
        CurrencyPickerDialog(
            selected = s.baseCurrency,
            onSelect = {
                vm.setBaseCurrency(it)
                showCurrency = false
            },
            onDismiss = { showCurrency = false },
        )
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(modifier = Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/** Строка с переключателем: нажатие по всей строке тоже переключает. */
@Composable
private fun SwitchRow(icon: ImageVector, tint: Color, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bounceClick(pressedScale = 0.98f, role = Role.Switch) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, tint, size = 40.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, tint: Color, onClick: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.bounceClick(pressedScale = 0.98f, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, tint, size = 40.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
