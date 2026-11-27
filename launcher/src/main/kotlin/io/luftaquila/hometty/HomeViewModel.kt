package io.luftaquila.hometty

import android.content.Context
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.luftaquila.hometty.core.*
import io.luftaquila.hometty.data.*
import io.luftaquila.hometty.commands.CommandEvent
import io.luftaquila.hometty.commands.LauncherEngine
import io.luftaquila.hometty.platform.*
import io.luftaquila.hometty.resources.R as TextR
import io.luftaquila.hometty.resources.message
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * The launcher window: which screen is open, the home terminal as the screens drive it, and
 * settings and records as the screens edit them. Work that outlives the window belongs to
 * [LauncherRuntime]; backup and fonts have their own models.
 */
@HiltViewModel
class HomeViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    banner: ErrorBanner,
    private val storage: Storage,
    private val secrets: Secrets,
    val device: DeviceAccess,
    val purchases: Purchases,
    private val engine: LauncherEngine,
    catalog: AppCatalog,
    private val terminals: TerminalHost,
    private val runtime: LauncherRuntime,
    private val notices: TerminalNotices,
    private val network: NetworkData,
) : LauncherViewModel(context, banner) {
    val settings =
        storage.settings.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsSnapshot())
    val entries = storage.entries.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val apps = catalog.apps
    // Declared before init, which reads it.
    private val launcherPreferences = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    val route = MutableStateFlow<Route>(Route.Home)
    val appearancePreview = MutableStateFlow<Map<String, String>?>(null)
    val settingsTarget = MutableStateFlow<String?>(null)
    private val navigationHistory = mutableListOf<Route>()

    /** Screens that back can return to, oldest first. */
    val backStack: List<Route>
        get() = navigationHistory.toList()

    val error = banner.message
    val ready = runtime.ready
    val session = terminals.session

    init {
        if (firstRun) route.value = Route.Start
        runtime.start()
        viewModelScope.launch {
            for (destination in terminals.bridge.navigation) navigate(destination)
        }
        viewModelScope.launch { route.collect { runtime.atHome.value = it == Route.Home } }
        // The home screen has no banner: while its terminal runs, errors print there instead.
        viewModelScope.launch {
            combine(banner.message, route, session) { message, route, terminal ->
                    message.takeIf { route == Route.Home && terminal != null }
                }
                .collect { message ->
                    if (message != null) {
                        notices.print(message)
                        banner.dismiss()
                    }
                }
        }
    }

    // Status lines above the terminal

    /** The last weather line (or why there is none) and when it was asked for; 0: never. */
    private var weather = ""
    private var weatherAt = 0L
    private var weatherFailed = false
    private var weatherJob: Job? = null

    /** Asks for the weather again after 30 minutes, or 5 after a failure. */
    private fun refreshWeather() {
        val age = android.os.SystemClock.elapsedRealtime() - weatherAt
        if (weatherJob?.isActive == true) return
        if (weatherAt != 0L && age < (if (weatherFailed) 5 else 30) * 60_000L) return
        weatherAt = android.os.SystemClock.elapsedRealtime()
        weatherJob =
            viewModelScope.launch {
                try {
                    // Not started by the user: no permission dialog every few minutes.
                    val here = device.coordinates(ask = false)
                    weather = network.weather(here.latitude, here.longitude, device.place(here))
                    weatherFailed = false
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    weather = context.message(e)
                    weatherFailed = true
                }
            }
    }

    private suspend fun statusValues(format: String, snapshot: SettingsSnapshot): Map<String, String> {
        if ("{weather}" in format) refreshWeather()
        // System services and the file system: off the main thread, which draws every second.
        val (status, charging) = withContext(Dispatchers.Default) { device.status() to device.charging() }
        return status + mapOf(
            "charging" to if (charging) context.getString(TextR.string.status_charging) else "",
            "device" to snapshot[Settings.deviceName],
            "weather" to weather,
        )
    }

    /**
     * The status lines, drawn again every second when they show seconds, else every 10 seconds
     * and on the minute; only while the screen collects them.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val statusLines: StateFlow<List<String>> =
        settings
            .map { it[Settings.statusLines] }
            .distinctUntilChanged()
            .flatMapLatest { format ->
                if (format.isBlank()) flowOf(emptyList())
                else
                    flow {
                        while (true) {
                            val now = java.time.ZonedDateTime.now()
                            // A value that cannot be read leaves the lines as they were rather
                            // than ending them (or the app).
                            val values =
                                try {
                                    statusValues(format, settings.value)
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    null
                                }
                            if (values != null)
                                emit(StatusLines.render(format, values, now, java.util.Locale.getDefault()))
                            val millis = System.currentTimeMillis()
                            delay(
                                if (StatusLines.showsSeconds(format)) 1000 - millis % 1000
                                else minOf(10_000L, 60_000 - millis % 60_000)
                            )
                        }
                    }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun showError(text: String) = banner.show(text)

    fun dismissError() = banner.dismiss()

    fun navigate(destination: Route) {
        if (destination == route.value) return
        if (destination == Route.Home) navigationHistory.clear()
        else navigationHistory.add(route.value)
        appearancePreview.value = null
        settingsTarget.value = null
        route.value = destination
        banner.dismiss()
    }

    fun back() {
        settingsTarget.value = null
        appearancePreview.value = null
        route.value = navigationHistory.removeLastOrNull() ?: Route.Home
        banner.dismiss()
    }

    fun openSetting(key: String) {
        navigate(settingsRoute(key))
        settingsTarget.value = key
    }

    /** True until the getting started screen has been closed once. */
    val firstRun
        get() = !launcherPreferences.getBoolean("startDone", false)

    fun finishStart() {
        launcherPreferences.edit().putBoolean("startDone", true).apply()
        route.value = Route.Home
        navigationHistory.clear()
    }

    fun foreground(active: Boolean) = runtime.foreground(active)

    /** Prints [text] in the home terminal after the current time. */
    fun append(text: String) = notices.print(text)

    /** Opens what the notification printed with [id] opens in the shade. */
    fun openNotification(id: Long) = launchAction {
        val target = notices.target(id) ?: return@launchAction
        device.openNotification(target.intent, target.packageName, target.user, target.app)
    }

    /**
     * Leaves notifications from the app of the one printed with [id] out from now on, through its
     * rule (made if it has none), and says so.
     */
    fun hideNotifications(id: Long) = launchAction {
        val target = notices.target(id) ?: return@launchAction
        val kind = "notificationRule"
        val rule = storage.list(kind).firstOrNull { it.fields["package"] == target.packageName }
        if (rule != null) storage.modify(kind, rule.name) { it + ("enabled" to "false") }
        else {
            // Rules are named after their app; another app may have the same name.
            val name = if (storage.find(kind, target.app) == null) target.app else target.packageName
            storage.put(kind, name, mapOf("package" to target.packageName, "enabled" to "false"))
        }
        notices.print(context.getString(TextR.string.message_notifications_hidden, target.app))
    }

    // The home terminal

    /** Runs a launcher command from a settings link and shows its output on the home screen. */
    fun runFromSettings(command: String) {
        navigate(Route.Home)
        submit(command)
    }

    /** Output of a command run from settings without leaving them: (command, text). */
    val commandResult = MutableStateFlow<Pair<String, String>?>(null)

    /** Runs a launcher command in place and shows its output in a dialog over settings. */
    fun runInDialog(vararg argv: String) = launchAction {
        val output = StringBuilder()
        try {
            engine.execute(argv.toList()).collect { event ->
                if (event is CommandEvent.Output) output.appendLine(event.text)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            output.appendLine(context.message(e))
        }
        commandResult.value = argv.joinToString(" ") to output.toString().trimEnd()
    }

    /** Puts [value] on the prompt line without running it. */
    fun stageCommand(value: String) = launchAction {
        runtime.openTerminal()
        val terminal = requireNotNull(session.value)
        // withTimeoutOrNull: waiting in vain must say why, not end silently.
        withTimeoutOrNull(2000) { terminal.atPrompt.first { it } }
            ?: error(context.getString(TextR.string.message_finish_the_foreground_terminal_command_first))
        terminal.write(CLEAR_LINE + value.replace("\n", " "))
        navigate(Route.Home)
    }

    /** Types [value] at the prompt and runs it. */
    fun submit(value: String) = launchAction {
        if (value.isBlank()) return@launchAction
        runtime.openTerminal()
        val terminal = requireNotNull(session.value)
        require(terminal.atPrompt.value) {
            context.getString(TextR.string.message_finish_the_foreground_terminal_command_first)
        }
        terminal.write(CLEAR_LINE + value + "\n")
        navigate(Route.Home)
    }

    /** Recalls an earlier (positive [delta]) or later command line. */
    fun history(delta: Int) {
        session.value?.key(if (delta > 0) 5 else 6)
    }

    fun ensureTerminal() = launchAction { runtime.openTerminal() }

    fun stopTerminal() = runtime.stopTerminal()

    // Settings and records

    /** Saves [values]; [after] then runs with whether they were saved (failures show an error). */
    fun updateSettings(values: Map<String, String>, after: (saved: Boolean) -> Unit = {}) =
        launchAction {
            var saved = false
            try {
                storage.update(values)
                saved = true
            } finally {
                after(saved)
            }
        }

    fun setSetting(key: String, value: String, after: (saved: Boolean) -> Unit = {}) =
        updateSettings(mapOf(key to value), after)

    fun resetSetting(key: String) = launchAction { storage.reset(key) }

    /** The saved OpenWeather key; empty without one or when it can no longer be read. */
    fun weatherKey(): String = runCatching { secrets.get("weatherKey") }.getOrNull().orEmpty()

    /** Saves the weather service and, for OpenWeather, its key apart from the settings. */
    fun saveWeatherProvider(provider: String, key: String, saved: () -> Unit) = launchAction {
        if (provider == "openweather") secrets.set("weatherKey", key.trim())
        storage.update(mapOf(Settings.weatherProvider.key to provider))
        saved()
    }

    /** Sets a per-app preference such as favorite or hidden. */
    fun setAppFlag(appId: String, field: String, value: Boolean) = launchAction {
        storage.modify("app", appId) { it + (field to value.toString()) }
    }

    fun saveEntry(
        kind: String,
        name: String,
        fields: Map<String, String>,
        done: () -> Unit,
        failed: (String) -> Unit = banner::show,
    ) = launchAction {
        try {
            // Saving validates the record.
            storage.put(kind, name, fields)
            done()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            failed(context.message(e))
        }
    }

    fun deleteEntry(entry: Entry) = launchAction { storage.remove(entry.kind, entry.id) }

    fun resetPreferences() = launchAction {
        stopTerminal()
        withContext(Dispatchers.IO) {
            storage.restore(
                Storage.ImportPreview(
                    Backup(settings = SettingsSnapshot(), entries = emptyList()),
                    null,
                )
            )
            secrets.clear()
            terminals.historyFile().delete()
        }
        navigate(Route.Home)
    }

    /** Every display setting back to its default; an imported font stays imported. */
    fun recoverAppearance() = launchAction {
        storage.update(
            Settings.all
                .filter { it.category == "appearance" && it.key != Settings.customFont.key }
                .associate { it.key to it.default }
        )
    }

    override fun onCleared() = runtime.uiClosed()
}
