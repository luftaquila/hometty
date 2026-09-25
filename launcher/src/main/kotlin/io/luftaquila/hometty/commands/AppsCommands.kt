package io.luftaquila.hometty.commands

import io.luftaquila.hometty.Route
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import io.luftaquila.hometty.core.AppNames
import io.luftaquila.hometty.core.Cli
import io.luftaquila.hometty.core.CliUsageException
import io.luftaquila.hometty.core.Commands
import io.luftaquila.hometty.core.EntryStore
import io.luftaquila.hometty.core.Shell
import io.luftaquila.hometty.AppCatalog
import io.luftaquila.hometty.platform.DeviceAccess
import io.luftaquila.hometty.platform.PlatformRequest
import javax.inject.Inject
import javax.inject.Provider
import io.luftaquila.hometty.core.Settings
import io.luftaquila.hometty.core.get

/** `help` section Apps: apps, alias, unalias, search, open, share. */
class AppsCommands
@Inject
constructor(
    private val catalog: Provider<AppCatalog>,
    private val entries: EntryStore,
    private val device: Provider<DeviceAccess>,
    private val names: CommandNames,
) : CommandGroup {
    override val commands =
        listOf(
            command(Commands.apps, gui = Route.Apps) { apps() },
            command(Commands.alias) { alias() },
            command(Commands.unalias) {
                for (name in operands) entries.remove("alias", name)
                names.refresh()
            },
            command(Commands.search) {
                val query = text
                if (query.isBlank()) throw CliUsageException("search: enter a query")
                val encoded = Uri.encode(query)
                device
                    .get()
                    .open(
                        when (value(Cli.engine) ?: settings[Settings.searchEngine]) {
                            "google" -> "https://www.google.com/search?q=$encoded"
                            "youtube" -> "https://www.youtube.com/results?search_query=$encoded"
                            else -> "https://duckduckgo.com/?q=$encoded"
                        }
                    )
            },
            command(Commands.open) {
                if (operands.isEmpty()) device.get().request(PlatformRequest.OpenDocument)
                else device.get().open(text)
            },
            command(Commands.share) { device.get().share(text) },
        )

    private suspend fun CommandCall.apps() {
        val catalog = catalog.get()
        catalog.refresh()
        when (action.ifEmpty { "list" }) {
            "list" -> list(catalog)
            "launch" -> {
                val app = catalog.find(text)
                catalog.launch(app)
                if (settings[Settings.showLaunches]) out("→ ${app.label} (${app.packageName})")
            }
            "hide",
            "show",
            "favorite",
            "reset" -> {
                val app = catalog.find(text)
                var change = "" to ""
                entries.modify("app", app.id) { old ->
                    change =
                        when (action) {
                            "hide" -> "hidden" to "true"
                            "show" -> "hidden" to "false"
                            "favorite" -> "favorite" to (old["favorite"] != "true").toString()
                            else -> "launches" to "0"
                        }
                    old + change
                }
                // A hidden app's name stops opening it at once, not after the next refresh.
                if (change.first == "hidden") names.refresh()
                out("${app.label}: ${change.first} = ${change.second}")
            }
            "info" ->
                device
                    .get()
                    .launch(
                        Intent(
                            AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${catalog.find(text).packageName}"),
                        )
                    )
            "uninstall" ->
                device
                    .get()
                    .launch(
                        Intent(
                            Intent.ACTION_DELETE,
                            Uri.parse("package:${catalog.find(text).packageName}"),
                        )
                    )
            "store" ->
                device
                    .get()
                    .open(
                        "https://play.google.com/store/apps/details?id=${catalog.find(text).packageName}"
                    )
            else -> usage()
        }
    }

    private suspend fun CommandCall.list(catalog: AppCatalog) {
        val query = text
        val stored = entries.list("app").associateBy { it.name }
        val matches =
            catalog.apps.value.filter {
                (has(Cli.all) || stored[it.id]?.fields?.get("hidden") != "true") &&
                    (AppNames.key(it.label).contains(AppNames.key(query)) ||
                        it.packageName.contains(query, true))
            }
        val collator = java.text.Collator.getInstance()
        out(
            if (has(Cli.long))
            // Each app with its ID and how often it was launched.
            matches
                    .sortedWith { a, b -> collator.compare(a.label, b.label) }
                    .joinToString("\n") {
                        Cli.record(
                            it.label,
                            "id" to it.id,
                            "launches" to (stored[it.id]?.fields?.get("launches") ?: "0"),
                        )
                    }
            else
                AppNames.sections(matches, { it.label }, collator).joinToString("\n") {
                    (section, list) ->
                    "\u001b[1m$section\u001b[0m\n" + list.joinToString("\n") { "  ${it.label}" }
                }
        )
    }

    private suspend fun CommandCall.alias() {
        if (operands.isEmpty())
            out(
                entries.list("alias").joinToString("\n") {
                    "alias ${it.name}=${Shell.quote(it.fields["command"].orEmpty())}"
                }
            )
        var changed = false
        for (value in operands) {
            val name = value.substringBefore('=')
            if ('=' !in value) {
                val entry =
                    requireNotNull(entries.find("alias", name)) { "alias: $name: not found" }
                out("alias $name=${Shell.quote(entry.fields["command"].orEmpty())}")
            } else {
                // Saving checks the name and the body (Collections.validate).
                entries.put("alias", name, mapOf("command" to value.substringAfter('=')))
                changed = true
            }
        }
        // The next command on the same line may already use the new name.
        if (changed) names.refresh()
    }
}
