# Zedex

**Zedex** — a ZX Spectrum emulator for Android 11+, with a native front
end: OpenGL ES rendering, AAudio output, a touch keyboard carrying every
legend the real machine had, and menus that belong on a phone rather than in
the emulated screen.

> Zedex uses [Fuse](http://fuse-emulator.sourceforge.net/) as its emulation
> backend, unmodified and unpatched. How it is wired in — and how it could be
> replaced — is in [docs/INTERNALS.md](docs/INTERNALS.md).

> **No ROMs ship with the app or live in this repository.** On first run it
> creates an empty `roms` folder and offers to import them. The set it
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
- **On-screen keyboard** drawn from the real thing, so every key carries its
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

- CRT, scanline and sharp-bilinear shaders. The fragment shader in
  `native/ui/android/android_gl.c` is the only code that touches a pixel, so
  this is where they go
- In portrait the screen is centred above the keyboard, leaving wide empty
  bands; it should sit at the top

**Playing**

- An on-screen joystick. Cursor, Kempston, Sinclair 1 and 2, Timex 1 and 2
  and Fuller are all emulated; nothing yet maps to them
- Rewind, and playing back RZX recordings as recordings

**Capture**

- Sound in recordings. Both formats are video only
- A resolution change mid-recording — a Timex hi-res mode — is skipped
  rather than handled

**Odds and ends**

- Renaming a save state, and writing a disk back over the file it came from
- Recording a tape in real time, rather than through the save trap
- Native dialogs in place of the last of the core's own ones
- A debugger front end over the core's debugging API
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

**The ☰ button** opens:

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

## Layout

```
vendor/          the emulation core, fetched by the build script, never modified
native/          our UI backend, compiled into the core's own build tree
  ui/android/          display, GLES renderer, JNI bridge, keysym map
  sound/               AAudio driver
scripts/
  build-native.sh      cross-compiles everything per ABI
  ui-tap.py            taps the app by text, for driving it from a terminal
  ui-type.py           types on the machine's keyboard the same way
build-native/    out-of-tree build trees + per-ABI install prefixes
app/
  src/main/            the app; jniLibs/ and assets/ are build outputs
  src/androidTest/     UI Automator tests
```

The app is a handful of classes: `EmulatorActivity` holds the menus,
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
`app/src/main/jniLibs/`. Changing C code therefore means running the build
script *and* `assembleDebug` — installing after only the first one ships the
previous library.

How the core is built and wired in, and why none of it needs a patch, is in
[docs/INTERNALS.md](docs/INTERNALS.md).

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
adb shell "run-as dev.ldlab.zedex sh -c 'cat > /data/data/dev.ldlab.zedex/files/game.tap'" < game.tap
adb shell am start -a android.intent.action.VIEW \
    -d file:///data/data/dev.ldlab.zedex/files/game.tap \
    -n dev.ldlab.zedex/.EmulatorActivity
```

### Releases

CI does the same two steps in the same order. `.github/workflows/build.yml`
builds a debug APK on every push and pull request;
`.github/workflows/release.yml` builds, signs and publishes on a tag. Both
share `.github/actions/native`, which pins **NDK 27.0.12077973** — the script
picks the highest NDK installed, so without a pin a runner image update would
quietly change compilers — and caches the tarballs and the native build trees.
The cache is keyed on `build-native.sh`; restoring it is safe because the
script recompiles `native/` and relinks every time, so a change there always
lands.

Tagging `v1.2.3` produces `versionName 1.2.3` and `versionCode 10203`
(`major*10000 + minor*100 + patch`, so no component may exceed 99). The APK is
published as a **draft** release with its SHA-256, to be installed and checked
before anyone else sees it. Drop `--draft` from the workflow to publish
straight from the tag.

Signing needs four repository secrets. The keystore never enters the repo, and
losing it means no future release can upgrade an installed app, so back it up
somewhere durable:

```sh
keytool -genkeypair -v -keystore zedex-release.jks -alias zedex \
        -keyalg RSA -keysize 4096 -validity 10000

base64 -w0 zedex-release.jks | gh secret set ZEDEX_KEYSTORE_BASE64
gh secret set ZEDEX_KEYSTORE_PASSWORD
gh secret set ZEDEX_KEY_ALIAS        # zedex
gh secret set ZEDEX_KEY_PASSWORD
```

The release job checks all four are present and opens the keystore before it
starts the nine-minute build, so a mis-pasted secret fails in seconds.

Locally the same variables — `ZEDEX_KEYSTORE` pointing at the file, plus
`ZEDEX_KEYSTORE_PASSWORD`, `ZEDEX_KEY_ALIAS`, `ZEDEX_KEY_PASSWORD` — make
`assembleRelease` produce a signed `app-release.apk`. With none of them set it
produces `app-release-unsigned.apk`, which is the normal case.

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
of hardware, since all of it is emulated already.

The suite in `app/src/androidTest` covers two paths so far. More there is
worth more than any one feature above.

## Licence

Zedex is free software under the **GNU General Public License, version 2 or
(at your option) any later version**. The full text is in [LICENSE](LICENSE).

It could not be anything else. Fuse and libspectrum are GPL-2.0-or-later, and
the Android backend is not a separate program that talks to them: it
implements Fuse's own `ui/ui.h` interface, includes its internal headers, and
is compiled into the same binary in place of `ui/fb`. The APK is one combined
work, so it carries the core's terms.

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
