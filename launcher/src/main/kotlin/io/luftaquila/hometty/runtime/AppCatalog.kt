package io.luftaquila.hometty.runtime

import io.luftaquila.hometty.commands.Texts
import io.luftaquila.hometty.core.AppNames
import io.luftaquila.hometty.core.EntryStore
import io.luftaquila.hometty.platform.DeviceAccess
import io.luftaquila.hometty.resources.R as TextR
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Launchable apps: the list commands, completion, the shell's app names and screens share. */
@Singleton
class AppCatalog
@Inject
constructor(
    private val device: Provider<DeviceAccess>,
    private val entries: EntryStore,
    private val texts: Texts,
) {
    private val _apps = MutableStateFlow<List<DeviceAccess.App>>(emptyList())
    val apps = _apps.asStateFlow()

    suspend fun refresh() {
        _apps.value = withContext(Dispatchers.IO) { device.get().apps() }
    }

    /**
     * Whether [name] (typed as a command) names an app: its package or id, or with the words
     * after it ([query]) the app's whole name.
     */
    fun names(name: String, query: String): Boolean =
        apps.value.any {
            it.packageName == name || it.id == name || AppNames.key(it.label) == AppNames.key(query)
        }

    /**
     * The app [query] names: its id, package or whole name, ignoring case and spaces, as in
     * T-UI. Of apps sharing a name, the one in the user's own profile opens.
     */
    fun find(query: String): DeviceAccess.App {
        val key = AppNames.key(query)
        return requireNotNull(
            apps.value
                .filter { it.id == query || it.packageName == query || AppNames.key(it.label) == key }
                .minByOrNull { if (it.user == android.os.Process.myUserHandle()) 0 else 1 }
        ) {
            texts.string(TextR.string.app_not_found, query)
        }
    }

    /** Opens [app] and counts the launch. */
    suspend fun launch(app: DeviceAccess.App) {
        withContext(Dispatchers.Main) { device.get().launchApp(app) }
        entries.modify("app", app.id) {
            it + ("launches" to ((it["launches"]?.toIntOrNull() ?: 0) + 1).toString())
        }
    }
}
