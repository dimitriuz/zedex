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

# The keyboard artwork, in its own pixels, exactly as SpectrumKeyboardView has
# it. Which one is on screen is a setting, so both are here and the stored skin
# picks between them - the 128K plate has its keys somewhere else entirely, and
# tapping the rubber one's coordinates on it types nonsense.
ROWS_48 = [
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

ROWS_128 = [
    (40, 84, "1234567890",
     [(170, 236), (244, 311), (319, 385), (393, 460), (468, 534), (542, 609), (617, 683), (691, 758), (766, 832), (840, 907)]),
    (118, 162, "qwertyuiop\n",
     [(207, 273), (281, 348), (356, 422), (430, 497), (505, 571), (579, 646), (654, 720), (728, 795), (803, 869), (877, 944), (952, 1018)]),
    (196, 240, "asdfghjkl\n",
     [(225, 291), (299, 366), (374, 440), (448, 515), (523, 589), (597, 664), (672, 738), (746, 813), (821, 887), (895, 1018)]),
    (274, 318, "\x01zxcvbnm.",
     [(22, 181), (267, 333), (341, 408), (416, 482), (490, 557), (565, 631), (639, 706), (714, 780), (788, 855)]),
    (339, 396, "\x02; ,",
     [(22, 94), (102, 168), (400, 724), (881, 947)]),
]

# 541x201 for the rubber keyboard, 1040x413 for the 128K's plate.
ARTWORK = {"rubber": (541.0, 201.0), "plus": (1040.0, 413.0)}


def skin():
    """Which keyboard the app is drawing, from its own preferences.

    Both packages are asked, because run-as only works on the debuggable one
    and a build off the bench is dev.ldlab.zedex.debug. Getting this wrong is
    silent: the rubber layout tapped on the 128K's plate types nonsense.
    """
    for package in ("dev.ldlab.zedex", "dev.ldlab.zedex.debug"):
        stored = subprocess.run(
            [ADB, "shell", "run-as", package, "cat", "shared_prefs/fuse.xml"],
            capture_output=True, text=True).stdout
        found = re.search(r'name="keyboardSkin">([a-z]+)<', stored)
        if found and found.group(1) in ARTWORK:
            return found.group(1)
    return "rubber"


SKIN = skin()
ROWS = ROWS_128 if SKIN == "plus" else ROWS_48

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
    art_w, art_h = ARTWORK[SKIN]
    scale = min((right - left) / art_w, (bottom - top) / art_h)
    return (left + ((right - left) - art_w * scale) / 2,
            top + ((bottom - top) - art_h * scale) / 2,
            scale)


def main():
    origin_x, origin_y, scale = keyboard_rect()

    # The dump leaves the accessibility machinery busy for a moment and the
    # first tap after it goes missing often enough to notice: "border 7" arrives
    # as "rder 7", and a program's line number is the first thing typed.
    time.sleep(0.5)

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
