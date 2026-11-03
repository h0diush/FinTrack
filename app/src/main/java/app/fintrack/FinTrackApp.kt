package app.fintrack

import android.app.Application
import app.fintrack.notify.RecurringNotifier
import app.fintrack.notify.RecurringWorker
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class FinTrackApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RecurringNotifier.createChannel(this)
        RecurringWorker.schedule(this)
    }
}
