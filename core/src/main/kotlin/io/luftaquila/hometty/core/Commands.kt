package io.luftaquila.hometty.core

/** Quoting for shell code the launcher writes itself. The launcher never parses shell text. */
object Shell {
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun render(arguments: List<String>) = arguments.joinToString(" ", transform = ::quote)

    /** A reference to the positional parameters: $1, ${1}, $@, $*, $#. */
    private val positional = Regex("""\$\{?[0-9@*#]""")

    /**
     * Whether [body] refers to the positional parameters where the shell expands them: not in
     * single quotes (awk '{print $1}') and not escaped (\$1).
     */
    private fun usesArguments(body: String): Boolean {
        val expanded = StringBuilder()
        var quoted = false
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                quoted -> {
                    if (c == '\'') quoted = false
                    expanded.append(' ')
                }
                c == '\\' && i + 1 < body.length -> {
                    expanded.append("  ")
                    i++
                }
                c == '\'' -> {
                    quoted = true
                    expanded.append(' ')
                }
                else -> expanded.append(c)
            }
            i++
        }
        return positional.containsMatchIn(expanded)
    }

    /**
     * Shell code that runs the saved alias [body] in the home shell with the alias's own arguments
     * as $1, ${1}... Argument values stay literal: one value is one argument and is not expanded
     * as a path pattern. A body without parameter references gets the arguments at its end. A
     * body that calls the alias's own name (alias ls='ls -a') means the command the alias hides,
     * as shell aliases do: it is looked up past the launcher's names ($HOMETTY_COMMANDS). Any other
     * alias already running further up the same chain stops with status 1.
     */
    fun alias(name: String, body: String): String {
        val quoted = quote(name)
        val arguments = if (usesArguments(body)) "" else " \"\$@\""
        return "case \":\${HOMETTY_ALIAS_CHAIN-}\" in *:$quoted)\n" +
            "  case \":\$PATH:\" in *:\"\${HOMETTY_COMMANDS:-/}\":*)\n" +
            "    PATH=\$(IFS=:; p=; for d in \$PATH; do " +
            "[ \"\$d\" = \"\$HOMETTY_COMMANDS\" ] || p=\"\$p\${p:+:}\$d\"; done; printf %s \"\$p\")\n" +
            "    export PATH; exec $quoted \"\$@\";;\n" +
            "  esac;;\n" +
            "esac\n" +
            "case \":\${HOMETTY_ALIAS_CHAIN-}:\" in *:$quoted:*) " +
            "printf '%s\\n' ${quote("$name: alias cycle")} >&2; exit 1;; esac\n" +
            "export HOMETTY_ALIAS_CHAIN=\"\${HOMETTY_ALIAS_CHAIN-}:\"$quoted\n" +
            "IFS=; set -f\n" +
            body + arguments
    }
}

/**
 * A launcher command's contract: name, help summary, accepted forms and its `help` section
 * ([group], a resource key).
 */
data class CommandSpec(
    val name: String,
    val summary: String,
    val forms: List<CliForm>,
    val group: String,
) {
    val actions
        get() = forms.map { it.action }.filter { it.isNotEmpty() }

    val usage
        get() = Cli.help(name)
}

/** Every launcher command. Implementations refer to these definitions, never to bare names. */
object Commands {
    /**
     * One word, one meaning: no command here reuses a shell builtin or BusyBox applet name for
     * something else. These words reach the shell as functions that call the command (the shell
     * setup defines them), so they get no command file of their own.
     */
    val shellFunctions = setOf("alias", "unalias", "help", "su")

    /** BusyBox ash builtins; persistent aliases cannot take these names. */
    val shellBuiltins =
        (". : [ [[ alias bg break cd chdir command continue echo eval exec exit export false fg " +
                "getopts hash help history jobs kill let local printf pwd read readonly return " +
                "set shift sleep source test times trap true type ulimit umask unalias unset wait")
            .split(' ')
            .toSet()

    private const val APPS = "label_help_apps"
    private const val PEOPLE = "label_help_people"
    private const val DEVICE = "label_help_device"
    private const val TOOLS = "label_help_tools"
    private const val LAUNCHER = "label_help_launcher"

    private fun form(
        action: String = "",
        operands: String = "",
        min: Int = 0,
        max: Int = min,
        vararg options: CliOption,
        complete: Operand? = null,
    ) = CliForm(action, operands, min, max, options.toList(), complete)

    private fun command(name: String, summary: String, group: String, vararg forms: CliForm) =
        CommandSpec(name, summary, forms.toList(), group)

    private fun simple(name: String, summary: String, group: String) =
        command(name, summary, group, form())

    /** Opens Android's settings for a radio: apps can no longer switch them themselves. */
    private fun panel(name: String, summary: String) = simple(name, summary, DEVICE)

