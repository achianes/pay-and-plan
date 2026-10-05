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

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName ?: return
        if (packageName == packageName()) return            // our own alarms are not bank news
        if (sbn.isOngoing) return                           // players, downloads, navigation

        val extras = sbn.notification?.extras ?: return
        if (extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)) return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val text = big.ifBlank { extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty() }
        if (title.isBlank() && text.isBlank()) return

        val label = appLabel(packageName)
        val repo = PayPlanApp.repository(applicationContext)
        scope.launch {
            runCatching { repo.readNotification(packageName, label, title, text, sbn.postTime) }
                .onFailure { android.util.Log.w("PayPlan", "notification not read: ${it.message}") }
        }
    }

    private fun packageName(): String = applicationContext.packageName

    private fun appLabel(packageName: String): String = runCatching {
        val pm = applicationContext.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    companion object {

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

        /** The system screen where that right is given or taken back. */
        fun settingsIntent(): Intent =
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
