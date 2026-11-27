# Status lines

Run `config terminal` and select **Status lines**. Edit the text and check the live preview before saving. Clear the text to hide the status area.

## Start with a format

The default format shows the time, battery, network, memory, storage, and weather:

```text
{date} {time:HH:mm:ss}
BATTERY  {battery}{charging:+} ({network})
MEMORY   {memory}
STORAGE  {storage}
{weather}
```

Add, remove, or reorder lines as needed. Use `{right}` to align the rest of a line to the right:

```text
{time:HH:mm}{right}{battery}{charging:+}
```

## Style the lines

Select **All lines (defaults)** to style all lines. To change one line, place the cursor on it and choose **Selected line**. You can change its color, size, alignment, and how it displays long text.

**Whole status area** controls the background, spacing, padding, and divider. Styles stay with their line numbers when you move text. Lines with `{right}` keep their left and right alignment.

To color part of a line, wrap it in color tags:

```text
Battery {color:#50FA7B}{battery}{/color}
```

## Conditional format

Use `{if condition}…{else}…{end}`. The `else` part is optional. Conditions and colors can be nested.

**Add `+` while charging, as in `64%+`:**

```text
{battery}{if charging}+{end}
```

The shorter `{battery}{charging:+}` has the same effect. `{charging:+|-}` shows `+` while charging and `-` otherwise.

**Highlight a low battery:**

```text
{if batteryPercent < 20}{color:#FF5555}{battery}{/color}{else}{battery}{end}
```

**Warn when free storage drops below 5 GiB:**

```text
{if storageFreeGiB < 5}{color:#FFB86C}Low storage: {storage}{/color}{end}
```

**Show the network or an offline message:**

```text
{if networkConnected}{network}{else}Offline{end}
```

**Show weather when available:**

```text
{if weatherAvailable}{weather}{end}
```

**Show a different message at night:**

```text
{if hour >= 22 || hour < 7}Quiet hours{else}Good day{end}
```

Conditions work in **status lines**. Notification, weather, and RSS formats use their own placeholders.

::: details Operators and functions

| Purpose | Syntax |
| --- | --- |
| Compare | `==`, `!=`, `<`, `<=`, `>`, `>=` |
| Combine | `&&`, `\|\|`, `!`, `( )` |
| Calculate | `+`, `-`, `*`, `/`, `%` |
| Check a value | `exists(x)`, `empty(x)` |
| Check text | `contains(s, x)`, `startsWith(s, x)`, `endsWith(s, x)` |

Use variable names without braces inside conditions. Quote text with single or double quotes, such as `network == 'Wi-Fi'`. Empty text, zero, `false`, and missing values are false. Numeric comparisons and arithmetic require numbers.

`exists(x)` checks that a value is present. `empty(x)` checks for missing or empty text. Escape literal braces with `\{` and `\}`.

:::

## Placeholders

| Placeholder | Displays |
| --- | --- |
| `{time}`, `{date}` | `HH:mm` and `yyyy-MM-dd` |
| `{time:HH:mm:ss}` | Time in a custom format |
| `{battery}` | Battery percentage, including `%` |
| `{charging}` | Charging text, empty when not charging |
| `{charging:+}` | Custom text while charging |
| `{memory}`, `{storage}` | Free / total capacity |
| `{network}` | Wi-Fi, mobile, ethernet, connected, or offline |
| `{device}` | Configured device name (`home.device`) |
| `{weather}` | Weather using your chosen format |
| `{right}` | Right-align the rest of the line |

::: details All condition variables

| Group | Variables |
| --- | --- |
| Battery | `batteryPercent`, `charging` |
| Memory | `memoryFreeGiB`, `memoryTotalGiB`, `memoryUsedPercent`, `memoryFreeBytes`, `memoryTotalBytes` |
| Storage | `storageFreeGiB`, `storageTotalGiB`, `storageUsedPercent`, `storageFreeBytes`, `storageTotalBytes` |
| Network | `network`, `networkConnected`, `device` |
| Weather | `weather`, `weatherAvailable`, `weatherError`, `weatherLoading` |
| Clock | `time`, `date`, `year`, `month`, `day`, `weekday`, `hour`, `minute`, `second` |

GiB values use 1,073,741,824 bytes per GiB. Percent values run from 0 to 100. `weekday` is 1 for Monday through 7 for Sunday. Use `exists(batteryPercent)` if the battery value may be unavailable.

:::

## Set or reset from the shell

```sh
config set terminal.statusLines '{time}{right}{battery}{charging:+}'
config reset terminal.statusLines
```

To reset the style without changing the text, run `config reset terminal.statusAppearance` or choose **Reset appearance only** in the editor.
