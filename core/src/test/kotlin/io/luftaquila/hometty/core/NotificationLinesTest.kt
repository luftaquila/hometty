package io.luftaquila.hometty.core

import org.junit.Assert.*
import org.junit.Test

class NotificationLinesTest {
    private val defaults = SettingsSnapshot()

    private fun rule(vararg fields: Pair<String, String>) =
        Entry("id", "notificationRule", "Chat", mapOf("package" to "chat.app") + fields)

    private fun content(title: String = "Ann", text: String = "hello", bigText: String = "") =
        NotificationContent("chat.app", "Chat", title, text, bigText, "3 new")

    private fun plain(line: String?) = line?.replace(Regex("\u001b\\[[0-9;]*m"), "")

    @Test
    fun appsWithoutARuleFollowTheDefaultFormatAndSwitch() {
        assertEquals("Chat: Ann - hello", plain(NotificationLines.line(null, defaults, content())))
        val excluded = SettingsSnapshot(values = mapOf(Settings.includeByDefault.key to "false"))
        assertNull(NotificationLines.line(null, excluded, content()))
        // A rule includes its app even when apps without one are left out.
        assertEquals("Chat: Ann - hello", plain(NotificationLines.line(rule(), excluded, content())))
    }

    @Test
    fun theAppNameTakesTheThemeAccent() {
        // Dracula, the default theme, has its own accent.
        assertEquals(
            "\u001b[38;2;189;147;249mChat\u001b[39m: Ann - hello",
            NotificationLines.line(null, defaults, content()),
        )
        // Themes without one use the input color, as the settings screens do.
        val nord = SettingsSnapshot(values = Themes.values("nord"))
        assertEquals("#A3BE8C", NotificationLines.appColor(nord))
    }

    @Test
    fun theTimeFadesTheTextColorAndStaysReadableOnEveryTheme() {
        assertEquals("#909194", NotificationLines.timeColor(defaults))
        Themes.presets.keys.forEach { name ->
            val settings = SettingsSnapshot(values = Themes.values(name))
            val time = NotificationLines.timeColor(settings)
            assertNotEquals(name, settings[Settings.foreground].uppercase(), time)
            assertNotEquals(name, settings[Settings.background].uppercase(), time)
        }
        // Solarized text has little contrast, so its time fades less than Dracula's.
        val solarized = SettingsSnapshot(values = Themes.values("solarized_dark"))
        assertEquals("#556F74", NotificationLines.timeColor(solarized))
    }

    @Test
    fun placeholdersFillTheShortTextByDefaultAndTheOthersOnRequest() {
        val long = content(text = "short", bigText = "short\nand the rest")
        assertEquals("Chat: Ann - short", plain(NotificationLines.line(null, defaults, long)))
        assertEquals(
            "chat.app|short\nand the rest|3 new",
            plain(NotificationLines.line(rule("format" to "{package}|{bigtext}|{subtext}"), defaults, long)),
        )
        // Each falls back to the other when a notification has only one.
        assertEquals(
            "only big|only big",
            plain(
                NotificationLines.line(rule("format" to "{text}|{bigtext}"), defaults, content(text = "", bigText = "only big"))
            ),
        )
        assertEquals(
            "hello|hello",
            plain(NotificationLines.line(rule("format" to "{text}|{bigtext}"), defaults, content())),
        )
        // Nothing is cut short.
        val title = "t".repeat(300)
        assertEquals("Chat: $title - hello", plain(NotificationLines.line(null, defaults, content(title = title))))
    }

    @Test
    fun ruleFiltersFormatsAndColors() {
        assertNull(NotificationLines.line(rule("enabled" to "false"), defaults, content(text = "hi")))
        assertNull(NotificationLines.line(rule("include" to "urgent"), defaults, content(text = "hi")))
        assertNull(NotificationLines.line(rule("exclude" to "^Ann"), defaults, content(text = "hi")))
        // Patterns see the full text.
        assertNull(
            NotificationLines.line(rule("exclude" to "rest"), defaults, content(text = "hi", bigText = "hi and the rest"))
        )
        // A rule's color colors the whole text, without the theme's colors inside.
        assertEquals(
            "\u001b[38;2;255;85;85m[Chat] Ann: hi\u001b[39m",
            NotificationLines.line(
                rule("format" to "[{app}] {title}: {text}", "color" to "#FF5555"),
                defaults,
                content(text = "hi"),
            ),
        )
        // Android colors may carry alpha first; the terminal takes the RGB part.
        assertEquals("\u001b[38;2;0;255;0mx\u001b[39m", NotificationLines.colored("x", "#8000FF00"))
    }

    @Test
    fun textFromOtherAppsCannotSendTerminalSequences() {
        val runs =
            NotificationLines.runs(
                "{title}: {text}",
                NotificationContent("p", "App", "\u001b]0;owned\u0007Ann", "a\r\nb\tc\u009b31m{app}"),
            )
        val line = runs.joinToString("") { it.text }
        assertEquals("]0;ownedAnn: a\nb c31m{app}", line)
        assertFalse(line.any { it < ' ' && it != '\n' })
        // Line breaks before the first text and after the last are dropped.
        assertEquals(
            "x",
            NotificationLines.runs("{title}\n{text}\n", NotificationContent("p", "App", "", "x"))
                .joinToString("") { it.text },
        )
    }
}
