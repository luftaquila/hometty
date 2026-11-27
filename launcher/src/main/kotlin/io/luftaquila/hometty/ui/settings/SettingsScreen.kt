package io.luftaquila.hometty.ui.settings

import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.luftaquila.hometty.Route
import io.luftaquila.hometty.core.*
import io.luftaquila.hometty.resources.R as TextR
import io.luftaquila.hometty.resources.choiceLabel
import io.luftaquila.hometty.resources.message
import io.luftaquila.hometty.resources.text
import io.luftaquila.hometty.ui.Header
import io.luftaquila.hometty.ui.HomeViewModel
import io.luftaquila.hometty.ui.PlaceholderList
import io.luftaquila.hometty.ui.Placeholders
import io.luftaquila.hometty.ui.color
import io.luftaquila.hometty.ui.inserting
import io.luftaquila.hometty.ui.rememberCursorText

/** A titled block of settings; [id] also selects the links shown around its rows. */
data class SettingsGroup(val id: String, val title: Int, val keys: List<String> = emptyList())

/**
 * A settings page. Pages group settings by what a person wants to change, independently of the
 * storage key prefixes, which stay fixed so saved values are kept.
 */
data class SettingsSection(
    val id: String,
    val title: Int,
    val summary: Int,
    val glyph: SettingsGlyph,
    val groups: List<SettingsGroup>,
) {
    val keys
        get() = groups.flatMap { it.keys }
}

val settingsSections =
    listOf(
        SettingsSection(
            "appearance",
            TextR.string.section_display,
            TextR.string.section_display_detail,
            SettingsGlyph.Display,
            listOfNotNull(
                SettingsGroup("theme", TextR.string.group_theme_font, listOf(Settings.fontSize.key)),
                SettingsGroup(
                    "window",
                    TextR.string.group_window,
                    listOf(
                        Settings.fullscreen.key,
                        Settings.orientation.key,
                        Settings.wallpaper.key,
                        Settings.opacity.key,
                    ),
                ),
                // Per-app languages are Android's own setting, from Android 13.
                SettingsGroup("language", TextR.string.group_language).takeIf {
                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
                },
                SettingsGroup("reset", TextR.string.group_reset),
            ),
        ),
        SettingsSection(
            "terminal",
            TextR.string.section_terminal,
            TextR.string.section_terminal_detail,
            SettingsGlyph.Tools,
            listOf(
                SettingsGroup(
                    "prompt",
                    TextR.string.group_prompt,
                    listOf(Settings.prompt.key, Settings.username.key, Settings.deviceName.key),
                ),
                SettingsGroup(
                    "input",
                    TextR.string.group_input,
                    listOf(
                        Settings.autoKeyboard.key,
                        Settings.toolbar.key,
                        Settings.backHistory.key,
                        Settings.showHints.key,
                    ),
                ),
                SettingsGroup(
                    "output",
                    TextR.string.group_output,
                    listOf(Settings.statusLines.key, Settings.maxLines.key, Settings.clearOnLock.key),
                ),
                SettingsGroup(
                    "shell",
                    TextR.string.group_shell,
                    listOf(
                        Settings.shellHome.key,
                        Settings.rootEnabled.key,
                    ),
                ),
            ),
        ),
        // What apps print in the terminal (launches, changes, notifications) and how each app is
        // handled, on one page.
        SettingsSection(
            "apps",
            TextR.string.section_apps,
            TextR.string.section_apps_detail,
            SettingsGlyph.Apps,
            listOf(
                SettingsGroup(
                    "appMessages",
                    TextR.string.group_app_messages,
                    listOf(Settings.showLaunches.key, Settings.showChanges.key),
                ),
                SettingsGroup(
                    "viewing",
                    TextR.string.label_notifications,
                    listOf(
                        Settings.notificationsEnabled.key,
                        Settings.includeByDefault.key,
                        Settings.notificationFormat.key,
                        Settings.notificationTapOpen.key,
                    ),
                ),
                SettingsGroup("appManage", TextR.string.group_manage),
            ),
        ),
        SettingsSection(
            "tools",
            TextR.string.section_tools,
            TextR.string.section_tools_detail,
            SettingsGlyph.Data,
            listOf(
                SettingsGroup("search", TextR.string.group_search, listOf(Settings.searchEngine.key)),
                SettingsGroup("notes", TextR.string.label_notes),
                SettingsGroup(
                    "rss",
                    TextR.string.group_rss,
                    listOf(
                        Settings.rssEnabled.key,
                        Settings.rssInterval.key,
                        Settings.rssWifiOnly.key,
                        Settings.rssShowUpdates.key,
                    ),
                ),
                SettingsGroup(
                    "weather",
                    TextR.string.label_weather,
                    listOf(
                        Settings.weatherProvider.key,
                        Settings.units.key,
                        Settings.weatherFormat.key,
                        Settings.weatherInterval.key,
                    ),
                ),
            ),
        ),
        SettingsSection(
            "data",
            TextR.string.section_data,
            TextR.string.section_data_detail,
            SettingsGlyph.History,
            listOf(
                SettingsGroup(
                    "history",
                    TextR.string.group_history,
                    listOf(Settings.historyEnabled.key, Settings.historyLimit.key),
                ),
                SettingsGroup("backup", TextR.string.group_backup),
                SettingsGroup("danger", TextR.string.settings_danger),
            ),
        ),
    )

