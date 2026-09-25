package io.luftaquila.hometty

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.luftaquila.hometty.core.SettingsSnapshot
import io.luftaquila.hometty.core.Themes
import io.luftaquila.hometty.resources.R as TextR
import io.luftaquila.hometty.resources.choiceLabel
import io.luftaquila.hometty.core.Settings
import io.luftaquila.hometty.core.get

@Composable
fun ThemeScreen(model: HomeViewModel) {
    val context = LocalContext.current
    val settings by model.settings.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf(Themes.selected(settings)) }
    var sampleSwitch by rememberSaveable { mutableStateOf(true) }
    val changes = selected?.let(Themes::values).orEmpty()
    val draft = settings.copy(values = settings.values + changes)
    LaunchedEffect(changes) { model.appearancePreview.value = changes }
    DisposableEffect(Unit) { onDispose { model.appearancePreview.value = null } }
    Column(Modifier.fillMaxSize().testTag("themePreview")) {
        Header(context.getString(TextR.string.theme_preview), model::back)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text(
                    context.getString(TextR.string.theme_preview_hint),
                    Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TerminalPreview(draft, Modifier.padding(horizontal = 20.dp))
                SectionLabel(context.getString(TextR.string.theme_sample_title))
                Surface(
                    Modifier.padding(horizontal = 20.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    TogglePreference(
                        context.getString(TextR.string.theme_sample_switch),
                        sampleSwitch,
                        { sampleSwitch = it },
                    )
                }
                SectionLabel(context.getString(TextR.string.label_theme_presets))
            }
            items(Themes.presets.keys.toList().chunked(2)) { row ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { name ->
                        val theme = Themes.presets.getValue(name)
                        val active = selected == name
                        Column(
                            Modifier.weight(1f)
                                .clip(RoundedCornerShape(18.dp))
                                .border(
                                    if (active) 2.dp else 1.dp,
                                    if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(18.dp),
                                )
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .selectable(
                                    active,
                                    role = Role.RadioButton,
                                    onClick = { selected = name },
                                )
                                .testTag("theme:$name")
                                .padding(14.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .height(42.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(color(theme.background))
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                // Output and prompt colors, then the six ANSI hues.
                                (listOf(theme.foreground, theme.input) +
                                        theme.palette.subList(1, 7))
                                    .forEach { value ->
                                        Box(
                                            Modifier.size(9.dp)
                                                .clip(RoundedCornerShape(5.dp))
                                                .background(color(value))
                                        )
                                    }
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(top = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    context.choiceLabel(name),
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                if (active)
                                    SettingsIcon(
                                        SettingsGlyph.Check,
                                        Modifier.size(18.dp),
                                        MaterialTheme.colorScheme.primary,
                                    )
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        SaveDraftRow(model, changes, "themeSave")
    }
}

/** Cancel, or save [changes] and go back; [saveTag] tags the save button for tests. */
@Composable
fun SaveDraftRow(model: HomeViewModel, changes: Map<String, String>, saveTag: String) {
    val context = LocalContext.current
    var saving by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = model::back, modifier = Modifier.weight(1f), enabled = !saving) {
            Text(context.getString(TextR.string.label_cancel))
        }
        Button(
            onClick = {
                saving = true
                model.updateSettings(changes) { saved ->
                    saving = false
                    if (saved) model.back()
                }
            },
            modifier = Modifier.weight(1f).testTag(saveTag),
            enabled = !saving,
        ) {
            Text(context.getString(TextR.string.label_save))
        }
    }
}

/** [settings]' prompt as the shell shows it in a home folder. */
fun promptPreview(settings: SettingsSnapshot) =
    settings[Settings.prompt]
        .replace("{user}", settings[Settings.username])
        .replace("{device}", settings[Settings.deviceName])
        .replace("{cwd}", "~")

/** Terminal look of [settings]: prompt and output in the terminal font, then the ANSI hues. */
@Composable
fun TerminalPreview(settings: SettingsSnapshot, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val typeface = TerminalFonts.typeface(context, settings[Settings.font])
    val family = remember(typeface) { FontFamily(typeface) }
    val size = settings[Settings.fontSize].sp
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .background(color(settings[Settings.background]))
            .padding(20.dp)
    ) {
        Text(
            promptPreview(settings),
            color = color(settings[Settings.inputColor]),
            fontFamily = family,
            fontSize = size,
        )
        Text(
            context.getString(TextR.string.label_config_settings_nhello_terminal_123),
            color = color(settings[Settings.foreground]),
            fontFamily = family,
            fontSize = size,
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Themes.palette(settings).subList(1, 7).forEach { value ->
                Box(
                    Modifier.size(20.dp, 12.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(color(value))
                )
            }
        }
    }
}
