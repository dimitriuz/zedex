#!/usr/bin/env python3
"""Checks that every preference is read as the type it is written as.

A SharedPreferences key has no declared type: whoever writes it decides, and a
getter of the wrong type throws ClassCastException. The trap is that it throws
only when the key is *present* - getString on an absent key returns the default
quite happily - so a mismatch survives every test on a fresh install and fails on
the first device where somebody has changed that setting.

That is exactly how 1.1.0 shipped a crash. `joystickType` is written with putInt
in three places; the diagnostics report read it with getString; nobody saw it
until a phone that had picked a joystick interface asked for a bug report.

Run it after touching anything that reads preferences:

    scripts/check-prefs.py

Exits non-zero on a mismatch, so it can go in a workflow.

What it cannot see: keys written by the preference framework from
res/xml/settings_*.xml rather than by our code. Those show a writer of "-", and
their type comes from the Preference class - ListPreference writes a String,
SwitchPreferenceCompat a Boolean. If a reader disagrees with that, this will not
tell you; the XML will.
"""

import collections
import pathlib
import re
import sys

TYPES = "String|Int|Boolean|Long|Float|StringSet"

SOURCE = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/java"


def main() -> int:
    files = {p: p.read_text() for p in SOURCE.rglob("*.java")}

    # The constants, so the report can say "joystickType" and not KEY_JOYSTICK_TYPE.
    names = {}
    for text in files.values():
        for match in re.finditer(r'String\s+(KEY_[A-Z_0-9]+)\s*=\s*"([^"]+)"', text):
            names[match.group(1)] = match.group(2)

    writers = collections.defaultdict(set)
    readers = collections.defaultdict(set)
    where = collections.defaultdict(set)

    for path, text in files.items():
        for kind, table in (("put", writers), ("get", readers)):
            pattern = rf"\.{kind}({TYPES})\(\s*(?:[A-Za-z_]+\.)?(KEY_[A-Z_0-9]+)"
            for match in re.finditer(pattern, text):
                table[match.group(2)].add(match.group(1))
                where[match.group(2)].add(path.name)

    mismatched = []

    print(f"{'preference':<26} {'written':<10} {'read':<12} where")
    print("-" * 78)

    for key in sorted(set(writers) | set(readers)):
        written = writers.get(key, set())
        read = readers.get(key, set())

        # A reader must use a type something writes. Several writers of different
        # types would itself be a bug, and this catches that too.
        wrong = bool(written and read and not read <= written)
        if wrong:
            mismatched.append(key)

        print(f"{names.get(key, key):<26} "
              f"{','.join(sorted(written)) or '-':<10} "
              f"{','.join(sorted(read)) or '-':<12} "
              f"{' '.join(sorted(where[key]))}"
              f"{'   *** MISMATCH ***' if wrong else ''}")

    print()

    if mismatched:
        for key in mismatched:
            print(f"error: {names.get(key, key)} is written as "
                  f"{','.join(sorted(writers[key]))} and read as "
                  f"{','.join(sorted(readers[key]))}", file=sys.stderr)
        print(f"\n{len(mismatched)} mismatch(es). Each one is a "
              f"ClassCastException waiting for a device that has set it.",
              file=sys.stderr)
        return 1

    print("every preference is read as it is written")
    return 0


if __name__ == "__main__":
    sys.exit(main())