/** The page for a `config CATEGORY` argument or a page id. */
fun settingsSection(category: String): SettingsSection? =
    settingsSections.firstOrNull { it.id == category }
        ?: if (category == "notes") settingsSections.first { it.id == "tools" }
        else settingsSections.firstOrNull { section ->
            section.keys.any { it.substringBefore('.') == category }
        }

/** Where a setting is edited: its page, or the color page for colors. */
fun settingsRoute(key: String): Route.SettingsPage =
    Route.SettingsPage(
        if (Settings.byKey[key]?.kind == SettingKind.COLOR) "colors"
        else settingsSections.first { key in it.keys }.id
    )

private fun settingValue(
    context: Context,
    definition: SettingDefinition,
    settings: SettingsSnapshot,
): String {
    val value = settings.string(definition.key)
    return when {
        definition.kind == SettingKind.CHOICE || definition.kind == SettingKind.BOOLEAN ->
            context.choiceLabel(value)
        definition.key == Settings.fontSize.key ->
            context.getString(TextR.string.settings_font_size, value.toInt())
        definition.key == Settings.opacity.key ->
            context.getString(TextR.string.settings_percent, value.toInt())
        definition.key.endsWith("Minutes") ->
            context.getString(TextR.string.settings_minutes, value.toInt())
        definition.key == Settings.maxLines.key ->
            context.getString(TextR.string.settings_lines, value.toInt())
        definition.key == Settings.historyLimit.key ->
            context.getString(TextR.string.settings_items, value.toInt())
        definition.key == Settings.shellHome.key && value.isBlank() ->
            context.getString(TextR.string.settings_default_home)
        else -> value.ifBlank { context.getString(TextR.string.label_empty) }
    }
}

