package app.fintrack.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.fintrack.domain.logic.LockPolicy
import app.fintrack.domain.logic.PinHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class SecuritySettings(
    val pinSet: Boolean = false,
    val biometric: Boolean = false,
    val timeoutMs: Long = LockPolicy.DEFAULT_TIMEOUT_MS,
    /** FLAG_SECURE: не показывать в недавних приложениях, запретить скриншоты. */
    val secureScreen: Boolean = false,
)

sealed interface PinCheck {
    data object Ok : PinCheck
    /** Неверный PIN; [attemptsLeft] до паузы (0 — следующая ошибка включит паузу). */
    data class Wrong(val attemptsLeft: Int, val lockedForMs: Long) : PinCheck
    /** Слишком много ошибок — подождать. */
    data class LockedOut(val remainingMs: Long) : PinCheck
}

/** Состояние экрана: проверяем, заблокировано, открыто. */
enum class LockState { CHECKING, LOCKED, UNLOCKED }

/**
 * Защита приложения: PIN (хранится только соль + хеш), вход по биометрии, время блокировки,
 * счётчик неверных попыток с паузой. Также держит текущее состояние блокировки процесса.
 */
@Singleton
class SecurityRepository @Inject constructor(
    private val store: DataStore<Preferences>,
) {
    private object Keys {
        val PIN_HASH = stringPreferencesKey("pin_hash")
        val PIN_SALT = stringPreferencesKey("pin_salt")
        val BIOMETRIC = booleanPreferencesKey("lock_biometric")
        val TIMEOUT = longPreferencesKey("lock_timeout_ms")
        val SECURE = booleanPreferencesKey("lock_secure_screen")
        val FAILURES = intPreferencesKey("lock_failures")
        val LOCKOUT_UNTIL = longPreferencesKey("lock_lockout_until")
    }

    private val prefs = store.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    val settings: Flow<SecuritySettings> = prefs.map { p ->
        SecuritySettings(
            pinSet = p[Keys.PIN_HASH] != null,
            biometric = p[Keys.BIOMETRIC] ?: false,
            timeoutMs = p[Keys.TIMEOUT] ?: LockPolicy.DEFAULT_TIMEOUT_MS,
            secureScreen = p[Keys.SECURE] ?: false,
        )
    }

    // ---------- Состояние блокировки (живёт вместе с процессом) ----------

    private val _lock = MutableStateFlow(LockState.CHECKING)
    val lockState: StateFlow<LockState> = _lock.asStateFlow()
    private var backgroundAt: Long? = null
    private var exemptUntil = 0L

    /** При запуске процесса: есть PIN — сразу заблокировано. */
    suspend fun initLock() {
        if (_lock.value == LockState.CHECKING) {
            _lock.value = if (settings.first().pinSet) LockState.LOCKED else LockState.UNLOCKED
        }
    }

    fun onBackground(now: Long = System.currentTimeMillis()) {
        if (_lock.value == LockState.UNLOCKED) backgroundAt = now
    }

    /**
     * Приложение сейчас откроет системное окно (выбор файла) и уйдёт в фон по просьбе пользователя:
     * при возвращении в ближайшие 5 минут PIN не спрашивать, иначе экспорт прервётся блокировкой.
     */
    fun allowExternalScreen(now: Long = System.currentTimeMillis()) {
        exemptUntil = now + 5 * 60_000L
    }

    suspend fun onForeground(now: Long = System.currentTimeMillis()) {
        val s = settings.first()
        val exempt = now < exemptUntil
        exemptUntil = 0L
        if (!exempt && s.pinSet && _lock.value == LockState.UNLOCKED && LockPolicy.shouldLock(backgroundAt, now, s.timeoutMs)) {
            _lock.value = LockState.LOCKED
        }
        backgroundAt = null
    }

    fun unlock() {
        _lock.value = LockState.UNLOCKED
    }

    // ---------- PIN ----------

    /** Проверка PIN с учётом паузы после ошибок. Хеширование — не на главном потоке. */
    suspend fun check(pin: String, now: Long = System.currentTimeMillis()): PinCheck {
        val p = prefs.first()
        val until = p[Keys.LOCKOUT_UNTIL] ?: 0L
        if (until > now) return PinCheck.LockedOut(until - now)
        val hash = p[Keys.PIN_HASH] ?: return PinCheck.Ok
        val salt = p[Keys.PIN_SALT] ?: return PinCheck.Ok
        val ok = withContext(Dispatchers.Default) { PinHasher.verify(pin, salt, hash) }
        if (ok) {
            store.edit {
                it[Keys.FAILURES] = 0
                it.remove(Keys.LOCKOUT_UNTIL)
            }
            return PinCheck.Ok
        }
        val failures = (p[Keys.FAILURES] ?: 0) + 1
        val lockout = LockPolicy.lockoutMs(failures)
        store.edit {
            it[Keys.FAILURES] = failures
            if (lockout > 0) it[Keys.LOCKOUT_UNTIL] = now + lockout
        }
        return PinCheck.Wrong((LockPolicy.FREE_ATTEMPTS - failures).coerceAtLeast(0), lockout)
    }

    /** Вход по биометрии прошёл — счётчик ошибок PIN обнуляется. */
    suspend fun resetFailures() {
        store.edit {
            it[Keys.FAILURES] = 0
            it.remove(Keys.LOCKOUT_UNTIL)
        }
    }

    /** Сколько ещё ждать после ошибок (0 — можно вводить). */
    suspend fun lockoutRemaining(now: Long = System.currentTimeMillis()): Long =
        ((prefs.first()[Keys.LOCKOUT_UNTIL] ?: 0L) - now).coerceAtLeast(0L)

    suspend fun setPin(pin: String) {
        require(PinHasher.isValidPin(pin))
        val salt = PinHasher.newSalt()
        val hash = withContext(Dispatchers.Default) { PinHasher.hash(pin, salt) }
        store.edit {
            it[Keys.PIN_SALT] = salt
            it[Keys.PIN_HASH] = hash
            it[Keys.FAILURES] = 0
            it.remove(Keys.LOCKOUT_UNTIL)
        }
        _lock.value = LockState.UNLOCKED
    }

    /** Выключить защиту: PIN и вход по биометрии удаляются. */
    suspend fun clearPin() {
        store.edit {
            it.remove(Keys.PIN_HASH)
            it.remove(Keys.PIN_SALT)
            it.remove(Keys.BIOMETRIC)
            it.remove(Keys.FAILURES)
            it.remove(Keys.LOCKOUT_UNTIL)
        }
        _lock.value = LockState.UNLOCKED
    }

    suspend fun setBiometric(enabled: Boolean) {
        store.edit { it[Keys.BIOMETRIC] = enabled }
    }

    suspend fun setTimeout(ms: Long) {
        store.edit { it[Keys.TIMEOUT] = ms }
    }

    suspend fun setSecureScreen(enabled: Boolean) {
        store.edit { it[Keys.SECURE] = enabled }
    }
}
