package io.luftaquila.hometty

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.luftaquila.hometty.core.SettingsSnapshot
import io.luftaquila.hometty.resources.R as TextR
import io.luftaquila.hometty.terminal.TerminalSession
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.luftaquila.hometty.core.Settings
import io.luftaquila.hometty.core.get

/** Internal storage root: the shell's default home. */
@Suppress("DEPRECATION")
fun sharedStorage(): File = android.os.Environment.getExternalStorageDirectory()

/**
 * The app's own folder ($HOMETTY_DATA): shell history and .shrc live here whatever the home is, since
 * the app cannot create files at the internal storage root. Also the home when nothing else opens.
 */
fun appFolder(context: Context): File = File(context.filesDir, "home").apply { mkdirs() }

@Singleton
class TerminalHost
@Inject
constructor(
    @ApplicationContext private val context: Context,
    val bridge: ShellBridge,
    private val links: CommandLinks,
    private val notices: TerminalNotices,
) {
    val session = MutableStateFlow<TerminalSession?>(null)

    private val opening = Mutex()

    /** Prompt, launcher functions and .shrc: sourced by the terminal and by one-off runs. */
    private val setupFile = File(context.filesDir, "shell-setup.sh")

    /** The folder the current terminal started in. */
    @Volatile private var home = appFolder(context).path

    /** Variables every home shell gets: the launcher connection, PATH and the app's folder. */
    private fun shellEnvironment() = bridge.environment + ("HOMETTY_DATA" to appFolder(context).path)

    /** Whether setup files left by terminals of earlier processes were deleted. */
    private var cleaned = false

    suspend fun openHome(prefs: SettingsSnapshot): Unit =
        opening.withLock {
            if (session.value != null && session.value?.exit?.value == null) return
            if (!cleaned) {
                cleaned = true
                // A terminal deletes its setup file when it closes, which a killed process never
                // does; files from before this process started belong to no terminal.
                val started =
                    System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime() +
                        android.os.Process.getStartElapsedRealtime()
                context.filesDir
                    .listFiles { file -> file.name.startsWith("shellrc-") && file.lastModified() < started }
                    ?.forEach { it.delete() }
            }
            links.refresh()
            io.luftaquila.hometty.terminal.BusyBox.installApplets(context)
            session.value?.close()
            // A failed start leaves no session, so the home screen offers retry and settings
            // instead of a dead terminal.
            session.value = null
            // A home folder that cannot be entered (removed, mistyped, no access) must not stop
            // the shell: use the app's own folder and say so.
            val data = appFolder(context)
            val requestedHome = prefs[Settings.shellHome].ifBlank { sharedStorage().path }
            home = if (io.luftaquila.hometty.core.usableHomeFolder(requestedHome)) requestedHome else data.path
            val setup = buildString {
                append(io.luftaquila.hometty.core.ShellPrompt.setup(prefs))
                if (home != requestedHome)
                    append(
                        "case \"\$-\" in *i*) printf '%s\\n\\n' " +
                            io.luftaquila.hometty.core.Shell.quote(
                                context.getString(
                                    TextR.string.message_home_folder_fallback,
                                    requestedHome,
                                )
                            ) +
                            ";; esac\n"
                    )
                // The setting's maximum is BusyBox's build limit.
                val shellHistory = prefs[Settings.historyLimit]
                if (prefs[Settings.historyEnabled])
                    append(
                        "export HISTFILE=\"\$HOMETTY_DATA/.sh_history\" HISTSIZE=$shellHistory HISTFILESIZE=$shellHistory\n"
                    )
                else append("unset HISTFILE; HISTSIZE=0\n")
                // BusyBox ash provides UTF-8 line editing, job control, history and native
                // completion.

                append(
                    "su() { hometty __root && command /system/bin/sh -c 'command su \"\$@\"' sh \"\$@\"; }\n"
                )
                // One word, one meaning: help, alias and unalias are the launcher's, which also
                // cover the shell's. alias NAME=VALUE is saved (Settings > Aliases)
                // and works at once; inside $HOMETTY_DATA/.shrc they stay plain shell aliases.
                append("help() { hometty help \"\$@\"; }\n")
                // calc's * is arithmetic, not a file pattern: the alias turns file name expansion
                // off for that line, and the function turns it back on before calc runs.
                append("command alias calc='set -f; __hometty_calc'\n")
                append("__hometty_calc() { set +f; command calc \"\$@\"; }\n")
                append(
                    "alias() {\n" +
                        "  if [ -n \"\$HOMETTY_LOADING\" ]; then command alias \"\$@\"; return; fi\n" +
                        "  if [ \"\$#\" -eq 0 ]; then hometty alias; command alias; return; fi\n" +
                        "  case \"\$*\" in\n" +
                        // The shell remembers where it found a command; forget it, so a new alias
                        // of a name already run takes over at once.
                        "    *=*) hometty alias \"\$@\" && hash -r ;;\n" +
                        "    *) hometty alias \"\$@\" 2>/dev/null || command alias \"\$@\" ;;\n" +
                        "  esac\n" +
                        "}\n"
                )
                // Shell aliases (from .shrc) go quietly; saved ones are the launcher's to remove.
                append(
                    "unalias() {\n" +
                        "  if [ -n \"\$HOMETTY_LOADING\" ]; then command unalias \"\$@\"; return; fi\n" +
                        "  hash -r\n" +
                        "  if command unalias \"\$@\" 2>/dev/null; then hometty unalias \"\$@\" 2>/dev/null; return 0; fi\n" +
                        "  hometty unalias \"\$@\"\n" +
                        "}\n"
                )
                if (prefs[Settings.showHints])
                    append(
                        "case \"\$-\" in *i*) printf 'hometty ${BuildConfig.VERSION_NAME}\\napps: list   config: settings   help\\n\\n';; esac\n"
                    )
                append(
                    "HOMETTY_LOADING=1; [ ! -f \"\$HOMETTY_DATA/.shrc\" ] || . \"\$HOMETTY_DATA/.shrc\"; unset HOMETTY_LOADING\n"
                )
                // A PS1 from .shrc keeps the prompt marks the terminal finds the prompt by.
                val start = "\u001b]133;A\u0007"
                val end = "\u001b]133;B\u0007"
                append(
                    "case \"\$PS1\" in *${io.luftaquila.hometty.core.Shell.quote(start)}*) ;; " +
                        "*) PS1=${io.luftaquila.hometty.core.Shell.quote("\\[$start\\]")}\"\$PS1\"${io.luftaquila.hometty.core.Shell.quote("\\[$end\\]")} ;; esac\n"
                )
            }
            setupFile.writeText(setup)
            session.value =
                TerminalSession(
                    context,
                    null,
                    home,
                    prefs[Settings.maxLines],
                    setup,
                    shellEnvironment(),
                    deferUntilMeasured = true,
                )
            notices.attach(session.value)
        }

    /** The shell's history file, which the shell's history command reads and a full reset deletes. */
    fun historyFile(): File = File(appFolder(context), ".sh_history")

    fun stop() {
        bridge.rootRequested.value = false
        notices.attach(null)
        session.value?.close()
        session.value = null
    }
}

/**
 * Typed before a line the launcher puts at the prompt: Ctrl-E moves to the end of what is typed,
 * Ctrl-U then clears all of it (alone it keeps what follows the cursor).
 */
const val CLEAR_LINE = "\u0005\u0015"
