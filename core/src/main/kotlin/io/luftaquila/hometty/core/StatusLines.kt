package io.luftaquila.hometty.core

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The source line keeps its style even when a placeholder expands to several display lines. */
data class StatusColorSpan(val start: Int, val end: Int, val color: String)
data class StatusRow(val sourceLine: Int, val left: String, val right: String? = null,
    val leftColors: List<StatusColorSpan> = emptyList(), val rightColors: List<StatusColorSpan> = emptyList()) {
    val plainText: String get() = if (right == null) left else "$left $right"
}

/**
 * The lines shown fixed above the terminal (like T-UI's status lines): the
 * [Settings.statusLines] format, one line per line, with its placeholders filled in.
 */
object StatusLines {
    private val templates = linkedMapOf<String, Result<StatusTemplate>>()
    @Synchronized
    private fun compile(format: String): Result<StatusTemplate> = templates.getOrPut(format) {
        if (templates.size >= 12) templates.remove(templates.keys.first())
        runCatching { StatusTemplate.parse(format) }
    }
    fun validate(format: String) { compile(format).getOrThrow() }
    fun usesWeather(format: String): Boolean = compile(format).getOrNull()?.references
        ?.any { it.startsWith("weather") } ?: ("weather" in format)

    /** Layout markers are parsed before values, so placeholder contents cannot change layout. */
    fun rows(format: String, values: Map<String, String>, now: ZonedDateTime, locale: Locale): List<StatusRow> {
        if (format.isBlank()) return emptyList()
        val clock = mapOf("hour" to now.hour.toString(), "minute" to now.minute.toString(),
            "second" to now.second.toString(), "weekday" to now.dayOfWeek.value.toString(),
            "day" to now.dayOfMonth.toString(), "month" to now.monthValue.toString(), "year" to now.year.toString(),
            "time" to time.format(now), "date" to date.format(now))
        val all = values + clock
        val context = all.mapValues { StatusExpression.scalar(it.value) }.toMutableMap<String, Any?>()
        context["charging"] = !values["charging"].isNullOrEmpty()
        values["battery"]?.removeSuffix("%")?.toDoubleOrNull()?.let { context["battery"] = it }
        compile(format).getOrNull()?.let { template ->
            return template.render(context) { renderPlain(it, all, now, locale).joinToString("\n") }
        }
        // Incomplete editor drafts remain readable; validation prevents saving invalid commands.
        return format.lines().flatMapIndexed { index, template ->
            val parts = template.split("{right}", limit = 2)
            val left = renderPlain(parts[0], all, now, locale).ifEmpty { listOf(parts[0]) }
            val right = parts.getOrNull(1)?.let {
                renderPlain(it, all, now, locale).ifEmpty { listOf(it) }
            }
            List(maxOf(left.size, right?.size ?: 0)) { row ->
                StatusRow(index, left.getOrElse(row) { "" }, right?.getOrElse(row) { "" })
            }
        }
    }

    /** {time:PATTERN}: the time in a java.time pattern such as HH:mm:ss or yyyy-MM-dd E. */
    private val timePattern = Regex("""\{time:([^}]*)\}""")
    // One pass: device values and custom charging text cannot introduce more placeholders.
    private val placeholder = Regex(
        timePattern.pattern + """|\{charging:((?:\\[\\|{}]|[^{}\\])*)\}|\{([^{}:]+)\}"""
    )
    private val time = DateTimeFormatter.ofPattern("HH:mm")
    private val date = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** The first unescaped | separates charging/otherwise; the latter defaults to empty. */
    private fun chargingText(pattern: String, charging: Boolean): String {
        val on = StringBuilder()
        val off = StringBuilder()
        var target = on
        var index = 0
        while (index < pattern.length) {
            val character = pattern[index++]
            when {
                character == '\\' && index < pattern.length -> target.append(pattern[index++])
                character == '|' && target === on -> target = off
                else -> target.append(character)
            }
        }
        return (if (charging) on else off).toString()
    }

    /** Whether [format] shows seconds, so it must be drawn again every second. */
    fun showsSeconds(format: String) =
        timePattern.findAll(format).any { match -> match.groupValues[1].any { it == 's' || it == 'S' } } ||
            compile(format).getOrNull()?.references?.contains("second") == true

    /**
     * [format] with date/time, device values and {charging:on|off} filled in. The plain
     * {charging} keeps its localized text. Unknown tokens and invalid time patterns stay as typed.
     */
    fun render(format: String, values: Map<String, String>, now: ZonedDateTime, locale: Locale): List<String> {
        return rows(format, values, now, locale).map { it.plainText }
    }

    private fun renderPlain(format: String, values: Map<String, String>, now: ZonedDateTime, locale: Locale): List<String> {
        if (format.isBlank()) return emptyList()
        val text = placeholder.replace(format) { match ->
            val timeFormat = match.groups[1]?.value
            val chargingFormat = match.groups[2]?.value
            when {
                timeFormat != null ->
                    runCatching { DateTimeFormatter.ofPattern(timeFormat, locale).format(now) }
                        .getOrDefault(match.value)
                chargingFormat != null -> values["charging"]?.let {
                    chargingText(chargingFormat, it.isNotEmpty())
                } ?: match.value
                else -> when (val name = match.groupValues[3]) {
                    "time" -> time.format(now)
                    "date" -> date.format(now)
                    else -> values[name] ?: match.value
                }
            }
        }
        return text.lines()
    }
}
