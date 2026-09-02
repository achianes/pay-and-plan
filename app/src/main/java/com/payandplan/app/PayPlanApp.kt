package com.payandplan.app

import android.app.Application
import android.content.Context
import com.payandplan.app.alarm.MaintenanceWorker
import com.payandplan.app.alarm.Notifications
import com.payandplan.app.data.AppDatabase
import com.payandplan.app.data.Repository
import com.payandplan.app.util.Prefs

class PayPlanApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        MaintenanceWorker.enqueue(this)
    }

    companion object {
        @Volatile private var repo: Repository? = null

        fun repository(context: Context): Repository {
            val app = context.applicationContext
            return repo ?: synchronized(this) {
                repo ?: Repository(app, AppDatabase.get(app), Prefs(app)).also { repo = it }
            }
        }
    }
}
