package app.fintrack.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import app.fintrack.domain.model.AccountType
import app.fintrack.domain.model.CategoryKind
import java.util.UUID

/**
 * Начальные данные: категории из ТЗ и счёт «Наличные».
 * Вызывается в Room onCreate — в той же транзакции, что и создание таблиц.
 */
object DefaultData {

    private val expense = listOf(
        Triple("Продукты", "groceries", 0xFF22A06BL),
        Triple("Кафе и рестораны", "restaurant", 0xFFF08A24L),
        Triple("Транспорт", "bus", 0xFF3B82F6L),
        Triple("Автомобиль", "car", 0xFF475569L),
        Triple("Жильё и коммунальные услуги", "home", 0xFF8B5CF6L),
        Triple("Покупки", "shopping", 0xFFEC4899L),
        Triple("Одежда и обувь", "clothes", 0xFFD946EFL),
        Triple("Здоровье", "health", 0xFFEF4444L),
        Triple("Развлечения", "games", 0xFFF59E0BL),
        Triple("Подписки", "subscriptions", 0xFF6366F1L),
        Triple("Связь и интернет", "wifi", 0xFF0EA5E9L),
        Triple("Образование", "school", 0xFF14B8A6L),
        Triple("Дети и семья", "family", 0xFFF97316L),
        Triple("Путешествия", "flight", 0xFF06B6D4L),
        Triple("Подарки", "gift", 0xFFE11D48L),
        Triple("Кредиты", "credit", 0xFF64748BL),
        Triple("Прочее", "other", 0xFF94A3B8L),
    )

    private val income = listOf(
        Triple("Зарплата", "salary", 0xFF16A34AL),
        Triple("Аванс", "payments", 0xFF22C55EL),
        Triple("Премия", "star", 0xFFEAB308L),
        Triple("Подработка", "work", 0xFF0EA5E9L),
        Triple("Бизнес", "business", 0xFF6366F1L),
        Triple("Продажа имущества", "sell", 0xFFF97316L),
        Triple("Возврат средств", "refund", 0xFF14B8A6L),
        Triple("Проценты и инвестиции", "invest", 0xFF8B5CF6L),
        Triple("Подарки", "gift", 0xFFE11D48L),
        Triple("Прочее", "other", 0xFF94A3B8L),
    )

    fun seed(db: SupportSQLiteDatabase, baseCurrency: String = "BYN") {
        insertCategories(db, CategoryKind.EXPENSE, expense)
        insertCategories(db, CategoryKind.INCOME, income)

        val account = ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("name", "Наличные")
            put("type", AccountType.CASH.name)
            put("currencyCode", baseCurrency)
            put("initialBalanceMinor", 0L)
            put("color", 0xFF0F766EL)
            put("sortOrder", 0)
            put("isArchived", 0)
            put("createdAt", System.currentTimeMillis())
        }
        db.insert("accounts", SQLiteDatabase.CONFLICT_ABORT, account)
    }

    private fun insertCategories(
        db: SupportSQLiteDatabase,
        kind: CategoryKind,
        items: List<Triple<String, String, Long>>,
    ) {
        items.forEachIndexed { index, (name, icon, color) ->
            val values = ContentValues().apply {
                put("id", UUID.randomUUID().toString())
                put("name", name)
                put("kind", kind.name)
                putNull("parentId")
                put("iconKey", icon)
                put("color", color)
                put("sortOrder", index)
                put("isArchived", 0)
                put("isSystem", if (name == "Прочее") 1 else 0)
            }
            db.insert("categories", SQLiteDatabase.CONFLICT_ABORT, values)
        }
    }
}
