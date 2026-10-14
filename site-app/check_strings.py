"""Fails on duplicate resource names in values/strings.xml: Android only reports them at resource-merge time in CI."""
import re
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "site-app/app/src/main/res/values/strings.xml"
names = re.findall(r'<(?:string|plurals|string-array) name="([^"]+)"', open(path, encoding="utf-8").read())
dups = sorted({n for n in names if names.count(n) > 1})
if dups:
    print("duplicate string names: " + ", ".join(dups))
    sys.exit(1)
print(f"{len(names)} strings, no duplicates")
