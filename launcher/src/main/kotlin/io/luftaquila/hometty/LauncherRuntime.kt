package io.luftaquila.hometty

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.luftaquila.hometty.core.StateNames
import io.luftaquila.hometty.data.Storage
import io.luftaquila.hometty.platform.NetworkData
import io.luftaquila.hometty.resources.R as TextR
import io.luftaquila.hometty.resources.message
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import io.luftaquila.hometty.core.Settings
import io.luftaquila.hometty.core.get

/**
 * What the launcher does for as long as its process runs, whichever screen is open: starting the
 * home shell, reacting to settings, keeping the shell's command names current, screen-off and
 * package events, and RSS work while the launcher is in the foreground.
 */
@Singleton
class LauncherRuntime
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val storage: Storage,
    private val catalog: AppCatalog,
    private val links: CommandLinks,
    private val terminals: TerminalHost,
    private val notices: TerminalNotices,
    private val banner: ErrorBanner,
    private val feeds: Feeds,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false
    private var foregroundWork: Job? = null

    private val _ready = MutableStateFlow(false)

    /** The home shell has started at least once. */
    val ready = _ready.asStateFlow()

    /**
     * The home terminal is on screen and nothing else is: set by the UI. RSS command rules only
     * run then, so they never act over another screen.
     */
    val atHome = MutableStateFlow(false)

    /** Starts the runtime once; the UI calls this when it first appears. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            failures("Startup") {
                // Android may leave the listener unbound after the process dies; with access
                // allowed this binds it again (nothing happens when it is bound).
                if (
                    androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context)
                        .contains(context.packageName)
                )
                    android.service.notification.NotificationListenerService.requestRebind(
                        android.content.ComponentName(context, io.luftaquila.hometty.platform.NotificationAccess::class.java)
                    )
                withContext(Dispatchers.IO) { storage.recover() }
                catalog.refresh()
                openTerminal()
                if (storage.recoveredSettings.get())
                    notices.print(
                        context.getString(
                            TextR.string
                                .message_damaged_settings_were_recovered_original_file_preserved_privately
                        )
                    )
            }
        }
        scope.launch {
            storage.settings.collect { prefs ->
                failures {
                    NetworkData.schedule(context, storage)
                    // Turning root off ends a shell that has asked for it.
                    if (terminals.bridge.rootRequested.value && !prefs[Settings.rootEnabled])
                        terminals.stop()
                }
            }
        }
        // Saved aliases and installed apps, less hidden ones, are names the shell can type.
        scope.launch {
            val hidden =
                storage
                    .entries("app")
                    .map { apps -> apps.filter { it.fields["hidden"] == "true" }.map { it.name }.toSet() }
                    .distinctUntilChanged()
            combine(storage.entries("alias"), hidden, catalog.apps) { _, _, _ -> }
                .collect { if (_ready.value) failures { withContext(Dispatchers.IO) { links.refresh() } } }
        }
        // SCREEN_OFF is a protected broadcast: apps cannot send it.
        ContextCompat.registerReceiver(
            context,
            screen,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        ContextCompat.registerReceiver(
            context,
            packages,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** Opens the home shell unless it is running. */
    suspend fun openTerminal() {
        withContext(Dispatchers.IO) { terminals.openHome(storage.snapshot()) }
        _ready.value = true
    }

    fun stopTerminal() = terminals.stop()

    /** The launcher's window came to the front or left it. */
    fun foreground(active: Boolean) {
        foregroundWork?.cancel()
        if (!active) return
        foregroundWork =
            scope.launch {
                launch { failures { catalog.refresh() } }
                while (isActive) {
                    feeds.poll()
                    delay(15000)
                }
            }
    }

    /** The last screen closed for good (not a configuration change). */
    fun uiClosed() {
        terminals.stop()
    }

    private fun terminalIdle(): Boolean {
        val session = terminals.session.value ?: return false
        return session.atPrompt.value && !session.inputPending.value
    }

    private val screen =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF ->
                        scope.launch {
                            failures {
                                // Only at an empty prompt: typed input must not be thrown away.
                                if (storage.snapshot()[Settings.clearOnLock] && terminalIdle())
                                    terminals.session.value?.write(CLEAR_LINE + "clear\n")
                            }
                        }
                }
            }
        }

    private val packages =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                scope.launch {
                    failures {
                        val before = catalog.apps.value
                        catalog.refresh()
                        if (intent == null || !storage.snapshot()[Settings.showChanges])
                            return@failures
                        // An update arrives as removed and added again, both replacing: the
                        // added half says it.
                        val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                        val added = intent.action == Intent.ACTION_PACKAGE_ADDED
                        if (replacing && !added) return@failures
                        val packageName = intent.data?.schemeSpecificPart.orEmpty()
                        val label =
                            (if (added) catalog.apps.value else before)
                                .firstOrNull { it.packageName == packageName }
                                ?.label
                        val name = label?.let { "$it ($packageName)" } ?: packageName
                        notices.print(
                            this@LauncherRuntime.context.getString(
                                when {
                                    replacing -> TextR.string.message_app_updated
                                    added -> TextR.string.message_app_installed
                                    else -> TextR.string.message_app_removed
                                },
                                name,
                            )
                        )
                    }
                }
            }
        }

    private suspend fun failures(label: String? = null, block: suspend () -> Unit) =
        banner.catching(context, label, block)
}

/** RSS while the launcher is in the foreground: refreshes the feeds that are due. */
class Feeds
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val storage: Storage,
    private val network: NetworkData,
    private val banner: ErrorBanner,
) {
    /**
     * One pass over the enabled feeds: those not fetched within the feed interval are fetched
     * (new items print through [TerminalNotices]).
     */
    suspend fun poll() {
        val prefs = storage.settings.first()
        if (!prefs[Settings.rssEnabled]) return
        // The same test as the background refresh: an unmetered network.
        if (
            prefs[Settings.rssWifiOnly] &&
                context.getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered
        )
            return
        for (feed in storage.list("rss").filter { it.fields["enabled"] != "false" }) {
            try {
                val updated = storage.getState(StateNames.feedUpdated(feed.id))?.toLongOrNull() ?: 0
                if (System.currentTimeMillis() - updated > prefs[Settings.rssInterval] * 60_000L)
                    network.feed(feed)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // A failed fetch waits a full interval too, instead of retrying every pass.
                storage.putState(mapOf(StateNames.feedUpdated(feed.id) to System.currentTimeMillis().toString()))
                banner.show("RSS ${feed.name}: ${e.message}")
            }
        }
    }

}
