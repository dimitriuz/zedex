# Privacy policy

**Zedex** · 4 August 2026

**Zedex collects no personal data.** No account, no analytics, no advertising, no
crash reporting, and no server belonging to the developer.

## What Zedex stores

On your device, and nowhere else:

- Settings — machine, keyboard, filters, controls
- Your files — ROMs, save states, tapes, disks, screenshots, recordings
- The names of the last ten files you opened

The default folder is `/storage/emulated/0/Documents/Zedex`. Screenshots and
recordings go to `Pictures/Zedex`, so your gallery shows them; save-state
pictures are marked `.nomedia` so it does not.

Uninstalling removes the settings. Your folder stays.

## When Zedex uses the internet

Three times, and never otherwise.

**1. Checking for a new version** — the version from GitHub only, at startup. It
downloads two small files:

```
github.com/dimitriuz/zedex/releases/latest/download/latest.json
github.com/dimitriuz/zedex/releases/download/v<your version>/alive.txt
```

Nothing is sent but the request. GitHub sees your IP address, as any website
does.

GitHub counts how many times each file is downloaded, and the developer can read
those totals. That is the only usage figure this app produces: downloads per
version. It carries no identifier and cannot be traced to a person, a phone or a
session. To stop it: **Settings › App › Check for updates**.

**2. Downloading a new version** — only if you tap Update. The file comes from
GitHub and is passed to Android's installer, which asks you before installing.

**3. Downloading system ROMs** — only if you tap that button. One file, from
`archive.org`.

**The version from Google Play does neither 1 nor 2.** It cannot install updates;
Play updates it instead.

## If you send a report

*About Zedex › Report a problem* builds a report about your device and this
build: app version, Android version, phone model, screen size, which folder your
files are in, whether ROMs were found, and the settings that change how the app
behaves. If Zedex has crashed, the report also holds the crash itself.

**You see the whole report before it goes anywhere, and you can edit it.** It is
sent by your own mail app, to the address at the foot of this page. Nothing is
sent if you do not tap Send, nothing is gathered unless you ask for a report, and
no report is stored anywhere but on your device.

A crash report is kept on your device until you are asked about it, once. Whether
you send it or discard it, the file is then deleted.

## Links that leave the app

Tapping one opens your browser, and that site's privacy policy applies:

| Link | What it carries |
| --- | --- |
| The Internet Archive | nothing but the request |
| The Tipshop cheat search | the name of the program you have loaded |
| github.com/dimitriuz/zedex | nothing but the request |

## Permissions

| Permission | Used for | In the Play version |
| --- | --- | --- |
| `INTERNET` | the three uses above | yes |
| `MANAGE_EXTERNAL_STORAGE` | keeping your folder where a file manager can open it. Optional — the app works without it | no |
| `REQUEST_INSTALL_PACKAGES` | passing a downloaded version to Android's installer | no |

There are no others. Zedex does not read contacts, location, camera, microphone,
phone state or any device identifier.

## Android's backup

Android may copy Zedex's settings to your own Google account, as it does for
other apps. Android does that, not Zedex, and your device settings turn it off.

## Children

Zedex collects no data from anyone, of any age. It has no advertising, no chat
and no social features.

## Changes

This file changes when the app does, and the date above changes with it. Earlier
versions are in the project's git history.

## Contact

dimitriuz@gmail.com · source code at https://github.com/dimitriuz/zedex
