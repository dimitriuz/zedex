# Privacy policy

**Zedex** · last updated 4 August 2026

Zedex collects nothing about you. There is no server to collect it with.

This document says exactly what the app does with data, because "we collect
nothing" is worth being specific about.

## What Zedex does not do

- No accounts, no sign-in, no profile
- No analytics or telemetry, and no third-party analytics library of any kind
- No usage statistics beyond one thing, said plainly under *Network use*: the
  update check downloads two small files from GitHub, and GitHub counts
  downloads. A total, with nobody in it who can be picked out
- No advertising and no advertising identifiers
- No crash or error reporting
- No device, advertising or user identifiers of any kind are read or stored
- Nothing is sent to the developer, who operates no server and receives no data

## What stays on your device

Everything the app keeps, it keeps on your device only:

- **Your settings** — which machine, which keyboard, filter strengths, key
  profiles, controller bindings
- **Your files** — the ROMs, save states, tapes, disks, screenshots and
  recordings in the folder you chose, `/storage/emulated/0/Documents/Zedex` by
  default
- **The recent files list** — the names of the last ten files you opened

None of it is transmitted anywhere. Removing the app removes the settings and
the recent list; the folder you chose is yours and is left alone.

## Network use

Zedex holds the `INTERNET` permission for two purposes, and both of them are
things you ask for.

**Two small requests to GitHub at startup**, in the build distributed as an APK,
to find out whether there is a newer release:

```
github.com/dimitriuz/zedex/releases/latest/download/latest.json
github.com/dimitriuz/zedex/releases/download/v<your version>/alive.txt
```

Both send nothing but the request itself; GitHub sees an IP address, as any web
request shows it. The second one's contents are not even read.

**They are counted, and that is deliberate.** GitHub keeps a download count for
every file attached to a release, so those two numbers are how this project knows
whether anyone is using it — roughly how often the app is started, and which
versions are still being started. What reaches the developer is a total, from
GitHub, some time later. It cannot be told apart into people: there is no
identifier in the request, nothing is stored anywhere by us, and one download
looks exactly like another. If that is a total you would rather not be in,
*Settings › App › Check for updates* stops both requests being made at all.

Nothing is downloaded beyond those two files unless you answer yes to the note
that follows. **The version from Google Play makes neither request**: it has
neither the code nor the permission to install an update, because Play updates
its own apps.

**And the ROMs.** If you tap the button offering to fetch a set of system ROMs,
the app downloads one file from the Internet Archive:

```
https://archive.org/download/zx-roms-fuse-roms/zx%20roms.zip
```

That is an ordinary HTTPS request, and archive.org sees what any download shows
it — your IP address and the request itself. The Internet Archive's own privacy
policy governs what they do with that. **No connection is made unless you ask
for it**, and nothing else in the app opens one at all.

Three places offer a link that leaves Zedex and opens your browser:

| Link | What it carries |
| --- | --- |
| The Internet Archive item page | nothing beyond the request |
| The Tipshop cheat search | the name of the program currently loaded, so the site can find its cheats |
| The source repository on github.com | nothing beyond the request |

Once your browser has opened, that site's privacy policy applies, not this one.

## Files

Your files live in `/storage/emulated/0/Documents/Zedex` unless you choose
somewhere else — a folder any file manager can open, and one the app needs no
permission whatsoever to create and use. Android gives an app access to the
files it wrote itself, which is all Zedex needs; the rest of your storage is not
scanned, indexed, catalogued or copied anywhere. Files you open from elsewhere
come through the system picker, which grants access to that one file.

**The version on Google Play holds no storage permission at all.** The build
published on GitHub additionally declares All files access, so that whoever wants
the folder somewhere else can grant it and choose. It is never required, is
requested only when you ask for such a folder, and is used only for the folders
you pick.

Screenshots and recordings are the exception, and deliberately so: they go to
`Pictures/Zedex`, which is what makes them appear in your gallery, and the app
tells the system's media index about each one as it is written. Nothing else
does. Save-state thumbnails carry a `.nomedia` marker so your photo app is not
filled with pictures of Spectrum screens.

## Android's own backup

Zedex allows Android's standard app backup, so your settings can survive a new
phone. That backup goes to your own Google account, is performed by the
operating system rather than by Zedex, and is governed by Google's terms. Turn
it off in your device's system settings if you would rather it did not happen.

## Permissions in full

| Permission | Why | Play build |
| --- | --- | --- |
| `INTERNET` | the two update-check requests and the optional ROM download, all described above | yes |
| `MANAGE_EXTERNAL_STORAGE` | offered, never required, for a data folder outside the ones Android gives an app for free | **no** |
| `REQUEST_INSTALL_PACKAGES` | handing a downloaded release to Android's installer, which asks you itself | **no** |

The app declares no others, and the version on Google Play declares only the
first — where it is used for the ROM download alone.

## Children

Zedex collects no personal data from anyone, of any age. It contains no
advertising, no social features, no chat and no user-generated content.

## Changes to this policy

If the app's behaviour ever changes, this file changes with it and the date at
the top moves. Every previous version is in the project's git history.

## Contact

Questions about this policy: dimitriuz@gmail.com

Source code: https://github.com/dimitriuz/zedex
