package io.luftaquila.hometty.core

import org.junit.Assert.*
import org.junit.Test

class CliTest {
    @Test
    fun shortOptionsAndExplicitValues() {
        assertEquals(setOf("all", "long"), Cli.parse(listOf("apps", "-al")).options.keys)
        assertEquals("google", Cli.parse(listOf("search", "-egoogle", "hello")).value("engine"))
        assertEquals(
            "youtube",
            Cli.parse(listOf("search", "--engine=youtube", "hello")).value("engine"),
        )
        assertEquals("duckduckgo", Cli.parse(listOf("search", "-e", "duckduckgo", "hi")).value("engine"))
    }

    @Test
    fun optionsStopAtFirstOperandAndDoubleDash() {
        assertEquals(listOf("-h"), Cli.parse(listOf("notes", "add", "--", "-h")).operands)
        val search = Cli.parse(listOf("search", "hello", "--engine=youtube"))
        assertNull(search.value("engine"))
        assertEquals(listOf("hello", "--engine=youtube"), search.operands)
        assertEquals(
            "--help",
            Cli.parse(listOf("config", "set", "home.username", "--help")).operands.last(),
        )
    }

    @Test
    fun malformedInvocationsFailBeforeExecution() {
        for (argv in
            listOf(
                listOf("apps", "--bogus"),
                listOf("search", "-e"),
                listOf("config", "set", "home.username"),
                listOf("apps", "launch", "--all", "Chrome"),
                listOf("apps", "--all=yes"),
                listOf("notes", "rm"),
                listOf("search", "-e", "bad", "x"),
            )) {
            assertThrows(argv.toString(), CliUsageException::class.java) { Cli.parse(argv) }
        }
    }

    @Test
    fun everyCommandHasValidatedHelpAndUniqueOptions() {
        assertEquals(Commands.all.size, Commands.byName.size)
        for (spec in Commands.all) {
            assertTrue(spec.name, Cli.parse(listOf(spec.name, "--help")).has("help"))
            assertTrue(spec.name, Cli.help(spec.name).contains("--help"))
            assertEquals(spec.name, spec.forms.size, spec.forms.map { it.action }.distinct().size)
            for (form in spec.forms) {
                assertEquals(spec.name, form.options.size, form.options.map { it.name }.distinct().size)
                assertEquals(
                    spec.name,
                    form.options.mapNotNull { it.short }.size,
                    form.options.mapNotNull { it.short }.distinct().size,
                )
            }
        }
    }

    @Test
    fun completionUsesCurrentCommandAndDoesNotEvaluateShellText() {
        assertEquals(
            listOf("config", "set", "home.user"),
            Cli.completionWords("echo ignored | config set home.user"),
        )
        assertEquals(
            listOf("apps", "launch", "Mixed C"),
            Cli.completionWords("apps launch 'Mixed C"),
        )
        assertEquals(listOf("home.username"), Cli.complete(listOf("config", "set", "home.user")))
        assertEquals(listOf("--engine"), Cli.complete(listOf("search", "--en")))
        assertTrue(Cli.complete(listOf("apps", "")).contains("launch"))
    }

    @Test
    fun helpListsEveryCommandOnce() {
        val listed = Commands.sections.values.flatten().map { it.name }
        assertEquals(Commands.all.map { it.name }, listed.distinct().sortedBy { name -> Commands.all.indexOfFirst { it.name == name } })
        assertEquals(listed.size, listed.toSet().size)
    }

    @Test
    fun operandsCompleteFromTheirDefinition() {
        val dynamic = { kind: Operand -> if (kind == Operand.APP) listOf("Maps", "Mail") else listOf("work") }
        assertEquals(listOf("Mail", "Maps"), Cli.complete(listOf("apps", "launch", "Ma"), dynamic))
        // Only the first operand names a setting.
        assertEquals(emptyList<String>(), Cli.complete(listOf("config", "set", "appearance.fontSize", "")))
        // NAME... completes every position.
        assertEquals(listOf("work"), Cli.complete(listOf("unalias", "home", "w"), dynamic))
        assertEquals(listOf("duckduckgo", "google", "youtube"), Cli.complete(listOf("search", "-e", "")))
        assertTrue(Cli.complete(listOf("config", "")).containsAll(listOf("appearance", "get", "set")))
        assertTrue("apps" in Cli.complete(listOf("help", "ap")))
    }
}
