package com.payandplan.app.alarm

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.payandplan.app.PayPlanApp
import java.util.concurrent.TimeUnit

/** Safety net: tops up recurring series and re-arms alarms twice a day. */
class MaintenanceWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = PayPlanApp.repository(applicationContext)
        return runCatching {
            repo.topUpAll()
            repo.armWindow()
            // the bank is asked twice a day as well: a notification can be missed, the
            // account cannot, and nothing here depends on the phone having been awake
            runCatching { repo.readTheAccount() }
                .onFailure { android.util.Log.w("PayPlan", "account not read: ${it.message}") }
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        private const val NAME = "payplan_maintenance"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<MaintenanceWorker>(12, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
