package io.luftaquila.hometty.core

import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class StatusAppearanceTest {
    private val now = ZonedDateTime.parse("2026-11-27T17:42:08+09:00")

    @Test
    fun existingTemplatesKeepTextOrderBlankLinesAndUnknownPlaceholders() {
        val format = "custom {battery}\n\n  {time:HH:mm:ss} {unknown}  \n"
        val values = mapOf("battery" to "78%")
        assertEquals(StatusLines.render(format, values, now, Locale.ROOT),
            StatusLines.rows(format, values, now, Locale.ROOT).map { it.plainText })
        assertTrue(StatusLines.rows(" \n", values, now, Locale.ROOT).isEmpty())
    }

    @Test
    fun splitIsOptInAndCannotBeInjectedByAPlaceholderValue() {
        val values = mapOf("weather" to "value {right}\nsecond", "battery" to "78%")
        assertEquals(listOf(StatusRow(0, "value {right}"), StatusRow(0, "second")),
            StatusLines.rows("{weather}", values, now, Locale.ROOT))
        assertEquals(listOf(StatusRow(0, "value {right}", "78%"), StatusRow(0, "second", ""), StatusRow(1, "tail")),
            StatusLines.rows("{weather}{right}{battery}\ntail", values, now, Locale.ROOT))
        assertEquals(listOf(StatusRow(0, "", "78%")),
            StatusLines.rows("{right}{battery}", values, now, Locale.ROOT))
    }

    @Test
    fun lineOverridesInheritIndividuallyAndSurviveSerialization() {
        val appearance = StatusAppearance(
            text = StatusTextStyle(fontSize = 16, color = "#AABBCC", alignment = "center", overflow = "wrap"),
            background = "#11223380", padding = 12, lineSpacing = 6, divider = false,
            lines = mapOf(1 to StatusTextStyle(color = "#FF0000", alignment = "right")),
        )
        assertEquals(appearance, StatusAppearance.decode(appearance.encode()))
        assertEquals(appearance.text, appearance.style(0))
        assertEquals(StatusTextStyle(16, "#FF0000", "right", "wrap"), appearance.style(1))
        assertEquals(StatusAppearance(), StatusAppearance.decode("{}"))
    }

    @Test
    fun invalidAppearanceCannotReachPersistedSettings() {
        val invalid = listOf(
            "not json", "{\"padding\":-1}", "{\"lineSpacing\":100}",
            "{\"text\":{\"color\":\"red\"}}", "{\"text\":{\"fontSize\":0}}",
            "{\"text\":{\"fontSize\":37}}", "{\"text\":{\"alignment\":\"random\"}}",
            "{\"text\":{\"overflow\":\"hidden\"}}", "{\"background\":\"#XYZXYZ\"}",
            "{\"lines\":{\"-1\":{}}}", "{\"unknown\":true}",
        )
        invalid.forEach { value ->
            assertTrue(value, runCatching { Settings.statusAppearance.definition.validate(value) }.isFailure)
        }
        listOf("{}", "{\"text\":{\"overflow\":\"ellipsis\"}}", "{\"background\":\"#11223300\"}")
            .forEach { Settings.statusAppearance.definition.validate(it) }
    }
}
