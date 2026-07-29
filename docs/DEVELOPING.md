# Developing Zedex

Building it, driving it from a terminal, testing it and cutting a release.
How the core is wired in — and why none of it needs a patch — is in
[INTERNALS.md](INTERNALS.md); the [README](../README.md) is for people using
the app rather than working on it.

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
store/           Play assets: 512 icon and 1024x500 feature graphic, not packaged
.github/
  actions/native/      the cross-compile, shared by both workflows
  workflows/           debug APK on every push; signed APK on a tag
```

The icon is a set of PNGs rather than a `VectorDrawable`, because the mark is
lettering — `zdx` in Onest ExtraBold — and a vector drawable has no fonts, so
it would mean converting glyphs to paths. `mipmap-anydpi-v26` is what every
supported device actually uses, minSdk being 30; the legacy mipmaps are there
for tooling and the store listing. The Android 12 splash is set through the
framework attributes in `values-v31/styles.xml` rather than
`androidx.core:core-splashscreen`, since the library exists to back-port it
below API 31 and the app has no dependencies.

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

**Debug builds are signed with `app/debug.keystore`, which is in the
repository.** That is what lets one debug APK replace another: left to itself
Gradle invents a debug key per machine and keeps it in
`~/.android/debug.keystore`, so a build from CI — where that file does not
exist and is created fresh for the run — carries a key nothing else has, and
`adb install -r` answers `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Three builds
meant three certificates and three uninstalls, CI against CI included.

Nothing is given away by committing it. It signs debug builds and only debug
builds, it is not the release key, it cannot update anything installed from a
store, and its password is the published Android default. Anything installed
before this key existed has to be uninstalled once to cross over.

If your default JDK is newer than 21, point Gradle at Android Studio's JBR:
`JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug`.

The native build is deliberately **not** wired into Gradle: it is slow, rarely
changes, and Gradle just packages the prebuilt `.so` from
`app/src/main/jniLibs/`. Changing C code therefore means running the build
script *and* `assembleDebug` — installing after only the first one ships the
previous library.

## Driving it from adb

A plain `adb shell input keyevent KEYCODE_A` does reach the app and does type
an `a`, but that is as far as it goes: there is no way to spell a shifted
character or a BASIC keyword with it, and `adb shell input text` produces
nothing at all. Tapping the on-screen
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

## Releases

CI does the same two steps in the same order. `.github/workflows/build.yml`
builds a debug APK on every push and pull request;
`.github/workflows/release.yml` builds, signs and publishes on a tag. Both
share `.github/actions/native`, which pins **NDK 27.0.12077973** — the script
picks the highest NDK installed, so without a pin a runner image update would
quietly change compilers — and caches the tarballs and the native build trees.
The cache is keyed on `build-native.sh`; restoring it is safe because the
script recompiles `native/` and relinks every time, so a change there always
lands.

Both workflows then check the APK really contains both ABIs and `fuse.font`,
because packaging a stale or missing library is this project's recurring
failure and it stays silent until runtime.

The build artifact is named `Zedex-debug`, holding `Zedex-debug.apk`. GitHub
zips every artifact on download and a workflow cannot change that — but
`gh run download` unpacks it, giving the bare APK. Release assets are direct
uploads and are not zipped at all: a tag produces `Zedex-1.2.3.apk` and its
`.sha256` beside it.

**The version is set in `version.properties` at the root of the repository, and
nowhere else.** One line - `version=1.2.3` - which the build turns into
`versionName 1.2.3` and `versionCode 10203` (`major*10000 + minor*100 + patch`,
so no component may exceed 99, and 1.10.0 sorts above 1.9.9 as it should). It is
read straight by `app/build.gradle`, so a local build and a release build call
themselves the same thing, and ☰ shows what is installed by asking the package
manager rather than a compiled-in constant.

A tag only has to *agree*. The release workflow compares `v1.2.3` with the file
and stops if they differ, so tagging a tree whose version says something else
fails before the nine-minute build rather than publishing an APK that calls
itself the wrong number. Bump the file in the commit you tag. A
`-PzedexVersionName=` still overrides both, for a one-off build that has to say
something particular. Deriving the code from the version rather than the run
number means rebuilding a tag gives the same APK. It is published as a **draft** release with its SHA-256, to be
installed and checked before anyone else sees it; drop `--draft` from the
workflow to publish straight from the tag.

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

The store password unlocks the file; the key password unlocks the one entry
named by the alias. **For the keystore that command produces they are the same
value.** Despite the `.jks` name, keytool on JDK 9 and later writes a PKCS12
keystore, which has no separate per-entry password — give it a different
`-keypass` and it says so and ignores it, which is why it only ever asks for
one password. Set both secrets to that one.

Leaving `ZEDEX_KEY_PASSWORD` empty or guessed does not work: AGP checks it even
though keytool does not, and `:app:packageRelease` fails with `Get Key failed:
Given final block not properly padded`. The two are separate settings because a
legacy `-storetype JKS` keystore does support distinct passwords.

That `gh` is GitHub's own CLI (`github-cli`), not the abandoned npm package of
the same name — the npm one authenticates through an endpoint GitHub removed in
2020 and can only fail with `Error creating GitHub token / Not Found`. If it is
installed, its `~/.local/bin/gh` symlink shadows the real `/usr/bin/gh`, so
remove it rather than installing alongside it.

None of this is required: the four secrets can equally be pasted into
Settings → Secrets and variables → Actions. The `gh` the release workflow
itself calls is the one preinstalled on GitHub's runners.

The release job checks all four are present and opens the keystore before it
starts the build, so a mis-pasted secret fails in seconds rather than after
the cross-compile.

Locally the same variables — `ZEDEX_KEYSTORE` pointing at the file, plus
`ZEDEX_KEYSTORE_PASSWORD`, `ZEDEX_KEY_ALIAS`, `ZEDEX_KEY_PASSWORD` — make
`assembleRelease` produce a signed `app-release.apk`. With none of them set it
produces `app-release-unsigned.apk`, which is the normal case.

## Next steps

The list under [Not yet](../README.md#not-yet) is roughly in the order that
would add the most. A peripherals screen is the single change that unlocks the
widest range of hardware, since all of it is emulated already.

Where some of those land:

- Shaders — CRT, scanline, sharp-bilinear — go in the fragment shader in
  `native/ui/android/android_gl.c`, the only code that touches a pixel
- `armeabi-v7a` is a matter of adding it to `ABIS` and to `abiFilters` in
  `app/build.gradle`; nothing should stop it
- A debugger front end would sit on the core's own debugging API

The suite in `app/src/androidTest` covers two paths so far. More there is
worth more than any one feature above.
