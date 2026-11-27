package io.luftaquila.hometty.core

/** Initial and reset values; user templates and per-line styles remain freely editable. */
object StatusDefaults {
    const val lines = "{date} {time:HH:mm:ss}\n" +
        "BATTERY  {battery}{charging:+} ({network})\n" +
        "MEMORY   {memory}\n" +
        "STORAGE  {storage}\n" +
        "{weather}"
    val appearance = StatusAppearance(
        text = StatusTextStyle(fontSize = 13, overflow = "wrap"),
        lineSpacing = 2,
        padding = 6,
        divider = false,
        lines = mapOf(
            0 to StatusTextStyle(color = "#BD93F9"),
            1 to StatusTextStyle(color = "#50FA7B"),
            2 to StatusTextStyle(color = "#8BE9FD"),
            3 to StatusTextStyle(color = "#F1FA8C"),
            4 to StatusTextStyle(color = "#FFB86C"),
        ),
    )
}
