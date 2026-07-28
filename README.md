# FuseMobile

Android port of [Fuse](http://fuse-emulator.sourceforge.net/) (the Free Unix
Spectrum Emulator), with a native Android UI backend, running any of the
sixteen Spectrum-family machines Fuse supports on Android 11+.

Fuse and libspectrum are used **completely unmodified** — everything here is
build configuration, a Fuse UI backend of our own, and a thin Android app.

## Status

Verified on an Android 16 x86_64 emulator (API 36) and cross-built for
arm64-v8a.

| Working | Not yet |
| --- | --- |
| Emulation of all 16 machines Fuse supports | CRT / scanline filters |
| Machine switcher, remembered across launches | Loading tapes and snapshots |
| GPU-scaled display, portrait and landscape | Save states |
| AAudio output, pacing emulation at 50.3 fps | On-screen joystick |
| On-screen Spectrum keyboard (multi-touch) | Debugger front end |
| Hardware keyboard input | armeabi-v7a (add to `ABIS`/`abiFilters`) |
| Fuse's widget dialogs, menus and debugger UI | |
| Background / resume without losing the surface | |

All sixteen machines boot. Fuse itself only ships the ROMs it is allowed to
redistribute, so the rest live in `roms/` — see below. If a ROM is ever
missing Fuse falls back to 48K, which the app reports rather than silently
accepting: the machine it remembers is whatever actually ended up running.

**Known wart:** the launcher icon is still SDL's, inherited from its sample
project before SDL was removed. It needs replacing.

Hardware keys work as they do on the desktop — e.g. Shift+6 / Shift+7 are Caps
Shift + 6/7, the Spectrum's cursor down/up. Note that `adb shell input
keyevent` does **not** reach the emulator, so test key handling with a real
keyboard or by tapping the on-screen one.

## Layout

```
vendor/          upstream sources, fetched by the build script, never modified
  fuse-1.9.0/          fuse-emulator 1.9.0
  libspectrum-1.6.2/   libspectrum 1.6.2
native/          our Fuse backend, compiled into Fuse's own build tree
  ui/android/          display, GLES renderer, JNI bridge, keysym map
  sound/               AAudio driver
roms/            machine ROMs Fuse does not ship (see below)
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

The machine switcher is the first feature built on the queue rather than on
key events: the Android dialog queues an index, the emulation thread calls
`machine_select()`, and the machine list itself is snapshotted from Fuse's
`machine_types` on the emulation thread for the UI thread to read back.

### ROMs

Fuse's tarball carries the Sinclair and Timex ROMs, and a staged
`make install` puts them in the APK's assets. It does not carry the clone
ROMs — Pentagon (`128p-*`, `trdos`, `gluck`) and Scorpion (`256s-*`) — nor
the Interface 1 and Opus Discovery ROMs, because they are copyrighted and not
Fuse's to redistribute. Those sit in `roms/` and the build copies them
alongside Fuse's own, which is all `machine_select()` needs to find them by
name. Bear that in mind before publishing this repository.

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
