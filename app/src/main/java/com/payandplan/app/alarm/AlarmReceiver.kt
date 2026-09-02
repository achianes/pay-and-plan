package com.payandplan.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.payandplan.app.PayPlanApp
import com.payandplan.app.data.PayStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(AlarmScheduler.EXTRA_ID)
        if (id.isNullOrBlank()) return
        val action = intent.action ?: AlarmScheduler.ACTION_FIRE
        val pending = goAsync()
        val app = context.applicationContext
        val repo = PayPlanApp.repository(app)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    AlarmScheduler.ACTION_SNOOZE -> repo.snooze(id, 60)

                    AlarmScheduler.ACTION_PAID -> repo.markPaid(id)

                    else -> {
                        val payment = repo.getPayment(id) ?: return@launch
                        if (payment.statusEnum != PayStatus.PENDING || !payment.alarmEnabled) {
                            AlarmScheduler.cancel(app, id)
                            Notifications.dismiss(app, id)
                            return@launch
                        }
                        val snoozedUntil = payment.snoozedUntil
                        val now = System.currentTimeMillis()
                        if (snoozedUntil != null && snoozedUntil > now) {
                            AlarmScheduler.schedule(app, payment)
                            return@launch
                        }
                        Notifications.show(app, payment, repo.prefs.currency)
                        // Keep shouting until it gets marked as paid.
                        val nag = if (payment.nagMinutes > 0) payment.nagMinutes else 0
                        if (nag > 0) AlarmScheduler.scheduleNag(app, payment, nag)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
