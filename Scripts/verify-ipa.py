#!/usr/bin/env python3
import hashlib
import json
import plistlib
import struct
import sys
import zipfile
from pathlib import Path

archive = Path(sys.argv[1])
with zipfile.ZipFile(archive) as package:
    names = set(package.namelist())
    app = 'Payload/ClearClass.app/'
    widget = app + 'PlugIns/ClearClassWidgets.appex/'
    app_info = plistlib.loads(package.read(app + 'Info.plist'))
    widget_info = plistlib.loads(package.read(widget + 'Info.plist'))
    assert app_info['CFBundleDisplayName'] == '烨昕的课表'
    assert widget_info['CFBundleDisplayName'] == '烨昕的课表小组件'
    assert widget_info['NSExtension']['NSExtensionPointIdentifier'] == 'com.apple.widgetkit-extension'
    assert widget_info['CFBundleIdentifier'].startswith(app_info['CFBundleIdentifier'] + '.')
    assert app_info['ClearClassAppGroup'] == widget_info['ClearClassAppGroup']
    binaries = []
    for directory, info in [(app, app_info), (widget, widget_info)]:
        executable = package.read(directory + info['CFBundleExecutable'])
        # A generic iPhone build must contain a real arm64 Mach-O, not a simulator binary.
        assert executable[:4] == b'\xcf\xfa\xed\xfe', 'Expected thin 64-bit Mach-O'
        assert struct.unpack_from('<I', executable, 4)[0] == 0x0100000C, 'Expected arm64 CPU'
        commands, cursor = struct.unpack_from('<I', executable, 16)[0], 32
        platforms = []
        for _ in range(commands):
            command, size = struct.unpack_from('<II', executable, cursor)
            if command == 0x32: platforms.append(struct.unpack_from('<I', executable, cursor + 8)[0])
            cursor += size
        assert 2 in platforms, 'Expected iOS device platform'
        font_name = 'LXGWWenKai-Regular.ttf' if directory == app else 'LXGWWenKai-Widget.ttf'
        font = package.read(directory + font_name)
        expected = Path(__file__).resolve().parents[1] / 'Resources' / font_name
        assert hashlib.sha256(font).digest() == hashlib.sha256(expected.read_bytes()).digest()
        assert font_name in info['UIAppFonts']
        if directory == widget: assert len(font) < 14 * 1024 * 1024
        binaries.append({'bundle': info['CFBundleIdentifier'], 'executable_bytes': len(executable), 'cpu': 'arm64', 'platform': 'iOS'})
    assert app + 'xlsx.full.min.js' in names and app + 'SpreadsheetImport.js' in names
    report = {'name': app_info['CFBundleDisplayName'], 'version': app_info['CFBundleShortVersionString'],
              'signing': 'unsigned', 'binaries': binaries, 'widget_included': True, 'kai_font_verified': True,
              'ipa_bytes': archive.stat().st_size, 'sha256': hashlib.sha256(archive.read_bytes()).hexdigest()}
    archive.with_suffix('.verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps(report, ensure_ascii=False, indent=2))
