package io.luftaquila.hometty.core

/** Store user text as variable values: PS1 must never evaluate text from settings as shell code. */
object ShellPrompt {
    fun setup(settings: SettingsSnapshot): String {
        val template =
            settings[Settings.prompt]
                .replace("{user}", settings[Settings.username])
                .replace("{device}", settings[Settings.deviceName])
                .filter { it >= ' ' && it != '\u007f' }
        val parts = template.split("{cwd}", limit = 2)
        val prefix = Shell.quote(parts[0])
        val suffix = Shell.quote(parts.getOrElse(1) { "" })
        val rgb = settings[Settings.inputColor].takeLast(6)
        val color =
            "\u001b[38;2;" + rgb.chunked(2).joinToString(";") { it.toInt(16).toString() } + "m"
        // OSC 133;A marks the prompt start and 133;B where typed input begins; the terminal
        // uses B to find entered commands again when they are tapped.
        val prompt =
            "\\[\u001b]133;A\u0007$color\\]\${HOMETTY_PROMPT_PREFIX}" +
                (if (parts.size == 2) "\\w\${HOMETTY_PROMPT_SUFFIX}" else "") +
                " \\[\u001b]133;B\u0007\\]"
        return "export HOMETTY_SHELL_COLORS=1\nexport HOMETTY_PROMPT_PREFIX=$prefix HOMETTY_PROMPT_SUFFIX=$suffix\nexport PS1=${Shell.quote(prompt)}\n"
    }
}
