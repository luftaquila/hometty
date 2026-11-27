# Weather, RSS & tools

## Weather

Run `config weather` to choose a service. Allow location access in **Permissions and setup**.

| Service | Setup |
| --- | --- |
| wttr.in | No API key required |
| OpenWeather | Enter your own API key in weather settings |
| Off | Disable weather requests |

```sh
weather
config set weather.units imperial
```

Choose `metric` for Celsius, `imperial` for Fahrenheit, or `standard` for Kelvin. The refresh interval controls weather updates in [status lines](./status-lines.md).

The default weather format is:

```text
{city} · {temp}{unit} · {description}
```

Use `{city}` and `{district}` for the location, `{temp}` and `{unit}` for temperature, and `{description}` for conditions.

You can also save an OpenWeather key with `weather key 'YOUR_API_KEY'`. Enter it in settings to keep it out of shell history.

## RSS

Add an HTTPS feed, fetch it, and read it:

```sh
rss add android https://android-developers.googleblog.com/feeds/posts/default
rss update android
rss show android
```

`rss` lists your feeds. `rss -g` opens the feed manager. `rss rm android` removes a feed.

`rss show` uses the last fetched entries if available. Use `rss update` to fetch fresh entries. See [rss](./reference/commands/tools.md#rss) for all commands.

Run `config rss` to enable automatic updates, set the interval, or limit updates to Wi-Fi. Edit individual feeds to change their filters and format.

Feed formats accept `{title}`, `{text}`, `{url}`, and `{date}`. The default shows the title and URL on separate lines.

## Notes

```sh
notes add 'Pick up groceries'
notes
notes -g
```

Replace `ID` below with the ID printed by `notes add` or shown in `notes`:

```sh
notes set ID 'Pick up groceries and coffee'
notes copy ID
notes lock ID
notes unlock ID
notes rm ID
```

A lock prevents editing and deletion. It does not encrypt the note. See [notes](./reference/commands/tools.md#notes) for options.

## Calculator

```sh
calc 2 * 3
calc 2 ^ 10
calc 'sqrt(144)'
```

Run `calc` without an expression to calculate one line at a time. Enter a blank line to exit. When passing an expression from the shell, put it in quotes if it contains parentheses.

## Search, links, and sharing

```sh
search Android launchers
search --engine duckduckgo shell scripting
open https://github.com/luftaquila/hometty
share 'See you at 6!'
```

`open` without a URL opens Android's file picker.

## Music

`music` controls playback in another app. Enable notification access and start a music app first. Use `music play`, `music pause`, `music next`, or `music previous`.
