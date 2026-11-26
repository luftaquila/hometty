package io.luftaquila.hometty.commands

import io.luftaquila.hometty.core.Collections
import io.luftaquila.hometty.core.Entry
import io.luftaquila.hometty.core.EntryStore
import io.luftaquila.hometty.core.Settings
import io.luftaquila.hometty.core.SettingsSnapshot
import io.luftaquila.hometty.core.SettingsStore
import io.luftaquila.hometty.core.StateStore
import io.luftaquila.hometty.AppCatalog
import io.luftaquila.hometty.Route
import javax.inject.Provider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Launcher commands run on the JVM against in-memory stores; nothing Android is needed. */
class CommandsTest {
    private class MemoryStore : SettingsStore, EntryStore, StateStore {
        override val settings = MutableStateFlow(SettingsSnapshot())
        val entries = mutableListOf<Entry>()

        override suspend fun snapshot() = settings.value

        override suspend fun set(key: String, value: String) = update(mapOf(key to value))

        override suspend fun update(values: Map<String, String>) {
            values.forEach { (key, value) -> requireNotNull(Settings.byKey[key]).validate(value) }
            settings.value = settings.value.copy(values = settings.value.values + values)
        }

        override suspend fun reset(key: String) {
            settings.value = settings.value.copy(values = settings.value.values - key)
        }

        override suspend fun list(kind: String) = entries.filter { it.kind == kind }

        override suspend fun find(kind: String, name: String) =
            entries.firstOrNull { it.kind == kind && (it.name == name || it.id == name) }

        override suspend fun put(kind: String, name: String, fields: Map<String, String>): Entry {
            Collections.validate(kind, name, fields)
            val previous = find(kind, name)
            entries.remove(previous)
            return Entry(previous?.id ?: "$kind-${entries.size}", kind, name, fields).also {
                entries += it
            }
        }

        override suspend fun modify(
            kind: String,
            name: String,
            change: (Map<String, String>) -> Map<String, String>,
        ) = put(kind, name, change(find(kind, name)?.fields.orEmpty()))

        override suspend fun remove(kind: String, name: String) {
            val entry = requireNotNull(find(kind, name))
            Collections.validateRemoval(entry)
            entries.remove(entry)
        }

        val state = mutableMapOf<String, String>()

        override suspend fun getState(name: String) = state[name]

        override suspend fun putState(values: Map<String, String>) = state.putAll(values)
    }

    private object KeyTexts : Texts {
        override fun string(id: Int, vararg arguments: Any) = "text#$id"

        override fun key(key: String) = key

        override fun message(error: Throwable) = error.message.orEmpty()
    }

    private val store = MemoryStore()
    private var refreshes = 0
    private val names =
        object : CommandNames {
            override suspend fun refresh() {
                refreshes++
            }
        }

    /** Android services are out of reach here; a test that needs one fails loudly. */
    private fun <T> unavailable() = Provider<T> { error("Android service used in a JVM test") }

    private val engine =
        LauncherEngine(
            CommandRegistry(
                listOf(
                        AppsCommands(unavailable(), store, unavailable(), names),
                        ToolCommands(store, store, unavailable(), unavailable(), unavailable(), unavailable()),
                        LauncherCommands(store),
                    )
                    .flatMap { it.commands }
            ),
            KeyTexts,
            store,
            store,
            Provider { AppCatalog(unavailable(), store, KeyTexts) },
        )

    private fun run(vararg argv: String) = runBlocking { engine.execute(argv.toList()).toList() }

    private fun List<CommandEvent>.code() = (last() as CommandEvent.Finished).code

    private fun List<CommandEvent>.output() =
        filterIsInstance<CommandEvent.Output>().joinToString("\n") { it.text }

