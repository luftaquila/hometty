package io.luftaquila.hometty.core

data class FieldSpec(
    val key: String,
    val label: String,
    val default: String = "",
    val kind: SettingKind = SettingKind.TEXT,
)

/** [description] says what the entries are for; shown on the manager screen and its link. */
data class CollectionSpec(
    val kind: String,
    val title: String,
    val fields: List<FieldSpec>,
    val description: String = "label_collection_${kind.replace(Regex("([A-Z])"), "_$1").lowercase()}_desc",
)

object Collections {
    val all =
        listOf(
            CollectionSpec(
                "alias",
                "label_aliases",
                listOf(FieldSpec("command", "label_command_arguments_1")),
            ),
            CollectionSpec(
                "note",
                "label_notes",
                listOf(
                    FieldSpec("text", "label_text"),
                    FieldSpec("locked", "label_lock", "false", SettingKind.BOOLEAN),
                ),
            ),
            CollectionSpec(
                "rss",
                "label_feeds",
                listOf(
                    FieldSpec("url", "label_https_url"),
                    FieldSpec("enabled", "label_enabled", "true", SettingKind.BOOLEAN),
                    FieldSpec("include", "label_include_regex"),
                    FieldSpec("exclude", "label_exclude_regex"),
                    FieldSpec("format", "label_format_2", "{title}\n{url}"),
                    FieldSpec("entryTag", "label_entry_selector", "item, entry"),
                    FieldSpec("dateTag", "label_date_selector", "pubDate, published, updated"),
                ),
            ),
            CollectionSpec(
                "notificationRule",
                "label_notification_rules",
                listOf(
                    FieldSpec("package", "label_apps"),
                    FieldSpec("enabled", "label_include", "true", SettingKind.BOOLEAN),
                    // Blank: the terminal's text color and the format setting.
                    FieldSpec("color", "label_text_color", "", SettingKind.COLOR),
                    FieldSpec("format", "label_format_2"),
                    FieldSpec("include", "label_include_regex"),
                    FieldSpec("exclude", "label_exclude_regex"),
                ),
            ),
        )
    val byKind = all.associateBy { it.kind }

    /** Kinds a backup holds and restores: every collection plus per-app preferences. */
    val backupKinds = all.map { it.kind }.toSet() + "app"

    /** A locked entry (a note) stays until it is unlocked. */
    fun validateRemoval(entry: Entry) {
        requireMessage(entry.fields["locked"] != "true", "message_unlock_note_first")
    }

    fun validate(kind: String, name: String, fields: Map<String, String>) {
        requireMessage(name.isNotBlank() && name.length <= 1024, "error_entry_name")
        val spec = byKind[kind] ?: return
        for (field in spec.fields) {
            val value = fields[field.key] ?: field.default
            if (field.kind == SettingKind.BOOLEAN)
                requireMessage(value in setOf("true", "false"), "error_field_boolean")
            if (field.kind == SettingKind.COLOR && value.isNotBlank())
                requireMessage(value.matches(colorPattern), "error_color")
            if (field.key in setOf("pattern", "include", "exclude") && value.isNotBlank())
                Patterns.validate(value)
        }
        if (kind == "rss") {
            val uri = runCatching { java.net.URI(fields["url"].orEmpty()) }.getOrNull()
            requireMessage(uri?.scheme == "https" && !uri.host.isNullOrBlank(), "error_https_url")
        }
        if (kind == "alias") {
            require(
                name.matches(Regex("[\\p{L}\\p{N}_-]+")) &&
                    // The name is a file name on PATH: 255 bytes at most.
                    name.toByteArray().size <= 255 &&
                    name !in Commands.byName &&
                    name !in Commands.shellBuiltins
            ) {
                "message_alias_name_invalid_or_reserved"
            }
            require(fields["command"].orEmpty().isNotBlank()) {
                "message_command_required"
            }
            require(fields["command"].orEmpty().length <= 16384) { "message_alias_too_long" }
        }
        if (kind == "notificationRule")
            require(fields["package"].orEmpty().matches(Regex("[A-Za-z0-9_.]+"))) {
                "message_package_required"
            }
        if (kind == "note")
            require(!fields["text"].isNullOrBlank()) { "message_note_text_required" }
    }
}
