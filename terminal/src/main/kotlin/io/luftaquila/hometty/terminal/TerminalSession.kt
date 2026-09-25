package io.luftaquila.hometty.terminal

import android.content.Context
import android.system.OsConstants
import io.luftaquila.hometty.core.Shell
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

internal object Native {
    init {
        System.loadLibrary("homettyterminal")
    }

    external fun characterWidth(codepoint: Int): Int

    external fun create(rows: Int, cols: Int, limit: Int): Long

    external fun destroy(pointer: Long)

    external fun feed(pointer: Long, bytes: ByteArray)

    external fun output(pointer: Long): ByteArray

    external fun key(pointer: Long, key: Int, modifiers: Int): ByteArray

    external fun resize(pointer: Long, fd: Int, rows: Int, cols: Int)

    external fun cells(pointer: Long, offset: Int): IntArray

    external fun historySize(pointer: Long): Int

    external fun palette(pointer: Long, colors: IntArray)

    external fun position(pointer: Long): LongArray

    external fun line(pointer: Long, absolute: Long): IntArray?

    external fun paste(pointer: Long, text: ByteArray): ByteArray

    external fun spawn(
        executable: String,
        arguments: Array<String>,
        directory: String,
        environment: Array<String>,
        rows: Int,
        cols: Int,
        defer: Boolean,
    ): IntArray?

    external fun read(fd: Int): ByteArray?

    external fun write(fd: Int, bytes: ByteArray)

    external fun waitFor(pid: Int): Int

    external fun signal(fd: Int, pid: Int, signal: Int)

    external fun close(fd: Int)
}

