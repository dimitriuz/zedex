#!/usr/bin/env python3
"""Tap an on-screen element by its text, via the view hierarchy.

Coordinates shift whenever a menu gains an item; text does not.
"""
import os
import re
import subprocess
import sys
import time

ADB = os.environ.get("ADB", os.path.expanduser("~/Android/Sdk/platform-tools/adb"))


def dump():
    subprocess.run([ADB, "shell", "uiautomator", "dump", "/sdcard/ui.xml"],
                   capture_output=True)
    return subprocess.run([ADB, "shell", "cat", "/sdcard/ui.xml"],
                          capture_output=True, text=True).stdout


def nodes():
    found = []
    for m in re.finditer(r'text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
                         dump()):
        text, x1, y1, x2, y2 = m.groups()
        if text:
            found.append((text, (int(x1) + int(x2)) // 2, (int(y1) + int(y2)) // 2))
    return found


def tap(target, timeout=8):
    deadline = time.time() + timeout
    while time.time() < deadline:
        for text, x, y in nodes():
            if target.lower() in text.lower():
                subprocess.run([ADB, "shell", "input", "tap", str(x), str(y)])
                print(f"tapped {text!r} at ({x},{y})")
                time.sleep(1.2)
                return True
        time.sleep(0.5)
    print(f"NOT FOUND: {target!r}")
    print("  on screen:", [t for t, _, _ in nodes()])
    return False


if __name__ == "__main__":
    if sys.argv[1] == "list":
        for text, x, y in nodes():
            print(f"{text!r:40} ({x},{y})")
    else:
        for argument in sys.argv[1:]:
            if not tap(argument):
                sys.exit(1)
