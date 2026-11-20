package tools.fmc.musiccompanion

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Gives the app access to active media sessions and matching media notifications for local phone UI use. Notification
 * data is read in memory only, is never persisted, and is never included in a watch reply.
 */
enum class ListenerAccess {
    GRANTED,
    NEVER_GRANTED,
    REVOKED,
}

class MusicNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        live = this
    }

    override fun onListenerDisconnected() {
        live = null
    }

    override fun onDestroy() {
        live = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var live: MusicNotificationListener? = null

        /** The notifications visible to this listener, newest first. */
        internal fun activeNotifications(): List<StatusBarNotification> = runCatching {
            live?.activeNotifications.orEmpty().sortedByDescending { it.postTime }
        }.getOrDefault(emptyList())

        fun isConnected(): Boolean = live != null

        /** Whether the user has enabled this app's notification-listener access. */
        fun isAccessGranted(context: Context): Boolean = accessState(context) == ListenerAccess.GRANTED

        /** Distinguishes access that was never enabled from access that the user later revoked. */
        fun accessState(context: Context): ListenerAccess {
            val component = ComponentName(context, MusicNotificationListener::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ).orEmpty()
            val granted = enabled.split(':').mapNotNull(ComponentName::unflattenFromString).any {
                it.packageName == component.packageName && it.className == component.className
            }
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            if (granted) {
                preferences.edit().putBoolean(GRANTED_ONCE, true).apply()
                return ListenerAccess.GRANTED
            }
            return if (preferences.getBoolean(GRANTED_ONCE, false)) {
                ListenerAccess.REVOKED
            } else {
                ListenerAccess.NEVER_GRANTED
            }
        }

        private const val PREFERENCES = "notification_access"
        private const val GRANTED_ONCE = "granted_once"
    }
}
