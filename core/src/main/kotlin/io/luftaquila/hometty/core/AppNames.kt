package io.luftaquila.hometty.core

import java.text.Normalizer
import java.util.Locale

object AppNames {
    fun key(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC)
            .filterNot(Char::isWhitespace)
            .lowercase(Locale.ROOT)

    /** Section order of app lists: Latin letters, Hangul initial consonants, then the rest. */
    val indexOrder = ('A'..'Z').map(Char::toString) + "ㄱㄴㄷㄹㅁㅂㅅㅇㅈㅊㅋㅌㅍㅎ".map(Char::toString) + "#"

    /** The section of a label: its first letter, or the plain initial consonant of Hangul. */
    fun index(label: String): String {
        val c = label.trim().firstOrNull() ?: return "#"
        val initials = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
        val plain = mapOf('ㄲ' to 'ㄱ', 'ㄸ' to 'ㄷ', 'ㅃ' to 'ㅂ', 'ㅆ' to 'ㅅ', 'ㅉ' to 'ㅈ')
        return when (c) {
            in 'a'..'z',
            in 'A'..'Z' -> c.uppercase()
            in '가'..'힣' -> initials[(c.code - 0xAC00) / 588].let { plain[it] ?: it }.toString()
            in initials -> (plain[c] ?: c).toString()
            else -> "#"
        }
    }

    /** [items] in sections by [index] of [label], each sorted by [comparator]. */
    fun <T> sections(
        items: List<T>,
        label: (T) -> String,
        comparator: Comparator<in String>,
    ): List<Pair<String, List<T>>> =
        items
            .groupBy { index(label(it)) }
            .toSortedMap(compareBy { indexOrder.indexOf(it) })
            .map { (key, list) -> key to list.sortedWith { a, b -> comparator.compare(label(a), label(b)) } }

    fun commands(label: String): Set<String> {
        val words = label.trim().split(Regex("\\s+"))
        return setOf(label, words.firstOrNull().orEmpty(), words.joinToString(""))
            .flatMap { listOf(it, it.lowercase(Locale.ROOT), it.uppercase(Locale.ROOT)) }
            .filter { it.isNotBlank() }
            .toSet()
    }
}
