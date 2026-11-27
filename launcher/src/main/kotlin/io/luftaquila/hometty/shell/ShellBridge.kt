package io.luftaquila.hometty.shell

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import dagger.hilt.android.qualifiers.ApplicationContext
import io.luftaquila.hometty.Route
import io.luftaquila.hometty.commands.CommandEvent
import io.luftaquila.hometty.commands.Completion
import io.luftaquila.hometty.commands.LauncherEngine
import io.luftaquila.hometty.core.CliUsageException
import io.luftaquila.hometty.core.Settings
import io.luftaquila.hometty.core.SettingsStore
import io.luftaquila.hometty.core.get
import io.luftaquila.hometty.resources.R as TextR
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The launcher end of the CLI: the shell runs the APK's CLI through its normal PATH, and the CLI
 * sends its argv here over an app-private socket. Frames back: 1 stdout, 2 stderr, 3 exit code,
 * 4 shell code for the CLI to exec in the home shell.
 */
@Singleton
class ShellBridge
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val engine: LauncherEngine,
    private val completion: Completion,
    private val settings: SettingsStore,
    private val links: CommandLinks,
    private val device: io.luftaquila.hometty.platform.DeviceAccess,
) {
    /** A root shell was asked for through the su function; cleared when the session stops. */
    val rootRequested = MutableStateFlow(false)

    /** Screens commands open; the UI takes them in order. */
    val navigation = Channel<Route>(Channel.BUFFERED)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val address = "hometty-${android.os.Process.myUid()}-${UUID.randomUUID()}"
    private val server = LocalServerSocket(address)
    private val clients = Semaphore(8)

    val environment
        get() =
            mapOf(
                "HOMETTY_SOCKET" to address,
                "HOMETTY_COMPLETER" to
                    File(context.applicationInfo.nativeLibraryDir, "libhomettycli.so").path,
                // Where launcher commands and aliases live, so an alias can look past them.
                "HOMETTY_COMMANDS" to links.commandDirectory.path,
                // The home shell; launcher answers that are shell code (aliases) run in it.
                "HOMETTY_SHELL" to io.luftaquila.hometty.terminal.BusyBox.executable(context).path,
                // One lookup order for every name: launcher commands and aliases, BusyBox,
                // Android's commands, then app names.
                "PATH" to
                    listOf(
                            links.commandDirectory.path,
                            File(context.filesDir, "bin").path,
                            System.getenv("PATH").orEmpty(),
                            links.appDirectory.path,
                        )
                        .filter { it.isNotEmpty() }
                        .joinToString(":"),
            )

    init {
        scope.launch {
            while (isActive) {
                val socket = server.accept()
                if (socket.peerCredentials.uid != android.os.Process.myUid()) {
                    socket.close()
                    continue
                }
                launch { clients.withPermit { serve(socket) } }
            }
        }
    }

    private suspend fun serve(socket: LocalSocket) = coroutineScope {
        socket.use {
            val output = DataOutputStream(it.outputStream)
            fun frame(type: Int, text: String) {
                val bytes = text.toByteArray()
                // Shell code (4) goes whole: the client runs the first such frame it gets.
                val chunk = if (type == 4) bytes.size.coerceAtLeast(1) else 65536
                var offset = 0
                while (offset < bytes.size) {
                    val size = minOf(chunk, bytes.size - offset)
                    output.writeInt(type)
                    output.writeInt(size)
                    output.write(bytes, offset, size)
                    output.flush()
                    offset += size
                }
            }
            var disconnect: Job? = null
            try {
                it.soTimeout = 10000
                val input = DataInputStream(it.inputStream)
                // The client sends at most 4096 arguments of 1 MiB in all.
                val count = input.readInt()
                require(count in 1..4096) { "argument list too long" }
                var total = 0
                val args =
                    List(count) {
                        val size = input.readInt()
                        total += size
                        require(size >= 0 && total <= 1048576) { "argument list too long" }
                        ByteArray(size).also(input::readFully).decodeToString()
                    }
                it.soTimeout = 0
                val request = currentCoroutineContext().job
                disconnect =
                    launch(Dispatchers.IO) {
                        try {
                            input.read()
                            request.cancel()
                        } catch (_: Exception) {}
                    }
                var code = 0
                when (args.first()) {
                    // The su shell function asks before it runs Android's su.
                    "__root" -> {
                        require(settings.snapshot()[Settings.rootEnabled]) {
                            context.getString(
                                TextR.string.message_enable_root_in_config_terminal_first
                            )
                        }
                        rootRequested.value = true
                    }
                    // The shell's line editor asks for TAB candidates.
                    "__complete" -> {
                        require(args.size == 2)
                        frame(1, completion.line(args[1]).joinToString("\n") + "\n")
                    }
                    else ->
                        engine.execute(args).collect { event ->
                            when (event) {
                                is CommandEvent.Output ->
                                    frame(if (event.error) 2 else 1, event.text + "\n")
                                // A screen opened from the background would show up later.
                                is CommandEvent.Navigate ->
                                    if (device.shown) navigation.send(event.destination)
                                    else {
                                        frame(2, context.getString(TextR.string.message_open_launcher_first) + "\n")
                                        code = maxOf(code, 1)
                                    }
                                is CommandEvent.Exec -> frame(4, event.script)
                                is CommandEvent.Finished -> code = maxOf(code, event.code)
                            }
                        }
                }
                output.writeInt(3)
                output.writeInt(code)
                output.flush()
            } catch (e: Exception) {
                if (e !is CancellationException)
                    runCatching {
                        frame(2, "hometty: ${e.message}\n")
                        output.writeInt(3)
                        output.writeInt(if (e is CliUsageException) 2 else 1)
                        output.flush()
                    }
            } finally {
                socket.close()
                disconnect?.cancel()
            }
        }
    }
}
