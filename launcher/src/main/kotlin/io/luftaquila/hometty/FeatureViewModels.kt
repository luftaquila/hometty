package io.luftaquila.hometty

import android.content.Context
import android.net.Uri
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.luftaquila.hometty.core.Fonts
import io.luftaquila.hometty.core.readBytesLimited
import io.luftaquila.hometty.data.Storage
import io.luftaquila.hometty.resources.R as TextR
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import io.luftaquila.hometty.core.Settings
import io.luftaquila.hometty.core.get

/** ZIP backup export and the restore preview. */
@HiltViewModel
class BackupViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    banner: ErrorBanner,
    private val storage: Storage,
    private val runtime: LauncherRuntime,
    private val notices: TerminalNotices,
) : LauncherViewModel(context, banner) {
    /** A checked backup waiting for the person to confirm the restore. */
    val preview = MutableStateFlow<Storage.ImportPreview?>(null)

    fun export(destination: Uri) = launchAction {
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(destination)?.use { storage.export(it) }
                ?: error(context.getString(TextR.string.message_cannot_write_backup))
        }
        notices.print(context.getString(TextR.string.message_backup_saved))
    }

    fun inspect(source: Uri) = launchAction {
        preview.value =
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(source)?.use { storage.inspect(it) }
                    ?: error(context.getString(TextR.string.message_cannot_read_backup))
            }
    }

    /** Replaces settings and records with the previewed backup; the shell restarts without root. */
    fun restore() = launchAction {
        val backup = requireNotNull(preview.value)
        runtime.stopTerminal()
        withContext(Dispatchers.IO) { storage.restore(backup) }
        preview.value = null
        notices.print(context.getString(TextR.string.message_backup_restored_root_is_disabled))
    }
}

/** The imported terminal font. */
@HiltViewModel
class FontViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    banner: ErrorBanner,
    private val storage: Storage,
) : LauncherViewModel(context, banner) {
    /** Bumped when the imported font file changes, so typefaces built from it are rebuilt. */
    val revision = MutableStateFlow(0)

    /**
     * Stores [source] as the imported font. The font screen selects it in its unsaved draft
     * through [imported].
     */
    fun import(source: Uri, imported: () -> Unit = {}) = launchAction {
        withContext(Dispatchers.IO) {
            val bytes =
                context.contentResolver.openInputStream(source)?.use {
                    it.readBytesLimited(8 * 1024 * 1024)
                } ?: error(context.getString(TextR.string.message_cannot_read_font))
            val temporary = File(context.cacheDir, "font-import.ttf")
            temporary.writeBytes(bytes)
            try {
                android.graphics.fonts.Font.Builder(temporary).build()
                temporary.copyTo(TerminalFonts.customFile(context), true)
            } catch (e: Exception) {
                // Font.Builder rejects broken files with IOException or IllegalArgumentException.
                error(context.getString(TextR.string.message_cannot_read_font))
            } finally {
                temporary.delete()
            }
        }
        revision.value++
        storage.set(Settings.customFont.key, TerminalFonts.CUSTOM_FILE)
        imported()
    }

    /** Deletes the imported font; the terminal returns to the default font if it used it. */
    fun remove() = launchAction {
        storage.update(
            buildMap {
                put(Settings.customFont.key, "")
                if (storage.snapshot()[Settings.font] == Fonts.CUSTOM)
                    put(Settings.font.key, Fonts.DEFAULT)
            }
        )
        withContext(Dispatchers.IO) { TerminalFonts.customFile(context).delete() }
        revision.value++
    }
}

/** The screen models besides [HomeViewModel], created by the activity and passed to screens. */
class FeatureModels(val backup: BackupViewModel, val fonts: FontViewModel)