@Composable
fun SettingsScreen(model: HomeViewModel, category: String) {
    val context = LocalContext.current
    val settings by model.settings.collectAsStateWithLifecycle()
    val target by model.settingsTarget.collectAsStateWithLifecycle()
    var query by rememberSaveable(category) { mutableStateOf("") }
    var editingKey by rememberSaveable(category) { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    val section = settingsSection(category)
    val changed = category == "changed"
    val title =
        if (category == "colors") context.getString(TextR.string.settings_color_customization)
        else if (changed) context.getString(TextR.string.settings_changed)
        else
            section?.let { context.getString(it.title) }
                ?: context.getString(TextR.string.label_settings)
    val focus = Settings.byKey[target]?.takeIf { section != null && it.key in section.keys }
    val searchResults =
        Settings.terminalUi.filter {
            SearchIndex.score(
                query,
                context.text(it.title) + " " + context.text(it.description) + " " + it.key,
            ) > 0
        }
    val links = buildList {
        add(context.getString(TextR.string.start_title) to Route.Start)
        add(context.getString(TextR.string.label_theme_presets) to Route.Theme)
        add(context.getString(TextR.string.label_font) to Route.Font)
        add(context.getString(TextR.string.label_manage_apps) to Route.Apps)
        add(context.getString(TextR.string.label_backup_restore) to Route.Backup)
        add(context.getString(TextR.string.settings_changed) to Route.SettingsPage("changed"))
        io.luftaquila.hometty.core.Collections.all.forEach {
            add(context.text(it.title) to Route.Manage(it.kind))
        }
    }
    Column(Modifier.fillMaxSize()) {
        Header(title, model::back)
        SettingsSearch(
            query,
            { query = it },
            Modifier.testTag("settingsSearch"),
            context.getString(TextR.string.label_search_settings),
        )
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).testTag("settingsList"),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            when {
                query.isNotBlank() -> {
                    val matchedLinks =
                        links.filter {
                            val names =
                                when (it.second) {
                                    Route.Theme -> Themes.presets.keys
                                    Route.Font -> Fonts.choices
                                    Route.Start -> listOf(context.getString(TextR.string.start_search_terms))
                                    else -> emptyList()
                                }
                            val searchable =
                                it.first +
                                    " " +
                                    names.joinToString(" ") { name -> context.choiceLabel(name) }
                            SearchIndex.score(query, searchable) > 0
                        }
                    if (searchResults.isEmpty() && matchedLinks.isEmpty())
                        item { EmptySettings(context.getString(TextR.string.settings_no_results)) }
                    items(matchedLinks, key = { it.second.path }) { (label, route) ->
                        SettingsLink(label) { model.navigate(route) }
                    }
                    items(searchResults, key = { it.key }) { definition ->
                        PreferenceRow(
                            context.text(definition.title),
                            settingsRoute(definition.key).page.let { page ->
                                if (page == "colors")
                                    context.getString(TextR.string.settings_color_customization)
                                else context.getString(settingsSection(page)!!.title)
                            },
                            Modifier.testTag("search:${definition.key}"),
                            onClick = { model.openSetting(definition.key) },
                        )
                    }
                }
                category.isBlank() -> {
                    item {
                        SettingsLink(
                            context.getString(TextR.string.start_title),
                            context.getString(TextR.string.start_summary),
                            SettingsGlyph.Shield,
                        ) {
                            model.navigate(Route.Start)
                        }
                    }
                    items(settingsSections, key = { it.id }) { item ->
                        SettingsLink(
                            context.getString(item.title),
                            context.getString(item.summary),
                            item.glyph,
                        ) {
                            model.navigate(Route.SettingsPage(item.id))
                        }
                        // Aliases have their own entry beside the terminal settings.
                        if (item.id == "terminal") {
                            val aliases = io.luftaquila.hometty.core.Collections.byKind.getValue("alias")
                            PreferenceRow(
                                context.text(aliases.title),
                                context.getString(TextR.string.section_aliases_detail),
                                Modifier.testTag("settingsAliases"),
                                glyph = SettingsGlyph.Alias,
                                onClick = { model.navigate(Route.Manage("alias")) },
                            )
                        }
                    }
                    item {
                        HorizontalDivider(
                            Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    item {
                        SettingsLink(
                            context.getString(TextR.string.donate_title),
                            context.getString(TextR.string.donate_summary),
                        ) {
                            model.navigate(Route.Donate)
                        }
                    }
                    item {
                        SettingsLink(context.getString(TextR.string.label_about)) {
                            model.navigate(Route.About)
                        }
                    }
                }
                category == "colors" -> {
                    item { TerminalPreview(settings, Modifier.padding(20.dp)) }
                    items(
                        Settings.terminalUi.filter { it.kind == SettingKind.COLOR },
                        key = { it.key },
                    ) { definition ->
                        SettingRow(definition, settings, model) { editingKey = definition.key }
                    }
                }
                changed -> {
                    val definitions =
                        Settings.terminalUi.filter { settings.string(it.key) != it.default }
                    val font = settings[Settings.font]
                    val theme = Themes.base(settings)
                    val defaultTheme = Settings.byKey.getValue(Settings.theme.key).default
                    if (definitions.isEmpty() && font == Fonts.DEFAULT && theme == defaultTheme)
                        item { EmptySettings(context.getString(TextR.string.settings_unchanged)) }
                    if (theme != defaultTheme)
                        item {
                            PreferenceRow(
                                context.getString(TextR.string.label_theme_presets),
                                Themes.selected(settings)?.let(context::choiceLabel)
                                    ?: context.getString(TextR.string.theme_custom),
                                Modifier.testTag("changed:appearance.theme"),
                                trailing = {
                                    TextButton(
                                        onClick = {
                                            model.updateSettings(Themes.values(defaultTheme))
                                        }
                                    ) {
                                        Text(
                                            context.getString(TextR.string.settings_restore_default)
                                        )
                                    }
                                },
                            )
                        }
                    if (font != Fonts.DEFAULT)
                        item {
                            PreferenceRow(
                                context.getString(TextR.string.label_font),
                                context.choiceLabel(font),
                                Modifier.testTag("changed:appearance.font"),
                                trailing = {
                                    TextButton(
                                        onClick = {
                                            model.resetSetting(Settings.font.key)
                                        }
                                    ) {
                                        Text(
                                            context.getString(TextR.string.settings_restore_default)
                                        )
                                    }
                                },
                            )
                        }
                    items(definitions, key = { it.key }) { definition ->
                        PreferenceRow(
                            context.text(definition.title),
                            settingValue(context, definition, settings),
                            Modifier.testTag("changed:${definition.key}"),
                            trailing = {
                                TextButton(
                                    onClick = {
                                        model.resetSetting(definition.key)
                                    }
                                ) {
                                    Text(context.getString(TextR.string.settings_restore_default))
                                }
                            },
                        )
                    }
                }
                section != null -> {
                    if (focus != null)
                        item(key = "focus") {
                            val gate = Settings.gate(focus)
                            Surface(
                                Modifier.padding(horizontal = 12.dp),
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                            ) {
                                Column {
                                    if (gate != null && !settings[gate]) {
                                        Text(
                                            context.getString(
                                                TextR.string.settings_enable_first,
                                                context.text(gate.definition.title),
                                            ),
                                            Modifier.padding(20.dp),
                                        )
                                        SettingRow(gate.definition, settings, model) {
                                            editingKey = gate.key
                                        }
                                    } else
                                        SettingRow(focus, settings, model) {
                                            editingKey = focus.key
                                        }
                                }
                            }
                        }
                    val visible = Settings.visibleSettings(settings).map { it.key }.toSet()
                    section.groups.forEach { group ->
                        item(key = "label:${group.id}") {
                            SectionLabel(context.getString(group.title))
                        }
                        item(key = "before:${group.id}") {
                            GroupLinksBefore(group.id, settings, model)
                        }
                        items(
                            group.keys.filter { it in visible && it != focus?.key },
                            key = { it },
                        ) { key ->
                            SettingRow(Settings.byKey.getValue(key), settings, model) {
                                editingKey = key
                            }
                        }
                        item(key = "after:${group.id}") {
                            GroupLinksAfter(
                                group.id,
                                settings,
                                model,
                                confirm = { confirmation = it },
                            )
                        }
                    }
                }
            }
        }
    }
    editingKey?.let { key ->
        val definition = Settings.byKey.getValue(key)
        if (key == Settings.shellHome.key)
            HomeFolderDialog(settings.string(key), { editingKey = null }) { value ->
                model.setSetting(key, value) { saved -> if (saved) editingKey = null }
            }
        else if (key == Settings.weatherProvider.key)
            WeatherProviderDialog(model, settings) { editingKey = null }
        else SettingDialog(
            definition,
            settings,
            { editingKey = null },
            { value ->
                model.setSetting(key, value) { saved -> if (saved) editingKey = null }
            },
        )
    }
    confirmation?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = {
                Text(
                    context.getString(
                        if (action == "appearance") TextR.string.label_recover_appearance
                        else TextR.string.label_reset_3
                    )
                )
            },
            text = {
                Text(
                    context.getString(
                        if (action == "appearance") TextR.string.settings_recover_confirm
                        else
                            TextR.string
                                .label_deletes_settings_notes_aliases_rules_history_and_api_keys_shell_h
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmation = null
                        if (action == "appearance") model.recoverAppearance()
                        else model.resetPreferences()
                    }
                ) {
                    Text(context.getString(TextR.string.label_reset_2))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = null }) {
                    Text(context.getString(TextR.string.label_cancel))
                }
            },
        )
    }
    val result by model.commandResult.collectAsStateWithLifecycle()
    result?.let { (command, text) ->
        AlertDialog(
            onDismissRequest = { model.commandResult.value = null },
            title = { Text(command) },
            text = {
                Text(
                    text.ifBlank { context.getString(TextR.string.editor_no_details) },
                    Modifier.verticalScroll(rememberScrollState()).testTag("commandResult"),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = { model.commandResult.value = null }) {
                    Text(context.getString(TextR.string.label_dismiss))
                }
            },
        )
    }
}

