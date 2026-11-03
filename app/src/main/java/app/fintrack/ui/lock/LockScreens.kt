package app.fintrack.ui.lock

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockReset
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.fintrack.data.repository.PinCheck
import app.fintrack.data.repository.SecurityRepository
import app.fintrack.domain.logic.PinHasher
import app.fintrack.domain.logic.ruPlural
import app.fintrack.ui.theme.FinTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** «30 с», «2 мин 10 с». */
private fun waitText(ms: Long): String {
    val s = ((ms + 999) / 1000).coerceAtLeast(1)
    return if (s < 60) "$s с" else "${s / 60} мин" + if (s % 60 > 0) " ${s % 60} с" else ""
}

data class PinUiState(
    val digits: String = "",
    val message: String? = null,
    val isError: Boolean = false,
    val shake: Int = 0,
    /** Пауза после ошибок — ввод недоступен. */
    val lockedOut: Boolean = false,
    val biometric: Boolean = false,
)

// ---------- Экран блокировки ----------

@HiltViewModel
class LockViewModel @Inject constructor(private val security: SecurityRepository) : ViewModel() {

    private val _state = MutableStateFlow(PinUiState())
    val state: StateFlow<PinUiState> = _state.asStateFlow()
    private var countdown: Job? = null

    init {
        viewModelScope.launch {
            _state.update { it.copy(biometric = security.settings.first().biometric) }
            security.lockoutRemaining().takeIf { it > 0 }?.let(::startCountdown)
        }
    }

    fun digit(d: Char) {
        val s = _state.value
        if (s.lockedOut || s.digits.length >= PinHasher.PIN_LENGTH) return
        val next = s.digits + d
        _state.update { it.copy(digits = next, isError = false, message = null) }
        if (next.length == PinHasher.PIN_LENGTH) viewModelScope.launch { check(next) }
    }

    fun backspace() = _state.update { it.copy(digits = it.digits.dropLast(1), isError = false) }

    fun biometricSuccess() {
        viewModelScope.launch {
            security.resetFailures()
            _state.update { it.copy(digits = "", message = null, isError = false) }
            security.unlock()
        }
    }

    private suspend fun check(pin: String) {
        when (val r = security.check(pin)) {
            PinCheck.Ok -> {
                // Экран живёт дальше — к следующей блокировке он должен быть чистым.
                _state.update { it.copy(digits = "", message = null, isError = false) }
                security.unlock()
            }
            is PinCheck.Wrong -> {
                _state.update {
                    it.copy(
                        digits = "",
                        isError = true,
                        shake = it.shake + 1,
                        message = if (r.attemptsLeft > 0) {
                            "Неверный PIN-код. ${ruPlural(r.attemptsLeft.toLong(), "Осталась", "Осталось", "Осталось")} " +
                                "${r.attemptsLeft} ${ruPlural(r.attemptsLeft.toLong(), "попытка", "попытки", "попыток")}"
                        } else {
                            "Неверный PIN-код"
                        },
                    )
                }
                if (r.lockedForMs > 0) startCountdown(r.lockedForMs)
            }
            is PinCheck.LockedOut -> {
                _state.update { it.copy(digits = "") }
                startCountdown(r.remainingMs)
            }
        }
    }

    private fun startCountdown(ms: Long) {
        countdown?.cancel()
        countdown = viewModelScope.launch {
            var left = ms
            while (left > 0) {
                _state.update { it.copy(lockedOut = true, isError = true, message = "Слишком много попыток. Подождите ${waitText(left)}") }
                delay(1000)
                left = security.lockoutRemaining()
            }
            _state.update { it.copy(lockedOut = false, isError = false, message = "Можно попробовать снова") }
        }
    }
}

/** Полноэкранная блокировка: PIN и (если включено) отпечаток. «Назад» сворачивает приложение. */
@Composable
fun LockScreen(vm: LockViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val canBiometric = state.biometric && activity != null && Biometrics.available(context)
    val askBiometric = { activity?.let { Biometrics.prompt(it, "Приложите палец или посмотрите в камеру", vm::biometricSuccess) } }
    var forgot by remember { mutableStateOf(false) }

    BackHandler { (context as? Activity)?.moveTaskToBack(true) }
    // Отпечаток предлагаем сразу — так быстрее всего.
    LaunchedEffect(canBiometric) { if (canBiometric && !state.lockedOut) askBiometric() }

    PinScreen(
        title = "Введите PIN-код",
        entered = state.digits.length,
        message = state.message,
        isError = state.isError,
        shake = state.shake,
        enabled = !state.lockedOut,
        icon = Icons.Rounded.Lock,
        onDigit = vm::digit,
        onBackspace = vm::backspace,
        onBiometric = if (canBiometric) ({ askBiometric() }) else null,
        footer = {
            TextButton(onClick = { forgot = true }) { Text("Забыли PIN-код?", color = FinTheme.colors.onHeroMuted) }
        },
    )

    if (forgot) {
        AlertDialog(
            onDismissRequest = { forgot = false },
            title = { Text("Забыли PIN-код?") },
            text = {
                Text(
                    "PIN хранится только на этом телефоне в зашифрованном виде, восстановить его нельзя. " +
                        (if (canBiometric) "Войдите по отпечатку и задайте новый PIN в настройках. " else "") +
                        "Иначе остаётся только очистить данные приложения в настройках Android — все записи будут удалены.",
                )
            },
            confirmButton = { TextButton(onClick = { forgot = false }) { Text("Понятно") } },
        )
    }
}