class TerminalSession(
    context: Context,
    command: String?,
    home: String,
    limit: Int,
    shellSetup: String = "",
    extraEnvironment: Map<String, String> = emptyMap(),
    deferUntilMeasured: Boolean = false,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val monitor = Any()
    private val writes = Channel<ByteArray>(64)
    val atPrompt = MutableStateFlow(false)
    val inputPending = MutableStateFlow(false)
    private var promptScan = ""
    /** A notice to print, with the id a tap on it reports (null: none). */
    private class Notice(val text: String, val id: Long?)

    /** Notices waiting for the next prompt while a command runs. */
    private val notices = ArrayDeque<Notice>()
    /** Where the current prompt starts (OSC 133;A): absolute line and column. */
    private var promptStart: Pair<Long, Int>? = null
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()
    private val _exit = MutableStateFlow<Int?>(null)
    val exit = _exit.asStateFlow()
    var rows = 24
        private set

    var cols = 80
        private set

    private var pointer = 0L
    private val rcFile: File
    private var startGate = -1
    private val process: IntArray
    @Volatile private var closed = false
    private val reader: Job
    private val writer: Job
    /**
     * Input that arrives before the shell's first prompt (OSC 133;A), replayed once it is drawn.
     * Until then the terminal is still in cooked mode and echoes keys among the startup output,
     * above the prompt. Null: input goes straight to the shell.
     */
    @Volatile private var early: MutableList<() -> Unit>? =
        if (shellSetup.contains("\u001b]133;A")) mutableListOf() else null
    private var fallbackStarted = false

    init {
        val directory =
            if (home.isBlank()) File(context.filesDir, "home").apply { mkdirs() } else File(home)
        val canonicalHome = directory.canonicalPath
        require(directory.isDirectory) { "Invalid home directory" }
        val bb = BusyBox.executable(context)
        val rc = File.createTempFile("shellrc-", ".sh", context.filesDir)
        rcFile = rc
        val rcText = buildString {
            append("export PS1='$ '\n")
            // BusyBox applets come from PATH (see BusyBox.installApplets), not shell aliases.
            append(shellSetup)
        }
        rc.writeText(rcText)
        val interactiveAsh = shellSetup.isNotBlank()
        val executable = if (interactiveAsh) bb.absolutePath else "/system/bin/sh"
        val launch =
            ". ${Shell.quote(rc.absolutePath)}; ${command ?: "exec /system/bin/sh -i"}"
        val args =
            if (interactiveAsh)
                if (command == null) arrayOf(executable, "ash", "-i")
                else arrayOf(executable, "ash", "-c", launch)
            else if (command != null) arrayOf(executable, "-c", launch)
            else arrayOf(executable, "-i")
        val environment =
            System.getenv()
                .toMutableMap()
                .apply {
                    put("TERM", "xterm-256color")
                    put("COLORTERM", "truecolor")
                    put("HOME", canonicalHome)
                    put("ENV", rc.absolutePath)
                    put("TMPDIR", context.cacheDir.absolutePath)
                    put("LANG", "C.UTF-8")
                    putAll(extraEnvironment)
                }
                .map { "${it.key}=${it.value}" }
                .toTypedArray()
        pointer = Native.create(rows, cols, limit)
        try {
            process =
                requireNotNull(
                    Native.spawn(
                        executable,
                        args,
                        canonicalHome,
                        environment,
                        rows,
                        cols,
                        deferUntilMeasured,
                    )
                ) {
                    "Could not create PTY"
                }
            startGate = process[2]
        } catch (error: Exception) {
            Native.destroy(pointer)
            pointer = 0
            scope.cancel()
            rc.delete()
            throw error
        }
        // Nothing is written once close() starts: the descriptor is closed after this ends.
        writer = scope.launch { for (bytes in writes) if (!closed) Native.write(process[0], bytes) }
        reader =
            scope.launch {
                try {
                    while (isActive && !closed) {
                        val bytes = Native.read(process[0]) ?: break
                        if (bytes.isEmpty()) continue
                        // The shell runs from its first output on (it may wait for the view's
                        // size first). A prompt without the mark (.shrc may replace PS1) must
                        // not hold input for good.
                        if (!fallbackStarted && early != null) {
                            fallbackStarted = true
                            scope.launch {
                                delay(2000)
                                release()
                            }
                        }
                        promptScan =
                            (promptScan + bytes.toString(Charsets.ISO_8859_1)).takeLast(32768)
                        val prompted = promptScan.contains("\u001b]133;A\u0007")
                        if (prompted) promptScan = ""
                        synchronized(monitor) {
                            if (pointer != 0L) {
                                feedMarkingInput(bytes)
                                val response = Native.output(pointer)
                                if (response.isNotEmpty()) writes.trySend(response)
                            }
                            if (prompted) {
                                // Enter or ^C left the last prompt. A prompt drawn while still
                                // at it is a redraw (resize, notice): the typed input remains.
                                if (!atPrompt.value) inputPending.value = false
                                atPrompt.value = true
                            }
                        }
                        if (prompted) release()
                        _revision.value++
                    }
                } finally {
                    _revision.value++
                }
            }
        scope.launch {
            _exit.value = Native.waitFor(process[1])
            writes.close()
        }
    }

    /** Holds [action] until the first prompt; false when input goes to the shell now. */
    private fun deferred(action: () -> Unit): Boolean =
        synchronized(monitor) {
            val held = early ?: return false
            if (held.size < 1024) held += action
            true
        }

    /**
     * Replays input held for the first prompt, in order. Under the lock, so input typed meanwhile
     * waits for it and cannot overtake it.
     */
    private fun release() {
        synchronized(monitor) {
            val held = early ?: return
            early = null
            held.forEach { runCatching { it() } }
        }
    }

    fun write(text: String) {
        if (deferred { write(text) }) return
        inputPending.value = true
        if (text.contains('\n') && atPrompt.value) synchronized(monitor) { enterPending = true }
        if (text.contains('\n') || text.contains('\r') || text.contains('\u0003')) leavePrompt()
        check(writes.trySend(text.toByteArray()).isSuccess) {
            "Terminal has exited or input queue is full"
        }
    }

    fun paste(text: String) {
        if (deferred { paste(text) }) return
        synchronized(monitor) {
            inputPending.value = true
            // A pasted line break runs the line, as Enter does.
            if (text.contains('\n') || text.contains('\r')) leavePrompt()
            if (pointer != 0L && _exit.value == null)
                writes.trySend(Native.paste(pointer, text.toByteArray()))
        }
    }

    fun key(code: Int, modifiers: Int = 0) {
        if (deferred { key(code, modifiers) }) return
        synchronized(monitor) {
            inputPending.value = true
            if (code == 1 && atPrompt.value) enterPending = true
            if (code == 1) leavePrompt()
            if (pointer != 0L && _exit.value == null)
                writes.trySend(Native.key(pointer, code, modifiers))
        }
    }

    private fun leavePrompt() =
        synchronized(monitor) {
            atPrompt.value = false
            promptStart = null
        }

    /**
     * Shows [text] (lines, optionally with SGR colors) on its own lines, between shell output:
     * above the prompt right away when the shell waits there, typed input or not, otherwise just
     * before the next prompt. [noticeAt] finds it by [id] while it stays on screen. False when
     * this terminal has ended.
     */
    fun announce(text: String, id: Long? = null): Boolean =
        synchronized(monitor) {
            if (pointer == 0L || closed || _exit.value != null) return false
            notices.addLast(Notice(text, id))
            while (notices.size > 200) notices.removeFirst()
            printAbovePrompt()
            true
        }

    /** Prints waiting notices above the prompt when the shell waits at it. */
    private fun printAbovePrompt() {
        // Only BusyBox ash is known to redraw its prompt on SIGWINCH.
        if (notices.isEmpty() || !atPrompt.value) return
        val (row, column) = promptStart ?: return
        val p = Native.position(pointer)
        val up = (p[2] + p[0] - row).toInt()
        if (up !in 0 until rows) return
        // Rows from the prompt start to the cursor as the shell counts them: its prompt starts
        // at column 0, on screen at [column].
        val shellUp = (up * cols + p[1].toInt() - column) / cols
        val out = StringBuilder("\r")
        if (up > 0) out.append("\u001b[${up}A")
        if (column > 0) out.append("\u001b[${column + 1}G")
        out.append("\u001b[0m\u001b[J")
        if (column > 0) out.append("\r\n")
        Native.feed(pointer, out.toString().toByteArray())
        feedNotices()
        // The shell redraws its prompt here; a notice that comes before that redraw goes above
        // this line, not above the old prompt and over the notices just printed.
        promptStart = Native.position(pointer).let { (it[2] + it[0]) to 0 }
        // Leave the cursor where the shell will look for its prompt: it moves up shellUp rows,
        // then draws the prompt and the typed input again. Keys already on their way are drawn
        // relative to the same place, and the redraw repeats them.
        Native.feed(pointer, "\n".repeat(shellUp).toByteArray())
        _revision.value++
        Native.signal(process[0], process[1], OsConstants.SIGWINCH)
    }

    /** A notice printed with an id: its first and last absolute lines and how it starts. */
    private class NoticeMark(val row: Long, val endRow: Long, val id: Long, val start: String)

    private val noticeMarks = ArrayDeque<NoticeMark>()

    /** Prints the waiting notices from the cursor on, each on its own lines. */
    private fun feedNotices() {
        while (notices.isNotEmpty()) {
            val notice = notices.removeFirst()
            val row = Native.position(pointer).let { it[2] + it[0] }
            Native.feed(pointer, (notice.text.replace("\n", "\r\n") + "\u001b[0m\r\n").toByteArray())
            val id = notice.id ?: continue
            val end = Native.position(pointer).let { it[2] + it[0] } - 1
            noticeMarks.addLast(NoticeMark(row, maxOf(row, end), id, noticeStart(notice.text)))
            while (noticeMarks.size > 500) noticeMarks.removeFirst()
        }
    }

    /**
     * The id of the notice shown on view row [viewRow] while scrolled back [scrollback] lines,
     * when that line still holds it (not cleared or overwritten since).
     */
    fun noticeAt(viewRow: Int, scrollback: Int): Long? =
        synchronized(monitor) {
            if (pointer == 0L) return null
            val absolute = Native.position(pointer)[2] - scrollback + viewRow
            val mark = noticeMarks.lastOrNull { absolute in it.row..it.endRow } ?: return null
            mark.id.takeIf { text(mark.row)?.startsWith(mark.start) == true }
        }

    fun resize(rows: Int, cols: Int) =
        synchronized(monitor) {
            val r = rows.coerceIn(2, 200)
            val c = cols.coerceIn(2, 300)
            if (pointer != 0L && !closed && (r != this.rows || c != this.cols)) {
                this.rows = r
                this.cols = c
                Native.resize(pointer, process[0], r, c)
                _revision.value++
            }
            if (startGate >= 0) {
                Native.write(startGate, byteArrayOf(1))
                Native.close(startGate)
                startGate = -1
            }
        }

    fun cells(offset: Int = 0): IntArray =
        synchronized(monitor) { if (pointer == 0L) IntArray(0) else Native.cells(pointer, offset) }

    fun historySize(): Int =
        synchronized(monitor) { if (pointer == 0L) 0 else Native.historySize(pointer) }

    /** A command entered at the prompt: its first line and last line, both absolute. */
    private data class CommandMark(val row: Long, val column: Int, val endRow: Long, val command: String)

    private val marks = ArrayDeque<CommandMark>()
    /** Where the prompt ended: absolute line and column of the first input cell. */
    private var inputStart: Pair<Long, Int>? = null

    /** Set by Enter at the prompt; the shell's next newline ends the echoed command line. */
    private var enterPending = false

    /**
     * Feeds output, noting where each prompt starts (OSC 133;A) and the cursor right after each
     * prompt-end mark (OSC 133;B) in PS1, and reading the entered command just before the shell
     * echoes Enter: only then is all of the typed text, sent ahead of Enter, on screen. Waiting
     * notices are printed just before a new prompt.
     */
    private fun feedMarkingInput(bytes: ByteArray) {
        var from = 0
        while (from < bytes.size) {
            val mark = indexOf(bytes, INPUT_MARK, from)
            val newline = if (enterPending) indexOf(bytes, NEWLINE, from) else -1
            val prompt = indexOf(bytes, PROMPT_MARK, from)
            when {
                prompt >= 0 && (mark < 0 || prompt < mark) && (newline < 0 || prompt < newline) -> {
                    Native.feed(pointer, bytes.copyOfRange(from, prompt))
                    // Notices that came while a command ran go just before the next prompt.
                    if (!atPrompt.value && notices.isNotEmpty()) {
                        val lineStart = if (Native.position(pointer)[1] > 0L) "\r\n" else ""
                        Native.feed(pointer, (lineStart + "\u001b[0m\u001b[J").toByteArray())
                        feedNotices()
                    }
                    Native.feed(pointer, PROMPT_MARK)
                    val p = Native.position(pointer)
                    promptStart = (p[2] + p[0]) to p[1].toInt()
                    from = prompt + PROMPT_MARK.size
                }
                newline >= 0 && (mark < 0 || newline < mark) -> {
                    Native.feed(pointer, bytes.copyOfRange(from, newline))
                    enterPending = false
                    captureEntered()
                    from = newline
                }
                mark >= 0 -> {
                    Native.feed(pointer, bytes.copyOfRange(from, mark + INPUT_MARK.size))
                    val p = Native.position(pointer)
                    inputStart = (p[2] + p[0]) to p[1].toInt()
                    from = mark + INPUT_MARK.size
                }
                else -> {
                    Native.feed(pointer, if (from == 0) bytes else bytes.copyOfRange(from, bytes.size))
                    from = bytes.size
                }
            }
        }
    }

    /** Text of absolute line [row] from [column] through [end], without trailing blanks. */
    private fun text(row: Long, column: Int = 0, end: Int = Int.MAX_VALUE): String? =
        Native.line(pointer, row)?.let { cells ->
            buildString {
                for (c in column..minOf(end, cells.size - 1)) {
                    val cp = cells[c]
                    when {
                        cp == -1 -> {}
                        cp <= 0 || !Character.isValidCodePoint(cp) -> append(' ')
                        else -> appendCodePoint(cp)
                    }
                }
            }.trimEnd()
        }

    /** Records the command on the input line, from the prompt end to the cursor. */
    private fun captureEntered() {
        val (row, column) = inputStart ?: return
        inputStart = null
        val p = Native.position(pointer)
        val endRow = maxOf(row, p[2] + p[0])
        val command =
            buildString { for (r in row..endRow) append(text(r, if (r == row) column else 0) ?: "") }
                .trim()
        if (command.isEmpty()) return
        marks.addLast(CommandMark(row, column, endRow, command))
        while (marks.size > 500) marks.removeFirst()
    }

    /**
     * The command shown on view row [viewRow] while scrolled back [scrollback] lines, when that
     * line still holds it (not cleared or overwritten since).
     */
    fun commandAt(viewRow: Int, scrollback: Int): String? =
        synchronized(monitor) {
            if (pointer == 0L) return null
            val absolute = Native.position(pointer)[2] - scrollback + viewRow
            val mark = marks.lastOrNull { absolute in it.row..it.endRow } ?: return null
            val shown = buildString {
                for (r in mark.row..mark.endRow) append(text(r, if (r == mark.row) mark.column else 0) ?: "")
            }
            mark.command.takeIf { shown.startsWith(it) }
        }

    /** The absolute line shown on the first view row when not scrolled back. */
    fun topRow(): Long = synchronized(monitor) { if (pointer == 0L) 0 else Native.position(pointer)[2] }

    private var palette = IntArray(0)

    /** Sets the 16 ANSI colors (opaque RGB); existing output is drawn with them too. */
    fun palette(colors: IntArray) =
        synchronized(monitor) {
            if (pointer != 0L && !colors.contentEquals(palette)) {
                palette = colors.copyOf()
                Native.palette(pointer, colors)
                _revision.value++
            }
        }

    fun stop() {
        if (_exit.value == null) {
            Native.signal(process[0], process[1], 15)
            scope.launch {
                delay(750)
                if (_exit.value == null) Native.signal(process[0], process[1], 9)
            }
        }
    }

    fun close() {
        synchronized(monitor) {
            if (closed) return
            closed = true
            if (startGate >= 0) {
                Native.close(startGate)
                startGate = -1
            }
        }
        stop()
        scope.launch {
            withTimeoutOrNull(2000) { exit.firstDone() }
            reader.join()
            // Pending input is dropped; a write already under way ends before the close.
            writes.cancel()
            withTimeoutOrNull(1000) { writer.join() }
            Native.close(process[0])
            synchronized(monitor) {
                if (pointer != 0L) {
                    Native.destroy(pointer)
                    pointer = 0
                }
            }
            rcFile.delete()
            scope.cancel()
        }
    }
}

private val INPUT_MARK = "\u001b]133;B\u0007".toByteArray()

private val PROMPT_MARK = "\u001b]133;A\u0007".toByteArray()

private val NEWLINE = byteArrayOf('\n'.code.toByte())

private val SGR = Regex("\u001b\\[[0-9;]*m")

private val ZERO_WIDTH =
    setOf(
        Character.NON_SPACING_MARK,
        Character.ENCLOSING_MARK,
        Character.COMBINING_SPACING_MARK,
        Character.FORMAT,
    ).map { it.toInt() }

/**
 * How a notice's first line reads on screen, as far as it is sure to: a cell keeps one code
 * point, so text stops before the first mark that combines with the one before it.
 */
private fun noticeStart(notice: String): String =
    notice.replace(SGR, "").lineSequence().first().take(10).takeWhile {
        Character.getType(it) !in ZERO_WIDTH
    }

private fun indexOf(bytes: ByteArray, needle: ByteArray, from: Int): Int {
    outer@ for (i in from..bytes.size - needle.size) {
        for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
        return i
    }
    return -1
}

private suspend fun kotlinx.coroutines.flow.StateFlow<Int?>.firstDone() {
    first { it != null }
}