/** Links shown above a group's settings rows. */
@Composable
private fun GroupLinksBefore(group: String, settings: SettingsSnapshot, model: HomeViewModel) {
    val context = LocalContext.current
    when (group) {
        "theme" -> {
            SettingsLink(
                context.getString(TextR.string.label_theme_presets),
                Themes.selected(settings)?.let(context::choiceLabel)
                    ?: context.getString(TextR.string.theme_custom),
                SettingsGlyph.Display,
            ) {
                model.navigate(Route.Theme)
            }
            TerminalPreview(settings, Modifier.padding(horizontal = 20.dp))
            SettingsLink(
                context.getString(TextR.string.label_font),
                context.choiceLabel(settings[Settings.font]),
            ) {
                model.navigate(Route.Font)
            }
            SettingsLink(
                context.getString(TextR.string.settings_color_customization),
                context.getString(TextR.string.link_custom_colors_desc),
            ) {
                model.navigate(Route.SettingsPage("colors"))
            }
        }
        "language" ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
                AppLanguage()
        "viewing" ->
            if (!notificationAccessGranted(context))
                SettingsLink(
                    context.getString(TextR.string.link_notification_access_needed),
                    context.getString(TextR.string.link_notification_access_needed_desc),
                    SettingsGlyph.Shield,
                ) {
                    model.navigate(Route.Start)
                }
    }
}

