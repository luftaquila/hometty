# Settings reference

Run `config` to open settings, or use these commands to view, change, and reset values:

```sh
config list
config list appearance.
config get appearance.fontSize
config set appearance.fontSize 16
config reset appearance.fontSize
```

Use the full setting key, such as `appearance.fontSize`. Boolean settings accept `true` or `false`. Put colors and values containing spaces or shell symbols in quotes.

## Appearance

| Key | Default | Description |
| --- | --- | --- |
| `appearance.theme` | `dracula` | Preset name. Run `theme` to list them |
| `appearance.background` | `#282A36` | Background color |
| `appearance.foreground` | `#F8F8F2` | Output text color |
| `appearance.input` | `#50FA7B` | Input color |
| `appearance.font` | `jetendard` | Font ID, listed below |
| `appearance.customFont` | Empty | Managed by the font import screen |
| `appearance.fontSize` | `14` | 10–36 |
| `appearance.wallpaper` | `false` | Show Android wallpaper |
| `appearance.opacity` | `100` | 0–100, used with wallpaper |
| `appearance.fullscreen` | `false` | Hide system bars |
| `appearance.orientation` | `auto` | `auto`, `portrait`, `landscape` |

::: details Font IDs

`jetendard`, `d2coding`, `jetbrains_mono`, `fira_code`, `cascadia_code`, `source_code_pro`, `ibm_plex_mono`, `hack`, `iosevka`, `0xproto`, `monospace`, `custom`.

Import a file in the font screen before selecting `custom`. `monospace` uses the system font.

:::

Colors use `#RRGGBB` or `#RRGGBBAA`. Changing `appearance.theme` also applies that preset's colors.

## Prompt and input

| Key | Default | Description |
| --- | --- | --- |
| `home.username` | `user` | Prompt user name |
| `home.device` | `android` | Device name in the prompt and status lines |
| `home.prompt` | See below | Prompt format |
| `input.showHints` | `true` | Startup help text |
| `input.autoKeyboard` | `true` | Show the keyboard on returning home |
| `input.toolbar` | `true` | Extra terminal keys |
| `input.backHistory` | `true` | Back recalls previous commands |

Default prompt:

```text
{user}@{device}:{cwd} $
```

## Apps

| Key | Default | Description |
| --- | --- | --- |
| `apps.showLaunches` | `true` | Show a message when an app launches |
| `apps.showChanges` | `true` | Show messages for app installs, updates, and removals |
| `apps.searchEngine` | `google` | `google`, `duckduckgo`, `youtube` |

## Terminal

| Key | Default | Description |
| --- | --- | --- |
| `terminal.statusLines` | [Five status lines](../status-lines.md#start-with-a-format) | Leave empty to hide status lines |
| `terminal.statusAppearance` | Colored per-line styles | Use the status-lines editor for layout and text styles |
| `terminal.maxLines` | `10000` | Scrollback limit: 1,000–50,000 in steps of 1,000 |
| `terminal.clearOnLock` | `false` | Clear output at an empty prompt when the screen turns off |
| `terminal.rootEnabled` | `false` | Permit `su` on a rooted phone |
| `terminal.home` | Empty | Starting folder. Empty uses internal shared storage |

## Notifications

| Key | Default | Description |
| --- | --- | --- |
| `notifications.enabled` | `true` | Print notifications when access is granted |
| `notifications.includeByDefault` | `true` | Include apps without a rule |
| `notifications.tapToOpen` | `false` | Open a notification by tapping it |
| `notifications.format` | See below | Default notification format |

```text
{app}: {title} - {text}
```

Use per-app rules to show or hide notifications and change their color or format. See [Notifications](../notifications.md).

## RSS and weather

| Key | Default | Description |
| --- | --- | --- |
| `rss.enabled` | `false` | Check saved feeds for updates |
| `rss.intervalMinutes` | `60` | 15–1,440 minutes |
| `rss.wifiOnly` | `false` | Check for updates only on Wi-Fi |
| `rss.showUpdates` | `true` | Print new feed items |
| `weather.provider` | `wttr` | `off`, `wttr`, `openweather` |
| `weather.intervalMinutes` | `30` | 15–360 minutes, in steps of 15 |
| `weather.units` | `metric` | `metric`, `imperial`, `standard` |
| `weather.format` | See below | Weather output format |

```text
{city} · {temp}{unit} · {description}
```

The weather interval controls how often weather updates in status lines. The OpenWeather API key is stored separately.

## Privacy

| Key | Default | Description |
| --- | --- | --- |
| `privacy.history` | `true` | Save command history |
| `privacy.historyLimit` | `10000` | 1,000–50,000 in steps of 1,000 |

## Settings screens

`config CATEGORY` accepts `appearance`, `home`, `input`, `apps`, `terminal`, `notifications`, `notes`, `rss`, `weather`, and `privacy`.

Manage aliases, notes, feeds, and notification rules through their commands or their editors in `config`.
