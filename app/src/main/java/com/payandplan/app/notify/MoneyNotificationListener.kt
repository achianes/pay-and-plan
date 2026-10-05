package com.payandplan.app.notify

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.payandplan.app.PayPlanApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Listens to what the other apps on this phone have to say, so a bank saying "pagamento
 * accettato" can tick off the bill that was waiting for it.
 *
 * Nothing is sent anywhere: every line read here stays in this phone's database, and only
 * the ones matching a rule the owner wrote become a movement. The rest are kept briefly,
 * and only so a rule can be taught from a real notification instead of typed blind.
 */
class MoneyNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        alive = this
        // the shade is usually already full when we are switched on: read what is in it
        scope.launch {
            runCatching { readActive() }
                .onFailure { android.util.Log.w("PayPlan", "shade not read: ${it.message}") }
            runCatching { PayPlanApp.repository(applicationContext).fileWaitingMovements() }
                .onFailure { android.util.Log.w("PayPlan", "waiting movements not filed: ${it.message}") }
        }
    }

    override fun onListenerDisconnected() {
        alive = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        scope.launch {
            runCatching { read(sbn) }
                .onFailure { android.util.Log.w("PayPlan", "notification not read: ${it.message}") }
        }
    }

    /** Everything still sitting in the shade, including what arrived before a rule existed. */
    private suspend fun readActive(): Int {
        val open = runCatching { activeNotifications }.getOrNull() ?: return 0
        var made = 0
        for (sbn in open) {
            if (runCatching { read(sbn) }.getOrDefault(false)) made++
        }
        return made
    }

    /** One notification, as the rules see it. True when it became a movement. */
    private suspend fun read(sbn: StatusBarNotification): Boolean {
        val packageName = sbn.packageName ?: return false
        if (packageName == applicationContext.packageName) return false   // our own alarms
        if (sbn.isOngoing) return false                                   // players, downloads

        val extras = sbn.notification?.extras ?: return false
        if (extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)) return false

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val text = big.ifBlank { extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty() }
        if (title.isBlank() && text.isBlank()) return false

        return PayPlanApp.repository(applicationContext)
            .readNotification(packageName, appLabel(packageName), title, text, sbn.postTime)
    }

    private fun appLabel(packageName: String): String = runCatching {
        val pm = applicationContext.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    companion object {

        /** The running service, when the system has one bound. */
        @Volatile private var alive: MoneyNotificationListener? = null

        /**
         * Reads the notifications still open in the shade right now. Returns how many became
         * movements, or null when nobody is listening (the permission is off, or the system
         * has not bound the service yet).
         */
        suspend fun rescanActive(): Int? = alive?.readActive()

        /** Whether the owner has given this app the right to read notifications. */
        fun isAllowed(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ).orEmpty()
            val me = ComponentName(context, MoneyNotificationListener::class.java)
            return flat.split(':').any {
                val parsed = ComponentName.unflattenFromString(it)
                parsed != null && parsed.packageName == me.packageName
            }
        }

        /** Asks the system to bind us again, for when the service was killed. */
        fun wakeUp(context: Context) = runCatching {
            requestRebind(ComponentName(context, MoneyNotificationListener::class.java))
        }

        /** The system screen where that right is given or taken back. */
        fun settingsIntent(): Intent =
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
