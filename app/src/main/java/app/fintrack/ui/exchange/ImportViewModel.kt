package app.fintrack.ui.exchange

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fintrack.data.repository.AccountRepository
import app.fintrack.data.repository.CategoryRepository
import app.fintrack.data.repository.ImportFile
import app.fintrack.data.repository.ImportItem
import app.fintrack.data.repository.ImportRepository
import app.fintrack.data.repository.ImportStatus
import app.fintrack.data.repository.SecurityRepository
import app.fintrack.domain.logic.CategoryGuesser
import app.fintrack.domain.logic.ColumnMap
import app.fintrack.domain.logic.CsvReader
import app.fintrack.domain.logic.ParsedStatement
import app.fintrack.domain.logic.SignRule
import app.fintrack.domain.logic.StatementParser
import app.fintrack.domain.model.AccountType
import app.fintrack.domain.model.AccountWithBalance
import app.fintrack.domain.model.Category
import app.fintrack.domain.model.CategoryKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class ImportStep { PICK, MAP, REVIEW, DONE }

/** Поля, которым пользователь сопоставляет колонки файла. */
enum class ImportField(val title: String) {
    DATE("Дата"),
    AMOUNT("Сумма"),
    CREDIT("Приход"),
    DEBIT("Расход"),
    DESCRIPTION("Получатель или описание"),
    CATEGORY("Категория"),
    COMMENT("Комментарий"),
    CURRENCY("Валюта"),
}

data class ImportUiState(
    val step: ImportStep = ImportStep.PICK,
    val busy: Boolean = false,
    val error: String? = null,
    val fileName: String = "",
    val rows: List<List<String>> = emptyList(),
    val headerIndex: Int = 0,
    val map: ColumnMap = ColumnMap(),
    /** Приход и расход в разных колонках. */
    val splitAmount: Boolean = false,
    val sign: SignRule = SignRule.SIGNED,
    val accounts: List<AccountWithBalance> = emptyList(),
    val accountId: String? = null,
    val parsed: ParsedStatement = ParsedStatement(emptyList(), emptyList(), 0),
    val items: List<ImportItem> = emptyList(),
    /** Загружать ли операции, похожие на внесённые вручную. */
    val includeSimilar: Boolean = false,
    val categories: List<Category> = emptyList(),
    val importedCount: Int = 0,
) {
    val headers: List<String> get() = rows.getOrNull(headerIndex).orEmpty()
    val account: AccountWithBalance? get() = accounts.firstOrNull { it.account.id == accountId }

    /** Пример значения колонки — первая непустая ячейка под заголовком. */
    fun sample(column: Int): String =
        rows.drop(headerIndex + 1).take(20).firstNotNullOfOrNull { r -> r.getOrNull(column)?.takeIf { it.isNotBlank() } }.orEmpty()

    fun column(field: ImportField): Int? = when (field) {
        ImportField.DATE -> map.date
        ImportField.AMOUNT -> map.amount
        ImportField.CREDIT -> map.credit
        ImportField.DEBIT -> map.debit
        ImportField.DESCRIPTION -> map.description
        ImportField.CATEGORY -> map.category
        ImportField.COMMENT -> map.comment
        ImportField.CURRENCY -> map.currency
    }

    val toImport: List<ImportItem>
        get() = items.filter { it.status == ImportStatus.NEW || (includeSimilar && it.status == ImportStatus.SIMILAR) }

    fun count(status: ImportStatus) = items.count { it.status == status }
    val canContinue: Boolean get() = map.isValid && accountId != null && parsed.rows.isNotEmpty()
}

