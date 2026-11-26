package io.luftaquila.hometty.core

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The lines shown fixed above the terminal (like T-UI's status lines): the
 * [Settings.statusLines] format, one line per line, with its placeholders filled in.
 */
object StatusLines {
    /** {time:PATTERN}: the time in a java.time pattern such as HH:mm:ss or yyyy-MM-dd E. */
    private val timePattern = Regex("""\{time:([^}]*)\}""")
    private val time = DateTimeFormatter.ofPattern("HH:mm")
    private val date = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** Whether [format] shows seconds, so it must be drawn again every second. */
    fun showsSeconds(format: String) =
        timePattern.findAll(format).any { match -> match.groupValues[1].any { it == 's' || it == 'S' } }

    /**
     * [format] with {time}, {date} and {time:PATTERN} as of [now], and each other {name} in
     * [values] replaced; a pattern Java cannot use stays as typed. No lines when it is blank.
     */
    fun render(format: String, values: Map<String, String>, now: ZonedDateTime, locale: Locale): List<String> {
        if (format.isBlank()) return emptyList()
        var text =
            timePattern.replace(format) { match ->
                runCatching { DateTimeFormatter.ofPattern(match.groupValues[1], locale).format(now) }
                    .getOrDefault(match.value)
            }
        text = text.replace("{time}", time.format(now)).replace("{date}", date.format(now))
        values.forEach { (name, value) -> text = text.replace("{$name}", value) }
        return text.lines()
    }
}
