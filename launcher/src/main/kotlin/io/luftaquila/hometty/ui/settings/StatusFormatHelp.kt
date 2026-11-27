package io.luftaquila.hometty.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.luftaquila.hometty.resources.R as TextR

/** Optional reference, separate from the always-visible content and appearance controls. */
@Composable
fun StatusFormatHelp(insert: (String) -> Unit) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().testTag("statusConditionsToggle")) {
            Text(context.getString(TextR.string.status_conditions), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(context.getString(if (expanded) TextR.string.status_help_hide else TextR.string.status_help_show))
            Spacer(Modifier.width(6.dp))
            SettingsIcon(SettingsGlyph.Next, Modifier.rotate(if (expanded) 90f else 0f))
        }
        if (expanded) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(TextR.string.status_help_examples, TextR.string.status_help_expressions, TextR.string.status_help_variables)
                    .forEachIndexed { index, label ->
                        FilterChip(selected = page == index, onClick = { page = index },
                            label = { Text(context.getString(label)) },
                            modifier = Modifier.weight(1f).testTag("statusHelpTab:$index"))
                    }
            }
            when (page) {
                0 -> {
                    Text(context.getString(TextR.string.status_condition_help), style = MaterialTheme.typography.bodySmall)
                    listOf(
                        Triple("statusInsertCondition", TextR.string.status_help_charging, "{if charging}+{else}-{end}"),
                        Triple("statusInsertComparison", TextR.string.status_help_compare, "{if batteryPercent < 20}LOW{end}"),
                        Triple("statusInsertColor", TextR.string.status_help_color, "{color:#FF5555}{battery}{/color}"),
                    ).forEach { (tag, label, example) ->
                        OutlinedButton(onClick = { insert(example) }, modifier = Modifier.fillMaxWidth().testTag(tag)) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(context.getString(label), style = MaterialTheme.typography.labelMedium)
                                Text(example, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                    Text(context.getString(TextR.string.status_help_nesting), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                1 -> {
                    listOf(
                        TextR.string.status_help_compare to "==  !=  <  <=  >  >=",
                        TextR.string.status_help_logic to "&&  ||  !  ( )",
                        TextR.string.status_help_math to "+  -  *  /  %",
                        TextR.string.status_help_text to "'Wi-Fi'  \"text\"",
                        TextR.string.status_help_functions to "exists(x), empty(x)\ncontains(s, x)\nstartsWith(s, x), endsWith(s, x)",
                    ).forEach { (label, syntax) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Text(context.getString(label), Modifier.width(76.dp), style = MaterialTheme.typography.labelMedium)
                            Text(syntax, Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(context.getString(TextR.string.status_help_truth), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    Text(context.getString(TextR.string.status_help_variable_hint), style = MaterialTheme.typography.bodySmall)
                    listOf(
                        TextR.string.status_help_battery to "batteryPercent · charging",
                        TextR.string.status_help_memory to "memoryFreeGiB · memoryTotalGiB\nmemoryUsedPercent\nmemoryFreeBytes · memoryTotalBytes",
                        TextR.string.status_help_storage to "storageFreeGiB · storageTotalGiB\nstorageUsedPercent\nstorageFreeBytes · storageTotalBytes",
                        TextR.string.status_help_network to "network · networkConnected · device",
                        TextR.string.status_help_weather to "weather · weatherAvailable\nweatherError · weatherLoading",
                        TextR.string.status_help_clock to "time · date\nyear · month · day · weekday\nhour · minute · second",
                    ).forEach { (label, names) ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(context.getString(label), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                            Text(names, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(context.getString(TextR.string.status_help_units), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
