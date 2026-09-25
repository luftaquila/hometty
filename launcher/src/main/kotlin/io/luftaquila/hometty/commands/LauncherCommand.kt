package io.luftaquila.hometty.commands

import io.luftaquila.hometty.Route
import io.luftaquila.hometty.core.CliInvocation
import io.luftaquila.hometty.core.CliOption
import io.luftaquila.hometty.core.CliUsageException
import io.luftaquila.hometty.core.CommandSpec
import io.luftaquila.hometty.core.Commands
import io.luftaquila.hometty.core.SettingsSnapshot

/** What running a launcher command produces, in order. */
sealed interface CommandEvent {
    data class Output(val text: String, val error: Boolean = false) : CommandEvent

    data class Navigate(val destination: Route) : CommandEvent

    /** Shell code the calling CLI runs in place, in the home shell, with its own arguments. */
    data class Exec(val script: String) : CommandEvent

    data class Finished(val code: Int) : CommandEvent
}

/** Text resources for command output; commands never need an Android context for them. */
interface Texts {
    fun string(id: Int, vararg arguments: Any): String

    /** A metadata resource key such as a command summary; unknown keys come back unchanged. */
    fun key(key: String): String

    fun message(error: Throwable): String
}

/** The shell's command names (launcher commands, aliases, apps) as files on its PATH. */
interface CommandNames {
    /** Makes the files match the saved aliases and installed apps now. */
    suspend fun refresh()
}

/** One launcher command: its definition and what it does. */
interface LauncherCommand {
    val spec: CommandSpec

    /** The screen `--gui` opens; null when the command has none. */
    val gui: Route?
        get() = null

    suspend fun run(call: CommandCall)
}

/** Commands that share dependencies. Each group lists its commands with their definitions. */
interface CommandGroup {
    val commands: List<LauncherCommand>
}

fun command(
    spec: CommandSpec,
    gui: Route? = null,
    run: suspend CommandCall.() -> Unit,
): LauncherCommand =
    object : LauncherCommand {
        override val spec = spec
        override val gui = gui

        override suspend fun run(call: CommandCall) = call.run()
    }

/** One run of a command: its parsed arguments and where its output goes. */
class CommandCall(
    val spec: CommandSpec,
    val invocation: CliInvocation,
    /** Settings when the command started. */
    val settings: SettingsSnapshot,
    private val texts: Texts,
    private val emit: suspend (CommandEvent) -> Unit,
) {
    val action: String
        get() = invocation.action

    val operands: List<String>
        get() = invocation.operands

    /** All operands as one value: app names, queries and note texts may contain spaces. */
    val text: String
        get() = operands.joinToString(" ")

    fun has(option: CliOption) = invocation.has(option.name)

    fun value(option: CliOption) = invocation.value(option.name)

    fun operand(index: Int = 0): String = operands.getOrNull(index) ?: usage()

    fun usage(): Nothing = throw CliUsageException(spec.usage)

    fun string(id: Int, vararg arguments: Any) = texts.string(id, *arguments)

    fun key(key: String) = texts.key(key)

    /** Prints [value] unless it is blank or --quiet is given. */
    suspend fun out(value: Any?) {
        val text = value?.toString()
        if (text.isNullOrBlank() || invocation.has("quiet")) return
        emit(CommandEvent.Output(text))
    }

    /** Prints [records], each one or more lines; --limit keeps the first that many records. */
    suspend fun outRecords(records: List<String>) {
        val limit = invocation.value("limit")?.toInt()
        out((if (limit == null) records else records.take(limit)).joinToString("\n"))
    }

    suspend fun navigate(destination: Route) = emit(CommandEvent.Navigate(destination))

    suspend fun exec(script: String) = emit(CommandEvent.Exec(script))
}

/** The implementation of every definition in [Commands], by name. */
class CommandRegistry(commands: List<LauncherCommand>) {
    private val byName: Map<String, LauncherCommand>

    init {
        val duplicates = commands.groupBy { it.spec.name }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Commands implemented twice: $duplicates" }
        byName = commands.associateBy { it.spec.name }
    }

    operator fun get(name: String): LauncherCommand? = byName[name]

    /** Definitions without an implementation; empty in a complete build. */
    val missing: List<String>
        get() = Commands.all.map { it.name }.filter { it !in byName }
}
