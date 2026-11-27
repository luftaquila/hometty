# App commands

## apps

List installed apps, launch them, or change how they appear in hometty.

### List and find apps

```sh
apps
apps chrome
apps -al
apps -g
```

With no query, `apps` lists visible apps. A query filters by app name or package name. `apps list` does the same thing.

| Option | Use |
| --- | --- |
| `--all`, `-a` | Include hidden apps |
| `--long`, `-l` | Show app IDs and launch counts |
| `--gui`, `-g` | Open the app list on screen. Takes no query |

You can combine `-a` and `-l` as `-al`. Each `id` includes the app's component and Android profile. Copy the whole value to target that app, including a particular profile.

### Launch an app

Use an app name, package name, or the full ID from `apps -al`:

```sh
apps launch chrome
apps launch com.android.chrome
apps launch 'YouTube Music'
```

These examples require the named apps to be installed. Use TAB to complete a name.

You can also type `chrome` directly. `apps launch` is useful when an app is hidden or its name conflicts with another command.

### Favorites and visibility

```sh
apps favorite chrome
apps hide chrome
apps show chrome
apps reset chrome
```

- `favorite` adds or removes the app from favorites.
- `hide` removes it from the normal list and disables its direct shell command. `apps launch` can still open it.
- `show` makes a hidden app visible again.
- `reset` clears the launch count.

### App settings and removal

```sh
apps info chrome
apps store chrome
apps uninstall chrome
```

`info` opens Android's app info. `store` opens the app's Play Store page. `uninstall` opens Android's uninstall prompt.

`launch`, `favorite`, `hide`, `show`, and `reset` accept `--quiet` (`-q`) before the app name.

## alias

Save a command under a shorter name. Aliases created at the prompt remain available after restarting hometty.

```sh
alias cr='chrome'
cr
```

Run `alias` to list aliases, or give a name to inspect one:

```sh
alias
alias cr
```

Run `alias NAME='COMMAND'` again to change an alias. Without parameter references, any arguments are added to the end of its command:

```sh
alias web='search'
web Android launchers
```

For arguments such as `$1` and `"$@"`, see [Apps & aliases](../../apps-and-aliases.md#pass-arguments). Names can contain letters, numbers, underscores, and hyphens. Launcher commands and shell builtins are reserved.

## unalias

Remove one or more aliases by name:

```sh
unalias cr
unalias web launch
```

Use `alias` to see the names you have saved.

## search

Search using your chosen search engine.

```sh
search Android launchers
search --engine duckduckgo shell scripting
search -e youtube BusyBox tutorial
```

Without `--engine` (`-e`), the command uses `apps.searchEngine`, which defaults to `google`. Accepted engines are `google`, `duckduckgo`, and `youtube`.

## open

Open a URL in the app Android assigns to it. With no argument, open the file picker.

```sh
open https://github.com/luftaquila/hometty
open
```

Supported URL schemes are `https`, `http`, `mailto`, `tel`, and `geo`. Put URLs containing shell characters such as `&` in quotes.

## share

Send text to Android's share sheet, where you choose the receiving app.

```sh
share 'See you at 6!'
```

Put the text in quotes so it is passed as one argument.
