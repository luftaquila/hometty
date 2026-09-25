package io.luftaquila.hometty.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** What a notification shows: its app and the texts Android gives it. */
data class NotificationContent(
    val packageName: String,
    val app: String,
    val title: String,
    /** The short text, as in a collapsed notification. */
    val text: String,
    /** The full text of an expanded notification; blank when it has none. */
    val bigText: String = "",
    val subText: String = "",
)

/** How an incoming notification is printed in the terminal: its app's rule, or the defaults. */
object NotificationLines {
    /** What a run of a printed notification shows. */
    enum class Part {
        TEXT,
        APP,
    }

    /** A run of a printed notification: format text or one filled-in placeholder. */
    data class Run(val text: String, val part: Part)

    private val placeholder = Regex("\\{(app|package|title|text|bigtext|subtext)\\}")

    /**
     * The terminal text for a notification, or null when its app's [rule] (null: the app has
     * none) or the defaults leave it out. The app name takes the theme's accent; a rule's
     * color colors the whole text instead.
     */
    fun line(rule: Entry?, settings: SettingsSnapshot, content: NotificationContent): String? {
        if (rule == null && !settings[Settings.includeByDefault]) return null
        val fields = rule?.fields.orEmpty()
        if (fields["enabled"] == "false") return null
        val include = fields["include"].orEmpty()
        val exclude = fields["exclude"].orEmpty()
        val subject = "${content.title} ${content.bigText.ifEmpty { content.text }}"
        if (include.isNotBlank() && !Patterns.matches(include, subject)) return null
        if (exclude.isNotBlank() && Patterns.matches(exclude, subject)) return null
        val format = fields["format"].orEmpty().ifBlank { settings[Settings.notificationFormat] }
        val runs = runs(format, content)
        val color = fields["color"].orEmpty()
        if (color.isNotBlank()) return colored(runs.joinToString("") { it.text }, color)
        val app = appColor(settings)
        return runs.joinToString("") {
            when (it.part) {
                Part.APP -> colored(it.text, app)
                Part.TEXT -> it.text
            }
        }
    }

    /**
     * [format] with its placeholders filled in, as plain printable runs: `{app}`, `{package}`,
     * `{title}`, `{text}` (the short text, else the full one), `{bigtext}` (the full text, else
     * the short one) and `{subtext}`.
     */
    fun runs(format: String, content: NotificationContent): List<Run> {
        val values =
            mapOf(
                "app" to content.app,
                "package" to content.packageName,
                "title" to content.title,
                "text" to content.text.ifBlank { content.bigText },
                "bigtext" to content.bigText.ifBlank { content.text },
                "subtext" to content.subText,
            )
        val runs = mutableListOf<Run>()
        var at = 0
        for (match in placeholder.findAll(format)) {
            runs += Run(printable(format.substring(at, match.range.first)), Part.TEXT)
            val name = match.groupValues[1]
            val part = if (name == "app") Part.APP else Part.TEXT
            runs += Run(printable(values.getValue(name).take(4000)), part)
            at = match.range.last + 1
        }
        runs += Run(printable(format.substring(at)), Part.TEXT)
        return trimLines(runs.filter { it.text.isNotEmpty() })
    }

    /** [text] in 24-bit [color] (`#RRGGBB` or `#AARRGGBB`); blank keeps the terminal's color. */
    fun colored(text: String, color: String): String {
        if (color.isBlank()) return text
        val rgb = color.takeLast(6).chunked(2).joinToString(";") { it.toInt(16).toString() }
        return "\u001b[38;2;${rgb}m$text\u001b[39m"
    }

    /** The app name's color: the theme's accent, as in the settings screens. */
    fun appColor(settings: SettingsSnapshot): String = Themes.accent(settings)

    /**
     * The color of the time before each launcher message: the text color faded toward the
     * background, less where the text has little contrast already, so it stays readable.
     */
    fun timeColor(settings: SettingsSnapshot): String {
        val foreground = rgb(settings[Settings.foreground])
        val background = rgb(settings[Settings.background])
        val target = min(4.5, contrast(foreground, background) * 0.55)
        val faded =
            (10 downTo 1)
                .map { step -> mix(foreground, background, step * 0.05) }
                .firstOrNull { contrast(it, background) >= target } ?: foreground
        return "#" + faded.joinToString("") { "%02X".format(it) }
    }

    private fun rgb(color: String): List<Int> = color.takeLast(6).chunked(2).map { it.toInt(16) }

    private fun mix(from: List<Int>, to: List<Int>, amount: Double) =
        from.zip(to) { a, b -> (a + (b - a) * amount).roundToInt() }

    /** WCAG contrast ratio of two colors. */
    private fun contrast(a: List<Int>, b: List<Int>): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun luminance(rgb: List<Int>): Double {
        val (r, g, b) =
            rgb.map {
                val c = it / 255.0
                if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            }
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** [runs] without line breaks before the first text or after the last. */
    private fun trimLines(runs: List<Run>): List<Run> {
        val trimmed = runs.toMutableList()
        while (trimmed.isNotEmpty()) {
            val text = trimmed[0].text.trimStart('\n')
            if (text.isNotEmpty()) {
                trimmed[0] = trimmed[0].copy(text = text)
                break
            }
            trimmed.removeAt(0)
        }
        while (trimmed.isNotEmpty()) {
            val text = trimmed[trimmed.lastIndex].text.trimEnd('\n')
            if (text.isNotEmpty()) {
                trimmed[trimmed.lastIndex] = trimmed[trimmed.lastIndex].copy(text = text)
                break
            }
            trimmed.removeAt(trimmed.lastIndex)
        }
        return trimmed
    }

}

/**
 * Text from outside the app (notifications, feeds, app names, command output) without control
 * characters, so it can only print: an escape sequence in it must not reach the terminal. Line
 * breaks stay; a tab becomes a space.
 */
fun printable(text: String): String =
    buildString {
        text.replace("\r\n", "\n").replace('\r', '\n').forEach { c ->
            when {
                c == '\n' -> append(c)
                c == '\t' -> append(' ')
                c < ' ' || c in '\u007f'..'\u009f' -> {}
                else -> append(c)
            }
        }
    }
