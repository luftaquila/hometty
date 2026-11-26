package io.luftaquila.hometty.core

import kotlinx.serialization.Serializable

enum class SettingKind {
    BOOLEAN,
    INTEGER,
    TEXT,
    COLOR,
    CHOICE,
}

data class SettingDefinition(
    val key: String,
    val title: String,
    val description: String,
    val default: String,
    val kind: SettingKind = SettingKind.TEXT,
    val min: Int? = null,
    val max: Int? = null,
    val choices: List<String> = emptyList(),
    /** Values go from [min] in steps of this size; null: any value in range. */
    val step: Int? = null,
) {
    val category
        get() = key.substringBefore('.')

    fun validate(value: String): String {
        when (kind) {
            SettingKind.BOOLEAN ->
                requireMessage(value == "true" || value == "false", "error_boolean")
            SettingKind.INTEGER -> requireMessage(value.toIntOrNull() != null, "error_integer")
            SettingKind.COLOR ->
                requireMessage(value.matches(colorPattern), "error_color")
            SettingKind.CHOICE ->
                requireMessage(value in choices, "error_choice", choices.joinToString())
            SettingKind.TEXT -> requireMessage(value.length <= 8192, "error_too_long")
        }
        // Ranges belong to integer settings, whose value is a number by now.
        if (min != null) requireMessage(value.toInt() >= min, "error_minimum", min)
        if (max != null) requireMessage(value.toInt() <= max, "error_maximum", max)
        if (step != null) requireMessage((value.toInt() - (min ?: 0)) % step == 0, "error_step", step)
        return value
    }
}

/** A color as settings and records store it: #RRGGBB or #RRGGBBAA. */
val colorPattern = Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")

@Serializable
data class SettingsSnapshot(val version: Int = 1, val values: Map<String, String> = emptyMap()) {
    fun string(key: String): String = values[key] ?: Settings.byKey.getValue(key).default

    fun bool(key: String) = string(key).toBooleanStrict()
}

object Settings {
    /** What `config CATEGORY` accepts: the first part of setting keys. */
    val categories =
        listOf(
            "appearance",
            "home",
            "input",
            "apps",
            "terminal",
            "notifications",
            "notes",
            "rss",
            "weather",
            "privacy",
        )

    // Definitions in list order. Title "label_set_X" and description "label_set_X_desc" for every
    // key; see strings.xml.
    private val definitions = mutableListOf<SettingDefinition>()

    private fun <T : Setting<*>> define(setting: T): T = setting.also { definitions += it.definition }

    private fun text(k: String, title: String, default: String) =
        define(TextSetting(SettingDefinition(k, title, title + "_desc", default)))

    private fun bool(k: String, title: String, default: Boolean) =
        define(
            BooleanSetting(
                SettingDefinition(k, title, title + "_desc", default.toString(), SettingKind.BOOLEAN)
            )
        )

    private fun number(
        k: String,
        title: String,
        default: Int,
        min: Int,
        max: Int,
        step: Int? = null,
    ) =
        define(
            IntSetting(
                SettingDefinition(
                    k,
                    title,
                    title + "_desc",
                    default.toString(),
                    SettingKind.INTEGER,
                    min,
                    max,
                    step = step,
                )
            )
        )

    private fun choice(k: String, title: String, default: String, options: List<String>) =
        define(
            TextSetting(
                SettingDefinition(
                    k,
                    title,
                    title + "_desc",
                    default,
                    SettingKind.CHOICE,
                    choices = options,
                )
            )
        )

    private fun color(k: String, title: String, default: String) =
        define(TextSetting(SettingDefinition(k, title, title + "_desc", default, SettingKind.COLOR)))

