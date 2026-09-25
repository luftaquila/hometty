package io.luftaquila.hometty.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.room.*
import io.luftaquila.hometty.core.*
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json {
    ignoreUnknownKeys = false
    prettyPrint = true
}

object SettingsSerializer : Serializer<SettingsSnapshot> {
    override val defaultValue = SettingsSnapshot()

    override suspend fun readFrom(input: InputStream): SettingsSnapshot =
        try {
            json
                .decodeFromString<SettingsSnapshot>(input.readBytes().decodeToString())
                .also { require(it.version == 1) }
                .let { snapshot ->
                    // A value that does not validate (edited by hand, out of range) falls back to
                    // its default instead of failing every setting.
                    snapshot.copy(
                        values =
                            snapshot.values.filter { (key, value) ->
                                Settings.byKey[key]?.let { runCatching { it.validate(value) }.isSuccess } == true
                            }
                    )
                }
        } catch (error: Exception) {
            throw CorruptionException("Settings invalid; use recovery", error)
        }

    override suspend fun writeTo(t: SettingsSnapshot, output: OutputStream) {
        output.write(json.encodeToString(t).toByteArray())
    }
}

@Entity(tableName = "entries", indices = [Index(value = ["kind", "name"], unique = true)])
data class EntryRow(
    @PrimaryKey val id: String,
    val kind: String,
    val name: String,
    val payload: String,
    val created: Long,
) {
    fun entry() = Entry(id, kind, name, json.decodeFromString(payload), created)

    companion object {
        fun from(e: Entry) =
            EntryRow(e.id, e.kind, e.name, json.encodeToString(e.fields), e.created)
    }
}

@Dao
interface EntryDao {
    @Query("SELECT * FROM entries ORDER BY created DESC") fun observe(): Flow<List<EntryRow>>

    @Query("SELECT * FROM entries WHERE kind = :kind ORDER BY created DESC")
    fun observe(kind: String): Flow<List<EntryRow>>

    /** By name, else by id. */
    @Query(
        "SELECT * FROM entries WHERE kind = :kind AND (name = :name OR id = :name) " +
            "ORDER BY name = :name DESC LIMIT 1"
    )
    suspend fun find(kind: String, name: String): EntryRow?

    @Query("SELECT * FROM entries ORDER BY created DESC") suspend fun all(): List<EntryRow>

    @Query("SELECT * FROM entries WHERE kind = :kind ORDER BY created DESC")
    suspend fun list(kind: String): List<EntryRow>

    @Upsert suspend fun put(entry: EntryRow)

    @Insert suspend fun insertAll(rows: List<EntryRow>)

    @Query("DELETE FROM entries WHERE id = :id") suspend fun remove(id: String)

    @Query("DELETE FROM entries") suspend fun clearAll()
}

/** One value of app-made state; see [StateNames]. */
@Entity(tableName = "state") data class StateRow(@PrimaryKey val name: String, val value: String)

@Dao
interface StateDao {
    @Query("SELECT value FROM state WHERE name = :name") suspend fun get(name: String): String?

    @Upsert suspend fun put(rows: List<StateRow>)

    @Query("DELETE FROM state") suspend fun clear()
}

@Database(entities = [EntryRow::class, StateRow::class], version = 1, exportSchema = true)
abstract class HomettyDatabase : RoomDatabase() {
    abstract fun entries(): EntryDao

    abstract fun state(): StateDao
}

/** Single application-scoped instance. All mutations share one lock, including restore. */
class Storage(context: Context) : SettingsStore, EntryStore, StateStore {
    private val dir = context.filesDir
    private val mutex = Mutex()
    val recoveredSettings = java.util.concurrent.atomic.AtomicBoolean(false)
    private val store =
        DataStoreFactory.create(
            serializer = SettingsSerializer,
            corruptionHandler =
                ReplaceFileCorruptionHandler {
                    File(dir, "settings.json")
                        .takeIf { it.exists() }
                        ?.copyTo(File(dir, "settings-corrupt-${System.currentTimeMillis()}.json"))
                    recoveredSettings.set(true)
                    SettingsSnapshot()
                },
            produceFile = { File(dir, "settings.json") },
        )
    private val database =
        Room.databaseBuilder(context, HomettyDatabase::class.java, "hometty.db")
            .build()
    private val dao = database.entries()
    private val state = database.state()
    override val settings: Flow<SettingsSnapshot> = store.data

