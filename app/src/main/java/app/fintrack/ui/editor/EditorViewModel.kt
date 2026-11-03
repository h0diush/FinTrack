package app.fintrack.ui.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fintrack.data.repository.AccountRepository
import app.fintrack.data.repository.CategoryRepository
import app.fintrack.data.repository.RatesRepository
import app.fintrack.domain.logic.CurrencyConverter
import app.fintrack.data.repository.RecurringRepository
import app.fintrack.data.repository.SettingsRepository
import app.fintrack.data.repository.TransactionRepository
import app.fintrack.domain.logic.ExpressionEvaluator
import app.fintrack.domain.logic.KeypadInput
import app.fintrack.domain.logic.KeypadKey
import app.fintrack.domain.logic.Money
import app.fintrack.domain.logic.RecurrenceCalculator
import app.fintrack.domain.logic.RecurringMode
import app.fintrack.domain.logic.RepeatUnit
import app.fintrack.domain.logic.TransactionDraft
import app.fintrack.domain.logic.TransactionValidator
import app.fintrack.domain.logic.ValidationError
import app.fintrack.domain.logic.ValidationResult
import app.fintrack.domain.model.AccountWithBalance
import app.fintrack.domain.model.Category
import app.fintrack.domain.model.CategoryKind
import app.fintrack.domain.model.TransactionType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

/** TEMPLATE — изменение регулярного платежа (шаблона), а не операции. */
enum class EditorMode { NEW, EDIT, COPY, TEMPLATE }

data class EditorUiState(
    val loading: Boolean = true,
    val mode: EditorMode = EditorMode.NEW,
    val editingId: String? = null,
    val type: TransactionType = TransactionType.EXPENSE,
    val expression: String = "",
    val accountId: String? = null,
    val toAccountId: String? = null,
    val toAmountText: String = "",
    val feeText: String = "",
    val categoryId: String? = null,
    val date: LocalDate = LocalDate.now(),
    val time: LocalTime = LocalTime.now().withSecond(0).withNano(0),
    val payee: String = "",
    val comment: String = "",
    val isFavorite: Boolean = false,
    /** Повтор: null — разовая операция. У шаблона задан всегда. */
    val repeat: RepeatDraft? = null,
    /** id регулярного платежа в режиме TEMPLATE. */
    val templateId: String? = null,
    /** Валюта покупки, если не валюта счёта (20 USD с карты в BYN); null — валюта счёта. */
    val foreignCurrency: String? = null,
    /** Сколько списано со счёта — если пусто, по курсу. */
    val debitText: String = "",
    val converter: CurrencyConverter = CurrencyConverter(emptyMap()),
    /** Сумма корректировки (со знаком), только для просмотра. */
    val adjustmentMinor: Long = 0,
    val errors: Set<ValidationError> = emptySet(),
    val saving: Boolean = false,
    val accounts: List<AccountWithBalance> = emptyList(),
    val categories: List<Category> = emptyList(),
    val baseCurrency: String = "BYN",
    val hideAmounts: Boolean = false,
) {
    val account: AccountWithBalance? get() = accounts.firstOrNull { it.account.id == accountId }
    val toAccount: AccountWithBalance? get() = accounts.firstOrNull { it.account.id == toAccountId }
    val currency: String get() = account?.account?.currencyCode ?: baseCurrency
    val toCurrency: String? get() = toAccount?.account?.currencyCode
    val needsToAmount: Boolean get() = type == TransactionType.TRANSFER && toCurrency != null && toCurrency != currency

    /** Можно указать сумму в другой валюте: доход или расход (в том числе регулярный). */
    val canForeign: Boolean
        get() = type == TransactionType.EXPENSE || type == TransactionType.INCOME

    /** Регулярный платёж: со счёта каждый раз спишется сумма по курсу на день платежа. */
    val isRecurringSave: Boolean get() = repeat != null && canRepeat

    /** Поле «Списано со счёта» нужно, только если операция проводится сейчас (разовая или первый повтор сегодня). */
    val showsDebit: Boolean
        get() = isForeign && mode != EditorMode.TEMPLATE && (repeat == null || !date.isAfter(LocalDate.now()))
    val isForeign: Boolean get() = canForeign && foreignCurrency != null && foreignCurrency != currency

    /** Валюта, в которой вводится сумма на клавиатуре. */
    val entryCurrency: String get() = if (isForeign) foreignCurrency!! else currency

    /** Сумма в валюте покупки (по выражению). */
    val foreignMinor: Long?
        get() = if (!isForeign) null else ExpressionEvaluator.evaluate(expression)?.takeIf { it.signum() > 0 }?.let { Money.toMinor(it, entryCurrency) }

    /** Сколько спишется по курсу. */
    val convertedMinor: Long? get() = foreignMinor?.let { converter.convert(it, entryCurrency, currency) }
    val preview: BigDecimal? get() = ExpressionEvaluator.preview(expression)
    val hasOperators: Boolean get() = ExpressionEvaluator.hasOperators(expression)

    val kind: CategoryKind? get() = when (type) {
        TransactionType.EXPENSE -> CategoryKind.EXPENSE
        TransactionType.INCOME -> CategoryKind.INCOME
        else -> null
    }
    val selectedCategory: Category? get() = categories.firstOrNull { it.id == categoryId }
    val selectedParentId: String? get() = selectedCategory?.let { it.parentId ?: it.id }

    /** Активные корневые категории текущего типа (+ выбранная, даже если в архиве). */
    val rootCategories: List<Category>
        get() = categories.filter {
            it.kind == kind && it.parentId == null && (!it.isArchived || it.id == selectedParentId)
        }

    fun childrenOf(parentId: String): List<Category> =
        categories.filter { it.parentId == parentId && (!it.isArchived || it.id == categoryId) }

    /** Активные счета + счета этой операции, даже если они уже в архиве. */
    val selectableAccounts: List<AccountWithBalance>
        get() = accounts.filter { !it.account.isArchived || it.account.id == accountId || it.account.id == toAccountId }
}

