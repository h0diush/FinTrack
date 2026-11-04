package app.fintrack.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.fintrack.data.db.AccountEntity
import app.fintrack.data.db.FinTrackDatabase
import app.fintrack.data.db.Migrations
import app.fintrack.data.db.TransactionEntity
import app.fintrack.data.repository.BackupOpen
import app.fintrack.data.repository.BackupRepository
import app.fintrack.domain.logic.BackupContent
import app.fintrack.domain.logic.BackupCrypto
import app.fintrack.domain.logic.BackupMeta
import app.fintrack.domain.logic.BackupPayload
import app.fintrack.domain.model.AccountType
import app.fintrack.domain.model.TransactionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Копия и восстановление на настоящем файле базы: это единственное место, где данные заменяются целиком. */
@RunWith(AndroidJUnit4::class)
class BackupRepositoryTest {

    private val name = "backup-test.db"
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val copy = File(context.cacheDir, "copy.ftbackup")
    private val prefsFile = File(context.cacheDir, "backup-test.preferences_pb")
    private val currency = stringPreferencesKey("base_currency")
    private val pin = stringPreferencesKey("pin_hash")

    private lateinit var db: FinTrackDatabase
    private lateinit var store: DataStore<Preferences>
    private lateinit var backups: BackupRepository

    private fun openDb() = Room.databaseBuilder(context, FinTrackDatabase::class.java, name)
        .addMigrations(*Migrations.ALL)
        .allowMainThreadQueries()
        .build()

    private fun tx(id: String, amount: Long) = TransactionEntity(
        id, TransactionType.INCOME, "card", null, amount, null, 0, null, 1_000, "", "", false, 1, 1,
    )

    @Before
    fun setUp(): Unit = runBlocking {
        context.deleteDatabase(name)
        File(context.getDatabasePath(name).path + ".before-restore").delete()
        copy.delete()
        prefsFile.delete()
        db = openDb()
        store = PreferenceDataStoreFactory.create(scope = scope) { prefsFile }
        backups = BackupRepository(context, db, store)
        db.accountDao().insert(AccountEntity("card", "Карта", AccountType.CARD, "BYN", 10_000, 0, 0, false, 0))
        db.transactionDao().insert(tx("first", 5_000))
        store.edit {
            it[currency] = "BYN"
            it[pin] = "старый"
        }
    }

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
        context.deleteDatabase(name)
        copy.delete()
        prefsFile.delete()
    }

    @Test
    fun restoreBringsBackDataAndSettingsButKeepsPin(): Unit = runBlocking {
        val uri = Uri.fromFile(copy)
        assertTrue(backups.create(uri, "пароль-123"))
        assertNotNull(backups.lastBackupAt.first())

        // После копии жизнь продолжилась
        db.transactionDao().insert(tx("second", 7_000))
        store.edit {
            it[currency] = "USD"
            it[pin] = "новый"
        }

        val file = backups.read(uri)
        assertNotNull(file)
        assertTrue(backups.open(file!!, "неверный-пароль") is BackupOpen.Failure)
        val opened = backups.open(file, "пароль-123") as BackupOpen.Ok
        assertEquals(1, opened.content.meta.operations)
        assertEquals(1, opened.content.meta.accounts)
        assertEquals("BYN", opened.content.settings["base_currency"])
        assertFalse("PIN не попадает в копию", opened.content.settings.containsKey("pin_hash"))

        assertTrue(backups.restore(opened.content, restartApp = false))
        db = openDb()
        assertNotNull(db.transactionDao().getById("first"))
        assertNull(db.transactionDao().getById("second"))
        assertEquals(15_000L, db.accountDao().balanceOf("card"))
        val prefs = store.data.first()
        assertEquals("BYN", prefs[currency])
        assertEquals("новый", prefs[pin])
    }

    @Test
    fun copyIncludesRecentWrites(): Unit = runBlocking {
        // Запись из журнала WAL не должна потеряться в снимке
        repeat(50) { db.transactionDao().insert(tx("t$it", 100)) }
        val uri = Uri.fromFile(copy)
        assertTrue(backups.create(uri, "пароль-123"))
        val opened = backups.open(backups.read(uri)!!, "пароль-123") as BackupOpen.Ok
        assertEquals(51, opened.content.meta.operations)
    }

    @Test
    fun copyFromNewerAppIsRejectedAndDataIntact(): Unit = runBlocking {
        val temp = File(context.cacheDir, "future.db")
        SQLiteDatabase.deleteDatabase(temp)
        SQLiteDatabase.openOrCreateDatabase(temp, null).use {
            it.execSQL("CREATE TABLE accounts (id TEXT)")
            it.execSQL("CREATE TABLE transactions (id TEXT)")
            it.version = 9999
        }
        val content = BackupContent(BackupMeta(1, "99.0", 9999, 0, 0), temp.readBytes(), emptyMap())
        SQLiteDatabase.deleteDatabase(temp)
        val file = BackupCrypto.encrypt(BackupPayload.pack(content), "пароль-123", 1_000)
        val r = backups.open(file, "пароль-123")
        assertTrue(r is BackupOpen.Failure && r.message.contains("новой версии"))
        assertNotNull(db.transactionDao().getById("first"))
    }

    @Test
    fun corruptedDatabaseInCopyIsRejected(): Unit = runBlocking {
        val content = BackupContent(BackupMeta(1, "0.14.0", 9, 0, 0), ByteArray(4096) { 7 }, emptyMap())
        val file = BackupCrypto.encrypt(BackupPayload.pack(content), "пароль-123", 1_000)
        assertTrue(backups.open(file, "пароль-123") is BackupOpen.Failure)
    }

    @Test
    fun foreignFileIsNotABackup(): Unit = runBlocking {
        copy.writeText("обычный файл, не резервная копия приложения FinTrack")
        assertNull(backups.read(Uri.fromFile(copy)))
    }
}
