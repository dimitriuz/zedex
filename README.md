# FuseMobile

A ZX Spectrum emulator for Android 11+, built on
[Fuse](http://fuse-emulator.sourceforge.net/) — the Free Unix Spectrum
Emulator — with a native Android front end: OpenGL ES rendering, AAudio
output, a touch keyboard from Fuse's own artwork, and menus that belong on a
phone rather than in the emulated screen.

Fuse and libspectrum are used **completely unmodified**. Everything here is
build configuration, a Fuse UI backend of our own, and a thin Android app;
upstream is fetched, checksummed and never written to. How that is managed
without a single patch is described under [Keeping Fuse
unmodified](#keeping-fuse-unmodified).

> **No ROMs ship with the app or live in this repository.** On first run it
> creates an empty `roms` folder and offers to import them. The set Fuse
> expects, by the filenames it looks for, is at
> <https://archive.org/details/zx-roms-fuse-roms>.

> **Developed with AI assistance.** Most of the code, the tests and this
> document were written by Claude (Anthropic's Claude Code), directed and
> reviewed by a human. Every feature described here was checked by running
> it on a device — the disk-writing sections in particular record what was
> actually verified, and what was not.

## Features

**The machine**

- **16 machines**, 16K through Scorpion, switchable while running and
  remembered for next time
- **Speed control** from 25% to 500% — the fast-forward
- **Reset** and **NMI**, the magic button real hardware had
- **Issue 2 keyboard** for the early 48K games that need it

**Loading and saving**

- **Every format Fuse reads** — snapshots, tapes, disks, cartridges,
  microdrive images, RZX recordings. Fuse identifies the file itself and
  switches machine when the media needs one: a `.dsk` brings up a +3, a
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
- **On-screen keyboard** from Fuse's own artwork, so every key carries its
  BASIC keyword, symbol-shift character, colour and extended-mode token —
  and **either shift latches** on a long hold
- **Hardware keyboards** work exactly as they do on the desktop
- **Every key is named** to accessibility, so a screen reader reads them out
- **Folders you choose** for data and for content
- **Survives backgrounding** without losing the drawing surface
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

Fuse emulates a great deal more, and none of it is reachable yet because
there is no screen to switch it on — see below.

## Not yet

**Would unlock the most:** a peripherals screen. Fuse can already emulate all
of this, and every one of them is a single setting away:

- Interface I with microdrives, RS-232 and the network; Interface II
  cartridges
- +D, DISCiPLE, Opus Discovery, Didaktik 80 disk interfaces
- Multiface One, 128 and 3
- DivIDE, DivMMC, ZXATASP, ZXCF, SimpleIDE, ZXMMC storage
- SpecDrum, Fuller Box, Melodik, Covox and Currah µSpeech sound
- Spectranet, SpeccyBoot and the TTX2000S teletext adaptor
- Kempston mouse, ZX Printer

**The picture**

- CRT, scanline and sharp-bilinear shaders. The fragment shader in
  `native/ui/android/android_gl.c` is the only code that touches a pixel, so
  this is where they go
- In portrait the screen is centred above the keyboard, leaving wide empty
  bands; it should sit at the top

**Playing**

- An on-screen joystick. Fuse emulates Cursor, Kempston, Sinclair 1 and 2,
  Timex 1 and 2 and Fuller; nothing yet maps to them
- Rewind, and playing back RZX recordings as recordings

**Capture**

- Sound in recordings. Both formats are video only
- A resolution change mid-recording — a Timex hi-res mode — is skipped
  rather than handled

**Odds and ends**

- Renaming a save state, and writing a disk back over the file it came from
- Recording a tape in real time, rather than through the save trap
- Native dialogs in place of the last of Fuse's widget ones
- A debugger front end over Fuse's `debugger/` API
- `armeabi-v7a` — add it to `ABIS` and `abiFilters`; nothing should stop it
- The launcher icon is still SDL's, inherited from the sample project the
  first prototype started from and never replaced

## Tested on

An Android 16 x86_64 emulator (API 36), cross-built for arm64-v8a. Four
instrumentation tests cover the disk and capture paths; see
[Tests](#tests).

## Using it

**The screen** fills whatever room the keyboard leaves, always 4:3 and always
scaled on the GPU, in either orientation. Rotating does not restart the
emulator.

**The keyboard** is Fuse's own keyboard artwork, so every key carries its
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

**The ☰ button** opens:

- **Open file…** — anything Fuse can read: snapshots (`.z80`, `.sna`,
  `.szx`, …), tapes (`.tap`, `.tzx`, `.pzx`, `.csw`, …), disks (`.dsk`,
  `.trd`, `.scl`, `.mgt`, `.udi`, …), cartridges, microdrive images and RZX
  recordings. Fuse identifies the file itself and puts it wherever it
  belongs, switching machine first if the media needs one — a `.dsk` brings
  up a +3, a `.trd` or `.scl` a Beta-equipped machine. Tapes autoload.
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

**Settings** covers, in Fuse's terms:

| | |
| --- | --- |
| **Machine at startup** | which machine to boot; the ☰ switcher writes here too |
| **Issue 2 keyboard** | early 48K keyboard behaviour a few games depend on |
| **Fast loading** | ROM traps plus loader acceleration — a turbo loader showing a seven-minute countdown finishes in seconds. Off gives the real thing, border stripes and all |
| **Loading sound** | the loading noise, which only exists when a tape runs in real time |
| **Autoload media** | whether inserting a tape types `LOAD` for you |
| **Sound**, **AY volume**, **Beeper volume** | restart Fuse's sound subsystem when changed |
| **Black and white TV** | Fuse's monochrome palette |
| **Keep the screen on** | ours, not Fuse's |
| **Speed** | 25% to 500%; this is the fast-forward |

Everything except the startup machine takes effect immediately, including
while a tape is loading.

## Layout

```
vendor/          upstream sources, fetched by the build script, never modified
  fuse-1.9.0/          fuse-emulator 1.9.0
  libspectrum-1.6.2/   libspectrum 1.6.2
native/          our Fuse backend, compiled into Fuse's own build tree
  ui/android/          display, GLES renderer, JNI bridge, keysym map
  sound/               AAudio driver
scripts/
  build-native.sh      cross-compiles everything per ABI
  ui-tap.py            taps the app by text, for driving it from a terminal
  ui-type.py           types on the machine's keyboard the same way
build-native/    out-of-tree build trees + per-ABI install prefixes
app/
  src/main/            the app; jniLibs/ and assets/fuse/ are build outputs
  src/androidTest/     UI Automator tests
```

The app is a handful of classes: `FuseActivity` holds the menus,
`SpectrumKeyboardView` the keyboard, `Storage` decides where things live,
`Recorder` takes frames off the emulation thread and `GifRecording` /
`Mp4Recording` turn them into files.

## Building

Requires the Android SDK with NDK r27, plus `autoconf`-era build tools on the
host (`make`, `perl`, `pkg-config`, and a host `gcc` for libspectrum's
build-time codegen).

The two upstream tarballs are not in git. The build script downloads them into
`vendor/` on first run, verifies their SHA-256, and never writes to them again.

```sh
./scripts/build-native.sh              # all ABIs; ~90 s cold
ABIS=x86_64 ./scripts/build-native.sh  # single ABI
./scripts/build-native.sh clean

./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If your default JDK is newer than 21, point Gradle at Android Studio's JBR:
`JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug`.

The native build is deliberately **not** wired into Gradle: it is slow, rarely
changes, and Gradle just packages the prebuilt `.so` from
`app/src/main/jniLibs/`.

Files can also come from elsewhere: the app accepts `ACTION_VIEW`, so a file
manager can hand it a tape directly.

One thing worth knowing: tapes do **not** switch machine — Fuse only does
that for disks — so choose the machine before loading a 128K-only tape.

### Driving it from adb

`adb shell input keyevent` does **not** reach the app. Tapping the on-screen
keyboard with `adb shell input tap` does, which is enough to automate most
things; key coordinates follow from the artwork's 541x201 layout. Use
`input swipe x y x y 1200` to hold a key, for instance to latch a shift.

For the menus, tap by text rather than by coordinate — they move whenever a
menu gains an item:

```sh
scripts/ui-tap.py list                       # what is on screen
scripts/ui-tap.py "Disks" "Beta Disk A" "Save…"
```

It reads the view hierarchy through `uiautomator dump`. For the emulated
machine there is `scripts/ui-type.py`, which taps the keyboard artwork:

```sh
scripts/ui-type.py 'randomize usr 15616' ENTER
scripts/ui-type.py CS+SS SS+0 ' ' '"test"' ENTER   # extended mode: FORMAT
```

These are for poking at the app by hand. The test suite is proper
instrumentation; see below.

Media can be loaded without touching the picker at all:

```sh
adb shell "run-as com.fusemobile sh -c 'cat > /data/data/com.fusemobile/files/game.tap'" < game.tap
adb shell am start -a android.intent.action.VIEW \
    -d file:///data/data/com.fusemobile/files/game.tap \
    -n com.fusemobile/.FuseActivity
```

## How it fits together

### Keeping Fuse unmodified

Fuse's display, keyboard, menus, file selection and debugger all live in its
UI layer, and `ui/gtk3`, `ui/win32`, `ui/sdl2`, `ui/fb` and `ui/null` are peers
there — so an Android port belongs in that layer. Adding a `ui/android` to
Fuse's own build system would mean patching `configure.ac` and `Makefile.am`
and regenerating autotools output on every upgrade.

Instead the build configures Fuse `--with-fb`. That selects Fuse's portable
**widget UI** — the dialogs, menus, debugger and file selector, all software
drawn — and pulls in nothing framebuffer specific outside `ui/fb` itself; the
only coupling in the core is one `isatty()` guard in `ui.c`. The build then
never compiles `ui/fb`, and links `native/ui/android` in its place. The same
trick swaps `sound/nullsound.o` for the AAudio driver.

The script asks the generated Makefile for `fuse_OBJECTS`, `fuse_LDADD`,
`AM_CPPFLAGS` and friends rather than hardcoding them, so the substitution
follows upstream instead of drifting from it.

### The backend

- **`android_display.c`** writes palette indices exactly as `ui/fb` does,
  expands the frame to RGBA once, and leaves Fuse's software scalers at 1x:
  scaling and filtering are the GPU's job. It also implements
  `uidisplay_frame_save`/`_restore`, which `ui/fb` leaves as FIXMEs, so
  dialogs no longer corrupt the screen behind them.
- **`android_gl.c`** owns EGL and a GLES 3 context on the emulation thread and
  draws the frame as an aspect-corrected quad. The fragment shader is the only
  code that touches pixels, which is where CRT/scanline filters go.
- **`android_bridge.c`** is the Android boundary. Fuse's core is single
  threaded, so everything arriving from the UI thread is queued and replayed
  on the emulation thread from `ui_event()`. It also runs the window handover:
  `surfaceDestroyed()` blocks until the emulation thread has released the
  surface.
- **`keysyms.c`** maps Android keycodes to Fuse input keys, so physical keys
  and the on-screen keyboard share one path — including Caps Shift
  (`SHIFT_LEFT`) and Symbol Shift (`CTRL_LEFT`), which Fuse already maps.
- **`aaudiosound.c`** writes to AAudio and *blocks*, deliberately: that is what
  paces the emulator. Audio is the clock, not vsync and not a wall timer.
- **`ui_error_specific`** in `android_ui.c` turns Fuse's errors into Android
  toasts. Fuse would otherwise draw a Spectrum-styled modal into the emulated
  screen that only Enter or Escape dismisses — and, worse, block whatever
  raised it until then: a save to a lossy snapshot format did not write its
  file until the warning had been answered. `ui/widget/error.c` cannot simply
  be dropped, because `ui/widget/query.c` shares its `split_message`, so the
  build weakens that one symbol with `llvm-objcopy --weaken-symbol` and ours
  wins the link.

A key release is never run in the same queue pump as its press. The Spectrum
ROM scans the keyboard once per frame, so a press and release arriving
together — a synthesised tap, or a very fast finger — would otherwise be
invisible to the emulated machine.

### The menu

Everything the menu does goes through the same queue as keys, because none of
it is safe to call from the UI thread: the Android dialog queues a command and
the emulation thread runs `machine_select()`, `machine_reset()` or
`event_add( 0, z80_nmi_event )`. The machine list is snapshotted from Fuse's
`machine_types` on the emulation thread for the UI thread to read back.

A machine change can fail — Fuse falls back to 48K when a machine's ROMs are
missing — so the app checks what actually ended up running, says so, and
remembers that rather than what was asked for.

NMI is the magic button, and what it does depends on the machine. On Scorpion,
`z80_nmi()` pages ROM 2 — the Shadow service monitor — before jumping to
0x0066. On Beta-equipped machines such as Pentagon it pages TR-DOS instead.
Pentagon 512K and 1024K need no NMI to reach Gluck: their reset sets
`beta_active`, which selects ROM 2 at boot, so they start in the service ROM.

### Opening files

Fuse opens files by path and identifies them by content, so the Android side
does not need to know one format from another: the picked document is copied
out of its content provider into the cache — keeping its original name, since
libspectrum uses the extension as a hint — and the path is queued for the
emulation thread, which hands it to `utils_open_file()`.

### Saving tapes

The machine writes to its tape as well as reading from it. With fast loading
on, Fuse's `tape_save_trap()` catches the ROM's save routine and appends each
block to the tape held in memory, so `SAVE "name"` from BASIC lands there;
*Save tape…* then calls `tape_write()`, which picks TAP or TZX from the
extension. It needs fast loading, since that is what puts the trap in place —
without it the save goes out as audio that nothing is recording.

*New tape* clears `tape_modified` before calling `tape_close()`. Fuse would
otherwise ask whether to save first, through a widget dialog that only Enter
or Escape dismisses; Android has already asked by that point.

One thing worth knowing at the *Start tape, then press any key* prompt: press
Enter rather than Space. Space aborted the save with `D BREAK` in testing.

### Screenshots and recording

Fuse hands over a frame of palette indices, and both formats are built from
those rather than from the expanded picture. That is what makes this cheap
enough to run on a 50Hz machine:

- a **GIF** is a palette format too, so the indices are the pixels. Sixteen
  colours go in the global colour table and nothing is quantised or dithered
  — the file is exact. It is written frame by frame as they arrive, so length
  costs no memory, and the delay of each frame is measured rather than
  assumed, so a recording plays back at the speed it happened.
- an **MP4** goes through the device's own H.264 encoder, which wants YUV.
  Converting the sixteen palette entries once a recording turns every pixel
  into three table lookups.

Frames are copied out on the emulation thread — the callback happens while
Fuse is between frames, so the buffer is whole — and encoded on another. When
the encoder falls behind the frame is dropped rather than waited for: a
recording that skips beats a machine that stutters. A GIF is capped at 25 fps
because its delays are in hundredths of a second; an MP4 takes all 50.

Neither has sound yet.

*Open recordings folder* asks the file manager to show the folder, which
only works when the data folder is on shared storage — the app's own
directories are invisible to the rest of the system by design, and then the
path is all there is to offer. The intent needs its own task, because this
activity is `singleInstance` and the file manager would otherwise be handed
the intent in the background and never come forward. The path is compared
through `getCanonicalPath`, since `/sdcard`, `/storage/self/primary` and
`/storage/emulated/0` are all the same folder and only the last is what
`getExternalStorageDirectory` answers with.

### What the app offers to open

Spectrum media has no MIME types of its own, so `.tap`, `.tzx`, `.z80`,
`.szx`, `.sna`, `.trd`, `.dsk`, `.scl` and the rest all arrive as
`application/octet-stream` and Fuse identifies the content itself. The
manifest matches that, and a `content` or `file` scheme.

It used to match `*/*`, which made the app a candidate for opening every
file on the device: its own recordings offered themselves back to it instead
of to a video player, and it competed with the file manager for
`vnd.android.document/directory` when asked to show a folder. Now a GIF or
an MP4 offers only a video player, a folder only the file manager, and a
`.trd` offers Fuse.

### Disks

Every disk interface Fuse emulates — +3, Beta 128, +D, DISCiPLE, Opus,
Didaktik — registers its drives with `ui_media_drive_register()`, so the menu
is built by walking `ui_media_drive_find()` over the controllers rather than
naming any of them. Each drive is asked `is_available()` first, which is how
the list follows the machine: a +3 has no Beta drives and a Pentagon no +3
ones. The names shown are Fuse's own.

Loading, creating and ejecting all go through
`ui_media_drive_insert()` / `ui_media_drive_eject()`, with `disk.dirty`
cleared first: those would otherwise ask about losing changes through a
widget dialog only Enter or Escape dismisses, and Android has already asked.

A disk made with *New disk* is **unformatted** — Fuse's `disk_new()` gives it
geometry but no filesystem — so there is nothing to write until the machine
formats it. Saving one produced a silent zero byte file, so a failed or empty
write now deletes the file and says why.

Once the machine has formatted it, it writes: a blank disk in a Scorpion's
Beta A:, `FORMAT "test"` at the TR-DOS prompt, `SAVE "hi"`, then *Save…*
gives a 655360 byte TRD whose catalogue reads `test`, one file `hi`, 2543
free — and reopening it in the app shows the same.

The write itself is `disk_write()`, which picks its format from the extension
once `disk.type` is cleared — the same thing Fuse's own save-as does, minus
`ui_get_save_filename()` and the modal file selector behind it. Not every
format Fuse reads can be written: an `.scl` in particular has to come back as
a `.trd`, so the interface decides the default extension.

Disks are always written as a new file in the data folder, never back over
the one that was opened, because what was opened is a copy staged in the
cache. Expect the bytes to differ from the original even when nothing has
changed: Fuse writes them out of its own in-memory track representation
rather than copying the file.

### Save states

States live under `files/states`, named rather than numbered, so there can be
any number of them and each says what it is. The format is chosen in settings
and decided by the file's extension, which is all `snapshot_write()` looks at;
a state is whichever of `.szx`, `.z80` and `.sna` carries its name, so states
written before the setting changed still load. Saving removes the others for
that name, so a name is never ambiguous.

New states are named after the media that is loaded — the base name of the
opened file, or of the state last loaded — with a number appended if that is
taken, and the name is editable before saving. A reset or a machine change
empties the machine, so there is nothing left to name a state after and they
go back to being numbered `Snapshot 1`, `Snapshot 2` and so on.

SZX is the default because it is libspectrum's own format and the only one
that can represent every machine here — a state saved on a Pentagon or a
Timex restores as itself. The other two are for exchanging states with other
emulators, and Fuse warns on every save to them that information has been
lost; that warning is now a toast (see below).

Each slot also gets a `.thumb`: the last frame at half size, written by the
display backend as a width, a height and RGBA rows, which Android decodes
straight into a `Bitmap`. It costs 76kB a slot and saves guessing which save
is which.

Both directions are queued like any other command and run between frames on
the emulation thread, which is what makes the state coherent —
`snapshot_write()` and `snapshot_read()` are the same calls Fuse's own menus
make.

Loading a state is not the only way in: a `.szx`, `.z80` or `.sna` opened
through **Open file…** takes the same path through Fuse.

### Settings

`SettingsActivity` is a plain framework `PreferenceFragment` over the same
`fuse` preferences file the emulator reads, so there is one store rather than
two. The machine list is not a fixed array: it is read back from Fuse through
`FuseNative.machineNames()`, the same snapshot the ☰ switcher uses.

Each setting is applied twice. At startup it goes on Fuse's command line —
Fuse generates `--x` / `--no-x` for every boolean setting and `--x n` for
every numeric one — so the options are in force before Fuse has finished
starting, which matters because a file arriving by intent can be loading
before the command queue is first drained. Changed later, it goes through the
queue like everything else.

Fast loading is one switch over three Fuse settings: `tape_traps` catches the
ROM's loading routine, `fastload` makes a trapped block appear at once, and
`accelerate_loader` speeds up the timing loops of custom loaders that never
call the ROM at all.

Sound settings are only read when Fuse's sound subsystem starts, so changing
one calls `fuse_emulation_pause()` / `fuse_emulation_unpause()` to restart
it — which is what Fuse's own options dialogs do.

### The on-screen keyboard

`SpectrumKeyboardView` draws Fuse's own `keyboard.png`. The key rectangles were
measured off the image and are held in its 541x201 coordinate space, expanded
to meet their neighbours so the gutters in the artwork are not dead to a
fingertip. Presses are tracked per pointer, which is what makes both two-finger
chords and the shift latch work.

The picture is one bitmap, so the view would otherwise be a single unnamed
`View` with nothing inside it to address. Each key is published as a virtual
accessibility node instead, named the way the Spectrum names it — `ENTER`,
`CAPS SHIFT`, `7` — which is both what makes a screen reader usable and what
lets the tests press keys without knowing a coordinate.

Sending an accessibility event when nothing is listening throws, and nothing
listening is the normal case, so the latch only announces itself when
`AccessibilityManager.isEnabled()`. That was learned the hard way: the check
was missing at first and every long press on a shift killed the app. The
instrumentation suite ran clean throughout, because UI Automator switches
accessibility *on*.

### ROMs, and where things live

No ROMs are shipped. Fuse's tarball carries the Sinclair and Timex ones and
the staged `make install` would happily bundle them, so the build deletes
them from the assets again; what does travel with the app is Fuse's own UI
data, the widget font and the status bitmaps, which are not ROMs.

A complete set under the names Fuse looks for — `48.rom`, `128-0.rom`,
`plus3-0.rom` and the rest, including the Pentagon and Scorpion ones Fuse
cannot distribute — is at
<https://archive.org/details/zx-roms-fuse-roms>. Fuse's per-machine ROM
filenames are listed in its `settings.dat` if you need to check what a
particular machine wants.

Fuse looks for a ROM in the current working directory before anywhere else,
so the app simply `chdir`s into the user's `roms` folder before starting the
emulation thread. That is the whole mechanism: no per-ROM paths on the
command line, and it follows the folder setting immediately, because `chdir`
is process wide.

Without ROMs Fuse cannot even reach the 48K machine it falls back to, and
gives up hard — `fuse_abort()` — so the app checks the folder first and does
not start the emulator at all until there is something in it, offering an
importer instead.

Save states and ROMs share a root the user picks from what the device offers.
It has to be a real filesystem path the app can write without a permission,
because Fuse reaches both with plain stdio; that means internal storage or an
app-specific external directory, not an arbitrary tree from the document
picker. The folder to read *content* from has no such restriction, since that
goes through the picker: any granted tree works as its starting point.

### Data files and environment

Fuse resolves ROMs and its widget font against the compile-time `FUSEDATADIR`,
so the build passes `--datadir=/data/data/com.fusemobile/files` and a staged
`make install` provides the assets that `FuseActivity` unpacks there on first
run. **`applicationId` in `app/build.gradle` and `DATA_ROOT` in
`scripts/build-native.sh` must stay in sync.** The activity also points
`$HOME`, `$XDG_CONFIG_HOME` and `$TMPDIR` at app-private storage before the
emulation thread starts.

### The one real portability bug

`compat.h` defines `PATH_MAX` as 1024 if nothing has defined it yet, and
`struct path_context` embeds a `char path[PATH_MAX]`. On bionic some
translation units pull in `<limits.h>` (PATH_MAX 4096) before `compat.h` and
some do not, so `compat_get_next_path()` `strncpy()`s 4096 bytes into a
1024-byte field — smashing the caller's stack and nulling `ret_path`, which
crashes inside `snprintf` while looking for `fuse.font`.

Fixed without touching Fuse by forcing a consistent value:
`CPPFLAGS="-include limits.h"`. Worth reporting upstream.

## Tests

`app/src/androidTest` — UI Automator, run on a connected device:

```sh
./gradlew connectedDebugAndroidTest
```

`NewDiskTest` is the whole disk story end to end: a blank disk conjured into
a Scorpion's Beta A:, `FORMAT "test"` and `SAVE "hi"` typed on the machine's
own keyboard, *Save…*, and then the resulting file read back and checked as
a TR-DOS image — 655360 bytes, the signature byte, one file called `hi`
saved as a BASIC program, on a disk labelled `test`. That path is the one
that broke: an unformatted disk used to save as a silent zero byte file.

Two things make it possible to write that without a single coordinate.

The keyboard is one bitmap, so it would otherwise be a lone unnamed view.
Each key is published as an accessibility node instead, named the way the
Spectrum names it, which is what `emulator.key("ENTER")` finds — and it
gives the app a keyboard a screen reader can read out, which it did not have
before. `extendedMode()` is a long press to latch Caps Shift and a tap on
Symbol Shift, because FORMAT is not a word you can spell: it is a token on
the 0 key.

The emulated screen is the exception. It is a GL surface with no structure
to query, so there is nothing to assert against and nothing to wait on;
`Emulator.idle()` waits by the clock, and the assertions are made against
the files the machine produces. That is the honest boundary of this
approach, and why the interesting assertion is on bytes rather than pixels.

`CaptureTest` is the counterpart for screenshots and recording: a PNG the
size the machine is drawing, a GIF whose blocks parse and hold more than one
frame, an MP4 the device's own metadata reader agrees is video. Counting
0x2c bytes would have "found" GIF frames in the compressed data and could
never have failed, so the blocks are walked properly instead.

Gradle uninstalls the app when a run finishes, so the next one starts with
no preferences and no storage permission. The suite sets both itself: it
grants All files access through the shell and points the app at a folder
that has ROMs in it, `/sdcard/Download/Spectrum` unless told otherwise.

```sh
./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.dataFolder=/sdcard/Spectrum
```

The ROMs decide what can run. `NewDiskTest` needs a Scorpion, whose ROMs are
not redistributable, so it skips rather than fails when they are absent.

## Next steps

The list under [Not yet](#not-yet) is roughly in the order that would add the
most. A peripherals screen is the single change that unlocks the widest range
of hardware, since Fuse already emulates all of it.

The suite in `app/src/androidTest` covers two paths so far. More there is
worth more than any one feature above.