sealed interface EditorEvent {
    data object Closed : EditorEvent
    data class Message(val text: String) : EditorEvent
    data class OpenCopy(val id: String) : EditorEvent
}

/** Сделать регулярной можно новую операцию или копию; шаблон регулярен всегда. Не корректировку. */
val EditorUiState.canRepeat: Boolean
    get() = type != TransactionType.ADJUSTMENT && (mode == EditorMode.NEW || mode == EditorMode.COPY || mode == EditorMode.TEMPLATE)

@HiltViewModel
class EditorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val transactions: TransactionRepository,
    private val accountsRepo: AccountRepository,
    private val categoriesRepo: CategoryRepository,
    private val settings: SettingsRepository,
    private val recurring: RecurringRepository,
    rates: RatesRepository,
) : ViewModel() {

    private val argType: String? = savedState["type"]
    private val argRecurringId: String? = savedState["recurringId"]
    private val argRepeat: Boolean = savedState.get<String>("repeat") == "1"
    private val argId: String? = savedState["id"]
    private val argCopyOf: String? = savedState["copyOf"]

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val events = Channel<EditorEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    init {
        viewModelScope.launch { initialize() }
        viewModelScope.launch {
            accountsRepo.observeAccounts().collect { list -> _state.update { it.copy(accounts = list) } }
        }
        viewModelScope.launch {
            categoriesRepo.observeAll().collect { list -> _state.update { it.copy(categories = list) } }
        }
        viewModelScope.launch {
            rates.state.collect { r -> _state.update { it.copy(converter = r.converter) } }
        }
    }

    private suspend fun initialize() {
        val s = settings.settings.first()
        val accounts = accountsRepo.observeAccounts().first()
        if (argRecurringId != null) {
            val r = recurring.get(argRecurringId)
            if (r == null) {
                events.send(EditorEvent.Closed)
                return
            }
            val accCurrency = accounts.firstOrNull { it.account.id == r.accountId }?.account?.currencyCode ?: s.baseCurrency
            val toCurrency = accounts.firstOrNull { it.account.id == r.toAccountId }?.account?.currencyCode
            val rule = r.rule
            _state.update {
                it.copy(
                    loading = false,
                    mode = EditorMode.TEMPLATE,
                    templateId = r.id,
                    type = r.type,
                    expression = Money.toInput(r.amountMinor, r.foreignCurrency ?: accCurrency),
                    foreignCurrency = r.foreignCurrency,
                    accountId = r.accountId,
                    toAccountId = r.toAccountId,
                    toAmountText = if (toCurrency != null && toCurrency != accCurrency && r.toAmountMinor != null) {
                        Money.toInput(r.toAmountMinor, toCurrency)
                    } else {
                        ""
                    },
                    feeText = if (r.feeMinor > 0) Money.toInput(r.feeMinor, accCurrency) else "",
                    categoryId = r.categoryId,
                    // Дата в форме — следующий платёж; после сохранения правило считается от неё.
                    date = r.nextDate ?: LocalDate.now(),
                    time = LocalTime.of(r.minuteOfDay / 60, r.minuteOfDay % 60),
                    payee = r.payee,
                    comment = r.comment,
                    repeat = RepeatDraft(
                        unit = rule.unit,
                        interval = rule.interval,
                        lastDayOfMonth = rule.lastDayOfMonth,
                        end = when {
                            rule.maxCount != null -> RepeatEnd.COUNT
                            rule.endDate != null -> RepeatEnd.DATE
                            else -> RepeatEnd.NEVER
                        },
                        endDate = rule.endDate,
                        count = ((rule.maxCount ?: 12) - r.processedCount).coerceAtLeast(1),
                        mode = r.mode,
                    ),
                    baseCurrency = s.baseCurrency,
                    hideAmounts = s.hideAmounts,
                )
            }
            return
        }
        val active = accounts.filter { !it.account.isArchived }
        val sourceId = argId ?: argCopyOf
        val source = sourceId?.let { transactions.get(it) }

        if (source == null) {
            val type = argType?.let { runCatching { TransactionType.valueOf(it) }.getOrNull() }
                ?.takeIf { it != TransactionType.ADJUSTMENT } ?: TransactionType.EXPENSE
            val accountId = s.lastAccountId?.takeIf { id -> active.any { it.account.id == id } }
                ?: active.firstOrNull()?.account?.id
            _state.update {
                it.copy(
                    loading = false,
                    mode = EditorMode.NEW,
                    type = type,
                    accountId = accountId,
                    toAccountId = if (type == TransactionType.TRANSFER) defaultToAccount(active, accountId) else null,
                    categoryId = defaultCategory(type, s.lastExpenseCategoryId, s.lastIncomeCategoryId),
                    repeat = if (argRepeat) RepeatDraft(unit = RepeatUnit.MONTH, mode = RecurringMode.CONFIRM) else null,
                    baseCurrency = s.baseCurrency,
                    hideAmounts = s.hideAmounts,
                )
            }
            return
        }

        val accCurrency = accounts.firstOrNull { it.account.id == source.accountId }?.account?.currencyCode ?: s.baseCurrency
        val toCurrency = accounts.firstOrNull { it.account.id == source.toAccountId }?.account?.currencyCode
        val isCopy = argId == null
        val dt = if (isCopy) {
            LocalDateTime.now()
        } else {
            Instant.ofEpochMilli(source.dateTime).atZone(ZoneId.systemDefault()).toLocalDateTime()
        }
        _state.update {
            it.copy(
                loading = false,
                mode = if (isCopy) EditorMode.COPY else EditorMode.EDIT,
                editingId = if (isCopy) null else source.id,
                type = source.type,
                expression = when {
                    source.type == TransactionType.ADJUSTMENT -> ""
                    source.originalAmountMinor != null && source.originalCurrency != null ->
                        Money.toInput(source.originalAmountMinor, source.originalCurrency)
                    else -> Money.toInput(source.amountMinor, accCurrency)
                },
                foreignCurrency = source.originalCurrency?.takeIf { source.originalAmountMinor != null },
                debitText = if (source.originalCurrency != null && source.originalAmountMinor != null) Money.toInput(source.amountMinor, accCurrency) else "",
                accountId = source.accountId,
                toAccountId = source.toAccountId,
                toAmountText = if (toCurrency != null && toCurrency != accCurrency && source.toAmountMinor != null) {
                    Money.toInput(source.toAmountMinor, toCurrency)
                } else {
                    ""
                },
                feeText = if (source.feeMinor > 0) Money.toInput(source.feeMinor, accCurrency) else "",
                categoryId = source.categoryId,
                date = dt.toLocalDate(),
                time = dt.toLocalTime().withSecond(0).withNano(0),
                payee = source.payee,
                comment = source.comment,
                isFavorite = !isCopy && source.isFavorite,
                adjustmentMinor = if (source.type == TransactionType.ADJUSTMENT) source.amountMinor else 0,
                baseCurrency = s.baseCurrency,
                hideAmounts = s.hideAmounts,
            )
        }
    }

    private fun defaultToAccount(active: List<AccountWithBalance>, fromId: String?): String? =
        active.firstOrNull { it.account.id != fromId }?.account?.id

    private suspend fun defaultCategory(type: TransactionType, lastExpense: String?, lastIncome: String?): String? {
        val id = when (type) {
            TransactionType.EXPENSE -> lastExpense
            TransactionType.INCOME -> lastIncome
            else -> null
        } ?: return null
        val c = categoriesRepo.get(id) ?: return null
        return if (c.isArchived) null else c.id
    }

    fun setType(type: TransactionType) {
        val s = _state.value
        if (s.type == type || s.type == TransactionType.ADJUSTMENT || type == TransactionType.ADJUSTMENT) return
        viewModelScope.launch {
            val st = settings.settings.first()
            val active = s.accounts.filter { !it.account.isArchived }
            _state.update {
                it.copy(
                    type = type,
                    categoryId = defaultCategory(type, st.lastExpenseCategoryId, st.lastIncomeCategoryId),
                    toAccountId = if (type == TransactionType.TRANSFER) {
                        it.toAccountId ?: defaultToAccount(active, it.accountId)
                    } else {
                        null
                    },
                    errors = emptySet(),
                )
            }
        }
    }

    fun onKey(key: KeypadKey) {
        _state.update {
            it.copy(
                expression = KeypadInput.apply(it.expression, key, Money.scale(it.entryCurrency)),
                errors = it.errors - ValidationError.AMOUNT_REQUIRED - ValidationError.AMOUNT_TOO_LARGE,
            )
        }
    }

    fun setAccount(id: String) = _state.update {
        val toId = if (it.toAccountId == id) it.accountId else it.toAccountId
        val accCurrency = it.accounts.firstOrNull { a -> a.account.id == id }?.account?.currencyCode
        it.copy(
            accountId = id,
            toAccountId = toId,
            // Счёт в той же валюте, что и покупка, — пересчёт не нужен.
            foreignCurrency = it.foreignCurrency?.takeIf { c -> c != accCurrency },
            debitText = if (accCurrency == it.currency) it.debitText else "",
            errors = it.errors - ValidationError.ACCOUNT_REQUIRED - ValidationError.SAME_ACCOUNT - ValidationError.DEBIT_REQUIRED,
        )
    }

    /** Валюта покупки; валюта счёта — значит без пересчёта. Сумма на клавиатуре сохраняется. */
    fun setForeignCurrency(code: String) = _state.update {
        it.copy(
            foreignCurrency = code.takeIf { c -> c != it.currency },
            debitText = "",
            errors = it.errors - ValidationError.DEBIT_REQUIRED,
        )
    }

    fun setDebit(text: String) = _state.update { it.copy(debitText = text.take(20), errors = it.errors - ValidationError.DEBIT_REQUIRED) }

    fun setToAccount(id: String) = _state.update {
        val fromId = if (it.accountId == id) it.toAccountId else it.accountId
        it.copy(toAccountId = id, accountId = fromId, errors = it.errors - ValidationError.TO_ACCOUNT_REQUIRED - ValidationError.SAME_ACCOUNT)
    }

    fun setToAmount(text: String) = _state.update { it.copy(toAmountText = text.take(20), errors = it.errors - ValidationError.TO_AMOUNT_REQUIRED) }
    fun setFee(text: String) = _state.update { it.copy(feeText = text.take(20), errors = it.errors - ValidationError.FEE_INVALID) }
    fun selectCategory(id: String) = _state.update { it.copy(categoryId = id, errors = it.errors - ValidationError.CATEGORY_REQUIRED) }
    fun setDate(date: LocalDate) = _state.update { it.copy(date = date) }
    fun setTime(time: LocalTime) = _state.update { it.copy(time = time) }
    fun setPayee(text: String) = _state.update { it.copy(payee = text.take(100)) }
    fun setComment(text: String) = _state.update { it.copy(comment = text.take(500)) }
    fun setRepeat(r: RepeatDraft?) = _state.update { if (it.mode == EditorMode.TEMPLATE && r == null) it else it.copy(repeat = r) }

    fun toggleFavorite() {
        val s = _state.value
        val id = s.editingId
        _state.update { it.copy(isFavorite = !it.isFavorite) }
        if (id != null) viewModelScope.launch { transactions.setFavorite(id, !s.isFavorite) }
    }

    fun save() {
        val s = _state.value
        if (s.saving || s.loading) return
        val millis = LocalDateTime.of(s.date, s.time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        if (s.type == TransactionType.ADJUSTMENT) {
            val id = s.editingId ?: return
            _state.update { it.copy(saving = true) }
            viewModelScope.launch {
                transactions.updateMeta(id, millis, s.comment)
                events.send(EditorEvent.Closed)
            }
            return
        }

        val evaluated = ExpressionEvaluator.evaluate(s.expression)
        // Покупка в другой валюте: со счёта списывается указанная сумма или пересчитанная по курсу.
        val foreign = s.foreignMinor
        // Регулярный платёж хранит сумму в валюте платежа — курс понадобится уже при проведении.
        if (s.isForeign && !s.isRecurringSave && foreign != null && s.debitText.isBlank() && s.convertedMinor == null) {
            _state.update { it.copy(errors = setOf(ValidationError.DEBIT_REQUIRED)) }
            return
        }
        val amountMinor = when {
            s.isForeign && foreign == null -> null
            s.isForeign && s.isRecurringSave -> foreign
            s.isForeign -> if (s.debitText.isNotBlank()) Money.parse(s.debitText, s.currency) else s.convertedMinor
            else -> evaluated?.takeIf { it.signum() > 0 }?.let { Money.toMinor(it, s.currency) }
        }
        val original = if (s.isForeign && foreign != null) foreign to s.entryCurrency else null
        val toAmountMinor = if (s.needsToAmount) s.toCurrency?.let { Money.parse(s.toAmountText, it) } else null
        val feeMinor = if (s.feeText.isBlank()) 0L else Money.parse(s.feeText, s.currency) ?: -1L

        val draft = TransactionDraft(
            type = s.type,
            accountId = s.accountId,
            accountCurrency = s.account?.account?.currencyCode,
            toAccountId = s.toAccountId,
            toAccountCurrency = s.toCurrency,
            amountMinor = amountMinor,
            toAmountMinor = toAmountMinor,
            feeMinor = if (s.type == TransactionType.TRANSFER) feeMinor else 0L,
            categoryId = s.categoryId,
        )
        when (val result = TransactionValidator.validate(draft)) {
            is ValidationResult.Invalid -> _state.update { it.copy(errors = result.errors) }
            is ValidationResult.Valid -> {
                _state.update { it.copy(saving = true, errors = emptySet()) }
                val repeat = s.repeat
                if (repeat != null && s.canRepeat) {
                    viewModelScope.launch {
                        val rule = repeat.toRule(s.date)
                        val minute = s.time.hour * 60 + s.time.minute
                        val templateId = s.templateId
                        if (templateId != null) {
                            recurring.update(templateId, result.value, s.payee, s.comment, rule, minute, repeat.mode, original)
                            events.send(EditorEvent.Message("Регулярный платёж сохранён"))
                        } else {
                            recurring.create(
                                result.value, s.payee, s.comment, rule, minute, repeat.mode,
                                foreign = original,
                                firstDebitMinor = if (s.isForeign && s.debitText.isNotBlank()) Money.parse(s.debitText, s.currency) else null,
                            )
                            settings.rememberLastUsed(
                                accountId = result.value.accountId,
                                expenseCategoryId = result.value.categoryId.takeIf { s.type == TransactionType.EXPENSE },
                                incomeCategoryId = result.value.categoryId.takeIf { s.type == TransactionType.INCOME },
                            )
                            events.send(EditorEvent.Message("Регулярный платёж: " + RecurrenceCalculator.describe(rule).lowercase()))
                        }
                        events.send(EditorEvent.Closed)
                    }
                    return
                }
                viewModelScope.launch {
                    transactions.save(s.editingId, result.value, millis, s.payee, s.comment, s.isFavorite, original)
                    settings.rememberLastUsed(
                        accountId = result.value.accountId,
                        expenseCategoryId = result.value.categoryId.takeIf { s.type == TransactionType.EXPENSE },
                        incomeCategoryId = result.value.categoryId.takeIf { s.type == TransactionType.INCOME },
                    )
                    events.send(EditorEvent.Closed)
                }
            }
        }
    }

    fun delete() {
        _state.value.templateId?.let { templateId ->
            viewModelScope.launch {
                recurring.delete(templateId)
                events.send(EditorEvent.Message("Регулярный платёж удалён. Проведённые операции остались в истории"))
                events.send(EditorEvent.Closed)
            }
            return
        }
        val id = _state.value.editingId ?: return
        viewModelScope.launch {
            transactions.delete(id)
            events.send(EditorEvent.Message("Операция удалена"))
            events.send(EditorEvent.Closed)
        }
    }

    fun repeatNow() {
        val id = _state.value.editingId ?: return
        viewModelScope.launch {
            if (transactions.repeatNow(id) != null) {
                events.send(EditorEvent.Message("Операция повторена с текущей датой"))
                events.send(EditorEvent.Closed)
            }
        }
    }

    fun copy() {
        val id = _state.value.editingId ?: return
        viewModelScope.launch { events.send(EditorEvent.OpenCopy(id)) }
    }
}
