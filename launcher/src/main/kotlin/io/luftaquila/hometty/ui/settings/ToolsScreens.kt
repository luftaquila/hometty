package io.luftaquila.hometty.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.luftaquila.hometty.BuildConfig
import io.luftaquila.hometty.Route
import io.luftaquila.hometty.resources.R as TextR
import io.luftaquila.hometty.resources.text
import io.luftaquila.hometty.ui.Header
import io.luftaquila.hometty.ui.HomeViewModel

@Composable
fun BackupScreen(model: HomeViewModel, backup: BackupViewModel) {
    val textContext = androidx.compose.ui.platform.LocalContext.current
    val create =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip")
        ) {
            it?.let(backup::export)
        }
    val open =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            it?.let(backup::inspect)
        }
    val preview by backup.preview.collectAsStateWithLifecycle()
    Column {
        Header(textContext.getString(TextR.string.label_backup_restore), model::back)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                textContext.getString(
                    TextR.string
                        .label_includes_settings_aliases_notes_rules_and_custom_font_excludes_ap
                ),
                Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingsLink(
                textContext.getString(TextR.string.label_export_backup),
                textContext.getString(TextR.string.backup_export_detail),
                SettingsGlyph.Data,
            ) {
                create.launch("hometty-backup-${java.time.LocalDate.now()}.zip")
            }
            SettingsLink(
                textContext.getString(TextR.string.label_import_backup),
                textContext.getString(TextR.string.backup_import_detail),
                SettingsGlyph.Data,
            ) {
                open.launch(arrayOf("application/zip", "application/octet-stream"))
            }
        }
    }
    preview?.let { value ->
        AlertDialog(
            onDismissRequest = { backup.preview.value = null },
            title = { Text(textContext.getString(TextR.string.label_restore_backup)) },
            text = {
                Text(
                    textContext.getString(
                        TextR.string.backup_restore_summary,
                        textContext.resources.getQuantityString(
                            TextR.plurals.settings_count,
                            value.backup.settings.values.size,
                            value.backup.settings.values.size,
                        ),
                        textContext.resources.getQuantityString(
                            TextR.plurals.entries_count,
                            value.backup.entries.size,
                            value.backup.entries.size,
                        ),
                        value.backup.entries
                            .groupingBy { it.kind }
                            .eachCount()
                            .entries
                            .joinToString("\n") { item ->
                                val title =
                                    io.luftaquila.hometty.core.Collections.byKind[item.key]
                                        ?.title
                                        ?.let(textContext::text)
                                        ?: textContext.getString(
                                            if (item.key == "app") TextR.string.label_apps
                                            else TextR.string.backup_entries
                                        )
                                "$title: ${item.value}"
                            },
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { backup.restore() }) {
                    Text(textContext.getString(TextR.string.label_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { backup.preview.value = null }) {
                    Text(textContext.getString(TextR.string.label_cancel))
                }
            },
        )
    }
}

@Composable
fun AboutScreen(model: HomeViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Header(context.getString(TextR.string.label_about), model::back)
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Text(
                    "hometty",
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
                    style = MaterialTheme.typography.headlineLarge,
                )
                Text(
                    BuildConfig.VERSION_NAME,
                    Modifier.padding(horizontal = 20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    context.getString(TextR.string.about_description),
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
                )
            }
            item {
                SettingsLink(context.getString(TextR.string.about_source_code), "github.com/luftaquila/hometty") {
                    model.device.open("https://github.com/luftaquila/hometty")
                }
            }
            item {
                SettingsLink(context.getString(TextR.string.about_inspired_by), "github.com/fandreuz/TUI-ConsoleLauncher") {
                    model.device.open("https://github.com/fandreuz/TUI-ConsoleLauncher")
                }
            }
            item {
                SettingsLink(context.getString(TextR.string.donate_title), context.getString(TextR.string.donate_summary)) {
                    model.navigate(Route.Donate)
                }
            }
            item {
                SettingsLink(context.getString(TextR.string.label_open_source_notices)) {
                    model.navigate(Route.Licenses)
                }
            }
        }
    }
}
