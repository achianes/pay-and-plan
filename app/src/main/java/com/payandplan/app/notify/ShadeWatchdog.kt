package com.payandplan.app.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.payandplan.app.PayPlanApp
import java.util.concurrent.TimeUnit

/**
 * The listener is not a thread of ours: the system binds it and the system can drop it, on a
 * reboot, on an app update, or whenever it feels short of memory. Nobody noticed until a
 * bank notification went by unread.
 *
 * So, every quarter of an hour: ask to be bound again, read whatever is still in the shade,
 * and retry the movements that never made it into a day.
 */
class ShadeWatchdog(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        if (!MoneyNotificationListener.isAllowed(app)) return Result.success()

        MoneyNotificationListener.wakeUp(app)
        runCatching { MoneyNotificationListener.rescanActive() }
            .onFailure { android.util.Log.w("PayPlan", "shade not reread: ${it.message}") }
        runCatching { PayPlanApp.repository(app).fileWaitingMovements() }
            .onFailure { android.util.Log.w("PayPlan", "waiting movements not filed: ${it.message}") }
        return Result.success()
    }

    companion object {
        private const val NAME = "payplan_shade"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<ShadeWatchdog>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.UPDATE, request
            )
        }
    }
}
