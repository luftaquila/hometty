package io.luftaquila.hometty.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.luftaquila.hometty.core.Settings
import io.luftaquila.hometty.core.SettingsSnapshot
import io.luftaquila.hometty.core.Themes
import io.luftaquila.hometty.core.get
import io.luftaquila.hometty.resources.R as TextR
import io.luftaquila.hometty.ui.color
import kotlin.math.max
import kotlin.math.min

/** GUI text stays legible even when a custom terminal palette has low contrast. */
internal fun readable(foreground: Color, background: Color, minimum: Float = 4.5f): Color {
    fun contrast(a: Color, b: Color): Float =
        (max(a.luminance(), b.luminance()) + .05f) / (min(a.luminance(), b.luminance()) + .05f)
    val opaque = foreground.copy(alpha = 1f)
    if (contrast(opaque, background) >= minimum) return opaque
    return if (contrast(Color.White, background) >= contrast(Color.Black, background)) Color.White
    else Color.Black
}

internal fun settingsColors(settings: SettingsSnapshot): ColorScheme {
    val background = color(settings[Settings.background]).copy(alpha = 1f)
    val dark = background.luminance() < .4f
    val foreground = readable(color(settings[Settings.foreground]), background)
    val surface = lerp(background, if (dark) Color.White else Color.Black, .055f)
    val raised = lerp(background, if (dark) Color.White else Color.Black, .095f)
    val accent = readable(color(Themes.accent(settings)), raised)
    val muted = readable(lerp(foreground, background, .28f), raised)
    val container = lerp(background, accent, .15f)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = readable(background, accent),
        primaryContainer = container,
        onPrimaryContainer = readable(accent, container),
        secondary = accent,
        onSecondary = readable(background, accent),
        secondaryContainer = container,
        onSecondaryContainer = readable(accent, container),
        tertiary = accent,
        onTertiary = readable(background, accent),
        background = background,
        onBackground = foreground,
        surface = background,
        onSurface = foreground,
        surfaceVariant = raised,
        onSurfaceVariant = muted,
        surfaceContainerLowest = background,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = raised,
        surfaceContainerHighest = raised,
        surfaceTint = Color.Transparent,
        outline = readable(lerp(foreground, background, .5f), surface, 3f),
        outlineVariant = lerp(foreground, background, .85f),
        error = if (dark) Color(0xFFFF9AAB) else Color(0xFFBA1A36),
        errorContainer = if (dark) Color(0xFF481A28) else Color(0xFFFFD9E0),
        onErrorContainer = if (dark) Color(0xFFFFD9E0) else Color(0xFF481A28),
    )
}

enum class SettingsGlyph {
    Display,
    Apps,
    Tools,
    System,
    Data,
    Search,
    Back,
    Next,
    Close,
    Check,
    Minus,
    Plus,
    History,
    Shield,
    More,
    Alias,
}

