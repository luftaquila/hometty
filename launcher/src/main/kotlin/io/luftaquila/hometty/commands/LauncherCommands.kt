package io.luftaquila.hometty.commands

import io.luftaquila.hometty.Route
import io.luftaquila.hometty.core.Cli
import io.luftaquila.hometty.core.CliUsageException
import io.luftaquila.hometty.core.Commands
import io.luftaquila.hometty.core.Settings
import io.luftaquila.hometty.core.SettingsStore
import io.luftaquila.hometty.core.Themes
import javax.inject.Inject
import io.luftaquila.hometty.core.get

/** `help` section Launcher: config, theme and help. */
class LauncherCommands @Inject constructor(private val store: SettingsStore) : CommandGroup {
    override val commands =
        listOf(
            command(Commands.config) { config() },
            command(Commands.theme) {
                if (action == "set") {
                    store.update(Themes.values(operand()))
                    out("Theme: ${operand()}")
                } else out(Themes.presets.keys.joinToString("\n"))
            },
            command(Commands.help) {
                out(if (operands.isEmpty()) overview() else Cli.help(operand(), text = ::key))
            },
        )

    private fun known(key: String): String =
        key.takeIf { it in Settings.byKey } ?: throw CliUsageException("config: unknown setting $key")

    private suspend fun CommandCall.config() {
        when (action) {
            "list" ->
                out(
                    Settings.all
                        .filter { it.key.startsWith(operands.firstOrNull().orEmpty()) }
                        .joinToString("\n") { "${it.key}: ${settings.string(it.key)}" }
                )
            "get" -> out(settings.string(known(operand())))
            "set" -> {
                val key = known(operand(0))
                val value = operands.drop(1).joinToString(" ")
                when (key) {
                    // A preset sets the colors too, as `theme set` does.
                    Settings.theme.key -> store.update(Themes.values(value))
                    // Only importing or removing a font file sets it.
                    Settings.customFont.key -> throw CliUsageException("config: $key is set by the font screen")
                    else -> store.set(key, value)
                }
                out("$key = ${store.snapshot().string(key)}")
            }
            "reset" -> {
                store.reset(known(operand()))
                out("Reset ${operand()}")
            }
            else -> {
                val category = operands.firstOrNull()
                if (category != null && category !in Settings.categories)
                    throw CliUsageException("config: unknown category: $category")
                navigate(Route.SettingsPage(category.orEmpty()))
            }
        }
    }

    /** `help` without a command: grouped, one short line per command, narrow enough for phones. */
    private fun CommandCall.overview(): String = buildString {
        Commands.sections.forEach { (title, specs) ->
            append("\u001b[1m").append(key(title)).append("\u001b[0m\n")
            specs.forEach { append("  ").append(it.name.padEnd(14)).append(key(it.summary)).append('\n') }
            append('\n')
        }
        append(key("label_help_footer"))
    }
}
