package com.payandplan.app.alarm

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.payandplan.app.MainActivity
import com.payandplan.app.R
import com.payandplan.app.data.Payment
import com.payandplan.app.util.Format
import java.time.LocalDate

object Notifications {

    const val CHANNEL_DUE = "payments_due"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_DUE) != null) return
        val channel = NotificationChannel(
            CHANNEL_DUE,
            "Payment alarms",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Keeps nagging until the payment is marked as paid"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 350, 200, 350)
            setBypassDnd(false)
            lockscreenVisibility = androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        manager.createNotificationChannel(channel)
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    private fun code(id: String, slot: Int): Int = (AlarmScheduler.requestCode(id) * 10 + slot) and 0x7fffffff

    fun show(context: Context, payment: Payment, currency: String) {
        ensureChannels(context)
        if (!canPost(context)) return

        val today = LocalDate.now().toEpochDay()
        val late = today - payment.dueDate
        val when0 = LocalDate.ofEpochDay(payment.dueDate)

        val headline = if (late > 0) "OVERDUE by $late day${if (late > 1L) "s" else ""}"
        else if (late == 0L) "Due today" else "Due ${Format.day(when0)}"

        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_PAYMENT, payment.id)
        }
        val openPi = PendingIntent.getActivity(
            context, code(payment.id, 1), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snooze = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmScheduler.ACTION_SNOOZE
            putExtra(AlarmScheduler.EXTRA_ID, payment.id)
        }
        val snoozePi = PendingIntent.getBroadcast(
            context, code(payment.id, 2), snooze,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Marking as paid always lands in the app when a receipt is required.
        val paidPi = if (payment.requireReceipt) {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_OPEN_PAYMENT, payment.id)
                putExtra(MainActivity.EXTRA_ASK_RECEIPT, true)
            }
            PendingIntent.getActivity(
                context, code(payment.id, 3), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                action = AlarmScheduler.ACTION_PAID
                putExtra(AlarmScheduler.EXTRA_ID, payment.id)
            }
            PendingIntent.getBroadcast(
                context, code(payment.id, 3), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val money = Format.money(payment.amountCents, payment.currency.ifBlank { currency })
        val body = buildString {
            append(headline)
            append("  •  ")
            append(money)
            if (payment.requireReceipt) append("\nReceipt required to close it.")
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_DUE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("💸 ${payment.title}")
            .setContentText("$headline  •  $money")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openPi)
            .setAutoCancel(false)
            .setOngoing(late >= 0)
            .setOnlyAlertOnce(false)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .addAction(0, "PAID ✓", paidPi)
            .addAction(0, "Snooze 1h", snoozePi)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(AlarmScheduler.requestCode(payment.id), notification)
        }
    }

    fun dismiss(context: Context, id: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(AlarmScheduler.requestCode(id)) }
    }
}
