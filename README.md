# Zedex

A ZX Spectrum emulator for Android 11 and later, with a native front end. The
emulation is [Fuse](http://fuse-emulator.sourceforge.net/), unmodified.

ROMs are included, so every machine runs out of the box. See
[Licence](#licence).

Building, testing and releases: [docs/DEVELOPING.md](docs/DEVELOPING.md).
How Fuse is wired in: [docs/INTERNALS.md](docs/INTERNALS.md).

Most of the code was written by Claude, directed and reviewed by a professional software developer.


## Machines

| Family | Machines |
| --- | --- |
| Sinclair | 16K · 48K · 48K NTSC · 128K · +2 · +2A · +3 · +3e |
| Timex | TC2048 · TC2068 · TS2068 |
| Clones | Pentagon 128K · 512K · 1024K · Scorpion ZS 256 |
| Other | Spectrum SE |

Speed 25%–500%. Reset and NMI. Issue 2 keyboard.


## Hardware

| | |
| --- | --- |
| Beta 128 / TR-DOS, drives A:–D: | Pentagon, Scorpion |
| +3 floppy, drives A: and B: | +3, +3e |
| AY-3-8912, ACB or ABC stereo | 128K family |
| Timex SCLD video, hi-res modes | Timex machines |
| DivMMC + memory card | any machine, with esxDOS firmware |
| Tape deck | read and write |
| Kempston mouse | drag on the picture, or the pad and a controller's stick |
| Joystick | Kempston · Cursor · Sinclair 1/2 · Timex 1/2 · Fuller · keyboard |
| Keyboard | on screen or physical |


## Files

Snapshots, tapes, disks, cartridges, microdrive images, RZX recordings. The file
identifies itself; the machine switches when the media needs one — a `.dsk`
brings up a +3, a `.trd` a Beta-equipped machine.

- Opens files from other apps
- Fast tape loading: off, safe (ROM loaders), turbo (custom loaders too)
- Save states, named, renamed and deleted from the list
- Writes tapes and disks out, or back over the file they came from
- Recent files: the last ten
- Screenshots as PNG, recording to GIF or MP4
- A demo tape in your tapes folder, offered on the first run


## Display

- GPU-scaled, fitted or at a whole-pixel scale
- Filters: scanlines, CRT (curve, shadow mask, glow), or both
- Video output: RGB, composite or RF, with colour bleed and noise
- Border in full, slimmed to a quarter, or cropped
- Black and white palette
- Fullscreen: picture and joystick only
- Second screen on dual-screen handhelds


## Controls

**On-screen keyboard** — the rubber 48K, the 128K plate, or your own input
method. Both are drawn rather than photographed, so they stay sharp at any size:
every key carries its BASIC keyword, symbol-shift character, colour and
extended-mode token, in the colours the machine prints them in. Two fingers give
a shifted key; holding a shift for 400ms latches it until tapped again.

Each also comes **slim** — every key, none of the BASIC. A key shows its own
legend and the character symbol shift gives it, the 48K keeps the cursor arrows
and DELETE, and the rows are barely more than half as tall, which sideways is
that much more picture.

In **fullscreen landscape**, where there is no keyboard at all, a button by the
pad brings a slim 48K one up *over* the picture, see-through, with a button of
its own to put it away again.

**On-screen joystick** — thumb pad, fire, and three key buttons in an arc.
Appears as any of the Spectrum's seven interfaces, or as keys. Eight-key
profiles: QAOPM, QAOP + Space, cursor keys, both Sinclair sets, WASD, and your
own — copied and deleted from the same list they are chosen in.

**Physical controllers** — no setup. Stick, hat and D-pad steer; A is fire; B, X
and Y are the key buttons; Start is Enter. The on-screen pad steps aside.

**Hotkeys** — RetroArch style: the modifier button plus another, so nothing is
taken from the game. The modifier is Select by default and can be any button, or
None, in which case bindings fire on their own. Twenty-two actions to choose
from; nine are bound out of the box:

| Action | Button |
| --- | --- |
| Pause or resume | B |
| Quit the app | Start |
| Quick save | R1 |
| Quick load | L1 |
| Fast forward | R2, while held |
| Fullscreen | X |
| Show or hide the keyboard | Y |
| Next key profile | R3 |
| Next joystick type | L3 |

Rebind in ☰ *Controls… › Controller hotkeys…*: tap a row, press the button.

**Kempston mouse** — a mode. While on, a drag moves the pointer, and fire and
the first key button are its buttons. Sensitivity is a setting.


## Cheats

3,682 games' cheats are built in, found by the file's fingerprint or by name.
Cheats that take a number ask for one. Pokes of your own can be applied once or
kept on a list; decimal, or hex after `0x`, `$` or `#`.


## Activity lamps

Tape, disk, memory card, AY, keyboard, joystick, mouse. Blue reads, amber
writes. The AY is three bars, one per channel. A lit joystick lamp with a dead
stick means the game wants a different interface.


## Using it

**First start** asks for two folders. Both can be changed later in
*Settings › Files*.

| | |
| --- | --- |
| Data folder | `roms`, `states`, `tapes`, `disks`, `screenshots`, `recordings`. Its own storage by default; somewhere shared keeps them if the app is uninstalled. Only the last two reach your gallery — a save state's thumbnail is not a photograph |
| Content folder | where *Open file…* starts |

**ROMs** are installed on first run, never over a file you put there yourself.
If any are missing a panel says which, and offers to fetch a set from
archive.org, take them from a folder, or take files you pick. *Run anyway*
starts on what is there.

### The quick bar

A strip across the top of the window, either way up.

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


Fullscreen hides everything but the picture and the joystick. Tap the picture to
bring the bar back; Back leaves fullscreen. There is no fullscreen button when
the controls are on a second screen.

**Back** never exits: it leaves fullscreen, or goes up a menu page, or opens ☰.
*Quit* exits, and warns about unsaved disks first.

### ☰

| | |
| --- | --- |
| Open file… | the picker |
| Open recent… | the last ten, newest first |
| Machine… | pause, change machine, reset, NMI |
| States… | a grid of cards, each showing the screen it holds; rename and delete on the card |
| Pokes… | the database for what is loaded, a search, and pokes of your own |
| Media… | the tape deck, every drive the machine has, and the card slot |
| Capture… | screenshots and recording |
| Controls… | joystick, keyboard, mouse, controller hotkeys |
| Settings… | |
| Quit | |

### Settings

Six tabs. Everything that persists is there, exactly once; ☰ and the bar are
shortcuts to the same settings.

| Machine | |
| --- | --- |
| Machine at startup | which machine to boot |
| Issue 2 keyboard | early 48K keyboard behaviour a few games need |
| DivMMC interface | resets the machine |
| DivMMC firmware | the 8K esxDOS ROM, which is yours to bring |
| Speed | 25% to 500% |

| Tape | |
| --- | --- |
| Fast loading | off, safe, turbo |
| Detect loaders | start and stop the tape when a loader asks |
| Loading sound | only exists when a tape runs in real time |
| Autoload media | type `LOAD` on inserting a tape |

| Display | |
| --- | --- |
| Filter | off, scanlines, CRT, or both |
| Advanced… | each effect's strength, video output, sharpness |
| Black and white TV | |
| Border | full, slim, none |
| Portrait / Landscape scale | fit, or whole device pixels per emulated pixel |
| Activity indicators | |
| Second screen | |
| Keep the screen on | |

| Controls | |
| --- | --- |
| Interface | which joystick the pad comes out as, or Keyboard |
| Key profile | and an editor for it |
| Hide the joystick for a controller | |
| Keyboard type | 48K or 128K, either of them slim, or Android's |
| Mouse sensitivity | |
| Controller hotkeys… | |

| Sound | |
| --- | --- |
| Sound, AY volume, Beeper volume | restart the sound subsystem |
| AY stereo separation | off, ACB or ABC |

| Files | |
| --- | --- |
| Data folder | |
| Content folder | |
| Save state format | SZX, Z80 or SNA |
| Save tape format | TAP or TZX |


## The memory card

A DivMMC with esxDOS on it. Two things are yours to bring:

1. **Firmware.** `ESXMMC.BIN`, the 8K DivMMC build, from
   [esxdos.org](http://www.esxdos.org/). *Settings › Machine › DivMMC firmware*.
2. **A card image.** ☰ *Media… › Insert card…*. An `.hdf`, or a raw image such
   as a MiSTer `.vhd`, which gets an HDF header written for it. It needs
   esxDOS's own `BIN` and `SYS` folders on it.

Switch on *DivMMC interface* in the same place; the machine resets.

Then `.ls` lists the card, `.` commands are esxDOS's, and ☰ *Machine… › NMI*
opens its file browser. Changes are written back once a second and whenever the
app is paused.


## Two screens

On a dual-screen handheld — an AYN Thor and its like — *Settings › Display ›
Second screen* moves the keyboard, the joystick, the lamps and the quick bar to
the other panel, leaving the first screen showing nothing but the machine. ☰,
settings, about and the hotkeys open on the panel too.

Android decides which display an input method appears on, so the *Android
keyboard* skin usually opens over the machine. It still types into the Spectrum.

The setting cannot be switched on with no panel to move to. Unplugging one
brings everything back.


## Not yet

- A file browser of our own, filtered by type and reading zip archives.
  Android's picker cannot filter by extension
- A library rather than a list of filenames: cover art, loading screens, maps,
  manuals, the publisher and the year, looked up in ZXDB and the archives behind
  it and kept on the device for offline use
- A debugger, which the core supports and nothing yet exposes


## Licence

Zedex is free software under the **GNU General Public License, version 2 or (at
your option) any later version**. The full text is in [LICENSE](LICENSE).

`vendor/` keeps its own upstream copyright — see `vendor/fuse-1.9.0/AUTHORS` and
`vendor/libspectrum-1.6.2/AUTHORS`. Everything outside it is © 2026 Dmitrii
Leshchenko.

The name **Zedex** and the app icon are not covered by the GPL, which grants no
trademark rights. Fork the code freely; ship it under your own name.

**The ROMs are not under the GPL and are not ours.** Most are Fuse's set,
redistributed by permission of the copyright holders — Amstrad for the Sinclair
machines, others for the rest. [README.copyright.md](roms/README.copyright.md)
is that permission, copied from Fuse whole.

**The demo's music is not ours.** The tune the shipped demo tape plays is *Time
Up* by [shiru8bit](https://opengameart.org/users/shiru8bit), from OpenGameArt
under **CC-BY 3.0**, used with attribution and otherwise unchanged.

**The cheats are not ours.** They come from
[The Tipshop](https://www.the-tipshop.co.uk/), run by Gerard Sweeney. The
machine-readable form is *AllTipshopPokes*, gathered by Lady Eklipse and
distributed with
[ZX Pokemaster](https://github.com/eklipse2009/all-tipshop-pokes).
