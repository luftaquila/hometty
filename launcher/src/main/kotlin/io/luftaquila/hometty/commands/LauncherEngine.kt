package io.luftaquila.hometty.commands

import io.luftaquila.hometty.core.Cli
import io.luftaquila.hometty.core.CliUsageException
import io.luftaquila.hometty.core.CommandNotFoundException
import io.luftaquila.hometty.core.EntryStore
import io.luftaquila.hometty.core.SettingsStore
import io.luftaquila.hometty.core.Shell
import io.luftaquila.hometty.AppCatalog
import io.luftaquila.hometty.BuildConfig
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import io.luftaquila.hometty.core.Settings
import io.luftaquila.hometty.core.get

/**
 * Runs launcher commands from argv, as the shell passes them: finds the implementation, parses
 * the arguments against its definition and reports output and the exit status.
 */
@Singleton
class LauncherEngine
@Inject
constructor(
    private val registry: CommandRegistry,
    private val texts: Texts,
    private val settings: SettingsStore,
    private val entries: EntryStore,
    private val catalog: Provider<AppCatalog>,
) {
    /** Output events, then [CommandEvent.Finished] with 0, 1 (failure), 2 (usage) or 127. */
    fun execute(arguments: List<String>): Flow<CommandEvent> {
        val argv =
            arguments
                .let { if (it.firstOrNull() == "hometty") it.drop(1) else it }
                .ifEmpty { listOf("help") }
        val normalized =
            when (argv.first()) {
                "--help",
                "-h" -> listOf("help") + argv.drop(1)
                "--version",
                "-V" -> listOf("help", "--version") + argv.drop(1)
                else -> argv
            }
        return flow {
                try {
                    require(Shell.render(normalized).length <= 32768) { "Command too long" }
                    dispatch(normalized)
                    emit(CommandEvent.Finished(0))
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    emit(CommandEvent.Output(texts.message(e), true))
                    emit(
                        CommandEvent.Finished(
                            when (e) {
                                is CliUsageException -> 2
                                is CommandNotFoundException -> 127
                                else -> 1
                            }
                        )
                    )
                }
            }
            .flowOn(Dispatchers.IO)
    }

    private suspend fun FlowCollector<CommandEvent>.dispatch(argv: List<String>) {
        val name = argv.first()
        val command = registry[name] ?: return pathTail(argv)
        val invocation = Cli.parse(command.spec, argv)
        val call = CommandCall(command.spec, invocation, settings.snapshot(), texts) { emit(it) }
        when {
            invocation.has("help") -> call.out(Cli.help(name, invocation.action, texts::key))
            invocation.has("version") -> call.out("hometty ${BuildConfig.VERSION_NAME}")
            invocation.has("gui") -> {
                require(invocation.operands.isEmpty()) { "--gui does not take operands" }
                call.navigate(command.gui ?: error("GUI unavailable"))
            }
            else -> command.run(call)
        }
    }

    /**
     * Names the shell found in the launcher's folders that are not commands: saved aliases, run
     * by the shell itself, then app and package names, which open the app.
     */
    private suspend fun FlowCollector<CommandEvent>.pathTail(argv: List<String>) {
        val name = argv.first()
        val alias = entries.find("alias", name)
        if (alias != null) {
            emit(CommandEvent.Exec(Shell.alias(alias.name, alias.fields["command"].orEmpty())))
            return
        }
        val catalog = catalog.get()
        val query = argv.joinToString(" ")
        if (!catalog.names(name, query)) throw CommandNotFoundException(name)
        // A package name such as com.android.chrome opens that app, in the shell and in alias
        // bodies alike; a name typed without quotes arrives as words, joined back here.
        val app = catalog.find(if (catalog.apps.value.any { it.packageName == name }) name else query)
        // As in T-UI, a hidden app opens only through apps launch.
        if (entries.find("app", app.id)?.fields?.get("hidden") == "true") throw CommandNotFoundException(name)
        catalog.launch(app)
        if (settings.snapshot()[Settings.showLaunches])
            emit(CommandEvent.Output("→ ${app.label} (${app.packageName})"))
    }
}
