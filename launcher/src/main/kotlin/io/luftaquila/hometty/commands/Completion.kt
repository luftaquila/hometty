package io.luftaquila.hometty.commands

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.luftaquila.hometty.core.Cli
import io.luftaquila.hometty.core.EntryStore
import io.luftaquila.hometty.core.Operand
import io.luftaquila.hometty.resources.message
import io.luftaquila.hometty.resources.text
import io.luftaquila.hometty.runtime.AppCatalog
import javax.inject.Inject
import javax.inject.Provider

/** TAB candidates for launcher command lines, from the definitions and the saved data. */
class Completion
@Inject
constructor(private val catalog: Provider<AppCatalog>, private val entries: EntryStore) {
    /** Candidates for the last word of [words]; a leading `hometty` is skipped. */
    suspend fun candidates(words: List<String>): List<String> {
        val command = if (words.firstOrNull() == "hometty") words.drop(1).ifEmpty { listOf("") } else words
        val aliases = entries.list("alias").map { it.name }
        val feeds = entries.list("rss").map { it.name }
        // Hidden apps are offered only to apps show, which brings them back.
        val hidden = entries.list("app").filter { it.fields["hidden"] == "true" }.map { it.name }.toSet()
        val unhiding = command.getOrNull(0) == "apps" && command.getOrNull(1) == "show"
        return Cli.complete(command) { kind ->
                when (kind) {
                    Operand.APP ->
                        catalog.get().apps.value.filter { unhiding == (it.id in hidden) }.map { it.label }
                    Operand.ALIAS -> aliases
                    Operand.FEED -> feeds
                    else -> emptyList()
                }
            }
            .take(200)
    }

    /**
     * Candidates for the shell's line editor. Command names stay with the shell's own PATH
     * completion, so only arguments are completed here.
     */
    suspend fun line(line: String): List<String> {
        val words = Cli.completionWords(line)
        if (words.size <= 1) return emptyList()
        return candidates(words)
    }
}

/** Command text from the app's resources. */
class ContextTexts @Inject constructor(@ApplicationContext private val context: Context) : Texts {
    override fun string(id: Int, vararg arguments: Any): String = context.getString(id, *arguments)

    override fun key(key: String) = context.text(key)

    override fun message(error: Throwable) = context.message(error)
}
