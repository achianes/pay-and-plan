package com.payandplan.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.payandplan.app.PayPlanApp
import com.payandplan.app.notify.MoneyNotificationListener
import com.payandplan.app.notify.ShadeWatchdog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Alarms die on reboot, on time changes and on app updates, and so does the binding that
 * lets us read notifications: put them all back.
 */
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
                // an update unbinds the listener: ask for it back, then read what was missed
                MoneyNotificationListener.wakeUp(app)
                ShadeWatchdog.enqueue(app)
                runCatching { repo.fileWaitingMovements() }
            } finally {
                pending.finish()
            }
        }
    }
}
