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
- **Save states**
- **Writes tapes and disks back**

**Capture**

- **Screenshots** as PNG
- **Recording** to **GIF** or **MP4**

**Comfort**

- **GPU-scaled display**, fitted or at a **whole-pixel scale**, with
  **scanline, CRT, dot-matrix and composite/RF filters**
- **Five landscape layouts**
- **On-screen Spectrum keyboard**, as the rubber **48K** or the **128K** plate —
  or **your phone's own keyboard** instead
- **On-screen joystick**, and **physical controllers**, which just work
- **A quick actions bar**, always on screen, with a **fullscreen** button that
  clears it and the keyboard away
- **Pause** — automatic when the app is not in front
- **Activity lamps** for the tape, disks, AY, keyboard and joystick — the quick
  bar's *Display…* puts them away, along with the keyboard and the joystick, and
  fullscreen gives the picture their strip back
- **Hardware keyboards**

## Hardware

What the app reaches today:

| | |
| --- | --- |
| **Beta 128 / TR-DOS**, drives A: to D: | on Pentagon and Scorpion |
| **+3 floppy**, drives A: and B: | on a +3 or +3e |
| **AY-3-8912** | on the 128K-family machines |
| **Timex SCLD** video, including hi-res modes | on the Timex machines |
| **Tape deck** | loading and saving |
| **Keyboard** | on screen or physical |
| **Joystick** | Kempston · Cursor · Sinclair 1/2 · Timex 1/2 · Fuller · keyboard |


## Not yet

**Would unlock the most:** a peripherals screen. All of this is already
emulated, and each of them is one setting away:

- Multiface One, 128 and 3
- DivIDE, DivMMC, ZXATASP, ZXCF, SimpleIDE, ZXMMC storage
- SpecDrum, Fuller Box, Melodik, Covox and Currah µSpeech sound
- Kempston mouse, ZX Printer


**Playing**

- A second joystick
- Rewind, and playing back RZX recordings


**Odds and ends**

- Renaming a save state, and writing a disk back over the file it came from
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

**The lamps** beside the picture — tape, disk, AY, keyboard, joystick — show
what the machine is busy with. Blue reads, amber writes, and the disk lamp only
says it is being used, since the emulator does not report which way. The AY is
three bars, one per channel, as tall as it is loud. A lit joystick lamp and a
dead stick means the game wants a different interface. Off in settings.

**The joystick** is a thumb pad, a fire button and three key buttons in an arc
beside it, and it goes wherever the picture is not.

☰ *Controls… › Joystick…* turns it off and on, chooses which interface it comes
out as — including **Keyboard**, for the games that want QAOP rather than a
joystick — and picks the **key profile**: eight keys, five for the pad and three
for the buttons, named and switchable, with QAOPM, QAOP + Space, cursor keys,
both Sinclair sets and WASD built in. *Edit keys…* binds one by tapping the
control and then the key.

**A physical controller** needs no setting up: plug one in and it drives the same
five controls, through the same profile. The stick, the hat and the D-pad steer,
**A** is fire, and **B**, **X** and **Y** are the three key buttons. The rest are
the app: **Start** is Enter, **Select** puts the keyboard away and brings it back,
**L1** loads a state, **R1** saves one, and **R2** held runs the machine fast. The on-screen pad steps aside while a
controller is connected, which *Hide for a controller* turns off.


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
**Capture**, **Controls**, **Machine** (pause, change machine, reset), **Fast
forward** (hold it: 500% while held, silent, and back to your speed on release),
**Fullscreen**, and ☰.
Fullscreen clears the bar away and, sideways, the keyboard with it; tap the picture
to bring the bar back for a moment.

**Main menu**

| | |
| --- | --- |
| **Open file…** | straight to the picker |
| **States…** | save and load |
| **Media…** | the tape, and every drive the machine has |
| **Capture…** | screenshots and recording |
| **Machine…** | which machine, pause, reset, NMI |
| **Controls…** | the joystick and the keyboard |
| **Settings…** | the settings screen |

In full:

- **Open file…** — anything the emulator can read: snapshots (`.z80`, `.sna`,
  `.szx`, …), tapes (`.tap`, `.tzx`, `.pzx`, `.csw`, …), disks (`.dsk`,
  `.trd`, `.scl`, `.mgt`, `.udi`, …).
- **States…** — *Save state…* and *Load state…*: as many saves as you like,
  each named and showing the screen as it was when it was written. Long-press deletes.
- **Media…**
  - *Save tape…*
  writes what the machine has put on its tape to a `.tap` in the data folder, which is how a BASIC `SAVE "name"` reaches a
  file;
  - *New tape* throws the current one away so a save does not append to a game you loaded earlier.
  - *Load disk…*, *New disk*, *Save…* and *Eject*. The
  drives follow the machine, so a +3 shows its two and a Pentagon its four
  Beta ones, and a machine with none says so.
- **Capture…**
  - *Save screenshot* writes the emulated screen as a PNG at
  its own size.
  - *Record a GIF* or *Record an
  MP4* starts filming it;
  - *Stop recording*
  - *Open recordings folder* hands that folder to the file
  manager.
- **Machine…**
  - *Change machine…* lists all sixteen with the running one
  checked, and the choice is remembered for the next launch.
  - *Reset* asks
  first, since it discards machine state.
  - *Pause* stops the machine; a big play button over the picture starts it
  again. It pauses itself whenever the app is not in front.
  - *NMI* is the magic button of the real hardware; what it does depends on
  the machine.
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
- **About Zedex** — which version this is, which commit it was built from and
  when, and where the source is.

**Settings** is five tabs — Machine, Tape, Picture, Sound, Files — covering:

| | |
| --- | --- |
| **Machine at startup** | which machine to boot |
| **Issue 2 keyboard** | early 48K keyboard behaviour a few games depend on |
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
| **Portrait scale**, **Landscape scale** | fit to the screen, or a whole number of device pixels per emulated one |
| **Activity indicators** | the lamps beside the picture |
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
