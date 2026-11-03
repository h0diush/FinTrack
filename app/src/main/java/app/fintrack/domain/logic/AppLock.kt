package app.fintrack.domain.logic

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Хеш PIN-кода: PBKDF2-HMAC-SHA256 с солью. Сам PIN нигде не хранится —
 * только соль и хеш, и подобрать PIN по ним можно лишь перебором (дорогим из-за итераций).
 */
object PinHasher {

    const val PIN_LENGTH = 4
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256

    fun isValidPin(pin: String): Boolean = pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }

    fun newSalt(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.let { Base64.getEncoder().encodeToString(it) }

    fun hash(pin: String, salt: String): String {
        val spec = PBEKeySpec(pin.toCharArray(), Base64.getDecoder().decode(salt), ITERATIONS, KEY_BITS)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return Base64.getEncoder().encodeToString(bytes)
        } finally {
            spec.clearPassword()
        }
    }

    /** Сравнение за постоянное время — не выдаёт, сколько символов совпало. */
    fun verify(pin: String, salt: String, expectedHash: String): Boolean =
        MessageDigest.isEqual(hash(pin, salt).toByteArray(), expectedHash.toByteArray())
}

/** Когда блокировать приложение и сколько ждать после неверных попыток. */
object LockPolicy {

    /** Варианты «Блокировать через»: подпись → миллисекунды. */
    val TIMEOUTS: List<Pair<String, Long>> = listOf(
        "Сразу" to 0L,
        "Через 1 минуту" to 60_000L,
        "Через 5 минут" to 5 * 60_000L,
        "Через 15 минут" to 15 * 60_000L,
    )
    const val DEFAULT_TIMEOUT_MS = 60_000L
    const val FREE_ATTEMPTS = 5
    private const val MAX_LOCKOUT_MS = 15 * 60_000L

    /** Пора ли закрыть приложение при возвращении: свёрнуто дольше [timeoutMs]. */
    fun shouldLock(backgroundAt: Long?, now: Long, timeoutMs: Long): Boolean =
        backgroundAt != null && now - backgroundAt >= timeoutMs

    /**
     * Пауза после [failures] неверных попыток подряд: первые 5 — без паузы,
     * дальше 30 с, 1 мин, 2 мин, 4 мин… но не больше 15 минут.
     */
    fun lockoutMs(failures: Int): Long {
        if (failures < FREE_ATTEMPTS) return 0L
        val step = (failures - FREE_ATTEMPTS).coerceAtMost(10)
        return (30_000L shl step).coerceAtMost(MAX_LOCKOUT_MS)
    }
}