    val background =
        color("appearance.background", "label_set_background", "#282A36")
    val foreground =
        color("appearance.foreground", "label_set_foreground", "#F8F8F2")
    val inputColor =
        color("appearance.input", "label_set_input_color", "#50FA7B")
    val theme =
        choice("appearance.theme", "label_set_theme", "dracula", Themes.presets.keys.toList())
    val fontSize =
        number("appearance.fontSize", "label_set_font_size", 14, 10, 36)
    val opacity =
        number("appearance.opacity", "label_set_opacity", 100, 0, 100)
    val wallpaper =
        bool("appearance.wallpaper", "label_set_wallpaper", false)
    val fullscreen =
        bool("appearance.fullscreen", "label_set_fullscreen", false)
    val font =
        choice("appearance.font", "label_set_font", Fonts.DEFAULT, Fonts.choices)
    val customFont =
        text("appearance.customFont", "label_set_custom_font", "")
    val orientation =
        choice(
            "appearance.orientation",
            "label_set_orientation",
            "auto",
            listOf("auto", "portrait", "landscape"),
        )
    val showHints =
        bool("input.showHints", "label_set_show_hints", true)
    val weatherFormat =
        text("weather.format", "label_set_weather_format", "{city} · {temp}{unit} · {description}")
    val username =
        text("home.username", "label_set_username", "user")
    val deviceName =
        text("home.device", "label_set_device", "android")
    val prompt =
        text("home.prompt", "label_set_prompt", "{user}@{device}:{cwd} $")
    val autoKeyboard =
        bool("input.autoKeyboard", "label_set_auto_keyboard", true)
    val toolbar =
        bool("input.toolbar", "label_set_toolbar", true)
    val backHistory =
        bool("input.backHistory", "label_set_back_history", true)
    val showLaunches =
        bool("apps.showLaunches", "label_set_show_launches", true)
    val showChanges =
        bool("apps.showChanges", "label_set_show_changes", true)
    val searchEngine =
        choice(
            "apps.searchEngine",
            "label_set_search_engine",
            "google",
            listOf("duckduckgo", "google", "youtube"),
        )
    val maxLines =
        number("terminal.maxLines", "label_set_max_lines", 10000, 1000, 50000, step = 1000)
    val clearOnLock =
        bool("terminal.clearOnLock", "label_set_clear_on_lock", false)
    val rootEnabled =
        bool("terminal.rootEnabled", "label_set_root_enabled", false)
    val shellHome =
        text("terminal.home", "label_set_terminal_home", "")
    val notificationsEnabled =
        bool("notifications.enabled", "label_set_notifications_enabled", true)
    val includeByDefault =
        bool("notifications.includeByDefault", "label_set_include_by_default", true)
    /** T-UI's click_notification; off unless turned on. */
    val notificationTapOpen =
        bool("notifications.tapToOpen", "label_set_notification_tap_open", false)
    val notificationFormat =
        text("notifications.format", "label_set_notification_format", "{app}: {title} - {text}")
    val rssInterval =
        number("rss.intervalMinutes", "label_set_rss_interval", 60, 15, 1440)
    val rssWifiOnly =
        bool("rss.wifiOnly", "label_set_rss_wifi_only", false)
    val rssEnabled =
        bool("rss.enabled", "label_set_rss_enabled", false)
    val rssShowUpdates =
        bool("rss.showUpdates", "label_set_rss_show_updates", true)
    /** wttr.in needs no account; OpenWeather needs an API key. */
    val weatherProvider =
        choice("weather.provider", "label_set_weather_provider", "wttr", listOf("wttr", "openweather"))
    val units =
        choice("weather.units", "label_set_units", "metric", listOf("metric", "imperial", "standard"))
    val historyEnabled =
        bool("privacy.history", "label_set_history", true)
    val historyLimit =
        number("privacy.historyLimit", "label_set_history_limit", 10000, 1000, 50000, step = 1000)

    val all: List<SettingDefinition> = definitions.toList()

    /** Font and theme preset are chosen on their own screens, not in the generic setting rows. */
    val terminalUi = all.filter { it.key !in setOf(customFont.key, font.key, theme.key) }

    /** The switch a setting only matters behind: opacity needs the wallpaper, and so on. */
    fun gate(definition: SettingDefinition): BooleanSetting? =
        when {
            definition.key == opacity.key -> wallpaper
            definition.key == historyLimit.key -> historyEnabled
            definition.category == "notifications" && definition.key != notificationsEnabled.key ->
                notificationsEnabled
            definition.category == "rss" && definition.key != rssEnabled.key -> rssEnabled
            else -> null
        }

    /** The rows to show: settings whose switch is off are hidden. */
    fun visibleSettings(snapshot: SettingsSnapshot): List<SettingDefinition> =
        terminalUi.filter { definition -> gate(definition)?.let { snapshot[it] } ?: true }

