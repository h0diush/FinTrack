package app.fintrack.domain.logic

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Шифрование резервной копии паролем: AES-256-GCM, ключ из пароля через PBKDF2-HMAC-SHA256.
 * Ключ Android Keystore здесь не подходит: он не покидает телефон, и после потери телефона
 * копию было бы нечем открыть. GCM заодно проверяет целостность: неверный пароль и испорченный
 * файл дают одну и ту же ошибку, частично расшифрованных данных не бывает.
 *
 * Файл: «FTBK», версия (1 байт), итерации (4 байта), соль (16), IV (12), шифротекст с тегом.
 * Заголовок входит в проверку (AAD) — подменить итерации или соль незаметно нельзя.
 */
object BackupCrypto {

    const val MIN_PASSWORD = 8
    const val DEFAULT_ITERATIONS = 250_000
    private const val MAX_ITERATIONS = 10_000_000
    private val MAGIC = "FTBK".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1
    private const val SALT = 16
    private const val IV = 12
    private const val HEADER = 4 + 1 + 4 + SALT + IV

    fun looksLikeBackup(data: ByteArray): Boolean =
        data.size > HEADER && MAGIC.indices.all { data[it] == MAGIC[it] } && data[4] == VERSION

    private fun key(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    fun encrypt(plain: ByteArray, password: String, iterations: Int = DEFAULT_ITERATIONS): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT).also(random::nextBytes)
        val iv = ByteArray(IV).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER).put(MAGIC).put(VERSION).putInt(iterations).put(salt).put(iv).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }

    /** null — неверный пароль, файл повреждён или это не наша копия. */
    fun decrypt(data: ByteArray, password: String): ByteArray? {
        if (!looksLikeBackup(data)) return null
        val buffer = ByteBuffer.wrap(data, 5, HEADER - 5)
        val iterations = buffer.int
        if (iterations !in 1..MAX_ITERATIONS) return null
        val salt = ByteArray(SALT).also { buffer.get(it) }
        val iv = ByteArray(IV).also { buffer.get(it) }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
            cipher.updateAAD(data, 0, HEADER)
            cipher.doFinal(data, HEADER, data.size - HEADER)
        } catch (e: GeneralSecurityException) {
            null
        }
    }
}

/** Что внутри копии — показывается перед восстановлением. */
data class BackupMeta(
    val createdAt: Long,
    val appVersion: String,
    /** Версия схемы базы: копию из более новой версии приложения открыть нельзя. */
    val schema: Int,
    val operations: Int,
    val accounts: Int,
)

data class BackupContent(val meta: BackupMeta, val database: ByteArray, val settings: Map<String, Any>)

/** Содержимое копии: ZIP с базой, описанием и настройками (сжимается до шифрования). */
object BackupPayload {

    private const val DB = "fintrack.db"
    private const val META = "meta.txt"
    private const val SETTINGS = "settings.txt"
    private const val MAX_ENTRY = 512L * 1024 * 1024

    fun pack(content: BackupContent): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            val m = content.meta
            put(
                META,
                PrefsCodec.encode(
                    mapOf(
                        "createdAt" to m.createdAt,
                        "appVersion" to m.appVersion,
                        "schema" to m.schema,
                        "operations" to m.operations,
                        "accounts" to m.accounts,
                    ),
                ).toByteArray(),
            )
            put(SETTINGS, PrefsCodec.encode(content.settings).toByteArray())
            put(DB, content.database)
        }
        return out.toByteArray()
    }

    /** null — внутри не то, что ожидается. */
    fun unpack(bytes: ByteArray): BackupContent? {
        val entries = HashMap<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val data = ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        data.write(buffer, 0, n)
                        if (data.size() > MAX_ENTRY) return null
                    }
                    entries[e.name] = data.toByteArray()
                }
            }
        } catch (e: ZipException) {
            return null
        }
        val database = entries[DB] ?: return null
        val meta = entries[META]?.let { PrefsCodec.decode(String(it)) } ?: return null
        return BackupContent(
            meta = BackupMeta(
                createdAt = meta["createdAt"] as? Long ?: return null,
                appVersion = meta["appVersion"] as? String ?: return null,
                schema = meta["schema"] as? Int ?: return null,
                operations = meta["operations"] as? Int ?: return null,
                accounts = meta["accounts"] as? Int ?: return null,
            ),
            database = database,
            settings = entries[SETTINGS]?.let { PrefsCodec.decode(String(it)) }.orEmpty(),
        )
    }
}

/** Настройки в тексте: тип, имя и значение в строке. Строки — в Base64, в них бывает что угодно. */
object PrefsCodec {

    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    fun encode(values: Map<String, Any>): String = values.entries.joinToString("\n") { (k, v) ->
        val name = encoder.encodeToString(k.toByteArray())
        when (v) {
            is Boolean -> "b $name $v"
            is Int -> "i $name $v"
            is Long -> "l $name $v"
            is String -> "s $name ${encoder.encodeToString(v.toByteArray())}"
            else -> ""
        }
    }

    fun decode(text: String): Map<String, Any> = text.lineSequence().mapNotNull { line ->
        val p = line.split(' ')
        if (p.size != 3) return@mapNotNull null
        val name = runCatching { String(decoder.decode(p[1])) }.getOrNull() ?: return@mapNotNull null
        val value: Any = when (p[0]) {
            "b" -> p[2].toBooleanStrictOrNull()
            "i" -> p[2].toIntOrNull()
            "l" -> p[2].toLongOrNull()
            "s" -> runCatching { String(decoder.decode(p[2])) }.getOrNull()
            else -> null
        } ?: return@mapNotNull null
        name to value
    }.toMap()
}
