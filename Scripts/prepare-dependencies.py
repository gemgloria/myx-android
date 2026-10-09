#!/usr/bin/env python3
"""Fetch pinned, hash-checked build resources. The installed app is fully offline."""
from pathlib import Path
from urllib.request import Request, urlopen
import hashlib
import json

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / 'Resources/Build-dependencies.json'
for item in json.loads(MANIFEST.read_text()):
    destination = ROOT / item['path']
    expected = item['sha256']
    if destination.exists() and hashlib.sha256(destination.read_bytes()).hexdigest() == expected:
        print('Verified:', destination.name)
        continue
    data = urlopen(Request(item['url'], headers={'User-Agent': 'YexinTimetable-build/0.2'}), timeout=120).read()
    if hashlib.sha256(data).hexdigest() != expected:
        raise SystemExit('Dependency hash mismatch: ' + destination.name)
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + '.download')
    temporary.write_bytes(data)
    temporary.replace(destination)
    print('Downloaded and verified:', destination.name, len(data), 'bytes')
