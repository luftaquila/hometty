package io.luftaquila.hometty.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.luftaquila.hometty.core.*

/** Settings colors use trailing alpha (#RRGGBBAA), unlike Android's #AARRGGBB. */
internal fun statusColor(value: String): Color =
    color(value.take(7)).copy(alpha = if (value.length == 9) value.takeLast(2).toInt(16) / 255f else 1f)

/** Shared by the live terminal and the editor, including width and overflow behavior. */
@Composable
fun StatusLinesView(
    rows: List<StatusRow>,
    appearance: StatusAppearance,
    settings: SettingsSnapshot,
    font: FontFamily,
    modifier: Modifier = Modifier,
) {
    if (rows.isEmpty()) return
    val foreground = statusColor(settings[Settings.foreground])
    Column(
        modifier.fillMaxWidth()
            .background(appearance.background?.let(::statusColor) ?: Color.Transparent)
            .clearAndSetSemantics { text = AnnotatedString(rows.joinToString("\n") { it.plainText }) }
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = appearance.padding.dp),
            verticalArrangement = Arrangement.spacedBy(appearance.lineSpacing.dp),
        ) {
            rows.forEach { row ->
                val style = appearance.style(row.sourceLine)
                val size = (style.fontSize ?: settings[Settings.fontSize]).sp
                val ink = style.color?.let(::statusColor) ?: foreground.copy(alpha = foreground.alpha * .8f)
                val wrap = style.overflow == "wrap"
                val overflow = if (style.overflow == "ellipsis") TextOverflow.Ellipsis else TextOverflow.Clip
                val align = when (style.alignment) {
                    "center" -> TextAlign.Center
                    "right" -> TextAlign.Right
                    else -> TextAlign.Left
                }
                @Composable
                fun part(value: String, spans: List<StatusColorSpan>, modifier: Modifier, alignment: TextAlign) {
                    val styled = AnnotatedString.Builder(value).apply {
                        spans.forEach { addStyle(SpanStyle(color = statusColor(it.color)), it.start, it.end) }
                    }.toAnnotatedString()
                    Text(
                        styled, modifier,
                        color = ink, fontFamily = font, fontSize = size,
                        lineHeight = size * 1.35f,
                        letterSpacing = 0.sp,
                        textAlign = alignment,
                        softWrap = wrap,
                        maxLines = if (wrap) Int.MAX_VALUE else 1,
                        overflow = overflow,
                    )
                }
                val right = row.right
                if (right == null) part(row.left, row.leftColors, Modifier.fillMaxWidth(), align)
                else Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    // Equal lanes prevent either user-defined field from pushing the other out.
                    part(row.left, row.leftColors, Modifier.weight(1f), TextAlign.Left)
                    part(right, row.rightColors, Modifier.weight(1f), TextAlign.Right)
                }
            }
        }
        if (appearance.divider) HorizontalDivider(color = foreground.copy(alpha = foreground.alpha * .16f))
    }
}
