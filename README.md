# Zedex

**Zedex** — a ZX Spectrum emulator for Android 11+, with a native front
end: OpenGL ES rendering, AAudio output, a touch keyboard carrying every
legend the real machine had, and menus that belong on a phone rather than in
the emulated screen.

> Zedex uses [Fuse](http://fuse-emulator.sourceforge.net/) as its emulation
> backend, unmodified and unpatched.
>
> This page is about using the app. Building it, testing it and cutting a
> release are in [docs/DEVELOPING.md](docs/DEVELOPING.md); how the core is
> wired in, and how it could be replaced, in
> [docs/INTERNALS.md](docs/INTERNALS.md).

> **No ROMs ship with the app or live in this repository.** On first run it
> says so and offers three ways to get them: **Get ROMs** downloads a set from
> <https://archive.org/details/zx-roms-fuse-roms>, or you can point it at a
> folder already on the device, or pick the files — or a zip — yourself.
> Whether you may download and use ROM images depends on the law where you
> are, and the app says so before fetching anything.

> **Developed with AI assistance.** Most of the code, the tests and this
> document were written by Claude (Anthropic's Claude Code), directed and
> reviewed by a human. Every feature described here was checked by running
> it on a device, and where something is unverified this page says so.

## Features

**The machine**

- **16 machines**, 16K through Scorpion, switchable while running and
  remembered for next time
- **Speed control** from 25% to 500% — the fast-forward
- **Reset** and **NMI**, the magic button real hardware had
- **Issue 2 keyboard** for the early 48K games that need it

**Loading and saving**

- **Every format worth reading** — snapshots, tapes, disks, cartridges,
  microdrive images, RZX recordings. The file identifies itself, and the
  machine switches when the media needs one: a `.dsk` brings up a +3, a
  `.trd` a Beta-equipped machine
- **Opens files from other apps**, and from the Files app
- **Fast tape loading**, or the real thing in real time with the loading
  noise and border stripes
- **Save states**, as many as you like, each named and showing the screen as
  it was when it was written
- **Writes tapes back** — a BASIC `SAVE "name"` reaches a `.tap` file
- **Writes disks back**, per drive, including a disk you made and the
  machine formatted itself

**Capture**

- **Screenshots** as PNG, at the machine's own size, pixel for pixel
- **Recording** to **GIF** or **MP4**, chosen when you start, written while
  you play rather than collected in memory
- **Open recordings folder** hands the folder to the file manager

**Comfort**

- **GPU-scaled display**, 4:3 in either orientation, no restart on rotate
- **Five landscape layouts** — keyboard below, over the screen, beside it on
  either side, or gone entirely
- **On-screen keyboard** drawn from the real thing, so every key carries its
  BASIC keyword, symbol-shift character, colour and extended-mode token —
  and **either shift latches** on a long hold
- **Hardware keyboards** work exactly as they do on the desktop
- **Every key is named** to accessibility, so a screen reader reads them out
- **Folders you choose** for data and for content
- **Survives backgrounding** without losing the drawing surface
- A **rubber-key icon** of its own — adaptive, themed on Android 13+, with a
  matching splash
- Emulation paced by the audio clock, measured at **50.28 fps**

## Machines

All sixteen are in the ☰ **Machine…** menu; which ones you can actually boot
depends on the ROMs you provide.

| Family | Machines |
| --- | --- |
| Sinclair | Spectrum 16K · 48K · 48K (NTSC) · 128K · +2 · +2A · +3 · +3e |
| Timex | TC2048 · TC2068 · TS2068 |
| Clones | Pentagon 128K · 512K · 1024K · Scorpion ZS 256 |
| Enhanced | Spectrum SE |

## Hardware

What the app reaches today:

| | |
| --- | --- |
| **Beta 128 / TR-DOS**, drives A: to D: | on Pentagon and Scorpion, or whenever a `.trd`/`.scl` is opened |
| **+3 floppy**, drives A: and B: | on a +3 or +3e |
| **AY-3-8912** | on the 128K-family machines, with its own volume |
| **Beeper** | with its own volume |
| **Timex SCLD** video, including hi-res modes | on the Timex machines |
| **Tape deck** | loading, and saving what the machine writes |
| **Keyboard** | on screen or physical |

A great deal more is already emulated and none of it is reachable yet,
because there is no screen to switch it on — see below.

## Not yet

**Would unlock the most:** a peripherals screen. All of this is already
emulated, and every one of them is a single setting away:

- Interface I with microdrives, RS-232 and the network; Interface II
  cartridges
- +D, DISCiPLE, Opus Discovery, Didaktik 80 disk interfaces
- Multiface One, 128 and 3
- DivIDE, DivMMC, ZXATASP, ZXCF, SimpleIDE, ZXMMC storage
- SpecDrum, Fuller Box, Melodik, Covox and Currah µSpeech sound
- Spectranet, SpeccyBoot and the TTX2000S teletext adaptor
- Kempston mouse, ZX Printer

**The picture**

- CRT, scanline and smoothing filters, for something closer to a television
  than to sharp pixels

**Playing**

- An on-screen joystick. Cursor, Kempston, Sinclair 1 and 2, Timex 1 and 2
  and Fuller are all emulated; nothing yet maps to them. The landscape
  layouts are where it goes — each one already decides where its pieces sit
- Rewind, and playing back RZX recordings as recordings

**Capture**

- Sound in recordings. Both formats are video only
- A resolution change mid-recording — a Timex hi-res mode — is skipped
  rather than handled

**Odds and ends**

- Renaming a save state, and writing a disk back over the file it came from
- Recording a tape in real time, rather than through the save trap
- Native dialogs in place of the last of the core's own ones
- A debugger, which the core supports and nothing yet exposes

## Tested on

An Android 16 x86_64 emulator (API 36), cross-built for arm64-v8a. Four
instrumentation tests cover the disk and capture paths.

## Using it

**ROMs first.** A Spectrum cannot run without one, so until there are ROMs the
screen carries a panel saying what is missing and three ways to fix it: *Get
ROMs* fetches a set from archive.org and unpacks it — it warns first that the
images are somebody else's copyright and that the law where you are is yours to
know — *Choose folder…* takes every `.rom` out of a folder on the device and
out of the folders inside it, and *Choose files…* takes the files, or a zip, you
pick. Note that Android will not grant a folder on `Download`, so ROMs sitting
there have to be picked as files.

If the ROMs turn out not to be the ones the machine needs, the panel comes back
saying so, with a *Restart* button — the emulator cannot be started twice in
one run, so trying again means a fresh start.

**The screen** fills whatever room the keyboard leaves, always 4:3 and always
scaled on the GPU, in either orientation. Rotating does not restart the
emulator.

**Sideways, five layouts**, from ☰ *Landscape layout…* or from settings:

| | |
| --- | --- |
| **Keyboard below the screen** | capped at two fifths of the height, so the picture keeps its full width |
| **Keyboard over the screen** | translucent across the bottom; the screen keeps the whole window |
| **Keyboard left, screen right** | an even split, each centred in its half |
| **Screen left, keyboard right** | the same, mirrored |
| **No keyboard** | the whole window is the machine, for a physical keyboard |

Portrait has one arrangement and ignores the setting; the screen sits at the
top, with whatever height is left over in one band above the keyboard.

**The keyboard** is drawn from the real one, so every key carries its
BASIC keyword, symbol-shift character, colour and extended-mode token. Two
fingers give a real shifted key; alternatively **hold either shift for 400ms
to latch it** (it turns amber) until you tap it again. That is how you get
BREAK — Caps Shift and Space. A physical keyboard works too, exactly as it
does on the desktop.

**Folders** are yours to choose, in settings. *Data folder* holds `roms`,
`states`, `tapes`, `disks`, `screenshots` and `recordings`: pick one of the
roots the device offers — internal storage, shared storage, an SD card — or
*Choose folder…* for anywhere at all, which needs Android's **All files
access**. Whatever is already saved moves with it.
*Content folder* is where **Open file…** starts, granted through the document
picker.

Two things Android will not allow as a chosen folder: the root of shared
storage, and `Download`. The picker refuses both and says so; make a subfolder
instead — it has a button for exactly that.

**The ☰ button** slides a sheet in from the edge, grouped into Files, Media,
Machine and Zedex, leaving the screen visible behind it. Tap the screen or press
back to dismiss it. It holds:

- **Open file…** — anything the emulator can read: snapshots (`.z80`, `.sna`,
  `.szx`, …), tapes (`.tap`, `.tzx`, `.pzx`, `.csw`, …), disks (`.dsk`,
  `.trd`, `.scl`, `.mgt`, `.udi`, …), cartridges, microdrive images and RZX
  recordings. The file is identified by its contents and put wherever it
  belongs, switching machine first if the media needs one — a `.dsk` brings
  up a +3, a `.trd` or `.scl` a Beta-equipped machine. Tapes autoload.
  Files can come from elsewhere too: the app accepts `ACTION_VIEW`, so a
  file manager can hand it a tape directly. Note that tapes do **not** switch
  machine — only disks do — so choose the machine before loading a 128K-only
  tape.
- **Save state…** / **Load state…** — as many saves as you like, each named
  and showing the screen as it was when it was written. Saving offers *Add
  new snapshot* first, named after whatever media is loaded and editable
  before it is written; picking an existing one overwrites it, with a
  confirmation. Long-press deletes.
- **Media…** — *Save tape…* writes what the machine has put on its tape to a
  `.tap` (or `.tzx`, if you type that extension) in the data folder, which is
  how a BASIC `SAVE "name"` reaches a file. *New tape* throws the current one
  away so a save does not append to a game you loaded earlier. Any drive with
  a disk in it is listed too, so a disk the machine has written to can be
  saved the same way.
- **Disks…** — every drive the running machine has, with what is in it, and
  per drive: *Load disk…*, *New disk*, *Save…* and *Eject*. The drives follow
  the machine, so a +3 shows its two and a Pentagon its four Beta ones.
- **Capture…** — *Save screenshot* writes the emulated screen as a PNG at
  its own size, 320x240 and pixel for pixel. *Record a GIF* or *Record an
  MP4* starts filming it; the same menu then offers *Stop recording*, and the
  toast that follows arrives when the file is really finished rather than
  when you asked for it. Both go in the data folder, named after whatever is
  loaded, and *Open recordings folder* hands that folder to the file manager.
- **Settings…** — see below.
- **Machine…** — all sixteen machines, with the running one checked. The
  choice is remembered for the next launch.
- **Reset** — asks first, since it discards machine state.
- **NMI** — the magic button of the real hardware. What it does depends on the
  machine; see below.

Anything that completes without visible effect — a blank disk, an eject, an
NMI, a reset — says so with a toast, since the emulated screen often looks
identical either way.

**Settings** covers:

| | |
| --- | --- |
| **Machine at startup** | which machine to boot; the ☰ switcher writes here too |
| **Issue 2 keyboard** | early 48K keyboard behaviour a few games depend on |
| **Fast loading** | ROM traps plus loader acceleration — a turbo loader showing a seven-minute countdown finishes in seconds. Off gives the real thing, border stripes and all |
| **Loading sound** | the loading noise, which only exists when a tape runs in real time |
| **Autoload media** | whether inserting a tape types `LOAD` for you |
| **Sound**, **AY volume**, **Beeper volume** | restart the sound subsystem when changed |
| **Black and white TV** | the monochrome palette |
| **Keep the screen on** | Android's, not the emulator's |
| **Speed** | 25% to 500%; this is the fast-forward |

Everything except the startup machine takes effect immediately, including
while a tape is loading.

## Licence

Zedex is free software under the **GNU General Public License, version 2 or
(at your option) any later version**. The full text is in [LICENSE](LICENSE).

It could not be anything else. Fuse and libspectrum are GPL-2.0-or-later, and
the Android front end is not a separate program that talks to them — it is
compiled into the same binary, as one of the core's own interchangeable user
interfaces. The app is a single combined work, so it carries the core's terms.

What that means in practice:

- Use it, study it, change it, and pass it on, commercially or not.
- If you distribute it, or anything derived from it, give your recipients the
  complete source under these same terms. It cannot be folded into a
  closed-source product.
- No warranty. See sections 11 and 12.

`vendor/` stays under its own upstream copyright — see
`vendor/fuse-1.9.0/AUTHORS` and `vendor/libspectrum-1.6.2/AUTHORS`. Everything
outside it is © 2026 Dmitrii Leshchenko.

The name **Zedex** and the app icon are not covered by the GPL, which grants
no trademark rights. Fork the code freely; ship it under your own name.

The Spectrum ROMs are neither included nor licensed here; the note at the top
of this README says where to get them.
