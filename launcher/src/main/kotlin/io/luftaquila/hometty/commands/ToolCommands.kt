package io.luftaquila.hometty.commands

import io.luftaquila.hometty.Route
import io.luftaquila.hometty.core.Commands
import io.luftaquila.hometty.core.EntryStore
import io.luftaquila.hometty.core.StateNames
import io.luftaquila.hometty.core.StateStore
import io.luftaquila.hometty.platform.DeviceAccess
import io.luftaquila.hometty.platform.MusicAccess
import io.luftaquila.hometty.platform.NetworkData
import io.luftaquila.hometty.resources.R as TextR
import javax.inject.Inject
import javax.inject.Provider
import io.luftaquila.hometty.core.get

/** `help` section Tools: notes, calc, music, rss. */
class ToolCommands
@Inject
constructor(
    private val entries: EntryStore,
    private val state: StateStore,
    private val device: Provider<DeviceAccess>,
    private val network: Provider<NetworkData>,
    private val music: Provider<MusicAccess>,
) : CommandGroup {
    override val commands =
        listOf(
            command(Commands.notes, gui = Route.Manage("note")) { notes() },
            // BusyBox awk in the home shell computes it, so decimals, ^ and sqrt() work. Without an
            // expression it reads one per line, where parentheses need no quotes, until an empty one.
            command(Commands.calc) {
                exec(
                    "IFS=' '; [ \"\$#\" -eq 0 ] || exec awk -v OFMT=%.12g \"BEGIN { print (\$*) }\"\n" +
                        "while printf 'calc> ' && IFS= read -r line && [ -n \"\$line\" ]; do\n" +
                        "  awk -v OFMT=%.12g \"BEGIN { print (\$line) }\"\n" +
                        "done\n"
                )
            },
            command(Commands.music) { out(music.get().control(action)) },
            command(Commands.rss, gui = Route.Manage("rss")) { rss() },
        )

    private suspend fun CommandCall.note() =
        requireNotNull(entries.find("note", operand())) {
            string(TextR.string.message_note_not_found)
        }

    private suspend fun CommandCall.notes() {
        when (action.ifEmpty { "list" }) {
            "list" ->
                outRecords(
                    entries.list("note").map {
                        "${it.name}${if (it.fields["locked"] == "true") " 🔒" else ""}: ${it.fields["text"].orEmpty()}"
                    }
                )
            "add" -> {
                // Named by time; a note added in the same millisecond takes the next number. modify
                // checks and saves in one step, so two adds at once cannot pick the same name.
                var number = System.currentTimeMillis()
                while (true) {
                    try {
                        entries.modify("note", number.toString()) { old ->
                            if (old.isNotEmpty()) throw NameTaken()
                            mapOf("text" to text)
                        }
                        break
                    } catch (_: NameTaken) {
                        number++
                    }
                }
                out("Note $number")
            }
            "set" -> {
                val note = note()
                require(note.fields["locked"] != "true") {
                    string(TextR.string.message_unlock_note_first)
                }
                entries.put("note", note.name, note.fields + ("text" to operands.drop(1).joinToString(" ")))
                out(string(TextR.string.message_saved))
            }
            "lock",
            "unlock" -> {
                val note = note()
                entries.put("note", note.name, note.fields + ("locked" to (action == "lock").toString()))
                out("${note.name}: $action")
            }
            "copy" -> {
                device.get().copy(note().fields["text"].orEmpty())
                out(string(TextR.string.message_copied))
            }
            "rm" -> {
                entries.remove("note", operand())
                out(string(TextR.string.message_removed))
            }
            else -> usage()
        }
    }

    private suspend fun CommandCall.feed() =
        requireNotNull(entries.find("rss", operand())) {
            string(TextR.string.message_feed_not_found)
        }

    private suspend fun CommandCall.rss() {
        when (action.ifEmpty { "list" }) {
            "list" -> out(entries.list("rss").joinToString("\n") { "${it.name}: ${it.fields["url"]}" })
            // Adds the feed, or replaces the URL of one with that name.
            "add" -> {
                // Saving checks for an https URL (Collections.validate).
                entries.modify("rss", operand()) { it + ("url" to operand(1)) }
                out("Saved ${operand()}")
            }
            "rm" -> {
                entries.remove("rss", operand())
                out(string(TextR.string.message_removed))
            }
            "show" -> {
                val feed = feed()
                // The command prints the feed itself, so new items are not announced again.
                out(state.getState(StateNames.feedText(feed.id)) ?: network.get().feed(feed, announce = false))
            }
            "update" ->
                for (feed in if (operands.isEmpty()) entries.list("rss") else listOf(feed()))
                    out("${feed.name}\n${network.get().feed(feed, announce = false)}")
            else -> usage()
        }
    }
}

/** A note name already in use, so the next one is tried. */
private class NameTaken : RuntimeException()
