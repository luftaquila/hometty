# Tool commands

## notes

Create, read, and manage notes.

### Add and read notes

```sh
notes add 'Pick up groceries'
notes
notes -g
```

`notes add` prints the new note's ID. `notes` lists IDs and text, and `notes list` is equivalent. `notes -g` opens the note manager.

### Edit, copy, and remove

Replace `ID` below with the ID printed by `notes add` or shown in `notes`:

```sh
notes set ID 'Pick up groceries and coffee'
notes copy ID
notes rm ID
```

`set` replaces the text. `copy` copies it to the clipboard. `rm` deletes the note.

### Lock a note

```sh
notes lock ID
notes unlock ID
```

A locked note cannot be edited or deleted until it is unlocked. Locking does not encrypt it.

| Option | Use |
| --- | --- |
| `--limit COUNT`, `-n COUNT` | Limit the list to 1–10,000 notes. By default, show all |
| `--gui`, `-g` | Open the note manager |
| `--quiet`, `-q` | Hide success messages for `add`, `set`, `copy`, `rm`, `lock`, and `unlock` |

## calc

Evaluate an arithmetic expression, including powers and functions such as `sqrt()`.

```sh
calc 2 * 3
calc 2 ^ 10
calc 'sqrt(144)'
```

These print `6`, `1024`, and `12`. Put expressions containing parentheses in quotes when passing them from the shell. For an expression starting with a minus sign, use `--`:

```sh
calc -- -2 + 3
```

Run `calc` alone to enter expressions one line at a time. Enter a blank line to exit.

## music

Show playback status or control a player running in another app.

```sh
music
music play
music pause
music next
music previous
```

With no action, `music` prints the player's package name and whether it is playing or paused. Enable notification access and start a player in another app before using these commands.

## weather

Fetch and print weather for your location.

```sh
weather
weather -g
```

Location permission is required. `weather -g` opens weather settings. The default service is wttr.in, which needs no API key. OpenWeather requires your own key:

```sh
weather key 'YOUR_API_KEY'
```

Replace `YOUR_API_KEY` with your key, or enter it in settings to keep it out of shell history. The key is stored separately from settings and is not included in backups.

See [Weather setup](../../tools.md#weather) for services, units, and output formatting.

## rss

Save HTTPS feeds and read their entries.

### Add and list feeds

```sh
rss add android https://android-developers.googleblog.com/feeds/posts/default
rss
rss -g
```

`rss` lists saved feed names and URLs. `rss list` is equivalent. `rss -g` opens the feed manager. Adding a feed with an existing name replaces its URL.

### Read and update

```sh
rss show android
rss update android
rss update
```

- `show` displays the last fetched entries. If none are stored, it fetches the feed first.
- `update NAME` fetches fresh entries for one feed and prints them.
- `update` fetches and prints all saved feeds.

### Remove a feed

```sh
rss rm android
```

`add` and `rm` accept `--quiet` (`-q`) before the feed name. To enable automatic updates, choose an interval, or edit filters and formatting, see [RSS setup](../../tools.md#rss).
