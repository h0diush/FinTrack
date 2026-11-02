package app.fintrack.ui.categories

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fintrack.data.repository.CategoryRemoval
import app.fintrack.data.repository.CategoryRepository
import app.fintrack.domain.model.Category
import app.fintrack.domain.model.CategoryKind
import app.fintrack.ui.components.PaletteColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ---------- Список категорий ----------

data class CategoryNode(val category: Category, val children: List<Category>)

data class CategoriesUiState(
    val kind: CategoryKind = CategoryKind.EXPENSE,
    val active: List<CategoryNode> = emptyList(),
    val archived: List<Category> = emptyList(),
)

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val repo: CategoryRepository,
) : ViewModel() {

    private val kind = MutableStateFlow(CategoryKind.EXPENSE)
    private val messages = Channel<String>(Channel.BUFFERED)
    val messageFlow = messages.receiveAsFlow()

    val state: StateFlow<CategoriesUiState> = combine(kind, repo.observeAll()) { k, all ->
        val ofKind = all.filter { it.kind == k }
        CategoriesUiState(
            kind = k,
            active = ofKind.filter { it.parentId == null && !it.isArchived }.map { root ->
                CategoryNode(root, ofKind.filter { it.parentId == root.id && !it.isArchived })
            },
            archived = ofKind.filter { it.isArchived },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoriesUiState())

    fun setKind(k: CategoryKind) = kind.update { k }

    fun restore(id: String) {
        viewModelScope.launch {
            repo.restore(id)
            messages.send("Категория восстановлена")
        }
    }
}

// ---------- Создание и редактирование категории ----------

data class CategoryEditUiState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val id: String? = null,
    val name: String = "",
    val kind: CategoryKind = CategoryKind.EXPENSE,
    val parentId: String? = null,
    val iconKey: String = "other",
    val color: Long = PaletteColors.first(),
    val isSystem: Boolean = false,
    val hasChildren: Boolean = false,
    val all: List<Category> = emptyList(),
    val nameError: Boolean = false,
    val saving: Boolean = false,
) {
    /** Возможные родители: корневые активные категории того же типа, кроме самой себя. */
    val parents: List<Category>
        get() = all.filter { it.kind == kind && it.parentId == null && !it.isArchived && it.id != id }
}

sealed interface CategoryEditEvent {
    data object Closed : CategoryEditEvent
    data class Message(val text: String) : CategoryEditEvent
}

@HiltViewModel
class CategoryEditViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: CategoryRepository,
) : ViewModel() {

    private val argId: String? = savedState["id"]
    private val argKind: String? = savedState["kind"]
    private val argParent: String? = savedState["parentId"]

    private val _state = MutableStateFlow(CategoryEditUiState())
    val state: StateFlow<CategoryEditUiState> = _state.asStateFlow()
    private val events = Channel<CategoryEditEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    init {
        viewModelScope.launch {
            val existing = argId?.let { repo.get(it) }
            if (existing != null) {
                _state.update {
                    it.copy(
                        loading = false, isNew = false, id = existing.id, name = existing.name, kind = existing.kind,
                        parentId = existing.parentId, iconKey = existing.iconKey, color = existing.color,
                        isSystem = existing.isSystem, hasChildren = repo.hasChildren(existing.id),
                    )
                }
            } else {
                val kind = argKind?.let { runCatching { CategoryKind.valueOf(it) }.getOrNull() } ?: CategoryKind.EXPENSE
                val parent = argParent?.let { repo.get(it) }
                _state.update {
                    it.copy(
                        loading = false,
                        kind = parent?.kind ?: kind,
                        parentId = parent?.id,
                        iconKey = parent?.iconKey ?: "other",
                        color = parent?.color ?: PaletteColors.first(),
                    )
                }
            }
        }
        viewModelScope.launch {
            repo.observeAll().collect { all ->
                _state.update { it.copy(all = all) }
            }
        }
    }

    fun setName(v: String) = _state.update { it.copy(name = v.take(40), nameError = false) }
    fun setIcon(v: String) = _state.update { it.copy(iconKey = v) }
    fun setColor(v: Long) = _state.update { it.copy(color = v) }
    fun setParent(id: String?) = _state.update { if (it.hasChildren || it.isSystem) it else it.copy(parentId = id) }

    fun save() {
        val s = _state.value
        if (s.saving) return
        if (s.name.isBlank()) {
            _state.update { it.copy(nameError = true) }
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            if (s.isNew) {
                repo.create(s.name, s.kind, s.parentId, s.iconKey, s.color)
            } else {
                val current = repo.get(s.id!!) ?: return@launch
                repo.update(current.copy(name = s.name, iconKey = s.iconKey, color = s.color, parentId = s.parentId))
            }
            events.send(CategoryEditEvent.Closed)
        }
    }

    fun remove() {
        val id = _state.value.id ?: return
        viewModelScope.launch {
            val msg = when (repo.removeOrArchive(id)) {
                CategoryRemoval.DELETED -> "Категория удалена"
                CategoryRemoval.ARCHIVED -> "Есть операции с этой категорией — она перемещена в архив, история сохранена"
                CategoryRemoval.PROTECTED -> "Категорию «Прочее» удалить нельзя"
            }
            events.send(CategoryEditEvent.Message(msg))
            events.send(CategoryEditEvent.Closed)
        }
    }
}