@Composable
fun SettingsIcon(
    glyph: SettingsGlyph,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Canvas(modifier.size(22.dp)) {
        val u = size.minDimension / 24f
        val stroke = 1.65f * u
        fun line(x: Float, y: Float, x2: Float, y2: Float) =
            drawLine(tint, Offset(x * u, y * u), Offset(x2 * u, y2 * u), stroke, StrokeCap.Round)
        fun box(x: Float, y: Float, w: Float, h: Float, r: Float = 2f) =
            drawRoundRect(
                tint,
                Offset(x * u, y * u),
                Size(w * u, h * u),
                CornerRadius(r * u),
                style = Stroke(stroke),
            )
        when (glyph) {
            SettingsGlyph.Display -> {
                box(3f, 4f, 18f, 13f)
                line(8f, 21f, 16f, 21f)
                line(12f, 17f, 12f, 21f)
            }
            SettingsGlyph.Apps -> {
                for (x in listOf(3f, 14f)) for (y in listOf(3f, 14f)) box(x, y, 7f, 7f)
            }
            SettingsGlyph.Tools -> {
                line(4f, 6f, 9f, 11f)
                line(9f, 11f, 4f, 16f)
                line(13f, 17f, 20f, 17f)
            }
            SettingsGlyph.System -> {
                for ((y, x) in listOf(5f to 8f, 12f to 16f, 19f to 8f)) {
                    line(3f, y, 21f, y)
                    drawCircle(tint, 3f * u, Offset(x * u, y * u))
                }
            }
            SettingsGlyph.Data -> {
                box(4f, 4f, 16f, 16f, 3f)
                line(8f, 9f, 16f, 9f)
                line(8f, 14f, 13f, 14f)
            }
            SettingsGlyph.Search -> {
                drawCircle(tint, 7f * u, Offset(10f * u, 10f * u), style = Stroke(stroke))
                line(15f, 15f, 21f, 21f)
            }
            SettingsGlyph.Back -> {
                line(14f, 5f, 7f, 12f)
                line(7f, 12f, 14f, 19f)
            }
            SettingsGlyph.Next -> {
                line(9f, 6f, 15f, 12f)
                line(15f, 12f, 9f, 18f)
            }
            SettingsGlyph.Close -> {
                line(6f, 6f, 18f, 18f)
                line(6f, 18f, 18f, 6f)
            }
            SettingsGlyph.Check -> {
                line(5f, 12f, 10f, 17f)
                line(10f, 17f, 20f, 7f)
            }
            SettingsGlyph.History -> {
                drawCircle(tint, 9f * u, Offset(12f * u, 12f * u), style = Stroke(stroke))
                line(12f, 7f, 12f, 12f)
                line(12f, 12f, 16f, 14f)
            }
            SettingsGlyph.Shield -> {
                line(12f, 3f, 20f, 6f)
                line(12f, 3f, 4f, 6f)
                line(4f, 6f, 4f, 12f)
                line(20f, 6f, 20f, 12f)
                line(4f, 12f, 12f, 21f)
                line(20f, 12f, 12f, 21f)
                line(9f, 12f, 11f, 14f)
                line(11f, 14f, 15f, 10f)
            }
            // One name pointing at another: a short stroke, then an arrow.
            SettingsGlyph.Alias -> {
                line(3f, 12f, 7f, 12f)
                line(10f, 12f, 20f, 12f)
                line(15f, 7f, 20f, 12f)
                line(20f, 12f, 15f, 17f)
            }
            SettingsGlyph.More ->
                for (y in listOf(5f, 12f, 19f)) drawCircle(tint, 1.8f * u, Offset(12f * u, y * u))
            SettingsGlyph.Minus -> line(5f, 12f, 19f, 12f)
            SettingsGlyph.Plus -> {
                line(5f, 12f, 19f, 12f)
                line(12f, 5f, 12f, 19f)
            }
        }
    }
}

@Composable
fun SettingsSearch(
    query: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = LocalContext.current.getString(TextR.string.label_search),
) {
    val context = LocalContext.current
    TextField(
        query,
        onChange,
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        placeholder = { Text(label) },
        singleLine = true,
        leadingIcon = { SettingsIcon(SettingsGlyph.Search) },
        trailingIcon = {
            if (query.isNotEmpty())
                IconButton(onClick = { onChange("") }) {
                    SettingsIcon(
                        SettingsGlyph.Close,
                        Modifier.clearDescription(
                            context.getString(TextR.string.settings_search_clear)
                        ),
                    )
                }
        },
        shape = RoundedCornerShape(18.dp),
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
    )
}

private fun Modifier.clearDescription(value: String) = semantics { contentDescription = value }

