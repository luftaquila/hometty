package io.luftaquila.hometty

import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.luftaquila.hometty.core.get
import io.luftaquila.hometty.platform.PlatformDependencies
import io.luftaquila.hometty.resources.R as TextR
import io.luftaquila.hometty.resources.message
import io.luftaquila.hometty.resources.text
import io.luftaquila.hometty.shell.sharedStorage
import io.luftaquila.hometty.terminal.TerminalSession
import io.luftaquila.hometty.ui.HomeViewModel
import io.luftaquila.hometty.ui.color
import io.luftaquila.hometty.ui.settings.LICENSES
import io.luftaquila.hometty.ui.settings.settingsColors
import io.luftaquila.hometty.ui.settings.settingsSection
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val storage
        get() = (context.applicationContext as PlatformDependencies).storage

    private val automation
        get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    /** The launcher window's model; read it outside runOnUiThread blocks. */
    private val model: HomeViewModel
        get() =
            compose.runOnUiThread {
                androidx.lifecycle.ViewModelProvider(compose.activity)[HomeViewModel::class.java]
            }

    // Settings and records as the test found them; @After puts them back, so no test sees another's
    // changes and none needs its own cleanup for them.
    private lateinit var settingsBefore: io.luftaquila.hometty.core.SettingsSnapshot
    private lateinit var entriesBefore: List<io.luftaquila.hometty.core.Entry>

    @Before
    fun prepare(): Unit = runBlocking {
        storage.set("input.autoKeyboard", "false")
        settingsBefore = storage.snapshot()
        entriesBefore = storage.all()
        // The rule launches the activity first, so close the first-run screen through its model.
        val model = model
        compose.runOnUiThread { if (model.route.value == Route.Start) model.finishStart() }
    }

    @After
    fun restore(): Unit = runBlocking {
        (storage.snapshot().values.keys - settingsBefore.values.keys).forEach { storage.reset(it) }
        if (settingsBefore.values.isNotEmpty()) storage.update(settingsBefore.values)
        val before = entriesBefore.associateBy { it.kind to it.name }
        for (entry in storage.all()) {
            val old = before[entry.kind to entry.name]
            if (old == null) storage.remove(entry.kind, entry.name)
            else if (old.fields != entry.fields) storage.put(entry.kind, entry.name, old.fields)
        }
        entriesBefore
            .filter { storage.find(it.kind, it.name) == null }
            .forEach { storage.put(it.kind, it.name, it.fields) }
    }

    /** Runs [command] as the shell user; its output. */
    private fun shell(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }

    /** Wait for the focused UI, not ActivityManager's state ahead of the window transition. */
    private fun focused(packageName: String) =
        automation.rootInActiveWindow?.packageName?.toString() == packageName

    private fun launcherFocused() = compose.runOnUiThread {
        compose.activity.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED &&
            compose.activity.hasWindowFocus()
    }

    private suspend fun returnToLauncher(session: TerminalSession) {
        shell(
            "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n ${context.packageName}/io.luftaquila.hometty.MainActivity"
        )
        waitFor("the focused launcher again", session, 20000) {
            launcherFocused() && session.atPrompt.value
        }
        compose.waitForIdle()
    }

    /** Waits up to [millis] for [done]; fails with [what] and, given [session], its screen. */
    private suspend fun waitFor(
        what: String,
        session: TerminalSession? = null,
        millis: Long = 5000,
        done: suspend () -> Boolean,
    ) {
        withTimeoutOrNull(millis) { while (!done()) delay(50) }
            ?: fail(what + (session?.let { "\n" + visibleText(it) } ?: ""))
    }

    /** Types [command] at the home prompt and waits for the settings it opens. */
    private fun openSettings(command: String = "config") {
        homeTerminal().session!!.write("$command\n")
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("settingsSearch").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The first node under [node], itself included, that [predicate] accepts. */
    private fun findNode(
        node: android.view.accessibility.AccessibilityNodeInfo?,
        predicate: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean,
    ): android.view.accessibility.AccessibilityNodeInfo? =
        when {
            node == null -> null
            predicate(node) -> node
            else -> (0 until node.childCount).firstNotNullOfOrNull { findNode(node.getChild(it), predicate) }
        }

    /** The package holding the HOME role, or "". */
    private fun homeRoleHolder() =
        shell("cmd role get-role-holders android.app.role.HOME").trim().lineSequence().firstOrNull().orEmpty()

    /** Gives the HOME role back to [previous], or takes it from this app when there was none. */
    private fun restoreHomeRole(previous: String) {
        if (previous.isNotEmpty()) shell("cmd role add-role-holder --user 0 android.app.role.HOME $previous 0")
        else shell("cmd role remove-role-holder --user 0 android.app.role.HOME ${context.packageName} 0")
    }

    /** Opens Android's default home picker from Permissions and setup. */
    private fun openDefaultHomePicker() {
        openSettings()
        compose.onNodeWithText(context.getString(TextR.string.start_title)).performClick()
        compose.onNodeWithText(context.getString(TextR.string.home_default_choose)).performClick()
    }

    /** Where cell [row], [column] starts in [TerminalSession.cells]: code points, then +6 foreground, +7 background, +8 attributes (1 bold, 8 default foreground). */
    private fun cellIndex(session: TerminalSession, row: Int, column: Int) = 2 + (row * session.cols + column) * 10

    @Test
    fun translationsFollowAndroidLocaleAndKeepFormattingArguments() {
        fun localized(tag: String): android.content.Context {
            val config = android.content.res.Configuration(context.resources.configuration)
            config.setLocales(android.os.LocaleList.forLanguageTags(tag))
            return context.createConfigurationContext(config)
        }
        val en = localized("en")
        val ko = localized("ko")
        val fallback = localized("fr")
        assertEquals("Set default home app", en.getString(TextR.string.home_default_choose))
        assertEquals("기본 홈 앱 설정", ko.getString(TextR.string.home_default_choose))
        assertEquals(
            en.getString(TextR.string.home_default_choose),
            fallback.getString(TextR.string.home_default_choose),
        )
        assertEquals("저장", ko.getString(TextR.string.label_save))
        assertEquals("Save", en.getString(TextR.string.label_save))
        assertEquals("1 entry", en.resources.getQuantityString(TextR.plurals.entries_count, 1, 1))
        assertEquals("항목 2개", ko.resources.getQuantityString(TextR.plurals.entries_count, 2, 2))
        assertTrue(ko.message(io.luftaquila.hometty.core.UserMessage("error_minimum", 10)).contains("10"))
        // Every name the app looks up at run time (Context.text) must resolve.
        val keys =
            io.luftaquila.hometty.core.Settings.all.flatMap { listOf(it.title, it.description) } +
                io.luftaquila.hometty.core.Commands.all.flatMap { spec ->
                    listOf(spec.summary, spec.group) +
                        spec.forms.flatMap { form -> form.options.map { it.description } }
                } +
                listOf(io.luftaquila.hometty.core.Cli.help, io.luftaquila.hometty.core.Cli.version).map { it.description } +
                listOf("cli_usage", "cli_options", "cli_option_order", "label_help_footer") +
                io.luftaquila.hometty.core.Collections.all.flatMap {
                    listOf(it.title, it.description) + it.fields.map { field -> field.label }
                }
        for (key in keys.filter { Regex("[a-z]+_[a-z0-9_]+").matches(it) }) {
            assertNotEquals(key, en.text(key))
            assertNotEquals(key, ko.text(key))
        }
        assertTrue(io.luftaquila.hometty.core.Cli.help("apps", text = ko::text).contains("사용법:"))
    }

    @Test
    fun defaultHomeButtonGrantsRoleThroughSystemPicker(): Unit = runBlocking {
        val previous = homeRoleHolder()
        val roles = context.getSystemService(android.app.role.RoleManager::class.java)
        if (roles.isRoleHeld(android.app.role.RoleManager.ROLE_HOME))
            shell(
                "cmd role remove-role-holder --user 0 android.app.role.HOME ${context.packageName} 0"
            )
        suspend fun click(
            predicate: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean
        ) {
            waitFor("a node to click in the role picker", millis = 8000) {
                var node = findNode(automation.rootInActiveWindow, predicate)
                while (node != null && !node.isClickable) node = node.parent
                node?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true
            }
        }
        try {
            openDefaultHomePicker()
            val label = context.applicationInfo.loadLabel(context.packageManager).toString()
            click {
                it.text?.toString() == label &&
                    it.packageName?.toString()?.contains("permissioncontroller") == true
            }
            click { it.viewIdResourceName == "android:id/button1" }
            waitFor("the HOME role", millis = 8000) {
                roles.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)
            }
            // Granting HOME can replace the Activity tracked by the Compose test rule.
            // Verify the real system HOME destination rather than querying that stale Activity.
            val homeIntent =
                android.content
                    .Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME)
            assertEquals(
                context.packageName,
                context.packageManager
                    .resolveActivity(
                        homeIntent,
                        android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
                    )
                    ?.activityInfo
                    ?.packageName,
            )
        } finally {
            restoreHomeRole(previous)
        }
    }

    @Test
    fun unixOptionsExitCodesAndArgumentCompletionWorkInRealShell(): Unit = runBlocking {
        val terminal = homeTerminal()
        val session = requireNotNull(terminal.session)
        val folder = File(context.filesDir, "cli-contract").apply { mkdirs() }
        folder.listFiles()?.forEach { it.delete() }
        val path = io.luftaquila.hometty.core.Shell.quote(folder.path)
        session.write(
            "cd $path; apps -al > apps.txt; config --help > help.txt; apps --bad > out.txt 2> error.txt; printf '%s' \$? > usage.txt; hometty missing_cli_command > missing.txt 2> missing.err; printf '%s' \$? > missing.code; config set home.username --help; config get home.username > literal.txt; config reset home.username; notes add -- '--help'; printf done > done.txt\n"
        )
        waitFor("done.txt", session, 10000) { File(folder, "done.txt").exists() }
        fun read(name: String) = File(folder, name).readText()
        // -al: every app as a record, each field on its own line.
        assertTrue(read("apps.txt"), read("apps.txt").lines().any { it.startsWith("  id: ") })
        assertTrue(read("help.txt"), read("help.txt").contains("--help"))
        assertEquals("2", read("usage.txt"))
        assertEquals("127", read("missing.code"))
        assertEquals("", read("out.txt"))
        assertTrue(read("error.txt"), read("error.txt").contains("unknown option"))
        assertEquals("--help", read("literal.txt").trim())
        assertTrue(storage.list("note").any { it.fields["text"] == "--help" })
        withTimeout(5000) { session.atPrompt.first { it } }
        session.write("config get appearance.fontS")
        session.key(2)
        waitFor("the completed key", session) { "appearance.fontSize" in unwrapped(session) }
        session.write(" > completed.txt\n")
        withTimeout(5000) {
            while (
                !File(folder, "completed.txt").exists() ||
                    File(folder, "completed.txt").length() == 0L
            ) delay(50)
        }
        assertEquals(
            storage.snapshot().string("appearance.fontSize"),
            File(folder, "completed.txt").readText().trim(),
        )
    }

    @Test
    fun coloredPromptDoesNotColorProgramOutput(): Unit = runBlocking {
        val terminal = homeTerminal()
        val session = requireNotNull(terminal.session)
        session.write("printf 'PLAIN_COLOR_CHECK\\n\\033[31mRED_COLOR_CHECK\\033[0m\\n'\n")
        waitFor("both lines and the next prompt", session) {
            session.atPrompt.value && visibleText(session).lines().any { it.trim() == "RED_COLOR_CHECK" }
        }
        val cells = session.cells()
        val lines = visibleText(session).lines()
        val plain = lines.indexOfFirst { it.trim() == "PLAIN_COLOR_CHECK" }
        val red = lines.indexOfFirst { it.trim() == "RED_COLOR_CHECK" }
        assertTrue(lines.joinToString("\n"), plain >= 0 && red >= 0)
        assertTrue(
            "Ordinary output must use the default foreground",
            cells[cellIndex(session, plain, 0) + 8] and 8 != 0,
        )
        val redColor = cells[cellIndex(session, red, 0) + 6]
        assertTrue(
            "Program ANSI colors must survive",
            android.graphics.Color.red(redColor) > android.graphics.Color.green(redColor),
        )
        val prompt = cells[0]
        assertEquals(
            android.graphics.Color.parseColor(storage.snapshot().string("appearance.input")),
            cells[cellIndex(session, prompt, 0) + 6],
        )
    }

    @Test
    fun wideGlyphsPaintTheirOwnBackgroundOverBothColumns(): Unit = runBlocking {
        val terminal = homeTerminal()
        val session = requireNotNull(terminal.session)
        // Row 0: 한글 written over a blue background. Row 1: 한 written with a red background.
        session.write(
            "clear; printf '\\033[44m      \\033[0m\\r\\355\\225\\234\\352\\270\\200\\n" +
                "\\033[41m\\355\\225\\234\\033[0m\\n'\n"
        )
        withTimeout(5000) { session.atPrompt.first { it } }
        val bitmap =
            android.graphics.Bitmap.createBitmap(
                terminal.width,
                terminal.height,
                android.graphics.Bitmap.Config.ARGB_8888,
            )
        compose.runOnUiThread { terminal.draw(android.graphics.Canvas(bitmap)) }
        // The top edge of a cell, clear of the glyph strokes.
        fun pixel(column: Double, row: Int) =
            bitmap.getPixel(
                (terminal.charWidth * column).toInt(),
                (terminal.lineHeight * row + 1).toInt(),
            )
        val background = terminal.terminalBackground
        // The second column of each glyph takes the glyph's colors, not the old blue.
        assertEquals(background, pixel(0.5, 0))
        assertEquals(background, pixel(1.5, 0))
        assertEquals(background, pixel(3.5, 0))
        assertNotEquals(background, pixel(4.5, 0))
        // The red background covers both columns of 한.
        assertNotEquals(background, pixel(0.5, 1))
        assertEquals(pixel(0.5, 1), pixel(1.5, 1))
        assertEquals(background, pixel(2.5, 1))
    }

    @Test
    fun koreanCommittedRegionsReplacePreviousSyllables(): Unit = runBlocking {
        val terminal = homeTerminal()
        val session = requireNotNull(terminal.session)
        val file = File(context.filesDir, "home/ime-recompose.txt").apply { delete() }
        session.write("printf '%s' '")
        compose.runOnUiThread {
            val connection = terminal.onCreateInputConnection(android.view.inputmethod.EditorInfo())
            val updates =
                listOf(
                    0 to "ㄱ",
                    0 to "가",
                    0 to "간",
                    0 to "가나",
                    1 to "낟",
                    1 to "나다",
                    2 to "달",
                    2 to "다라",
                    3 to "람",
                    3 to "라마",
                )
            for ((start, text) in updates) {
                connection.beginBatchEdit()
                connection.setComposingRegion(
                    start,
                    connection.getTextBeforeCursor(100, 0)!!.length,
                )
                connection.commitText(text, 1)
                connection.endBatchEdit()
            }
            connection.finishComposingText()
        }
        session.write("' > '$file'\n")
        withTimeout(5000) { while (!file.exists()) delay(50) }
        assertEquals("가나다라마", file.readText())
    }

    @Test
    fun imeRangeReplacementAndUtf8EraseReachCanonicalPrograms(): Unit = runBlocking {
        val terminal = homeTerminal()
        val session = requireNotNull(terminal.session)
        val file = File(context.filesDir, "home/ime-canonical.txt").apply { delete() }
        session.write("cat > '$file'\n")
        delay(200)
        compose.runOnUiThread {
            val connection = terminal.onCreateInputConnection(android.view.inputmethod.EditorInfo())
            connection.commitText("ㄱ", 1)
            connection.setComposingRegion(0, 1)
            connection.commitText("가나😀", 1)
            connection.beginBatchEdit()
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                connection.replaceText(1, 2, "나다라", 1, null)
            } else {
                connection.setComposingRegion(1, 2)
                connection.commitText("나다라", 1)
            }
            connection.deleteSurroundingTextInCodePoints(0, 1)
            connection.commitText("마", 1)
            assertEquals("가나다라마", connection.getTextBeforeCursor(100, 0).toString())
            connection.performEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE)
            connection.endBatchEdit()
        }
        withTimeout(5000) { while (!file.exists() || file.readText() != "가나다라마\n") delay(50) }
        session.write("\u0004")
        withTimeout(5000) { session.atPrompt.first { it } }
    }

    @Test
    fun composingKoreanIsVisibleEditableAndEnterFlushesBeforeExecuting(): Unit = runBlocking {
        val terminal = homeTerminal()
        val session = requireNotNull(terminal.session)
        val file = File(context.filesDir, "home/ime-enter.txt").apply { delete() }
        session.write("cat > '$file'\n")
        delay(250)
        compose.runOnUiThread {
            val connection = terminal.onCreateInputConnection(android.view.inputmethod.EditorInfo())
            connection.beginBatchEdit()
            for (part in listOf("ㅎ", "하", "한")) connection.setComposingText(part, 1)
            assertEquals("한", terminal.composingText)
            assertRightHalfOfKoreanIsDrawn(terminal)
            connection.commitText("한", 1)
            connection.setComposingText("글😀", 1)
            connection.deleteSurroundingTextInCodePoints(1, 0)
            assertEquals("글", terminal.composingText)
            connection.setComposingText("글", 1)
            connection.endBatchEdit()
            connection.sendKeyEvent(
                android.view.KeyEvent(
                    android.view.KeyEvent.ACTION_DOWN,
                    android.view.KeyEvent.KEYCODE_ENTER,
                )
            )
        }
        withTimeout(5000) { while (!file.exists() || file.readText() != "한글\n") delay(50) }
        session.write("\u0004")
        withTimeout(5000) { session.atPrompt.first { it } }
        session.write("printf '\\033[2J\\033[H한글\\n'\n")
        waitFor("한글 on the first row", session) { visibleText(session).startsWith("한") }
        compose.runOnUiThread { assertRightHalfOfKoreanIsDrawn(terminal, 0, 0) }
    }

    private fun assertRightHalfOfKoreanIsDrawn(
        terminal: io.luftaquila.hometty.terminal.TerminalView,
        row: Int? = null,
        col: Int? = null,
    ) {
        val session = requireNotNull(terminal.session)
        val cells = session.cells()
        val paint =
            android.graphics.Paint().apply {
                typeface = terminal.terminalTypeface
                textSize =
                    android.util.TypedValue.applyDimension(
                        android.util.TypedValue.COMPLEX_UNIT_SP,
                        terminal.fontSize,
                        terminal.resources.displayMetrics,
                    )
            }
        val width = paint.measureText("M")
        val height = paint.fontSpacing
        val bitmap =
            android.graphics.Bitmap.createBitmap(
                terminal.width,
                terminal.height,
                android.graphics.Bitmap.Config.ARGB_8888,
            )
        terminal.draw(android.graphics.Canvas(bitmap))
        val x = ((col ?: cells[1]) * width + width).toInt()
        val y = ((row ?: cells[0]) * height).toInt()
        var foreground = 0
        for (dy in 2 until height.toInt() - 3) for (dx in 1 until width.toInt() - 2) {
            val pixel = bitmap.getPixel(x + dx, y + dy)
            if (
                maxOf(
                    android.graphics.Color.red(pixel),
                    android.graphics.Color.green(pixel),
                    android.graphics.Color.blue(pixel),
                ) > 100
            )
                foreground++
        }
        bitmap.recycle()
        assertTrue("The right half of the Korean glyph was erased", foreground > 3)
    }

    @Test
    fun appListAndTypedNamesLaunchSeparateActivities(): Unit = runBlocking {
        val terminal = homeTerminal()
        val session = requireNotNull(terminal.session)
        val list = File(context.filesDir, "home/app-list.txt").apply { delete() }
        session.write("apps -l > '$list'\n")
        withTimeout(5000) {
            while (!list.exists() || !list.readText().contains("한글 테스트 앱")) delay(50)
        }
        assertTrue(list.readText(), list.readText().contains("Mixed Case App"))
        withTimeout(5000) { session.atPrompt.first { it } }
        // The probe apps are activities of this test APK.
        val probes = InstrumentationRegistry.getInstrumentation().context.packageName
        for (name in listOf("mixedcaseapp", "mixed case app", "한글 테스트 앱", "한글테스트앱")) {
            session.write("$name\n")
            waitFor("$name to open", session, 8000) { focused(probes) }
            returnToLauncher(session)
        }
        session.write("apps --gui\n")
        compose.waitUntil(5000) {
            compose
                .onAllNodesWithText(context.getString(TextR.string.label_search_apps))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNode(hasSetTextAction()).performTextInput("Mixed Case")
        compose.onNodeWithText("Mixed Case App").assertExists()
    }

    @Test
    fun configCommandOpensSearchableGuiAndPersistsChanges() {
        val terminal = homeTerminal()
        compose.runOnUiThread {
            val connection = terminal.onCreateInputConnection(android.view.inputmethod.EditorInfo())
            connection.commitText("config", 1)
            terminal.dispatchKeyEvent(
                android.view.KeyEvent(
                    android.view.KeyEvent.ACTION_DOWN,
                    android.view.KeyEvent.KEYCODE_ENTER,
                )
            )
        }
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("settingsSearch").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("settingsSearch").performTextInput("home.username")
        compose.onNodeWithTag("search:home.username").performClick()
        compose.onNodeWithTag("setting:home.username").performClick()
        compose.onAllNodes(hasSetTextAction()).onLast().performTextReplacement("검증사용자")
        compose.onNodeWithText(context.getString(TextR.string.label_save)).performClick()
        compose.waitUntil(5000) {
            runBlocking { storage.snapshot().string("home.username") == "검증사용자" }
        }
        compose.onNodeWithText("검증사용자").assertExists()
    }

    @Test
    fun draculaPreviewCancelsWithoutSavingAndAppliesAtomically(): Unit = runBlocking {
        storage.update(io.luftaquila.hometty.core.Themes.values("default"))
        openSettings("config appearance")
        compose.onNodeWithText(context.getString(TextR.string.label_theme_presets)).performClick()
        compose.onNodeWithTag("theme:dracula").performScrollTo().performClick()
        assertEquals("#000000", storage.snapshot().string("appearance.background"))
        compose.onNodeWithText(context.getString(TextR.string.label_cancel)).performClick()
        assertEquals("#000000", storage.snapshot().string("appearance.background"))
        compose.onNodeWithText(context.getString(TextR.string.label_theme_presets)).performClick()
        compose.onNodeWithTag("theme:dracula").performScrollTo().performClick()
        compose.onNodeWithTag("themeSave").performClick()
        compose.waitUntil(5000) {
            runBlocking { io.luftaquila.hometty.core.Themes.selected(storage.snapshot()) == "dracula" }
        }
        assertEquals("#BD93F9", io.luftaquila.hometty.core.Themes.accent(storage.snapshot()))
    }

    @Test
    fun settingsSearchFocusesOneAccessibleSwitchWithoutOpeningAnEditor(): Unit = runBlocking {
        storage.set("input.toolbar", "false")
        openSettings()
        compose.onNodeWithTag("settingsSearch").performTextInput("input.toolbar")
        compose.onNodeWithTag("search:input.toolbar").performClick()
        compose.onNodeWithTag("setting:input.toolbar").assertIsOff().performClick()
        compose.waitUntil(5000) { runBlocking { storage.snapshot().bool("input.toolbar") } }
        // DataStore can finish before its Flow reaches the rendered switch.
        compose.waitUntil(5000) {
            compose.onAllNodes(hasTestTag("setting:input.toolbar") and isOn()).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("setting:input.toolbar").assertIsOn()
        compose.onNodeWithText("input.toolbar").assertDoesNotExist()
        compose.onNodeWithText(context.getString(TextR.string.label_save)).assertDoesNotExist()
        compose.onNodeWithTag("setting:input.toolbar").performClick()
        compose.waitUntil(5000) { runBlocking { !storage.snapshot().bool("input.toolbar") } }
    }

    @Test
    fun homeFolderThatCannotBeOpenedIsNotSaved(): Unit = runBlocking {
        val missing = File(context.filesDir, "missing-home").apply { deleteRecursively() }
        val failure = runCatching { storage.update(mapOf("terminal.home" to missing.path)) }
        assertEquals("error_home_folder", (failure.exceptionOrNull() as? io.luftaquila.hometty.core.UserMessage)?.key)
        assertEquals("", storage.snapshot().string("terminal.home"))
        val folder = File(context.filesDir, "home").apply { mkdirs() }
        storage.update(mapOf("terminal.home" to folder.path))
        assertEquals(folder.path, storage.snapshot().string("terminal.home"))
    }

    @Test
    fun homeFolderIsPickedFromFoldersTheShellCanEnter(): Unit = runBlocking {
        val home = File(context.filesDir, "home").apply { mkdirs() }
        val picked = File(home, "picked-home").apply { mkdirs() }
        val model = model
        compose.runOnUiThread { model.openSetting("terminal.home") }
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("setting:terminal.home").fetchSemanticsNodes().isNotEmpty()
        }
        // The opened setting is also shown highlighted at the top, so it can appear twice.
        compose.onAllNodesWithTag("setting:terminal.home").onFirst().performClick()
        compose.onNodeWithText(context.getString(TextR.string.label_folder_app)).performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("folder:picked-home").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("folder:picked-home").performClick()
        compose.onNodeWithTag("folderPath").assertTextEquals(picked.path)
        compose.onNodeWithTag("useFolder").performClick()
        compose.waitUntil(5000) {
            runBlocking { storage.snapshot().string("terminal.home") == picked.path }
        }
        picked.delete()
    }

    @Test
    fun backReturnsToTheSettingsScrollPosition() {
        val model = model
        fun offset() =
            compose
                .onNode(hasScrollToIndexAction())
                .fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
                .value()
        compose.runOnUiThread { model.navigate(Route.SettingsPage("terminal")) }
        compose.waitForIdle()
        compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeUp() }
        compose.waitForIdle()
        val scrolled = offset()
        assertTrue(scrolled > 0f)
        // Open a page from the list, then come back.
        compose.runOnUiThread { model.navigate(Route.Manage("alias")) }
        compose.waitForIdle()
        compose.runOnUiThread { model.back() }
        compose.waitForIdle()
        assertEquals(scrolled, offset(), 1f)
        // A fresh visit starts at the top again.
        compose.runOnUiThread { model.back() }
        compose.waitForIdle()
        compose.runOnUiThread { model.navigate(Route.SettingsPage("terminal")) }
        compose.waitForIdle()
        assertEquals(0f, offset(), 1f)
    }

    @Test
    fun aboutOpensLicensesAndDonations() {
        val model = model
        // Every listed license has its text in the APK.
        LICENSES.forEach { context.assets.open(it.asset).close() }
        compose.runOnUiThread { model.navigate(Route.About) }
        compose.onNodeWithText(BuildConfig.VERSION_NAME).assertExists()
        compose.onNodeWithText(context.getString(TextR.string.label_open_source_notices)).performClick()
        compose.onNodeWithText("BusyBox").performClick()
        compose.onNodeWithText("GNU GENERAL PUBLIC LICENSE", substring = true).assertExists()
        compose.onNodeWithText(context.getString(TextR.string.label_close)).performClick()
        compose.onNodeWithText("GNU GENERAL PUBLIC LICENSE", substring = true).assertDoesNotExist()
        compose.runOnUiThread { model.back() }
        compose.onNodeWithText(context.getString(TextR.string.donate_title)).performClick()
        compose.onNodeWithText(context.getString(TextR.string.donate_headline)).assertExists()
        compose.onNodeWithText(context.getString(TextR.string.donate_small)).assertExists()
        compose.onNodeWithText("GitHub Sponsors").assertExists()
    }

    @Test
    fun displaySettingsOfferTheAppLanguages() {
        // Per-app languages, and the row for them, start at Android 13.
        Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
        val model = model
        val label = context.getString(TextR.string.label_app_language)
        compose.runOnUiThread { model.navigate(Route.SettingsPage("appearance")) }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
        compose.onNodeWithText(label).performClick()
        compose.onNodeWithText("English").assertExists()
        compose.onNodeWithText("한국어").assertExists()
        compose.onNodeWithText(context.getString(TextR.string.label_cancel)).performClick()
        compose.onNodeWithText("한국어").assertDoesNotExist()
    }

    @Test
    fun unknownAndInvalidLocalSettingsDoNotDiscardValidPreferences(): Unit = runBlocking {
        val settings =
            io.luftaquila.hometty.data.SettingsSerializer.readFrom(
                ByteArrayInputStream(
                    // Unknown keys and values out of range fall back to their defaults.
                    """{"version":1,"values":{"terminal.busyboxEnabled":"false","notifications.title":"old","appearance.fontSize":"18","terminal.maxLines":"0"}}"""
                        .toByteArray()
                )
            )
        assertEquals(18, settings[io.luftaquila.hometty.core.Settings.fontSize])
        assertEquals(setOf("appearance.fontSize"), settings.values.keys)
    }

    @Test
    fun ruleEditorPicksAnAppByNameAndStoresItsPackage(): Unit = runBlocking {
        val app = io.luftaquila.hometty.platform.DeviceAccess(context).apps().first()
        storage.find("notificationRule", app.label)?.let {
            storage.remove("notificationRule", app.label)
        }
        val model = model
        compose.runOnUiThread { model.navigate(Route.Manage("notificationRule")) }
        compose.onNodeWithText(context.getString(TextR.string.label_add)).performClick()
        compose.onNodeWithTag("entryApps").performClick()
        compose.onNodeWithTag("appPickerSearch").performTextInput(app.label)
        compose.onNodeWithTag("pick:${app.packageName}").performClick()
        compose.onNodeWithTag("appPickerSave").performClick()
        // The rule is named after the chosen app.
        compose.onNodeWithTag("entrySave").performClick()
        compose.waitUntil(5000) {
            runBlocking { storage.find("notificationRule", app.label) != null }
        }
        assertEquals(
            app.packageName,
            storage.find("notificationRule", app.label)!!.fields["package"],
        )
        // The list shows the package beside the rule's name rather than the app name again.
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("entry:${app.label}").fetchSemanticsNodes().isNotEmpty()
        }
        val texts =
            compose
                .onNodeWithTag("entry:${app.label}")
                .fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                .map { it.text }
        assertEquals(listOf(app.label, app.packageName), texts.take(2))
    }

    @Test
    fun searchRevealsDisabledFeatureAndChangedSettingsCanBeReset(): Unit = runBlocking {
        storage.set("rss.enabled", "false")
        openSettings()
        compose.onNodeWithTag("settingsSearch").performTextInput("rss.intervalMinutes")
        compose.onNodeWithTag("search:rss.intervalMinutes").performClick()
        compose.onNodeWithTag("setting:rss.intervalMinutes").assertDoesNotExist()
        compose.onAllNodesWithTag("setting:rss.enabled").onFirst().performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("setting:rss.intervalMinutes").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("setting:rss.intervalMinutes").performClick()
        compose.onAllNodes(hasSetTextAction()).onLast().performTextReplacement("45")
        compose.onNodeWithText(context.getString(TextR.string.label_save)).performClick()
        compose.waitUntil(5000) {
            runBlocking { storage.snapshot().string("rss.intervalMinutes") == "45" }
        }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("settingsSearch").performTextClearance()
        // Changed settings sit in History and data, beside resetting everything.
        compose
            .onNodeWithTag("settingsList")
            .performScrollToNode(hasText(context.getString(TextR.string.section_data)))
        compose.onNodeWithText(context.getString(TextR.string.section_data)).performClick()
        compose
            .onNodeWithTag("settingsList")
            .performScrollToNode(hasText(context.getString(TextR.string.settings_changed)))
        compose.onNodeWithText(context.getString(TextR.string.settings_changed)).performClick()
        compose
            .onNodeWithTag("settingsList")
            .performScrollToNode(hasTestTag("changed:rss.intervalMinutes"))
        compose
            .onNodeWithTag("changed:rss.intervalMinutes")
            .onChildren()
            .filter(hasClickAction())
            .onFirst()
            .performClick()
        compose.waitUntil(5000) {
            runBlocking { storage.snapshot().string("rss.intervalMinutes") == "60" }
        }
    }

    @Test
    fun lowContrastTerminalColorsKeepSettingsReadable() {
        val snapshot =
            io.luftaquila.hometty.core.SettingsSnapshot(
                values =
                    mapOf(
                        "appearance.background" to "#FFFFFF",
                        "appearance.foreground" to "#FFFFFF",
                        "appearance.input" to "#FFFFFF",
                    )
            )
        val scheme = settingsColors(snapshot)
        fun contrast(
            a: androidx.compose.ui.graphics.Color,
            b: androidx.compose.ui.graphics.Color,
        ): Float {
            val x = a.luminance()
            val y = b.luminance()
            return (maxOf(x, y) + .05f) / (minOf(x, y) + .05f)
        }
        assertTrue(contrast(scheme.onSurface, scheme.surface) >= 4.5f)
        assertTrue(contrast(scheme.onSurfaceVariant, scheme.surfaceContainerHigh) >= 4.5f)
        assertTrue(contrast(scheme.primary, scheme.surfaceContainerHigh) >= 4.5f)
        assertTrue(contrast(scheme.onPrimary, scheme.primary) >= 4.5f)
    }

    @Test
    fun cancellingDefaultHomePickerLeavesCurrentHomeUnchanged(): Unit = runBlocking {
        val previous = homeRoleHolder()
        if (previous == context.packageName)
            shell(
                "cmd role remove-role-holder --user 0 android.app.role.HOME ${context.packageName} 0"
            )
        val expected = homeRoleHolder()
        try {
            openDefaultHomePicker()
            waitFor("the role picker") {
                automation.rootInActiveWindow?.packageName?.toString()?.contains("permissioncontroller") == true
            }
            waitFor("its cancel button") {
                findNode(automation.rootInActiveWindow) { it.viewIdResourceName == "android:id/button2" }
                    ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            compose.waitUntil(5000) {
                runCatching {
                        compose
                            .onAllNodesWithTag("defaultHomeStatus")
                            .fetchSemanticsNodes()
                            .isNotEmpty()
                    }
                    .getOrDefault(false)
            }
            compose
                .onNodeWithTag("defaultHomeStatus")
                .assertTextEquals(context.getString(TextR.string.home_default_unchanged))
            assertEquals(expected, homeRoleHolder())
        } finally {
            if (previous.isNotBlank()) restoreHomeRole(previous)
        }
    }

    @Test
    fun appearanceRecoveryPreservesPromptIdentity(): Unit = runBlocking {
        storage.set("home.username", "keep-my-prompt")
        storage.set("appearance.fontSize", "18")
        openSettings("config appearance")
        compose
            .onNodeWithTag("settingsList")
            .performScrollToNode(hasText(context.getString(TextR.string.label_recover_appearance)))
        compose.onNodeWithText(context.getString(TextR.string.label_recover_appearance)).performClick()
        compose
            .onNode(hasText(context.getString(TextR.string.label_reset_2)) and hasClickAction())
            .performClick()
        compose.waitUntil(5000) {
            runBlocking { storage.snapshot()[io.luftaquila.hometty.core.Settings.fontSize] == 14 }
        }
        assertEquals("keep-my-prompt", storage.snapshot().string("home.username"))
    }

    @Test
    fun weatherOffOnlySaysSo(): Unit = runBlocking {
        storage.set("weather.provider", "off")
        val terminal = homeTerminal()
        val session = requireNotNull(terminal.session)
        val result = File(context.filesDir, "home/weather.txt").apply { delete() }
        val message = context.getString(TextR.string.message_weather_off)
        withTimeout(5000) { session.atPrompt.first { it } }
        session.write("weather > \$HOMETTY_DATA/weather.txt 2>&1\n")
        waitFor("the weather-off message", session) { result.exists() && message in result.readText() }
    }

    @Test
    fun calcTakesAStarWithoutQuotesAndParenthesesOnItsOwnLines(): Unit = runBlocking {
        val terminal = homeTerminal()
        val session = requireNotNull(terminal.session)
        val result = File(context.filesDir, "home/calc.txt").apply { delete() }
        withTimeout(5000) { session.atPrompt.first { it } }
        // Unquoted: * is arithmetic here, not the files of the home folder.
        session.write("cd; calc 7 / 2 * 3 > \$HOMETTY_DATA/calc.txt\n")
        waitFor("calc with a star", session) { result.exists() && result.readText().trim() == "10.5" }
        withTimeout(5000) { session.atPrompt.first { it } }
        session.write("calc >> \$HOMETTY_DATA/calc.txt\n")
        delay(500)
        session.write("(2 + 3) * 4\n\n")
        waitFor("calc on its own lines", session) { "20" in result.readText() }
    }

    @Test
    fun tapRerunsACommand(): Unit = runBlocking {
        val terminal = homeTerminal()
        val session = requireNotNull(terminal.session)
        val taps = File(context.filesDir, "home/tp").apply { delete() }
        fun count() = if (taps.exists()) taps.readLines().size else 0
        // Short enough not to wrap, so the command is on one row.
        session.write("cd\n")
        withTimeout(5000) { session.atPrompt.first { it } }
        session.write("echo tp >>\$HOMETTY_DATA/tp\n")
        waitFor("the first run", session) { count() == 1 }
        val row = compose.runOnUiThread { terminal.visibleText().lines().indexOfLast { "echo tp >>" in it } }
        touch(terminal, 100f, (row + .5f) * terminal.lineHeight)
        waitFor("the rerun", session) { count() == 2 }
    }

    @Test
    fun rerunningAWrappedCommandKeepsEveryRow(): Unit = runBlocking {
        val result = File(context.filesDir, "home/wrapped-rerun").apply { delete() }
        val setup = io.luftaquila.hometty.core.ShellPrompt.setup(io.luftaquila.hometty.core.SettingsSnapshot())
        val session = TerminalSession(context, null, "", 100, setup, deferUntilMeasured = true)
        try {
            session.resize(24, 35)
            withTimeout(5000) { session.atPrompt.first { it } }
            val command = "printf '%s\\n' WRAPPED_COMMAND_TAIL >> wrapped-rerun"
            session.write("$command\n")
            waitFor("the wrapped command", session) { result.exists() && result.readLines().size == 1 }
            withTimeout(5000) { session.atPrompt.first { it } }
            val row = visibleText(session).lines().indexOfFirst { "printf" in it }
            assertTrue(visibleText(session), row >= 0)
            assertEquals(command, session.commandAt(row, 0))
            assertEquals(command, session.commandAt(row + 1, 0))
            session.write("${session.commandAt(row, 0)}\n")
            waitFor("the complete rerun", session) { result.readLines().size == 2 }
            assertEquals(listOf("WRAPPED_COMMAND_TAIL", "WRAPPED_COMMAND_TAIL"), result.readLines())
        } finally {
            session.close()
            result.delete()
        }
    }

    @Test
    fun aNotificationLineOpensItsNotificationAndCanHideItsApp(): Unit = runBlocking {
        val hub = (context.applicationContext as PlatformDependencies).notifications
        // Exercise a real cross-package PendingIntent without starting the system Settings app,
        // whose background ANR dialog can cover unrelated tests later in the suite.
        val packageName = InstrumentationRegistry.getInstrumentation().context.packageName
        storage.set("notifications.enabled", "true")
        val terminal = homeTerminal()
        val session = requireNotNull(terminal.session)
        val model = model
        val intent =
            android.app.PendingIntent.getActivity(
                context,
                0,
                android.content.Intent().setClassName(packageName, LaunchProbeActivity::class.java.name)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        val content = io.luftaquila.hometty.core.NotificationContent(packageName, "Test App", "Ann", "OPENME")
        fun post(key: String, content: io.luftaquila.hometty.core.NotificationContent, update: Boolean) =
            hub.post(
                io.luftaquila.hometty.platform.NotificationHub.Posted(
                    key,
                    content,
                    android.os.Process.myUserHandle(),
                    intent,
                    update,
                )
            )
        fun lines() = compose.runOnUiThread { terminal.visibleText().lines() }
        // Notices print in order, so once [text] from another app shows, everything posted
        // before it has been handled.
        suspend fun sentinel(text: String) {
            post("hometty-$text", io.luftaquila.hometty.core.NotificationContent("io.luftaquila.hometty.sentinel", "Sentinel", "S", text), false)
            waitFor("the sentinel $text", session) { text in unwrapped(session) }
        }
        // A resize can redraw the old prompt before clear has run. Wait for output from this
        // command, or a notice posted at that old prompt can be erased by the pending clear.
        session.write("clear; printf 'NOTICE_READY\\n'\n")
        waitFor("the cleared notification screen", session) {
            session.atPrompt.value && lines().any { it.trimEnd() == "NOTICE_READY" }
        }
        post("hometty-open", content, false)
        waitFor("the notification", session) { lines().any { "OPENME" in it } }
        val row = lines().indexOfLast { "OPENME" in it }
        val text = lines()[row]
        assertTrue(text, Regex("""^\[\d\d:\d\d:\d\d] Test App: Ann - OPENME""").containsMatchIn(text))

        // The time is faded and the app name takes the theme's accent; nothing is bold.
        val settings = storage.snapshot()
        val cells = session.cells()
        fun cell(column: Int) = cellIndex(session, row, column)
        assertEquals(
            android.graphics.Color.parseColor(io.luftaquila.hometty.core.NotificationLines.timeColor(settings)),
            cells[cell(0) + 6],
        )
        assertEquals(
            android.graphics.Color.parseColor(io.luftaquila.hometty.core.NotificationLines.appColor(settings)),
            cells[cell(text.indexOf("Test App")) + 6],
        )
        assertEquals(0, cells[cell(text.indexOf("Ann")) + 8] and 1)
        assertEquals(0, cells[cell(text.indexOf("OPENME")) + 8] and 1)

        // An update that changes only text the format leaves out prints nothing.
        post("hometty-open", content.copy(subText = "2 new"), true)
        sentinel("SENTINEL_UPDATE")
        assertEquals(1, lines().count { "OPENME" in it })

        val y = (row + .5f) * terminal.lineHeight
        // By default (tap to open off) the line taps like any other: nothing opens, which only
        // shows once an activity start would have had time to happen.
        touch(terminal, 100f, y)
        delay(1500)
        assertTrue(launcherFocused())
        storage.set("notifications.tapToOpen", "true")
        compose.waitUntil(5000) { compose.runOnUiThread { terminal.onNoticeTap != null } }
        // Turned on, a tap opens what the notification opens. Opening another app's activity can
        // take a while on a busy emulator.
        touch(terminal, 100f, y)
        waitFor("its activity", session, 20000) { focused(packageName) }
        // A launcher activity's Back can return to the system home.
        returnToLauncher(session)

        // The long-press menu's hide leaves the app out from now on.
        val id = requireNotNull(session.noticeAt(row, 0))
        compose.runOnUiThread { model.hideNotifications(id) }
        waitFor("the rule") {
            storage.list("notificationRule").any {
                it.fields["package"] == packageName && it.fields["enabled"] == "false"
            }
        }
        val hidden = context.getString(TextR.string.message_notifications_hidden, "Test App")
        waitFor("the hidden message", session) { lines().any { hidden.take(20) in it } }
        post("hometty-hidden", content.copy(text = "HIDDENME"), false)
        sentinel("SENTINEL_HIDDEN")
        assertTrue(lines().none { "HIDDENME" in it })
    }

    @Test
    fun aliasesHaveATopLevelEntryAndAppSectionsOpenFromAGrid() {
        val model = model
        // App messages and notifications share one page, which `config notifications` opens.
        assertEquals("apps", settingsSection("notifications")!!.id)
        compose.runOnUiThread { model.navigate(Route.SettingsPage("apps")) }
        for (key in listOf("apps.showChanges", "notifications.enabled"))
            compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("setting:$key"))
        compose.runOnUiThread { model.navigate(Route.SettingsPage()) }
        compose
            .onAllNodesWithText(context.getString(TextR.string.settings_changed))
            .assertCountEquals(0)
        compose.onNodeWithTag("settingsAliases").performScrollTo().performClick()
        compose.waitUntil(5000) { model.route.value == Route.Manage("alias") }

        // Sections open from a grid, so nothing sits on the edges where the back gesture starts.
        compose.runOnUiThread { model.navigate(Route.Apps) }
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("appsJump").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("appsJump").performClick()
        val cells =
            compose
                .onAllNodes(
                    SemanticsMatcher("a section cell") {
                        it.config
                            .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)
                            ?.startsWith("appsJump:") == true
                    }
                )
                .fetchSemanticsNodes()
                .map { it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag] }
        assertTrue(cells.toString(), cells.size > 1)
        // The second section: the last may sit too near the list's end to reach its top.
        val last = cells[1].removePrefix("appsJump:")
        compose.onNodeWithTag("appsJump:$last").performClick()
        compose.onNodeWithTag("appsJumpGrid").assertDoesNotExist()
        // The picked section's title sticks to the top of the list.
        val list = compose.onNodeWithTag("appsList").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithTag("appsHeader:$last").fetchSemanticsNode().boundsInRoot
        assertEquals(list.top, title.top, 1f)
        // A section title opens the grid too.
        compose.onNodeWithTag("appsHeader:$last").performClick()
        compose.onNodeWithTag("appsJumpGrid").assertExists()
        compose.onNodeWithTag("appsJump:$last").performClick()
    }

    @Test
    fun fullScreenHidesOnlyTheStatusBarSoGesturesWorkAtOnce(): Unit = runBlocking {
        fun bars() =
            compose.runOnUiThread {
                androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.let {
                        it.isVisible(androidx.core.view.WindowInsetsCompat.Type.statusBars()) to
                            it.isVisible(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
                    }
            }
        storage.set("appearance.fullscreen", "true")
        try {
            // With the navigation bar hidden too, the first back or home gesture would only
            // bring the bars back.
            withTimeoutOrNull(5000) { while (bars() != (false to true)) delay(100) }
                ?: fail("status and navigation bars visible: ${bars()}")
        } finally {
            storage.reset("appearance.fullscreen")
        }
        withTimeoutOrNull(5000) { while (bars() != (true to true)) delay(100) }
            ?: fail("bars after full screen: ${bars()}")
    }

    @Test
    fun formatEditorsListTheirPlaceholdersAndInsertThem() {
        val model = model
        // The format shows only while notifications are on.
        runBlocking { storage.reset("notifications.enabled") }
        compose.runOnUiThread { model.navigate(Route.SettingsPage("apps")) }
        compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("setting:notifications.format"))
        compose.onNodeWithTag("setting:notifications.format").performClick()
        // The placeholders are in the editor, not in the description under the setting's name.
        // The dialog's field: the settings search field sits behind it.
        val field = compose.onAllNodes(hasSetTextAction()).onLast()
        field.performTextReplacement("AB")
        field.performTextInputSelection(androidx.compose.ui.text.TextRange(1))
        // A placeholder goes in at the cursor, not at the end.
        compose.onNodeWithTag("placeholder:{package}").performScrollTo().performClick()
        field.assert(hasText("A{package}B"))
        compose.onNodeWithText(context.getString(TextR.string.label_cancel)).performClick()
        assertFalse(
            runBlocking { storage.snapshot() }.string("notifications.format").contains("{package}")
        )
    }

    @Test
    fun recordEditorsInsertPlaceholdersAtTheCursor() {
        val model = model
        compose.runOnUiThread { model.navigate(Route.Manage("alias")) }
        compose.onNodeWithText(context.getString(TextR.string.label_add)).performClick()
        // The command field: the name field comes first.
        val command = compose.onAllNodes(hasSetTextAction()).onLast()
        command.performTextReplacement("echo  end")
        command.performTextInputSelection(androidx.compose.ui.text.TextRange(5))
        compose.onNodeWithTag("placeholder:\${1}").performScrollTo().performClick()
        command.assert(hasText("echo \${1} end"))
    }

    @Test
    fun choosingOpenWeatherAsksForItsKeyInTheSameDialog() {
        val secrets = io.luftaquila.hometty.data.Secrets(context)
        val saved = runCatching { secrets.get("weatherKey") }.getOrNull()
        secrets.set("weatherKey", "")
        val model = model
        compose.runOnUiThread { model.navigate(Route.SettingsPage("tools")) }
        try {
            compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("setting:weather.provider"))
            compose.onNodeWithTag("setting:weather.provider").performClick()
            // wttr.in needs no key, so there is nothing to type until OpenWeather is picked.
            compose.onNodeWithTag("weatherKey").assertDoesNotExist()
            compose.onNodeWithTag("weatherProvider:openweather").performClick()
            val save = compose.onNodeWithText(context.getString(TextR.string.label_save))
            save.assertIsNotEnabled()
            compose.onNodeWithTag("openWeatherSite").assertExists()
            compose.onNodeWithTag("weatherKey").performTextInput("TESTKEY")
            save.assertIsEnabled().performClick()
            compose.waitUntil(5000) {
                runBlocking { storage.snapshot() }.string("weather.provider") == "openweather"
            }
            assertEquals("TESTKEY", secrets.get("weatherKey"))
        } finally {
            secrets.set("weatherKey", saved.orEmpty())
        }
    }

    /**
     * Sends [times] taps 120 ms apart, or with [moveTo] a drag, to [terminal] at view coordinates;
     * [end] can make the gesture end in a cancel instead.
     */
    private fun touch(
        terminal: io.luftaquila.hometty.terminal.TerminalView,
        x: Float,
        y: Float,
        moveTo: Pair<Float, Float>? = null,
        end: Int = android.view.MotionEvent.ACTION_UP,
        times: Int = 1,
    ) =
        compose.runOnUiThread {
            var down = android.os.SystemClock.uptimeMillis()
            repeat(times) {
                val steps =
                    listOf(Triple(android.view.MotionEvent.ACTION_DOWN, x, y)) +
                        (moveTo?.let { (toX, toY) ->
                            (1..5).map { i ->
                                Triple(
                                    android.view.MotionEvent.ACTION_MOVE,
                                    x + (toX - x) * i / 5,
                                    y + (toY - y) * i / 5,
                                )
                            }
                        } ?: emptyList()) +
                        Triple(end, moveTo?.first ?: x, moveTo?.second ?: y)
                steps.forEachIndexed { i, (action, atX, atY) ->
                    val event = android.view.MotionEvent.obtain(down, down + i * 20L, action, atX, atY, 0)
                    terminal.dispatchTouchEvent(event)
                    event.recycle()
                }
                down += 120
            }
        }

    @Test
    fun aTapOnTheTypedLineMovesItsCursorThere(): Unit = runBlocking {
        val terminal = homeTerminal()
        val session = requireNotNull(terminal.session)
        val result = File(context.filesDir, "home/ct").apply { delete() }
        // A short line: it must not wrap.
        session.write("HOME=\"\$HOMETTY_DATA\"; cd\n")
        withTimeout(5000) { session.atPrompt.first { it } }
        session.write("echo bc > ct")
        waitFor("the typed line", session) { visibleText(session).lines().any { "echo bc > ct" in it } }
        val row = visibleText(session).lines().indexOfLast { "echo bc > ct" in it }
        val column = visibleText(session).lines()[row].indexOf("echo bc") + "echo ".length
        // On "b": the cursor moves back before it, so typing puts "a" there. The arrow keys and
        // the typing reach the shell in order.
        touch(terminal, (column + .5f) * terminal.charWidth, (row + .5f) * terminal.lineHeight)
        session.write("a\n")
        waitFor("abc in ${result.name}", session) { result.exists() && result.readText().trim() == "abc" }
        result.delete()
    }

    @Test
    fun aLongPressSelectionCopiesTheChosenRange(): Unit = runBlocking {
        val terminal = homeTerminal()
        val session = requireNotNull(terminal.session)
        session.write("echo 'SELECTME-one two three'\n")
        waitFor("the output line", session) {
            visibleText(session).lines().any { it.trimStart().startsWith("SELECTME") }
        }
        val row = visibleText(session).lines().indexOfLast { it.trimStart().startsWith("SELECTME") }
        val line = visibleText(session).lines()[row]
        val column = line.indexOf("SELECTME")
        val y = (row + .5f) * terminal.lineHeight
        // Where the menu's Select starts: the press before the long press opens the menu.
        touch(terminal, (column + .5f) * terminal.charWidth, y, end = android.view.MotionEvent.ACTION_CANCEL)
        compose.runOnUiThread { terminal.startSelection() }
        compose.waitForIdle()
        // The word is selected; its end handle hangs under the end of "SELECTME-one".
        val radius = 10 * context.resources.displayMetrics.density
        val handleY = (row + 1) * terminal.lineHeight + radius
        val wordEnd = (column + "SELECTME-one".length) * terminal.charWidth
        val twoEnd = (column + "SELECTME-one two".length) * terminal.charWidth
        touch(terminal, wordEnd, handleY, moveTo = twoEnd to handleY)
        compose.waitForIdle()
        val automation = automation
        automation.serviceInfo =
            automation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        val copy = context.getString(android.R.string.copy)
        // Searching by text does not reach into the floating toolbar's own surface; walking does.
        fun button() = automation.windows.firstNotNullOfOrNull { window -> findNode(window.root) { it.text?.toString() == copy } }
        waitFor("$copy in ${automation.windows.map { it.title }}") { button() != null }
        // The toolbar is drawn in a surface of its own: tap where it shows.
        val bounds = android.graphics.Rect().also(requireNotNull(button())::getBoundsInScreen)
        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        fun clip() = compose.runOnUiThread { clipboard.primaryClip?.getItemAt(0)?.text?.toString() }
        waitFor("the copied range") { clip() == "SELECTME-one two" }
    }

    @Test
    fun lineLimitsMoveInStepsOfAThousand() {
        val model = model
        runBlocking { storage.reset("terminal.maxLines") }
        compose.runOnUiThread { model.navigate(Route.SettingsPage("terminal")) }
        compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("setting:terminal.maxLines"))
        compose.onNodeWithTag("setting:terminal.maxLines").performClick()
        // In the dialog and in the row behind it.
        compose.onAllNodesWithText(context.getString(TextR.string.settings_lines, 10000)).onFirst().assertExists()
        compose.onNodeWithTag("sliderIncrease").performClick()
        compose.onNodeWithText(context.getString(TextR.string.settings_lines, 11000)).assertExists()
        compose.onNodeWithText(context.getString(TextR.string.label_save)).performClick()
        compose.waitUntil(5000) { runBlocking { storage.snapshot() }.string("terminal.maxLines") == "11000" }
    }

    @Test
    fun statusLinesShowAboveTheTerminalUntilCleared(): Unit = runBlocking {
        homeTerminal()
        storage.set("terminal.statusLines", "STAT {battery}\nT {time:HH:mm:ss}")
        try {
            compose.waitUntil(5000) { compose.onAllNodesWithTag("statusLines").fetchSemanticsNodes().isNotEmpty() }
            val text =
                compose.onNodeWithTag("statusLines").fetchSemanticsNode().config[
                        androidx.compose.ui.semantics.SemanticsProperties.Text
                    ]
                    .joinToString("") { it.text }
            assertTrue(text, Regex("STAT \\d+%\nT \\d\\d:\\d\\d:\\d\\d").matches(text))
            // Above the terminal, not over it.
            val status = compose.onNodeWithTag("statusLines").fetchSemanticsNode().boundsInRoot
            val terminal = compose.onNodeWithTag("terminalSurface").fetchSemanticsNode().boundsInRoot
            assertTrue("$status over $terminal", status.bottom <= terminal.top)
        } finally {
            storage.set("terminal.statusLines", "")
        }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("statusLines").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun statusChargingFollowsTheBatteryStatus(): Unit = runBlocking {
        // Simulate battery states only on the emulator, never on someone's phone.
        Assume.assumeTrue(android.os.Build.MODEL.startsWith("sdk_gphone"))
        homeTerminal()
        try {
            shell("cmd battery unplug")
            shell("cmd battery set status 3")
            shell("cmd battery set level 60")
            storage.set("terminal.statusLines", "{time:HH:mm:ss} {battery}{charging:+}|{charging}|{if charging && exists(batteryPercent)}{color:#50FA7B}⚡{/color}{else}{if !charging}battery{end}{end}")
            fun expect(charging: Boolean) {
                val suffix = if (charging)
                    "%+|${context.getString(TextR.string.status_charging)}|⚡"
                else "%||battery"
                compose.waitUntil(5000) {
                    compose.onAllNodesWithTag("statusLines").fetchSemanticsNodes().any { node ->
                        node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)
                            ?.joinToString("") { it.text }?.endsWith(suffix) == true
                    }
                }
            }
            expect(false)
            shell("cmd battery set ac 1")
            shell("cmd battery set status 2")
            expect(true)
            shell("cmd battery set status 4")
            expect(false)
            shell("cmd battery set status 5")
            expect(true)
            shell("cmd battery unplug")
            shell("cmd battery set status 3")
            expect(false)
        } finally {
            shell("cmd battery reset")
        }
    }

    @Test
    fun statusOverflowControlsHeightWithoutLosingTemplate(): Unit = runBlocking {
        homeTerminal()
        val original = "a long custom status value ".repeat(5) + "{right}78%"
        fun appearance(mode: String) = io.luftaquila.hometty.core.StatusAppearance(
            text = io.luftaquila.hometty.core.StatusTextStyle(fontSize = 16, overflow = mode),
        ).encode()
        storage.update(mapOf("terminal.statusLines" to original, "terminal.statusAppearance" to appearance("clip")))
        compose.waitUntil(5000) { compose.onAllNodesWithTag("statusLines").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val clipped = compose.onNodeWithTag("statusLines").fetchSemanticsNode().boundsInRoot.height
        storage.set("terminal.statusAppearance", appearance("wrap"))
        compose.waitUntil(5000) {
            compose.onNodeWithTag("statusLines").fetchSemanticsNode().boundsInRoot.height > clipped * 2
        }
        storage.set("terminal.statusAppearance", appearance("ellipsis"))
        compose.waitUntil(5000) {
            compose.onNodeWithTag("statusLines").fetchSemanticsNode().boundsInRoot.height == clipped
        }
        assertEquals(original, storage.snapshot().string("terminal.statusLines"))
        val status = compose.onNodeWithTag("statusLines").fetchSemanticsNode().boundsInRoot
        val terminal = compose.onNodeWithTag("terminalSurface").fetchSemanticsNode().boundsInRoot
        assertTrue(status.bottom <= terminal.top)
    }

    @Test
    fun statusEditorCancelPreservesTextAndAppearance(): Unit = runBlocking {
        val original = "first {battery}\n\nlast {time}"
        val originalStyle = io.luftaquila.hometty.core.StatusAppearance(padding = 12).encode()
        storage.update(mapOf("terminal.statusLines" to original, "terminal.statusAppearance" to originalStyle))
        val model = model
        compose.runOnUiThread { model.navigate(Route.SettingsPage("terminal")) }
        compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("setting:terminal.statusLines"))
        compose.onNodeWithTag("setting:terminal.statusLines").performClick()
        val field = compose.onNodeWithTag("statusFormat")
        field.assert(hasText(original))
        field.performTextReplacement("AB")
        field.performTextInputSelection(androidx.compose.ui.text.TextRange(1))
        compose.onNodeWithTag("placeholder:{right}").performScrollTo().performClick()
        field.performScrollTo().assert(hasText("A{right}B"))
        compose.onNodeWithTag("placeholder:{charging:+}").performScrollTo().performClick()
        field.performScrollTo().assert(hasText("A{right}{charging:+}B"))
        field.performTextReplacement("")
        compose.onNodeWithTag("statusInsertCondition").assertDoesNotExist()
        compose.onNodeWithTag("statusConditionsToggle").performScrollTo().performClick()
        compose.onNodeWithTag("statusInsertCondition").performScrollTo().performClick()
        field.performScrollTo().assert(hasText("{if charging}+{else}-{end}"))
        compose.onNodeWithTag("statusConditionsToggle").performScrollTo().performClick()
        compose.onNodeWithTag("statusInsertCondition").assertDoesNotExist()
        field.performScrollTo()
        field.performTextReplacement("{if batteryPercent <}invalid{end}")
        compose.onNodeWithTag("statusSave").assertIsNotEnabled()
        field.performScrollTo().performTextReplacement("draft {time}{right}{battery}\nmy {network}")
        // The real preview resolves the draft without changing saved content.
        compose.waitUntil(5000) {
            compose.onAllNodes(hasText("draft", substring = true) and !hasSetTextAction())
                .fetchSemanticsNodes().any {
                    val text = it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)
                        ?.joinToString("") { value -> value.text }.orEmpty()
                    "{time}" !in text && "%" in text
                }
        }
        assertEquals(original, storage.snapshot().string("terminal.statusLines"))
        compose.onNodeWithTag("statusTextColor").performScrollTo().performTextReplacement("#oops")
        compose.onNodeWithTag("statusSave").assertIsNotEnabled()
        compose.onNodeWithTag("statusCancel").performClick()
        assertEquals(original, storage.snapshot().string("terminal.statusLines"))
        assertEquals(originalStyle, storage.snapshot().string("terminal.statusAppearance"))
    }

    @Test
    fun statusEditorSavesPerLineStylesAndResetKeepsContent(): Unit = runBlocking {
        val original = "my {time}{right}{battery}\nnotes stay mine"
        storage.update(mapOf("terminal.statusLines" to original, "terminal.statusAppearance" to "{}"))
        val model = model
        compose.runOnUiThread { model.navigate(Route.SettingsPage("terminal")) }
        fun openEditor() {
            compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("setting:terminal.statusLines"))
            compose.onNodeWithTag("setting:terminal.statusAppearance").assertDoesNotExist()
            compose.onNodeWithTag("setting:terminal.statusLines").performClick()
        }
        openEditor()
        // The caret starts at the end: the second source line is selected.
        compose.onNodeWithTag("statusStyleTarget").performScrollTo().performClick()
        compose.onNodeWithTag("statusStyleTarget:line").performClick()
        compose.onNodeWithTag("statusTextColor").performScrollTo().performTextReplacement("#8BE9FD")
        compose.onNodeWithTag("statusAlignment").performScrollTo().performClick()
        compose.onNodeWithTag("statusAlignment:right").performClick()
        compose.onNodeWithTag("statusOverflow").performScrollTo().performClick()
        compose.onNodeWithTag("statusOverflow:wrap").performClick()
        compose.onNodeWithTag("statusSave").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("statusFormat").fetchSemanticsNodes().isEmpty() }
        val saved = io.luftaquila.hometty.core.StatusAppearance.decode(storage.snapshot().string("terminal.statusAppearance"))
        assertEquals(original, storage.snapshot().string("terminal.statusLines"))
        assertEquals(io.luftaquila.hometty.core.StatusTextStyle(), saved.text)
        assertEquals(io.luftaquila.hometty.core.StatusTextStyle(color = "#8BE9FD", alignment = "right", overflow = "wrap"), saved.lines[1])
        openEditor()
        compose.onNodeWithTag("statusFormat").assert(hasText(original))
        compose.onNodeWithTag("statusResetAppearance").performScrollTo().performClick()
        compose.onNodeWithTag("statusFormat").performScrollTo().assert(hasText(original))
        compose.onNodeWithTag("statusSave").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("statusFormat").fetchSemanticsNodes().isEmpty() }
        assertEquals(original, storage.snapshot().string("terminal.statusLines"))
        assertEquals(io.luftaquila.hometty.core.StatusDefaults.appearance, io.luftaquila.hometty.core.StatusAppearance.decode(storage.snapshot().string("terminal.statusAppearance")))
    }

    @Test
    fun theShellStartsWithNoSignalsBlocked(): Unit = runBlocking {
        val session = requireNotNull(homeTerminal().session)
        val result = File(context.filesDir, "home/sigblk").apply { delete() }
        // The app's threads block SIGPIPE and others; the shell must not inherit that, or
        // `big | head` never ends.
        session.write("grep SigBlk /proc/\$\$/status > \"\$HOMETTY_DATA/sigblk\"\n")
        waitFor("sigblk", session) { result.exists() && result.length() > 0 }
        // SIGQUIT (3), SIGUSR1 (10) and SIGPIPE (13); bionic keeps its own real-time signal 32.
        val blocked = result.readText().trim().substringAfter('\t').toLong(16)
        assertEquals(result.readText(), 0L, blocked and 0x1204L)
        result.delete()
    }

    @Test
    fun aLineTheLauncherTypesReplacesAllOfWhatWasTyped(): Unit = runBlocking {
        val session = requireNotNull(homeTerminal().session)
        val model = model
        val result = File(context.filesDir, "home/clear-line").apply { delete() }
        File(context.filesDir, "home/clear-lineXYZ").delete()
        session.write("cd \"\$HOMETTY_DATA\"\n")
        withTimeout(5000) { session.atPrompt.first { it } }
        // The cursor sits before XYZ, as after tapping the typed line.
        session.write("echo XYZ")
        repeat(3) { session.key(7) }
        compose.runOnUiThread { model.submit("echo ok > clear-line") }
        waitFor("clear-line", session) { result.exists() }
        assertEquals("ok", result.readText().trim())
        assertFalse(File(context.filesDir, "home/clear-lineXYZ").exists())
        result.delete()
    }

    @Test
    fun screensOpenOnlyWhileTheLauncherIsShown(): Unit = runBlocking {
        val session = requireNotNull(homeTerminal().session)
        val model = model
        val code = File(context.filesDir, "home/hidden-code").apply { delete() }
        val error = File(context.filesDir, "home/hidden-error").apply { delete() }
        // As if the command ran from a job while another app was in front.
        compose.runOnUiThread { model.device.shown = false }
        try {
            session.write("search hidden 2> \"\$HOMETTY_DATA/hidden-error\"; echo \$? > \"\$HOMETTY_DATA/hidden-code\"\n")
            waitFor("hidden-code", session) { code.exists() && code.length() > 0 }
            assertEquals("1", code.readText().trim())
            assertTrue(error.readText(), error.readText().contains(context.getString(TextR.string.message_open_launcher_first)))
        } finally {
            compose.runOnUiThread { model.device.shown = true }
            code.delete()
            error.delete()
        }
    }

    @Test
    fun aPromptFromShrcKeepsTheMarksTheTerminalNeeds(): Unit = runBlocking {
        homeTerminal()
        val model = model
        val shrc = File(context.filesDir, "home/.shrc")
        val saved = shrc.takeIf { it.exists() }?.readText()
        suspend fun restart() {
            val old = model.session.value
            compose.runOnUiThread {
                model.stopTerminal()
                model.ensureTerminal()
            }
            compose.waitUntil(15000) {
                model.session.value.let { it != null && it !== old && it.atPrompt.value }
            }
        }
        try {
            shrc.writeText("PS1='custom\$ '\n")
            // Without the marks the shell would never count as waiting at its prompt.
            restart()
            val session = requireNotNull(model.session.value)
            waitFor("the custom prompt", session) { visibleText(session).contains("custom$") }
        } finally {
            if (saved != null) shrc.writeText(saved) else shrc.delete()
            restart()
        }
    }

    @Test
    fun inputBeforeTheFirstPromptWaitsForIt(): Unit = runBlocking {
        val result = File(context.filesDir, "home/early-input").apply { delete() }
        val setup =
            io.luftaquila.hometty.core.ShellPrompt.setup(io.luftaquila.hometty.core.SettingsSnapshot()) +
                "printf 'BANNER\\n'\n"
        val session = TerminalSession(context, null, "", 100, setup)
        try {
            // Sent before the shell has drawn anything: the terminal must not echo it above the
            // startup output, and the shell still runs it at its prompt.
            session.write("echo early > early-input\n")
            withTimeout(5000) {
                while (!result.exists() || result.readText().trim() != "early") delay(50)
            }
            assertEquals("BANNER", visibleText(session).lines().first().trim())
        } finally {
            session.close()
            result.delete()
        }
    }

    @Test
    fun theTerminalViewStaysWhileAnotherScreenCoversIt(): Unit = runBlocking {
        storage.set("input.autoKeyboard", "true")
        try {
            val terminal = homeTerminal()
            val model = model
            fun <T> onUi(read: () -> T): T = compose.runOnUiThread(read)
            openSettings()
            compose.waitUntil(5000) { onUi { terminal.visibility == android.view.View.INVISIBLE } }
            assertFalse(onUi { terminal.isFocused })
            assertTrue(onUi { terminal.isAttachedToWindow })
            compose.runOnUiThread { model.back() }
            // The same view comes back and takes focus for the keyboard at once.
            compose.waitUntil(5000) { onUi { terminal.visibility == android.view.View.VISIBLE } }
            assertTrue(onUi { terminal.isFocused })
            assertSame(terminal, homeTerminal())
        } finally {
            // Before the next test's activity starts with it.
            storage.set("input.autoKeyboard", "false")
        }
    }

    /** The home terminal's view, once its shell waits at its first prompt. */
    private fun homeTerminal(): io.luftaquila.hometty.terminal.TerminalView {
        fun find(view: android.view.View): io.luftaquila.hometty.terminal.TerminalView? {
            if (view is io.luftaquila.hometty.terminal.TerminalView) return view
            if (view is android.view.ViewGroup)
                for (i in 0 until view.childCount) find(view.getChildAt(i))?.let {
                    return it
                }
            return null
        }
        var terminal: io.luftaquila.hometty.terminal.TerminalView? = null
        compose.waitUntil(15000) {
            compose.runOnUiThread {
                terminal =
                    find(compose.activity.findViewById<android.view.View>(android.R.id.content))
            }
            terminal?.session?.atPrompt?.value == true
        }
        return requireNotNull(terminal)
    }

    @Test
    fun homeShellPreservesCwdEnvironmentPipelinesAndConfigRoundTrip(): Unit = runBlocking {
        val terminal = homeTerminal()
        val session = requireNotNull(terminal.session)
        val folder = File(context.filesDir, "home/integration-cwd").apply { mkdirs() }
        val before = File(folder, "before.txt").apply { delete() }
        val after = File(folder, "after.txt").apply { delete() }
        session.write(
            "cd \"\$HOMETTY_DATA/integration-cwd\"; export HOMETTY_PROBE=kept; printf '%s' \"\$HOMETTY_PROBE\" | tr a-z A-Z > before.txt\n"
        )
        waitFor("before.txt", session) { before.exists() && before.readText() == "KEPT" }
        openSettings()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertSame(session, homeTerminal().session)
        session.write("printf '%s:%s' \"\$PWD\" \"\$HOMETTY_PROBE\" > after.txt\n")
        withTimeout(5000) {
            while (!after.exists() || !after.readText().endsWith(":kept")) delay(50)
        }
        assertEquals(folder.path + ":kept", after.readText())
        withTimeout(5000) { session.atPrompt.first { it } }
        File(folder, "completion-proof.txt").writeText("COMPLETED_INPUT")
        val completion = File(folder, "completion-result.txt").apply { delete() }
        session.write("cat completion-pr")
        session.key(2)
        waitFor("the completed file name", session) { "completion-proof.txt" in unwrapped(session) }
        session.write(" > completion-result.txt\n")
        withTimeout(5000) {
            while (!completion.exists() || completion.readText() != "COMPLETED_INPUT") delay(50)
        }
        withTimeout(5000) { session.atPrompt.first { it } }
        val resized = File(folder, "resize-result.txt").apply { delete() }
        session.write("printf '%s' RESIZE")
        waitFor("the typed half", session) { "RESIZE" in unwrapped(session) }
        session.resize(20, 40)
        // The shell redraws the line after the resize; typing goes on at its end.
        delay(150)
        session.write("_PRESERVED > resize-result.txt\n")
        withTimeout(5000) { while (!resized.exists()) delay(50) }
        assertEquals("RESIZE_PRESERVED", resized.readText())
        withTimeout(5000) { session.atPrompt.first { it } }
        session.write("sleep 60\n")
        // Ctrl+C must reach sleep, not the shell still reading the line.
        delay(200)
        compose.runOnUiThread {
            terminal.dispatchKeyEvent(
                android.view.KeyEvent(
                    0,
                    0,
                    android.view.KeyEvent.ACTION_DOWN,
                    android.view.KeyEvent.KEYCODE_C,
                    0,
                    android.view.KeyEvent.META_CTRL_ON,
                )
            )
        }
        withTimeout(5000) { session.atPrompt.first { it } }
    }

    @Test
    fun oneLookupOrderForAliasesLauncherBusyBoxAndroidAndApps(): Unit = runBlocking {
        val session = requireNotNull(homeTerminal().session)
        val result = File(context.filesDir, "home/lookup.txt").apply { delete() }
        try {
            session.write(
                "alias lookup_probe='apps list'; { command -v lookup_probe; command -v notes; " +
                    "command -v ls; command -v settings; type alias; type help; printf '%s\\n' \"\$HOME\" \"\$HISTFILE\"; } > \"\$HOMETTY_DATA/lookup.txt\"\n"
            )
            withTimeout(8000) {
                while (!result.exists() || result.readLines().size < 8) delay(50)
            }
            val lines = result.readLines()
            assertTrue(lines[0], lines[0].endsWith("/files/commands/lookup_probe"))
            assertTrue(lines[1], lines[1].endsWith("/files/commands/notes"))
            assertTrue(lines[2], lines[2].endsWith("/files/bin/ls"))
            assertEquals("/system/bin/settings", lines[3])
            assertTrue(lines[4], lines[4].contains("function"))
            assertTrue(lines[5], lines[5].contains("function"))
            // Home defaults to internal storage; history stays in the app's own folder.
            assertEquals(sharedStorage().canonicalPath, lines[6])
            assertEquals(File(context.filesDir, "home/.sh_history").path, lines[7])
            assertNotNull(storage.find("alias", "lookup_probe"))
        } finally {
            result.delete()
        }
    }

    @Test
    fun shellHistorySurvivesSessionRestart(): Unit = runBlocking {
        val history = File(context.filesDir, "integration-shell-history").apply { delete() }
        val setup =
            "export HISTFILE='${history.path}' HISTFILESIZE=20 HISTSIZE=20\n" +
                io.luftaquila.hometty.core.ShellPrompt.setup(io.luftaquila.hometty.core.SettingsSnapshot())
        val first = TerminalSession(context, null, "", 100, setup)
        try {
            withTimeout(5000) { first.atPrompt.first { it } }
            first.write("echo HISTORY_TOKEN\n")
            withTimeout(5000) { first.atPrompt.first { it } }
            first.write("exit\n")
            withTimeout(5000) { first.exit.first { it != null } }
            assertTrue(history.readText().contains("HISTORY_TOKEN"))
        } finally {
            first.close()
        }
        val second = TerminalSession(context, null, "", 100, setup)
        try {
            withTimeout(5000) { second.atPrompt.first { it } }
            second.write("history\n")
            waitFor("the restored history", second) { "HISTORY_TOKEN" in visibleText(second) }
        } finally {
            second.close()
            history.delete()
        }
    }

    @Test
    fun promptSettingsCannotExecuteShellCode(): Unit = runBlocking {
        val injected = File(context.filesDir, "prompt-injected").apply { delete() }
        val complete = File(context.filesDir, "prompt-complete").apply { delete() }
        val settings =
            io.luftaquila.hometty.core.SettingsSnapshot(
                values =
                    mapOf(
                        "home.username" to "\$(touch ${injected.path})",
                        "home.prompt" to "{user}:{cwd} $",
                    )
            )
        val session =
            TerminalSession(context, null, "", 100, io.luftaquila.hometty.core.ShellPrompt.setup(settings))
        try {
            withTimeout(5000) { session.atPrompt.first { it } }
            session.write("touch '${complete.path}'\n")
            withTimeout(5000) { while (!complete.exists()) delay(50) }
            assertFalse(injected.exists())
        } finally {
            session.close()
            complete.delete()
        }
    }

    @Test
    fun newFeedItemsPrintInTheTerminalAfterTheFirstFetch(): Unit = runBlocking {
        val session = requireNotNull(homeTerminal().session)
        val network = (context.applicationContext as PlatformDependencies).network
        val feed = storage.put("rss", "item-probe", mapOf("url" to "https://example.com/feed"))
        // The first fetch only records what the feed already has.
        network.record(feed, listOf("OLD_FEED_ITEM"))
        network.record(feed, listOf("NEW_FEED_ITEM", "OLD_FEED_ITEM"))
        waitFor("the new item", session) { "item-probe: NEW_FEED_ITEM" in visibleText(session) }
        val text = visibleText(session)
        assertTrue(text, Regex("""\[\d\d:\d\d:\d\d] item-probe: NEW_FEED_ITEM""").containsMatchIn(text))
        assertFalse(text, text.contains("item-probe: OLD_FEED_ITEM"))
    }

    /** The screen's rows joined, so a line the terminal wrapped reads as one. */
    private fun unwrapped(session: TerminalSession) = visibleText(session).lines().joinToString("")

    /** The terminal's screen as text, one line per row. */
    private fun visibleText(session: io.luftaquila.hometty.terminal.TerminalSession): String {
        val cells = session.cells()
        if (cells.isEmpty()) return ""
        return (0 until session.rows).joinToString("\n") { row ->
            buildString {
                for (col in 0 until session.cols) {
                    val cp = cells[2 + (row * session.cols + col) * 10]
                    append(if (cp > 0 && Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else " ")
                }
            }.trimEnd()
        }
    }

    @Test
    fun backupRoundTripAndMalformedImportDoNotLoseData() = runBlocking {
        storage.put("note", "integration-note", mapOf("text" to "보존할 메모"))
        storage.set("home.username", "backup-user")
        storage.set("terminal.rootEnabled", "true")
        val bytes = ByteArrayOutputStream().also { storage.export(it) }.toByteArray()
        storage.set("home.username", "changed")
        storage.restore(storage.inspect(ByteArrayInputStream(bytes)))
        assertEquals("backup-user", storage.snapshot().string("home.username"))
        assertEquals("보존할 메모", storage.find("note", "integration-note")?.fields?.get("text"))
        // A restored backup never turns root on.
        assertFalse(storage.snapshot().bool("terminal.rootEnabled"))
        assertThrows(IllegalArgumentException::class.java) {
            storage.inspect(ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        }
        assertNotNull(storage.find("note", "integration-note"))
    }

    @Test
    fun aliasesRunInTheHomeShellWithLiteralArgumentsAndStopCycles(): Unit = runBlocking {
        val session = requireNotNull(homeTerminal().session)
        val folder =
            File(context.filesDir, "home/alias-contract").apply {
                deleteRecursively()
                mkdirs()
            }
        val payload = "literal; config set home.username INJECTED"
        storage.set("home.username", "safe")
        try {
            session.write(
                "cd \"\$HOMETTY_DATA/alias-contract\"; touch zz1; " +
                    "alias save_arg='notes add \${1}' pipe_arg='printf \"<%s>\" \${1} | tr a-z A-Z' " +
                    "cycle_a='cycle_b' cycle_b='cycle_a'; " +
                    "save_arg ${io.luftaquila.hometty.core.Shell.quote(payload)}; pipe_arg 'zz* x' > pipe.txt; " +
                    "cycle_a 2> cycle.err; printf '%s' \$? > cycle.code; " +
                    // ls runs once first: the shell remembers where it found it, and the alias
                    // must still take over at once.
                    "ls > /dev/null; alias ls='ls -d /'; ls > self.txt; unalias ls; printf done > done.txt\n"
            )
            waitFor("done.txt", session, 10000) { File(folder, "done.txt").exists() }
            assertEquals("safe", storage.snapshot().string("home.username"))
            assertTrue(storage.list("note").any { it.fields["text"] == payload })
            // The body ran in the home shell; the argument stayed one literal value.
            assertEquals("<ZZ* X>", File(folder, "pipe.txt").readText())
            assertEquals("1", File(folder, "cycle.code").readText())
            assertTrue(File(folder, "cycle.err").readText().contains("alias cycle"))
            // An alias that calls its own name runs the command it hides, as shell aliases do.
            assertEquals("/", File(folder, "self.txt").readText().trim())
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun notificationsPrintAboveThePromptInTheirAppColor(): Unit = runBlocking {
        var session = requireNotNull(homeTerminal().session)
        fun lines() = visibleText(session).lines()
        fun row(text: String) = lines().indexOfFirst { it.contains(text) }
        /** The color where the message on [row] starts, after the time. */
        fun color(row: Int): Int {
            val start = lines()[row].indexOf("NOTICE").coerceAtLeast(0)
            return session.cells()[cellIndex(session, row, start) + 6]
        }
        // Tags differ per run: a snoozed key (see the cleanup below) keeps later posts hidden.
        val run = System.currentTimeMillis().toString(36)
        // `cmd notification post` posts as the shell package; the listener may still be
        // connecting, so a post is repeated until it shows (an unchanged repeat is skipped).
        suspend fun post(tag: String, text: String) {
            withTimeout(15000) {
                while (row("NOTICE HOMETTY_TEST: $text") < 0) {
                    shell("cmd notification post -t HOMETTY_TEST hometty-$run-$tag $text")
                    delay(1000)
                }
            }
        }
        val component = "${context.packageName}/io.luftaquila.hometty.platform.NotificationAccess"
        // Left as it was found: other tests and the emulator's owner may rely on it.
        val listening = component in shell("settings get secure enabled_notification_listeners")
        storage.set("notifications.enabled", "true")
        storage.set("notifications.includeByDefault", "false")
        storage.put(
            "notificationRule",
            "integration-rule",
            mapOf(
                "package" to "com.android.shell",
                "enabled" to "true",
                "color" to "#FF5555",
                "format" to "NOTICE {title}: {text}",
            ),
        )
        try {
            shell("cmd notification allow_listener $component")
            session.write("clear\n")
            withTimeout(5000) { session.atPrompt.first { it } }

            // Waiting at an empty prompt: the notice goes above it, in the rule's color.
            post("first", "FIRST")
            val first = row("NOTICE HOMETTY_TEST: FIRST")
            assertEquals(0xFFFF5555.toInt(), color(first))
            assertTrue(lines()[first], Regex("""^\[\d\d:\d\d:\d\d] NOTICE""").containsMatchIn(lines()[first]))
            waitFor("prompt below FIRST", session, 10000) { lines()[first + 1].endsWith("$") }

            // Typing does not hold a notice back; the typed input stays on the redrawn prompt
            // line and is still pending.
            session.write("echo KEPT")
            post("second", "SECOND")
            val second = row("NOTICE HOMETTY_TEST: SECOND")
            waitFor("typed input below SECOND", session, 10000) { lines()[second + 1].endsWith("$ echo KEPT") }
            assertTrue(session.inputPending.value)
            session.write("\n")
            waitFor("KEPT output", session, 10000) { lines().any { it == "KEPT" } }

            // While a command runs (the write marks it running at once), the notice waits for
            // the next prompt.
            session.write("sleep 2; echo DONE\n")
            shell("cmd notification post -t HOMETTY_TEST hometty-$run-third THIRD")
            waitFor("THIRD after the command", session, 10000) { row("NOTICE HOMETTY_TEST: THIRD") >= 0 }
            val done = lines().indexOfFirst { it == "DONE" }
            assertTrue(lines().joinToString("\n"), done >= 0 && done < row("NOTICE HOMETTY_TEST: THIRD"))

            // With no shell running, a notice waits and prints in the next one.
            val model = model
            val stopped = session
            compose.runOnUiThread { model.stopTerminal() }
            withTimeout(5000) { stopped.exit.first { it != null } }
            shell("cmd notification post -t HOMETTY_TEST hometty-$run-fourth FOURTH")
            // Time for the listener to deliver it while no shell runs.
            delay(1000)
            compose.runOnUiThread { model.ensureTerminal() }
            // The new shell starts once its view is measured; waitUntil lets the screen redraw.
            compose.waitUntil(15000) {
                model.session.value.let { it != null && it !== stopped && it.atPrompt.value }
            }
            session = requireNotNull(model.session.value)
            waitFor("FOURTH in the new shell", session, 10000) { row("NOTICE HOMETTY_TEST: FOURTH") >= 0 }
        } finally {
            if (!listening) shell("cmd notification disallow_listener $component")
            // There is no shell command to cancel them; snoozing clears the shade and the
            // heads-up for the tests that follow.
            shell("cmd notification list")
                .lines()
                .filter { it.startsWith("0|com.android.shell|") && "|hometty-" in it }
                .forEach { shell("cmd notification snooze --for 86400000 $it") }
        }
    }

    @Test
    fun busyboxExecutesAndInteractiveViSavesThroughPty() = runBlocking {
        val file = File(context.filesDir, "pty-integration.txt")
        file.delete()
        val bb = io.luftaquila.hometty.terminal.BusyBox.executable(context)
        val check = ProcessBuilder(bb.absolutePath, "--list").redirectErrorStream(true).start()
        val applets = check.inputStream.bufferedReader().readText()
        assertEquals(0, check.waitFor())
        assertTrue(applets.lines().contains("vi"))
        val session =
            TerminalSession(
                context,
                "'${bb.absolutePath}' vi '${file.absolutePath}'",
                "",
                100,
            )
        try {
            withTimeout(10000) { while (session.revision.value < 1) delay(50) }
            session.resize(30, 100)
            delay(100)
            assertNull("Terminal exited before input: ${visibleText(session)}", session.exit.value)
            session.write("iPTY_EDIT\n")
            delay(200)
            session.key(4)
            // vi reads a lone ESC only after its escape-sequence timeout.
            delay(250)
            session.write(":wq\n")
            val code = withTimeoutOrNull(10000) { session.exit.first { it != null } }
            assertNotNull("vi did not exit: ${visibleText(session)}", code)
            assertEquals(0, code)
            assertTrue(file.readText().contains("PTY_EDIT"))
        } finally {
            session.close()
            file.delete()
        }
    }

    @Test
    fun nativeShellHandlesUnicodeResizeAndCancellation(): Unit = runBlocking {
        val session = TerminalSession(context, null, "", 100)
        try {
            session.resize(30, 100)
            session.write("printf '한글_PTY_OK\\n'; stty size\n")
            // Wide characters take two columns; visibleText leaves the second one blank.
            waitFor("the output", session, 10000) {
                visibleText(session).let { "한 글 _PTY_OK" in it && "30 100" in it }
            }
            session.write("sleep 60\n")
            delay(100)
            session.write("\u0003")
            session.write("exit\n")
            withTimeout(10000) { session.exit.first { it != null } }
        } finally {
            session.close()
        }
    }
}
