#!/usr/bin/env python3
"""Checks every translation against the English it translates.

Four things go wrong with a translated strings.xml, and none of them is a build
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
  * a <plurals> whose forms are wrong for the language, or that one language
    writes as a <string>. See plurals() for what can be checked and what cannot.

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


# Android's six, from CLDR. A quantity outside this set is dropped silently at
# build time, so the form is simply never shown.
QUANTITIES = {"zero", "one", "two", "few", "many", "other"}


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


def plurals(path: pathlib.Path) -> dict:
    """name -> {quantity: text}, for every <plurals> in the file.

    A plural cannot be checked the way a string-array is - form for form against
    English - because which forms a language *has* is a property of the language:
    English distinguishes one from other and nothing else, Russian and Polish
    need few and many as well, and Czech uses many only for fractions. A Russian
    translation with four forms where English has two is correct, not a mismatch,
    so this returns the forms and lets the caller compare what is comparable.
    """
    if not path.exists():
        return {}

    return {
        element.get("name"): {
            item.get("quantity"): "".join(item.itertext()) for item in element
        }
        for element in ElementTree.parse(path).getroot()
        if element.tag == "plurals"
    }


def base() -> dict:
    out = {}
    for file in ("strings.xml", "arrays.xml"):
        for name, text in strings(RES / "values" / file).items():
            out[name] = text
    return out


def base_plurals() -> dict:
    out = {}
    for file in ("strings.xml", "arrays.xml"):
        out.update(plurals(RES / "values" / file))
    return out


def demanded(forms: dict) -> set:
    """What every form of a plural must hand String.format.

    All of English's forms take the same arguments - "%1$d field" and "%1$d
    fields" - so one set describes the plural, and each translated form is
    checked against it whatever quantity it carries.
    """
    return set().union(*(specifiers(text) for text in forms.values()))


def main() -> int:
    english = base()
    english_plurals = base_plurals()

    # A plural whose own forms disagree makes the per-language check below
    # meaningless, so English is checked against itself first.
    for name, forms in sorted(english_plurals.items()):
        found = {frozenset(specifiers(text)) for text in forms.values()}
        if len(found) > 1:
            print(f"error: values/: {name}: its own forms take different "
                  f"arguments: {[sorted(f) for f in found]}", file=sys.stderr)
            return 1
        if "other" not in forms:
            print(f"error: values/: {name}: no other form, which is the one "
                  f"Android falls back to", file=sys.stderr)
            return 1

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
    # An array is one element and many keys. A <string-array> that says
    # translatable="false" puts its own name in the set above, but this file
    # names its items name[0], name[1] and so on, so the exclusion never
    # reached them and every item counted as missing in all eight languages
    # for ever. Add the items as well as the array.
    untranslatable |= {
        name for name in english
        if "[" in name and name[:name.index("[")] in untranslatable
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
        theirs_plurals = {}
        for file in ("strings.xml", "arrays.xml"):
            theirs.update(strings(RES / f"values-{locale}" / file))
            theirs_plurals.update(plurals(RES / f"values-{locale}" / file))

        unknown = sorted((set(theirs) - set(english)) - set(english_plurals))
        unknown += sorted((set(theirs_plurals) - set(english_plurals))
                          - set(english))
        missing = sorted(
            name for name in (set(english) | set(english_plurals))
                           - (set(theirs) | set(theirs_plurals))
            if name not in untranslatable)

        # (name, what they demand, what English demands) - one list for strings
        # and for plural forms alike, so both are counted and reported once.
        mismatched = []
        for name, text in sorted(theirs.items()):
            if name not in english:
                continue
            theirs_specifiers = specifiers(text)
            if theirs_specifiers != specifiers(english[name]):
                mismatched.append(
                    (name, theirs_specifiers, specifiers(english[name])))

        # getQuantityString on a <string> throws, and getString on a <plurals>
        # does not resolve, so which element a name is written as has to agree
        # across languages - it is the call site's contract, not a preference.
        for name in sorted(set(theirs) & set(english_plurals)):
            wrong.append((locale, name,
                          "a string here and a plural in values/"))
        for name in sorted(set(theirs_plurals) & set(english)):
            wrong.append((locale, name,
                          "a plural here and a string in values/"))

        for name in sorted(set(theirs_plurals) & set(english_plurals)):
            forms = theirs_plurals[name]
            for quantity in sorted(set(forms) - QUANTITIES):
                wrong.append((locale, name,
                              f"{quantity!r} is not one of Android's "
                              f"quantities, so that form is never shown"))
            if "other" not in forms:
                wrong.append((locale, name, "no other form, which is the one "
                                            "Android falls back to"))
            wanted = demanded(english_plurals[name])
            for quantity, text in sorted(forms.items()):
                found = specifiers(text)
                if found != wanted:
                    mismatched.append((f"{name}[{quantity}]", found, wanted))

        for name in unknown:
            wrong.append((locale, name, "not a string this app has"))
        for name, found, wanted in mismatched:
            wrong.append((locale, name,
                          f"format is {sorted(found)} where English has "
                          f"{sorted(wanted)}"))

        # A plural counts once however many forms the language needs, so the
        # column stays comparable: Russian's four forms are not more translated
        # than German's two.
        print(f"{locale:<10} {len(theirs) + len(theirs_plurals):>12} "
              f"{len(missing):>9} {len(unknown):>9} {len(mismatched):>11}")

    print()

    if wrong:
        for locale, name, why in wrong:
            print(f"error: values-{locale}: {name}: {why}", file=sys.stderr)
        return 1

    print(f"{len(locales)} translations agree with values/")
    return 0


if __name__ == "__main__":
    sys.exit(main())
