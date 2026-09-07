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
import com.payandplan.app.data.EntryKind
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

        val kind = payment.kindEnum
        val clock = Format.time(payment.dueTimeMinutes)
        // an appointment is kept, a reminder is done, money comes in or goes out
        val headline = when (kind) {
            EntryKind.APPOINTMENT ->
                if (late > 0) "Missed, was ${Format.day(when0)} at $clock"
                else if (late == 0L) "Today at $clock" else "${Format.day(when0)} at $clock"
            EntryKind.REMINDER ->
                if (late > 0) "OVERDUE by $late day${if (late > 1L) "s" else ""}"
                else if (late == 0L) "Due today at $clock" else "Due ${Format.day(when0)}"
            EntryKind.INCOME ->
                if (late > 0) "Expected $late day${if (late > 1L) "s" else ""} ago"
                else if (late == 0L) "Expected today" else "Expected ${Format.day(when0)}"
            EntryKind.BILL ->
                if (late > 0) "OVERDUE by $late day${if (late > 1L) "s" else ""}"
                else if (late == 0L) "Due today" else "Due ${Format.day(when0)}"
        }
        val doneLabel = when (kind) {
            EntryKind.APPOINTMENT, EntryKind.REMINDER -> "DONE ✓"
            EntryKind.INCOME -> "RECEIVED ✓"
            EntryKind.BILL -> "PAID ✓"
        }
        val emoji = when (kind) {
            EntryKind.APPOINTMENT -> "🗓"
            EntryKind.REMINDER -> "⏰"
            EntryKind.INCOME -> "💰"
            EntryKind.BILL -> "💸"
        }

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

        // Marking as paid always lands in the app when a receipt is required (bills only).
        val paidPi = if (payment.requireReceipt && kind == EntryKind.BILL) {
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
        // an appointment shows where it is, not a price; money only when there is one
        val detail = when {
            kind == EntryKind.APPOINTMENT && payment.location.isNotBlank() -> "📍 ${payment.location}"
            kind == EntryKind.APPOINTMENT || kind == EntryKind.REMINDER ->
                if (payment.amountCents > 0) money else ""
            else -> money
        }
        val line = if (detail.isBlank()) headline else "$headline  •  $detail"
        val body = buildString {
            append(line)
            if (payment.requireReceipt && kind == EntryKind.BILL) append("\nReceipt required to close it.")
            if (kind != EntryKind.BILL && payment.notes.isNotBlank()) append("\n").append(payment.notes.take(160))
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_DUE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$emoji ${payment.title}")
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openPi)
            .setAutoCancel(false)
            .setOngoing(late >= 0)
            .setOnlyAlertOnce(false)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .addAction(0, doneLabel, paidPi)
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
