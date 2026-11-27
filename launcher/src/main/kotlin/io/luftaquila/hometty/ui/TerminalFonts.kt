package io.luftaquila.hometty.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import io.luftaquila.hometty.R
import io.luftaquila.hometty.core.Fonts
import java.io.File

/**
 * Terminal typefaces: the chosen font, then Jetendard for characters it lacks (Hangul in Latin-only
 * fonts), then the system fonts. The system monospace choice uses system fonts only.
 */
object TerminalFonts {
    const val CUSTOM_FILE = "custom-font.ttf"

    private val cache = HashMap<String, Typeface>()

    fun resource(id: String): Int? =
        when (id) {
            "jetendard" -> R.font.jetendard
            "d2coding" -> R.font.d2coding
            "jetbrains_mono" -> R.font.jetbrains_mono
            "fira_code" -> R.font.fira_code
            "cascadia_code" -> R.font.cascadia_code
            "source_code_pro" -> R.font.source_code_pro
            "ibm_plex_mono" -> R.font.ibm_plex_mono
            "hack" -> R.font.hack
            "iosevka" -> R.font.iosevka_term
            "0xproto" -> R.font.zero_x_proto
            else -> null
        }

    fun customFile(context: Context) = File(context.filesDir, CUSTOM_FILE)

    /** The typeface for font [id]; an unusable imported font falls back to the default font. */
    fun typeface(context: Context, id: String): Typeface {
        val custom = customFile(context)
        val key =
            if (id == Fonts.CUSTOM) "$id:${custom.lastModified()}:${custom.length()}" else id
        synchronized(cache) { cache[key] }?.let { return it }
        val typeface =
            if (id == Fonts.SYSTEM) Typeface.MONOSPACE
            else build(context, id, custom) ?: return typeface(context, Fonts.DEFAULT)
        synchronized(cache) { cache[key] = typeface }
        return typeface
    }

    private fun build(context: Context, id: String, custom: File): Typeface? {
        val resources = context.resources
        val primary =
            runCatching {
                    if (id == Fonts.CUSTOM) Font.Builder(custom).build()
                    else Font.Builder(resources, requireNotNull(resource(id))).build()
                }
                .getOrNull() ?: return null
        val builder = Typeface.CustomFallbackBuilder(FontFamily.Builder(primary).build())
        if (id != Fonts.DEFAULT)
            builder.addCustomFallback(
                FontFamily.Builder(Font.Builder(resources, R.font.jetendard).build()).build()
            )
        return builder.setSystemFallback("monospace").build()
    }
}