    @Test
    fun exitStatusFollowsUnixConventions() {
        val sum = run("calc", "2", "+", "3")
        assertEquals(0, sum.code())
        // The home shell computes it: calc answers with awk and the operands as arguments.
        assertTrue(sum.filterIsInstance<CommandEvent.Exec>().single().script.contains("BEGIN { print (\$*) }"))
        assertEquals(2, run("notes", "--bogus").code())
        assertEquals(2, run("config", "nowhere").code())
        // Unknown keys and the font flag are usage errors, not crashes.
        assertEquals(2, run("config", "get", "no.such").code())
        assertEquals(2, run("config", "set", "appearance.customFont", "x").code())
        assertEquals(127, run("no_such_name").code())
        assertTrue(run("notes", "--help").output().contains("--help"))
        assertTrue(run("hometty", "--version").output().startsWith("hometty "))
    }

    @Test
    fun guiAndSettingsOpenScreens() {
        assertEquals(CommandEvent.Navigate(Route.Manage("note")), run("notes", "--gui").first())
        assertEquals(CommandEvent.Navigate(Route.SettingsPage("appearance")), run("config", "appearance").first())
        assertEquals(2, run("calc", "--gui", "1").code())
    }

    @Test
    fun aliasesAreSavedAndRunByTheShell() {
        assertEquals(0, run("alias", "work=notes add \${1}").code())
        assertEquals(1, refreshes)
        assertEquals("alias work='notes add \${1}'", run("alias", "work").output())
        // A reserved name is refused and changes nothing.
        assertEquals(1, run("alias", "notes=calc 1").code())
        assertEquals(1, refreshes)
        val exec = run("work", "a b").first() as CommandEvent.Exec
        assertTrue(exec.script, exec.script.endsWith("notes add \${1}"))
        assertEquals(0, run("unalias", "work").code())
        assertNull(runBlocking { store.find("alias", "work") })
        assertEquals(2, refreshes)
    }

    @Test
    fun notesKeepLockedTextUnchanged() {
        run("notes", "add", "first note")
        val note = runBlocking { store.list("note").single() }
        assertEquals(0, run("notes", "lock", note.name).code())
        assertEquals(1, run("notes", "set", note.name, "changed").code())
        assertEquals(1, run("notes", "rm", note.name).code())
        run("notes", "unlock", note.name)
        assertEquals(0, run("notes", "set", note.name, "changed").code())
        assertEquals("changed", runBlocking { store.find("note", note.name) }!!.fields["text"])
        assertTrue(run("notes").output().endsWith(": changed"))
    }

    @Test
    fun configUsesTheSettingDefinitions() {
        assertEquals("home.username = me", run("config", "set", "home.username", "me").output())
        assertEquals("me", run("config", "get", "home.username").output())
        assertEquals(1, run("config", "set", "appearance.fontSize", "999").code())
        assertEquals("", run("config", "set", "-q", "home.device", "phone").output())
        run("config", "reset", "home.username")
        assertEquals("user", run("config", "get", "home.username").output())
        assertEquals(0, run("theme", "set", "nord").code())
        assertEquals("nord", runBlocking { store.snapshot() }.string("appearance.theme"))
        // config set of the preset applies its colors too, as theme set does.
        assertEquals(0, run("config", "set", "appearance.theme", "gruvbox").code())
        assertEquals(
            io.luftaquila.hometty.core.Themes.values("gruvbox")["appearance.background"],
            runBlocking { store.snapshot() }.string("appearance.background"),
        )
    }

    @Test
    fun notesAddedInOneMillisecondKeepTheirOwnNames() {
        repeat(5) { run("notes", "add", "same moment $it") }
        assertEquals(5, runBlocking { store.list("note") }.size)
    }

    @Test
    fun outputLimitsCountRecordsNotLines() {
        // Two-line notes: -n 2 keeps two whole notes, four lines.
        repeat(3) { run("notes", "add", "note $it\nsecond line") }
        assertEquals(4, run("notes", "-n", "2").output().lines().size)
    }

    @Test
    fun helpListsSectionsAndCommands() {
        val help = run("help").output()
        assertTrue(help.contains("label_help_tools"))
        assertTrue(help.contains("notes"))
    }

    @Test
    fun registryRejectsTwoImplementationsOfOneCommand() {
        val notes = ToolCommands(store, store, unavailable(), unavailable(), unavailable(), unavailable()).commands
        assertThrows(IllegalArgumentException::class.java) { CommandRegistry(notes + notes) }
        assertTrue("wifi" in CommandRegistry(notes).missing)
    }
}
