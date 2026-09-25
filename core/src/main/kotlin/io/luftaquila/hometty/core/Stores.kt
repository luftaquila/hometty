package io.luftaquila.hometty.core

import kotlinx.coroutines.flow.Flow

/** Saved settings. Every write is checked against the setting definitions. */
interface SettingsStore {
    val settings: Flow<SettingsSnapshot>

    suspend fun snapshot(): SettingsSnapshot

    suspend fun set(key: String, value: String)

    suspend fun update(values: Map<String, String>)

    suspend fun reset(key: String)
}

/** Records people keep (aliases, notes, feeds...). Every write is checked by its collection. */
interface EntryStore {
    suspend fun list(kind: String): List<Entry>

    /** The entry of [kind] whose name or id is [name]. */
    suspend fun find(kind: String, name: String): Entry?

    suspend fun put(kind: String, name: String, fields: Map<String, String>): Entry

    /**
     * Stores [change] applied to the current fields of [name] (empty when it does not exist yet)
     * in one step, so concurrent changes to the same entry are not lost.
     */
    suspend fun modify(
        kind: String,
        name: String,
        change: (Map<String, String>) -> Map<String, String>,
    ): Entry

    suspend fun remove(kind: String, name: String)
}

/** State the app makes for itself (caches, counters): not records people keep, not backed up. */
interface StateStore {
    suspend fun getState(name: String): String?

    suspend fun putState(values: Map<String, String>)
}

/** Names in [StateStore], kept in one place for every module that reads or writes them. */
object StateNames {
    fun feedText(feedId: String) = "rss.$feedId.text"

    /** The rendered items of the last fetch, as a JSON array of strings. */
    fun feedItems(feedId: String) = "rss.$feedId.items"

    /** Epoch milliseconds of the last successful fetch. */
    fun feedUpdated(feedId: String) = "rss.$feedId.updated"

    /** Comma-separated hashes of the items already printed in the terminal. */
    fun feedShown(feedId: String) = "rss.$feedId.shown"

}
