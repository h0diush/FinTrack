package app.fintrack.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.fintrack.MainActivity
import app.fintrack.R
import app.fintrack.data.repository.BudgetRepository
import app.fintrack.data.repository.CategoryRepository
import app.fintrack.data.repository.SettingsRepository
import app.fintrack.data.repository.StatsRepository
import app.fintrack.domain.logic.BudgetAlert
import app.fintrack.domain.logic.BudgetAlerts
import app.fintrack.domain.logic.BudgetLevel
import app.fintrack.domain.logic.BudgetThresholds
import app.fintrack.domain.logic.Money
import app.fintrack.domain.logic.OVERALL_BUDGET_KEY
import app.fintrack.domain.logic.ruPlural
import app.fintrack.ui.budget.BudgetItem
import app.fintrack.ui.budget.BudgetOverviewBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
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

/** Ключ бюджета: общий — [OVERALL_BUDGET_KEY], категория — «id:валюта». */
private fun BudgetItem.key(isOverall: Boolean) =
    if (isOverall) OVERALL_BUDGET_KEY else "${status.categoryId}:${status.currency}"

/**
 * Следит за бюджетом текущего месяца, пока жив процесс приложения (экраны, фоновая задача
 * регулярных платежей, кнопки уведомлений), и сообщает, когда расходы пересекают пороги
 * 50 / 80 / 100 % (пороги — из настроек). О каждом подъёме уровня — одно уведомление.
 */
@Singleton
class BudgetAlertMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stats: StatsRepository,
    private val budgets: BudgetRepository,
    private val categories: CategoryRepository,
    private val settings: SettingsRepository,
) {
    private val zone: ZoneId = ZoneId.systemDefault()

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            currentMonth()
                .flatMapLatest { month ->
                    combine(
                        stats.observeEntries(BudgetOverviewBuilder.fetchRange(month, zone)),
                        budgets.observeLimits(),
                        categories.observeAll(),
                        settings.settings,
                    ) { entries, limits, cats, s ->
                        val today = LocalDate.now()
                        val thresholds = BudgetThresholds(s.budgetInfoPercent, s.budgetWarningPercent).normalized()
                        val overview = BudgetOverviewBuilder.build(limits, entries, cats, month, s.baseCurrency, thresholds, today, zone)
                        val items = buildMap {
                            overview.overall?.let { put(it.key(true), it) }
                            overview.categories.forEach { put(it.key(false), it) }
                        }
                        Snapshot(month, items, s.budgetAlerts)
                    }
                }
                // Серия правок (сохранение, пересчёт) — одна проверка.
                .debounce(1_500)
                .collect { check(it) }
        }
    }

    private data class Snapshot(val month: YearMonth, val items: Map<String, BudgetItem>, val enabled: Boolean)

    private suspend fun check(s: Snapshot) {
        val (alerts, next) = BudgetAlerts.evaluate(settings.budgetAlertState(), s.month, s.items.mapValues { it.value.level })
        settings.saveBudgetAlertState(next)
        // Выключенные уведомления всё равно обновляют состояние — после включения не будет залпа.
        if (!s.enabled || alerts.isEmpty() || !RecurringNotifier.canNotify(context)) return
        alerts.forEach { a -> s.items[a.key]?.let { notify(a, it, s.month) } }
    }

    private fun notify(alert: BudgetAlert, item: BudgetItem, month: YearMonth) {
        val st = item.status
        val name = if (alert.key == OVERALL_BUDGET_KEY) "Бюджет месяца" else item.category?.name ?: "Бюджет"
        val percent = if (st.fraction.isFinite()) (st.fraction * 100).toInt() else 100
        val title = when (alert.level) {
            BudgetLevel.EXCEEDED -> "$name: бюджет превышен"
            else -> "$name: потрачено $percent %"
        }
        val monthName = month.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.forLanguageTag("ru")).lowercase()
        val text = if (alert.level == BudgetLevel.EXCEEDED) {
            "Перерасход ${Money.format(-st.remainingMinor, st.currency)} при лимите ${Money.format(st.availableMinor, st.currency)} на $monthName"
        } else {
            val f = item.forecast
            val perDay = f?.takeIf { it.daysLeft > 0 }?.let {
                ". ${it.daysLeft} ${ruPlural(it.daysLeft.toLong(), "день", "дня", "дней")} до конца месяца — " +
                    "по ${Money.format(it.dailyAllowanceMinor, st.currency)} в день"
            } ?: ""
            "Осталось ${Money.format(st.remainingMinor, st.currency)} из ${Money.format(st.availableMinor, st.currency)}$perDay"
        }
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_budget)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openBudget())
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        // Один бюджет — одно уведомление: новый уровень заменяет прежний.
        runCatching { NotificationManagerCompat.from(context).notify(2000 + (alert.key.hashCode() and 0x7FFFFFF), n) }
    }

    private fun openBudget(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(RecurringNotifier.EXTRA_OPEN, OPEN_BUDGET)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Текущий месяц; меняется, если приложение живёт через полночь 1-го числа. */
    private fun currentMonth(): Flow<YearMonth> = flow {
        while (true) {
            emit(YearMonth.now())
            delay(60 * 60 * 1000L)
        }
    }.distinctUntilChanged()

    companion object {
        const val CHANNEL_ID = "budget"
        const val OPEN_BUDGET = "budget"

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(CHANNEL_ID, "Бюджет", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Расходы достигли порога бюджета или превысили его"
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
