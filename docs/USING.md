# Using Zedex

Where the buttons are. What each feature *is* lives in the
[README](../README.md); this is the tour of the controls — the bar across the
top of the machine, the ☰ sheet behind it, what the indicators mean, and the
one part of the app that needs setting up before it works.

- [The quick bar](#the-quick-bar)
- [The bar over a game's details](#the-bar-over-a-games-details)
- [The menu sheet](#the-menu-sheet)
- [Settings](#settings)
- [The indicators](#the-indicators)
- [Controller hotkeys](#controller-hotkeys)
- [Setting up the memory card](#setting-up-the-memory-card)

# The quick bar

A strip of icons across the top of the window, either way up. It fades after
three seconds — **tap the picture to bring it back**.

<a href="screenshots/quick_bar.jpg"><img src="screenshots/quick_bar.jpg" width="400" alt="The quick bar with a group open"></a>

Six of the icons are *groups*: they open a short list of words underneath
rather than doing anything themselves, and tapping the same icon again puts the
list away. The rest act at once.

Rows that would be a lie are not drawn at all rather than greyed. *Stop
recording* is only there while something is recording, *Quick load* only once
there is something to load, the keyboard and indicator rows are gone in
fullscreen because fullscreen puts both away whatever the row promised, and
*Turbo* appears only on the machines that have it.

## <img src="icons/folder.svg" width="20" align="top"> Files…

| | | |
| --- | --- | --- |
| <img src="icons/folder.svg" width="16"> | **Open file…** | the picker, starting in your content folder |
| <img src="icons/file.svg" width="16"> | *the file's name* | the last ten, newest first, under a line |

An entry you opened from inside a zip comes back as the entry, not the archive.
A file whose grant has since gone — moved, deleted, or handed over for one
launch only — drops off the list instead of sitting there failing.

## <img src="icons/bookmark.svg" width="20" align="top"> States…

| | | |
| --- | --- | --- |
| <img src="icons/save.svg" width="16"> | **Save state…** | the grid of cards, each showing the screen it holds |
| <img src="icons/load.svg" width="16"> | **Load state…** | the same grid, to load from |
| <img src="icons/save.svg" width="16"> | **Quick save** | under a line, named after whatever is running, so every game keeps its own |
| <img src="icons/load.svg" width="16"> | **Quick load** | only once that game has a quick save |

## <img src="icons/chip.svg" width="20" align="top"> Machine…

| | | |
| --- | --- | --- |
| <img src="icons/pause.svg" width="16"> | **Pause** / **Resume** | says which way round it is |
| <img src="icons/swap.svg" width="16"> | **Change machine now…** | whatever is loaded reopens on the new one |
| <img src="icons/reset.svg" width="16"> | **Reset** | asks first |
| <img src="icons/bolt.svg" width="16"> | **NMI** | the magic button. No confirmation — half of what it is for is pressing it at a particular moment |
| <img src="icons/turbo.svg" width="16"> | **Turbo: on — 7 MHz** | the Pentagons and the Scorpion only. Takes effect between one frame and the next |

## <img src="icons/camera.svg" width="20" align="top"> Capture…

| | | |
| --- | --- | --- |
| <img src="icons/camera.svg" width="16"> | **Save screenshot** | a PNG, named after whatever is running |
| <img src="icons/record.svg" width="16"> | **Record a GIF** | |
| <img src="icons/film.svg" width="16"> | **Record an MP4** | |
| <img src="icons/stop.svg" width="16"> | **Stop recording** | in place of the two above, while one is running |
| <img src="icons/folder.svg" width="16"> | **Open captures folder** | under a line. All three go to `Pictures/Zedex`, which is what puts them in your gallery |

## <img src="icons/controls.svg" width="20" align="top"> Controls…

The joystick, the keyboard and the mouse, without leaving the game.

| | | |
| --- | --- | --- |
| <img src="icons/joystick.svg" width="16"> | **Show** / **Hide joystick** | |
| <img src="icons/keyboard.svg" width="16"> | **Show** / **Hide keyboard** | not in fullscreen, which puts it away regardless |
| <img src="icons/joystick.svg" width="16"> | **Joystick: Kempston** | under a line. Which of the Spectrum's interfaces the pad comes out as — or its keys |
| <img src="icons/bookmark.svg" width="16"> | **Keys: QAOPM** | the key profile, when the pad is sending keys |
| <img src="icons/keyboard.svg" width="16"> | **Keyboard: 128K** | which of the five skins is drawn |
| <img src="icons/mouse.svg" width="16"> | **Mouse: on** / **off** | the Kempston mouse |

The last four say what is set now and open the full list; the bar has no room
for eight joystick interfaces. ☰ *Controls…* reaches the same lists, and the
controller hotkeys besides.

## <img src="icons/display.svg" width="20" align="top"> Display…

| | | |
| --- | --- | --- |
| <img src="icons/scanlines.svg" width="16"> | **Enable** / **Disable scanlines** | |
| <img src="icons/crt.svg" width="16"> | **Enable** / **Disable CRT** | curvature, shadow mask and glow. Independent of scanlines — either, both or neither |
| <img src="icons/signal.svg" width="16"> | **Video: RGB** | steps through RGB, composite and RF |
| <img src="icons/border.svg" width="16"> | **Border: full** | steps through full, slim and cropped |
| <img src="icons/indicators.svg" width="16"> | **Show** / **Hide indicators** | under a line. Not in fullscreen |

Scanlines and CRT are two rows here and one choice of four in *Settings ›
Display*, because turning scanlines off to read something is a decision of the
moment.

## The three that act at once

| | | |
| --- | --- | --- |
| <img src="icons/fast_forward.svg" width="16"> | **Fast forward — hold** | 500%, silent, and only while held |
| <img src="icons/fullscreen.svg" width="16"> | **Fullscreen** | nothing but the picture and your thumbs |
| <img src="icons/info.svg" width="16"> | **Game details** | the game's page. Not shown for a game the store cannot name — a tape from a file manager, or an entry inside a zip |
| <img src="icons/menu.svg" width="16"> | **Menu** | ☰, below |

# The bar over a game's details

The details page borrows the same strip, so the bar wears a second face: over
the machine it is everything above, and over the details it is only what means
something there.

| | | |
| --- | --- | --- |
| <img src="icons/chip.svg" width="16"> | **Machine** | back to the game |
| <img src="icons/manual.svg" width="16"> | **Manual** | only when that game has one — three icons rather than four greyed ones |
| <img src="icons/close.svg" width="16"> | **Library** | closes the game |
| <img src="icons/menu.svg" width="16"> | **Menu** | ☰ |

# The menu sheet

**☰** at the right-hand end of the bar. It is the long form: everything the bar
carries, plus the three pages there was no room for — cheats, music, and the
tape deck with the drives.

Same rule as the bar — a row with nothing behind it is not drawn. *Library* and
*Online browser* need somewhere to go, *Open recent…* needs a recent file, and
*Music…* appears only for a game somebody has ripped the tunes out of.

| | | |
| --- | --- | --- |
| <img src="icons/library.svg" width="16"> | **Library** | your games. It is a separate task from the machine, so this is the only way across — Back here opens ☰ instead |
| <img src="icons/catalogue.svg" width="16"> | **Online browser** | the archives, when one is switched on |
| <img src="icons/folder.svg" width="16"> | **Open file…** | |
| <img src="icons/file.svg" width="16"> | **Open recent…** | the same last ten the bar's Files group has |
| <img src="icons/chip.svg" width="16"> | **Machine…** | the same page the bar's Machine group opens |
| <img src="icons/bookmark.svg" width="16"> | **States…** | the same page the bar's States group opens |
| <img src="icons/poke.svg" width="16"> | **Pokes…** | [below](#pokes) |
| <img src="icons/music.svg" width="16"> | **Music…** | [below](#music) |
| <img src="icons/tape.svg" width="16"> | **Media…** | [below](#media) |
| <img src="icons/camera.svg" width="16"> | **Capture…** | the same page the bar's Capture group opens |
| <img src="icons/controls.svg" width="16"> | **Controls…** | *Joystick…*, *Keyboard…* and *Mouse…* — the full lists behind the bar's own Controls group — plus *[Controller hotkeys…](#controller-hotkeys)* |
| <img src="icons/settings.svg" width="16"> | **Settings…** | |
| <img src="icons/info.svg" width="16"> | **About Zedex** | |
| <img src="icons/quit.svg" width="16"> | **Quit** | warns about unsaved disks first |

**Back** never exits: it leaves fullscreen, or goes up a menu page, or opens ☰.

## Pokes…

Cheats for whatever is loaded, then your own.

| | | |
| --- | --- | --- |
| | ***Cheats for …*** | the built-in database's, matched on the file's own fingerprint. Ones that take a number ask for it |
| | ***Cheats found by scraping*** | in its place, when the database has never heard of the file |
| <img src="icons/poke.svg" width="16"> | **Search the cheat database…** | for a game that loaded under a different name |
| <img src="icons/info.svg" width="16"> | **Look it up at The Tipshop…** | a web search, where the database came from |
| <img src="icons/poke.svg" width="16"> | **Poke once…** | fire an address and value now, without keeping it |
| <img src="icons/plus.svg" width="16"> | **Add a poke…** | keep one. Decimal, or hex after `0x`, `$` or `#` |
| | ***Stored*** | your own, each with a <img src="icons/trash.svg" width="14"> to forget it |

The database answers first and a scrape only tops it up, because the built-in
match is on the file itself and a scraped one is on whichever entry the scrape
settled on.

## Music…

Only for a game whose AY tunes somebody has ripped out and a scrape has
fetched. One row per tune; **Stop the music** appears while one is playing.

Playing a tune pauses the machine and puts it back exactly where it was
afterwards — mid-level, mid-jump.

## Media…

The tape deck, every drive the machine has, and the memory card slot, in three
sections.

**Tape**

| | | |
| --- | --- | --- |
| <img src="icons/folder.svg" width="16"> | **Load a tape…** | |
| <img src="icons/play.svg" width="16"> | **Play** / **Stop** | stop keeps the position, so playing again carries on |
| <img src="icons/rewind.svg" width="16"> | **Rewind to the start** | the first block; there is no winding |
| <img src="icons/tape.svg" width="16"> | **Blocks…** | what is on the tape, block by block |
| <img src="icons/save.svg" width="16"> | **Save tape…** | the machine writes to its tape as well as reading from it — a BASIC `SAVE "name"` appends, and this is what puts it on disk |
| <img src="icons/plus.svg" width="16"> | **New tape** | |

Everything but *Load a tape…* and *New tape* needs a tape in the deck.

**Drives**

One row per drive the machine actually has — so four on a Beta-equipped
Pentagon, two on a +3, and a *no drives* note on a 48K. Each says what is in it
and opens:

| | | |
| --- | --- | --- |
| <img src="icons/folder.svg" width="16"> | **Load disk…** | |
| <img src="icons/plus.svg" width="16"> | **New disk** | asks first if there is already one in the drive |
| <img src="icons/save.svg" width="16"> | **Save over …** | back over the file it came from, when it came from one |
| <img src="icons/save.svg" width="16"> | **Save as…** | a copy |
| <img src="icons/eject.svg" width="16"> | **Eject** | |

**Memory card**

| | | |
| --- | --- | --- |
| <img src="icons/folder.svg" width="16"> | **Insert card…** / **Change card…** | |
| <img src="icons/save.svg" width="16"> | **Write changes now** | they are written once a second anyway, and whenever the app is paused |
| <img src="icons/eject.svg" width="16"> | **Eject card** | |

The section says so when there is no firmware yet, or when the DivMMC
interface is off — a card can go in before either and will be waiting.
[Setting it up](#setting-up-the-memory-card) is at the foot of this page.

# Settings

Seven tabs — Machine, Tape, Display, Controls, Sound, Library, App. The first
five are the Spectrum; the last two are Zedex: the library and its folder, then
language, folders, formats, updates.

Everything that persists is there, exactly once. ☰ and the bar are shortcuts to
the same settings, never a second copy of them.

# The indicators

Tape, disk, memory card, AY, keyboard, joystick, mouse. Blue reads, amber
writes; the AY is three bars, one per channel. **A lit joystick lamp with a
dead stick means the game wants a different interface.**

Fullscreen gives the picture their strip back and keeps one of them: the disk,
where the strip itself would be — under the picture upright, beside it sideways
— for as long as a drive is turning and not a moment longer. It is the lamp
that answers "is it safe to close this yet". Turning the indicators off in
*Settings › Display* turns that off too.

# Controller hotkeys

One button is the hotkey, and every action is that button and another, so
nothing is taken from the game. The hotkey is **Select** by default and can be
any button, or None, in which case bindings fire on their own. Twenty-two
actions to bind; nine out of the box:

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

Rebind in ☰ *Controls… › Controller hotkeys…*: tap a row, press the button.

In the library a pad drives everything: the stick moves, **A** plays, **B**
goes up, **Y** favourites, **L1/R1** change tab, **L2/R2** page, **X**
searches and **Select** opens View, Sort and Filter.

# Setting up the memory card

A DivMMC with esxDOS on it. Two things are yours to bring:

1. **Firmware.** `ESXMMC.BIN`, the 8K DivMMC build, from
   [esxdos.org](http://www.esxdos.org/). *Settings › Machine › DivMMC
   firmware*.
2. **A card image.** ☰ *Media… › Insert card…*. An `.hdf`, or a raw image such
   as a MiSTer `.vhd`, which gets an HDF header written for it. It needs
   esxDOS's own `BIN` and `SYS` folders on it.

Switch on *DivMMC interface* in the same place; the machine resets. Then `.ls`
lists the card, `.` commands are esxDOS's, and ☰ *Machine… › NMI* opens its
file browser. Changes are written back once a second and whenever the app is
paused.
