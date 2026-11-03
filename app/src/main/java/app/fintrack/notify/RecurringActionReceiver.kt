package app.fintrack.notify

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import app.fintrack.data.repository.RecurringPostResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Кнопки «Провести» и «Пропустить» в уведомлении — без открытия приложения. */
class RecurringActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val action = intent.action ?: return
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                val repo = app.recurringRepository()
                val message = when (action) {
                    ACTION_CONFIRM -> repo.confirmNext(id).let { r -> if (r == RecurringPostResult.OK) "Платёж проведён" else r.message }
                    ACTION_SKIP -> { repo.skipNext(id); "Платёж пропущен" }
                    else -> null
                }
                RecurringNotifier.cancel(app, id)
                // Если наступило несколько повторов, следующий снова попадёт в уведомление.
                RecurringNotifier.notifyDue(app, repo.dueForConfirmation().filter { it.recurringId == id })
                if (message != null) withContext(Dispatchers.Main) { Toast.makeText(app, message, Toast.LENGTH_SHORT).show() }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_CONFIRM = "app.fintrack.recurring.CONFIRM"
        const val ACTION_SKIP = "app.fintrack.recurring.SKIP"
        private const val EXTRA_ID = "id"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun intent(context: Context, action: String, recurringId: String): PendingIntent {
            val i = Intent(context, RecurringActionReceiver::class.java).setAction(action).putExtra(EXTRA_ID, recurringId)
            return PendingIntent.getBroadcast(
                context,
                (action + recurringId).hashCode(),
                i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
