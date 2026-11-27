package io.luftaquila.hometty.core

import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class StatusConditionsTest {
    private val now = ZonedDateTime.parse("2026-11-28T09:12:08+09:00")
    private val values = mapOf("battery" to "64%", "batteryPercent" to "64", "charging" to "",
        "network" to "Wi-Fi", "memoryFreeGiB" to "0.75", "custom.load" to "55", "weatherError" to "")
    private fun rows(format: String, extra: Map<String, String> = emptyMap()) = StatusLines.rows(format, values + extra, now, Locale.ROOT)
    private fun text(format: String, extra: Map<String, String> = emptyMap()) = rows(format, extra).joinToString("\n") { it.plainText }

    @Test fun arbitraryValuesArithmeticBooleanLogicAndNestedBranches() {
        val format = "{if (custom.load * 2 >= 100 && network == 'Wi-Fi') || charging}{battery}{else}{if empty(weatherError)}OK{else}{weatherError}{end}{end}"
        assertEquals("64%", text(format))
        assertEquals("OK", text(format, mapOf("custom.load" to "12")))
        assertEquals("unavailable", text(format, mapOf("network" to "offline", "weatherError" to "unavailable")))
        assertEquals("64%", text(format, mapOf("network" to "mobile", "charging" to "충전 중")))
        assertEquals("yes", text("{if 2 + 3 * 4 == 14 && 9 % 2 == 1 && -3 < -2}yes{end}"))
        assertEquals("yes", text("{if false && true || !false}yes{end}"))
    }

    @Test fun missingValuesEmptyStringsAndFailedCalculationsHaveDefinedBehavior() {
        assertEquals("missing", text("{if exists(unknown)}present{else}missing{end}"))
        assertEquals("empty", text("{if empty(unknown) && empty(weatherError)}empty{end}"))
        assertEquals("safe", text("{if unknown < 20 || 1 / 0 > 0}bad{else}safe{end}"))
        assertEquals("safe", text("{if batteryPercent == '64'}bad{else}safe{end}"))
        assertEquals("off", text("{if flag}on{else}off{end}", mapOf("flag" to "false")))
        assertEquals("yes", text("{if unknown == null && !unknown}yes{end}"))
    }

    @Test fun stringFunctionsQuotesAndBracesAreParsedAsValues() {
        assertEquals("yes", text("{if contains(network, '-') && startsWith(network, 'Wi') && endsWith(network, 'Fi')}yes{end}"))
        assertEquals("yes", text("""{if label == 'a\'b}'}yes{end}""", mapOf("label" to "a'b}")))
        assertEquals("{if false} literal", text("""\{if false\} literal"""))
        assertEquals("{if true}{color:#FF0000}INJECT{/color}{end}{right}",
            text("{weather}", mapOf("weather" to "{if true}{color:#FF0000}INJECT{/color}{end}{right}")))
    }

    @Test fun hiddenRowsKeepSourceLineNumbersColorsAndMultilineLanes() {
        val format = "head\n  {if false}hide{end}\n{if true}\n{color:#FF5555}{weather}{/color}{right}R\n{end}\ntail"
        assertEquals(listOf(
            StatusRow(0, "head"),
            StatusRow(3, "A", "R", listOf(StatusColorSpan(0, 1, "#FF5555"))),
            StatusRow(3, "B", "", listOf(StatusColorSpan(0, 1, "#FF5555"))),
            StatusRow(5, "tail"),
        ), rows(format, mapOf("weather" to "A\nB")))
        assertTrue(rows("{if false}gone{end}").isEmpty())
        assertEquals("C Z", text("C{if false}hidden{end} Z"))
        assertEquals(listOf(StatusRow(0, "head"), StatusRow(1, ""), StatusRow(2, "tail")), rows("head\n\ntail"))
    }

    @Test fun colorsAreScopedAndRightAlignmentCanBeConditional() {
        val row = rows("{color:#FF000080}A{color:#00FF00}B{/color}C{/color}D{if true}{right}{color:#0000FF}R{/color}{end}").single()
        assertEquals("ABCD", row.left)
        assertEquals("R", row.right)
        assertEquals(listOf(StatusColorSpan(0, 1, "#FF000080"), StatusColorSpan(1, 2, "#00FF00"), StatusColorSpan(2, 3, "#FF000080")), row.leftColors)
        assertEquals(listOf(StatusColorSpan(0, 1, "#0000FF")), row.rightColors)
        assertEquals(StatusRow(0, "L"), rows("L{if false}{right}R{end}").single())
    }

    @Test fun clockAndWeatherDependenciesIncludeConditions() {
        assertEquals("weekend 9:12", text("{if weekday >= 6 && hour < 12}weekend {hour}:{minute}{end}"))
        assertTrue(StatusLines.showsSeconds("{if second % 2 == 0}blink{end}"))
        assertTrue(StatusLines.usesWeather("{if weatherAvailable}clear{else}unknown{end}"))
        assertFalse(StatusLines.usesWeather("{if networkConnected}connected{end}"))
    }

    @Test fun invalidDraftsAreReadableButCannotBeSaved() {
        val bad = listOf("{if batteryPercent <}x{end}", "{if true}x", "{else}", "{/color}",
            "{color:red}x{/color}", "{color:#FF0000}x", "{if contains(network)}x{end}",
            "{if unsupported(network)}x{end}", "{if true}".repeat(34) + "x" + "{end}".repeat(34),
            "{if " + "!".repeat(40) + "true}x{end}")
        bad.forEach { format ->
            assertTrue(format, runCatching { Settings.statusLines.definition.validate(format) }.exceptionOrNull() is UserMessage)
            assertTrue(rows(format).isNotEmpty())
        }
        val failure = runCatching { StatusLines.validate("ok\n{if true}missing") }.exceptionOrNull() as UserMessage
        assertEquals("error_status_format", failure.key)
        assertEquals(2, failure.arguments[0])
    }
}
