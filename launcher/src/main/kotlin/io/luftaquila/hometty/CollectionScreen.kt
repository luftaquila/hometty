package io.luftaquila.hometty

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.luftaquila.hometty.core.*
import io.luftaquila.hometty.resources.R as TextR
import io.luftaquila.hometty.resources.message
import io.luftaquila.hometty.resources.text
import kotlinx.coroutines.launch

@Composable
fun CollectionScreen(model: HomeViewModel, kind: String) {
    val textContext = androidx.compose.ui.platform.LocalContext.current
    val spec = io.luftaquila.hometty.core.Collections.byKind[kind] ?: return
    val entries by model.entries.collectAsStateWithLifecycle()
    val apps by model.apps.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Entry?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Entry?>(null) }
    var sort by rememberSaveable(kind) { mutableStateOf("name") }
    var sortMenu by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    val collator = remember { java.text.Collator.getInstance() }
    Column(Modifier.fillMaxSize()) {
        Header(textContext.text(spec.title), model::back) {
            TextButton(onClick = { adding = true }) {
                Text(textContext.getString(TextR.string.label_add))
            }
        }
        Text(
            textContext.text(spec.description),
            Modifier.padding(horizontal = 20.dp).padding(bottom = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SettingsSearch(query, { query = it }) }
            Box {
                TextButton(
                    onClick = { sortMenu = true },
                    modifier = Modifier.padding(end = 8.dp).testTag("collectionSort"),
                ) {
                    Text(textContext.getString(sortLabel(sort)))
                }
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    listOf("name", "newest", "oldest").forEach { option ->
                        DropdownMenuItem(
                            text = { Text(textContext.getString(sortLabel(option))) },
                            onClick = {
                                sort = option
                                sortMenu = false
                            },
                        )
                    }
                }
            }
        }
        val records =
            entries
                .filter {
                    it.kind == kind &&
                        (query.isBlank() ||
                            SearchIndex.score(
                                query,
                                it.name + " " + it.fields.values.joinToString(),
                            ) > 0)
                }
                .let { list ->
                    when (sort) {
                        "newest" -> list.sortedByDescending { it.created }
                        "oldest" -> list.sortedBy { it.created }
                        else -> list.sortedWith { a, b -> collator.compare(a.name, b.name) }
                    }
                }
        if (records.isEmpty())
            Text(
                textContext.getString(TextR.string.label_no_entries_tap_add_to_create_one),
                Modifier.padding(24.dp),
            )
        // One line per entry, so long lists such as aliases fit on screen.
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(records, key = { it.id }) { entry ->
                Row(
                    Modifier.fillMaxWidth()
                        .combinedClickable(
                            onClick = { if (entry.fields["locked"] != "true") editing = entry },
                            onLongClick = { menuFor = entry.id },
                        )
                        .padding(start = 20.dp, top = 2.dp, bottom = 2.dp)
                        .testTag("entry:${entry.name}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        entry.name,
                        Modifier.widthIn(min = 56.dp, max = 160.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Text(
                        entrySummary(entry, apps, textContext),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Box {
                        IconButton(onClick = { menuFor = entry.id }, Modifier.size(40.dp)) {
                            SettingsIcon(SettingsGlyph.More)
                        }
                        DropdownMenu(menuFor == entry.id, { menuFor = null }) {
                            @Composable
                            fun action(label: Int, run: () -> Unit) =
                                DropdownMenuItem(
                                    text = { Text(textContext.getString(label)) },
                                    onClick = {
                                        menuFor = null
                                        run()
                                    },
                                )
                            when (kind) {
                                "alias" ->
                                    action(TextR.string.label_use) {
                                        model.stageCommand(entry.name)
                                        model.navigate(Route.Home)
                                    }
                                "rss" ->
                                    action(TextR.string.label_refresh) {
                                        model.runFromSettings(
                                            "rss update ${Shell.quote(entry.name)}"
                                        )
                                    }
                                "note" ->
                                    action(TextR.string.label_copy) {
                                        model.device.copy(entry.fields["text"].orEmpty())
                                    }
                            }
                            // A locked note can be neither edited nor deleted.
                            if (entry.fields["locked"] != "true") {
                                action(TextR.string.label_edit) { editing = entry }
                                action(TextR.string.label_delete) { deleting = entry }
                            }
                        }
                    }
                }
            }
        }
    }
    if (adding || editing != null) {
        val existing = editing
        EntryDialog(
            spec,
            existing,
            model,
            onDismiss = {
                adding = false
                editing = null
            },
        )
    }
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(textContext.getString(TextR.string.delete_entry_title, entry.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.deleteEntry(entry)
                        deleting = null
                    }
                ) {
                    Text(textContext.getString(TextR.string.label_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(textContext.getString(TextR.string.label_cancel))
                }
            },
        )
    }
}

