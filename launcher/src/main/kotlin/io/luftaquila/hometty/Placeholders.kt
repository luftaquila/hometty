package io.luftaquila.hometty

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.luftaquila.hometty.core.Settings
import io.luftaquila.hometty.resources.R as TextR

/**
 * What a format setting or field can use, each with what it becomes. They show in the editor,
 * not in the description under the setting's name.
 */
object Placeholders {
    private val notification =
        listOf(
            "{app}" to TextR.string.placeholder_app,
            "{title}" to TextR.string.placeholder_title,
            "{text}" to TextR.string.placeholder_short_text,
            "{bigtext}" to TextR.string.placeholder_full_text,
            "{subtext}" to TextR.string.placeholder_subtext,
            "{package}" to TextR.string.placeholder_package,
        )

    private val prompt =
        listOf(
            "{user}" to TextR.string.placeholder_user,
            "{device}" to TextR.string.placeholder_device,
            "{cwd}" to TextR.string.placeholder_folder,
        )

    private val weather =
        listOf(
            "{city}" to TextR.string.placeholder_city,
            "{district}" to TextR.string.placeholder_district,
            "{temp}" to TextR.string.placeholder_temperature,
            "{unit}" to TextR.string.placeholder_unit,
            "{description}" to TextR.string.placeholder_conditions,
        )

    private val feed =
        listOf(
            "{title}" to TextR.string.placeholder_title,
            "{text}" to TextR.string.placeholder_text,
            "{url}" to TextR.string.placeholder_link,
            "{date}" to TextR.string.placeholder_date,
        )

    private val arguments =
        listOf(
            "\${1}" to TextR.string.placeholder_first_argument,
            "\${2}" to TextR.string.placeholder_second_argument,
            "\$@" to TextR.string.placeholder_all_arguments,
        )

    fun forSetting(key: String): List<Pair<String, Int>> =
        when (key) {
            Settings.notificationFormat.key -> notification
            Settings.prompt.key -> prompt
            Settings.weatherFormat.key -> weather
            else -> emptyList()
        }

    fun forField(kind: String, field: String): List<Pair<String, Int>> =
        when {
            kind == "notificationRule" && field == "format" -> notification
            kind == "rss" && field == "format" -> feed
            kind == "alias" && field == "command" -> arguments
            else -> emptyList()
        }
}

/**
 * The state of a text field showing [text] that keeps its cursor, so a placeholder goes in where
 * the cursor is. [text] changed elsewhere (a default restored, a swatch picked) resets it, with
 * the cursor at the end.
 */
@Composable
fun rememberCursorText(key: Any?, text: String): MutableState<TextFieldValue> {
    val state =
        rememberSaveable(key, stateSaver = TextFieldValue.Saver) {
            mutableStateOf(TextFieldValue(text, TextRange(text.length)))
        }
    if (state.value.text != text) state.value = TextFieldValue(text, TextRange(text.length))
    return state
}

/** This text with [token] in place of its selection (or at its cursor), the cursor after it. */
fun TextFieldValue.inserting(token: String): TextFieldValue {
    val start = selection.min.coerceIn(0, text.length)
    val end = selection.max.coerceIn(0, text.length)
    return TextFieldValue(text.replaceRange(start, end, token), TextRange(start + token.length))
}

/** [items] as rows of placeholder and meaning; tapping one passes it to [pick]. */
@Composable
fun PlaceholderList(items: List<Pair<String, Int>>, modifier: Modifier = Modifier, pick: (String) -> Unit) {
    if (items.isEmpty()) return
    val context = LocalContext.current
    Column(modifier.testTag("placeholders")) {
        Text(
            context.getString(TextR.string.placeholders_title),
            Modifier.padding(bottom = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        items.forEach { (token, meaning) ->
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { pick(token) }
                    .padding(vertical = 6.dp, horizontal = 4.dp)
                    .testTag("placeholder:$token"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(token, Modifier.width(120.dp), fontFamily = FontFamily.Monospace)
                Text(context.getString(meaning), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
