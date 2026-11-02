package app.fintrack.data.repository

import androidx.room.withTransaction
import app.fintrack.data.db.FinTrackDatabase
import app.fintrack.data.toDomain
import app.fintrack.data.toEntity
import app.fintrack.domain.model.Category
import app.fintrack.domain.model.CategoryKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class CategoryRemoval { DELETED, ARCHIVED, PROTECTED }

@Singleton
class CategoryRepository @Inject constructor(
    private val db: FinTrackDatabase,
) {
    private val dao = db.categoryDao()
    private val txDao = db.transactionDao()

    fun observeAll(): Flow<List<Category>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun get(id: String): Category? = dao.getById(id)?.toDomain()

    suspend fun hasChildren(id: String): Boolean = dao.childIds(id).isNotEmpty()

    suspend fun create(
        name: String,
        kind: CategoryKind,
        parentId: String?,
        iconKey: String,
        color: Long,
    ): String = db.withTransaction {
        val id = UUID.randomUUID().toString()
        dao.insert(
            Category(
                id = id,
                name = name.trim(),
                kind = kind,
                parentId = parentId,
                iconKey = iconKey,
                color = color,
                sortOrder = dao.maxSortOrder(kind.name, parentId) + 1,
                isArchived = false,
                isSystem = false,
            ).toEntity(),
        )
        id
    }

    suspend fun update(category: Category) = db.withTransaction {
        val current = dao.getById(category.id) ?: return@withTransaction
        // Тип (доход/расход) не меняется: операции уже привязаны к нему.
        val parentChanged = current.parentId != category.parentId
        dao.update(
            current.copy(
                name = category.name.trim(),
                iconKey = category.iconKey,
                color = category.color,
                parentId = category.parentId,
                sortOrder = if (parentChanged) {
                    dao.maxSortOrder(current.kind.name, category.parentId) + 1
                } else {
                    current.sortOrder
                },
            ),
        )
    }

    /**
     * Без операций — удаляет вместе с подкатегориями; если операции есть —
     * архивирует, чтобы история осталась корректной. «Прочее» защищено.
     */
    suspend fun removeOrArchive(id: String): CategoryRemoval = db.withTransaction {
        val entity = dao.getById(id) ?: return@withTransaction CategoryRemoval.DELETED
        if (entity.isSystem) return@withTransaction CategoryRemoval.PROTECTED
        val ids = listOf(id) + dao.childIds(id)
        if (txDao.countForCategories(ids) > 0) {
            dao.setArchived(ids, true)
            CategoryRemoval.ARCHIVED
        } else {
            dao.deleteByIds(ids)
            CategoryRemoval.DELETED
        }
    }

    /** Восстанавливает категорию (и её родителя, иначе она не видна). */
    suspend fun restore(id: String) = db.withTransaction {
        val entity = dao.getById(id) ?: return@withTransaction
        dao.setArchived(listOfNotNull(id, entity.parentId), false)
    }
}
