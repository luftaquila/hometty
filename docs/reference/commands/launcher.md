# Launcher commands

## config

Open settings, inspect current values, or change them from the shell.

### Open settings

```sh
config
config terminal
```

With no category, open the settings overview. Categories are `appearance`, `home`, `input`, `apps`, `terminal`, `notifications`, `notes`, `rss`, `weather`, and `privacy`.

### Read values

```sh
config list
config list appearance.
config get appearance.fontSize
```

`list` prints setting keys and their current values. Give a prefix to narrow the list. `get` prints just one setting's value.

### Change or reset a value

```sh
config set appearance.fontSize 16
config set home.prompt '{user}@{device}:{cwd} $'
config reset appearance.fontSize
```

`reset` restores that setting's default value. Use the full key and put values containing spaces or shell symbols in quotes. `set` and `reset` accept `--quiet` (`-q`) before the key.

See [Settings reference](../settings.md) for defaults and allowed values. Fonts are imported through the font screen. Use [Backup & restore](../../troubleshooting.md#back-up-and-restore) to save settings and data to a file.

## theme

List available themes or apply a preset.

```sh
theme
theme set dracula
```

With no action, print preset names. `theme set` applies the preset and its colors. Dracula is the default. You can adjust individual colors afterward in `config appearance`.

## help

List launcher commands or show the syntax and options for one command.

```sh
help
help apps
apps --help
```

`help apps` and `apps --help` show the same command reference. For a specific action, use `apps launch --help`.

For a bundled shell utility, use its own help, such as `busybox wget --help`.