    val byKey = all.associateBy { it.key }
}

/** A setting with its value type, read as `snapshot[Settings.fontSize]`. */
sealed class Setting<T>(val definition: SettingDefinition) {
    val key
        get() = definition.key

    abstract fun parse(value: String): T

    /** The saved form of [value], for SettingsStore.update. */
    fun value(value: T): Pair<String, String> = key to value.toString()
}

class BooleanSetting(definition: SettingDefinition) : Setting<Boolean>(definition) {
    override fun parse(value: String) = value.toBooleanStrict()
}

class IntSetting(definition: SettingDefinition) : Setting<Int>(definition) {
    override fun parse(value: String) = value.toInt()
}

/** Text, color and choice settings. */
class TextSetting(definition: SettingDefinition) : Setting<String>(definition) {
    override fun parse(value: String) = value
}

operator fun <T> SettingsSnapshot.get(setting: Setting<T>): T = setting.parse(string(setting.key))

/** Terminal fonts. Bundled ids match the launcher's font resources. */
object Fonts {
    const val DEFAULT = "jetendard"
    const val SYSTEM = "monospace"
    const val CUSTOM = "custom"

    /** Bundled fonts in list order. */
    val bundled =
        listOf(
            "jetendard",
            "d2coding",
            "jetbrains_mono",
            "fira_code",
            "cascadia_code",
            "source_code_pro",
            "ibm_plex_mono",
            "hack",
            "iosevka",
            "0xproto",
        )
    val choices = bundled + SYSTEM + CUSTOM
}

/**
 * A theme preset. [palette] holds the 16 ANSI colors (0-7 normal, 8-15 bright); [input] colors the
 * prompt and typed text; [accent] colors the settings UI when it differs from [input].
 */
data class Theme(
    val background: String,
    val foreground: String,
    val input: String,
    val palette: List<String>,
    val cursor: String = foreground,
    val accent: String? = null,
) {
    init {
        require(palette.size == 16) { "palette must have 16 colors" }
    }
}

object Themes {
    private val KEY
        get() = Settings.theme.key

    /** libvterm's built-in palette: the colors the terminal used before themes had palettes. */
    val vterm =
        listOf(
            "#000000",
            "#E00000",
            "#00E000",
            "#E0E000",
            "#0000E0",
            "#E000E0",
            "#00E0E0",
            "#E0E0E0",
            "#808080",
            "#FF4040",
            "#40FF40",
            "#FFFF40",
            "#4040FF",
            "#FF40FF",
            "#40FFFF",
            "#FFFFFF",
        )

    private fun palette(vararg rgb: String) = rgb.map { "#$it" }

    private val solarized =
        palette(
            "073642",
            "DC322F",
            "859900",
            "B58900",
            "268BD2",
            "D33682",
            "2AA198",
            "EEE8D5",
            "002B36",
            "CB4B16",
            "586E75",
            "657B83",
            "839496",
            "6C71C4",
            "93A1A1",
            "FDF6E3",
        )

