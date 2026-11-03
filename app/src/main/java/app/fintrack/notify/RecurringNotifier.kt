package app.fintrack.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.fintrack.MainActivity
import app.fintrack.R
import app.fintrack.data.repository.DueOccurrence
import app.fintrack.data.repository.PostedOccurrence
import app.fintrack.domain.logic.Money
import app.fintrack.domain.logic.ruPlural
import app.fintrack.domain.model.TransactionType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Уведомления о регулярных платежах: «ждёт подтверждения» и «проведено автоматически». */
object RecurringNotifier {

    const val CHANNEL_ID = "recurring"
    const val EXTRA_OPEN = "open"
    const val OPEN_RECURRING = "recurring"
    private const val POSTED_ID = 1
    private val DAY = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"))

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Регулярные платежи", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Напоминания в день платежа и сообщения об автоматическом проведении"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun canNotify(context: Context): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** По уведомлению на каждый шаблон, ждущий подтверждения; с кнопками «Провести» и «Пропустить». */
    fun notifyDue(context: Context, items: List<DueOccurrence>, today: LocalDate = LocalDate.now()) {
        if (!canNotify(context)) return
        val nm = NotificationManagerCompat.from(context)
        for (o in items) {
            val whenText = if (o.date == today) "сегодня" else o.date.format(DAY)
            val extra = if (o.count > 1) " · ещё ${o.count - 1} ${ruPlural((o.count - 1).toLong(), "пропущенный", "пропущенных", "пропущенных")}" else ""
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("${o.title}: ${Money.format(o.amountMinor, o.currency)}")
                .setContentText("Регулярный платёж, $whenText$extra. Провести?")
                .setContentIntent(openApp(context))
                .addAction(0, "Провести", RecurringActionReceiver.intent(context, RecurringActionReceiver.ACTION_CONFIRM, o.recurringId))
                .addAction(0, "Пропустить", RecurringActionReceiver.intent(context, RecurringActionReceiver.ACTION_SKIP, o.recurringId))
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .build()
            runCatching { nm.notify(idFor(o.recurringId), n) }
        }
    }

    /** Одно сводное уведомление о проведённых автоматически платежах. */
    fun notifyPosted(context: Context, items: List<PostedOccurrence>) {
        if (items.isEmpty() || !canNotify(context)) return
        val lines = items.map { o ->
            val sign = when (o.type) {
                TransactionType.INCOME -> "+"
                TransactionType.EXPENSE -> "−"
                else -> ""
            }
            "${o.title}: $sign${Money.format(o.amountMinor, o.currency)}"
        }
        val title = if (items.size == 1) "Проведён регулярный платёж" else "Проведено ${items.size} ${ruPlural(items.size.toLong(), "платёж", "платежа", "платежей")}"
        val style = NotificationCompat.InboxStyle().also { s -> lines.forEach(s::addLine) }
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(lines.joinToString(", "))
            .setStyle(style)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(POSTED_ID, n) }
    }

    fun cancel(context: Context, recurringId: String) = NotificationManagerCompat.from(context).cancel(idFor(recurringId))

    private fun idFor(recurringId: String) = 1000 + (recurringId.hashCode() and 0x7FFFFFF)

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN, OPEN_RECURRING)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
