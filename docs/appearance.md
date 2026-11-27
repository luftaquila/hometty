# Appearance

Run `config appearance` to change the theme, font, colors, wallpaper, and screen orientation.

## Themes and colors

List the available presets, then apply one:

```sh
theme
theme set dracula
```

Dracula is the default theme. A preset applies its own colors, which you can adjust afterward:

```sh
config set appearance.background '#282A36'
config set appearance.foreground '#F8F8F2'
config set appearance.input '#50FA7B'
```

Colors use `#RRGGBB` or `#RRGGBBAA`. Put them in quotes in shell commands.

## Fonts and layout

- Choose a bundled font or import a font in the **Font** screen.
- Change terminal text size with `config set appearance.fontSize 16`.
- Enable **Wallpaper** to show the Android wallpaper. Use **Opacity** to adjust how much shows through the terminal background.
- Set **Orientation** to auto, portrait, or landscape.

The Android keyboard uses its own theme. Change it in your keyboard app to match hometty.

## Prompt

The prompt uses `{user}`, `{device}`, and `{cwd}`:

```sh
config set home.username alex
config set home.prompt '{user}@{device}:{cwd} $'
```

`{device}` uses your configured device name in both the prompt and status lines.

## Status area

The lines above the terminal have separate text, colors, and layout controls. See [Status lines](./status-lines.md).

If the screen is hard to read, long-press the terminal, open **Settings**, and choose **Reset display settings**.
