package io.luftaquila.hometty.data

import android.content.Context

/**
 * Values kept apart from the settings, so that backups and `config` never show them (the
 * OpenWeather key). Stored as plain text in app-private storage.
 */
class Secrets(context: Context) {
    private val preferences = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    /** The value saved as [name]; null when there is none. */
    fun get(name: String): String? = preferences.getString(name, null)

    /** Saves [value] as [name]; a blank value removes it. */
    fun set(name: String, value: String) {
        if (value.isBlank()) preferences.edit().remove(name).apply()
        else preferences.edit().putString(name, value).apply()
    }

    fun clear() {
        check(preferences.edit().clear().commit()) { "Cannot clear secrets" }
    }
}
