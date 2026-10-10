#!/usr/bin/env python3
"""Keep everyday Chinese and punctuation in a smaller, bundled widget font."""
from pathlib import Path
import hashlib
import json
from fontTools import subset
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parents[1]
source = ROOT / 'Resources/LXGWWenKai-Regular.ttf'
destination = ROOT / 'Resources/LXGWWenKai-Widget.ttf'
assert hashlib.sha256(source.read_bytes()).hexdigest() == '39ad71264b588165b469e35e6afb162a378dacd1f95348160240ba9038ac3009'
font = TTFont(source, recalcTimestamp=False)
options = subset.Options()
options.recalc_timestamp = False
options.name_IDs = ['*']
options.name_legacy = True
options.name_languages = ['*']
subsetter = subset.Subsetter(options=options)
unicodes = list(range(0x100)) + list(range(0x2000, 0x2070)) + list(range(0x3000, 0x3100))
unicodes += list(range(0x4e00, 0xa000)) + list(range(0xff00, 0xfff0))
subsetter.populate(unicodes=unicodes)
subsetter.subset(font)
font.save(destination)
assert destination.stat().st_size < source.stat().st_size * .55
assert set(map(ord, '烨昕的课表新能源专业英语太阳能利用概论教三北413')) <= set(font.getBestCmap())
print(json.dumps({'widget_font_bytes': destination.stat().st_size,
                  'sha256': hashlib.sha256(destination.read_bytes()).hexdigest()}))
