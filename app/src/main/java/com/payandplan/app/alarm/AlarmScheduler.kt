package com.payandplan.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.payandplan.app.data.PayStatus
import com.payandplan.app.data.Payment
import com.payandplan.app.util.Format

object AlarmScheduler {

    const val ACTION_FIRE = "com.payandplan.app.action.FIRE"
    const val ACTION_SNOOZE = "com.payandplan.app.action.SNOOZE"
    const val ACTION_PAID = "com.payandplan.app.action.PAID"
    const val EXTRA_ID = "payment_id"

    /** stable positive request code derived from the uuid */
    fun requestCode(id: String): Int = (id.hashCode() and 0x7fffffff) % 1_000_000

    private const val DAY_MS = 24L * 60 * 60 * 1000

    private fun pendingFire(context: Context, id: String, mutableFlag: Int = PendingIntent.FLAG_IMMUTABLE): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_ID, id)
        }
        return PendingIntent.getBroadcast(
            context, requestCode(id), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag
        )
    }

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return am.canScheduleExactAlarms()
    }

    /** Computes the next moment this payment should shout at the user. */
    fun nextTrigger(payment: Payment, now: Long = System.currentTimeMillis()): Long {
        val due = Format.dateTimeMillis(payment.dueDate, payment.dueTimeMinutes)
        val first = due - payment.remindDaysBefore * DAY_MS
        val snooze = payment.snoozedUntil
        return when {
            snooze != null && snooze > now -> snooze
            first > now -> first
            due > now -> due
            else -> now + 10_000L
        }
    }

    fun schedule(context: Context, payment: Payment) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        if (!payment.alarmEnabled || payment.statusEnum != PayStatus.PENDING) {
            cancel(context, payment.id)
            return
        }
        Notifications.ensureChannels(context)
        val trigger = nextTrigger(payment)
        val pi = pendingFire(context, payment.id)
        runCatching {
            if (canScheduleExact(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            }
        }
    }

    /** Used by the receiver to keep nagging every [minutes] until the bill is closed. */
    fun scheduleNag(context: Context, payment: Payment, minutes: Int) {
        if (minutes <= 0) return
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val trigger = System.currentTimeMillis() + minutes * 60_000L
        val pi = pendingFire(context, payment.id)
        runCatching {
            if (canScheduleExact(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            }
        }
    }

    fun cancel(context: Context, id: String) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { am.cancel(pendingFire(context, id)) }
    }

    fun dismissNotification(context: Context, id: String) = Notifications.dismiss(context, id)
}
