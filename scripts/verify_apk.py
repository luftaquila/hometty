#!/usr/bin/env python3
"""Check the three supported ABIs, native LOAD alignment and APK ZIP alignment."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('apk', type=Path)
parser.add_argument('--readelf', required=True, type=Path)
parser.add_argument('--zipalign', required=True, type=Path)
args = parser.parse_args()
expected = {'arm64-v8a', 'armeabi-v7a', 'x86_64'}
native = []
with zipfile.ZipFile(args.apk) as archive, tempfile.TemporaryDirectory() as temp:
    paths = [name for name in archive.namelist() if name.startswith('lib/') and name.endswith('.so')]
    assert {name.split('/')[1] for name in paths} == expected, 'Unexpected APK ABI set'
    for abi in expected:
        for name in ('libbusybox.so', 'libhomettyterminal.so', 'libhomettycli.so'):
            assert f'lib/{abi}/{name}' in paths, f'Missing {abi}/{name}'
    for name in paths:
        file = Path(temp) / 'native.so'
        file.write_bytes(archive.read(name))
        headers = subprocess.check_output([str(args.readelf), '-l', str(file)], text=True)
        alignment = [line.split()[-1] for line in headers.splitlines() if line.strip().startswith('LOAD')]
        assert alignment and all(int(value, 16) >= 16384 for value in alignment), (name, alignment)
        native.append({'file': name, 'load_alignment': alignment})
subprocess.run([str(args.zipalign), '-c', '-P', '16', '4', str(args.apk)], check=True)
print(json.dumps({'apk': args.apk.name, 'bytes': args.apk.stat().st_size,
                  'sha256': hashlib.sha256(args.apk.read_bytes()).hexdigest(),
                  'native': native, 'zipalign': True}, indent=2))
