# Privacy policy

**Zedex** · 11 August 2026

**Zedex collects no personal data.** No account, no analytics, no advertising, no
crash reporting, and no server belonging to the developer.

## What Zedex stores

On your device, and nowhere else:

- Settings — machine, keyboard, filters, controls
- Your files — ROMs, save states, tapes, disks, screenshots, recordings
- The names of the last ten files you opened
- Your ScreenScraper username and password, if you chose to enter one —
  kept on the device and deliberately left out of Android's backup and
  device transfer, so a password of yours is never copied into a cloud
  account

The default folder is `/storage/emulated/0/Documents/Zedex`. Screenshots and
recordings go to `Pictures/Zedex`, so your gallery shows them; save-state
pictures are marked `.nomedia` so it does not.

Uninstalling removes the settings. Your folder stays.

## When Zedex uses the internet

Five times, and never otherwise. Four of them only if you ask.

**1. Checking for a new version** — the version from GitHub only, at startup. It
asks two things: which release is newest, and the small checksum file published
beside your own version's download.

```
github.com/dimitriuz/zedex/releases/latest
github.com/dimitriuz/zedex/releases/download/v<your version>/Zedex-<your version>.apk.sha256
```

Nothing is sent but the request. GitHub sees your IP address, as any website
does.

GitHub counts how many times that checksum file is downloaded, and the developer
can read the totals. That is the only usage figure this app produces: a count per
version. It carries no identifier and cannot be traced to a person, a phone or a
session. To stop it: **Settings › App › Check for updates**.

**2. Downloading a new version** — only if you tap Update. The file comes from
GitHub and is passed to Android's installer, which asks you before installing.

**3. Downloading system ROMs** — only if you tap that button. One file, from
`archive.org`.

**4. Looking up a game's details and artwork** — only if you ask for it, from
*Scrape this game…* or *Scrape many games…* in the library. Nothing is
scraped in the background and nothing is scraped automatically.

There are two services to choose between, in *Settings › Library › Scrape
from*, and each is covered below.

### screenscraper.fr

Run in France by its own community, and its privacy policy applies to what it
receives. What Zedex sends, per game:

- the file's name, and its MD5 checksum — the checksum is how a game is
  recognised exactly rather than guessed at from its name;
- an identifier for the app itself, which the service issues so it can tell
  one program from another;
- your own ScreenScraper username and password, **only if you have entered
  them** in *Settings › Library*. They are optional; without them the app
  uses a shared allowance.

The checksum is of the game file, not of anything about you, and no
identifier for your device, your installation or you is sent or exists.
screenscraper.fr sees your IP address, as any website does.

What comes back — names, descriptions, cover art, screenshots, videos and
manuals — is written into your own folder and nowhere else.

### ZXInfo

The open API over [ZXDB](https://github.com/zxdb/ZXDB), the database behind
World of Spectrum. There is no account and nothing to set up, so there is
nothing of yours to send. What Zedex sends, per game, is the
file's MD5 checksum — or, if that finds nothing, its title. Nothing else.

```
api.zxinfo.dk          the lookup itself
spectrumcomputing.co.uk  the pictures, manuals and files it names
zxinfo.dk              the loading screens it renders
```

Every request carries `Zedex/<version>` and nothing more, so the service can
tell one program from another. That name is asked for by ZXInfo's own
documentation; there is no identifier for your device, your installation or
you, because none exists. Each of those sites sees your IP address, as any
website does.

**5. Browsing the catalogue, and importing from it** — only if you open the
*Catalogue* tab in the library. Nothing is fetched until you open a shelf, a
cover is fetched only when its row is on screen, and a game is downloaded only
when you tap Import.

The same three addresses as above, and the same `Zedex/<version>` and nothing
else. What Zedex sends is what you asked for: the text you typed, or the letter
or category you tapped, or the identifier of an entry you opened. A file may be
named on some other host, and Zedex will follow that name to fetch it — the
same header goes with it.

What comes back — the game, and its details and artwork — is written into your
own folder and nowhere else.

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

Zedex itself talks to `screenscraper.fr`, `api.zxinfo.dk`,
`spectrumcomputing.co.uk` and `zxinfo.dk`, and only when you ask it to — see
*When Zedex uses the internet* above.

## Permissions

| Permission | Used for | In the Play version |
| --- | --- | --- |
| `INTERNET` | the five uses above | yes |
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

zedex.support@ldlab.dev · source code at https://github.com/dimitriuz/zedex
