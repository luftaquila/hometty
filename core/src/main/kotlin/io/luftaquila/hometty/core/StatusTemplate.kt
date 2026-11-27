package io.luftaquila.hometty.core

/** Compiled structure is kept separate from values, so values cannot become format commands. */
internal class StatusTemplate private constructor(
    private val nodes: List<Node>,
    val references: Set<String>,
    private val controlLines: Set<Int>,
) {
    private sealed interface Node {
        data class Text(val value: String, val line: Int) : Node
        data class Token(val value: String, val line: Int) : Node
        data class Right(val line: Int) : Node
        data class If(val condition: StatusExpression.Expr, val yes: List<Node>, val no: List<Node>) : Node
        data class Color(val color: String, val body: List<Node>) : Node
    }
    companion object { fun parse(format: String): StatusTemplate = Parser(format).parse() }

    fun render(values: Map<String, Any?>, expand: (String) -> String): List<StatusRow> {
        val output = Output(controlLines)
        fun visit(nodes: List<Node>, color: String?) {
            nodes.forEach { node -> when (node) {
                is Node.Text -> output.append(node.value, node.line, color, true)
                is Node.Token -> output.append(expand(node.value), node.line, color, false)
                is Node.Right -> output.right(node.line, color)
                is Node.If -> visit(if (StatusExpression.truth(node.condition.value(values))) node.yes else node.no, color)
                is Node.Color -> visit(node.body, node.color)
            } }
        }
        visit(nodes, null)
        return output.finish()
    }

    private class Output(private val controlLines: Set<Int>) {
        private val rows = mutableListOf<StatusRow>()
        private var source = 0
        private var left = StringBuilder()
        private var right: StringBuilder? = null
        private var leftColors = mutableListOf<StatusColorSpan>()
        private var rightColors = mutableListOf<StatusColorSpan>()
        private fun blank() = left.isEmpty() && right.isNullOrEmpty()
        fun right(line: Int, color: String?) {
            if (right != null) append("{right}", line, color, false)
            else { if (blank()) source = line; right = StringBuilder() }
        }
        fun append(value: String, line: Int, color: String?, advanceSource: Boolean) {
            if (!advanceSource) {
                if (blank()) source = line
                appendPart(value, color)
                return
            }
            var current = line
            var start = 0
            while (true) {
                val end = value.indexOf('\n', start).let { if (it == -1) value.length else it }
                if (blank()) source = current
                appendPart(value.substring(start, end), color)
                if (end == value.length) break
                flush()
                if (advanceSource) current++
                source = current
                start = end + 1
            }
        }
        private fun appendPart(value: String, color: String?) {
            val target = right ?: left
            val spans = if (right == null) leftColors else rightColors
            val offset = target.length
            target.append(value)
            if (color != null && target.length > offset) spans += StatusColorSpan(offset, target.length, color)
        }
        private fun flush() {
            if (!(left.isBlank() && right.isNullOrBlank() && source in controlLines)) {
                val l = left.toString().split('\n')
                val r = right?.toString()?.split('\n')
                var lo = 0
                var ro = 0
                fun colors(spans: List<StatusColorSpan>, start: Int, length: Int) = spans.mapNotNull {
                    val a = maxOf(start, it.start)
                    val b = minOf(start + length, it.end)
                    if (a < b) StatusColorSpan(a - start, b - start, it.color) else null
                }
                repeat(maxOf(l.size, r?.size ?: 0)) { index ->
                    val a = l.getOrElse(index) { "" }
                    val b = r?.getOrElse(index) { "" }
                    rows += StatusRow(source, a, b, colors(leftColors, lo, a.length), colors(rightColors, ro, b?.length ?: 0))
                    lo += a.length + 1; ro += (b?.length ?: 0) + 1
                }
            }
            left = StringBuilder(); right = null
            leftColors = mutableListOf(); rightColors = mutableListOf()
        }
        fun finish(): List<StatusRow> { flush(); return rows }
    }

    private class Parser(private val format: String) {
        private var offset = 0
        private var line = 0
        private var closing: String? = null
        private val references = mutableSetOf<String>()
        private val controls = mutableSetOf<Int>()
        private fun fail(message: String, at: Int = line): Nothing = throw UserMessage("error_status_format", at + 1, message)
        fun parse(): StatusTemplate {
            val body = sequence(emptySet(), 0)
            return StatusTemplate(body, references, controls)
        }
        private fun sequence(stops: Set<String>, depth: Int): List<Node> {
            if (depth > 32) fail("Block nesting exceeds 32")
            val result = mutableListOf<Node>()
            closing = null
            while (offset < format.length) {
                val startLine = line
                val literal = StringBuilder()
                while (offset < format.length) {
                    val c = format[offset]
                    if (c == '\\' && offset + 1 < format.length && format[offset + 1] in "{}\\") {
                        literal.append(format[offset + 1]); offset += 2
                    } else if (c == '{') break
                    else { literal.append(c); offset++; if (c == '\n') line++ }
                }
                if (literal.isNotEmpty()) result += Node.Text(literal.toString(), startLine)
                if (offset == format.length) break
                val tokenLine = line
                val start = offset
                val end = tokenEnd(start)
                if (end == null) {
                    if (format.startsWith("{if ", start) || format.startsWith("{color:", start)) fail("Unclosed format command")
                    result += Node.Text("{", line); offset++; continue
                }
                val raw = format.substring(start, end + 1)
                val command = raw.substring(1, raw.length - 1)
                offset = end + 1
                line += raw.count { it == '\n' }
                if (command in stops) { controls += tokenLine; closing = command; return result }
                when {
                    command == "else" || command == "end" || command == "/color" -> fail("Unexpected {$command}", tokenLine)
                    command == "if" || command.startsWith("if ") -> {
                        controls += tokenLine
                        val condition = try { StatusExpression.parse(command.removePrefix("if").trim()) }
                            catch (e: IllegalArgumentException) { fail(e.message ?: "Invalid condition", tokenLine) }
                        references += condition.references
                        val yes = sequence(setOf("else", "end"), depth + 1)
                        val no = if (closing == "else") sequence(setOf("end"), depth + 1) else emptyList()
                        if (closing != "end") fail("Missing {end}", tokenLine)
                        result += Node.If(condition.expression, yes, no)
                    }
                    command.startsWith("color:") -> {
                        controls += tokenLine
                        val color = command.substringAfter(':')
                        if (!color.matches(colorPattern)) fail("Expected #RRGGBB or #RRGGBBAA", tokenLine)
                        val body = sequence(setOf("/color"), depth + 1)
                        if (closing != "/color") fail("Missing {/color}", tokenLine)
                        result += Node.Color(color, body)
                    }
                    command == "right" -> result += Node.Right(tokenLine)
                    else -> {
                        references += command.substringBefore(':')
                        result += Node.Token(raw, tokenLine)
                    }
                }
            }
            closing = null
            return result
        }
        private fun tokenEnd(start: Int): Int? {
            var i = start + 1
            var quote: Char? = null
            val expression = format.startsWith("{if ", start)
            while (i < format.length) {
                val c = format[i++]
                if (c == '\\' && i < format.length) { i++; continue }
                if (expression && (c == '\'' || c == '"')) {
                    if (quote == null) quote = c else if (quote == c) quote = null
                }
                if (c == '}' && quote == null) return i - 1
                // A nested opening is not part of a normal placeholder (unknown text stays literal).
                if (c == '{' && quote == null) return null
            }
            return null
        }
    }
}
