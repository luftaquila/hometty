# Command reference

Select a command for examples, options, and required permissions. In the terminal, use `help COMMAND` or `COMMAND --help`.

## Apps

| Command | What it does |
| --- | --- |
| [apps](./commands/apps.md#apps) | List, launch, and manage installed apps |
| [alias](./commands/apps.md#alias) | Save a short name for a command |
| [unalias](./commands/apps.md#unalias) | Remove aliases |
| [search](./commands/apps.md#search) | Search the web |
| [open](./commands/apps.md#open) | Open a link or choose a file |
| [share](./commands/apps.md#share) | Share text with another app |

## People

| Command | What it does |
| --- | --- |
| [call](./commands/people.md#call) | Call a contact or phone number |
| [contacts](./commands/people.md#contacts) | Find, add, or edit contacts |

## Device

| Command | What it does |
| --- | --- |
| [wifi](./commands/device.md#wifi) | Open Wi-Fi controls |
| [bluetooth](./commands/device.md#bluetooth) | Open Bluetooth settings |
| [data](./commands/device.md#data) | Open mobile network settings |
| [airplane](./commands/device.md#airplane) | Open airplane mode settings |
| [brightness](./commands/device.md#brightness) | Set screen brightness |
| [volume](./commands/device.md#volume) | Check media volume or change volume and ringer mode |
| [flash](./commands/device.md#flash) | Turn the flashlight on or off |
| [vibrate](./commands/device.md#vibrate) | Vibrate the phone |
| [location](./commands/device.md#location) | Show coordinates and location accuracy |
| [status](./commands/device.md#status) | Show battery, memory, storage, network, and device model |

## Tools

| Command | What it does |
| --- | --- |
| [notes](./commands/tools.md#notes) | Create and manage notes |
| [calc](./commands/tools.md#calc) | Calculate an expression |
| [music](./commands/tools.md#music) | Check or control another app's playback |
| [weather](./commands/tools.md#weather) | Show local weather |
| [rss](./commands/tools.md#rss) | Add, read, and update feeds |

## Launcher

| Command | What it does |
| --- | --- |
| [config](./commands/launcher.md#config) | View and change settings |
| [theme](./commands/launcher.md#theme) | List or apply themes |
| [help](./commands/launcher.md#help) | Show command help |

## Options and arguments

Put options before the first argument value:

```sh
search --engine duckduckgo Android launchers
apps launch --quiet chrome
```

Put multiword arguments in quotes. Use `--` before a value that starts with `-`:

```sh
notes add 'Pick up groceries'
notes add -- '- check batteries'
```

Names such as `ID`, `KEY`, and `APP_ID` in examples are placeholders. Replace them with your own values.

| Option | Use |
| --- | --- |
| `--help`, `-h` | Show command help |
| `--version`, `-V` | Show the hometty version |
| `--quiet`, `-q` | Hide success messages on commands that support it |

Each command's help lists its supported options. In help output, square brackets mark optional arguments, `...` means multiple values, and `|` separates choices.

## Shell commands

For `cd`, pipes, redirection, and bundled tools such as `grep` and `vi`, see [Terminal & shell](../terminal.md).

```sh
busybox --list
busybox wget --help
```
