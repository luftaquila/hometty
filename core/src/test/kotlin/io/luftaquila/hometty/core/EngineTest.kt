package io.luftaquila.hometty.core

import org.junit.Assert.*
import org.junit.Test

class EngineTest {
    @Test
    fun weatherSettingsShowOnlyWhileTheWeatherIsOn() {
        fun visible(vararg values: Pair<String, String>) =
            Settings.visibleSettings(SettingsSnapshot(values = mapOf(*values))).map { it.key }
        val off = visible("weather.provider" to "off", "terminal.statusLines" to "{weather}")
        assertFalse(Settings.units.key in off)
        assertFalse(Settings.weatherInterval.key in off)
        assertTrue(Settings.weatherProvider.key in off)
        // The interval only matters when the status lines show the weather.
        assertTrue(Settings.weatherInterval.key in visible())
        assertFalse(Settings.weatherInterval.key in visible("terminal.statusLines" to ""))
        assertTrue(Settings.weatherInterval.key in visible("terminal.statusLines" to "{time}\n{weather}"))
        assertTrue(Settings.units.key in visible())
    }

    @Test
    fun appNamesSupportCaseSpacesAndCanonicalKorean() {
        assertEquals(AppNames.key("Google Maps"), AppNames.key("googlemaps"))
        assertEquals(
            AppNames.key("한글 앱"),
            AppNames.key(java.text.Normalizer.normalize("한글앱", java.text.Normalizer.Form.NFD)),
        )
        assertTrue(
            AppNames.commands("Google Maps")
                .containsAll(listOf("Google Maps", "googlemaps", "Google", "google", "GOOGLEMAPS"))
        )
    }

