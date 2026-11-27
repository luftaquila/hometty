# hometty

A CLI shell launcher for Android. Your home screen is a shell.

Inspired by [T-UI](https://github.com/fandreuz/TUI-ConsoleLauncher).

- Open apps by name or package name, with tab completion
- Notifications show up in the terminal
- Runs BusyBox ash that supports pipes, redirection, history and even `vi`
- Notes, RSS feeds, weather and status lines

## Usage

Install the APK (Android 10+) and set hometty as your home app.

Type `help` to see the commands and `config` to change settings.

[Privacy policy](https://luftaquila.github.io/hometty/privacy)

```sh
apps -al
chrome
alias work='apps launch ${1}'
calc 2 * 3
weather
```

## Building

Requires JDK 26, Python 3.12+, `patch`, and the Android SDK with NDK 28.2.13676358 and CMake 3.22.1.

```sh
./gradlew :launcher:assembleDebug                  # debug APK
./gradlew :core:test :launcher:testDebugUnitTest   # unit tests
./gradlew :launcher:connectedDebugAndroidTest      # device tests
```

The first build downloads and compiles BusyBox using the pinned [build recipe](busybox/README.md).

Release downloads include the matching BusyBox source archive with patched sources,
licenses and offline build instructions.