/** Links and managers shown below a group's settings rows. */
@Composable
private fun GroupLinksAfter(
    group: String,
    settings: SettingsSnapshot,
    model: HomeViewModel,
    confirm: (String) -> Unit,
) {
    val context = LocalContext.current
    @Composable
    fun manager(kind: String) {
        val spec = io.luftaquila.hometty.core.Collections.byKind.getValue(kind)
        SettingsLink(context.text(spec.title), context.text(spec.description)) {
            model.navigate(Route.Manage(kind))
        }
    }
    when (group) {
        "reset" ->
            SettingsLink(
                context.getString(TextR.string.label_recover_appearance),
                context.getString(TextR.string.link_recover_appearance_desc),
            ) {
                confirm("appearance")
            }
        "appManage" -> {
            SettingsLink(
                context.getString(TextR.string.label_manage_apps),
                context.getString(TextR.string.link_manage_apps_desc),
            ) {
                model.navigate(Route.Apps)
            }
        }
        "viewing" -> manager("notificationRule")
        "notes" -> manager("note")
        "rss" -> manager("rss")
        "weather" ->
            if (settings[Settings.weatherProvider] != "off")
                SettingsLink(context.getString(TextR.string.label_test_weather)) {
                    model.runInDialog("weather")
                }
        "backup" ->
            SettingsLink(
                context.getString(TextR.string.label_backup_restore),
                context.getString(TextR.string.link_backup_desc),
            ) {
                model.navigate(Route.Backup)
            }
        "danger" -> {
            // Changes from the defaults, each restorable, sit beside resetting everything.
            SettingsLink(
                context.getString(TextR.string.settings_changed),
                context.getString(TextR.string.settings_changed_detail),
            ) {
                model.navigate(Route.SettingsPage("changed"))
            }
            SettingsLink(
                context.getString(TextR.string.label_reset_settings_and_data),
                context.getString(
                    TextR.string.label_deletes_settings_notes_aliases_rules_history_and_api_keys_shell_h
                ),
            ) {
                confirm("all")
            }
        }
    }
}