    /** Runs [script] in the host's POSIX shell with [arguments] as $0, $1... */
    private fun sh(
        script: String,
        vararg arguments: String,
        chain: String? = null,
        environment: Map<String, String> = emptyMap(),
    ): Pair<Int, String> {
        val process =
            ProcessBuilder(listOf("/bin/sh", "-c", script) + arguments)
                .redirectErrorStream(true)
                .apply {
                    environment().remove("HOMETTY_ALIAS_CHAIN")
                    chain?.let { environment()["HOMETTY_ALIAS_CHAIN"] = it }
                    environment().putAll(environment)
                }
                .start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    @Test
    fun quotingRoundTripsUntrustedValuesThroughARealShell() {
        val values = listOf("", "a'b", "$(id); rm -rf /", "한글 공백", "a\nb", "\\", "\"", "*", "-n")
        val (code, output) = sh("printf '%s\\0' " + Shell.render(values))
        assertEquals(0, code)
        assertEquals(values, output.split('\u0000').dropLast(1))
    }

    @Test
    fun aliasArgumentsStayLiteralAndCyclesStop() {
        val folder = kotlin.io.path.createTempDirectory().toFile()
        try {
            java.io.File(folder, "zz1").writeText("")
            val (code, output) =
                sh("cd ${Shell.quote(folder.path)}; " + Shell.alias("show", "printf '<%s>' \${1} \${2}"), "show", "a b; id", "zz*")
            assertEquals(0, code)
            assertEquals("<a b; id><zz*>", output)
        } finally {
            folder.deleteRecursively()
        }
        // Without parameter references the arguments follow the body.
        assertEquals(0 to "<x><y z>", sh(Shell.alias("tail", "printf '<%s>' x"), "tail", "y z"))
        val (code, output) = sh(Shell.alias("loop", "echo never"), "loop", chain = ":loop:outer")
        assertEquals(1, code)
        assertEquals("loop: alias cycle\n", output)
    }

    @Test
    fun aliasArgumentsFollowUnlessTheShellExpandsAReference() {
        // In single quotes, or escaped, $1 is text for the program: the arguments still follow.
        assertEquals(0 to "<\$1><x>", sh(Shell.alias("f", "printf '<%s>' '\$1'"), "f", "x"))
        assertEquals(0 to "<\$1><x>", sh(Shell.alias("f", "printf '<%s>' \\\$1"), "f", "x"))
        assertEquals(0 to "<x>", sh(Shell.alias("f", "printf '<%s>' \"\$1\""), "f", "x"))
    }

    @Test
    fun aliasNamesFitAFileNameAndBodiesStayBounded() {
        // A name is a link on PATH: 255 bytes, 85 Hangul syllables.
        Collections.validate("alias", "가".repeat(85), mapOf("command" to "ls"))
        assertThrows(IllegalArgumentException::class.java) {
            Collections.validate("alias", "가".repeat(86), mapOf("command" to "ls"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Collections.validate("alias", "big", mapOf("command" to "x".repeat(16385)))
        }
    }

    @Test
    fun nestedRepetitionIsRefusedBeforeItCompiles() {
        Patterns.validate("a{1000}")
        Patterns.validate("(ab|cd){100}x{50}[a-z]+")
        // Each count is allowed, but nested they would compile to a billion instructions.
        assertThrows(IllegalArgumentException::class.java) { Patterns.validate("((a{1000}){1000}){1000}") }
        assertThrows(IllegalArgumentException::class.java) { Patterns.validate("(a{200}){200}") }
    }

    @Test
    fun outsideTextLosesControlCharacters() {
        assertEquals("a[2Jb\nc d", printable("a\u001b[2Jb\r\nc\td\u009b"))
    }

    @Test
    fun anAliasThatCallsItsOwnNameRunsTheCommandItHides() {
        val root = kotlin.io.path.createTempDirectory().toFile()
        try {
            // The launcher's link and the real command, both named "selfie".
            val commands = java.io.File(root, "commands").apply { mkdirs() }
            val real = java.io.File(root, "bin").apply { mkdirs() }
            java.io.File(commands, "selfie").apply { writeText("#!/bin/sh\necho link\n"); setExecutable(true) }
            java.io.File(real, "selfie").apply { writeText("#!/bin/sh\necho real \"\$@\"\n"); setExecutable(true) }
            val environment =
                mapOf("HOMETTY_COMMANDS" to commands.path, "PATH" to "${commands.path}:${real.path}:/usr/bin:/bin")
            // The body's own "selfie -a" reaches the alias again with the chain ending in it.
            assertEquals(
                0 to "real -a x\n",
                sh(Shell.alias("selfie", "selfie -a"), "selfie", "-a", "x", chain = ":selfie", environment = environment),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recordsAndHelpPutOneFieldOnALine() {
        assertEquals(
            "\u001b[1mChrome\u001b[0m\n  id: com.android.chrome/Main\n  launches: 3",
            Cli.record("Chrome", "id" to "com.android.chrome/Main", "launches" to "3"),
        )
        // Options as "spelling: description", not padded into one wide column.
        assertTrue(Cli.help("apps").lines().contains("  -l, --long: label_print_ids_names_and_launches"))
    }

    @Test
    fun statusLinesFillTimesAndValuesLineByLine() {
        val now = java.time.ZonedDateTime.of(2026, 11, 26, 9, 5, 7, 0, java.time.ZoneId.of("Asia/Seoul"))
        assertEquals(emptyList<String>(), StatusLines.render(" ", emptyMap(), now, java.util.Locale.ROOT))
        assertEquals(
            listOf("2026-11-26 09:05 · 80% charging", "09:05:07 [bad pattern {time:qqqqqq}]"),
            StatusLines.render(
                "{date} {time} · {battery} {charging}\n{time:HH:mm:ss} [bad pattern {time:qqqqqq}]",
                mapOf("battery" to "80%", "charging" to "charging"),
                now,
                java.util.Locale.ROOT,
            ),
        )
        assertTrue(StatusLines.showsSeconds("{time:HH:mm:ss}"))
        assertFalse(StatusLines.showsSeconds("{time} {date} {time:yyyy}"))
    }

    @Test
    fun allSettingDefaultsAreValidAndUnique() {
        assertEquals(Settings.all.size, Settings.byKey.size)
        Settings.all.forEach { it.validate(it.default) }
    }

    @Test
    fun settingValidationProtectsUiAndScheduler() {
        assertThrows(IllegalArgumentException::class.java) {
            Settings.byKey.getValue("appearance.fontSize").validate("0")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Settings.byKey.getValue("rss.intervalMinutes").validate("1")
        }
        // Line limits: 1000 to 50000 lines in steps of 1000, 10000 by default.
        for (key in listOf("terminal.maxLines", "privacy.historyLimit")) {
            val limit = Settings.byKey.getValue(key)
            assertEquals("10000", limit.default)
            limit.validate("1000")
            limit.validate("50000")
            listOf("500", "1500", "51000").forEach { value ->
                assertThrows(IllegalArgumentException::class.java) { limit.validate(value) }
            }
        }
    }

    @Test
    fun koreanInitialSearch() {
        assertTrue(SearchIndex.score("ㅋㅋㅇ", "카카오톡") > 0)
        assertEquals(0, SearchIndex.score("메일", "카카오톡"))
    }

    @Test
    fun themePresetsCarryPalettesAndFontsDefaultToJetendard() {
        val defaults = SettingsSnapshot()
        assertEquals("jetendard", defaults.string("appearance.font"))
        Fonts.choices.forEach { Settings.byKey.getValue("appearance.font").validate(it) }
        assertEquals("dracula", Themes.selected(defaults))
        assertEquals(Themes.presets.getValue("dracula").palette, Themes.palette(defaults))
        assertEquals("#F8F8F2", Themes.cursor(defaults))
        assertEquals("#BD93F9", Themes.accent(defaults))
        Themes.presets.forEach { (name, theme) ->
            Themes.values(name).forEach { (key, value) -> Settings.byKey.getValue(key).validate(value) }
            assertEquals(name, Themes.selected(SettingsSnapshot(values = Themes.values(name))))
            assertEquals(name, 16, theme.palette.size)
        }
        // Settings saved before presets were stored by name still get the preset's palette.
        val savedColors = SettingsSnapshot(values = Themes.values("nord") - "appearance.theme")
        assertEquals("nord", Themes.selected(savedColors))
        assertEquals(Themes.presets.getValue("nord").palette, Themes.palette(savedColors))
        // A customized color keeps the preset's palette but no longer counts as the preset.
        val customized =
            SettingsSnapshot(values = Themes.values("tern_dark") + ("appearance.input" to "#123456"))
        assertNull(Themes.selected(customized))
        assertEquals(Themes.presets.getValue("tern_dark").palette, Themes.palette(customized))
        assertEquals("#D8DEE9", Themes.cursor(customized))
        assertEquals("#123456", Themes.accent(customized))
    }

    @Test
    fun commandNamesNeverReuseShellOrBusyBoxWords() {
        // A shell builtin name is only ever the same feature, reached through a shell function.
        Commands.all.filter { it.name in Commands.shellBuiltins }.forEach {
            assertTrue(it.name, it.name in Commands.shellFunctions)
        }
        // Renamed to avoid BusyBox applets and Android commands with another meaning.
        listOf("groups", "time", "log", "clear", "exit", "busybox", "history").forEach {
            assertFalse(it, it in Commands.byName)
        }
        // Persistent aliases cannot hide shell builtins or launcher commands.
        listOf("cd", "export", "history", "apps").forEach { name ->
            assertThrows(Exception::class.java) {
                Collections.validate("alias", name, mapOf("command" to "apps"))
            }
        }
        Collections.validate("alias", "ps", mapOf("command" to "apps launch 'play store'"))
    }

    @Test
    fun appListsAreSectionedByLatinThenHangulInitials() {
        assertEquals("ㄱ", AppNames.index("까치"))
        assertEquals("ㅎ", AppNames.index("휴대전화"))
        assertEquals("K", AppNames.index("kakaoTalk"))
        assertEquals("#", AppNames.index("淘宝"))
        assertEquals("#", AppNames.index("70mai"))
        val sections =
            AppNames.sections(
                listOf("휴대전화", "Chrome", "70mai", "카메라", "chrome beta", "Brawl Stars"),
                { it },
                String.CASE_INSENSITIVE_ORDER,
            )
        assertEquals(listOf("B", "C", "ㅋ", "ㅎ", "#"), sections.map { it.first })
        assertEquals(listOf("Chrome", "chrome beta"), sections[1].second)
    }

    @Test
    fun optionalSettingsFollowFeatureSwitches() {
        fun keys(values: Map<String, String> = emptyMap()) =
            Settings.visibleSettings(SettingsSnapshot(values = values)).map { it.key }.toSet()
        val defaults = keys()
        // Weather needs no switch: its settings always show.
        assertTrue("weather.format" in defaults)
        assertTrue("rss.intervalMinutes" !in defaults)
        assertTrue("appearance.opacity" !in defaults)
        val enabled =
            keys(
                mapOf(
                    "rss.enabled" to "true",
                    "appearance.wallpaper" to "true",
                )
            )
        assertTrue(
            enabled.containsAll(
                setOf("weather.format", "rss.intervalMinutes", "appearance.opacity")
            )
        )
        val disabledNotifications = keys(mapOf("notifications.enabled" to "false"))
        assertTrue("notifications.format" !in disabledNotifications)
        assertTrue("notifications.enabled" in disabledNotifications)
    }

    @Test
    fun homeFolderMustBeEnterableButSavedValuesStillLoad() {
        val folder = kotlin.io.path.createTempDirectory("home").toFile()
        val file = java.io.File(folder, "file").apply { writeText("x") }
        assertTrue(usableHomeFolder(""))
        assertTrue(usableHomeFolder(folder.path))
        assertFalse(usableHomeFolder(java.io.File(folder, "missing").path))
        assertFalse(usableHomeFolder(file.path))
        // Loading saved settings never checks the folder, so one stale path cannot reset the rest.
        Settings.byKey.getValue("terminal.home").validate("/sdcard0")
        folder.deleteRecursively()
    }
}
