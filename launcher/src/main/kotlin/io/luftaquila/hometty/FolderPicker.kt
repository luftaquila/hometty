package io.luftaquila.hometty

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.luftaquila.hometty.core.usableHomeFolder
import io.luftaquila.hometty.resources.R as TextR
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Picks the shell home by walking real folders. Only folders the app can enter are listed, so a
 * path the shell cannot use can never be chosen. Android's document picker is not used: it returns
 * a content URI, which a shell cannot cd into.
 */
@Composable
fun HomeFolderDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val context = LocalContext.current
    val appHome = remember { appFolder(context) }
    // Empty means the default, internal storage.
    val storage = remember { sharedStorage() }
    var path by
        rememberSaveable {
            mutableStateOf(
                listOf(current, storage.path).firstOrNull {
                    it.isNotBlank() && usableHomeFolder(it)
                } ?: appHome.path
            )
        }
    val folder = File(path)
    val parent = folder.parentFile?.takeIf { usableHomeFolder(it.path) }
    val children by
        produceState<List<File>?>(null, path) {
            value =
                withContext(Dispatchers.IO) {
                    File(path)
                        .listFiles()
                        .orEmpty()
                        .filter { usableHomeFolder(it.path) }
                        // Hidden folders last, like a file manager.
                        .sortedWith(compareBy({ it.name.startsWith(".") }, { it.name.lowercase() }))
                }
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(TextR.string.label_set_terminal_home)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (usableHomeFolder(storage.path))
                        FilterChip(
                            selected = path == storage.path,
                            onClick = { path = storage.path },
                            label = { Text(context.getString(TextR.string.label_folder_storage)) },
                        )
                    FilterChip(
                        selected = path == appHome.path,
                        onClick = { path = appHome.path },
                        label = { Text(context.getString(TextR.string.label_folder_app)) },
                    )
                }
                Text(
                    path,
                    Modifier.testTag("folderPath"),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider()
                LazyColumn(Modifier.heightIn(max = 320.dp).testTag("folderList")) {
                    if (parent != null)
                        item(key = "..") {
                            FolderRow(context.getString(TextR.string.label_folder_parent)) {
                                path = parent.path
                            }
                        }
                    items(children.orEmpty(), key = { it.path }) { child ->
                        FolderRow(child.name + "/", Modifier.testTag("folder:${child.name}")) {
                            path = child.path
                        }
                    }
                }
                if (children?.isEmpty() == true)
                    Text(
                        context.getString(TextR.string.label_folder_none),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                Text(
                    context.getString(TextR.string.settings_next_session),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(if (path == storage.path) "" else path) },
                modifier = Modifier.testTag("useFolder"),
            ) {
                Text(context.getString(TextR.string.label_folder_use))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(context.getString(TextR.string.label_cancel)) }
        },
    )
}

@Composable
private fun FolderRow(name: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        name,
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        fontFamily = FontFamily.Monospace,
    )
}
