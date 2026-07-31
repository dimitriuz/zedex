#!/usr/bin/env python3
"""Type into the emulator by tapping the on-screen keyboard.

`adb shell input keyevent` does not reach the app, so text has to be tapped
out on the Spectrum keyboard. Every key of it is an accessibility node named
the way the Spectrum names it, so this asks the view hierarchy where the keys
are rather than carrying a copy of each keyboard's layout: it types on any of
them, and there is nothing to keep in step when one is redrawn.

    scripts/ui-type.py 'save "x"' ENTER
    scripts/ui-type.py 'randomize usr 15616' ENTER

A word in capitals is a key name (ENTER, SPACE, CS, SS); CS+0 and the like
hold a shift down for one key, which is how you reach DELETE and the arrows on
the 48K; anything else is typed a character at a time. Symbol-shifted
characters latch the shift with a long press, tap the key, then tap the shift
to let it go.
"""
import os
import re
import subprocess
import sys
import time

ADB = os.environ.get("ADB", os.path.expanduser("~/Android/Sdk/platform-tools/adb"))

CAPS, SYMBOL = "\x01", "\x02"
NAMED = {"ENTER": "\n", "SPACE": " ", "CS": CAPS, "SS": SYMBOL}

# What each character is called on the keyboard. SPACE is BREAK SPACE on the
# 48K and SPACE on the 128K's plate, so both names are tried in turn.
KEY_NAMES = {
    "\n": ("ENTER",),
    " ": ("SPACE", "BREAK SPACE"),
    CAPS: ("CAPS SHIFT",),
    SYMBOL: ("SYMBOL SHIFT",),
}

# Characters that need Symbol Shift held down.
SYMBOLS = {
    '"': "p", "!": "1", "@": "2", "#": "3", "$": "4", "%": "5",
    "&": "6", "'": "7", "(": "8", ")": "9", "_": "0",
    "<": "r", ">": "t", ";": "o", "-": "j", "+": "k", "=": "l",
    ":": "z", "?": "c", "/": "v", "*": "b", ",": "n", ".": "m",
}


def keys():
    """Every key on screen, by name, as the middle of where it is."""
    subprocess.run([ADB, "shell", "uiautomator", "dump", "/sdcard/ui.xml"],
                   capture_output=True)
    dump = subprocess.run([ADB, "shell", "cat", "/sdcard/ui.xml"],
                          capture_output=True, text=True).stdout

    found = {}
    for match in re.finditer(
            r'content-desc="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
            dump):
        name = match.group(1)
        left, top, right, bottom = (int(n) for n in match.groups()[1:])
        found.setdefault(name, ((left + right) // 2, (top + bottom) // 2))

    if "ENTER" not in found:
        sys.exit("no keyboard on screen; is the emulator up, and is the "
                 "keyboard one of ours rather than Android's?")
    return found


def main():
    on_screen = keys()

    def at(character):
        """Where to tap for a character, whatever the key is called."""
        for name in KEY_NAMES.get(character, (character.upper(),)):
            if name in on_screen:
                return on_screen[name]
        sys.exit(f"no key for {character!r} on this keyboard")

    # The dump leaves the accessibility machinery busy for a moment and the
    # first tap after it goes missing often enough to notice: "border 7" arrives
    # as "rder 7", and a program's line number is the first thing typed.
    time.sleep(0.5)

    def tap(character):
        x, y = at(character)
        subprocess.run([ADB, "shell", "input", "tap", str(x), str(y)])
        time.sleep(0.25)

    def latch(shift):
        x, y = at(shift)
        subprocess.run([ADB, "shell", "input", "swipe",
                        str(x), str(y), str(x), str(y), "700"])
        time.sleep(0.25)

    def with_shift(shift, character):
        latch(shift)
        tap(character)
        tap(shift)

    for argument in sys.argv[1:]:
        if argument in NAMED:
            tap(NAMED[argument])
            continue

        if "+" in argument and argument.split("+", 1)[0] in ("CS", "SS"):
            shift, key = argument.split("+", 1)
            with_shift(NAMED[shift], NAMED.get(key, key.lower()))
            continue

        for character in argument:
            character = character.lower()
            if character in SYMBOLS:
                with_shift(SYMBOL, SYMBOLS[character])
            else:
                tap(character)

    print("typed", " ".join(repr(a) for a in sys.argv[1:]))


if __name__ == "__main__":
    main()
