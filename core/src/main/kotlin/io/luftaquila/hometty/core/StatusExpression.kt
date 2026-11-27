package io.luftaquila.hometty.core

/** A small expression language: values only, with no code execution or external access. */
internal object StatusExpression {
    interface Expr { fun value(values: Map<String, Any?>): Any? }
    data class Parsed(val expression: Expr, val references: Set<String>)
    fun parse(source: String): Parsed = Parser(source).parse()
    fun truth(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Double -> value != 0.0 && value.isFinite()
        is String -> value.isNotEmpty()
        else -> false
    }
    fun scalar(value: String): Any = when (value) {
        "true" -> true
        "false" -> false
        else -> value.toDoubleOrNull()?.takeIf { it.isFinite() } ?: value
    }
    private fun number(value: Any?) = value as? Double
    private fun numeric(value: Double?) = value?.takeIf { it.isFinite() }
    private data class Literal(val item: Any?) : Expr { override fun value(values: Map<String, Any?>) = item }
    private data class Variable(val name: String) : Expr { override fun value(values: Map<String, Any?>) = values[name] }
    private data class Unary(val op: String, val arg: Expr) : Expr {
        override fun value(values: Map<String, Any?>): Any? = when (op) {
            "!" -> !truth(arg.value(values))
            "-" -> number(arg.value(values))?.let { -it }
            else -> number(arg.value(values))
        }
    }
    private data class Binary(val op: String, val left: Expr, val right: Expr) : Expr {
        override fun value(values: Map<String, Any?>): Any? {
            val a = left.value(values)
            if (op == "&&") return truth(a) && truth(right.value(values))
            if (op == "||") return truth(a) || truth(right.value(values))
            val b = right.value(values)
            if (op == "==") return a == b
            if (op == "!=") return a != b
            val x = number(a) ?: return if (op in comparisons) false else null
            val y = number(b) ?: return if (op in comparisons) false else null
            return when (op) {
                "<" -> x < y; "<=" -> x <= y; ">" -> x > y; ">=" -> x >= y
                "+" -> numeric(x + y); "-" -> numeric(x - y); "*" -> numeric(x * y)
                "/" -> if (y == 0.0) null else numeric(x / y)
                "%" -> if (y == 0.0) null else numeric(x % y)
                else -> null
            }
        }
    }
    private data class Call(val name: String, val args: List<Expr>) : Expr {
        override fun value(values: Map<String, Any?>): Any {
            val a = args[0].value(values)
            return when (name) {
                "exists" -> a != null
                "empty" -> a == null || a == ""
                else -> {
                    val x = a as? String ?: return false
                    val y = args[1].value(values) as? String ?: return false
                    when (name) {
                        "contains" -> y in x
                        "startsWith" -> x.startsWith(y)
                        else -> x.endsWith(y)
                    }
                }
            }
        }
    }
    private val comparisons = setOf("<", "<=", ">", ">=")
    private val precedence = mapOf("||" to 1, "&&" to 2, "==" to 3, "!=" to 3,
        "<" to 4, "<=" to 4, ">" to 4, ">=" to 4, "+" to 5, "-" to 5,
        "*" to 6, "/" to 6, "%" to 6)
    private data class Token(val text: String, val literal: Boolean = false)
    private class Parser(private val source: String) {
        private var offset = 0
        private var nodes = 0
        private var token = next()
        private val references = mutableSetOf<String>()
        private fun fail(message: String): Nothing = throw IllegalArgumentException("$message (column ${offset + 1})")
        private fun next(): Token {
            while (offset < source.length && source[offset].isWhitespace()) offset++
            if (offset == source.length) return Token("")
            val start = offset
            val c = source[offset++]
            if (c == '\'' || c == '"') {
                val text = StringBuilder()
                while (offset < source.length) {
                    val ch = source[offset++]
                    if (ch == c) return Token(text.toString(), true)
                    if (ch == '\\') {
                        if (offset == source.length) fail("Unclosed string")
                        text.append(when (val escaped = source[offset++]) {
                            'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; else -> escaped
                        })
                    } else text.append(ch)
                }
                fail("Unclosed string")
            }
            if (c.isDigit() || c == '.') {
                val match = Regex("""(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?""").find(source, start)
                if (match == null || match.range.first != start) fail("Expected a number")
                offset = match.range.last + 1
                return Token(match.value)
            }
            if (c.isLetter() || c == '_') {
                while (offset < source.length && (source[offset].isLetterOrDigit() || source[offset] in "_.")) offset++
                return Token(source.substring(start, offset))
            }
            val pair = source.substring(start, minOf(start + 2, source.length))
            if (pair in setOf("&&", "||", "==", "!=", "<=", ">=")) { offset = start + 2; return Token(pair) }
            if (c in "()+-*/%!,<>") return Token(c.toString())
            fail("Unexpected '$c'")
        }
        private fun take(): Token = token.also { token = next() }
        private fun expect(text: String) { if (token.literal || token.text != text) fail("Expected '$text'"); take() }
        fun parse(): Parsed {
            val expr = expression(1, 0)
            if (token.text.isNotEmpty() || token.literal) fail("Unexpected '${token.text}'")
            return Parsed(expr, references)
        }
        private fun expression(minimum: Int, depth: Int): Expr {
            if (depth > 32 || ++nodes > 256) fail("Expression is too complex")
            var left = atom(depth + 1)
            while (!token.literal) {
                val priority = precedence[token.text] ?: break
                if (priority < minimum) break
                val op = take().text
                left = Binary(op, left, expression(priority + 1, depth + 1))
            }
            return left
        }
        private fun atom(depth: Int): Expr {
            if (depth > 32) fail("Expression nesting exceeds 32")
            if (!token.literal && token.text in setOf("!", "-", "+")) return Unary(take().text, atom(depth + 1))
            if (!token.literal && token.text == "(") {
                take(); val result = expression(1, depth + 1); expect(")"); return result
            }
            val item = take()
            if (item.literal) return Literal(item.text)
            if (item.text in setOf("true", "false", "null")) return Literal(when (item.text) { "true" -> true; "false" -> false; else -> null })
            item.text.toDoubleOrNull()?.let { if (!it.isFinite()) fail("Number must be finite"); return Literal(it) }
            if (item.text.isEmpty() || !(item.text.first().isLetter() || item.text.first() == '_')) fail("Expected a value")
            if (!token.literal && token.text == "(") {
                val count = when (item.text) {
                    "exists", "empty" -> 1
                    "contains", "startsWith", "endsWith" -> 2
                    else -> fail("Unknown function '${item.text}'")
                }
                take()
                val args = List(count) { index -> if (index > 0) expect(","); expression(1, depth + 1) }
                expect(")")
                return Call(item.text, args)
            }
            references += item.text
            return Variable(item.text)
        }
    }
}
