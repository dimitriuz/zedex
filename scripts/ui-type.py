#!/usr/bin/env python3
"""Type into the emulator by tapping the on-screen keyboard.

`adb shell input keyevent` does not reach the app, so text has to be tapped
out on the Spectrum keyboard. The layout below mirrors SpectrumKeyboardView,
in the artwork's own coordinates; where the keyboard is on screen is read
from the view hierarchy so it survives a different device or orientation.

    scripts/ui-type.py 'save "x"' ENTER
    scripts/ui-type.py 'randomize usr 15616' ENTER

A word in capitals is a key name (ENTER, SPACE, CS, SS); CS+0 and the like
hold a shift down for one key, which is how you reach DELETE and the
arrows; anything else is typed a character at a time. Symbol-shifted
characters latch the shift with a long press, tap the key, then tap the
shift to let it go.
"""
import os
import re
import subprocess
import sys
import time

ADB = os.environ.get("ADB", os.path.expanduser("~/Android/Sdk/platform-tools/adb"))

# The keyboard artwork, 541x201, exactly as SpectrumKeyboardView has it.
ROWS = [
    (20, 43, "1234567890",
     [(10, 43), (60, 93), (110, 143), (160, 193), (210, 243),
      (260, 293), (310, 343), (360, 393), (410, 443), (460, 493)]),
    (69, 92, "qwertyuiop",
     [(35, 68), (85, 118), (135, 168), (185, 218), (235, 268),
      (285, 318), (335, 368), (385, 418), (435, 468), (485, 518)]),
    (118, 141, "asdfghjkl\n",
     [(47, 80), (97, 130), (147, 180), (197, 230), (247, 280),
      (297, 330), (347, 380), (397, 430), (447, 480), (497, 530)]),
    (167, 190, "\x01zxcvbnm\x02 ",
     [(10, 55), (72, 105), (122, 155), (172, 205), (222, 255),
      (272, 305), (322, 355), (372, 405), (422, 455), (472, 530)]),
]

CAPS, SYMBOL = "\x01", "\x02"
NAMED = {"ENTER": "\n", "SPACE": " ", "CS": CAPS, "SS": SYMBOL}

# Characters that need Symbol Shift held down.
SYMBOLS = {
    '"': "p", "!": "1", "@": "2", "#": "3", "$": "4", "%": "5",
    "&": "6", "'": "7", "(": "8", ")": "9", "_": "0",
    "<": "r", ">": "t", ";": "o", "-": "j", "+": "k", "=": "l",
    ":": "z", "?": "c", "/": "v", "*": "b", ",": "n", ".": "m",
}

KEYS = {}
for top, bottom, characters, spans in ROWS:
    for character, (left, right) in zip(characters, spans):
        KEYS[character] = ((left + right) / 2, (top + bottom) / 2)


def keyboard_rect():
    """Where the keyboard view is, and how the artwork maps onto it."""
    subprocess.run([ADB, "shell", "uiautomator", "dump", "/sdcard/ui.xml"],
                   capture_output=True)
    dump = subprocess.run([ADB, "shell", "cat", "/sdcard/ui.xml"],
                          capture_output=True, text=True).stdout

    view = None
    for match in re.finditer(
            r'class="android\.view\.View"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
            dump):
        view = [int(n) for n in match.groups()]
    if view is None:
        sys.exit("keyboard view not found; is the emulator on screen?")

    left, top, right, bottom = view
    scale = min((right - left) / 541.0, (bottom - top) / 201.0)
    return (left + ((right - left) - 541 * scale) / 2,
            top + ((bottom - top) - 201 * scale) / 2,
            scale)


def main():
    origin_x, origin_y, scale = keyboard_rect()

    def at(character):
        x, y = KEYS[character]
        return str(round(origin_x + x * scale)), str(round(origin_y + y * scale))

    def tap(character):
        x, y = at(character)
        subprocess.run([ADB, "shell", "input", "tap", x, y])
        time.sleep(0.25)

    def latch(shift):
        x, y = at(shift)
        subprocess.run([ADB, "shell", "input", "swipe", x, y, x, y, "700"])
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
            elif character in KEYS:
                tap(character)
            else:
                sys.exit(f"no key for {character!r}")

    print("typed", " ".join(repr(a) for a in sys.argv[1:]))


if __name__ == "__main__":
    main()
