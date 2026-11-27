# Getting started

## Installation

1. Install hometty from [Google Play](https://play.google.com/store/apps/details?id=io.luftaquila.hometty) or a [GitHub release](https://github.com/luftaquila/hometty/releases). Android 10 or later is required.
2. Open hometty and select it as your default home app when prompted. You can also change this in Android's **Default apps → Home app** settings.
3. Run `config` and open **Permissions and setup**. Use the table below to choose which permissions to grant.

| Feature | Access needed |
| --- | --- |
| Notifications in the terminal | Notification access |
| Weather and `location` | Location |
| Looking up contacts | Contacts |
| Placing calls with `call` | Phone |
| Changing brightness | Modify system settings |

## First commands

Run each command on its own line. If Chrome is installed, these commands list your apps and open it:

```sh
apps
chrome
```

Return to hometty and create an alias to launch Chrome as `cr`:

```sh
alias cr='chrome'
cr
```

Try a calculation and a shell pipeline:

```sh
calc 2 ^ 10
echo hello | rev
```

The results are `1024` and `olleh`.

## Basic controls

- `help` lists launcher commands. `help apps` shows how to use `apps`.
- `config` opens settings. `config terminal` opens the terminal settings.
- **TAB** completes names. The **APPS** toolbar button opens the app list.
- Long-press the terminal to open settings, select text, or copy and paste.

See [Apps & aliases](./apps-and-aliases.md) and [Terminal & shell](./terminal.md) for more examples.