private fun sortLabel(sort: String) =
    when (sort) {
        "newest" -> TextR.string.sort_newest
        "oldest" -> TextR.string.sort_oldest
        else -> TextR.string.sort_name
    }

/** A notification as the terminal will print it, with the prompt below. */
@Composable
private fun NotificationPreview(settings: SettingsSnapshot, app: String, fields: Map<String, String>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val typeface = TerminalFonts.typeface(context, settings[Settings.font])
    val family = remember(typeface) { androidx.compose.ui.text.font.FontFamily(typeface) }
    val size = settings[Settings.fontSize].sp
    val runs =
        NotificationLines.runs(
            fields["format"].orEmpty().ifBlank { settings[Settings.notificationFormat] },
            NotificationContent(
                fields["package"].orEmpty(),
                app,
                context.getString(TextR.string.notification_sample_title),
                context.getString(TextR.string.notification_sample_text),
            ),
        )
    val time = remember { java.time.LocalTime.now().format(TerminalNotices.TIME) }
    // A rule's color colors the whole text; without one the app name takes the accent.
    val ruleColor = fields["color"].orEmpty().isNotBlank()
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = color(NotificationLines.timeColor(settings)))) { append(time) }
        append(" ")
        runs.forEach { run ->
            when {
                ruleColor -> append(run.text)
                run.part == NotificationLines.Part.APP ->
                    withStyle(SpanStyle(color = color(NotificationLines.appColor(settings)))) {
                        append(run.text)
                    }
                else -> append(run.text)
            }
        }
    }
    SectionLabel(context.getString(TextR.string.notification_preview))
    Column(
        Modifier.padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .background(color(settings[Settings.background]))
            .padding(16.dp)
            .testTag("notificationPreview")
    ) {
        Text(
            text,
            // A half-typed color code shows the terminal's text color until it is complete.
            color =
                runCatching { color(fields["color"].orEmpty()) }
                    .getOrElse { color(settings[Settings.foreground]) },
            fontFamily = family,
            fontSize = size,
            modifier = Modifier.testTag("notificationPreviewText"),
        )
        Text(
            promptPreview(settings),
            color = color(settings[Settings.inputColor]),
            fontFamily = family,
            fontSize = size,
        )
    }
}

/** An optional color: blank keeps the terminal's text color. */
@Composable
private fun ColorField(label: String, value: String, change: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    SectionLabel(label)
    TogglePreference(
        context.getString(TextR.string.notification_color_default),
        value.isBlank(),
        { change(if (it) "" else "#8BE9FD") },
        Modifier.testTag("colorDefault"),
    )
    if (value.isNotBlank())
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ColorSwatches(value, change)
            OutlinedTextField(
                value,
                change,
                label = { Text(context.getString(TextR.string.label_color_code)) },
                singleLine = true,
                isError = !value.matches(colorPattern),
                modifier = Modifier.fillMaxWidth().testTag("colorCode"),
                shape = RoundedCornerShape(14.dp),
            )
        }
}

