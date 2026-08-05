#!/usr/bin/env python3
"""Checks every translation against the English it translates.

Three things go wrong with a translated strings.xml, and none of them is a build
error - the app compiles and then misbehaves in a language nobody on this side of
the screen reads:

  * a key that is not in values/ at all, usually a rename that was not carried
    over. It is dead weight and, worse, it hides the fact that the live string is
    untranslated.
  * a missing key. That falls back to English, which is fine for one string and
    embarrassing for fifty, so this counts them rather than failing: a
    translation in progress is allowed to be incomplete, and the count says how
    incomplete.
  * a format specifier that does not match. %1$s in one language and %1$d in
    another is a crash at the moment that string is shown - String.format sees an
    Integer where it wants a String - and no test that runs in English will ever
    see it. This is the reason the script exists.

Run it after touching any strings.xml:

    scripts/check-strings.py

Exits non-zero on a wrong or unknown key, so it can go in a workflow. Missing
keys are reported and do not fail.
"""

import pathlib
import re
import sys
import xml.etree.ElementTree as ElementTree

RES = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/res"

# %1$s, %2$d, %% - the whole of what Android hands to String.format.
SPECIFIER = re.compile(r"%(?:(\d+)\$)?([a-zA-Z%])")


def specifiers(text: str) -> set:
    """The set of (position, type) a string demands of its caller."""
    found = set()
    for index, (position, kind) in enumerate(SPECIFIER.findall(text), start=1):
        if kind == "%":
            continue
        found.add((position or str(index), kind))
    return found


def strings(path: pathlib.Path) -> dict:
    if not path.exists():
        return {}

    out = {}
    for element in ElementTree.parse(path).getroot():
        name = element.get("name")
        if element.tag == "string":
            out[name] = "".join(element.itertext())
        elif element.tag == "string-array":
            for number, item in enumerate(element):
                out[f"{name}[{number}]"] = "".join(item.itertext())
    return out


def base() -> dict:
    out = {}
    for file in ("strings.xml", "arrays.xml"):
        for name, text in strings(RES / "values" / file).items():
            out[name] = text
    return out


def main() -> int:
    english = base()

    # What no translation should carry, and why each one:
    #   translatable="false" - said so;
    #   a *_values array - Fuse reads those and compares them with strcmp;
    #   an item that is a @string/ reference - it is already translated where it
    #     is defined, and repeating it here would be a second place to forget;
    #   an item with no letter in it - 25%, 100, 256×192: the same in every
    #     language, and a translation of it is only a chance to mistype it;
    #   app_name, and the language list, which is written in each language
    #     itself on purpose.
    untranslatable = {
        element.get("name")
        for file in ("strings.xml", "arrays.xml")
        for element in ElementTree.parse(RES / "values" / file).getroot()
        if element.get("translatable") == "false"
    }
    untranslatable |= {"app_name"}
    untranslatable |= {
        name for name, text in english.items()
        if re.match(r"\w+_values\[", name)
        or name.startswith("language_names[")
        or text.startswith("@string/")
        or not any(character.isalpha() for character in text)
    }

    locales = sorted(p.name[len("values-"):] for p in RES.glob("values-*")
                     if (p / "strings.xml").exists())

    if not locales:
        print("no translations")
        return 0

    wrong = []

    print(f"{'language':<10} {'translated':>12} {'missing':>9} {'unknown':>9} "
          f"{'bad format':>11}")
    print("-" * 56)

    for locale in locales:
        theirs = {}
        for file in ("strings.xml", "arrays.xml"):
            theirs.update(strings(RES / f"values-{locale}" / file))

        unknown = sorted(set(theirs) - set(english))
        missing = sorted(name for name in set(english) - set(theirs)
                         if name not in untranslatable)

        mismatched = []
        for name, text in sorted(theirs.items()):
            if name not in english:
                continue
            theirs_specifiers = specifiers(text)
            if theirs_specifiers != specifiers(english[name]):
                mismatched.append((name, theirs_specifiers))

        for name in unknown:
            wrong.append((locale, name, "not a string this app has"))
        for name, found in mismatched:
            wrong.append((locale, name,
                          f"format is {sorted(found)} where English has "
                          f"{sorted(specifiers(english[name]))}"))

        print(f"{locale:<10} {len(theirs):>12} {len(missing):>9} "
              f"{len(unknown):>9} {len(mismatched):>11}")

    print()

    if wrong:
        for locale, name, why in wrong:
            print(f"error: values-{locale}: {name}: {why}", file=sys.stderr)
        return 1

    print(f"{len(locales)} translations agree with values/")
    return 0


if __name__ == "__main__":
    sys.exit(main())
