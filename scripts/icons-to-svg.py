#!/usr/bin/env python3
"""Renders the app's icon drawables as SVGs for docs/USING.md.

GitHub cannot draw an Android vector drawable, and docs/USING.md names every
row of the quick bar by the icon that sits beside it - so the icons have to
exist twice. Generated rather than drawn, because the second copy is the one
that goes stale: an icon changed in res/drawable and not here would leave the
guide illustrating a button that no longer looks like that, and nothing would
fail.

    scripts/icons-to-svg.py            # rewrite docs/icons/
    scripts/icons-to-svg.py --check    # fail if it would differ

The conversion is exact rather than approximate, and only because these
drawables stay inside the part of the format that maps onto SVG one for one:
<path> with pathData, fillColor, strokeColor, strokeWidth, strokeLineCap and
strokeLineJoin, no <group>, no clip path, no inline aapt resource. That is
asserted here - an icon using anything else stops the run rather than being
quietly half-drawn.

One deliberate difference from the app: the drawables are #ffffff, tinted by
the row that draws them, and white on GitHub's light theme is an invisible
icon. They are written out mid-grey, which reads on both themes.
"""

import os
import sys
import xml.etree.ElementTree as ElementTree

ANDROID = "{http://schemas.android.com/apk/res/android}"

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DRAWABLES = os.path.join(HERE, "app", "src", "main", "res", "drawable")
ICONS = os.path.join(HERE, "docs", "icons")

# What the guide draws. Named rather than globbed: res/drawable holds
# backgrounds and launcher art too, and a docs folder that grew whatever was
# added next to them would be a folder nobody could account for.
WANTED = """
bolt bookmark border camera chip close controls crt display fast_forward file
film folder fullscreen indicators info joystick keyboard load manual menu mouse
pause play record reset save scanlines signal stop swap turbo
""".split()

# The app tints its icons at draw time; a static file cannot be tinted.
TINT = "#888888"

KNOWN = {"pathData", "fillColor", "strokeColor", "strokeWidth",
         "strokeLineCap", "strokeLineJoin"}

STROKE_ATTRIBUTES = (("strokeWidth", "stroke-width"),
                     ("strokeLineCap", "stroke-linecap"),
                     ("strokeLineJoin", "stroke-linejoin"))


def colour(value):
    """A drawable's colour as SVG's. Fully transparent is SVG's "none"."""
    if value is None:
        return None
    if value.lower() in ("#00000000", "@android:color/transparent"):
        return "none"
    if value.lower() == "#ffffff":
        return TINT
    return value


def convert(name):
    """One drawable as SVG text, or an exception naming what it uses."""
    path = os.path.join(DRAWABLES, "ic_%s.xml" % name)
    root = ElementTree.parse(path).getroot()

    if not root.tag.endswith("vector"):
        raise ValueError("%s is not a vector drawable" % path)

    drawn = []
    for element in root:
        tag = element.tag.split("}")[-1]
        if tag != "path":
            raise ValueError("%s has a <%s>, which this cannot convert"
                             % (path, tag))

        for attribute in element.attrib:
            if not attribute.startswith(ANDROID):
                continue
            short = attribute[len(ANDROID):]
            if short not in KNOWN:
                raise ValueError("%s uses %s, which this cannot convert"
                                 % (path, short))

        parts = ['d="%s"' % element.get(ANDROID + "pathData")]
        parts.append('fill="%s"' % (colour(element.get(ANDROID + "fillColor"))
                                    or "none"))

        stroke = colour(element.get(ANDROID + "strokeColor"))
        if stroke and stroke != "none":
            parts.append('stroke="%s"' % stroke)
            for android, svg in STROKE_ATTRIBUTES:
                value = element.get(ANDROID + android)
                if value:
                    parts.append('%s="%s"' % (svg, value))

        drawn.append("  <path " + " ".join(parts) + "/>")

    return ('<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24"'
            ' viewBox="0 0 %s %s">\n%s\n</svg>\n'
            % (root.get(ANDROID + "viewportWidth"),
               root.get(ANDROID + "viewportHeight"),
               "\n".join(drawn)))


def main():
    checking = "--check" in sys.argv[1:]
    if not checking:
        os.makedirs(ICONS, exist_ok=True)

    stale = []
    for name in WANTED:
        svg = convert(name)
        target = os.path.join(ICONS, "%s.svg" % name)

        if checking:
            current = None
            if os.path.exists(target):
                with open(target) as handle:
                    current = handle.read()
            if current != svg:
                stale.append(name)
            continue

        with open(target, "w") as handle:
            handle.write(svg)

    if checking and stale:
        print("docs/icons is out of date: " + ", ".join(stale))
        print("run scripts/icons-to-svg.py")
        return 1

    print("%d icons %s" % (len(WANTED), "checked" if checking else "written"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
