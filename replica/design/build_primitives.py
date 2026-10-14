#!/usr/bin/env python3
"""Build primitives.html, a self-contained page of every primitive in light and dark.

    python3 replica/design/gen_tokens.py && python3 replica/design/build_primitives.py
    chromium --headless --screenshot=replica/design/primitives.png --window-size=896,6000 replica/design/primitives.html
"""

import base64
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))


def read(*p):
    with open(os.path.join(HERE, *p)) as f:
        return f.read()


def icon(name, size=""):
    svg = read("icons", name + ".svg")
    svg = re.sub(r"<!--.*?-->", "", svg, flags=re.S).strip()
    cls = "i sm" if size == "sm" else "i"
    return svg.replace('class="lucide', f'aria-hidden="true" class="{cls} lucide', 1)


def fonts_css():
    def inline(m):
        data = base64.b64encode(open(os.path.join(HERE, "fonts", m.group(1)), "rb").read()).decode()
        return f"url(data:font/woff2;base64,{data})"
    return re.sub(r"url\(\./([^)]+)\)", inline, read("fonts", "fonts.css"))


def main():
    src = read("primitives.src.html")
    page, phone = src.split("<!--PHONE-->")
    page = page.replace("{{css}}", fonts_css() + "\n" + read("tokens.css"))
    for theme in ("light", "dark"):
        page = page.replace("{{phone:%s}}" % theme, phone.replace("{{theme}}", theme))
    page = re.sub(r"\{\{icon:([a-z0-9-]+)(?::(sm))?\}\}", lambda m: icon(m.group(1), m.group(2) or ""), page)
    left = re.findall(r"\{\{[^}]+\}\}", page)
    if left:
        raise SystemExit("unfilled placeholders: %s" % left)
    with open(os.path.join(HERE, "primitives.html"), "w") as f:
        f.write(page)
    print("wrote replica/design/primitives.html (%d KB)" % (len(page) // 1024))


if __name__ == "__main__":
    main()
