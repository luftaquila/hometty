package io.luftaquila.hometty

/** A screen of the launcher window. [path] names it in saved state, which needs a string. */
sealed class Route(val path: String) {
    data object Home : Route("home")

    data object Start : Route("start")

    /**
     * A settings page: the overview (empty [page]), a section id, a setting category from
     * `config CATEGORY`, `colors` or `changed`.
     */
    data class SettingsPage(val page: String = "") :
        Route(if (page.isEmpty()) "settings" else "settings/$page")

    data object Theme : Route("settings/theme")

    data object Font : Route("settings/font")

    /** The list and editor of one collection (aliases, notes, feeds...). */
    data class Manage(val kind: String) : Route("manage/$kind")

    /** The searchable list of all apps. */
    data object Apps : Route("apps")

    data object Backup : Route("backup")

    data object About : Route("about")
}