    /** Every record people keep, newest first. */
    val entries: Flow<List<Entry>> = dao.observe().map { rows -> rows.map { it.entry() } }

    /** The records of one kind, newest first; changes to other kinds do not emit. */
    fun entries(kind: String): Flow<List<Entry>> =
        dao.observe(kind).map { rows -> rows.map { it.entry() } }.distinctUntilChanged()

    override suspend fun snapshot() = settings.first()

    override suspend fun set(key: String, value: String) = update(mapOf(key to value))

    override suspend fun update(values: Map<String, String>): Unit =
        mutex.withLock {
            values.forEach { (key, value) ->
                requireNotNull(Settings.byKey[key]) { "Unknown setting: $key" }.validate(value)
                if (key == Settings.shellHome.key)
                    requireMessage(usableHomeFolder(value), "error_home_folder", value)
            }
            store.updateData { it.copy(values = it.values + values) }
        }

    override suspend fun reset(key: String): Unit =
        mutex.withLock {
            require(key in Settings.byKey)
            store.updateData { it.copy(values = it.values - key) }
        }

    override suspend fun list(kind: String) = dao.list(kind).map { it.entry() }

    suspend fun all() = dao.all().map { it.entry() }

    override suspend fun find(kind: String, name: String): Entry? = dao.find(kind, name)?.entry()

    override suspend fun getState(name: String): String? = state.get(name)

    override suspend fun putState(values: Map<String, String>) =
        state.put(values.map { (name, value) -> StateRow(name, value) })

    override suspend fun put(kind: String, name: String, fields: Map<String, String>): Entry =
        mutex.withLock { write(kind, name, fields, find(kind, name)) }

    override suspend fun modify(
        kind: String,
        name: String,
        change: (Map<String, String>) -> Map<String, String>,
    ): Entry =
        mutex.withLock {
            val previous = find(kind, name)
            write(kind, previous?.name ?: name, change(previous?.fields.orEmpty()), previous)
        }

    /** Caller holds [mutex]. */
    private suspend fun write(
        kind: String,
        name: String,
        fields: Map<String, String>,
        previous: Entry?,
    ): Entry {
        Collections.validate(kind, name, fields)
        require(fields.values.all { it.length <= 262144 }) { "Value too large" }
        val entry =
            Entry(
                previous?.id ?: UUID.randomUUID().toString(),
                kind,
                name,
                fields,
                previous?.created ?: System.currentTimeMillis(),
            )
        dao.put(EntryRow.from(entry))
        return entry
    }

    override suspend fun remove(kind: String, name: String): Unit =
        mutex.withLock {
            val entry = requireNotNull(find(kind, name)) { "Not found: $name" }
            Collections.validateRemoval(entry)
            dao.remove(entry.id)
        }

    suspend fun export(output: OutputStream) =
        mutex.withLock {
            val backup =
                Backup(
                    settings = snapshot(),
                    entries = all().filter { it.kind in Collections.backupKinds },
                )
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("backup.json"))
                zip.write(json.encodeToString(backup).toByteArray())
                zip.closeEntry()
                val font = File(dir, "custom-font.ttf")
                if (font.isFile && font.length() <= 8 * 1024 * 1024) {
                    zip.putNextEntry(ZipEntry("custom-font.ttf"))
                    font.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }

    data class ImportPreview(val backup: Backup, val font: ByteArray?)

