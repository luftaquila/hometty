package io.luftaquila.hometty

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.luftaquila.hometty.core.Fonts
import io.luftaquila.hometty.resources.R as TextR
import io.luftaquila.hometty.resources.choiceLabel
import io.luftaquila.hometty.core.Settings
import io.luftaquila.hometty.core.get

/**
 * Font and text size, previewed before saving like the theme screen. The size control sits above
 * the preview, which grows with the size, so dragging never moves the slider under the finger.
 */
@Composable
fun FontScreen(model: HomeViewModel, fonts: FontViewModel) {
    val context = LocalContext.current
    val settings by model.settings.collectAsStateWithLifecycle()
    val fontRevision by fonts.revision.collectAsStateWithLifecycle()
    var font by rememberSaveable { mutableStateOf(settings[Settings.font]) }
    var fontSize by rememberSaveable { mutableIntStateOf(settings[Settings.fontSize]) }
    val imported = settings[Settings.customFont].isNotBlank()
    LaunchedEffect(imported) { if (!imported && font == Fonts.CUSTOM) font = Fonts.DEFAULT }
    val changes = mapOf(Settings.font.key to font, Settings.fontSize.key to fontSize.toString())
    val draft = settings.copy(values = settings.values + changes)
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { fonts.import(it) { font = Fonts.CUSTOM } }
        }
    val choices = Fonts.bundled + Fonts.SYSTEM + listOfNotNull(Fonts.CUSTOM.takeIf { imported })
    Column(Modifier.fillMaxSize().testTag("fontScreen")) {
        Header(context.getString(TextR.string.label_font), model::back)
        Row(
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val size = Settings.fontSize.definition
            PreferenceSlider(
                fontSize.toFloat(),
                { fontSize = it.toInt() },
                range = size.min!!.toFloat()..size.max!!.toFloat(),
                steps = size.max!! - size.min!! - 1,
                modifier = Modifier.weight(1f).testTag("fontSize"),
            )
            Text(
                context.getString(TextR.string.settings_font_size, fontSize),
                Modifier.padding(start = 16.dp),
            )
        }
        key(fontRevision) { TerminalPreview(draft, Modifier.padding(horizontal = 20.dp)) }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { SectionLabel(context.getString(TextR.string.font_section_fonts)) }
            items(choices, key = { "$it:$fontRevision" }) { id ->
                FontItem(id, selected = font == id) { font = id }
            }
            item {
                PreferenceRow(
                    context.getString(TextR.string.font_import),
                    context.getString(TextR.string.font_import_hint),
                    Modifier.testTag("fontImport"),
                    onClick = { picker.launch(arrayOf("*/*")) },
                    trailing =
                        if (imported) {
                            {
                                TextButton(onClick = fonts::remove) {
                                    Text(context.getString(TextR.string.label_remove_font))
                                }
                            }
                        } else null,
                )
                Text(
                    context.getString(TextR.string.font_fallback_note),
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SaveDraftRow(model, changes, "fontSave")
    }
}

/** A font in the list: its name drawn in the font itself and a short description. */
@Composable
private fun FontItem(id: String, selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val typeface = TerminalFonts.typeface(context, id)
    val family = remember(typeface) { FontFamily(typeface) }
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
            )
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .testTag("font:$id")
            .heightIn(min = 64.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                context.choiceLabel(id),
                fontFamily = family,
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color =
                    if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                    else Color.Unspecified,
            )
            Text(
                fontDescription(id),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected)
            SettingsIcon(
                SettingsGlyph.Check,
                Modifier.size(20.dp),
                MaterialTheme.colorScheme.primary,
            )
    }
}

@Composable
private fun fontDescription(id: String): String {
    val context = LocalContext.current
    return when (id) {
        "jetendard" -> context.getString(TextR.string.font_desc_jetendard)
        "d2coding" -> context.getString(TextR.string.font_desc_d2coding)
        "jetbrains_mono" -> "JetBrains"
        "fira_code" -> context.getString(TextR.string.font_desc_fira)
        "cascadia_code" -> "Microsoft"
        "source_code_pro" -> "Adobe"
        "ibm_plex_mono" -> "IBM"
        "hack" -> "Source Foundry"
        "iosevka" -> context.getString(TextR.string.font_desc_iosevka)
        "0xproto" -> context.getString(TextR.string.font_desc_0xproto)
        Fonts.SYSTEM -> context.getString(TextR.string.font_desc_system)
        else -> context.getString(TextR.string.font_desc_custom)
    }
}
