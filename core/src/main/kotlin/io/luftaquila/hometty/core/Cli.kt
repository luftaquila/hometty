package io.luftaquila.hometty.core

/** One schema drives argv validation, help and completion. Shell expansion happens before this. */
data class CliOption(
    val name: String,
    val short: Char? = null,
    val value: String? = null,
    val description: String,
    /** The values a valued option accepts; empty accepts any. */
    val choices: List<String> = emptyList(),
) {
    val spelling
        get() = (short?.let { "-$it, " } ?: "    ") + "--$name" + (value?.let { "=$it" } ?: "")
}

/** What an operand names, so TAB can offer the existing values. */
enum class Operand {
    APP,
    ALIAS,
    FEED,
    SETTING,
    CATEGORY,
    THEME,
    COMMAND,
}

data class CliForm(
    val action: String = "",
    val operands: String = "",
    val min: Int = 0,
    val max: Int = min,
    val options: List<CliOption> = emptyList(),
    /** Completes the first operand, or every operand when [operands] ends with "...". */
    val complete: Operand? = null,
)

data class CliInvocation(
    val name: String,
    val action: String,
    val operands: List<String>,
    val options: Map<String, String>,
) {
    fun has(name: String) = name in options

    fun value(name: String) = options[name]
}

class CliUsageException(message: String) : IllegalArgumentException(message)

class CommandNotFoundException(name: String) : IllegalArgumentException("$name: command not found")

object Cli {
    /**
     * One record for the terminal: [title] in bold, then each field on its own line as
     * "name: value", so nothing runs on past a phone's width.
     */
    fun record(title: String, vararg fields: Pair<String, String>): String =
        "\u001b[1m$title\u001b[0m" + fields.joinToString("") { (name, value) -> "\n  $name: $value" }

    val help = CliOption("help", 'h', description = "label_show_help_and_exit")
    val version = CliOption("version", 'V', description = "label_show_version_and_exit")
    val gui = CliOption("gui", 'g', description = "label_open_graphical_interface")
    val quiet = CliOption("quiet", 'q', description = "label_suppress_successful_output")
    val all = CliOption("all", 'a', description = "label_include_hidden_apps")
    val long = CliOption("long", 'l', description = "label_print_ids_names_and_launches")
    val engine =
        CliOption(
            "engine",
            'e',
            "ENGINE",
            "label_search_provider_google_duckduckgo_youtube",
            listOf("google", "duckduckgo", "youtube"),
        )
    val limit = CliOption("limit", 'n', "COUNT", "label_maximum_output_records")

    fun parse(argv: List<String>): CliInvocation {
        val name = argv.firstOrNull() ?: "help"
        return parse(Commands.byName[name] ?: throw CommandNotFoundException(name), argv)
    }

    fun parse(spec: CommandSpec, argv: List<String>): CliInvocation {
        val name = spec.name
        val options = mutableMapOf<String, String>()
        val operands = mutableListOf<String>()
        var action = ""
        var chooseAction = true
        var optionMode = true
        val supported = (spec.forms.flatMap { it.options } + help + version).distinctBy { it.name }
        fun fail(message: String): Nothing =
            throw CliUsageException("$name: $message\nTry '$name --help'.")
        var i = 1
        fun save(option: CliOption, attached: String?) {
            val value =
                if (option.value != null)
                    attached
                        ?: argv.getOrNull(++i)
                        ?: fail("--${option.name} requires ${option.value}")
                else {
                    if (attached != null) fail("--${option.name} takes no value")
                    "true"
                }
            options[option.name] = value
        }
        while (i < argv.size) {
            val token = argv[i]
            when {
                optionMode && token == "--" -> {
                    optionMode = false
                    chooseAction = false
                }
                optionMode && token.startsWith("--") -> {
                    val key = token.substring(2).substringBefore('=')
                    val option = supported.find { it.name == key } ?: fail("unknown option --$key")
                    save(option, if ('=' in token) token.substringAfter('=') else null)
                }
                optionMode && token.startsWith('-') && token != "-" -> {
                    var j = 1
                    while (j < token.length) {
                        val option =
                            supported.find { it.short == token[j] }
                                ?: fail("unknown option -${token[j]}")
                        val attached =
                            if (option.value != null && j + 1 < token.length) token.substring(j + 1)
                            else null
                        save(option, attached)
                        if (option.value != null) break
                        j++
                    }
                }
                chooseAction && token in spec.actions -> {
                    action = token
                    chooseAction = false
                }
                else -> {
                    operands += token
                    optionMode = false
                    chooseAction = false
                }
            }
            i++
        }
        val form = spec.forms.find { it.action == action } ?: fail("subcommand required")
        if ("help" !in options && "version" !in options) {
            val allowed = form.options.map { it.name }.toSet()
            options.keys
                .firstOrNull { it !in allowed }
                ?.let { fail("--$it is not valid for ${action.ifEmpty { name }}") }
            if (operands.size !in form.min..form.max)
                fail("expected ${form.operands.ifEmpty { "no operands" }}")
            options["limit"]?.let {
                if (it.toIntOrNull()?.let { n -> n in 1..10000 } != true)
                    fail("--limit must be 1..10000")
            }
            for (option in form.options.filter { it.choices.isNotEmpty() }) {
                val value = options[option.name] ?: continue
                if (value !in option.choices) fail("unknown ${option.name}: $value")
            }
        }
        return CliInvocation(name, action, operands, options)
    }

