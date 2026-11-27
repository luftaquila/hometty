package io.luftaquila.hometty.ui

import android.content.pm.LauncherApps
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import io.luftaquila.hometty.core.SearchIndex
import io.luftaquila.hometty.platform.DeviceAccess
import io.luftaquila.hometty.resources.R as TextR
import io.luftaquila.hometty.ui.settings.SettingsSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AppIcon(app: DeviceAccess.App, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val icon by
        produceState<ImageBitmap?>(null, app.id) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching {
                            context
                                .getSystemService(LauncherApps::class.java)
                                .getActivityList(app.packageName, app.user)
                                .firstOrNull { it.componentName == app.component }
                                ?.getBadgedIcon(0)
                                ?.toBitmap(96, 96)
                                ?.asImageBitmap()
                        }
                        .getOrNull()
                }
        }
    Box(
        modifier
            .size(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null)
            Image(
                icon!!,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(3.dp),
            )
        else Text(app.label.take(1), color = MaterialTheme.colorScheme.primary)
    }
}

/** Picks one app by name; [onSave] gets its package. */
@Composable
fun AppPicker(
    apps: List<DeviceAccess.App>,
    initial: String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(initial) }
    val candidates =
        remember(apps) { apps.distinctBy { it.packageName }.sortedBy { it.label.lowercase() } }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column {
                Header(context.getString(TextR.string.apps_choose_one), onDismiss)
                SettingsSearch(
                    query,
                    { query = it },
                    Modifier.testTag("appPickerSearch"),
                    context.getString(TextR.string.label_search_apps),
                )
                LazyColumn(Modifier.weight(1f).testTag("appPickerList")) {
                    items(
                        candidates.filter {
                            SearchIndex.score(query, it.label + " " + it.packageName) > 0
                        },
                        key = { it.packageName },
                    ) { app ->
                        val checked = app.packageName == selected
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(
                                    checked,
                                    role = Role.RadioButton,
                                    onClick = { selected = app.packageName },
                                )
                                .testTag("pick:${app.packageName}")
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            AppIcon(app)
                            Column(Modifier.weight(1f)) {
                                Text(app.label)
                                if (app.user != android.os.Process.myUserHandle())
                                    Text(
                                        context.getString(TextR.string.apps_profile),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                            }
                            RadioButton(checked, null)
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = { selected?.let(onSave) },
                        enabled = selected != null,
                        modifier = Modifier.testTag("appPickerSave"),
                    ) {
                        Text(context.getString(TextR.string.label_save))
                    }
                }
            }
        }
    }
}
