# Apps & aliases

## Open an app

Type an app's name or package name. Use **TAB** to complete it.

```sh
chrome
com.android.chrome
apps launch 'YouTube Music'
```

If a name is ambiguous or conflicts with a command, use `apps launch` with the package name. Quote app names that contain spaces.

```sh
apps -al
apps launch com.android.chrome
```

`apps -al` lists all apps, including hidden ones, with their IDs and launch counts. `apps -g` opens the app list on screen.

An app ID includes its component and Android profile. You can pass the whole ID to `apps launch` to select a particular app and profile.

## Manage apps

| Command | Result |
| --- | --- |
| `apps favorite chrome` | Add to or remove from favorites |
| `apps hide chrome` | Hide the app and disable its direct shell command |
| `apps show chrome` | Show it again |
| `apps reset chrome` | Reset its launch count |
| `apps info chrome` | Open Android's app info |
| `apps store chrome` | Open its Play Store page |
| `apps uninstall chrome` | Open Android's uninstall prompt |

Hidden apps still open with `apps launch`. See the [apps reference](./reference/commands/apps.md#apps) for options and examples.

## Save an alias

```sh
alias cr='chrome'
cr
```

Aliases created at the prompt are saved automatically, so they still work after restarting hometty.

```sh
alias           # list aliases
alias cr        # show one alias
unalias cr      # remove it
```

To change an alias, run `alias` again with the same name and a new command. You can also edit aliases in `config`.

## Pass arguments

By default, arguments are added to the end of the alias command:

```sh
alias web='search'
web Android launchers
```

Use `$1` and `$2` for individual arguments, or `"$@"` for all arguments. Put the alias body in single quotes so the shell expands these values when you run the alias, not when you save it:

```sh
alias launch='apps launch "$1"'
launch 'YouTube Music'
```

Alias names can contain letters, numbers, underscores, and hyphens. You cannot use the name of a launcher command or shell builtin.

Aliases defined inside [`.shrc`](./terminal.md#startup-file) are ordinary shell aliases, separate from the saved launcher aliases.
