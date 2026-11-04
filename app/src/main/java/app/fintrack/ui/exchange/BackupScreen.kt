@file:OptIn(ExperimentalMaterial3Api::class)

package app.fintrack.ui.exchange

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.fintrack.data.repository.BackupOpen
import app.fintrack.data.repository.BackupRepository
import app.fintrack.data.repository.SecurityRepository
import app.fintrack.domain.logic.BackupContent
import app.fintrack.domain.logic.BackupCrypto
import app.fintrack.domain.logic.ruPlural
import app.fintrack.ui.components.AppCard
import app.fintrack.ui.components.IconBadge
import app.fintrack.ui.piggy.readableOn
import app.fintrack.ui.theme.FinTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

private val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.forLanguageTag("ru"))

private fun moment(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(WHEN)

data class BackupUiState(
    val lastAt: Long? = null,
    /** Что сейчас делается — на это время кнопки недоступны. */
    val busy: String? = null,
    /** Файл выбран — нужен пароль для расшифровки. */
    val askPassword: Boolean = false,
    val passwordError: String? = null,
    /** Копия открыта — ждём подтверждения замены данных. */
    val preview: BackupContent? = null,
)

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backups: BackupRepository,
    private val security: SecurityRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()
    private val events = Channel<String>(Channel.BUFFERED)
    val messages = events.receiveAsFlow()

    private var newPassword: String? = null
    private var file: ByteArray? = null

    init {
        viewModelScope.launch { backups.lastBackupAt.collect { at -> _state.update { it.copy(lastAt = at) } } }
    }

    /** Системное окно выбора файла уводит приложение в фон — PIN при возврате не нужен. */
    fun beforePicker() = security.allowExternalScreen()

    /** Пароль задан — дальше системное окно «Сохранить». */
    fun rememberPassword(password: String) {
        newPassword = password
    }

    fun fileName() = "FinTrack_${LocalDate.now()}.ftbackup"

    fun save(uri: Uri?) {
        val password = newPassword
        newPassword = null
        if (uri == null || password == null) return
        _state.update { it.copy(busy = "Шифруем копию…") }
        viewModelScope.launch {
            val ok = backups.create(uri, password)
            _state.update { it.copy(busy = null) }
            events.send(if (ok) "Резервная копия сохранена" else "Не удалось создать копию")
        }
    }

    fun pick(uri: Uri?) {
        if (uri == null) return
        _state.update { it.copy(busy = "Читаем файл…") }
        viewModelScope.launch {
            val data = backups.read(uri)
            file = data
            _state.update { it.copy(busy = null, askPassword = data != null, passwordError = null) }
            if (data == null) events.send("Это не резервная копия FinTrack или файл не читается")
        }
    }

    fun unlock(password: String) {
        val data = file ?: return
        _state.update { it.copy(busy = "Расшифровываем…", passwordError = null) }
        viewModelScope.launch {
            when (val r = backups.open(data, password)) {
                is BackupOpen.Ok -> _state.update { it.copy(busy = null, askPassword = false, preview = r.content) }
                is BackupOpen.Failure -> _state.update { it.copy(busy = null, passwordError = r.message) }
            }
        }
    }

    fun cancel() {
        file = null
        _state.update { it.copy(askPassword = false, preview = null, passwordError = null) }
    }

    /** При успехе приложение перезапускается и сюда уже не вернётся. */
    fun restore() {
        val content = _state.value.preview ?: return
        _state.update { it.copy(preview = null, busy = "Восстанавливаем данные…") }
        viewModelScope.launch { backups.restore(content) }
    }
}