    fun inspect(input: InputStream): ImportPreview {
        var backup: Backup? = null
        var font: ByteArray? = null
        var total = 0
        val names = mutableSetOf<String>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(
                    entry.name in setOf("backup.json", "custom-font.ttf") && names.add(entry.name)
                ) {
                    "Unexpected archive entry"
                }
                val bytes = zip.readBytesLimited(8 * 1024 * 1024)
                total += bytes.size
                require(total <= 12 * 1024 * 1024) { "Archive too large" }
                when (entry.name) {
                    "backup.json" -> backup = json.decodeFromString(bytes.decodeToString())
                    "custom-font.ttf" -> font = bytes
                }
            }
        }
        val value = requireNotNull(backup) { "Missing backup.json" }
        require(value.version == 1 && value.settings.version == 1) { "Unsupported backup version" }
        value.settings.values.forEach { (key, v) ->
            requireNotNull(Settings.byKey[key]) { "Unknown setting: $key" }.validate(v)
        }
        require(value.entries.size <= 10000) { "Too many entries" }
        require(value.entries.map { it.id }.distinct().size == value.entries.size)
        require(value.entries.map { it.kind to it.name }.distinct().size == value.entries.size)
        require(
            value.entries.all {
                it.kind in Collections.backupKinds && it.name.isNotBlank() && it.fields.values.all { v -> v.length <= 262144 }
            }
        ) {
            "Invalid record"
        }
        value.entries.forEach { Collections.validate(it.kind, it.name, it.fields) }
        font?.let { bytes ->
            require(
                bytes.size >= 12 &&
                    (bytes.take(4) == listOf<Byte>(0, 1, 0, 0) ||
                        bytes.copyOfRange(0, 4).decodeToString() in setOf("OTTO", "ttcf", "true"))
            ) {
                "Invalid font"
            }
        }
        return ImportPreview(value, font)
    }

    /** Durable rollback journal spans DataStore and Room; recovered before the UI is exposed. */
    suspend fun restore(preview: ImportPreview) =
        // Once started, a restore and its rollback finish even if the screen that asked goes away:
        // a cancelled coroutine could not roll back, leaving half the backup and a stale journal.
        withContext(NonCancellable) { mutex.withLock {
            val journal = File(dir, "restore-journal.json")
            val previous = Backup(settings = snapshot(), entries = all())
            val fontBackup = File(dir, "restore-font.bak")
            val font = File(dir, "custom-font.ttf")
            val fontExisted = font.exists()
            if (fontExisted) {
                font.copyTo(fontBackup, true)
                java.io.FileOutputStream(fontBackup, true).use { it.fd.sync() }
            } else fontBackup.delete()
            File(dir, "restore-journal.tmp")
                .apply {
                    writeText(json.encodeToString(Recovery(previous, fontExisted)))
                    java.io.FileOutputStream(this, true).use { it.fd.sync() }
                }
                .also { check(it.renameTo(journal)) { "Cannot create restore journal" } }
            try {
                database.withTransaction {
                    dao.clearAll()
                    dao.insertAll(preview.backup.entries.map(EntryRow::from))
                }
                store.updateData {
                    preview.backup.settings.copy(
                        values =
                            preview.backup.settings.values +
                                (Settings.rootEnabled.key to "false") +
                                (Settings.customFont.key to
                                    if (preview.font != null) "custom-font.ttf" else "")
                    )
                }
                if (preview.font != null) File(dir, "custom-font.ttf").writeBytes(preview.font)
                else File(dir, "custom-font.ttf").delete()
                // Caches and rule progress belonged to the replaced records.
                state.clear()
                check(journal.delete())
                fontBackup.delete()
            } catch (error: Exception) {
                rollBack(previous, fontExisted)
                throw error
            }
        } }

    /** Finishes rolling back a restore that was cut short (see [restore]). */
    suspend fun recover() =
        mutex.withLock {
            val journal = File(dir, "restore-journal.json")
            if (journal.exists()) {
                val recovery = json.decodeFromString<Recovery>(journal.readText())
                rollBack(recovery.backup, recovery.fontExisted)
            }
        }

    /**
     * Puts back the records and settings of [previous] and the font saved in restore-font.bak,
     * then ends the restore journal. Caller holds [mutex].
     */
    private suspend fun rollBack(previous: Backup, fontExisted: Boolean) {
        database.withTransaction {
            dao.clearAll()
            dao.insertAll(previous.entries.map(EntryRow::from))
        }
        store.updateData { previous.settings }
        val fontBackup = File(dir, "restore-font.bak")
        if (fontExisted) fontBackup.copyTo(File(dir, "custom-font.ttf"), true)
        else File(dir, "custom-font.ttf").delete()
        File(dir, "restore-journal.json").delete()
        fontBackup.delete()
    }
}

@Serializable private data class Recovery(val backup: Backup, val fontExisted: Boolean)

