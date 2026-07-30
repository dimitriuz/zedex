# Zedex

**Zedex** — a modern ZX Spectrum emulator for Android 11+, with a native front
end.

> Zedex uses [Fuse](http://fuse-emulator.sourceforge.net/) as its emulation
> backend, unmodified and unpatched.

> **No ROMs ship with the app or live in this repository.**
> But you can get them automatically on the first run, or provide your own.

> **Developed with AI assistance.** Most of the code, the tests and this
> document were written by Claude, directed and reviewed by a human.

> This page is about using the app. Building it, testing it and cutting a
> release are in [docs/DEVELOPING.md](docs/DEVELOPING.md); how the core is
> wired in, and how it could be replaced, in
> [docs/INTERNALS.md](docs/INTERNALS.md).

## Features

**The machine**

| Family | Machines |
| --- | --- |
| Sinclair | Spectrum 16K · 48K · 48K (NTSC) · 128K · +2 · +2A · +3 · +3e |
| Timex | TC2048 · TC2068 · TS2068 |
| Clones | Pentagon 128K · 512K · 1024K · Scorpion ZS 256 |
| Enhanced | Spectrum SE |

- **Speed control** from 25% to 500%
- **Reset** and **NMI**

**Loading and saving**

- **Every format worth reading** — snapshots, tapes, disks, cartridges,
  microdrive images, RZX recordings. The file identifies itself, and the
  machine switches when the media needs one: a `.dsk` brings up a +3, a
  `.trd` a Beta-equipped machine
- **Opens files from other apps**
- **Fast tape loading**
- **Save states**, named, renamed and deleted from the list itself
- **Writes tapes and disks back** — a disk over the file it came from, or as a
  copy
- **A DivMMC memory card**, for **esxDOS** and a card full of games — bring the
  8K firmware and the card image, and a raw image gets its HDF header written
  for it

**Capture**

- **Screenshots** as PNG
- **Recording** to **GIF** or **MP4**

**Comfort**

- **GPU-scaled display**, fitted or at a **whole-pixel scale**, with
  **scanline, CRT, dot-matrix and composite/RF filters**, and the **border**
  shown in full, slimmed to a quarter, or cropped away
- **Five landscape layouts**
- **On-screen Spectrum keyboard**, as the rubber **48K** or the **128K** plate —
  or **your phone's own keyboard** instead
- **On-screen joystick**, and **physical controllers**, which just work — with
  RetroArch-style **hotkeys** for two dozen of the app's own actions
- **Kempston mouse**, driven by a drag on the picture or by the joystick
- **Cheats for 3,682 games** built in — found by the file's fingerprint, or by
  name — and pokes of your own
- **A quick actions bar**, always on screen, with a **fullscreen** button that
  clears it and the keyboard away
- **Pause** — automatic when the app is not in front, and nothing is drawn
  at all while the device sleeps
- **Activity lamps** for the tape, disks, AY, keyboard and joystick — the quick
  bar's *Display…* puts them away, along with the keyboard and the joystick, and
  fullscreen gives the picture their strip back
- **Two screens**, on a handheld built with them: the controls move to the
  other panel and the machine gets a whole screen to itself
- **Hardware keyboards**

## Hardware

What the app reaches today:

| | |
| --- | --- |
| **Beta 128 / TR-DOS**, drives A: to D: | on Pentagon and Scorpion |
| **+3 floppy**, drives A: and B: | on a +3 or +3e |
| **AY-3-8912** | on the 128K-family machines |
| **Timex SCLD** video, including hi-res modes | on the Timex machines |
| **DivMMC** and its memory card | any machine, with the esxDOS firmware |
| **Tape deck** | loading and saving |
| **Keyboard** | on screen or physical |
| **Joystick** | Kempston · Cursor · Sinclair 1/2 · Timex 1/2 · Fuller · keyboard |
| **Kempston mouse** | a drag on the picture, or the pad and a controller's stick |


## Not yet

- A file browser of our own — filtered by type, and reading straight out of zip
  archives. Android's picker cannot filter by extension
- A debugger, which the core supports and nothing yet exposes


## Using it

**ROMs first.** A Spectrum cannot run without one, so until there are ROMs the
screen carries a panel saying what is missing and three ways to fix it:

- *Get ROMs* fetches a set from archive.org and unpacks it
- *Choose folder…* takes every `.rom` out of a folder on the device and out of the folders inside it
- *Choose files…* takes the files, or a zip, you pick

The machine starts as soon as the ROMs are in. If some are missing the panel
says which, and *Run anyway* starts on the ones that are there.


**Sideways, five landscape layouts**, from ☰ *Controls… › Keyboard… › Landscape layout…*:

| | |
| --- | --- |
| **Keyboard below the screen** | capped at two fifths of the height, so the picture keeps its full width |
| **Keyboard over the screen** | translucent across the bottom; the screen keeps the whole window |
| **Keyboard left, screen right** | an even split; the keyboard at the foot of its half, the joystick above it |
| **Screen left, keyboard right** | the same, mirrored |
| **No keyboard** | the whole window is the machine, for a physical keyboard |


**The keyboard** is drawn from the real one, so every key carries its
BASIC keyword, symbol-shift character, colour and extended-mode token. Two
fingers give a real shifted key; alternatively **hold either shift for 400ms
to latch it** (it turns amber) until you tap it again. That is how you get
BREAK — Caps Shift and Space.

**The lamps** beside the picture — tape, disk, memory card, AY, keyboard,
joystick, mouse — show
what the machine is busy with. Blue reads, amber writes, and the disk lamp only
says it is being used, since the emulator does not report which way. The AY is
three bars, one per channel, as tall as it is loud. A lit joystick lamp and a
dead stick means the game wants a different interface. Off in settings.

**The joystick** is a thumb pad, a fire button and three key buttons in an arc
above and beside it — Enter, Space and CAPS SHIFT unless you change them — and it
goes wherever the picture is not.

☰ *Controls… › Joystick…* turns it off and on, chooses which interface it comes
out as — including **Keyboard**, for the games that want QAOP rather than a
joystick — and picks the **key profile**: eight keys, five for the pad and three
for the buttons, named and switchable, with QAOPM, QAOP + Space, cursor keys,
both Sinclair sets and WASD built in. *Edit keys…* binds one by tapping the
control and then the key.

**The Kempston mouse** is a mode, from ☰ *Controls… › Mouse…*. While it is on, a
drag across the picture moves the pointer, the pad and a controller's stick move
it too, and fire and the first button beside it are the mouse's two buttons —
there is a *Sensitivity* setting for how far a drag goes. It is only plugged in
while the mode is on, since it answers three ports a game might read for
something else, and the **mouse lamp** lights whenever a game reads them, which
is how you find out a game supports it — blue while a mouse is plugged in, amber
while the game is asking for one you have not turned on.

**A physical controller** needs no setting up: plug one in and it drives the same
five controls, through the same profile. The stick, the hat and the D-pad steer,
**A** is fire, **B**, **X** and **Y** are the three key buttons, and **Start** is
Enter. The on-screen pad steps aside while a controller is connected, which
*Hide for a controller* turns off.

**Hotkeys** work like RetroArch's: one button is the hotkey — **Select** unless
you change it — and everything else is that button *and* another, so nothing is
taken away from the game. Out of the box **Select+Start** quits, **+R1** quick-saves,
**+L1** quick-loads, **+B** pauses, **+X** is fullscreen, **+Y** hides the
keyboard, **+R2** held runs fast, and the stick clicks walk round the joystick
types (**+L3**) and the key profiles (**+R3**).
Two dozen actions to choose from in ☰ *Controls… › Controller hotkeys…*: tap a
row, press the button. The hotkey can be set to *None*, and then bindings fire on
their own.


**The memory card** — a **DivMMC** with **esxDOS** on it, which is a card full of
games and a filesystem the Spectrum can browse. Two things are yours to bring,
because neither is ours to ship:

1. **The firmware.** Get esxDOS from [esxdos.org](http://www.esxdos.org/) and
   take `ESXMMC.BIN` out of it — the 8K DivMMC build. In *Settings › Machine*,
   tap **DivMMC firmware** and pick it. The row then says *Loaded*.
2. **Switch on** *DivMMC interface*, in the same place. The machine resets: it
   is a card interface being plugged in, and esxDOS takes over the reset.
3. **The card.** ☰ *Media… › Insert card…*. Any card image will do — a `.hdf`,
   or a raw image such as a MiSTer `.vhd` or something `dd` wrote, which gets
   its HDF header written for it on the way in. It is copied into `cards/` in
   the data folder, because the machine writes to it, and the machine is reset
   so esxDOS reads the new card.
   The card needs esxDOS's own `BIN` and `SYS` folders on it — the same download
   — or nothing will run.

Then `.ls` at the BASIC prompt lists the card, `.` commands are esxDOS's, and ☰
*Machine… › NMI* opens its file browser: pick a game and it loads. **Changes are
written back for you**, once a second and whenever the app is paused, so a save
survives the phone being put away; *Write changes now* is there for when you
want to be certain.

**Two screens.** On a dual-screen handheld — an AYN Thor and its like —
*Settings › Picture › Second screen* moves the **keyboard, the lamps and the
quick bar** to the other panel: the bar across the top, the keys below it and
against the foot of the panel, the lamps in a row underneath. The first screen
is then nothing but the machine, as though fullscreen were on, with the joystick
still in the black beside it. ☰ opens on the machine's screen, since that is
where there is room for it.

The row says *No second screen on this device* and cannot be switched on when
there is no panel to move to; unplugging one brings everything back to the first
screen, and so does putting the app away.

**Folders** are yours to choose, in settings:

- *Data folder* holds `roms`,
`states`, `tapes`, `disks`, `screenshots` and `recordings`: pick one of the
roots the device offers — internal storage, shared storage, an SD card — or
*Choose folder…* for anywhere at all, which needs Android's **All files
access**. Whatever is already saved moves with it.

- *Content folder* is where **Open file…** starts, granted through the document
picker.

---

**The bar** is always there: across the top in portrait, smaller in the top right
corner sideways. Seven icons — **Files** (open, save a state, load one),
**Machine** (pause, change machine, reset, NMI), **Capture**, **Display** (hide
the keyboard, the joystick or the lamps; scanlines, CRT, video output and
border), **Fast
forward** (hold it: 500% while held, silent, and back to your speed on release),
**Fullscreen**, and ☰.
Fullscreen clears the bar away and, sideways, the keyboard with it; tap the picture
to bring the bar back for a moment.

**Main menu**

| | |
| --- | --- |
| **Open file…** | straight to the picker |
| **Machine…** | which machine, pause, reset, NMI — with the running one named under it |
| **States…** | save and load |
| **Pokes…** | one byte into memory: try one, or keep the ones that work |
| **Media…** | the tape deck, and every drive the machine has |
| **Capture…** | screenshots and recording |
| **Controls…** | the joystick and the keyboard |
| **Settings…** | the settings screen |
| **Quit** | closes the app, rather than leaving it paused in the background |

In full:

- **Open file…** — anything the emulator can read: snapshots (`.z80`, `.sna`,
  `.szx`, …), tapes (`.tap`, `.tzx`, `.pzx`, `.csw`, …), disks (`.dsk`,
  `.trd`, `.scl`, `.mgt`, `.udi`, …).
- **Machine…**
  - *Change machine…* lists all sixteen with the running one
  checked, and the choice is remembered for the next launch.
  - *Reset* asks
  first, since it discards machine state.
  - *Pause* stops the machine; a big play button over the picture starts it
  again. It pauses itself whenever the app is not in front.
  - *NMI* is the magic button of the real hardware; what it does depends on
  the machine.
- **States…** — *Save state…* and *Load state…*: as many saves as you like,
  each named and showing the screen as it was when it was written. The pencil on a
  row renames it, the bin deletes it — a long press does either.
- **Pokes…** — cheats. When a file is opened it is fingerprinted, and if it is
  one of the 3,682 games the built-in database knows, its cheats are listed at the
  top: tap one and it is poked. *Search the cheat database…* finds a game by name,
  for a save state or an odd dump; *Look it up at The Tipshop…* opens the site
  these cheats come from, which has the ones the database has not.
  A cheat that asks for a number — how many lives — asks before poking.
  *Poke once…* takes an address and a value, writes the byte and keeps nothing;
  *Add a poke…* takes a name as well and puts it on the list. Tapping a stored
  poke pokes it, the bin beside it forgets it. Decimal, or hex after `0x`, `$` or `#`.
- **Media…**
  - *Load a tape…* opens one, and *Play*, *Stop* and *Rewind to the start* work
  the deck. Stop keeps the position, so playing again carries on from there — and
  with *Detect loaders* on, Fuse stops the tape itself whenever nothing is loading;
  - *Blocks…* lists what is on the tape, marks the one the deck is at and winds to
  whichever you pick — for a multi-load game, or to skip past a side;
  - *Save tape…*
  writes what the machine has put on its tape to a `.tap` in the data folder, which is how a BASIC `SAVE "name"` reaches a
  file, and appears once there is something to write;
  - *New tape* throws the current one away so a save does not append to a game you loaded earlier.
  - *Load disk…*, *New disk*, *Save as…* and *Eject*. The
  drives follow the machine, so a +3 shows its two and a Pentagon its four
  Beta ones, and a machine with none says so;
  - *Save over “name”* is there as well for a disk that came from a file, and
  writes it back over that file — it asks first, since what is in the file now
  is replaced. Only what the machine changed changes; the format is the file's
  own, so a `.trd` goes back as a `.trd`.
  - *Insert card…* puts a card in the DivMMC — see **The memory card** above —
  with *Write changes now* and *Eject card* once there is one in. The page says
  so if the interface is off or has no firmware.
- **Capture…**
  - *Save screenshot* writes the emulated screen as a PNG at
  its own size.
  - *Record a GIF* or *Record an
  MP4* starts filming it;
  - *Stop recording*
  - *Open recordings folder* hands that folder to the file
  manager.
- **Controls…** — the two things you play with, each of which can be put away
  and neither of which the other replaces.
  - *Joystick…* has *Show on screen*, which of the Spectrum's seven interfaces
  the pad appears as or *Keyboard* for keys instead, *Keys…* for the profile the
  keys come from, and whether to *Hide for a controller*. A control shows the key
  it sends on its face.
  - *Keyboard…* has the same *Show on screen* — a game that only wants a
  joystick has no use for forty keys — a *Skin*: the rubber 48K, the 128K plate
  with its own DELETE, EDIT, GRAPH, arrows and punctuation, or **Android
  keyboard**, which puts your own input method up instead and types through it —
  and *Landscape layout…*, which is where the keyboard goes when it is out.
- **Settings…** — see below.
- **Quit** — ends the app rather than putting it away, which is all Back and
  Home do. A disk with changes nothing has written back gets a warning first.
- **About Zedex** — which version this is, which commit it was built from and
  when, and where the source is.

**Settings** is five tabs — Machine, Tape, Picture, Sound, Files — covering:

| | |
| --- | --- |
| **Machine at startup** | which machine to boot |
| **Issue 2 keyboard** | early 48K keyboard behaviour a few games depend on |
| **DivMMC interface** | a memory card and esxDOS, on any machine; resets it |
| **DivMMC firmware** | the 8K esxDOS ROM the interface needs, which is yours to bring |
| **Fast loading** | off, safe (ROM loaders) or turbo (custom loaders too) |
| **Detect loaders** | start and stop the tape when a loader asks for it |
| **Loading sound** | the loading sound, which only exists when a tape runs in real time |
| **Autoload media** | whether inserting a tape types `LOAD` for you |
| **Save tape format** | TAP or TZX, when you do not type an extension yourself |
| **Sound**, **AY volume**, **Beeper volume** | restart the sound subsystem when changed |
| **AY stereo separation** | off, ACB or ABC — the 128's three channels spread across two |
| **Black and white TV** | the monochrome palette |
| **Scanlines**, **CRT effect** | a dark line per emulated row; curve, shadow mask and glow, each with its own strength |
| **Dot matrix** | a handheld LCD: one square dot per emulated pixel, with a gap and a backlight |
| **Video output** | RGB, composite or RF — what the picture cost on the way to the telly, with colour bleed and noise |
| **Sharpness** | 100% is pixel for pixel; less softens the edges |
| **Border** | full, slim (a quarter of it) or none — less border is more picture |
| **Portrait scale**, **Landscape scale** | fit to the screen, or a whole number of device pixels per emulated one |
| **Activity indicators** | the lamps beside the picture |
| **Second screen** | the keyboard, the lamps and the bar on a handheld's other panel |
| **Keep the screen on** | Android's, not the emulator's |
| **Speed** | 25% to 500% |


## Licence

Zedex is free software under the **GNU General Public License, version 2 or
(at your option) any later version**. The full text is in [LICENSE](LICENSE).

`vendor/` stays under its own upstream copyright — see
`vendor/fuse-1.9.0/AUTHORS` and `vendor/libspectrum-1.6.2/AUTHORS`. Everything
outside it is © 2026 Dmitrii Leshchenko.

The name **Zedex** and the app icon are not covered by the GPL, which grants
no trademark rights. Fork the code freely; ship it under your own name.

The Spectrum ROMs are neither included nor licensed here; **ROMs first** above
says where the app can fetch a set from, and whether you may use them is the
law where you are.

**The cheats are not ours.** They come from
[The Tipshop](https://www.the-tipshop.co.uk/), run by Gerard Sweeney, which is
where three decades of Spectrum pokes have been collected; the machine-readable
form is *AllTipshopPokes*, gathered by Lady Eklipse and distributed with
[ZX Pokemaster](https://github.com/eklipse2009/all-tipshop-pokes).
