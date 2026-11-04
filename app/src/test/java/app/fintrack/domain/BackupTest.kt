package app.fintrack.domain

import app.fintrack.domain.logic.BackupContent
import app.fintrack.domain.logic.BackupCrypto
import app.fintrack.domain.logic.BackupMeta
import app.fintrack.domain.logic.BackupPayload
import app.fintrack.domain.logic.PrefsCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {

    // Мало итераций — чтобы тесты шли быстро; формат от этого не меняется
    private val fast = 1_000
    private val data = "Секретные финансы".toByteArray()

    @Test
    fun roundTrip() {
        val file = BackupCrypto.encrypt(data, "пароль-123", fast)
        assertTrue(BackupCrypto.looksLikeBackup(file))
        assertArrayEquals(data, BackupCrypto.decrypt(file, "пароль-123"))
    }

    @Test
    fun wrongPasswordGivesNothing() {
        assertNull(BackupCrypto.decrypt(BackupCrypto.encrypt(data, "правильный", fast), "неправильный"))
    }

    @Test
    fun contentIsNotReadableInFile() {
        val file = String(BackupCrypto.encrypt(data, "пароль-123", fast), Charsets.ISO_8859_1)
        assertFalse(file.contains(String(data, Charsets.ISO_8859_1)))
    }

    @Test
    fun sameInputEncryptsDifferently() {
        assertFalse(BackupCrypto.encrypt(data, "пароль-123", fast).contentEquals(BackupCrypto.encrypt(data, "пароль-123", fast)))
    }

    @Test
    fun anyTamperingIsDetected() {
        val file = BackupCrypto.encrypt(data, "пароль-123", fast)
        // Шифротекст, IV, соль, итерации — порча любого места отвергается
        for (i in listOf(file.lastIndex, 40, 30, 12, 8)) {
            val bad = file.clone()
            bad[i] = (bad[i].toInt() xor 1).toByte()
            assertNull("байт $i", BackupCrypto.decrypt(bad, "пароль-123"))
        }
        assertNull(BackupCrypto.decrypt(file.copyOf(file.size - 1), "пароль-123"))
    }

    @Test
    fun foreignFilesAreRejected() {
        assertFalse(BackupCrypto.looksLikeBackup("обычный текстовый файл, совсем не копия приложения".toByteArray()))
        assertFalse(BackupCrypto.looksLikeBackup(ByteArray(3)))
        assertNull(BackupCrypto.decrypt("FTBK".toByteArray(), "пароль-123"))
    }

    @Test
    fun payloadRoundTrip() {
        val content = BackupContent(
            BackupMeta(1_780_000_000_000, "0.14.0", 9, 1234, 5),
            ByteArray(100_000) { (it % 7).toByte() },
            mapOf("base_currency" to "BYN", "hide_amounts" to true, "budget_info_percent" to 50, "rates_updated_at" to 1_780_000_000_000),
        )
        val back = BackupPayload.unpack(BackupPayload.pack(content))
        assertNotNull(back)
        assertEquals(content.meta, back!!.meta)
        assertArrayEquals(content.database, back.database)
        assertEquals(content.settings, back.settings)
    }

    @Test
    fun fullFileRoundTrip() {
        val content = BackupContent(BackupMeta(1, "0.14.0", 9, 0, 1), byteArrayOf(1, 2, 3), emptyMap())
        val file = BackupCrypto.encrypt(BackupPayload.pack(content), "пароль-123", fast)
        val back = BackupPayload.unpack(BackupCrypto.decrypt(file, "пароль-123")!!)!!
        assertArrayEquals(byteArrayOf(1, 2, 3), back.database)
        assertTrue(back.settings.isEmpty())
    }

    @Test
    fun brokenPayloadIsRejected() {
        assertNull(BackupPayload.unpack("не архив".toByteArray()))
        assertNull(BackupPayload.unpack(ByteArray(0)))
    }

    @Test
    fun prefsKeepTypesAndAnyText() {
        val values = mapOf<String, Any>(
            "text" to "строка с пробелами;=\n и переводом",
            "empty" to "",
            "flag" to false,
            "int" to -5,
            "long" to Long.MAX_VALUE,
            "имя ключа" to "значение",
        )
        assertEquals(values, PrefsCodec.decode(PrefsCodec.encode(values)))
        assertTrue(PrefsCodec.decode("мусор\nx y z\ni !!! 5").isEmpty())
    }
}
