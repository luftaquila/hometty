package io.luftaquila.hometty.platform

import android.app.Notification
import android.app.PendingIntent
import android.os.UserHandle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.luftaquila.hometty.core.NotificationContent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Notifications as they arrive. One instance for the app: the listener posts here and the
 * launcher prints them in the terminal.
 */
class NotificationHub {
    /**
     * A notification as it arrived, with what a tap on it opens: its [intent], or else its app
     * for [user]. [update]: an earlier version of it (same [key], not removed since) came before.
     */
    data class Posted(
        val key: String,
        val content: NotificationContent,
        val user: UserHandle,
        val intent: PendingIntent?,
        val update: Boolean,
    )

    private val _posted =
        MutableSharedFlow<Posted>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** New notifications only; nothing is replayed to a late collector. */
    val posted: SharedFlow<Posted> = _posted.asSharedFlow()

    /** Called by the listener for each notification it passes on. */
    fun post(notification: Posted) {
        _posted.tryEmit(notification)
    }
}

/** Android's notification listener for the launcher; see [NotificationHub]. */
class NotificationAccess : NotificationListenerService() {
    private val hub
        get() = (application as PlatformDependencies).notifications

    /** What was last passed on for each notification key, so an unchanged update is skipped. */
    private val shown = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 500
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) return
        val notification = sbn.notification ?: return
        // Progress, media and service notifications update constantly; a group summary repeats
        // the notifications it groups.
        val skipped =
            Notification.FLAG_ONGOING_EVENT or
                Notification.FLAG_FOREGROUND_SERVICE or
                Notification.FLAG_GROUP_SUMMARY
        if (notification.flags and skipped != 0) return
        val extras = notification.extras
        fun extra(name: String) = extras.getCharSequence(name)?.toString().orEmpty()
        val title = extra(Notification.EXTRA_TITLE)
        val text = extra(Notification.EXTRA_TEXT)
        val bigText = extra(Notification.EXTRA_BIG_TEXT)
        val subText = extra(Notification.EXTRA_SUB_TEXT)
        if (title.isBlank() && text.isBlank() && bigText.isBlank()) return
        val seen = listOf(title, text, bigText, subText).joinToString("\n")
        val update =
            synchronized(shown) {
                val previous = shown.put(sbn.key, seen)
                if (previous == seen) return
                previous != null
            }
        val app =
            runCatching {
                    packageManager
                        .getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0))
                        .toString()
                }
                .getOrDefault(sbn.packageName)
        hub.post(
            NotificationHub.Posted(
                sbn.key,
                NotificationContent(sbn.packageName, app, title, text, bigText, subText),
                sbn.user,
                notification.contentIntent,
                update,
            )
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null) synchronized(shown) { shown.remove(sbn.key) }
    }
}
