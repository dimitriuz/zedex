# Zedex — working notes

A ZX Spectrum emulator for Android, built on an unmodified Fuse core.
`README.md` is for people using the app and nothing else — keep build, test
and release material out of it. `docs/DEVELOPING.md` covers building, driving
it from adb, the tests and releases; `docs/INTERNALS.md` how the core is wired
in. This file is the operational knowledge that is easy to get wrong and
expensive to rediscover.

## Hard rules

- **Never modify `vendor/`.** Fuse and libspectrum are used exactly as
  released. The Android backend is swapped in at link time — configure with
  `--with-fb`, then never compile `ui/fb` and link `native/ui/android` in its
  place. One symbol is overridden with `llvm-objcopy --weaken-symbol`. If
  something seems to need a patch, it almost certainly does not.
- **The debug build is a package of its own**, `dev.ldlab.zedex.debug`, so it
  installs beside the release one instead of fighting it over a certificate.
  Anything addressed to the app by name — `appops`, `run-as`, `am start` —
  needs the right one of the two.
  Fuse finds its data files *relative to argv[0]*, which the activity sets to
  a path inside its own files, so the package name no longer has to match the
  `PKG` baked into `FUSEDATADIR` at configure time. That used to be a hard
  rule and cost a debugging afternoon; it is now only a fallback nothing
  reaches.
- **Only swallow a key Fuse can use.** `onKeyDown` returning true consumes the
  event, and consuming the volume keys so that Fuse can ignore them is how the
  phone's volume buttons stopped working. `FuseNative.mapsKey()` asks Fuse's
  own keysym table; anything it does not know goes to `super`.
- **The version lives in `version.properties`, and nowhere else.** The build
  reads it and derives `versionCode` as `major * 10000 + minor * 100 + patch`;
  the release workflow *checks* the tag against it rather than computing a
  version of its own, so `git tag v1.2.3` on a tree that says `version=1.2.2`
  fails the release before it builds. Bump the file in the commit you tag.
- **`app/debug.keystore` is committed on purpose.** Gradle's own debug key is
  per machine, so a CI build could not update a local one — or another CI
  build. See *Building* in `docs/DEVELOPING.md`.
- **`ui_statusbar_update` is ours too.** `ui/widget/widget.c`'s version is a
  stub that discards the news, so the build weakens it the same way it weakens
  `ui_error_specific`, and `native/ui/android/android_status.c` keeps it for the
  activity lamps. Two weakened symbols now; both are listed in the build script.
- **Fuse's core is single threaded.** Everything from the UI thread goes
  through the command queue in `native/ui/android/android_bridge.c` and is
  drained on the emulation thread from `ui_event()`. Never call into Fuse
  from Java directly. The surface handover lives in `android_window.c` and the
  snapshots the UI thread reads back in `android_state.c`.
- **Nothing on screen may change its `contentDescription` continuously.** That
  is a window-content-changed event each time, the accessibility tree never
  settles, and every UI Automator test fails with *the ☰ button never
  appeared*. The activity lamps did this and took the whole suite down.

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

- **Sample the picture, not a view's bounds.** `borderColour()` computes the 4:3
  quad the way the renderer does. The window's corner was wrong once the bar took
  a strip; the SurfaceView's bounds are wrong sideways, where the picture is
  centred in a wider box. Both failed as "expected 4 but was 0".
- **Ask for portrait, not for natural.** On a tablet natural *is* landscape, so
  `setOrientationNatural()` runs a test that wants a tall window in a wide one.
- Instrumentation runs **inside the app's process**, so `am force-stop` on the
  app kills the test with it.
- **A run uninstalls the app afterwards**, wiping its preferences and storage
  permission — so the next launch unpacks the shipped ROMs into its own private
  folder and forgets the data folder the device was set up with. See *Device
  setup* below.
- `UiObject2.longClick()` holds for the platform long-press timeout, which is
  the same 400ms the keyboard latches at. Hold with a zero-length swipe.
- **☰ fades out after three seconds**, so it is usually gone by the time a
  test wants it, and it can vanish between being found and being clicked.
  `Emulator.menu()` taps the picture to reveal it every time, first.
- **A BASIC program belongs on a tape, not on the keyboard.** `TapeProgram`
  writes a real `.tap` with an autostart line; typing one costs a tap and
  150ms a character. The exception is a machine that is not sitting at a
  BASIC prompt, since autoloading means Fuse typing `LOAD ""` itself.
- The emulated screen cannot be read, but a **screenshot of the device** can:
  have the program under test say what it saw in the border colour, and
  `Emulator.borderColour()` will read it back.
- **Do not drive the picker; make a document instead.** `MediaStore` gives a
  real `content://` URI, and an `ACTION_VIEW` intent carrying it takes the same
  path a file manager's hand-over does. That is the only way to reach
  `Media.stage()` — the md5, the recent list, the grant and the write-back origin
  are all there. `Emulator.open()` calls Fuse directly and misses them.