@HiltViewModel
class ImportViewModel @Inject constructor(
    private val import: ImportRepository,
    private val accounts: AccountRepository,
    private val categories: CategoryRepository,
    private val security: SecurityRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val list = accounts.observeAccounts().first().filter {
                !it.account.isArchived && it.account.type != AccountType.LOAN && it.account.type != AccountType.PIGGY
            }
            _state.update { it.copy(accounts = list, accountId = list.firstOrNull()?.account?.id) }
        }
    }

    /** Системное окно выбора файла уводит приложение в фон — PIN при возврате не нужен. */
    fun beforePicker() = security.allowExternalScreen()

    fun open(uri: Uri) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val file = import.read(uri)) {
                is ImportFile.Failure -> _state.update { it.copy(busy = false, error = file.message) }
                is ImportFile.Text -> {
                    val rows = withContext(Dispatchers.Default) {
                        CsvReader.parse(file.content, CsvReader.detectDelimiter(file.content)).take(MAX_ROWS)
                    }
                    if (rows.size < 2) {
                        _state.update { it.copy(busy = false, error = "В файле не нашлось таблицы. Нужен CSV: строка заголовков и строки операций") }
                        return@launch
                    }
                    val header = StatementParser.headerRow(rows)
                    val map = StatementParser.guess(rows[header])
                    _state.update {
                        reparse(
                            it.copy(
                                busy = false,
                                step = ImportStep.MAP,
                                fileName = file.name,
                                rows = rows,
                                headerIndex = header,
                                map = map,
                                splitAmount = map.amount == null && (map.credit != null || map.debit != null),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun reparse(s: ImportUiState): ImportUiState =
        s.copy(parsed = if (s.map.isValid) StatementParser.parse(s.rows, s.headerIndex, s.map, s.sign) else ParsedStatement(emptyList(), emptyList(), 0))

    fun setColumn(field: ImportField, column: Int?) = _state.update {
        val m = it.map
        reparse(
            it.copy(
                map = when (field) {
                    ImportField.DATE -> m.copy(date = column)
                    ImportField.AMOUNT -> m.copy(amount = column)
                    ImportField.CREDIT -> m.copy(credit = column)
                    ImportField.DEBIT -> m.copy(debit = column)
                    ImportField.DESCRIPTION -> m.copy(description = column)
                    ImportField.CATEGORY -> m.copy(category = column)
                    ImportField.COMMENT -> m.copy(comment = column)
                    ImportField.CURRENCY -> m.copy(currency = column)
                },
            ),
        )
    }

    fun setSplitAmount(split: Boolean) = _state.update {
        val m = it.map
        reparse(it.copy(splitAmount = split, map = if (split) m.copy(amount = null) else m.copy(credit = null, debit = null)))
    }

    fun setSign(rule: SignRule) = _state.update { reparse(it.copy(sign = rule)) }
    fun setAccount(id: String) = _state.update { it.copy(accountId = id) }

    /** Сдвинуть строку заголовков, если она угадана неверно; колонки угадываются заново. */
    fun moveHeader(delta: Int) = _state.update {
        val index = (it.headerIndex + delta).coerceIn(0, (it.rows.size - 2).coerceAtLeast(0))
        val map = StatementParser.guess(it.rows[index])
        reparse(it.copy(headerIndex = index, map = map, splitAmount = map.amount == null && (map.credit != null || map.debit != null)))
    }

    fun review() {
        val s = _state.value
        val accountId = s.accountId ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val items = import.prepare(accountId, s.parsed.rows)
            val cats = categories.observeAll().first().filter { !it.isArchived }
            _state.update { it.copy(busy = false, step = ImportStep.REVIEW, items = items, categories = cats) }
        }
    }

    fun back() = _state.update {
        when (it.step) {
            ImportStep.REVIEW -> it.copy(step = ImportStep.MAP)
            else -> it.copy(step = ImportStep.PICK, error = null)
        }
    }

    fun setIncludeSimilar(v: Boolean) = _state.update { it.copy(includeSimilar = v) }

    /** Сменить категорию строке и всем строкам того же получателя — обычно это одно и то же. */
    fun setCategory(item: ImportItem, category: Category) = _state.update { s ->
        val key = CategoryGuesser.normalize(item.row.description)
        s.copy(
            items = s.items.map { i ->
                val same = i.id == item.id ||
                    (key.isNotEmpty() && i.row.isIncome == item.row.isIncome && CategoryGuesser.normalize(i.row.description) == key)
                if (same) i.copy(category = category) else i
            },
        )
    }

    fun categoriesFor(isIncome: Boolean): List<Category> {
        val kind = if (isIncome) CategoryKind.INCOME else CategoryKind.EXPENSE
        return _state.value.categories.filter { it.kind == kind }
    }

    fun commit() {
        val s = _state.value
        val accountId = s.accountId ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val n = import.commit(accountId, s.toImport)
            _state.update { it.copy(busy = false, step = ImportStep.DONE, importedCount = n) }
        }
    }

    private companion object {
        const val MAX_ROWS = 50_000
    }
}
