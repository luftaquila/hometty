package io.luftaquila.hometty.ui

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.luftaquila.hometty.Route
import io.luftaquila.hometty.core.NotificationLines
import io.luftaquila.hometty.core.Settings
import io.luftaquila.hometty.core.SettingsSnapshot
import io.luftaquila.hometty.core.Themes
import io.luftaquila.hometty.core.get
import io.luftaquila.hometty.resources.R as TextR
import io.luftaquila.hometty.resources.text
import io.luftaquila.hometty.terminal.TerminalView
import io.luftaquila.hometty.ui.settings.AboutScreen
import io.luftaquila.hometty.ui.settings.BackupScreen
import io.luftaquila.hometty.ui.settings.DonateScreen
import io.luftaquila.hometty.ui.settings.FeatureModels
import io.luftaquila.hometty.ui.settings.FontScreen
import io.luftaquila.hometty.ui.settings.FontViewModel
import io.luftaquila.hometty.ui.settings.LicensesScreen
import io.luftaquila.hometty.ui.settings.SettingsGlyph
import io.luftaquila.hometty.ui.settings.SettingsIcon
import io.luftaquila.hometty.ui.settings.SettingsScreen
import io.luftaquila.hometty.ui.settings.StartScreen
import io.luftaquila.hometty.ui.settings.ThemeScreen
import io.luftaquila.hometty.ui.settings.settingsColors

fun color(value: String): Color = Color(android.graphics.Color.parseColor(value))

@Composable
fun HomettyApp(model: HomeViewModel, features: FeatureModels) {
    val textContext = androidx.compose.ui.platform.LocalContext.current
    val settings by model.settings.collectAsStateWithLifecycle()
    val route by model.route.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val ready by model.ready.collectAsStateWithLifecycle()
    val screens = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val savedScreens = remember { mutableSetOf<String>() }
    LaunchedEffect(route) {
        val kept = (model.backStack + route).map { it.path }.toSet()
        savedScreens.filter { it !in kept }.forEach(screens::removeState)
        savedScreens.retainAll(kept)
        savedScreens += route.path
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val preview by model.appearancePreview.collectAsStateWithLifecycle()
    val displayed =
        if (preview == null) settings
        else settings.copy(values = settings.values + preview.orEmpty())
    val scheme = settingsColors(displayed)
    val background =
        if (route == Route.Home) color(settings[Settings.background]) else scheme.background
    BackHandler(
        enabled = route != Route.Home || settings[Settings.backHistory]
    ) {
        when {
            route != Route.Home -> model.back()
            else -> if (model.session.value?.atPrompt?.value == true) model.history(1)
        }
    }
    LaunchedEffect(route) { if (route != Route.Home) keyboard?.hide() }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
            val alpha =
                if (route == Route.Home && settings[Settings.wallpaper])
                    settings[Settings.opacity] / 100f
                else 1f
            Column(
                Modifier.fillMaxSize()
                    .background(background.copy(alpha = alpha))
                    .safeDrawingPadding()
                    .imePadding()
            ) {
                if (error != null && route != Route.Home)
                    Surface(color = scheme.errorContainer) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                error.orEmpty(),
                                Modifier.weight(1f),
                                color = scheme.onErrorContainer,
                            )
                            TextButton(onClick = { model.dismissError() }) {
                                Text(textContext.getString(TextR.string.label_dismiss))
                            }
                        }
                    }
                Box(Modifier.weight(1f)) {
                    val starting = !ready && route == Route.Home
                    val homeShown = route == Route.Home && !starting
                    // The terminal stays in place under the other screens. Coming back then
                    // needs no new view, so the keyboard opens at once: keys tapped while it is
                    // still on its way land on the terminal and are lost.
                    if (ready) HomeScreen(model, features.fonts, settings, shown = homeShown)
                    if (!homeShown)
                        Box(
                            Modifier.fillMaxSize()
                                .then(
                                    if (starting) Modifier
                                    else Modifier.background(scheme.background)
                                )
                                // Touches stop here instead of reaching the hidden terminal.
                                .pointerInput(Unit) {
                                    awaitPointerEventScope { while (true) awaitPointerEvent() }
                                }
                        ) {
                            // Each screen keeps its saved state (scroll position, search) while
                            // back can return to it, so going back lands where you left.
                            screens.SaveableStateProvider(route.path) {
                                if (starting)
                                    Column(Modifier.padding(24.dp)) {
                                        Text(
                                            error
                                                ?: textContext.getString(TextR.string.shell_starting)
                                        )
                                        if (error == null)
                                            LinearProgressIndicator(Modifier.fillMaxWidth())
                                        else
                                            TextButton(onClick = { model.ensureTerminal() }) {
                                                Text(textContext.getString(TextR.string.label_retry))
                                            }
                                        TextButton(
                                            onClick = { model.navigate(Route.SettingsPage()) }
                                        ) {
                                            Text(textContext.getString(TextR.string.label_settings))
                                        }
                                    }
                                else
                                    when (val screen = route) {
                                        Route.Home -> {}
                                        Route.Start -> StartScreen(model)
                                        Route.Theme -> ThemeScreen(model)
                                        Route.Font -> FontScreen(model, features.fonts)
                                        is Route.SettingsPage ->
                                            BoxWithConstraints {
                                                if (maxWidth >= 840.dp)
                                                    Row(Modifier.fillMaxSize()) {
                                                        Box(Modifier.weight(0.4f)) {
                                                            SettingsScreen(model, "")
                                                        }
                                                        VerticalDivider()
                                                        Box(Modifier.weight(0.6f)) {
                                                            SettingsScreen(
                                                                model,
                                                                screen.page.ifEmpty { "appearance" },
                                                            )
                                                        }
                                                    }
                                                else SettingsScreen(model, screen.page)
                                            }
                                        is Route.Manage -> CollectionScreen(model, screen.kind)
                                        is Route.Apps -> AppsScreen(model)
                                        Route.Backup -> BackupScreen(model, features.backup)
                                        Route.About -> AboutScreen(model)
                                        Route.Licenses -> LicensesScreen(model)
                                        Route.Donate -> DonateScreen(model)
                                    }
                            }
                        }
                }
            }
        }
    }
}

