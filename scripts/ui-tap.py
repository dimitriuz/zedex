#!/usr/bin/env python3
"""Tap an on-screen element by its text, via the view hierarchy.

Coordinates shift whenever a menu gains an item; text does not.

    scripts/ui-tap.py list
    scripts/ui-tap.py "Disks" "Beta Disk A" "Save…"

Each argument is matched against the screen in turn, waiting for it to
appear. An exact match wins over a substring, and a clickable node over a
decorative one, so asking for "Reset" finds the button in a dialog rather
than the sentence above it that happens to contain the word.
"""
import os
import re
import subprocess
import sys
import time

ADB = os.environ.get("ADB", os.path.expanduser("~/Android/Sdk/platform-tools/adb"))


def device():
    """A real phone or tablet if one is plugged in, otherwise the emulator.

    Both are usually attached here, and then a bare `adb shell` refuses to
    guess - so this picks, and picks the hardware: what the app does on a
    Xiaomi tablet is the answer that counts, and an emulator agreeing with it
    is a convenience rather than evidence. ANDROID_SERIAL still wins, for
    driving one of two devices deliberately.
    """
    chosen = os.environ.get("ANDROID_SERIAL")
    if chosen:
        return ["-s", chosen]

    listed = subprocess.run([ADB, "devices"], capture_output=True, text=True)
    ready = [line.split()[0] for line in listed.stdout.splitlines()[1:]
             if line.strip().endswith("\tdevice")]

    hardware = [serial for serial in ready if not serial.startswith("emulator-")]
    pick = (hardware or ready)

    return ["-s", pick[0]] if pick else []


ADB_TARGET = None


def adb(*arguments):
    global ADB_TARGET
    if ADB_TARGET is None:
        ADB_TARGET = device()

    return [ADB] + ADB_TARGET + list(arguments)

# The quick bar and the keyboard are icons and pixels, so their nodes carry a
# description and no text at all; the menu carries text. Take whichever is
# there, description first, since a described node was named on purpose.
NODE = re.compile(r'text="([^"]*)"[^>]*?content-desc="([^"]*)"'
                  r'[^>]*?clickable="(true|false)"'
                  r'[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')


def dump():
    subprocess.run(adb("shell", "uiautomator", "dump", "/sdcard/ui.xml"),
                   capture_output=True)
    return subprocess.run(adb("shell", "cat", "/sdcard/ui.xml"),
                          capture_output=True, text=True).stdout


def nodes():
    found = []
    for match in NODE.finditer(dump()):
        text, described, clickable, x1, y1, x2, y2 = match.groups()
        name = described or text
        if name:
            found.append((name, clickable == "true",
                          (int(x1) + int(x2)) // 2, (int(y1) + int(y2)) // 2))
    return found


def best(target, on_screen):
    """Exact before substring, clickable before not, in that order."""
    def rank(node):
        text, clickable, _, _ = node
        exact = text.strip().lower() == target.lower()
        return (0 if exact else 1, 0 if clickable else 1)

    matches = [n for n in on_screen if target.lower() in n[0].lower()]
    return min(matches, key=rank) if matches else None


def tap(target, timeout=8):
    deadline = time.time() + timeout
    while True:
        on_screen = nodes()
        match = best(target, on_screen)
        if match:
            text, _, x, y = match
            subprocess.run(adb("shell", "input", "tap", str(x), str(y)))
            print(f"tapped {text!r} at ({x},{y})")
            time.sleep(1.2)
            return True
        if time.time() > deadline:
            print(f"NOT FOUND: {target!r}")
            print("  on screen:", [n[0] for n in on_screen])
            return False
        time.sleep(0.5)


if __name__ == "__main__":
    if sys.argv[1:2] == ["list"]:
        for text, clickable, x, y in nodes():
            print(f"{text!r:44} {'tap' if clickable else '   '} ({x},{y})")
    else:
        for argument in sys.argv[1:]:
            if not tap(argument):
                sys.exit(1)
