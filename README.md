# FuseMobile

Android port of [Fuse](http://fuse-emulator.sourceforge.net/) (the Free Unix
Spectrum Emulator). This first milestone boots a **ZX Spectrum 128K** and
renders it full-screen on Android 11+.

Fuse and libspectrum are used **completely unmodified** — everything below is
build configuration and a thin Java activity.

## Status

Verified on an Android 16 x86_64 emulator (API 36) and cross-built for
arm64-v8a. The 128K boot menu renders full-screen, the Z80 core runs at
~50 fps, and SDL audio is streaming.

| Working | Not yet |
| --- | --- |
| 128K emulation, full-screen scaled display | On-screen (touch) keyboard |
| SDL audio output | Loading tapes and snapshots |
| Hardware keyboard input | On-screen menus |
| ROMs + widget font unpacked to app storage | armeabi-v7a (add to `ABIS`/`abiFilters` if needed) |
| arm64-v8a + x86_64 | |

Hardware keys work as they do on the desktop — e.g. Shift+6 / Shift+7 are Caps
Shift + 6/7, the Spectrum's cursor down/up, and move the 128 menu selection.
Note that `adb shell input keyevent` does **not** reach the emulator, so test
key handling with a real keyboard rather than synthetic injection.

## Layout

```
vendor/          upstream sources, fetched by the build script, never modified
  fuse-1.9.0/          fuse-emulator 1.9.0
  libspectrum-1.6.2/   libspectrum 1.6.2
  SDL2-2.32.10/        SDL 2.32.10
scripts/build-native.sh  cross-compiles the three above per ABI
build-native/    out-of-tree build trees + per-ABI install prefixes
app/             Gradle app; jniLibs/ and assets/fuse/ are build outputs
```

## Building

Requires the Android SDK with NDK r27, plus `autoconf`-era build tools on the
host (`make`, `perl`, `pkg-config`, `cmake`, `ninja`, and a host `gcc` for
libspectrum's build-time codegen).

The three upstream tarballs are not in git. The build script downloads them
into `vendor/` on first run, verifies their SHA-256, and never writes to them
again. It also refreshes SDL's Java glue in `app/src/main/java/org/libsdl/app`
so it always matches the `libSDL2.so` being linked.

```sh
./scripts/build-native.sh              # all ABIs; ~5 min cold
ABIS=x86_64 ./scripts/build-native.sh  # single ABI
./scripts/build-native.sh clean

./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If your default JDK is newer than 21, point Gradle at Android Studio's JBR:
`JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug`.

The native build is deliberately **not** wired into Gradle: it is slow, rarely
changes, and Gradle just packages the prebuilt `.so` files from
`app/src/main/jniLibs/`.

## How it fits together

**SDL 2 backend.** Fuse ships a `ui/sdl2` UI, and SDL 2 already has a mature
Android backend that renders through OpenGL ES and handles the activity
lifecycle. That gives us a hardware-accelerated display without writing (and
maintaining) a new Fuse UI backend.

**Entry point.** `fuse.c` includes `<SDL.h>` when `UI_SDL2` is defined, so
SDL's header rewrites its `main()` into `SDL_main()` — exactly what SDL's
Android bootstrap `dlsym()`s. Fuse therefore needs no entry-point shim; it just
has to be a shared library rather than an executable:

```sh
make LDFLAGS="-XCClinker -shared -XCClinker -Wl,-soname,libmain.so ..."
```

`-XCClinker` is required because Fuse links through libtool, which discards a
bare `-shared` on a program target. The resulting `fuse` file is copied to
`libmain.so`.

**Data files.** Fuse resolves ROMs and its widget font against the compile-time
`FUSEDATADIR`, so the build passes
`--datadir=/data/data/com.fusemobile/files`, and a staged `make install`
provides the assets that `FuseActivity` unpacks there on first run.
**`applicationId` in `app/build.gradle` and `DATA_ROOT` in
`scripts/build-native.sh` must stay in sync.**

**Environment.** `FuseActivity` sets `$HOME`, `$XDG_CONFIG_HOME` and `$TMPDIR`
to app-private storage before `SDL_main` starts, and passes
`--machine 128 --full-screen` through SDL's `getArguments()` hook. Without
`--full-screen`, SDL honours Fuse's 320x240 window request and the emulated
screen lands unscaled in a corner of the surface.

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

- On-screen touch keyboard. The native key path already works (see Status), so
  this is about surfacing Spectrum keys on a touchscreen — either an SDL-drawn
  overlay or an Android view above the SDL surface feeding `SDL_SendKeyboardKey`
  equivalents.
- File picker for `.tap`/`.tzx`/`.z80`, wired to Fuse's snapshot loading.
- Reaching Fuse's menus (the widget UI is built and its font is installed).
