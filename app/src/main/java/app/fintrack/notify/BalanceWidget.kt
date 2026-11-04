package app.fintrack.notify

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.fintrack.MainActivity
import app.fintrack.R
import app.fintrack.data.repository.AccountRepository
import app.fintrack.data.repository.RatesRepository
import app.fintrack.data.repository.SecurityRepository
import app.fintrack.data.repository.SettingsRepository
import app.fintrack.data.repository.TransactionRepository
import app.fintrack.domain.logic.BalanceCalculator
import app.fintrack.domain.logic.Money
import app.fintrack.domain.logic.Period
import app.fintrack.domain.logic.Periods
import app.fintrack.domain.model.TransactionType
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Что показать на виджете. */
private data class WidgetContent(val balance: String, val expenses: String)

/**
 * Виджет на рабочий стол: общий баланс, расходы за месяц, кнопки добавления операции.
 * Суммы видны всем, кто смотрит на экран, поэтому при PIN-коде или «Скрывать суммы» они не показываются.
 */
@Singleton
class WidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accounts: AccountRepository,
    private val transactions: TransactionRepository,
    private val settings: SettingsRepository,
    private val rates: RatesRepository,
    private val security: SecurityRepository,
) {
    private val zone: ZoneId = ZoneId.systemDefault()
    private val manager: AppWidgetManager get() = AppWidgetManager.getInstance(context)
    private val component get() = ComponentName(context, BalanceWidgetProvider::class.java)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun content(): Flow<WidgetContent> = currentMonth().flatMapLatest { month ->
        val range = Periods.range(Period.Month(month), LocalDate.now(), zone)!!
        combine(
            accounts.observeAccounts(),
            transactions.observeTotals(range),
            settings.settings,
            rates.state,
            security.settings,
        ) { accs, totals, s, r, sec ->
            val active = accs.filter { !it.account.isArchived }
            val currency = s.totalCurrency
            val converter = r.converter
            val balance = converter.total(BalanceCalculator.totalByCurrency(active), currency)
            val expense = converter.total(totals.mapValues { it.value.expenseMinor }, currency)
            val monthName = month.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.forLanguageTag("ru")).lowercase()
            val hidden = sec.pinSet || s.hideAmounts
            WidgetContent(
                balance = if (hidden) Money.masked(currency) else Money.format(balance.totalMinor, currency),
                expenses = if (hidden) "Суммы скрыты" else "Расходы за $monthName: ${Money.format(expense.totalMinor, currency)}",
            )
        }
    }

    /** Следить за данными, пока жив процесс: виджет меняется сразу после любой операции. */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            content().distinctUntilChanged().debounce(800).collect { c ->
                val ids = manager.getAppWidgetIds(component)
                if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(c))
            }
        }
    }

    /** Нарисовать по запросу системы: виджет только что добавили или пришло время обновления. */
    suspend fun render(ids: IntArray) {
        manager.updateAppWidget(ids, views(content().first()))
    }

    private fun views(c: WidgetContent) = RemoteViews(context.packageName, R.layout.widget_balance).apply {
        setTextViewText(R.id.widget_balance, c.balance)
        setTextViewText(R.id.widget_expenses, c.expenses)
        setOnClickPendingIntent(R.id.widget_root, open(null, 10))
        setOnClickPendingIntent(R.id.widget_add_expense, open(TransactionType.EXPENSE, 11))
        setOnClickPendingIntent(R.id.widget_add_income, open(TransactionType.INCOME, 12))
    }

    private fun open(type: TransactionType?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (type != null) intent.putExtra(RecurringNotifier.EXTRA_OPEN, ADD_PREFIX + type.name)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Текущий месяц; меняется, если процесс живёт через полночь 1-го числа. */
    private fun currentMonth(): Flow<YearMonth> = flow {
        while (true) {
            emit(YearMonth.now())
            delay(60 * 60 * 1000L)
        }
    }.distinctUntilChanged()

    companion object {
        /** Открыть форму операции: «add:EXPENSE». */
        const val ADD_PREFIX = "add:"

        /** Системное окно «Добавить на рабочий стол»; false — лаунчер этого не умеет. */
        fun requestPin(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context)
            if (!manager.isRequestPinAppWidgetSupported) return false
            return manager.requestPinAppWidget(ComponentName(context, BalanceWidgetProvider::class.java), null, null)
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun widgets(): WidgetUpdater
}

class BalanceWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val updater = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java).widgets()
        val pending = goAsync()
        scope.launch {
            try {
                updater.render(ids)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
