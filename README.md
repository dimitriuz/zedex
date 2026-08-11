# Zedex

<a href="docs/screenshots/demo-fullscreen.jpg"><img src="docs/screenshots/demo-fullscreen.jpg" width="720" alt="Zedex running its own demo tape, fullscreen"></a>

A ZX Spectrum that behaves like a modern console. Save at any moment, put the
picture through the CRT you remember, pick up a controller and play. Nothing to
set up — every machine boots the moment you open the app.

Android 11 and later. The emulation is
[Fuse](http://fuse-emulator.sourceforge.net/), unmodified; the front end is
native. Most of the code was written by Claude, directed and reviewed by a
professional software developer.

Building, testing and releases: [docs/DEVELOPING.md](docs/DEVELOPING.md).
How Fuse is wired in: [docs/INTERNALS.md](docs/INTERNALS.md).


## Save states

<a href="docs/screenshots/states.jpg"><img src="docs/screenshots/states.jpg" width="400" alt="The states grid, each card showing the screen it holds"></a>

Stop wherever you like. States are a grid of cards, each showing the screen it
holds, renamed and deleted on the card itself. Quick save and quick load sit on
the shoulder buttons — **Select + R1** and **Select + L1** — and are named after
whatever is running, so every game keeps its own pair. Written as SZX, Z80 or
SNA.


## Shaders

<a href="docs/screenshots/shaders.jpg"><img src="docs/screenshots/shaders.jpg" width="400" alt="Scanlines, CRT and composite video over a game"></a>
<a href="docs/screenshots/settings-display.jpg"><img src="docs/screenshots/settings-display.jpg" width="400" alt="Display settings"></a>

- Scanlines, or a CRT with curvature, shadow mask and glow, or both together
- The signal: RGB, composite or RF, with colour bleed and noise
- A black-and-white television
- Every effect's strength and the sharpness are yours to set

Scaling runs on the GPU — fitted to the screen, or at a whole number of device
pixels per emulated pixel. Border in full, slimmed to a quarter, or cropped
away. Fullscreen leaves nothing but the picture and your thumbs.


## Dual screen

On a dual-screen handheld — an AYN Thor and its like — the controls, the menu
and the indicators move to the second panel entirely, leaving the first showing
nothing but the machine. The setting cannot be switched on with no panel to move
to, and unplugging one brings everything back.

With the library on, the panel shows whichever game is selected — its artwork,
the facts and the description — and the list gets the whole first screen. Once a
game is running a corner switch flips the panel between the controls and that
same page.

Android decides which display an input method appears on, so the *Android
keyboard* skin usually opens over the machine. It still types into the Spectrum.

<!-- ![Both panels of a dual-screen handheld](docs/screenshots/dual-screen.jpg) — the picture alone above, controls and lamps below -->


## Controllers

<a href="docs/screenshots/hotkeys.jpg"><img src="docs/screenshots/hotkeys.jpg" width="400" alt="Controller hotkeys"></a>

Plug one in and it works — no mapping screen. Stick, hat and D-pad steer, A is
fire, B, X and Y are the key buttons, Start is Enter, and the on-screen pad
steps aside.

Hotkeys are RetroArch style: one button is the hotkey, and every action is that
button and another, so nothing is taken from the game. The hotkey is Select by
default and can be any button, or None, in which case bindings fire on their
own. Twenty-two actions to bind; nine out of the box:

| Action | Button |
| --- | --- |
| Pause or resume | Select + B |
| Quit the app | Select + Start |
| Quick save | Select + R1 |
| Quick load | Select + L1 |
| Fast forward | Select + R2, while held |
| Fullscreen | Select + X |
| Show or hide the keyboard | Select + Y |
| Next key profile | Select + R3 |
| Next joystick type | Select + L3 |

Fast forward runs at 500% and stays silent. Rebind in ☰ *Controls… ›
Controller hotkeys…*: tap a row, press the button.



## Profiles

<a href="docs/screenshots/key-profile.jpg"><img src="docs/screenshots/key-profile.jpg" width="400" alt="The key profile editor"></a>
<a href="docs/screenshots/settings-controls.jpg"><img src="docs/screenshots/settings-controls.jpg" width="400" alt="Controls settings"></a>

The joystick comes out as any of the Spectrum's seven interfaces — Kempston,
Cursor, Sinclair 1 and 2, Timex 1 and 2, Fuller — or as keys. Key profiles
cover QAOPM, QAOP and Space, the cursor keys, both Sinclair sets and WASD, and
you can copy any of them, edit the eight keys, and delete what you no longer
want.

Interface, profile, keyboard and mouse all change from the bar across the top of
the window, mid-game.


## Touch

<a href="docs/screenshots/landscape.jpg"><img src="docs/screenshots/landscape.jpg" width="400" alt="Landscape: the picture, the joystick and a keyboard"></a>

The on-screen joystick is a thumb pad, fire and three key buttons in an arc, put
where the picture leaves room and laid over it, translucent, when it does not.

Four keyboards, drawn rather than photographed, so they stay sharp at any size:
the rubber 48K and the 128K plate, each also slim with the BASIC left off. Every
key carries its keyword, symbol-shift character, colour and extended-mode token,
in the colours the machine printed them in. Two fingers give a shifted key; hold
a shift for 400ms and it latches until tapped again. Your own input method is
there too as a fifth choice.

<a href="docs/screenshots/keyboard-48k.jpg"><img src="docs/screenshots/keyboard-48k.jpg" width="400" alt="The rubber 48K, every legend in its own colour"></a>
<a href="docs/screenshots/keyboard-128k.jpg"><img src="docs/screenshots/keyboard-128k.jpg" width="400" alt="The 128K plate"></a>

Whenever no keyboard is on screen — you put it away, or you are in fullscreen —
a button by the pad raises a see-through one over the picture instead, in
whichever skin you chose, and the joystick stays put, so a game wanting a key
and a stick at once can have both.

**Kempston mouse** — a mode. While on, a drag moves the pointer, and fire and
the first key button are its buttons. Sensitivity is a setting.


## Cheats

<a href="docs/screenshots/cheats.jpg"><img src="docs/screenshots/cheats.jpg" width="400" alt="Cheats for what is loaded"></a>

Cheats for 3,682 games are built in, matched by the file's own fingerprint — or
find a game by name yourself, when it loaded under a different one. Ones that
take a number ask for it. Pokes of your own can be fired once or
kept on a list; decimal, or hex after `0x`, `$` or `#`. A scrape can add more:
the built-in database answers first, because it matches on the file itself, and
anything scraped tops it up rather than replacing it. Nothing for your game? A
link opens a search on The Tipshop, which is where the database came from.


## Capture

Screenshots as PNG, and recording to GIF or MP4. All three go to
`Pictures/Zedex` and show up in your gallery, named after whatever is running.


## The machines

| Family | Machines |
| --- | --- |
| Sinclair | 16K · 48K · 48K NTSC · 128K · +2 · +2A · +3 · +3e |
| Timex | TC2048 · TC2068 · TS2068 |
| Clones | Pentagon 128K · 512K · 1024K · Scorpion ZS 256 |
| Other | Spectrum SE |

- Speed 25%–500%, reset, NMI, Issue 2 keyboard
- Turbo — a 7 MHz processor at 50 Hz — on the Pentagons and the Scorpion
- AY-3-8912 in ACB or ABC stereo, on the 128K family
- TurboSound — two AY chips, six channels — on the Pentagons and the Scorpion
- Beta 128 and TR-DOS, drives A:–D:, on the Pentagons and the Scorpion
- The +3 floppy, drives A: and B:
- DivMMC and a memory card, on any machine
- Timex SCLD video and its hi-res modes
- A tape deck that reads and writes
- Kempston mouse

<a href="docs/screenshots/media.jpg"><img src="docs/screenshots/media.jpg" width="400" alt="The tape deck and every drive the machine has"></a>


## Files

Snapshots, tapes, disks, cartridges, microdrive images and RZX recordings. The
file identifies itself, and the machine switches when the media needs one — a
`.dsk` brings up a +3, a `.trd` a Beta-equipped machine.

- Opens files handed over by other apps
- Fast tape loading: off, safe (ROM loaders), or turbo (custom loaders too)
- Writes tapes and disks out, or back over the file they came from
- Recent files: the last ten
- A demo tape in your tapes folder — the first run says where
- The machine ROMs are put there for you, and kept in the app's own storage
  instead if that folder ever cannot hold them — nothing to do either way

<a href="docs/screenshots/settings-tape.jpg"><img src="docs/screenshots/settings-tape.jpg" width="400" alt="Tape settings"></a>

**First start** asks for two folders. The data folder can be changed later in
*Settings › App*, the content folder in *Settings › Library*.

Files the app writes there are yours to open, copy and delete. Files you copy
*in* are a different matter: Android hands an app only what it wrote itself, so
a tape dropped into `tapes` from a computer is invisible to the app and has to
be opened through *Open file…* instead — which remembers it afterwards. The
build from Releases here can additionally be granted All files access, and then
any folder will do, `/storage/emulated/0/Zedex` included; the Play build cannot,
because Play does not allow that permission to an app that works without it.

| | |
| --- | --- |
| Data folder | `roms`, `states`, `tapes`, `disks`, `cards`. **`/storage/emulated/0/Documents/Zedex`** by default — a folder any file manager can open, and one the app needs no permission at all to use. Screenshots and recordings are not in it: they go to `Pictures/Zedex`, which is what puts them in your gallery |
| Content folder | where *Open file…* starts, on the first open after each launch |

<!-- ![First run](docs/screenshots/first-run.jpg) — the two folder cards and the note about the demo tape -->


## Library

The app can open on your games instead of the machine — *Settings › Library ›
Start in the library*, which needs a content folder to browse. ☰ goes between
the two either way.

<a href="docs/screenshots/library-grid.jpg"><img src="docs/screenshots/library-grid.jpg" width="400" alt="The library as a grid of covers, one game selected and its details beside it"></a>
<a href="docs/screenshots/library-list.jpg"><img src="docs/screenshots/library-list.jpg" width="400" alt="The same library as a list, with year and format down the right"></a>

- The content folder as a list or a grid; zip archives open like folders
- Favourites and Recent beside Browse; search, sort by name, size, release
  date, format or rating
- Filter by format, genre, rating, developer or publisher — narrows the whole
  collection, not just the folder you're browsing; from the toolbar's
  **Options** button or the pad's Select
- A controller drives all of it: the stick moves, **A** plays, **B** goes up,
  **Y** favourites, **L1/R1** change tab, **L2/R2** page, **X** searches and
  **Select** opens View, Sort and Filter

<a href="docs/screenshots/library-options.jpg"><img src="docs/screenshots/library-options.jpg" width="400" alt="The Options menu: view, sort, filter, edit metadata and the two scrape actions"></a>
<a href="docs/screenshots/library-edit.jpg"><img src="docs/screenshots/library-edit.jpg" width="400" alt="Editing a game's name and description by hand"></a>

Names, descriptions, artwork and manuals come from an ES-DE scrape if you have
one — or scrape them yourself, one game from its menu or a whole folder at a
time, choosing which pictures you want. *Settings › Library* picks where from
and what to fetch.

| | |
| --- | --- |
| **ZXInfo** | The database behind SpectrumComputing. No account, nothing to set up. Matches on the file's own hash first, which is exact, and on the name only if that misses |
| **ScreenScraper** | Covers, screenshots, video and manuals for far more than the Spectrum. Everyone using the app shares one daily allowance, and every picture spends a piece of it — *Settings › Library › ScreenScraper username* is optional and buys a much larger one of your own |

You can also type the details in yourself, and scraping a game you have edited
by hand says so first and waits for an answer.

A scrape brings more than pictures. ZXInfo carries the manual, extra pokes for
the cheats page, and the game's own music — where somebody has ripped the AY
tunes out of it, *Music…* plays them and puts the machine back exactly where it
was afterwards, mid-level and mid-jump.

<a href="docs/screenshots/game-details.jpg"><img src="docs/screenshots/game-details.jpg" width="400" alt="A game's page: the full description, and its cassette scan in a gallery"></a>
<a href="docs/screenshots/manual.jpg"><img src="docs/screenshots/manual.jpg" width="400" alt="A game's manual, opened from its page"></a>

A page per game holds every picture there is for it — cover, loading screen,
in-game shot, the cassette or disk itself, a map — plus the video and the
manual. Manuals come as a PDF or as somebody's typed-up transcription, and the
transcriptions open in the app, monospaced and never re-wrapped, because that
is the only way the ASCII tables in them survive.


### The catalogue

<a href="docs/screenshots/catalogue.jpg"><img src="docs/screenshots/catalogue.jpg" width="400" alt="The catalogue tab: search, A-Z, categories, newest and Surprise me"></a>
<a href="docs/screenshots/catalogue-category.jpg"><img src="docs/screenshots/catalogue-category.jpg" width="400" alt="The Demoscene category, 1,403 entries, each with its loading screen"></a>

A fourth tab browses ZXInfo's ~40,000 entries and imports what you choose into
your own folder.

- Search, A–Z, 0–9, by category, newest first, *Surprise me*, or games like the
  one you are looking at
- An import lands in `Downloaded/Games`, `Applications`, `Demoscene` and so on,
  so what the app fetched stays separate from what you put there
- It arrives with its cover and description already on, because the catalogue's
  own entry is handed to the scraper rather than the file being looked up again
  by name
- A recording plays itself back when it arrives
- Entries the archive cannot distribute stay on the list, greyed, with the
  reason — a game that was announced and cancelled is worth knowing about

Checked on a device. A game that arrives as several files has not been tried
there, and the recordings ZXInfo hosts elsewhere do not appear to be offered
through it at all.


### When a game wants a particular machine

<a href="docs/screenshots/setup-dialog.jpg"><img src="docs/screenshots/setup-dialog.jpg" width="400" alt="Arkanoid asking whether to switch to 48K and Kempston, with a box to remember it"></a>

Where the database knows what a game wants, opening it offers to set the
machine and the joystick to match — once, with a box to remember the answer for
that game. *Skip* leaves everything alone.

Where the game was scraped from ScreenScraper and came with a pad layout, the
same dialog can offer its keys instead of a joystick, as a profile named after
the game. That is a different fact from a different service: which keys a pad
sends, rather than which interface the game listens to.


## Getting around

**The quick bar** — a strip across the top of the window, either way up. Tap the
picture to bring it back; it fades after three seconds.

| | |
| --- | --- |
| Files | open one, and the last ten |
| States | save, load, and the quick pair named after what is running |
| Machine | pause, change machine, reset, NMI |
| Capture | screenshot, GIF, MP4, and the folder they go to |
| On screen | the keyboard and the joystick, and the four settings worth changing mid-game: joystick interface, key profile, keyboard, mouse |
| Display | scanlines, CRT, video output, border, lamps |
| Fast forward | hold for 500%, silent |
| Fullscreen | |

<a href="docs/screenshots/main-menu.jpg"><img src="docs/screenshots/main-menu.jpg" width="400" alt="The main menu"></a>

**☰** has *Library*, *Open file…*, *Open recent…*, *Machine…*, *States…*,
*Pokes…*, *Music…*, *Media…*, *Capture…*, *Controls…*, *Settings…*, *About
Zedex* and *Quit*. *Library…*, *Open recent…* and *Music…* appear when there is
something for them to show. **Back** never
exits: it leaves fullscreen, or goes up a menu page, or opens ☰. *Quit* warns
about unsaved disks first.

**Settings** has seven tabs — Machine, Tape, Display, Controls, Sound, Library,
App. The first five are the Spectrum; the last two are Zedex: the library and
its folder, then language, folders, formats, updates.
Everything that persists is there, exactly once; ☰ and the bar are shortcuts to
the same settings.

**Activity lamps** — tape, disk, memory card, AY, keyboard, joystick, mouse.
Blue reads, amber writes; the AY is three bars, one per channel. A lit joystick
lamp with a dead stick means the game wants a different interface.

Fullscreen gives the picture their strip back, and keeps one of them: the disk,
where the strip itself would be — under the picture upright, beside it sideways —
for as long as a drive is turning and not a moment longer. It is the lamp that answers "is it safe to close this yet".
Turning the indicators off in *Settings › Display* turns that off too.


## ES-DE

If you use [ES-DE](https://es-de.org) as your frontend, *Settings › App › Add to
ES-DE* writes Zedex into its list of Spectrum emulators — its own entries are
kept, and the row only appears when ES-DE is installed. Games then launch
straight from ES-DE into Zedex.

Unless Zedex already has All files access it will ask to be shown ES-DE's folder,
once; nothing outside that folder is touched.

*Settings › Library › Link to ES-DE* goes the other way: what ES-DE has scraped —
names, descriptions, artwork, videos and manuals — becomes Zedex's own library.
Only the words are copied; the pictures stay where ES-DE keeps them. Press it
again to take in what has changed since.


## Languages

English, German, Spanish, French, Italian, Polish, Czech, Russian and
Ukrainian. It follows the phone's language, and *Settings › App › App language*
overrides it for Zedex alone.


## The memory card

A DivMMC with esxDOS on it. Two things are yours to bring:

1. **Firmware.** `ESXMMC.BIN`, the 8K DivMMC build, from
   [esxdos.org](http://www.esxdos.org/). *Settings › Machine › DivMMC firmware*.
2. **A card image.** ☰ *Media… › Insert card…*. An `.hdf`, or a raw image such
   as a MiSTer `.vhd`, which gets an HDF header written for it. It needs
   esxDOS's own `BIN` and `SYS` folders on it.

Switch on *DivMMC interface* in the same place; the machine resets. Then `.ls`
lists the card, `.` commands are esxDOS's, and ☰ *Machine… › NMI* opens its file
browser. Changes are written back once a second and whenever the app is paused.


## No ads, no tracking

No advertising, no analytics library, no accounts, and nothing stored about you
anywhere. The whole app carries two libraries, one for the settings screen and
one for the library's own list. Internet access is used for four things and all
are yours to ask for: fetching a ROM set, asking GitHub whether there is a newer
release than the one you are running, looking a game up when you scrape it, and
browsing the catalogue when you open that tab. Scraping and the catalogue send
nothing but what you asked about and the app's own name and version.

That second one is counted, and it is the only measure of use this project has:
the check fetches the small checksum file published beside your version's
download, and GitHub counts downloads, so the totals say roughly how often the app
is started and which versions are still in use. A total, and nobody in it who can
be picked out. *Settings › App › Check for
updates* stops it. The full account is in [docs/PRIVACY.md](docs/PRIVACY.md).


## Reporting a problem

*About Zedex › Report a problem* writes down the version, the device, the Android
version, where your files are and the settings that change what the app does —
and the crash, if it stopped last time. You see all of it, can edit any of it,
and it goes by your own mail app. Nothing is collected in the background and
nothing is sent unless you send it.


## What the Google Play version does not do

The Play build is the same emulator — same core, same machines, same settings —
but Play does not allow it two permissions, and two things go with them.

| | |
| --- | --- |
| **Updating itself** | Play updates its own apps, so that build contains neither the code nor the `REQUEST_INSTALL_PACKAGES` permission to install an APK, and *Settings › App › Check for updates* is not there |
| **Any folder as the data folder** | without All files access the data folder can only be somewhere Android gives an app for free: `Documents/Zedex`, the app's own storage, or `Android/data/…` on internal storage or an SD card. Not `/storage/emulated/0/Zedex`, and there is no *Choose folder…* |

Everything else is the same, *Add to ES-DE* included — it asks to be shown
ES-DE's folder once instead of wanting access to everything. Screenshots and
recordings still reach your gallery; `Pictures/Zedex` needs no permission
either.

The build from [Releases](https://github.com/dimitriuz/zedex/releases) does both.
Either build can be granted nothing at all and still work; the difference is only
what it may ask for.


## Keeping it up to date

The build from Releases here offers to update itself: one question to GitHub when
the app starts, and if there is a newer release, a note saying so. Say yes and it
downloads that APK, checks it against the `.sha256` published beside it, and
hands it to Android's installer — which will ask you to allow Zedex to install
apps, once. *Settings › App › Check for updates* turns the whole thing off. The
Play build has none of this, as above.


## Not yet

- A second catalogue. zxart.ee shaped the seam this one goes through and is not
  wired up yet
- Importing more than one thing at a time. A queue is a different feature with
  different manners
- Suggestions as you type in the catalogue. The archive asks to be asked
  gently, and a request per keystroke is the opposite of that
- Narrowing the catalogue to tapes or disks, which the archive supports and the
  tab has nowhere to put yet
- A debugger, which the core supports and nothing yet exposes


## Licence

Zedex is free software under the **GNU General Public License, version 2 or (at
your option) any later version**. The full text is in [LICENSE](LICENSE).

`vendor/` keeps its own upstream copyright — see `vendor/fuse-1.9.1/AUTHORS` and
`vendor/libspectrum-1.6.3/AUTHORS`. Everything outside it is © 2026 Dmitrii
Leshchenko.

The name **Zedex** and the app icon are not covered by the GPL, which grants no
trademark rights. Fork the code freely; ship it under your own name.

**The ROMs are not under the GPL and are not ours.** Most are Fuse's set,
redistributed by permission of the copyright holders — Amstrad for the Sinclair
machines, others for the rest. [README.copyright.md](roms/README.copyright.md)
is that permission, copied from Fuse whole. **No games are included.**

**The demo's music is not ours.** The tune the shipped demo tape plays is *Time
Up* by [shiru8bit](https://opengameart.org/users/shiru8bit), from OpenGameArt
under **CC-BY 3.0**, used with attribution and otherwise unchanged.

**The cheats are not ours.** They come from
[The Tipshop](https://www.the-tipshop.co.uk/), run by Gerard Sweeney. The
machine-readable form is *AllTipshopPokes*, gathered by Lady Eklipse and
distributed with
[ZX Pokemaster](https://github.com/eklipse2009/all-tipshop-pokes).

**The catalogue is not ours either.** Every name, description, screen, cover,
manual and file the library scrapes or imports from ZXInfo comes out of
[ZXDB](https://github.com/zxdb/ZXDB) — an open database created by **Einar
Saukas**, built from **Martijn van der Heide**'s original World of Spectrum,
**Jim Grimwood**'s SPOT/SPEX/TTFn, **Daren Pearcy**'s RZX Archive and **Chris
Bourne**'s ZXSR, all imported with their consent, and corrected and extended by
many hands since. Zedex reaches it through the open API at
[ZXInfo](https://zxinfo.dk/), and the files it names are served by
[Spectrum Computing](https://spectrumcomputing.co.uk/).

ZXDB asks only that anyone using it says so, which is what this paragraph is
for; its formal model is the
[Open Database License](https://en.wikipedia.org/wiki/Open_Database_License)
(ODbL 1.0). It also records, per entry, whether the copyright holder allowed
free distribution — where one has not, the entry stays on the list with that
reason shown against it, and the archive does not serve the file.
