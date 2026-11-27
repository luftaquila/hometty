# hometty

A CLI shell launcher for Android. Your home screen is a shell.

Inspired by [T-UI](https://github.com/fandreuz/TUI-ConsoleLauncher).

- Open apps by name or package name, with tab completion
- Notifications show up in the terminal
- Runs BusyBox ash that supports pipes, redirection, history and even `vi`
- Notes, RSS feeds, weather and status lines

## Usage

Install the APK (Android 10+) and set hometty as your home app.

Type `help` to see the commands and `config` to change settings.

[Privacy policy](https://github.com/luftaquila/hometty/blob/master/docs/privacy.md)

```sh
apps -al
chrome
alias work='apps launch ${1}'
calc 2 * 3
weather
```

## Status lines

The initial/reset preset shows date and seconds, battery with a charging `+` and network, memory, storage, and weather in five colored lines. It uses 13sp text, 2dp line spacing, 6dp vertical padding, and no divider. Existing saved formats and styles are preserved.

- Status lines (Settings > Terminal > Output): freely written lines fixed above the terminal, such as `{date} {time:HH:mm:ss} · {battery} {charging}` or `{weather}`. The editor offers placeholder insertion and a live preview using device values. Empty text turns the block off.
- Customize the charging marker with `{charging:ON|OFF}`; `OFF` is optional and defaults to empty. `{battery}{charging:+}` shows `64%+` while charging and `64%` otherwise; `{charging: ⚡| on battery}` chooses your own text for each state. Spaces and Unicode symbols are preserved, and each occurrence can use different text. The plain `{charging}` still uses the app language. Within the custom text, escape literal `|`, `{`, `}` or `\` with a backslash; custom text is literal, without nested placeholders.
- The status editor always shows placeholders and appearance settings: text size, color, alignment, wrapping/ellipsis/clipping, background, line spacing, padding and a divider. Apply text styles to all lines or just the line containing the cursor; line overrides follow source line numbers. Blank colors and inherited sizes follow the theme/terminal or the block defaults. Colors accept `#RRGGBB` or `#RRGGBBAA` (trailing alpha).
- Put `{right}` anywhere in a source line to split it into equal-width left and right fields, for example `{time}{right}{battery} {charging}`. Without it, all original text, line order and placeholders remain unchanged. Both fields use the chosen overflow behavior. Save commits content and appearance together; Cancel discards the draft. Reset appearance restores the built-in colors and spacing while preserving the content. Conditional-format help starts collapsed and separates examples, operators, and variables into tabs.
- CLI and backups keep the free text in `terminal.statusLines` and the separate JSON style in `terminal.statusAppearance` (initially the colored five-line preset). For example: `config set terminal.statusAppearance '{"text":{"fontSize":16,"overflow":"wrap"},"padding":8,"lines":{"1":{"color":"#8BE9FD"}}}'`. Omitted text-style fields inherit; source line indices in JSON start at zero.

### Conditional format

`{if expression}text{else}other text{end}` chooses a branch. The else branch is optional. Branches can contain any text, placeholders, nested conditions, `{right}`, and `{color:#RRGGBB}text{/color}` (also accepts trailing alpha, `#RRGGBBAA`). Color applies only inside its block; closing it restores the enclosing color or line style. Entire lines made empty by conditions disappear. Intentional blank lines remain, and per-line appearance settings keep their original source line numbers.

```text
{date} {time:HH:mm:ss}
{battery}{if charging}+{end} ({network})
{if batteryPercent < 20 && !charging}{color:#FF5555}LOW {battery}{/color}{end}
{if memoryFreeBytes / memoryTotalBytes < 0.1}RAM LOW: {memory}{end}
{if network == 'Wi-Fi'}WIFI{else}{if networkConnected}DATA{else}OFFLINE{end}{end}
{if weatherAvailable}{weather}{else}{if weatherLoading}Loading…{else}{weatherError}{end}{end}
```

Conditions use bare variable names, quoted strings (single or double quotes), numbers, `true`, `false`, and `null`. Operators, in increasing precedence: `||`, `&&`, `== !=`, `< <= > >=`, `+ -`, `* / %`, unary `! + -`. Parentheses override precedence. Arithmetic and ordering comparisons require numbers; equality is type-sensitive and string matching is case-sensitive. Missing values and invalid calculations (such as division by zero) yield `null`; ordering comparisons with unavailable/non-numeric values are false. Empty strings, zero, false and null are false; other values are true. `&&` and `||` short-circuit. Helpers: `exists(value)`, `empty(value)`, `contains(text, part)`, `startsWith(text, prefix)`, `endsWith(text, suffix)`. `empty` means null or an empty string, not numeric zero.

All supplied status values can be referenced by name; expressions are not restricted to the examples above. These values are currently supplied by the device and clock, and can also be printed as `{name}`:

| Values | Meaning |
|---|---|
| `batteryPercent`, `charging` | Battery percentage (0–100) and charging boolean. `{charging}` still prints localized text; `battery` is a numeric alias in conditions and prints with `%`. |
| `memoryFreeBytes`, `memoryTotalBytes`, `memoryFreeGiB`, `memoryTotalGiB`, `memoryUsedPercent` | Free/total RAM and used percentage. |
| `storageFreeBytes`, `storageTotalBytes`, `storageFreeGiB`, `storageTotalGiB`, `storageUsedPercent` | Available/total app-filesystem storage and used percentage. |
| `network`, `networkConnected`, `device` | Network name (`Wi-Fi`, `mobile`, `ethernet`, `connected`, `offline`), whether an active network exists (not an internet reachability check), and configured device name. |
| `weather`, `weatherAvailable`, `weatherError`, `weatherLoading` | Weather text, successful nonempty result, error text (empty on success), and request-in-progress boolean. Referencing weather state alone also starts weather updates when enabled. |
| `time`, `date`, `year`, `month`, `day`, `weekday`, `hour`, `minute`, `second` | Local clock; weekday Monday=1 through Sunday=7, hour 0–23. Conditions using `second` refresh every second. |
| `memory`, `storage` | Existing formatted free/total strings. Use the numeric fields for thresholds. |

Numeric fields are unrounded and locale-independent; one GiB is 1,073,741,824 bytes. Quotes in expressions support backslash escapes. Outside commands, `\{`, `\}`, and `\\` print literal braces/backslashes. Device values are always literal: they cannot inject conditions, layout, or colors. Existing `{charging:ON|OFF}` remains a literal-text shorthand. Blocks may nest up to 32 levels; expressions are bounded to prevent pathological input. Invalid commands show a line-numbered error and cannot be saved; incomplete drafts remain visible in the preview.

## Building

Requires JDK 26, Python 3.12+, `patch`, and the Android SDK with NDK 28.2.13676358 and CMake 3.22.1.

```sh
./gradlew :launcher:assembleDebug                  # debug APK
./gradlew :core:test :launcher:testDebugUnitTest   # unit tests
./gradlew :launcher:connectedDebugAndroidTest      # device tests
```

The first build downloads and compiles BusyBox using the pinned [build recipe](busybox/README.md).

Release downloads include the matching BusyBox source archive with patched sources,
licenses and offline build instructions.
