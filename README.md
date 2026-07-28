# FuseMobile

Android port of [Fuse](http://fuse-emulator.sourceforge.net/) (the Free Unix
Spectrum Emulator), with a native Android UI backend, running any of the
sixteen Spectrum-family machines Fuse supports on Android 11+.

Fuse and libspectrum are used **completely unmodified** — everything here is
build configuration, a Fuse UI backend of our own, and a thin Android app.

**No ROMs ship with the app or live in this repository.** On first run it
creates an empty `roms` folder and asks you to fill it, either by copying
files in or through the importer in the dialog.

## Status

Verified on an Android 16 x86_64 emulator (API 36) and cross-built for
arm64-v8a.

| Working | Not yet |
| --- | --- |
| All sixteen machines, 16K through Scorpion | CRT / scanline filters |
| Save states and ROMs in a folder you choose | Renaming an existing state |
| Loading every format Fuse supports | Save states |
| Opening files from other apps | On-screen joystick |
| Save states, named and unlimited | Writing tapes and disks back out |
| Settings screen over Fuse's own options | Native menus replacing the widget ones |
| Fast tape loading, or real-time loading with sound | On-screen joystick |
| Machine switcher, remembered across launches | Debugger front end |
| Reset and NMI from the menu | armeabi-v7a (add to `ABIS`/`abiFilters`) |
| GPU-scaled display, portrait and landscape | |
| AAudio output, pacing emulation at 50.3 fps | |
| On-screen keyboard from Fuse's own artwork | |
| Latching Caps Shift and Symbol Shift | |
| Hardware keyboard input | |
| Fuse's widget dialogs, menus and debugger UI | |
| Background / resume without losing the surface | |

Known issues:

- The launcher icon is still SDL's, inherited from its sample project before
  SDL was removed. It needs replacing.
- In portrait the emulated screen is centred in the space above the keyboard,
  which leaves wide empty bands. It should probably sit at the top.

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

**Folders** are yours to choose, in settings. *Save states folder* picks
which of the writable roots this device offers — internal storage, shared
storage, an SD card — holds the `states` and `roms` folders, and moves what
is already there when it changes. *Content folder* is where **Open file…**
starts, granted through the document picker.

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
- **Settings…** — see below.
- **Machine…** — all sixteen machines, with the running one checked. The
  choice is remembered for the next launch.
- **Reset** — asks first, since it discards machine state.
- **NMI** — the magic button of the real hardware. What it does depends on the
  machine; see below.

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
scripts/build-native.sh  cross-compiles everything per ABI
build-native/    out-of-tree build trees + per-ABI install prefixes
app/             Gradle app; jniLibs/ and assets/fuse/ are build outputs
```

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

### ROMs, and where things live

No ROMs are shipped. Fuse's tarball carries the Sinclair and Timex ones and
the staged `make install` would happily bundle them, so the build deletes
them from the assets again; what does travel with the app is Fuse's own UI
data, the widget font and the status bitmaps, which are not ROMs.

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

## Next steps

1. **Shaders** — CRT, scanlines, sharp-bilinear scaling, in `android_gl.c`.
2. **Files and state** — SAF file picker for `.tap`/`.tzx`/`.z80`, save/load
   states via libspectrum, driven through the command queue.
3. **Native menus** replacing the widget dialogs, and an on-screen joystick.
   An app icon that is not SDL's would be good too.
4. **Debugger** front end over Fuse's `debugger/` API.


