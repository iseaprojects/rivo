"""Report TXT_* keys referenced from Kotlin that the English pack does not define."""

import io
import json
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EN = os.path.join(ROOT, "app", "src", "main", "assets", "extensions", "langs", "en.lang.json")

with io.open(EN, encoding="utf-8") as handle:
    defined = set(json.load(handle)["strings"].keys())

used = set()
pattern = re.compile(r'"(TXT_[A-Z0-9_]+)"')
for base, _, files in os.walk(os.path.join(ROOT, "app", "src")):
    for name in files:
        if not name.endswith(".kt"):
            continue
        with io.open(os.path.join(base, name), encoding="utf-8") as handle:
            used.update(pattern.findall(handle.read()))

missing = sorted(used - defined)
print(f"{len(used)} referenced, {len(defined)} defined, {len(missing)} missing")
for key in missing:
    print(key)
