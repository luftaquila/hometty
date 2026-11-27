#!/usr/bin/env python3
"""Download, verify, patch and build the pinned Android/Bionic BusyBox."""
import argparse
import ast
import bz2
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import time
import urllib.error
import urllib.request


HERE = Path(__file__).resolve().parent
ABIS = ('arm64-v8a', 'armeabi-v7a', 'x86_64')
RECIPE = ('build.py', 'sources.json', 'hometty.patch', 'config.fragment', 'Android.mk', 'Application.mk', 'README.md')


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def tree_sha256(path):
    # Gitiles regenerates tar timestamps on every request. Hash the complete tree,
    # including paths, modes and link targets, but not container metadata.
    entries = []
    with tarfile.open(path) as tar:
        for entry in tar.getmembers():
            digest = ''
            if entry.isfile():
                with tar.extractfile(entry) as stream:
                    digest = hashlib.file_digest(stream, 'sha256').hexdigest()
            entries.append([entry.name, entry.mode, entry.type.decode('ascii'), entry.linkname, digest])
    return hashlib.sha256(json.dumps(sorted(entries), separators=(',', ':')).encode()).hexdigest()


def download(item, cache):
    destination = cache / item['name']
    digest = tree_sha256 if 'tree_sha256' in item else sha256
    expected = item.get('tree_sha256', item.get('sha256'))
    if destination.is_file() and digest(destination) == expected:
        return destination
    cache.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + '.part')
    for attempt in range(3):
        try:
            print(f"Downloading {item['name']}", flush=True)
            with urllib.request.urlopen(item['url'], timeout=60) as response, temporary.open('wb') as out:
                shutil.copyfileobj(response, out)
            if digest(temporary) != expected:
                raise ValueError(f"Source checksum mismatch: {item['name']}")
            temporary.replace(destination)
            return destination
        except (urllib.error.URLError, TimeoutError):
            if attempt == 2:
                raise
            time.sleep(attempt + 1)
        finally:
            temporary.unlink(missing_ok=True)


def extract(archive, destination, strip_root):
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive) as tar:
        members = tar.getmembers()
        if strip_root:
            roots = {entry.name.split('/')[0] for entry in members}
            if len(roots) != 1:
                raise ValueError(f'Expected one source directory in {archive.name}')
            stripped = []
            for entry in members:
                _, _, name = entry.name.partition('/')
                if not name:
                    continue
                entry.name = name
                stripped.append(entry)
            members = stripped
        # The upstream trees contain relative symlinks. The data filter permits these
        # but rejects links or paths escaping the destination, and special devices.
        tar.extractall(destination, members=members, filter='data')


