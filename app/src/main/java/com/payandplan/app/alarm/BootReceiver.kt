package com.payandplan.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.payandplan.app.PayPlanApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Alarms die on reboot, on time changes and on app updates: put them all back. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        val repo = PayPlanApp.repository(app)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Notifications.ensureChannels(app)
                repo.topUpAll()
                repo.armWindow()
            } finally {
                pending.finish()
            }
        }
    }
}
