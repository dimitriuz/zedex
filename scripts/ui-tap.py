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

# The quick bar and the keyboard are icons and pixels, so their nodes carry a
# description and no text at all; the menu carries text. Take whichever is
# there, description first, since a described node was named on purpose.
NODE = re.compile(r'text="([^"]*)"[^>]*?content-desc="([^"]*)"'
                  r'[^>]*?clickable="(true|false)"'
                  r'[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')


def dump():
    subprocess.run([ADB, "shell", "uiautomator", "dump", "/sdcard/ui.xml"],
                   capture_output=True)
    return subprocess.run([ADB, "shell", "cat", "/sdcard/ui.xml"],
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
            subprocess.run([ADB, "shell", "input", "tap", str(x), str(y)])
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
