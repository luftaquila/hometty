# BusyBox

Gradle downloads the sources pinned by commit and SHA-256 in `sources.json`, applies
`hometty.patch` and `config.fragment`, and builds with NDK 28.2.13676358.
Python 3.12+ and `patch` are required.
Downloads, sources and binaries stay under `terminal/build/busybox/`.
PCRE2 uses a SHA-256 of sorted paths, modes, link targets and file hashes because
its archive server changes tar timestamps on each download.

```sh
python3 busybox/build.py --ndk "$ANDROID_HOME/ndk/28.2.13676358" --output terminal/build/busybox
```

Sources: [BusyBox 1.36.1](https://github.com/topjohnwu/ndk-busybox),
[libselinux](https://github.com/topjohnwu/selinux),
[PCRE2](https://android.googlesource.com/platform/external/pcre), and build files from
[Magisk v27.0](https://github.com/topjohnwu/Magisk/tree/v27.0/native/src/external).

- `Android.mk` and `Application.mk`: three Android ABIs, static linking, 16 KB alignment,
  and compiler options (including the `vi` fix for NDK r28).
- `config.fragment`: only our three configuration overrides. `build.py` starts from
  the Android port's embedded config and regenerates the config headers at build time.
- `hometty.patch`: only C changes for resize, prompt colors, tab completion, and Enter
  markers so tapping a wrapped command reruns the whole command.

CI includes the generated `terminal/build/busybox/source.tar.gz` with its APK artifacts.
Keep this archive with distributed APKs: it contains the patched sources, licenses
and build recipe. It is not stored in Git. To rebuild it without network access,
extract it, then run from the extracted directory:

```sh
SOURCE_ROOT="$PWD/source"
mkdir -p build
BUILD_ROOT="$PWD/build"
cd "$SOURCE_ROOT/busybox"
"$ANDROID_HOME/ndk/28.2.13676358/ndk-build" \
  NDK_PROJECT_PATH="$BUILD_ROOT" APP_BUILD_SCRIPT="$SOURCE_ROOT/Android.mk" \
  NDK_APPLICATION_MK="$SOURCE_ROOT/Application.mk" -j8
```
