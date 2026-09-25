package io.luftaquila.hometty.core

object Patterns {
    fun validate(pattern: String) {
        require(pattern.length <= 2048) { "message_pattern_too_long" }
        require(size(pattern) <= 20_000) { "message_pattern_too_complex" }
        com.google.re2j.Pattern.compile(pattern)
    }

    /**
     * Roughly how many instructions [pattern] compiles to. RE2 copies a counted repetition's body
     * that many times, so nesting multiplies: ((a{1000}){1000}){1000} would need a billion and
     * exhaust memory, though each count is allowed.
     */
    fun size(pattern: String): Long {
        val outer = ArrayDeque<Long>()
        var current = 0L
        var last = 0L
        fun atom(cost: Long) {
            last = cost
            current = minOf(current + cost, Long.MAX_VALUE / 4)
        }
        fun repeat(times: Long) {
            current = minOf(current + last * (times - 1), Long.MAX_VALUE / 4)
            last = minOf(last * times, Long.MAX_VALUE / 4)
        }
        var i = 0
        while (i < pattern.length) {
            when (pattern[i]) {
                '\\' -> {
                    // \p{Greek}, \x{263a}: the braces belong to the escape.
                    val braced = i + 2 < pattern.length && pattern[i + 1] in "pPx" && pattern[i + 2] == '{'
                    i = if (braced) pattern.indexOf('}', i).let { if (it < 0) pattern.length else it + 1 } else i + 2
                    atom(1)
                }
                '[' -> {
                    var j = i + 1
                    if (j < pattern.length && pattern[j] == '^') j++
                    if (j < pattern.length && pattern[j] == ']') j++
                    while (j < pattern.length && pattern[j] != ']') j += if (pattern[j] == '\\') 2 else 1
                    i = j + 1
                    atom(1)
                }
                '(' -> {
                    outer.addLast(current)
                    current = 0
                    i++
                }
                ')' -> {
                    val inner = current
                    current = (outer.removeLastOrNull() ?: 0) + inner
                    last = inner
                    i++
                }
                '*', '+', '?' -> {
                    repeat(2)
                    i++
                }
                '{' -> {
                    val close = pattern.indexOf('}', i)
                    val counts = if (close < 0) null else Regex("""(\d+)(,(\d*))?""").matchEntire(pattern.substring(i + 1, close))
                    if (counts == null) {
                        atom(1)
                        i++
                    } else {
                        val low = counts.groupValues[1].toLongOrNull() ?: 0
                        val high = counts.groupValues[3].toLongOrNull()
                        repeat(maxOf(1, high ?: if (counts.groupValues[2].isEmpty()) low else low + 1))
                        i = close + 1
                    }
                }
                '|' -> {
                    last = 0
                    i++
                }
                else -> {
                    atom(1)
                    i++
                }
            }
        }
        return current
    }

    fun matches(pattern: String, text: String): Boolean {
        validate(pattern)
        return com.google.re2j.Pattern.compile(pattern).matcher(text.take(262144)).find()
    }

    fun find(pattern: String, text: String): List<String> {
        validate(pattern)
        val matcher = com.google.re2j.Pattern.compile(pattern).matcher(text.take(262144))
        return buildList { while (size < 1000 && matcher.find()) add(matcher.group()) }
    }
}
