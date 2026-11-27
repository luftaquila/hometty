package io.luftaquila.hometty.ui.settings

import io.luftaquila.hometty.ui.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.luftaquila.hometty.core.*
import io.luftaquila.hometty.resources.R as TextR
import io.luftaquila.hometty.resources.message
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest

/** Draft text and appearance are saved together; cancel never changes the terminal. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@Composable
fun StatusLinesDialog(
    model: HomeViewModel,
    settings: SettingsSnapshot,
    dismiss: () -> Unit,
) {
    val context = LocalContext.current
    var format by rememberSaveable { mutableStateOf(settings[Settings.statusLines]) }
    var input by rememberCursorText("statusFormat", format)
    var encoded by rememberSaveable { mutableStateOf(settings[Settings.statusAppearance]) }
    val appearance = remember(encoded) { StatusAppearance.decodeDraft(encoded) }
    var lineOnly by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val sourceLine = format.take(input.selection.start.coerceIn(0, format.length)).count { it == '\n' }
    val validation = runCatching {
        Settings.statusLines.definition.validate(format)
        Settings.statusAppearance.definition.validate(encoded)
    }.exceptionOrNull()?.let(context::message)
    var lastValid by remember { mutableStateOf(StatusAppearance.decode(settings[Settings.statusAppearance])) }
    LaunchedEffect(encoded, validation) { if (validation == null) lastValid = appearance }
    // Let typing settle before querying services; clock/device updates still continue while idle.
    val previewFlow = remember(model) {
        snapshotFlow { format }.debounce(150).flatMapLatest(model::statusPreview)
    }
    val rows by previewFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val typeface = remember(settings[Settings.font]) { TerminalFonts.typeface(context, settings[Settings.font]) }
    val font = remember(typeface) { FontFamily(typeface) }
    fun change(next: StatusAppearance) { encoded = next.encode() }
    val textStyle = if (lineOnly) appearance.lines[sourceLine] ?: StatusTextStyle() else appearance.text
    fun changeText(next: StatusTextStyle) {
        change(if (lineOnly) appearance.copy(lines = appearance.lines + (sourceLine to next))
            else appearance.copy(text = next))
    }
    Dialog(
        onDismissRequest = { if (!saving) dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                Modifier.fillMaxWidth().fillMaxHeight(.95f).padding(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(context.getString(TextR.string.label_set_status_lines), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    Text(context.getString(TextR.string.status_live_preview), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 150.dp)
                            .background(color(settings[Settings.background]), RoundedCornerShape(8.dp))
                            .verticalScroll(rememberScrollState())
                            .testTag("statusPreview"),
                    ) {
                        if (rows.isEmpty()) Text(
                            context.getString(if (format.isBlank()) TextR.string.status_preview_empty else TextR.string.status_preview_loading),
                            Modifier.padding(12.dp), color = color(settings[Settings.foreground]), fontFamily = font,
                        )
                        else StatusLinesView(rows, lastValid, settings, font)
                        Text(
                            promptPreview(settings), Modifier.padding(8.dp),
                            color = color(settings[Settings.inputColor]), fontFamily = font,
                            fontSize = settings[Settings.fontSize].sp,
                        )
                    }
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("statusEditorScroll"),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Spacer(Modifier.height(2.dp))
                        OutlinedTextField(
                            input,
                            { input = it; format = it.text },
                            Modifier.fillMaxWidth().testTag("statusFormat"),
                            label = { Text(context.getString(TextR.string.status_content)) },
                            supportingText = { Text(context.getString(TextR.string.status_content_help)) },
                            minLines = 3, maxLines = 8,
                        )
                        PlaceholderList(Placeholders.forSetting(Settings.statusLines.key)) {
                            input = input.inserting(it)
                            format = input.text
                        }
                        HorizontalDivider()
                        StatusFormatHelp { example ->
                            input = input.inserting(example)
                            format = input.text
                        }
                        HorizontalDivider()
                        Text(context.getString(TextR.string.label_set_status_appearance), style = MaterialTheme.typography.titleSmall)
                        StatusChoice(
                            context.getString(TextR.string.status_style_target),
                            if (lineOnly) "line" else "all",
                            listOf("all" to context.getString(TextR.string.status_all_lines),
                                "line" to context.getString(TextR.string.status_selected_line, sourceLine + 1)),
                            "statusStyleTarget",
                        ) { lineOnly = it == "line" }
                        if (lineOnly) Text(
                            context.getString(TextR.string.status_line_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        StatusTextControls(
                            textStyle, lineOnly,
                            (if (lineOnly) appearance.text.fontSize else null) ?: settings[Settings.fontSize],
                            ::changeText,
                        )
                        if (lineOnly) TextButton(
                            onClick = { change(appearance.copy(lines = appearance.lines - sourceLine)) },
                            modifier = Modifier.testTag("statusResetLine"),
                        ) { Text(context.getString(TextR.string.status_reset_line)) }
                        HorizontalDivider()
                        Text(context.getString(TextR.string.status_block_style), style = MaterialTheme.typography.titleSmall)
                        StatusColorField(
                            context.getString(TextR.string.status_background), appearance.background,
                            context.getString(TextR.string.status_transparent), "statusBackground",
                        ) { change(appearance.copy(background = it)) }
                        StatusSlider(context.getString(TextR.string.status_line_spacing), appearance.lineSpacing, 0..24, "dp") {
                            change(appearance.copy(lineSpacing = it))
                        }
                        StatusSlider(context.getString(TextR.string.status_padding), appearance.padding, 0..32, "dp") {
                            change(appearance.copy(padding = it))
                        }
                        StatusSwitch(context.getString(TextR.string.status_divider), appearance.divider, "statusDivider") {
                            change(appearance.copy(divider = it))
                        }
                        TextButton(
                            onClick = { change(StatusDefaults.appearance) },
                            modifier = Modifier.testTag("statusResetAppearance"),
                        ) { Text(context.getString(TextR.string.status_reset_appearance)) }
                        if (validation != null) Text(validation, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(4.dp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = dismiss, enabled = !saving, modifier = Modifier.testTag("statusCancel")) {
                            Text(context.getString(TextR.string.label_cancel))
                        }
                        TextButton(
                            onClick = {
                                saving = true
                                model.updateSettings(mapOf(Settings.statusLines.key to format, Settings.statusAppearance.key to encoded)) {
                                    saving = false
                                    if (it) dismiss()
                                }
                            },
                            enabled = validation == null && !saving,
                            modifier = Modifier.testTag("statusSave"),
                        ) { Text(context.getString(TextR.string.label_save)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusTextControls(style: StatusTextStyle, lineOnly: Boolean, terminalSize: Int, change: (StatusTextStyle) -> Unit) {
    val context = LocalContext.current
    val inherited = context.getString(if (lineOnly) TextR.string.status_inherit else TextR.string.status_follow_terminal)
    StatusSwitch(context.getString(TextR.string.status_inherit_size, inherited), style.fontSize == null, "statusInheritSize") {
        change(style.copy(fontSize = if (it) null else terminalSize))
    }
    style.fontSize?.let { size ->
        StatusSlider(context.getString(TextR.string.label_set_font_size), size, 10..36, "sp") {
            change(style.copy(fontSize = it))
        }
    }
    StatusColorField(context.getString(TextR.string.status_text_color), style.color, inherited, "statusTextColor") {
        change(style.copy(color = it))
    }
    StatusChoice(
        context.getString(TextR.string.status_alignment), style.alignment.orEmpty(),
        listOf("" to context.getString(if (lineOnly) TextR.string.status_inherit else TextR.string.status_default_left),
            "left" to context.getString(TextR.string.status_align_left),
            "center" to context.getString(TextR.string.status_align_center),
            "right" to context.getString(TextR.string.status_align_right)), "statusAlignment",
    ) { change(style.copy(alignment = it.ifEmpty { null })) }
    StatusChoice(
        context.getString(TextR.string.status_overflow), style.overflow.orEmpty(),
        listOf("" to context.getString(if (lineOnly) TextR.string.status_inherit else TextR.string.status_default_clip),
            "wrap" to context.getString(TextR.string.status_overflow_wrap),
            "ellipsis" to context.getString(TextR.string.status_overflow_ellipsis),
            "clip" to context.getString(TextR.string.status_overflow_clip)), "statusOverflow",
    ) { change(style.copy(overflow = it.ifEmpty { null })) }
}

@Composable
private fun StatusChoice(label: String, value: String, options: List<Pair<String, String>>, tag: String, change: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().testTag(tag)) {
                Text(options.first { it.first == value }.second)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (key, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = { change(key); open = false },
                        modifier = Modifier.testTag("$tag:$key"),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusColorField(label: String, value: String?, inherited: String, tag: String, change: (String?) -> Unit) {
    val context = LocalContext.current
    OutlinedTextField(
        value.orEmpty(), { change(it.ifEmpty { null }) },
        Modifier.fillMaxWidth().testTag(tag), label = { Text(label) },
        placeholder = { Text(inherited) }, singleLine = true,
        isError = value != null && !value.matches(colorPattern),
        supportingText = { Text(context.getString(TextR.string.status_color_help, inherited)) },
        trailingIcon = {
            if (value != null) IconButton(
                onClick = { change(null) },
                modifier = Modifier.testTag("$tag:clear").semantics {
                    contentDescription = context.getString(TextR.string.status_clear_color)
                },
            ) {
                SettingsIcon(SettingsGlyph.Close)
            }
        },
    )
}

@Composable
private fun StatusSlider(label: String, value: Int, range: IntRange, unit: String, change: (Int) -> Unit) {
    Column {
        Text("$label · $value $unit", style = MaterialTheme.typography.labelMedium)
        PreferenceSlider(value.toFloat(), { change(kotlin.math.round(it).toInt()) },
            range.first.toFloat()..range.last.toFloat(), steps = range.last - range.first - 1)
    }
}

@Composable
private fun StatusSwitch(label: String, checked: Boolean, tag: String, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, change, Modifier.testTag(tag).semantics { contentDescription = label })
    }
}