    fun help(name: String, action: String = "", text: (String) -> String = { it }): String {
        val spec = Commands.byName[name] ?: throw CommandNotFoundException(name)
        val forms = if (action.isEmpty()) spec.forms else spec.forms.filter { it.action == action }
        return buildString {
            appendLine("${spec.name} — ${text(spec.summary)}")
            appendLine(text("cli_usage"))
            forms.forEach { appendLine("  $name ${it.action} [options] ${it.operands}".trimEnd()) }
            appendLine(text("cli_options"))
            (forms.flatMap { it.options } + help + version)
                .distinctBy { it.name }
                .forEach { appendLine("  ${it.spelling}: ${text(it.description)}") }
            append(text("cli_option_order"))
        }
    }

    /** Tolerant lexer for completion only; never evaluates shell text. */
    fun completionWords(line: String): List<String> {
        val words = mutableListOf<String>()
        val word = StringBuilder()
        var quote: Char? = null
        var escaped = false
        var started = false
        fun flush() {
            if (started) {
                words += word.toString()
                word.clear()
                started = false
            }
        }
        for (c in line) {
            when {
                escaped -> {
                    word.append(c)
                    started = true
                    escaped = false
                }
                c == '\\' && quote != '\'' -> {
                    escaped = true
                    started = true
                }
                quote != null -> {
                    if (c == quote) quote = null else word.append(c)
                }
                c == '\'' || c == '"' -> {
                    quote = c
                    started = true
                }
                c in ";|&\n" -> {
                    words.clear()
                    word.clear()
                    started = false
                }
                c.isWhitespace() -> flush()
                else -> {
                    word.append(c)
                    started = true
                }
            }
        }
        if (started) flush() else words += ""
        return words
    }

    /**
     * Candidates for the last of [words] (command, then arguments). Operands naming saved or
     * installed things ask [dynamic]; the rest come from the command definitions.
     */
    fun complete(
        words: List<String>,
        dynamic: (Operand) -> List<String> = { emptyList() },
    ): List<String> {
        if ("--" in words.dropLast(1)) return emptyList()
        val prefix = words.lastOrNull().orEmpty()
        if (words.size <= 1) return Commands.all.map { it.name }.filter { it.startsWith(prefix) }
        val spec = Commands.byName[words.first()] ?: return emptyList()
        val middle = words.drop(1).dropLast(1)
        val action = middle.firstOrNull { it in spec.actions }.orEmpty()
        val form = spec.forms.find { it.action == action } ?: return emptyList()
        val options = form.options + help + version
        val previous = middle.lastOrNull()
        val valued =
            options.find {
                it.value != null && (previous == "--${it.name}" || previous == "-${it.short}")
            }
        val values =
            when {
                valued != null -> valued.choices
                prefix.startsWith('-') ->
                    options.flatMap { listOfNotNull("--${it.name}", it.short?.let { c -> "-$c" }) }
                else -> {
                    val position = middle.count { it != action && !it.startsWith('-') }
                    val actions = if (action.isEmpty() && position == 0) spec.actions else emptyList()
                    val kind = form.complete.takeIf { position == 0 || form.operands.endsWith("...") }
                    actions +
                        when (kind) {
                            null -> emptyList()
                            Operand.SETTING -> Settings.all.map { it.key }
                            Operand.CATEGORY -> Settings.categories
                            Operand.THEME -> Themes.presets.keys.toList()
                            Operand.COMMAND -> Commands.all.map { it.name }
                            else -> dynamic(kind)
                        }
                }
            }
        return values.filter { it.startsWith(prefix, ignoreCase = true) }.distinct().sorted()
    }
}
