package io.luftaquila.hometty.terminal

import android.content.Context
import android.system.Os
import java.io.File

object BusyBox {
    /** Only the APK-installed executable is run. This link supplies BusyBox's argv[0]. */
    @Synchronized
    fun executable(context: Context): File {
        val bundled = File(context.applicationInfo.nativeLibraryDir, "libbusybox.so")
        require(bundled.isFile) { "BusyBox unavailable for this ABI" }
        val directory = File(context.filesDir, "bin").apply { check(isDirectory || mkdirs()) }
        val link = File(directory, "busybox")
        if (runCatching { Os.readlink(link.path) }.getOrNull() != bundled.path) {
            java.nio.file.Files.deleteIfExists(link.toPath())
            Os.symlink(bundled.path, link.path)
        }
        return link
    }

    /**
     * Links every applet name to BusyBox in the folder of [executable]. The shell puts that folder
     * on PATH after the launcher's commands and before Android's, so a name means the same thing
     * in the shell, in scripts and in launcher aliases; no shell aliases are involved.
     */
    @Synchronized
    fun installApplets(context: Context): File {
        val busybox = executable(context)
        val directory = requireNotNull(busybox.parentFile)
        val bundled = File(context.applicationInfo.nativeLibraryDir, "libbusybox.so")
        val stamp = File(directory, ".applets")
        // The library path changes on every app update while APK file times stay fixed, so the
        // links are current only if they were made for this exact path.
        val version = "${bundled.path}:${bundled.length()}"
        if (stamp.isFile && stamp.readText() == version) return directory
        val process = ProcessBuilder(busybox.absolutePath, "--list").redirectErrorStream(true).start()
        val names =
            try {
                process.inputStream.bufferedReader().readLines().also {
                    check(process.waitFor() == 0) { "BusyBox --list failed" }
                }
            } finally {
                process.destroy()
            }
                .filter { it.matches(Regex("[A-Za-z0-9_.+-]+")) && it != "busybox" }
                .toSet()
        directory
            .listFiles()
            ?.filter { it.name !in names && it.name != "busybox" && it != stamp }
            ?.forEach { java.nio.file.Files.deleteIfExists(it.toPath()) }
        for (name in names) {
            val link = File(directory, name)
            if (runCatching { Os.readlink(link.path) }.getOrNull() != bundled.path) {
                java.nio.file.Files.deleteIfExists(link.toPath())
                Os.symlink(bundled.path, link.path)
            }
        }
        stamp.writeText(version)
        return directory
    }
}
