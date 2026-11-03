package app.fintrack.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.fintrack.data.repository.RecurringRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Доступ к репозиторию из фоновой задачи и приёмника кнопок уведомления. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RecurringEntryPoint {
    fun recurring(): RecurringRepository
    fun rates(): app.fintrack.data.repository.RatesRepository
}

fun Context.recurringRepository(): RecurringRepository =
    EntryPointAccessors.fromApplication(applicationContext, RecurringEntryPoint::class.java).recurring()

/**
 * Раз в день (около 9 утра): проводит автоматические платежи и напоминает
 * о тех, что ждут подтверждения. Пропущенные дни догоняются.
 */
class RecurringWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = applicationContext.recurringRepository()
        val posted = repo.processDue()
        RecurringNotifier.notifyPosted(applicationContext, posted)
        RecurringNotifier.notifyDue(applicationContext, repo.dueForConfirmation())
        // Заодно освежить курсы НБРБ (без сети — не страшно, останутся прежние).
        runCatching {
            dagger.hilt.android.EntryPointAccessors.fromApplication(applicationContext, RecurringEntryPoint::class.java).rates().refresh()
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "recurring-daily"
        private val RUN_AT: LocalTime = LocalTime.of(9, 0)

        /** Запланировать ежедневную проверку (существующее расписание не сбрасывается). */
        fun schedule(context: Context) {
            val now = LocalDateTime.now()
            var next = now.toLocalDate().atTime(RUN_AT)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val request = PeriodicWorkRequestBuilder<RecurringWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
