package io.luftaquila.hometty.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Null fields inherit the block style, or the terminal when used as the block style. */
@Serializable
data class StatusTextStyle(
    val fontSize: Int? = null,
    val color: String? = null,
    val alignment: String? = null,
    val overflow: String? = null,
) {
    fun over(base: StatusTextStyle) = StatusTextStyle(
        fontSize ?: base.fontSize,
        color ?: base.color,
        alignment ?: base.alignment,
        overflow ?: base.overflow,
    )

    fun validate() {
        require(fontSize == null || fontSize in 10..36)
        require(color == null || color.matches(colorPattern))
        require(alignment == null || alignment in listOf("left", "center", "right"))
        require(overflow == null || overflow in listOf("wrap", "ellipsis", "clip"))
    }
}

/** Separate from the template: editing appearance never rewrites user text. Row keys are zero-based. */
@Serializable
data class StatusAppearance(
    val text: StatusTextStyle = StatusTextStyle(),
    val background: String? = null,
    val lineSpacing: Int = 4,
    val padding: Int = 8,
    val divider: Boolean = true,
    val lines: Map<Int, StatusTextStyle> = emptyMap(),
) {
    fun style(line: Int) = (lines[line] ?: StatusTextStyle()).over(text)

    fun encode(): String = Json.encodeToString(this)

    fun validate() {
        text.validate()
        require(background == null || background.matches(colorPattern))
        require(lineSpacing in 0..24 && padding in 0..32)
        lines.forEach { (line, style) ->
            require(line in 0..8192)
            style.validate()
        }
    }

    companion object {
        /** Editor drafts can contain a partially entered color; validate before persisting. */
        fun decodeDraft(value: String): StatusAppearance = Json.decodeFromString(value)

        fun decode(value: String): StatusAppearance =
            decodeDraft(value).also { it.validate() }
    }
}