def configure(source):
    """Apply our three settings to the Android port's pinned, preconfigured headers."""
    busybox = source / 'busybox'
    options = busybox / 'include/bbconfigopts.h'
    header = options.read_text()
    # The Android port ships its actual config in this generated header; its old
    # configs/*defconfig files describe different builds and cannot reproduce it.
    config = ''.join(ast.literal_eval(line) for line in header.splitlines() if line.startswith('"'))
    autoconf = busybox / 'include/autoconf.h'
    macros = autoconf.read_text()
    for setting in (HERE / 'config.fragment').read_text().splitlines():
        key, value = setting.split('=', 1)
        config, count = re.subn(rf'^(?:{key}=.*|# {key} is not set)$', setting, config, flags=re.M)
        if count != 1:
            raise ValueError(f'Expected one upstream setting: {key}')
        name = key.removeprefix('CONFIG_')
        replacement = (
            f'#define {key} {"1" if value == "y" else value}\n'
            f'#define ENABLE_{name} 1\n'
            f'#ifdef MAKE_SUID\n# define IF_{name}(...) __VA_ARGS__ "{key}"\n'
            f'#else\n# define IF_{name}(...) __VA_ARGS__\n#endif\n'
            f'#define IF_NOT_{name}(...)\n'
        )
        macros, count = re.subn(
            rf'^#(?:define|undef) {key}\b[^\n]*\n.*?^#define IF_NOT_{name}\(\.\.\.\)[^\n]*\n',
            lambda _: replacement, macros, flags=re.M | re.S,
        )
        if count != 1:
            raise ValueError(f'Expected one upstream config macro block: {key}')
    autoconf.write_text(macros)
    (busybox / '.config').write_text(config)
    prefix = header.split('static const char bbconfig_config[] ALIGN1 =\n', 1)[0]
    options.write_text(prefix + 'static const char bbconfig_config[] ALIGN1 =\n'
                      + ''.join(json.dumps(line + '\n') + '\n' for line in config.splitlines())
                      + ';\n#endif\n')
    # Same payload as upstream scripts/mkconfigs: bzip2 level 1, minus the BZ header.
    packed = bz2.compress(config.encode(), compresslevel=1)[2:]
    compressed = busybox / 'include/bbconfigopts_bz2.h'
    prefix = compressed.read_text().split('static const char bbconfig_config_bz2[] ALIGN1 = {\n', 1)[0]
    compressed.write_text(prefix + 'static const char bbconfig_config_bz2[] ALIGN1 = {\n'
                          + ''.join(','.join(f'0x{b:02x}' for b in packed[i:i + 16]) + ',\n'
                                    for i in range(0, len(packed), 16))
                          + '};\n#endif\n')
    for name in ('Android.mk', 'Application.mk'):
        shutil.copy2(HERE / name, source / name)


def prepare(output, ndk):
    source = output / 'source'
    stamp = output / 'source.sha256'
    key = hashlib.sha256()
    for path in (*(HERE / name for name in RECIPE if name != 'README.md'), ndk / 'source.properties'):
        key.update(path.read_bytes())
    key.update(str(ndk).encode())
    build_key = key.hexdigest()
    if source.is_dir() and stamp.is_file() and stamp.read_text() == build_key:
        return source

    # Download first, so a network failure leaves the previous source tree intact.
    sources = json.loads((HERE / 'sources.json').read_text())
    archives = [(item, download(item, output / 'downloads')) for item in sources]
    stamp.unlink(missing_ok=True)
    for cached in (source, output / 'obj', output / 'libs', output / 'jniLibs'):
        if cached.exists():
            shutil.rmtree(cached)
    source.mkdir(parents=True)
    for item, archive in archives:
        destination = source / item['destination']
        if item['name'].endswith('.tar.gz'):
            extract(archive, destination, item['strip_root'])
        else:
            shutil.copy2(archive, destination)
    subprocess.run(
        ['patch', '--batch', '--forward', '-p1', '-i', str(HERE / 'hometty.patch')],
        cwd=source, check=True,
    )
    configure(source)
    stamp.write_text(build_key)
    return source


def bundle_source(source, output):
    # Generated beside the APK, never committed. Include licenses and the build recipe
    # so the exact patched source can be built without downloading upstream again.
    archive = output / 'source.tar.gz'
    temporary = output / 'source.tar.gz.part'
    with tarfile.open(temporary, 'w:gz', dereference=False) as tar:
        tar.add(source, arcname='source')
        for name in RECIPE:
            tar.add(HERE / name, arcname=f'recipe/{name}')
    temporary.replace(archive)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    ndk = args.ndk.resolve()
    output = args.output.resolve()
    source = prepare(output, ndk)
    bundle_source(source, output)
    subprocess.run([
        str(ndk / 'ndk-build'),
        f'NDK_PROJECT_PATH={output}',
        f'APP_BUILD_SCRIPT={source / "Android.mk"}',
        f'NDK_APPLICATION_MK={source / "Application.mk"}',
        f'-j{min(8, os.cpu_count() or 1)}',
    ], cwd=source / 'busybox', check=True)
    for abi in ABIS:
        destination = output / 'jniLibs' / abi / 'libbusybox.so'
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(output / 'libs' / abi / 'busybox', destination)


if __name__ == '__main__':
    main()
