package app.fintrack

import android.app.Application
import app.fintrack.notify.BudgetAlertMonitor
import app.fintrack.notify.RecurringNotifier
import app.fintrack.notify.RecurringWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject

@HiltAndroidApp
class FinTrackApp : Application() {

    @Inject lateinit var budgetAlerts: BudgetAlertMonitor

    /** Живёт вместе с процессом: следит за бюджетом и в фоне (автоплатежи), и на экранах. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        RecurringNotifier.createChannel(this)
        BudgetAlertMonitor.createChannel(this)
        RecurringWorker.schedule(this)
        budgetAlerts.start(appScope)
    }
}
