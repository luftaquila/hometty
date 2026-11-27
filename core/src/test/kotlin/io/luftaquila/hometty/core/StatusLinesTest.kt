package io.luftaquila.hometty.core

import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class StatusLinesTest {
    private val now = ZonedDateTime.parse("2026-11-27T17:42:08+09:00")
    private fun values(charging: Boolean) = mapOf(
        "battery" to "64%",
        "charging" to if (charging) "충전 중" else "",
    )

    @Test
    fun chargingTextSupportsSymbolsSpacesAndAnOptionalOtherwiseBranch() {
        val format = "{battery}{charging:+} {charging:⚡|—} [{charging: charging| on battery}] [{charging:|off}] [{charging:}]"
        assertEquals(listOf("64%+ ⚡ [ charging] [] []"),
            StatusLines.render(format, values(true), now, Locale.ROOT))
        assertEquals(listOf("64% — [ on battery] [off] []"),
            StatusLines.render(format, values(false), now, Locale.ROOT))
        assertEquals(listOf("충전 중"), StatusLines.render("{charging}", values(true), now, Locale.ROOT))
        assertEquals(listOf(""), StatusLines.render("{charging}", values(false), now, Locale.ROOT))
    }

    @Test
    fun escapedTextAndDeviceValuesAreLiteralRatherThanMorePlaceholders() {
        val format = """{charging:\{plug\}\|\\| idle \| \{battery\}}"""
        assertEquals(listOf("{plug}|\\"), StatusLines.render(format, values(true), now, Locale.ROOT))
        assertEquals(listOf(" idle | {battery}"), StatusLines.render(format, values(false), now, Locale.ROOT))
        assertEquals(listOf("{charging:+} {battery}"), StatusLines.render(
            "{weather}", values(true) + ("weather" to "{charging:+} {battery}"), now, Locale.ROOT,
        ))
        assertEquals(listOf("{charging:+} {unknown}"),
            StatusLines.render("{charging:+} {unknown}", emptyMap(), now, Locale.ROOT))
    }

    @Test
    fun customChargingTextKeepsLayoutAndSourceLineStyles() {
        val format = "{battery}{charging:+}{right}{charging:충전 중|배터리}\n\n{weather}"
        val weather = "weather" to "{right}{charging:+}"
        assertEquals(listOf(StatusRow(0, "64%+", "충전 중"), StatusRow(1, ""), StatusRow(2, weather.second)),
            StatusLines.rows(format, values(true) + weather, now, Locale.ROOT))
        assertEquals(listOf(StatusRow(0, "64%", "배터리"), StatusRow(1, ""), StatusRow(2, weather.second)),
            StatusLines.rows(format, values(false) + weather, now, Locale.ROOT))
    }
}
