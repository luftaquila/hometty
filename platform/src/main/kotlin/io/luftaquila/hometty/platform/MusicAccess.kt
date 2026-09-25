package io.luftaquila.hometty.platform

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState

/** Controls the player another app is running, through its media session. */
class MusicAccess(private val context: Context) {
    /**
     * Sends [action] (play, pause, next or previous) to the newest other app's media session, or
     * with no action tells which app it is and whether it plays. Android lists sessions only to an
     * enabled notification listener.
     */
    fun control(action: String): String {
        val sessions =
            context
                .getSystemService(MediaSessionManager::class.java)
                .getActiveSessions(ComponentName(context, NotificationAccess::class.java))
        val controller =
            sessions.firstOrNull { it.packageName != context.packageName }
                ?: error("No external player; enable notification access")
        when (action) {
            "" -> {
                val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
                return "${controller.packageName}: ${if (playing) "playing" else "paused"}"
            }
            "play" -> controller.transportControls.play()
            "pause" -> controller.transportControls.pause()
            "next" -> controller.transportControls.skipToNext()
            "previous" -> controller.transportControls.skipToPrevious()
            else -> error("Use play, pause, next or previous")
        }
        return "Sent $action to ${controller.packageName}"
    }
}