@Composable
fun BackupScreen(onBack: () -> Unit, vm: BackupViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var askNewPassword by remember { mutableStateOf(false) }
    val fin = FinTheme.colors

    LaunchedEffect(Unit) { vm.messages.collect { scope.launch { snackbar.showSnackbar(it) } } }

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"), vm::save)
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), vm::pick)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Резервная копия") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.Backup, MaterialTheme.colorScheme.primary, size = 48.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.lastAt?.let { "Последняя копия" } ?: "Копий ещё не было",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            state.lastAt?.let(::moment) ?: "Сейчас данные хранятся только на этом телефоне",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.lastAt == null) fin.statusWarning.readableOn(MaterialTheme.colorScheme.surfaceContainer)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            val busy = state.busy
            if (busy != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(busy, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Button(
                onClick = { askNewPassword = true },
                enabled = busy == null,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Rounded.Lock, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Создать копию")
            }
            OutlinedButton(
                onClick = {
                    vm.beforePicker()
                    openFile.launch(arrayOf("*/*"))
                },
                enabled = busy == null,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Rounded.Restore, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Восстановить из копии")
            }

            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint("Что внутри", "Все счета, операции, кредиты, копилки, бюджеты, регулярные платежи и настройки. PIN-код в копию не входит.")
                Hint("Где хранить", "Вне телефона: Google Диск, почта, компьютер. Копия в памяти телефона пропадёт вместе с ним.")
                Hint("Пароль", "Файл зашифрован вашим паролем. Восстановить забытый пароль нельзя: без него копию не открыть никому, и вам тоже.")
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (askNewPassword) {
        NewPasswordDialog(
            onDismiss = { askNewPassword = false },
            onConfirm = { password ->
                askNewPassword = false
                vm.rememberPassword(password)
                vm.beforePicker()
                saveFile.launch(vm.fileName())
            },
        )
    }
    if (state.askPassword) {
        OpenPasswordDialog(state.passwordError, busy = state.busy != null, onDismiss = vm::cancel, onConfirm = vm::unlock)
    }
    state.preview?.let { content ->
        val m = content.meta
        AlertDialog(
            onDismissRequest = vm::cancel,
            title = { Text("Заменить все данные?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Копия от ${moment(m.createdAt)}", fontWeight = FontWeight.SemiBold)
                    Text(
                        "${m.operations} ${ruPlural(m.operations.toLong(), "операция", "операции", "операций")}, " +
                            "${m.accounts} ${ruPlural(m.accounts.toLong(), "счёт", "счёта", "счетов")}",
                    )
                    Text(
                        "Всё, что сейчас есть в приложении, будет заменено содержимым копии. Операции, внесённые после неё, пропадут. " +
                            "Приложение перезапустится, PIN-код останется прежним.",
                    )
                }
            },
            confirmButton = { TextButton(onClick = vm::restore) { Text("Заменить", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = vm::cancel) { Text("Отмена") } },
        )
    }
}

@Composable
private fun Hint(title: String, text: String) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, isError: Boolean, support: String?) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = support?.let { { Text(it) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    if (visible) "Скрыть пароль" else "Показать пароль",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NewPasswordDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val short = first.isNotEmpty() && first.length < BackupCrypto.MIN_PASSWORD
    val mismatch = second.isNotEmpty() && first != second
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Пароль для копии") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Запишите пароль в надёжном месте. Если забыть его, копию открыть не получится — восстановить пароль нельзя.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                PasswordField(first, { first = it }, "Пароль", short, "Не меньше ${BackupCrypto.MIN_PASSWORD} символов")
                PasswordField(second, { second = it }, "Повторите пароль", mismatch, if (mismatch) "Пароли не совпадают" else null)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(first) },
                enabled = first.length >= BackupCrypto.MIN_PASSWORD && first == second,
            ) { Text("Далее") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun OpenPasswordDialog(error: String?, busy: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Пароль копии") },
        text = { PasswordField(password, { password = it }, "Пароль", error != null, error) },
        confirmButton = { TextButton(onClick = { onConfirm(password) }, enabled = password.isNotEmpty() && !busy) { Text(if (busy) "Проверяем…" else "Открыть") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
