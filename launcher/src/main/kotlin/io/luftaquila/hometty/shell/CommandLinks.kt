package io.luftaquila.hometty.shell

import android.content.Context
import android.system.Os
import dagger.hilt.android.qualifiers.ApplicationContext
import io.luftaquila.hometty.commands.CommandNames
import io.luftaquila.hometty.core.AppNames
import io.luftaquila.hometty.core.Commands
import io.luftaquila.hometty.core.EntryStore
import io.luftaquila.hometty.runtime.AppCatalog
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Launcher commands, aliases and app names as links to the CLI, so the real shell finds them on
 * its PATH. Commands and aliases come before BusyBox; app names come last.
 */
@Singleton
class CommandLinks
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val entries: EntryStore,
    private val catalog: AppCatalog,
) : CommandNames {
    val commandDirectory = File(context.filesDir, "commands").apply { mkdirs() }

    /** App names and package names: last on PATH, so they never hide a real command. */
    val appDirectory = File(context.filesDir, "apps").apply { mkdirs() }

    private val sync = Mutex()

    override suspend fun refresh() =
        sync.withLock {
            val binary = File(context.applicationInfo.nativeLibraryDir, "libhomettycli.so")
            require(binary.isFile) { "Terminal CLI missing for this ABI" }
            fun usable(names: List<String>) =
                names
                    .filter {
                        it.isNotBlank() &&
                            // A file name holds at most 255 bytes.
                            it.toByteArray().size <= 255 &&
                            '/' !in it &&
                            '\u0000' !in it &&
                            it !in setOf(".", "..")
                    }
                    .toSet()
            val commands =
                usable(
                    Commands.all
                        .filter { it.name !in Commands.shellFunctions }
                        .map { it.name } + "hometty" + entries.list("alias").map { it.name }
                )
            // As in T-UI, a hidden app no longer opens by its name; apps launch still does.
            val hidden = entries.list("app").filter { it.fields["hidden"] == "true" }.map { it.name }.toSet()
            val apps = catalog.apps.value.filter { it.id !in hidden }
            val appNames =
                usable(apps.flatMap { AppNames.commands(it.label) } + apps.map { it.packageName }) -
                    commands -
                    Commands.shellFunctions
            link(commandDirectory, commands, binary)
            link(appDirectory, appNames, binary)
        }

    /** Makes [folder] hold exactly [names], each a link to the CLI [binary]. */
    private fun link(folder: File, names: Set<String>, binary: File) {
        folder
            .listFiles()
            ?.filter { it.name !in names }
            ?.forEach { java.nio.file.Files.deleteIfExists(it.toPath()) }
        // One name the file system refuses must not cost the others their links.
        names.forEach { name ->
            val file = File(folder, name)
            if (runCatching { Os.readlink(file.path) }.getOrNull() != binary.path)
                runCatching {
                    java.nio.file.Files.deleteIfExists(file.toPath())
                    Os.symlink(binary.path, file.path)
                }
        }
    }
}
