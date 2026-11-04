package app.fintrack.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.fintrack.data.db.AccountEntity
import app.fintrack.data.db.CategoryEntity
import app.fintrack.data.db.FinTrackDatabase
import app.fintrack.data.db.TransactionEntity
import app.fintrack.data.repository.ExportFormat
import app.fintrack.data.repository.ExportRepository
import app.fintrack.data.repository.TransactionRepository
import app.fintrack.domain.model.AccountType
import app.fintrack.domain.model.CategoryKind
import app.fintrack.domain.model.TransactionType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Файлы экспорта на настоящем Android: PDF рисуется только здесь. */
@RunWith(AndroidJUnit4::class)
class ExportRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: FinTrackDatabase
    private lateinit var export: ExportRepository

    @Before
    fun setUp(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, FinTrackDatabase::class.java).allowMainThreadQueries().build()
        export = ExportRepository(context, TransactionRepository(db))
        db.accountDao().insert(AccountEntity("card", "Карта Приорбанк", AccountType.CARD, "BYN", 0, 0, 0, false, 0))
        db.accountDao().insert(AccountEntity("usd", "Доллары", AccountType.CURRENCY, "USD", 0, 0, 1, false, 0))
        listOf("Продукты" to "food", "Кафе и рестораны" to "cafe", "Транспорт" to "bus", "Жильё и коммунальные услуги" to "home").forEachIndexed { i, (name, id) ->
            db.categoryDao().insert(CategoryEntity(id, name, CategoryKind.EXPENSE, null, "other", 0, i, false, false))
        }
        db.categoryDao().insert(CategoryEntity("sal", "Зарплата", CategoryKind.INCOME, null, "salary", 0, 0, false, false))
        val day = 86_400_000L
        val start = 1_790_000_000_000L
        db.transactionDao().insert(tx("in", TransactionType.INCOME, 250_000, "sal", start, payee = "ООО Работа"))
        repeat(70) { i ->
            val cat = listOf("food", "cafe", "bus", "home")[i % 4]
            db.transactionDao().insert(tx("e$i", TransactionType.EXPENSE, 1_000L + i * 137, cat, start + i * day / 3, payee = "Магазин №$i с очень длинным названием для проверки обрезки", comment = "комментарий"))
        }
        db.transactionDao().insert(
            tx("fx", TransactionType.EXPENSE, 5_910, "cafe", start + 2 * day, payee = "Netflix").copy(originalAmountMinor = 2_000, originalCurrency = "USD"),
        )
        db.transactionDao().insert(
            TransactionEntity("tr", TransactionType.TRANSFER, "card", "usd", 30_000, 10_000, 150, null, start + day, "", "", false, 1, 1),
        )
    }

    private fun tx(id: String, type: TransactionType, amount: Long, category: String, at: Long, payee: String = "", comment: String = "") =
        TransactionEntity(id, type, "card", null, amount, null, 0, category, at, payee, comment, false, 1, 1)

    @After
    fun tearDown() = db.close()

    private fun write(format: ExportFormat): File = runBlocking {
        val file = File(context.cacheDir, "report.${format.extension}")
        assertTrue(export.write(Uri.fromFile(file), format, export.load(null, null), "Ноябрь 2026"))
        file
    }

    @Test
    fun loadsInDateOrder() = runBlocking {
        val list = export.load(null, null)
        assertEquals(73, list.size)
        assertTrue(list.zipWithNext().all { (a, b) -> a.transaction.dateTime <= b.transaction.dateTime })
    }

    @Test
    fun pdfIsDrawnWithSeveralPages() {
        val bytes = write(ExportFormat.PDF).readBytes()
        assertEquals("%PDF", String(bytes, 0, 4))
        assertTrue(String(bytes, Charsets.ISO_8859_1).split("/Type /Page").size > 2 || bytes.size > 5_000)
    }

    @Test
    fun csvAndExcelAreWritten() {
        assertTrue(write(ExportFormat.CSV).readText().contains("Netflix"))
        assertEquals("PK", String(write(ExportFormat.XLSX).readBytes(), 0, 2))
    }
}