@Composable
private fun EmptySettings(text: String) {
    Text(text, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SettingRow(
    definition: SettingDefinition,
    settings: SettingsSnapshot,
    model: HomeViewModel,
    edit: () -> Unit,
) {
    val context = LocalContext.current
    val modifier = Modifier.testTag("setting:${definition.key}")
    // Every row says what the setting changes; the current value sits on the right.
    val description = definition.description.takeIf { it.isNotBlank() }?.let(context::text)
    if (definition.kind == SettingKind.BOOLEAN)
        TogglePreference(
            context.text(definition.title),
            settings.bool(definition.key),
            { model.setSetting(definition.key, it.toString()) },
            modifier,
            description,
        )
    else
        PreferenceRow(
            context.text(definition.title),
            description,
            modifier,
            onClick = edit,
            trailing = {
                if (definition.kind == SettingKind.COLOR)
                    Surface(
                        Modifier.size(28.dp),
                        color = color(settings.string(definition.key)),
                        shape = RoundedCornerShape(9.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    ) {}
                else
                    Text(
                        settingValue(context, definition, settings),
                        Modifier.widthIn(max = 132.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
            },
        )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingDialog(
    definition: SettingDefinition,
    settings: SettingsSnapshot,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val context = LocalContext.current
    var value by
        rememberSaveable(definition.key) { mutableStateOf(settings.string(definition.key)) }
    // The text field's own state, with the cursor where placeholders go.
    var input by rememberCursorText(definition.key, value)
    val validation =
        runCatching { definition.validate(value) }.exceptionOrNull()?.let(context::message)
    val slider = definition.step != null || definition.key in setOf(Settings.fontSize.key, Settings.opacity.key)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.text(definition.title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (definition.description.isNotBlank())
                    Text(
                        context.text(definition.description),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                if (definition.kind == SettingKind.COLOR) ColorSwatches(value) { value = it }
                when {
                    definition.kind == SettingKind.CHOICE && definition.choices.size <= 3 ->
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            definition.choices.forEachIndexed { index, choice ->
                                SegmentedButton(
                                    selected = value == choice,
                                    onClick = { value = choice },
                                    shape =
                                        SegmentedButtonDefaults.itemShape(
                                            index,
                                            definition.choices.size,
                                        ),
                                ) {
                                    Text(context.choiceLabel(choice))
                                }
                            }
                        }
                    definition.kind == SettingKind.CHOICE ->
                        definition.choices.forEach { choice ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .selectable(
                                        value == choice,
                                        role = Role.RadioButton,
                                        onClick = { value = choice },
                                    )
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(value == choice, null)
                                Text(context.choiceLabel(choice))
                            }
                        }
                    slider -> {
                        Text(
                            settingValue(
                                context,
                                definition,
                                settings.copy(values = settings.values + (definition.key to value)),
                            ),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        PreferenceSlider(
                            value.toFloat(),
                            // Rounded: a stop such as 10000 can come back as 9999.999.
                            { value = kotlin.math.round(it).toInt().toString() },
                            range = definition.min!!.toFloat()..definition.max!!.toFloat(),
                            steps = (definition.max!! - definition.min!!) / (definition.step ?: 1) - 1,
                        )
                    }
                    else ->
                        OutlinedTextField(
                            input,
                            {
                                input = it
                                value = it.text
                            },
                            label = { Text(context.text(definition.title)) },
                            modifier = Modifier.fillMaxWidth(),
                            isError = validation != null,
                            singleLine =
                                definition.key !in
                                    setOf(
                                        Settings.notificationFormat.key,
                                        Settings.weatherFormat.key,
                                        Settings.statusLines.key,
                                    ),
                            keyboardOptions =
                                KeyboardOptions(
                                    keyboardType =
                                        if (definition.kind == SettingKind.INTEGER) KeyboardType.Number
                                        else KeyboardType.Text
                                ),
                        )
                }
                if (validation != null) Text(validation, color = MaterialTheme.colorScheme.error)
                PlaceholderList(Placeholders.forSetting(definition.key)) {
                    input = input.inserting(it)
                    value = input.text
                }
                if (definition.category == "appearance" && validation == null)
                    TerminalPreview(
                        settings.copy(values = settings.values + (definition.key to value))
                    )
                if (
                    (definition.category in setOf("home", "terminal", "privacy") ||
                        definition.key == Settings.inputColor.key) &&
                        definition.key != Settings.statusLines.key
                )
                    Text(
                        context.getString(TextR.string.settings_next_session),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                TextButton(onClick = { value = definition.default }) {
                    Text(context.getString(TextR.string.settings_restore_default))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }, enabled = validation == null) {
                Text(context.getString(TextR.string.label_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(context.getString(TextR.string.label_cancel)) }
        },
    )
}

/** The weather service; choosing OpenWeather asks for its API key right here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeatherProviderDialog(model: HomeViewModel, settings: SettingsSnapshot, dismiss: () -> Unit) {
    val context = LocalContext.current
    val definition = Settings.weatherProvider.definition
    var provider by rememberSaveable { mutableStateOf(settings[Settings.weatherProvider]) }
    // Not saveable: the key stays out of the saved instance state.
    val key = remember { androidx.compose.foundation.text.input.TextFieldState(model.weatherKey()) }
    val openWeather = provider == "openweather"
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(context.text(definition.title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    definition.choices.forEachIndexed { index, choice ->
                        SegmentedButton(
                            selected = provider == choice,
                            onClick = { provider = choice },
                            shape = SegmentedButtonDefaults.itemShape(index, definition.choices.size),
                            modifier = Modifier.testTag("weatherProvider:$choice"),
                        ) {
                            Text(context.choiceLabel(choice))
                        }
                    }
                }
                if (openWeather) {
                    OutlinedSecureTextField(
                        key,
                        Modifier.fillMaxWidth().testTag("weatherKey"),
                        label = { Text(context.getString(TextR.string.label_openweather_api_key)) },
                    )
                    TextButton(
                        onClick = { model.device.open("https://home.openweathermap.org/api_keys") },
                        Modifier.testTag("openWeatherSite"),
                    ) {
                        Text(context.getString(TextR.string.label_get_weather_key))
                    }
                }
            }
        },
        confirmButton = {
            // OpenWeather without a key would only fail later.
            TextButton(
                onClick = { model.saveWeatherProvider(provider, key.text.toString(), dismiss) },
                enabled = !openWeather || key.text.isNotBlank(),
            ) {
                Text(context.getString(TextR.string.label_save))
            }
        },
        dismissButton = {
            TextButton(onClick = dismiss) { Text(context.getString(TextR.string.label_cancel)) }
        },
    )
}

@Composable
fun ColorSwatches(value: String, select: (String) -> Unit) {
    val swatches =
        listOf(
            "#282A36",
            "#F8F8F2",
            "#50FA7B",
            "#BD93F9",
            "#FF79C6",
            "#8BE9FD",
            "#FF5555",
            "#F1FA8C",
            "#000000",
            "#FFFFFF",
            "#00FF00",
            "#FFB86C",
        )
    Column {
        swatches.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                row.forEach { hex ->
                    Box(
                        Modifier.size(48.dp)
                            .selectable(
                                value.equals(hex, true),
                                role = Role.RadioButton,
                                onClick = { select(hex) },
                            )
                            .semantics { contentDescription = hex },
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            Modifier.size(32.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = color(hex),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        ) {
                            if (value.equals(hex, true))
                                Box(contentAlignment = Alignment.Center) {
                                    SettingsIcon(
                                        SettingsGlyph.Check,
                                        tint =
                                            readable(
                                                androidx.compose.ui.graphics.Color.White,
                                                color(hex),
                                            ),
                                    )
                                }
                        }
                    }
                }
            }
        }
    }
}

/** The app's language, kept by Android (per-app language) rather than in the settings. */
@androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.TIRAMISU)
@Composable
private fun AppLanguage() {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(android.app.LocaleManager::class.java) }
    // Empty: follow the system. The names stay in their own language, as Android shows them.
    val choices =
        listOf(
            "" to context.getString(TextR.string.language_system_default),
            "en" to "English",
            "ko" to "한국어",
        )
    val current = manager.applicationLocales.toLanguageTags().substringBefore('-')
    var choosing by remember { mutableStateOf(false) }
    SettingsLink(
        context.getString(TextR.string.label_app_language),
        choices.firstOrNull { it.first == current }?.second ?: choices.first().second,
    ) {
        choosing = true
    }
    if (choosing)
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(context.getString(TextR.string.label_app_language)) },
            text = {
                Column {
                    choices.forEach { (tag, label) ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(
                                    tag == current,
                                    role = Role.RadioButton,
                                    onClick = {
                                        choosing = false
                                        // Android recreates the screens in the new language.
                                        manager.applicationLocales =
                                            android.os.LocaleList.forLanguageTags(tag)
                                    },
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(tag == current, null)
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choosing = false }) {
                    Text(context.getString(TextR.string.label_cancel))
                }
            },
        )
}
