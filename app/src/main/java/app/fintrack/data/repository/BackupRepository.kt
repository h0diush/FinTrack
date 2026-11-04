package app.fintrack.data.repository

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.fintrack.BuildConfig
import app.fintrack.data.db.FinTrackDatabase
import app.fintrack.domain.logic.BackupContent
import app.fintrack.domain.logic.BackupCrypto
import app.fintrack.domain.logic.BackupMeta
import app.fintrack.domain.logic.BackupPayload
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface BackupOpen {
    data class Ok(val content: BackupContent) : BackupOpen
    data class Failure(val message: String) : BackupOpen
}

/**
 * Резервные копии: вся база и настройки в файле, зашифрованном паролем. PIN и настройки блокировки
 * в копию не входят и при восстановлении не меняются — они относятся к этому телефону.
 */
@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: FinTrackDatabase,
    private val store: DataStore<Preferences>,
) {
    private val lastKey = longPreferencesKey("backup_last_at")

    val lastBackupAt: Flow<Long?> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[lastKey] }

    private val dbFile: File get() = context.getDatabasePath(db.openHelper.databaseName ?: FinTrackDatabase.NAME)

    /** Настройки этого телефона: блокировка и дата копии. При восстановлении остаются как есть. */
    private fun isLocal(name: String) = name.startsWith("pin_") || name.startsWith("lock_") || name.startsWith("backup_")

    /** Что показывали уведомления бюджета — состояние, а не настройка; с другой базой смысла не имеет. */
    private fun isTransient(name: String) = name.startsWith("budget_alert_")

    /** Создать копию и записать в файл. false — не получилось, ничего не записано. */
    suspend fun create(uri: Uri, password: String): Boolean = withContext(Dispatchers.Default) {
        val bytes = try {
            snapshot()
        } catch (e: IOException) {
            return@withContext false
        }
        val check = inspect(bytes) ?: return@withContext false
        val settings = store.data.first().asMap().entries
            .filter { !isLocal(it.key.name) && !isTransient(it.key.name) }
            .associate { it.key.name to it.value }
        val meta = BackupMeta(System.currentTimeMillis(), BuildConfig.VERSION_NAME, check.schema, check.operations, check.accounts)
        val file = BackupCrypto.encrypt(BackupPayload.pack(BackupContent(meta, bytes, settings)), password)
        val written = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(file) } != null
            } catch (e: IOException) {
                false
            } catch (e: SecurityException) {
                false
            }
        }
        if (written) store.edit { it[lastKey] = meta.createdAt }
        written
    }

    /** Содержимое базы одним файлом: журнал WAL сначала переносится в основной файл. */
    private suspend fun snapshot(): ByteArray = withContext(Dispatchers.IO) {
        val sql = db.openHelper.writableDatabase
        repeat(5) { attempt ->
            val busy = sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use { c -> c.moveToFirst() && c.getInt(0) != 0 }
            if (!busy) return@withContext dbFile.readBytes()
            delay(200L * (attempt + 1))
        }
        throw IOException("База занята")
    }

    private data class Inspection(val schema: Int, val operations: Int, val accounts: Int)

    /**
     * Открыть копию базы отдельно от рабочей и проверить: файл цел и это наши таблицы.
     * Временный файл живёт в личной папке приложения и сразу удаляется.
     */
    private suspend fun inspect(bytes: ByteArray): Inspection? = withContext(Dispatchers.IO) {
        val temp = File(context.cacheDir, "backup-check.db")
        try {
            temp.writeBytes(bytes)
            val sql = SQLiteDatabase.openDatabase(temp.path, null, SQLiteDatabase.OPEN_READWRITE)
            try {
                val ok = sql.rawQuery("PRAGMA integrity_check", null).use { c -> c.moveToFirst() && c.getString(0) == "ok" }
                if (!ok) return@withContext null
                fun count(table: String) = sql.rawQuery("SELECT COUNT(*) FROM $table", null).use { c -> c.moveToFirst(); c.getInt(0) }
                Inspection(sql.version, count("transactions"), count("accounts"))
            } finally {
                sql.close()
            }
        } catch (e: SQLiteException) {
            null
        } catch (e: IOException) {
            null
        } finally {
            SQLiteDatabase.deleteDatabase(temp)
        }
    }

    /** Прочитать файл; null — не наша копия или не читается. */
    suspend fun read(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val data = context.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_BYTES) return@withContext null
                }
                out.toByteArray()
            }
            data?.takeIf(BackupCrypto::looksLikeBackup)
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    /** Расшифровать и проверить копию. Рабочие данные на этом шаге не меняются. */
    suspend fun open(file: ByteArray, password: String): BackupOpen = withContext(Dispatchers.Default) {
        val plain = BackupCrypto.decrypt(file, password)
            ?: return@withContext BackupOpen.Failure("Неверный пароль или файл повреждён")
        val content = BackupPayload.unpack(plain) ?: return@withContext BackupOpen.Failure("Файл повреждён")
        val check = inspect(content.database) ?: return@withContext BackupOpen.Failure("База в копии повреждена")
        val current = withContext(Dispatchers.IO) { db.openHelper.readableDatabase.version }
        if (check.schema > current) {
            return@withContext BackupOpen.Failure("Копия сделана в более новой версии приложения (${content.meta.appVersion}). Сначала обновите приложение")
        }
        BackupOpen.Ok(content)
    }

    /**
     * Заменить данные содержимым копии и перезапустить приложение. Файл базы подменяется целиком
     * переименованием; прежняя база остаётся рядом и возвращается на место при сбое.
     * Старая копия обновится обычными миграциями при следующем открытии.
     * @param restartApp false только в тестах: перезапуск завершает процесс.
     * @return false — заменить не удалось, прежние данные на месте.
     */
    suspend fun restore(content: BackupContent, restartApp: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val target = dbFile
        val dir = target.parentFile ?: return@withContext false
        val incoming = File(dir, "${target.name}.restore")
        val before = File(dir, "${target.name}.before-restore")
        try {
            incoming.writeBytes(content.database)
        } catch (e: IOException) {
            incoming.delete()
            return@withContext false
        }
        // При закрытии WAL сливается в основной файл, так что прежняя база сохраняется целиком
        db.close()
        before.delete()
        var replaced = false
        if (target.renameTo(before)) {
            replaced = incoming.renameTo(target)
            if (!replaced) before.renameTo(target)
        }
        incoming.delete()
        if (replaced) {
            File(target.path + "-wal").delete()
            File(target.path + "-shm").delete()
            restoreSettings(content.settings)
        }
        // База закрыта в любом случае — работать дальше можно только после перезапуска
        if (restartApp) restart()
        replaced
    }

    private suspend fun restoreSettings(values: Map<String, Any>) {
        store.edit { p ->
            p.asMap().keys.filter { !isLocal(it.name) }.forEach { p.remove(it) }
            values.filterKeys { !isLocal(it) && !isTransient(it) }.forEach { (name, v) ->
                when (v) {
                    is Boolean -> p[booleanPreferencesKey(name)] = v
                    is Int -> p[intPreferencesKey(name)] = v
                    is Long -> p[longPreferencesKey(name)] = v
                    is String -> p[stringPreferencesKey(name)] = v
                }
            }
        }
    }

    private fun restart() {
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        Runtime.getRuntime().exit(0)
    }

    private companion object {
        const val MAX_BYTES = 256 * 1024 * 1024
    }
}