@Composable
fun Header(title: String, back: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    val textContext = androidx.compose.ui.platform.LocalContext.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = back) {
            SettingsIcon(
                SettingsGlyph.Back,
                Modifier.semantics {
                    contentDescription = textContext.getString(TextR.string.label_back)
                },
            )
        }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        actions()
    }
}

/** The home terminal; while another screen covers it ([shown] false) it only keeps its view. */
@Composable
private fun HomeScreen(
    model: HomeViewModel,
    fonts: FontViewModel,
    settings: SettingsSnapshot,
    shown: Boolean,
) {
    val textContext = androidx.compose.ui.platform.LocalContext.current
    val session by model.session.collectAsStateWithLifecycle()
    val terminal = session
    LaunchedEffect(terminal) { if (terminal == null) model.ensureTerminal() }
    if (terminal == null) {
        if (!shown) return
        val failure by model.error.collectAsStateWithLifecycle()
        Column(Modifier.padding(16.dp)) {
            Text(
                failure ?: textContext.getString(TextR.string.shell_starting),
                fontFamily = FontFamily.Monospace,
            )
            // Always offered: a start that never finishes must not leave the screen stuck.
            TextButton(onClick = { model.ensureTerminal() }) {
                Text(textContext.getString(TextR.string.label_retry))
            }
            TextButton(onClick = { model.navigate(Route.SettingsPage()) }) {
                Text(textContext.getString(TextR.string.label_settings))
            }
        }
        return
    }
    val revision by terminal.revision.collectAsStateWithLifecycle()
    val exited by terminal.exit.collectAsStateWithLifecycle()
    var view by remember { mutableStateOf<TerminalView?>(null) }
    var control by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    val fontRevision by fonts.revision.collectAsStateWithLifecycle()
    val terminalFont =
        remember(settings[Settings.font], fontRevision) {
            TerminalFonts.typeface(textContext, settings[Settings.font])
        }
    val palette =
        remember(Themes.base(settings)) {
            Themes.palette(settings).map(android.graphics.Color::parseColor).toIntArray()
        }
    // The insets the screen is padded by, and the same insets once the keyboard stops moving.
    val insets = WindowInsets.safeDrawing
    @OptIn(ExperimentalLayoutApi::class)
    val settledInsets =
        WindowInsets.systemBars
            .union(WindowInsets.imeAnimationTarget)
            .union(WindowInsets.displayCutout)
    // Where the keyboard is going, so the key toolbar appears and leaves with the animation's
    // start instead of its end.
    @OptIn(ExperimentalLayoutApi::class)
    val keyboardVisible =
        WindowInsets.imeAnimationTarget.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    // While text is selected, Back ends selecting before it does anything else.
    var selecting by remember { mutableStateOf(false) }
    BackHandler(enabled = selecting && shown) { view?.endSelection() }
    val status by model.statusLines.collectAsStateWithLifecycle()
    val statusFont = remember(terminalFont) { FontFamily(terminalFont) }
    Column(Modifier.fillMaxSize()) {
        // Like T-UI's status lines: fixed above the terminal, one row each, in its
        // font and in the dimmer color notification times use. Hidden with the terminal, it keeps
        // its room so the terminal grid does not change.
        if (status.isNotEmpty())
            Text(
                status.joinToString("\n"),
                Modifier.fillMaxWidth()
                    .padding(start = 8.dp, top = 4.dp, end = 8.dp)
                    .alpha(if (shown) 1f else 0f)
                    .testTag("statusLines"),
                color = Color(android.graphics.Color.parseColor(NotificationLines.timeColor(settings))),
                fontFamily = statusFont,
                fontSize = settings[Settings.fontSize].sp,
                softWrap = false,
                maxLines = status.size,
                overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
            )
        AndroidView(
            factory = { context ->
                TerminalView(context).also { v ->
                    v.session = terminal
                    // Like T-UI: tap an entered command to run it again.
                    v.onCommandTap = { command -> model.submit(command) }
                    v.onCopy = { text -> model.device.copy(text) }
                    v.onSelectionChanged = { selecting = it }
                    v.setOnCreateContextMenuListener { menu, _, _ ->
                        v.pressedNotice?.let { id ->
                            menu
                                .add(textContext.getString(TextR.string.label_open_app))
                                .setOnMenuItemClickListener {
                                    model.openNotification(id)
                                    true
                                }
                            menu
                                .add(textContext.getString(TextR.string.label_hide_app_notifications))
                                .setOnMenuItemClickListener {
                                    model.hideNotifications(id)
                                    true
                                }
                        }
                        v.pressedCommand?.let { command ->
                            menu
                                .add(textContext.getString(TextR.string.label_run_again))
                                .setOnMenuItemClickListener {
                                    v.finishInput()
                                    model.submit(command)
                                    true
                                }
                            menu
                                .add(textContext.getString(TextR.string.label_edit_command))
                                .setOnMenuItemClickListener {
                                    v.finishInput()
                                    model.stageCommand(command)
                                    true
                                }
                        }
                        menu
                            .add(textContext.getString(TextR.string.label_select_text))
                            .setOnMenuItemClickListener {
                                v.startSelection()
                                true
                            }
                        menu
                            .add(textContext.getString(TextR.string.label_copy_screen))
                            .setOnMenuItemClickListener {
                                model.device.copy(v.visibleText())
                                true
                            }
                        menu
                            .add(textContext.getString(TextR.string.label_paste))
                            .setOnMenuItemClickListener {
                                val clip =
                                    context
                                        .getSystemService(
                                            android.content.ClipboardManager::class.java
                                        )
                                        .primaryClip
                                clip?.getItemAt(0)?.coerceToText(context)?.toString()?.let(v::paste)
                                true
                            }
                        menu
                            .add(textContext.getString(TextR.string.label_apps_2))
                            .setOnMenuItemClickListener {
                                model.navigate(Route.Apps)
                                true
                            }
                        menu
                            .add(textContext.getString(TextR.string.label_settings))
                            .setOnMenuItemClickListener {
                                model.navigate(Route.SettingsPage())
                                true
                            }
                    }
                    view = v
                    v.visibility = if (shown) View.VISIBLE else View.INVISIBLE
                    if (shown && settings[Settings.autoKeyboard]) v.post { v.performClick() }
                }
            },
            update = { v ->
                // Hidden, the view gives up focus and the keyboard; shown again, it takes them
                // back in the same frame.
                val visibility = if (shown) View.VISIBLE else View.INVISIBLE
                if (v.visibility != visibility) {
                    v.visibility = visibility
                    if (shown && settings[Settings.autoKeyboard]) v.performClick()
                }
                v.session = terminal
                terminal.palette(palette)
                v.foregroundColor =
                    android.graphics.Color.parseColor(settings[Settings.foreground])
                v.cursorColor = android.graphics.Color.parseColor(Themes.cursor(settings))
                v.inputColor =
                    android.graphics.Color.parseColor(settings[Settings.inputColor])
                v.terminalBackground =
                    if (settings[Settings.wallpaper]) android.graphics.Color.TRANSPARENT
                    else android.graphics.Color.parseColor(settings[Settings.background])
                v.terminalTypeface = terminalFont
                v.fontSize = settings[Settings.fontSize].toFloat()
                // Like T-UI: tap a notification to open it, as in the shade.
                v.onNoticeTap =
                    if (settings[Settings.notificationTapOpen]) model::openNotification else null
                v.controlLatch = control
                v.altLatch = alt
                v.onModifiersConsumed = {
                    control = false
                    alt = false
                }
                if (revision >= 0) v.refresh()
            },
            // Sized for where the keyboard ends up, so showing or hiding it changes the grid once
            // instead of every animation frame; the box cuts off what the keyboard still covers.
            // The margin keeps text off the screen edges; the screen background fills it, so the
            // terminal color still reaches the edges.
            modifier =
                Modifier.weight(1f)
                    .fillMaxWidth()
                    .padding(start = 8.dp, top = 4.dp, end = 8.dp)
                    .clipToBounds()
                    .layout { measurable, constraints ->
                        val change = insets.getBottom(this) - settledInsets.getBottom(this)
                        val height =
                            if (constraints.hasBoundedHeight)
                                (constraints.maxHeight + change).coerceAtLeast(0)
                            else constraints.maxHeight
                        val placeable =
                            measurable.measure(
                                constraints.copy(minHeight = height, maxHeight = height)
                            )
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            placeable.place(0, 0)
                        }
                    }
                    .testTag("terminalSurface"),
        )
        if (exited != null && shown)
            Text(
                textContext.getString(TextR.string.shell_exited, exited.toString()),
                fontFamily = FontFamily.Monospace,
                modifier =
                    Modifier.fillMaxWidth().clickable { model.ensureTerminal() }.padding(8.dp),
            )
        if (settings[Settings.toolbar] && keyboardVisible && shown)
            Row(
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                listOf("APPS", "ESC", "CTRL", "ALT", "TAB", "←", "↓", "↑", "→").forEach { key ->
                    Text(
                        if (key == "APPS") textContext.getString(TextR.string.label_apps) else key,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color =
                            if (key == "CTRL" && control || key == "ALT" && alt) Color.White
                            else Color(0xFF9DA6AE),
                        modifier =
                            Modifier.heightIn(min = 40.dp)
                                .widthIn(min = 44.dp)
                                .clickable {
                                    when (key) {
                                        "APPS" -> model.navigate(Route.Apps)
                                        "CTRL" -> control = !control
                                        "ALT" -> alt = !alt
                                        else -> {
                                            view?.terminalKey(
                                                mapOf(
                                                        "ESC" to 4,
                                                        "TAB" to 2,
                                                        "←" to 7,
                                                        "↓" to 6,
                                                        "↑" to 5,
                                                        "→" to 8,
                                                    )
                                                    .getValue(key),
                                                (if (control) 4 else 0) or (if (alt) 2 else 0),
                                            )
                                            control = false
                                            alt = false
                                        }
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 12.dp),
                    )
                }
            }
    }
}