- **UI Automator switches accessibility on**, so anything guarded by
  `AccessibilityManager.isEnabled()` behaves differently under test than in
  real use. A crash on latching a shift once shipped for exactly this reason.
- **At a BASIC prompt the first key of a line is a keyword.** `B` gives `BORDER`;
  typing the six letters gives `BORDER ORDER` and a syntax error.
- **Poke above RAMTOP.** The printer buffer at 23296 is only free on a 48K; on a
  128 the byte is gone before the next `PEEK` sees it. Have the program `CLEAR`
  down to 32767 and use 32768.
- **`By.desc` is an exact match.** Card buttons are described `Rename “Tujad”`,
  quotes and all, so `find()` uses `descContains`.
- **A row that was a dialog is a page now.** The button commits by its own name —
  *Save as…*, *Delete* — and there is no OK. `NewDiskTest` tapped OK for months
  after the change and nobody ran it.

## Device setup, after a test run

```sh
# ...and dev.ldlab.zedex.debug for a build straight off the bench.
adb shell appops set dev.ldlab.zedex MANAGE_EXTERNAL_STORAGE allow
adb shell "run-as dev.ldlab.zedex mkdir -p shared_prefs"
# shared_prefs/fuse.xml:
#   <string name="statesRoot">/storage/emulated/0/Download/Spectrum</string>
```

Use the canonical `/storage/emulated/0/...`; `/sdcard` is a symlink and string
comparisons against `getExternalStorageDirectory()` will not match it.

## Driving the app from a terminal

A plain `adb shell input keyevent KEYCODE_A` does reach the emulator and types
an `a` — but nothing shifted, no keyword, and `input text` does nothing at all.
Two helpers:

```sh
scripts/ui-tap.py list                    # what is on screen
scripts/ui-tap.py "Media" "Beta Disk A" "Save…"
scripts/ui-type.py 'randomize usr 15616' ENTER
scripts/ui-type.py CS+SS SS+0 ' ' '"test"' ENTER   # extended mode: FORMAT
```

`ui-type.py` taps by coordinate and so carries a copy of each skin's key
positions; it reads the stored skin to pick between them. Neither script can type
on the **Android keyboard** skin - that is the phone's own input method, and on an
AVD Gboard hides its keys because the emulator reports a hardware keyboard.
`adb shell settings put secure show_ime_with_hard_keyboard 1` brings them back. **After switching skins
in a running app, `ui-tap.py` still reports the old skin's key names** — UI
Automator caches the window's tree and nothing the app sends clears it. Relaunch
before driving the keyboard.

Both address things by name, never by coordinate — menus grow, the keyboard is
one bitmap whose keys are accessibility nodes, and the quick bar is icons whose
only name is their content description (`ui-tap.py` matches that too). ☰ has
**pages**, so a path is several names deep: `"Machine" "Change machine"
"Scorpion"`. The quick bar fades after three seconds, so tap the picture first
to bring it back.

## Where things live

`FuseNative` and `EmulatorActivity` stay in `dev.ldlab.zedex` and cannot move:
`android_bridge.c` exports 55 `Java_dev_ldlab_zedex_FuseNative_*` symbols and
does a `FindClass` on that path, and the activity is addressed as
`dev.ldlab.zedex/.EmulatorActivity` by `am start` in the scripts and docs.
Everything else is in a layer — `machine`, `input`, `storage`, `cheats`, `media`,
`view`, `menu`, `screen`; see *How the code is laid out* in
`docs/INTERNALS.md`. Adding an activity means the manifest gets `.screen.Name`.

A member another layer needs has to be `public`; package-private stops at the
boundary.

## Refactoring this codebase

- **Build collaborators in `onCreate`, never as field initialisers.** Those run
  first and are handed a null `preferences`. It compiles.
- **A `Host` interface wider than about four methods means the seam is wrong.**
  "Move the menus out" needed fifteen, so the menus stayed. Pass a real
  collaborator instead: `ControlsUi` holds `EmulatorLayout`.
- **Extract first, move into packages after.** Every cross-package reference has
  to become `public`; pay for it once.
- **Never script the `public` widening by indentation.** Eight spaces is a method
  body at a top-level class and a member inside a nested type. Let the compiler
  name what is invisible and promote exactly that. Guessing cost an hour and a
  `git reset --hard`.
- **When a class leaves, read what it left behind.** Comments do not move with
  the code. Eleven were lying after this refactor; the worst was a fifteen-line
  explanation stranded above an unrelated constant.

## Conventions

- Commit subjects take a conventional prefix: `feat:`, `fix:`, `docs:`,
  `chore:`, `test:`, `refactor:`. Body explains *why*.
- Verify features by running them on a device and say what was actually
  checked. Where something is unverified, the README says so.