private fun entrySummary(
    entry: Entry,
    apps: List<io.luftaquila.hometty.platform.DeviceAccess.App>,
    context: android.content.Context,
): String =
    when (entry.kind) {
        // The rule is named after its app: say whether it is off and which package it is.
        "notificationRule" -> {
            val packageName = entry.fields["package"].orEmpty()
            listOfNotNull(
                    context.getString(TextR.string.notification_rule_hidden)
                        .takeIf { entry.fields["enabled"] == "false" },
                    context.getString(TextR.string.apps_unavailable)
                        .takeIf { apps.none { it.packageName == packageName } },
                    packageName,
                )
                .joinToString(" · ")
        }
        "alias" -> entry.fields["command"].orEmpty()
        "note" -> entry.fields["text"].orEmpty()
        "rss" -> entry.fields["url"].orEmpty()
        else -> ""
    }.ifBlank { context.getString(TextR.string.editor_no_details) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryDialog(
    spec: CollectionSpec,
    entry: Entry?,
    model: HomeViewModel,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val apps by model.apps.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    var name by rememberSaveable(entry?.id) { mutableStateOf(entry?.name.orEmpty()) }
    val initialFields =
        remember(entry?.id) {
            spec.fields.associate { it.key to (entry?.fields?.get(it.key) ?: it.default) }
        }
    var values by rememberSaveable(entry?.id) { mutableStateOf(initialFields) }
    fun update(key: String, value: String) {
        values = values + (key to value)
    }
    var localError by remember { mutableStateOf<String?>(null) }
    var testText by rememberSaveable { mutableStateOf("") }
    var showTest by rememberSaveable { mutableStateOf(false) }
    var picker by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    fun dismiss() {
        if (name != entry?.name.orEmpty() || values != initialFields) discard = true
        else onDismiss()
    }
    Dialog(
        onDismissRequest = { if (!saving) dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column {
                Header(context.text(spec.title), { if (!saving) dismiss() }) {
                    TextButton(
                        onClick = {
                            val validation =
                                runCatching {
                                        io.luftaquila.hometty.core.Collections.validate(spec.kind, name, values)
                                    }
                                    .exceptionOrNull()
                            if (validation != null) localError = context.message(validation)
                            else {
                                saving = true
                                model.saveEntry(
                                    spec.kind,
                                    name,
                                    values,
                                    {
                                        saving = false
                                        onDismiss()
                                    },
                                    {
                                        saving = false
                                        localError = it
                                    },
                                )
                            }
                        },
                        enabled = !saving,
                        modifier = Modifier.testTag("entrySave"),
                    ) {
                        Text(context.getString(TextR.string.label_save))
                    }
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 32.dp)) {
                    item {
                        OutlinedTextField(
                            name,
                            { name = it },
                            label = { Text(context.getString(TextR.string.label_name)) },
                            singleLine = true,
                            enabled = entry == null,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 12.dp)
                                    .testTag("entryName"),
                            shape = RoundedCornerShape(14.dp),
                        )
                    }
                    if (spec.kind == "notificationRule" && values["enabled"] != "false")
                        item {
                            NotificationPreview(
                                settings,
                                apps.firstOrNull { it.packageName == values["package"] }?.label
                                    ?: context.getString(TextR.string.notification_sample_app),
                                values,
                            )
                        }
                    items(spec.fields, key = { it.key }) { field ->
                        when {
                            field.kind == SettingKind.COLOR ->
                                ColorField(context.text(field.label), values[field.key].orEmpty()) {
                                    update(field.key, it)
                                }
                            field.kind == SettingKind.BOOLEAN ->
                                TogglePreference(
                                    context.text(field.label),
                                    values[field.key].toBoolean(),
                                    { update(field.key, it.toString()) },
                                )
                            field.key == "package" -> {
                                val chosen = values[field.key].orEmpty()
                                PreferenceRow(
                                    context.getString(TextR.string.apps_choose_one),
                                    when {
                                        chosen.isBlank() ->
                                            context.getString(TextR.string.apps_none_selected)
                                        else ->
                                            apps.firstOrNull { it.packageName == chosen }?.label
                                                ?: context.getString(TextR.string.apps_unavailable)
                                    },
                                    Modifier.testTag("entryApps"),
                                    glyph = SettingsGlyph.Apps,
                                    onClick = { picker = field.key },
                                )
                            }
                            else -> {
                                var input by rememberCursorText(field.key, values[field.key].orEmpty())
                                OutlinedTextField(
                                    input,
                                    {
                                        input = it
                                        update(field.key, it.text)
                                    },
                                    label = { Text(context.text(field.label)) },
                                    // A blank rule format uses the format setting.
                                    placeholder =
                                        if (spec.kind == "notificationRule" && field.key == "format")
                                            { { Text(settings[Settings.notificationFormat]) } }
                                        else null,
                                    minLines =
                                        if (field.key in setOf("command", "text", "format")) 3
                                        else 1,
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .padding(horizontal = 20.dp, vertical = 8.dp),
                                    shape = RoundedCornerShape(14.dp),
                                )
                                PlaceholderList(
                                    Placeholders.forField(spec.kind, field.key),
                                    Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                                ) {
                                    input = input.inserting(it)
                                    update(field.key, input.text)
                                }
                            }
                        }
                    }
                    if (spec.kind in setOf("notificationRule", "rss"))
                        item {
                            TogglePreference(
                                context.getString(TextR.string.editor_test_pattern),
                                showTest,
                                { showTest = it },
                            )
                            if (showTest) {
                                OutlinedTextField(
                                    testText,
                                    { testText = it },
                                    label = {
                                        Text(context.getString(TextR.string.label_test_text))
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                                )
                                if (testText.isNotBlank())
                                    Text(
                                        runCatching {
                                                val pattern =
                                                    values["pattern"] ?: values["include"].orEmpty()
                                                if (pattern.isBlank())
                                                    context.getString(TextR.string.label_no_pattern)
                                                else
                                                    Patterns.find(pattern, testText)
                                                        .joinToString("\n")
                                                        .ifEmpty {
                                                            context.getString(
                                                                TextR.string.label_no_matches
                                                            )
                                                        }
                                            }
                                            .getOrElse { context.message(it) },
                                        Modifier.padding(20.dp),
                                    )
                            }
                        }
                    if (localError != null)
                        item {
                            Text(
                                localError.orEmpty(),
                                Modifier.padding(20.dp),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                }
            }
        }
    }
    picker?.let { key ->
        AppPicker(apps, values[key]?.takeIf { it.isNotBlank() }, onDismiss = { picker = null }) {
            selected ->
            update(key, selected)
            if (name.isBlank()) name = apps.firstOrNull { it.packageName == selected }?.label.orEmpty()
            picker = null
        }
    }
    if (discard)
        AlertDialog(
            onDismissRequest = { discard = false },
            title = { Text(context.getString(TextR.string.label_discard_changes)) },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(context.getString(TextR.string.label_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { discard = false }) {
                    Text(context.getString(TextR.string.label_keep_editing))
                }
            },
        )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppsScreen(model: HomeViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val apps by model.apps.collectAsStateWithLifecycle()
    val entries by model.entries.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<io.luftaquila.hometty.platform.DeviceAccess.App?>(null) }
    var jumping by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        val collator = remember { java.text.Collator.getInstance() }
        val visible =
            apps.filter { SearchIndex.score(query, it.label + " " + it.packageName) > 0 }
        fun fields(app: io.luftaquila.hometty.platform.DeviceAccess.App) =
            entries.firstOrNull { it.kind == "app" && it.name == app.id }?.fields.orEmpty()
        // Favorites first, then A-Z, ㄱ-ㅎ and other scripts, each sorted by the locale's collation.
        val sections =
            buildList {
                    val favorites = visible.filter { fields(it)["favorite"] == "true" }
                    if (favorites.isNotEmpty())
                        add(context.getString(TextR.string.apps_favorite) to favorites)
                    addAll(AppNames.sections(visible, { it.label }, collator))
                }
                .map { (key, list) -> key to list.sortedWith { a, b -> collator.compare(a.label, b.label) } }
        val headers = sections.runningFold(0) { at, (_, list) -> at + 1 + list.size }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        // Sections are reached through a grid of them, from the header button or a section
        // title, so nothing sits on the screen edges where the back gesture starts.
        val labels = sections.map { (key, _) -> if (key.length == 1) key else "★" }
        Header(context.getString(TextR.string.label_apps), model::back) {
            if (labels.size > 1)
                TextButton(onClick = { jumping = true }, Modifier.testTag("appsJump")) {
                    Text(context.getString(TextR.string.apps_jump))
                }
        }
        SettingsSearch(
            query,
            { query = it },
            label = context.getString(TextR.string.label_search_apps),
        )
        Box(Modifier.weight(1f)) {
            LazyColumn(
                Modifier.fillMaxSize().testTag("appsList"),
                state = listState,
                contentPadding = PaddingValues(end = 8.dp, bottom = 24.dp),
            ) {
                sections.forEach { (key, list) ->
                    stickyHeader(key = "header:$key") {
                        Text(
                            key,
                            Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background)
                                .clickable(enabled = labels.size > 1) { jumping = true }
                                .padding(horizontal = 20.dp, vertical = 6.dp)
                                .testTag("appsHeader:$key"),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    items(list, key = { "$key:${it.id}" }) { app ->
                        val fields = fields(app)
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { selected = app }
                                .padding(start = 20.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            AppIcon(app)
                            Column(Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                val badges = buildList {
                                    if (fields["hidden"] == "true")
                                        add(context.getString(TextR.string.apps_hidden))
                                    if (app.user != android.os.Process.myUserHandle())
                                        add(context.getString(TextR.string.apps_profile))
                                }
                                if (badges.isNotEmpty())
                                    Text(
                                        badges.joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                            }
                            TextButton(
                                onClick = {
                                    model.runFromSettings("apps launch ${Shell.quote(app.id)}")
                                }
                            ) {
                                Text(context.getString(TextR.string.label_open))
                            }
                        }
                    }
                }
            }
        }
        if (jumping)
            SectionJump(
                labels,
                pick = { index ->
                    jumping = false
                    scope.launch { listState.scrollToItem(headers[index]) }
                },
                dismiss = { jumping = false },
            )
    }
    selected?.let { app ->
        val fields = entries.firstOrNull { it.kind == "app" && it.name == app.id }?.fields.orEmpty()
        AlertDialog(
            onDismissRequest = { selected = null },
            icon = { AppIcon(app) },
            title = { Text(app.label) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val launches = fields["launches"]?.toIntOrNull() ?: 0
                    Text(
                        context.resources.getQuantityString(TextR.plurals.apps_launches, launches, launches),
                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp).testTag("appLaunches"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TogglePreference(
                        context.getString(TextR.string.apps_favorite),
                        fields["favorite"] == "true",
                        { value ->
                            model.setAppFlag(app.id, "favorite", value)
                        },
                    )
                    TogglePreference(
                        context.getString(TextR.string.apps_hidden),
                        fields["hidden"] == "true",
                        { value ->
                            model.setAppFlag(app.id, "hidden", value)
                        },
                    )
                    SettingsLink(context.getString(TextR.string.label_info)) {
                        model.device.launch(
                            android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:${app.packageName}"),
                            )
                        )
                        selected = null
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        selected = null
                        model.runFromSettings("apps launch ${Shell.quote(app.id)}")
                    }
                ) {
                    Text(context.getString(TextR.string.label_open))
                }
            },
            dismissButton = {
                TextButton(onClick = { selected = null }) {
                    Text(context.getString(TextR.string.label_cancel))
                }
            },
        )
    }
}

/** The app list's sections as a grid; picking one jumps to it. */
@Composable
private fun SectionJump(labels: List<String>, pick: (Int) -> Unit, dismiss: () -> Unit) {
    Dialog(onDismissRequest = dismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            LazyVerticalGrid(
                GridCells.Adaptive(52.dp),
                Modifier.padding(16.dp).testTag("appsJumpGrid"),
            ) {
                itemsIndexed(labels) { index, label ->
                    Box(
                        Modifier.aspectRatio(1f)
                            .clip(CircleShape)
                            .clickable { pick(index) }
                            .testTag("appsJump:$label"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
