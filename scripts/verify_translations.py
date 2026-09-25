#!/usr/bin/env python3
"""Check locale coverage, positional placeholders and metadata resource keys."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]

def read(locale):
    return {node.attrib['name']: node for node in ET.parse(root / f'resources/src/main/res/{locale}/strings.xml').getroot()}

english, korean = read('values'), read('values-ko')
assert english.keys() == korean.keys(), 'Locale key sets differ'
placeholder = re.compile(r'%\d+\$[sdif]')
for key, node in english.items():
    translated = korean[key]
    assert node.tag == translated.tag, key
    originals = [node.text or ''] if node.tag == 'string' else [item.text or '' for item in node]
    translations = [translated.text or ''] if node.tag == 'string' else [item.text or '' for item in translated]
    for text in originals + translations:
        assert text.strip('"').strip(), f'Empty translation: {key}'
        assert sorted(placeholder.findall(text)) == sorted(placeholder.findall(originals[0])), f'Placeholder mismatch: {key}'
    if node.tag == 'plurals':
        assert any(item.attrib['quantity'] == 'other' for item in translated), key
    assert not re.search('[가-힣]', originals[0]), f'Korean text in English resource: {key}'
for path in (root / 'core/src/main/kotlin').rglob('*.kt'):
    for key in re.findall(r'"((?:label|message|error)_[a-z0-9_]+)"', path.read_text()):
        assert key in english, f'Missing metadata resource: {path.name}: {key}'
# Keys built in code: setting descriptions (title + "_desc") and collection descriptions.
settings = (root / 'core/src/main/kotlin/io/luftaquila/hometty/core/Settings.kt').read_text()
for title in re.findall(r'"(label_set_[a-z0-9_]+)"', settings):
    assert title + '_desc' in english, f'Missing setting description: {title}_desc'
collections = (root / 'core/src/main/kotlin/io/luftaquila/hometty/core/Collections.kt').read_text()
for kind in re.findall(r'CollectionSpec\(\s*"([A-Za-z]+)"', collections):
    key = 'label_collection_' + re.sub('([A-Z])', r'_\1', kind).lower() + '_desc'
    assert key in english, f'Missing collection description: {key}'
print(f'{len(english)} resource keys: English/Korean coverage and placeholders verified')