@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
fun PreferenceRow(
    title: String,
    summary: String? = null,
    modifier: Modifier = Modifier,
    glyph: SettingsGlyph? = null,
    onClick: (() -> Unit)? = null,
    value: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 64.dp)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (glyph != null)
                Box(
                    Modifier.size(42.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    SettingsIcon(glyph, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (!summary.isNullOrBlank())
                    Text(
                        summary,
                        // Lines break between words (Korean included) and stay balanced, so a
                        // description never ends with one stray syllable.
                        style =
                            MaterialTheme.typography.bodyMedium.copy(
                                lineBreak =
                                    androidx.compose.ui.text.style.LineBreak.Paragraph.copy(
                                        wordBreak =
                                            androidx.compose.ui.text.style.LineBreak.WordBreak.Phrase
                                    )
                            ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
            if (trailing != null) trailing()
            else if (onClick != null)
                SettingsIcon(SettingsGlyph.Next, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (value != null) PreferenceValue(value)
    }
}

/** Long values use the row's full width; the editor keeps the complete, editable text. */
@Composable
fun PreferenceValue(value: String, maxLines: Int = 5, monospace: Boolean = true) {
    Surface(
        Modifier.fillMaxWidth().testTag("preferenceValue"),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            value,
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            color = MaterialTheme.colorScheme.primary,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The row owns the single switch semantic/action; the drawing has no extra touch target. */
@Composable
fun TogglePreference(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
) {
    PreferenceRow(
        title,
        summary,
        modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        trailing = { CompactSwitch(checked) },
    )
}

@Composable
fun CompactSwitch(checked: Boolean) {
    val progress by animateFloatAsState(if (checked) 1f else 0f, label = "switch")
    val scheme = MaterialTheme.colorScheme
    Canvas(Modifier.size(width = 44.dp, height = 28.dp)) {
        val track = lerp(scheme.surfaceContainerHighest, scheme.primary, progress)
        val thumb = lerp(scheme.onSurfaceVariant, scheme.onPrimary, progress)
        drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
        if (progress < 1f)
            drawRoundRect(
                scheme.outline.copy(alpha = 1f - progress),
                cornerRadius = CornerRadius(size.height / 2),
                style = Stroke(1.dp.toPx()),
            )
        val center = Offset(14.dp.toPx() + 16.dp.toPx() * progress, size.height / 2)
        drawCircle(thumb, 10.dp.toPx(), center)
        if (progress > .5f) {
            val ink = scheme.primary.copy(alpha = (progress - .5f) * 2)
            drawLine(
                ink,
                center + Offset(-4.dp.toPx(), 0f),
                center + Offset(-1.dp.toPx(), 3.dp.toPx()),
                2.dp.toPx(),
                StrokeCap.Round,
            )
            drawLine(
                ink,
                center + Offset(-1.dp.toPx(), 3.dp.toPx()),
                center + Offset(4.dp.toPx(), -3.dp.toPx()),
                2.dp.toPx(),
                StrokeCap.Round,
            )
        }
    }
}

@Composable
fun SettingsLink(
    label: String,
    summary: String? = null,
    glyph: SettingsGlyph? = null,
    onClick: () -> Unit,
) {
    PreferenceRow(label, summary, glyph = glyph, onClick = onClick)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreferenceSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    modifier: Modifier = Modifier,
) {
    // Material3 1.5 takes a custom thumb only with SliderState; the caller still owns the value.
    val state = remember(steps, range) { SliderState(value, steps, range) }
    SideEffect { if (state.value != value) state.value = value }
    fun change(next: Float) {
        state.value = next.coerceIn(range)
        onValueChange(state.value)
    }
    val accent = MaterialTheme.colorScheme.primary
    val colors =
        SliderDefaults.colors(
            thumbColor = accent,
            activeTrackColor = accent,
            inactiveTrackColor = accent.copy(alpha = .3f),
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
        )
    val buttonColors =
        IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = accent.copy(alpha = .16f),
            contentColor = accent,
            disabledContainerColor = accent.copy(alpha = .06f),
            disabledContentColor = accent.copy(alpha = .3f),
        )
    // The buttons move by one slider step, for exact values without dragging.
    val step = (range.endInclusive - range.start) / (steps + 1)
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(
            onClick = { change(value - step) },
            modifier = Modifier.size(36.dp).testTag("sliderDecrease"),
            enabled = value > range.start,
            colors = buttonColors,
        ) {
            SettingsIcon(
                SettingsGlyph.Minus,
                Modifier.size(18.dp).clearDescription(stringResource(TextR.string.label_decrease)),
            )
        }
        Slider(
            state,
            onValueChange = ::change,
            modifier = Modifier.weight(1f),
            colors = colors,
            thumb = {
                Surface(
                    Modifier.size(22.dp),
                    shape = RoundedCornerShape(50),
                    color = accent,
                    shadowElevation = 2.dp,
                ) {}
            },
            // A thin track that runs under the thumb: the default leaves a gap around the thumb
            // that swallows the filled part near the start, so small values showed no track.
            track = {
                SliderDefaults.Track(
                    it,
                    Modifier.height(6.dp),
                    colors = colors,
                    drawStopIndicator = null,
                    thumbTrackGapSize = 0.dp,
                    trackInsideCornerSize = 3.dp,
                )
            },
        )
        FilledTonalIconButton(
            onClick = { change(value + step) },
            modifier = Modifier.size(36.dp).testTag("sliderIncrease"),
            enabled = value < range.endInclusive,
            colors = buttonColors,
        ) {
            SettingsIcon(
                SettingsGlyph.Plus,
                Modifier.size(18.dp).clearDescription(stringResource(TextR.string.label_increase)),
            )
        }
    }
}