    val presets: Map<String, Theme> =
        linkedMapOf(
            "default" to Theme("#000000", "#FFFFFF", "#00FF00", vterm),
            "dracula" to
                Theme(
                    "#282A36",
                    "#F8F8F2",
                    "#50FA7B",
                    palette(
                        "21222C",
                        "FF5555",
                        "50FA7B",
                        "F1FA8C",
                        "BD93F9",
                        "FF79C6",
                        "8BE9FD",
                        "F8F8F2",
                        "6272A4",
                        "FF6E6E",
                        "69FF94",
                        "FFFFA5",
                        "D6ACFF",
                        "FF92DF",
                        "A4FFFF",
                        "FFFFFF",
                    ),
                    accent = "#BD93F9",
                ),
            "nord" to
                Theme(
                    "#2E3440",
                    "#D8DEE9",
                    "#A3BE8C",
                    palette(
                        "3B4252",
                        "BF616A",
                        "A3BE8C",
                        "EBCB8B",
                        "81A1C1",
                        "B48EAD",
                        "88C0D0",
                        "E5E9F0",
                        "4C566A",
                        "BF616A",
                        "A3BE8C",
                        "EBCB8B",
                        "81A1C1",
                        "B48EAD",
                        "8FBCBB",
                        "ECEFF4",
                    ),
                ),
            "gruvbox" to
                Theme(
                    "#282828",
                    "#EBDBB2",
                    "#98971A",
                    palette(
                        "282828",
                        "CC241D",
                        "98971A",
                        "D79921",
                        "458588",
                        "B16286",
                        "689D6A",
                        "A89984",
                        "928374",
                        "FB4934",
                        "B8BB26",
                        "FABD2F",
                        "83A598",
                        "D3869B",
                        "8EC07C",
                        "EBDBB2",
                    ),
                ),
            "solarized_dark" to
                Theme("#002B36", "#839496", "#859900", solarized, cursor = "#93A1A1"),
            "solarized_light" to
                Theme("#FDF6E3", "#657B83", "#859900", solarized, cursor = "#586E75"),
            "tern_dark" to
                Theme(
                    "#0F1218",
                    "#D8DEE9",
                    "#9ECE6A",
                    palette(
                        "1C2129",
                        "F7768E",
                        "9ECE6A",
                        "E0AF68",
                        "7AA2F7",
                        "BB9AF7",
                        "7DCFFF",
                        "C0CAF5",
                        "4B5263",
                        "FF8FA3",
                        "B9F27C",
                        "FFC777",
                        "9AB8FF",
                        "D0B3FF",
                        "A6E3FF",
                        "FFFFFF",
                    ),
                    cursor = "#8CA6FF",
                ),
            "tern_light" to
                Theme(
                    "#FAFBFC",
                    "#24292F",
                    "#116329",
                    palette(
                        "24292F",
                        "CF222E",
                        "116329",
                        "9A6700",
                        "0969DA",
                        "8250DF",
                        "1B7C83",
                        "6E7781",
                        "57606A",
                        "A40E26",
                        "1A7F37",
                        "7D4E00",
                        "218BFF",
                        "A475F9",
                        "3192AA",
                        "8C959F",
                    ),
                    cursor = "#4C6FFF",
                ),
            "blue" to Theme("#081525", "#C8E5FF", "#64B5F6", vterm),
            "red" to Theme("#210E13", "#FFD8DF", "#FF6B81", vterm),
            "green" to Theme("#09180D", "#CAF7D4", "#69F0AE", vterm),
            "pink" to Theme("#211320", "#FFE0F0", "#FF80AB", vterm),
            "bw" to Theme("#090909", "#EEEEEE", "#FFFFFF", vterm),
            "cyberpunk" to Theme("#150C28", "#E9D5FF", "#00F5D4", vterm),
        )

    private fun matches(snapshot: SettingsSnapshot, name: String): Boolean =
        values(name).all { (key, value) -> key == KEY || snapshot.string(key).equals(value, true) }

    /**
     * The preset whose palette and cursor the terminal uses: the saved preset, or else the preset
     * whose colors are saved (settings written before presets were saved by name).
     */
    fun base(snapshot: SettingsSnapshot): String =
        snapshot.values[KEY] ?: presets.keys.firstOrNull { matches(snapshot, it) } ?: "default"

    /** The preset in use, or null when its colors were customized. */
    fun selected(snapshot: SettingsSnapshot): String? =
        base(snapshot).takeIf { matches(snapshot, it) }

    fun accent(snapshot: SettingsSnapshot): String =
        selected(snapshot)?.let { presets.getValue(it).accent }
            ?: snapshot[Settings.inputColor]

    fun palette(snapshot: SettingsSnapshot): List<String> = presets.getValue(base(snapshot)).palette

    fun cursor(snapshot: SettingsSnapshot): String =
        selected(snapshot)?.let { presets.getValue(it).cursor }
            ?: snapshot[Settings.foreground]

    fun values(name: String): Map<String, String> {
        val theme = requireNotNull(presets[name]) { "Unknown theme: $name" }
        return mapOf(
            KEY to name,
            Settings.background.value(theme.background),
            Settings.foreground.value(theme.foreground),
            Settings.inputColor.value(theme.input),
        )
    }
}

@Serializable
data class Entry(
    val id: String,
    val kind: String,
    val name: String,
    val fields: Map<String, String> = emptyMap(),
    val created: Long = System.currentTimeMillis(),
)

@Serializable
data class Backup(val version: Int = 1, val settings: SettingsSnapshot, val entries: List<Entry>)
