package io.luftaquila.hometty.core

/**
 * A locale-independent error; Android resolves its key and positional arguments at presentation.
 */
class UserMessage(val key: String, vararg val arguments: Any) : IllegalArgumentException(key)

/**
 * A shell home folder the app can enter: blank (internal storage) or an existing directory
 * with search permission. Checked when the setting is changed and when a shell starts, never when
 * saved settings load, so a folder that goes away later cannot discard the other settings.
 */
fun usableHomeFolder(path: String): Boolean =
    path.isBlank() || java.io.File(path).let { it.isDirectory && it.canExecute() }

fun requireMessage(condition: Boolean, key: String, vararg arguments: Any) {
    if (!condition) throw UserMessage(key, *arguments)
}

/** Reads at most [limit] bytes; a longer stream fails instead of filling memory. */
fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        require(output.size() + n <= limit) { "More than $limit bytes" }
        output.write(buffer, 0, n)
    }
    return output.toByteArray()
}
