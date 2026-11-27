# Terminal & shell

hometty uses BusyBox `ash` for its shell, with support for pipes, redirection, variables, scripts, and interactive programs.

## Keys and text

| Action | How |
| --- | --- |
| Complete a name or path | TAB |
| Previous / next command | Up / Down |
| Stop a running command | CTRL, then C |
| Clear the screen | `clear` or CTRL, then L |
| Move to start / end of input | CTRL, then A / E |
| Select, copy, or paste text | Long-press the terminal |
| Edit or rerun an earlier command | Long-press that command |

The toolbar has APPS, ESC, CTRL, ALT, TAB, and arrow keys. Show or hide it in `config input`.

When **Back recalls commands** is enabled, press Back at an idle prompt to recall the previous command. If the keyboard is open, Back dismisses it first.

## Shell examples

```sh
pwd
ls -la
echo hello | rev
printf 'one\ntwo\n' | grep two
find . -name '*.txt'
```

Create, read, and edit a file in hometty's private folder:

```sh
cd "$HOMETTY_DATA"
echo 'hello from hometty' > hello.txt
cat hello.txt
vi hello.txt
```

In `vi`, press `i` to start editing. Press ESC, type `:wq`, and press Enter to save and quit. Use `:q!` to quit without saving.

## Bundled commands

BusyBox provides **361 commands**, including `grep`, `find`, `sed`, `awk`, `wget`, `tar`, and `vi`.

```sh
busybox --list
busybox wget --help
```

Android's file permissions still apply. Some system utilities need privileges that a normal app does not have.

## Home folder and history

Set **Home folder** in `config terminal`. By default, the shell starts in internal shared storage. If that folder is unavailable, hometty uses its private folder and prints a message.

`$HOMETTY_DATA` points to hometty's private folder. Shell history is saved there in `.sh_history`. Use `config privacy` to turn history on or off and set how many commands to keep.

## Startup file

Add shell settings to `$HOMETTY_DATA/.shrc`:

```sh
vi "$HOMETTY_DATA/.shrc"
```

For example:

```sh
export EDITOR=vi
alias ll='ls -la'
```

The shell loads this file at startup. Start a new shell session to apply your changes.

## Root

The **Allow root** setting permits `su` on an already rooted device. It does not root the phone. Normal app launching and shell use do not require root.