// ---------- Установка, смена и снятие PIN ----------

enum class PinMode { SET, CHANGE, DISABLE }

private enum class PinStep { CURRENT, NEW, REPEAT }

data class PinSetupUiState(
    val pin: PinUiState = PinUiState(),
    val title: String = "",
)

@HiltViewModel
class PinSetupViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val security: SecurityRepository,
) : ViewModel() {

    val mode: PinMode = savedState.get<String>("mode")?.let { m -> PinMode.entries.firstOrNull { it.name == m } } ?: PinMode.SET
    private var step = if (mode == PinMode.SET) PinStep.NEW else PinStep.CURRENT
    private var first = ""

    private val _state = MutableStateFlow(PinSetupUiState(title = titleFor(step)))
    val state: StateFlow<PinSetupUiState> = _state.asStateFlow()
    private val events = Channel<String>(Channel.BUFFERED)
    /** Готово: текст для сообщения, экран закрывается. */
    val done = events.receiveAsFlow()

    private fun titleFor(s: PinStep) = when (s) {
        PinStep.CURRENT -> "Введите текущий PIN-код"
        PinStep.NEW -> if (mode == PinMode.CHANGE) "Новый PIN-код" else "Придумайте PIN-код из ${PinHasher.PIN_LENGTH} цифр"
        PinStep.REPEAT -> "Повторите PIN-код"
    }

    private fun go(next: PinStep, message: String? = null, error: Boolean = false) {
        step = next
        _state.update {
            it.copy(
                title = titleFor(next),
                pin = it.pin.copy(digits = "", message = message, isError = error, shake = if (error) it.pin.shake + 1 else it.pin.shake),
            )
        }
    }

    fun digit(d: Char) {
        val s = _state.value.pin
        if (s.lockedOut || s.digits.length >= PinHasher.PIN_LENGTH) return
        val next = s.digits + d
        _state.update { it.copy(pin = it.pin.copy(digits = next, isError = false)) }
        if (next.length == PinHasher.PIN_LENGTH) viewModelScope.launch { complete(next) }
    }

    fun backspace() = _state.update { it.copy(pin = it.pin.copy(digits = it.pin.digits.dropLast(1), isError = false)) }

    private suspend fun complete(pin: String) {
        when (step) {
            PinStep.CURRENT -> when (val r = security.check(pin)) {
                PinCheck.Ok -> if (mode == PinMode.DISABLE) {
                    security.clearPin()
                    events.send("Блокировка выключена")
                } else {
                    go(PinStep.NEW)
                }
                is PinCheck.Wrong -> go(PinStep.CURRENT, "Неверный PIN-код", error = true)
                is PinCheck.LockedOut -> go(PinStep.CURRENT, "Слишком много попыток. Подождите ${waitText(r.remainingMs)}", error = true)
            }
            PinStep.NEW -> {
                first = pin
                go(PinStep.REPEAT)
            }
            PinStep.REPEAT -> if (pin == first) {
                security.setPin(pin)
                events.send(if (mode == PinMode.CHANGE) "PIN-код изменён" else "Блокировка включена")
            } else {
                first = ""
                go(PinStep.NEW, "PIN-коды не совпали — придумайте заново", error = true)
            }
        }
    }
}

@Composable
fun PinSetupScreen(onDone: (String?) -> Unit, vm: PinSetupViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var warned by remember { mutableStateOf(vm.mode != PinMode.SET) }
    LaunchedEffect(Unit) { vm.done.collect { onDone(it) } }
    BackHandler { onDone(null) }

    PinScreen(
        title = state.title,
        entered = state.pin.digits.length,
        message = state.pin.message,
        isError = state.pin.isError,
        shake = state.pin.shake,
        enabled = !state.pin.lockedOut,
        icon = if (vm.mode == PinMode.SET) Icons.Rounded.Pin else Icons.Rounded.LockReset,
        onDigit = vm::digit,
        onBackspace = vm::backspace,
        onBiometric = null,
        footer = { TextButton(onClick = { onDone(null) }) { Text("Отмена", color = FinTheme.colors.onHeroMuted) } },
    )

    if (!warned) {
        AlertDialog(
            onDismissRequest = { onDone(null) },
            title = { Text("Запомните PIN-код") },
            text = {
                Text(
                    "Восстановить забытый PIN нельзя: аккаунтов нет, данные только на этом телефоне. " +
                        "Включите вход по отпечатку в настройках — он поможет, если PIN вылетит из головы.",
                )
            },
            confirmButton = { TextButton(onClick = { warned = true }) { Text("Понятно") } },
            dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Отмена") } },
        )
    }
}
