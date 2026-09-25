package io.luftaquila.hometty

import android.app.PendingIntent
import android.content.Context
import android.os.UserHandle
import dagger.hilt.android.qualifiers.ApplicationContext
import io.luftaquila.hometty.core.NotificationLines
import io.luftaquila.hometty.core.printable
import io.luftaquila.hometty.core.Settings
import io.luftaquila.hometty.core.SettingsSnapshot
import io.luftaquila.hometty.core.get
import io.luftaquila.hometty.data.Storage
import io.luftaquila.hometty.platform.NetworkData
import io.luftaquila.hometty.platform.NotificationHub
import io.luftaquila.hometty.resources.message
import io.luftaquila.hometty.terminal.TerminalSession
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Launcher messages in the home terminal, each after the time it happened: incoming
 * notifications, new feed items, app changes and the results of background work. They print for
 * as long as the app process runs, with or without a screen open; those that come while no shell
 * runs wait for the next one. A notification's lines open it when tapped.
 */
@Singleton
class TerminalNotices
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val storage: Storage,
    private val notifications: NotificationHub,
    private val network: NetworkData,
    private val banner: ErrorBanner,
) {
    /** A printed notification: what a tap on its lines opens, as the shade would. */
    class Target(val packageName: String, val app: String, val user: UserHandle, val intent: PendingIntent?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Lines to print, with the id of the notification each shows (null: none). */
    private val waiting = ArrayDeque<Pair<String, Long?>>()
    private var terminal: TerminalSession? = null
    private var started = false
    /** The notifications printed lately, by the id their lines carry. */
    private val targets =
        object : LinkedHashMap<Long, Target>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Target>?) = size > 500
        }
    private var nextId = 1L
    /** The last line printed for each notification key. */
    private val printed =
        object : LinkedHashMap<String, String>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 500
        }
    /** The current settings, for the time's color; null until they load. */
    @Volatile private var settings: SettingsSnapshot? = null

    /** Called once when the process starts, before the listener or a feed can post anything. */
    fun start() {
        if (started) return
        started = true
        scope.launch { storage.settings.collect { settings = it } }
        scope.launch { notifications.posted.collect { posted -> failures { show(posted) } } }
        scope.launch {
            network.newItems.collect { item ->
                failures {
                    if (storage.snapshot()[Settings.rssShowUpdates]) print("${item.feed}: ${item.text}")
                }
            }
        }
    }

    /** The home terminal from now on (null: none runs); messages that waited go first. */
    fun attach(session: TerminalSession?) =
        synchronized(waiting) {
            terminal = session
            flush()
        }

    /**
     * Prints [posted] as its app's rule or the defaults say, unless an update of it would print
     * the same line again (it changed only in text the format leaves out).
     */
    private suspend fun show(posted: NotificationHub.Posted) {
        val settings = storage.snapshot()
        if (!settings[Settings.notificationsEnabled]) return
        val content = posted.content
        val rule =
            storage.list("notificationRule").firstOrNull { it.fields["package"] == content.packageName }
        val line = NotificationLines.line(rule, settings, content) ?: return
        val previous = synchronized(printed) { printed.put(posted.key, line) }
        if (posted.update && previous == line) return
        print(line, Target(content.packageName, content.app, posted.user, posted.intent))
    }

    /** Prints [text] after the current time. */
    /** Prints [text] (feed items, rule output, messages) after the time, without control characters. */
    fun print(text: String) = print(printable(text), null)

    private fun print(text: String, target: Target?) {
        val now = LocalTime.now().format(TIME)
        val time = settings?.let { NotificationLines.colored(now, NotificationLines.timeColor(it)) } ?: now
        val line = "$time $text"
        synchronized(waiting) {
            val id = target?.let { nextId++.also { id -> targets[id] = it } }
            waiting.addLast(line to id)
            while (waiting.size > 200) waiting.removeFirst()
            flush()
        }
    }

    /** The notification whose lines carry [id], while it is among the last 500 printed. */
    fun target(id: Long): Target? = synchronized(waiting) { targets[id] }

    /** Hands waiting messages, oldest first, to the terminal while it takes them. */
    private fun flush() {
        val session = terminal ?: return
        while (waiting.isNotEmpty() && waiting.first().let { (line, id) -> session.announce(line, id) })
            waiting.removeFirst()
    }

    private suspend fun failures(block: suspend () -> Unit) = banner.catching(context, block = block)

    companion object {
        /** The time before each message. Quoted: unquoted brackets mark an optional section. */
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("'['HH:mm:ss']'")
    }
}
