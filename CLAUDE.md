# Zedex — working notes

A ZX Spectrum emulator for Android, built on an unmodified Fuse core.
`README.md` explains the design; this file is the operational knowledge that
is easy to get wrong and expensive to rediscover.

## Hard rules

- **Never modify `vendor/`.** Fuse and libspectrum are used exactly as
  released. The Android backend is swapped in at link time — configure with
  `--with-fb`, then never compile `ui/fb` and link `native/ui/android` in its
  place. One symbol is overridden with `llvm-objcopy --weaken-symbol`. If
  something seems to need a patch, it almost certainly does not.
- **`applicationId` in `app/build.gradle` and `PKG` in
  `scripts/build-native.sh` must match.** Fuse bakes `FUSEDATADIR` in at
  configure time; a mismatch compiles, links and installs happily, then
  fails at runtime looking for `fuse.font` in the wrong directory. The build
  tree stamps `.package` and reconfigures itself when it changes.
- **Fuse's core is single threaded.** Everything from the UI thread goes
  through the command queue in `native/ui/android/android_bridge.c` and is
  drained on the emulation thread from `ui_event()`. Never call into Fuse
  from Java directly.

## Building

Native first, then Gradle. Gradle only packages the prebuilt `.so`, so
**changing C code and running `adb install` without `./gradlew assembleDebug`
in between installs the old library** — this has cost real debugging time
more than once.

```sh
./scripts/build-native.sh                 # both ABIs, ~90s cold
ABIS=x86_64 ./scripts/build-native.sh     # one ABI while iterating
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`JAVA_HOME` is needed whenever the default JDK is newer than 21.

## Tests

`app/src/androidTest` — UI Automator. Run one class while iterating; the
whole suite only for refactors or large features.

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=dev.ldlab.zedex.CaptureTest
```

Things learned the hard way, all of them recorded in the tests' own comments:

- Instrumentation runs **inside the app's process**, so `am force-stop` on the
  app kills the test with it.
- **A run uninstalls the app afterwards**, wiping its preferences and storage
  permission. The next launch then finds no ROMs and shows a black screen —
  which looks exactly like a broken feature. See *Device setup* below.
- `UiObject2.longClick()` holds for the platform long-press timeout, which is
  the same 400ms the keyboard latches at. Hold with a zero-length swipe.
- **UI Automator switches accessibility on**, so anything guarded by
  `AccessibilityManager.isEnabled()` behaves differently under test than in
  real use. A crash on latching a shift once shipped for exactly this reason.

## Device setup, after a test run

```sh
adb shell appops set dev.ldlab.zedex MANAGE_EXTERNAL_STORAGE allow
adb shell "run-as dev.ldlab.zedex mkdir -p shared_prefs"
# shared_prefs/fuse.xml:
#   <string name="statesRoot">/storage/emulated/0/Download/Spectrum</string>
```

Use the canonical `/storage/emulated/0/...`; `/sdcard` is a symlink and string
comparisons against `getExternalStorageDirectory()` will not match it.

## Driving the app from a terminal

`adb shell input keyevent` does **not** reach the emulator. Two helpers:

```sh
scripts/ui-tap.py list                    # what is on screen
scripts/ui-tap.py "Disks" "Beta Disk A" "Save…"
scripts/ui-type.py 'randomize usr 15616' ENTER
scripts/ui-type.py CS+SS SS+0 ' ' '"test"' ENTER   # extended mode: FORMAT
```

Both address things by name, never by coordinate — menus grow and the
keyboard is one bitmap whose keys are accessibility nodes.

## Conventions

- Commit subjects take a conventional prefix: `feat:`, `fix:`, `docs:`,
  `chore:`, `test:`, `refactor:`. Body explains *why*.
- Verify features by running them on a device and say what was actually
  checked. Where something is unverified, the README says so.