    // Apps
    val apps =
        command(
            "apps",
            "label_applications",
            APPS,
            form(operands = "[QUERY]", max = 1, options = arrayOf(Cli.all, Cli.long, Cli.gui)),
            form("list", "[QUERY]", 0, 1, Cli.all, Cli.long),
            *listOf("launch", "hide", "show", "favorite", "reset")
                .map { form(it, "APP", 1, 1, Cli.quiet, complete = Operand.APP) }
                .toTypedArray(),
            *listOf("info", "uninstall", "store")
                .map { form(it, "APP", 1, 1, complete = Operand.APP) }
                .toTypedArray(),
        )
    val alias =
        command(
            "alias",
            "label_persistent_aliases",
            APPS,
            form(operands = "[NAME[=COMMAND] ...]", max = 256, options = arrayOf(Cli.quiet)),
        )
    val unalias =
        command(
            "unalias",
            "label_remove_persistent_aliases",
            APPS,
            form(operands = "NAME...", min = 1, max = 256, complete = Operand.ALIAS),
        )
    val search =
        command(
            "search",
            "label_web_search",
            APPS,
            form(operands = "QUERY...", min = 1, max = 256, options = arrayOf(Cli.engine)),
        )
    val open = command("open", "label_open_2", APPS, form(operands = "[URL]", max = 1))
    val share = command("share", "label_share", APPS, form(operands = "TEXT", min = 1))

    // People
    val call = command("call", "label_call", PEOPLE, form(operands = "NAME_OR_NUMBER", min = 1))
    val contacts =
        command(
            "contacts",
            "label_contacts",
            PEOPLE,
            form(operands = "[QUERY]", max = 1),
            form("list", "[QUERY]", max = 1),
            form("add"),
            form("edit", "ID", 1),
        )

    // Device
    val wifi = panel("wifi", "label_toggle_wifi")
    val bluetooth = panel("bluetooth", "label_toggle_bluetooth")
    val data = panel("data", "label_toggle_data")
    val airplane = panel("airplane", "label_toggle_airplane")
    val brightness =
        command("brightness", "label_brightness", DEVICE, form(operands = "PERCENT|auto", min = 1))
    val volume =
        command(
            "volume",
            "label_volume",
            DEVICE,
            form(operands = "[PERCENT|normal|silent|vibrate]", max = 1),
        )
    val flash = command("flash", "label_torch", DEVICE, form(operands = "[on|off]", max = 1))
    val vibrate =
        command("vibrate", "label_vibrate", DEVICE, form(operands = "[MILLISECONDS]", max = 1))
    val location = simple("location", "label_location", DEVICE)
    val status = simple("status", "label_device_status", DEVICE)

    // Tools
    val notes =
        command(
            "notes",
            "label_notes",
            TOOLS,
            form(options = arrayOf(Cli.gui, Cli.limit)),
            form("list", options = arrayOf(Cli.limit)),
            form("add", "TEXT", 1, 1, Cli.quiet),
            form("set", "ID TEXT", 2, 2, Cli.quiet),
            *listOf("rm", "lock", "unlock", "copy").map { form(it, "ID", 1, 1, Cli.quiet) }.toTypedArray(),
        )
    val calc =
        command("calc", "label_calculator", TOOLS, form(operands = "[EXPRESSION...]", max = 256))
    /** Controls the player another app is running. */
    val music =
        command(
            "music",
            "label_music",
            TOOLS,
            form(),
            *listOf("play", "pause", "next", "previous").map { form(it) }.toTypedArray(),
        )
    val rss =
        command(
            "rss",
            "label_feeds",
            TOOLS,
            form(options = arrayOf(Cli.gui)),
            form("list"),
            form("add", "NAME URL", 2, 2, Cli.quiet),
            form("rm", "NAME", 1, 1, Cli.quiet, complete = Operand.FEED),
            form("show", "NAME", 1, complete = Operand.FEED),
            form("update", "[NAME]", max = 1, complete = Operand.FEED),
        )
    // Launcher
    val config =
        command(
            "config",
            "label_settings",
            LAUNCHER,
            form(operands = "[CATEGORY]", max = 1, complete = Operand.CATEGORY),
            form("list", "[PREFIX]", max = 1, complete = Operand.SETTING),
            form("get", "KEY", 1, complete = Operand.SETTING),
            form("set", "KEY VALUE", 2, 2, Cli.quiet, complete = Operand.SETTING),
            form("reset", "KEY", 1, 1, Cli.quiet, complete = Operand.SETTING),
        )
    val theme =
        command(
            "theme",
            "label_themes",
            LAUNCHER,
            form(),
            form("set", "PRESET", 1, 1, Cli.quiet, complete = Operand.THEME),
        )
    val help =
        command(
            "help",
            "label_command_help",
            LAUNCHER,
            form(operands = "[COMMAND]", max = 1, complete = Operand.COMMAND),
        )

    /** In `help` order: sections in order of first appearance, commands in listed order. */
    val all =
        listOf(
            apps, alias, unalias, search, open, share,
            call, contacts,
            wifi, bluetooth, data, airplane, brightness, volume, flash, vibrate, location,
            status,
            notes, calc, music, rss,
            config, theme, help,
        )
    val byName = all.associateBy { it.name }

    /** `help` sections: every command, grouped. */
    val sections: Map<String, List<CommandSpec>> = all.groupBy { it.group }
}

object SearchIndex {
    private val initials = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"

    fun initials(text: String) =
        text.map { if (it in '가'..'힣') initials[(it.code - 0xAC00) / 588] else it }.joinToString("")

    fun score(query: String, text: String): Int {
        val q = query.trim().lowercase()
        val t = text.lowercase()
        return when {
            q.isEmpty() -> 1
            t == q -> 100
            t.startsWith(q) -> 80
            t.contains(q) -> 60
            initials(t).contains(q) -> 40
            else -> 0
        }
    }
}
