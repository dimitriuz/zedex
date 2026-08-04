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
  build-demo.py        assembles demo/, Z80 assembler and all
demo/            the tape the store screenshots are taken of
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
it would mean converting glyphs to paths.

**Updating it from a design pack takes more than a copy.** The pack ships the
adaptive layers at two densities - `ic_launcher_foreground` at xhdpi and xxxhdpi,
`ic_launcher_monochrome` at xxxhdpi only - and the app carries all five, so
copying what is in the pack leaves mdpi, hdpi and xxhdpi holding the *previous*
design. That is not academic: a 420 dpi phone resolves xxhdpi, and after one such
copy the launcher showed the new icon while the splash showed the old one. Rescale
the missing densities from the 432 px master (108 dp: 108, 162, 216, 324, 432) and
check both.

**And the pack's own layer split is wrong for a launcher.** It draws the whole
device - the plate, its gradient, the `48K`/`Z80` labels and the coloured
dashes - into `ic_launcher_foreground` at about half the canvas width, and
leaves the background a flat near-black. But the background is what the
launcher's mask cuts its circle out of, so that composition shows a small dark
square inside a black circle: a ring around the icon that nothing else on the
home screen has. The same applies to `ic_launcher_monochrome`, which shipped as
an outlined box with tiny lettering inside it.

So the layers here are *rebuilt* from the pack's artwork rather than copied:
`drawable/ic_launcher_background.xml` is the plate's own vertical gradient, read
off the art and stretched edge to edge, and the foreground carries only the
wordmark and the dashes at about 60% of what the mask shows. The corner labels
are dropped - they are unreadable at 48 dp and fall outside a circular mask
anyway. If a new pack is ever copied in wholesale, the ring comes back.

Two files in the pack are deliberately not taken. `values/themes-splash.xml`
inherits from `Theme.SplashScreen`, which is `androidx.core:core-splashscreen` -
`values-v31/styles.xml` already says the same thing in framework attributes, for
the reason below. And `drawable-xxxhdpi/ic_stat_zedex.png` is a notification small
icon for an app that posts no notifications. `mipmap-anydpi-v26` is what every
supported device actually uses, minSdk being 30; the legacy mipmaps are there
for tooling and the store listing. The Android 12 splash is set through the
framework attributes in `values-v31/styles.xml` rather than
`androidx.core:core-splashscreen`, since the library exists to back-port it
below API 31 and the app has no dependencies.

### The cheat database

`app/src/main/assets/pokes.db` is built, not written:

```sh
scripts/build-poke-db.py ~/apps/ZX_Pokemaster-.../pokemaster.db
```

It reduces ZX Pokemaster's 20 MB database to the two things an emulator needs —
the games that actually have pokes, and one md5 per known dump of them — which is
2.7 MB on disk and **1.08 MB in the APK**. The asset is committed, because a build
should not need a copy of somebody else's database; the script is committed
because an asset nobody can rebuild is an asset nobody can check. Bump
`PokeDatabase.VERSION` when the asset changes, or the copy already unpacked on a
device will be kept.

### The demo tape

`demo/zedex.tap` is what the store screenshots and the animated ones are taken
of: the app's icon, running on the machine the app emulates. The wordmark, the
four coloured pills under it, `48K` and `Z80` in the corners, a starfield, and
the store's own line scrolling past.

```sh
scripts/build-demo.py                        # demo/zedex.asm -> demo/zedex.tap
scripts/build-demo.py --list                 # and what every line assembled to
scripts/build-demo.py --logo [font.ttf]      # redraw demo/logo.inc
```

The Z80 assembler is *in the script*, because the alternative is every machine
that builds this tree installing one for a single file. It covers what the demo
uses and refuses everything else rather than guessing, and `--list` prints the
bytes beside the source, which is how it was checked. The tape is committed, so
taking a screenshot needs neither Python nor a font.

The wordmark is a picture and `--logo` is what draws it, thresholding `zedex` to
one bit; its output, `demo/logo.inc`, is committed as well, so an ordinary build
renders nothing and cannot drift with the machine's fonts. The icon's face is
Onest ExtraBold, which a Linux box does not have — the default is Noto Sans
ExtraBold, and at 48 pixels tall, in one bit, the two are the same lettering.
Pass a path to use the real one.

Nothing in the demo is timed against the raster, which is why it behaves the
same on a 48K, a 128 and a +3, and why four things can move at once. Caps Shift
and Space together stops it and hands the machine back to BASIC, AY included.

### The demo's music

`demo/pt3.asm` is a **ProTracker 3 player**, and `demo/timeup.pt3` the module it
plays. Written to the AY unconditionally: a machine without one decodes neither
of its ports and hears nothing, so the demo stays one file and a 48K is quiet.

A player rather than a recording, because the sizes are not close — this module
is 6 KB and plays for a minute and a half, where the same music as a stream of
AY registers is 27 KB packed and 72 KB raw. The player is about 1.5 KB.

Two tables the module does not carry are generated into `demo/pt3tables.inc` by
the build: which AY period a note means, and how a sample's amplitude and a
line's volume combine. Both belong to the tracker rather than to the music, both
are pure arithmetic, and the file is therefore *not* committed. The frequency
table is grown from twelve base periods by halving, with the tracker's own two
corrections; the volume table is Ivan Roshin's VolTableCreator, which is what
the tracker's player runs at startup. The values matter: table 1 puts A-4 at
390 Hz, not 440, so computing equal temperament instead sounds two semitones
sharp.

**How it was checked, and how to check it again.** The player was ported from
Vince Weaver's [pt3_lib](http://www.deater.net/weave/vmwprod/pt3_lib/), which is
itself verified against Bulba's ay_emul, and then *diffed against it frame by
frame*: compile `pt3_to_ym5` from that library, convert the module to a YM5
register dump, and run the player under a small Z80 interpreter, comparing all
fourteen registers each frame. All 4,608 frames of this module match. That is
the only way to tell a subtly wrong player from a right one — the first attempt
sounded like noise because `loadsample` clobbered the HL the decoder was walking
the pattern with, which no amount of listening would have located.

The **AY lamp** in the activity column is the quick check that the player is
running at all; recording the emulator's audio off the host was not worth the
fight.

**The music is not ours.** *Time Up* by
[shiru8bit](https://opengameart.org/users/shiru8bit), from OpenGameArt under
**CC-BY 3.0**. Attribution is required, so the tape says *Music by shiru8bit* in
its bottom corner — standing still, where a screenshot catches it, rather than
going past once a minute in the scroller. The link belongs in the README's
licence section and not on a Spectrum screen, where nobody can follow it. If the
tune is ever swapped, the corner line, the README and this paragraph move
together.

**It ships in the app.** A `stageDemoTape` task in `app/build.gradle` puts
`demo/zedex.tap` where the assets are gathered from, so the APK cannot carry a
copy older than the tree's, and `Storage.installDemo` copies it into the tapes
folder beside the ROMs, on
the same first-run path and for the same reason: that is the moment the folders
are settled. Once and never again, recorded in the `demoInstalled` preference
rather than by whether the file is there, so a tape somebody deletes stays
deleted. An install that predates the demo gets it on its next launch, quietly.

That task is not a `Copy`, and the difference is the whole of a release build
that failed. A plain directory added to `assets.srcDirs` is written by one task
and read by several, and Gradle stops any reader that has not been told which
task writes it. Telling the ones you know about is whack-a-mole: the merge tasks
were wired, the release build then stopped on **lint**, which reads the same
directory. So it is a typed task with a `DirectoryProperty` output, handed to
AGP's `addGeneratedSourceDirectory`, which wires every consumer there is and any
added later. `assembleDebug` will not catch a regression here — `lintVital` only
runs for release, so check with `./gradlew assembleRelease`.

The first run says where it is and nothing more — a line under the two folder
buttons, naming the path, kept in step as the data folder is chosen. It used to
be a screen of its own with *Load the demo* and *Not now* on it, and that is a
toll booth: an answer demanded before the Spectrum appears, when the answer is
nearly always no and the tape is a tape either way. Nothing asks on any later
launch, and nothing asks on an upgrade.

Loading it by hand: put it in the content folder and open it like any other
tape, or hand it straight over —

```sh
adb push demo/zedex.tap /storage/emulated/0/Download/Spectrum/tapes/
adb shell am start -a android.intent.action.VIEW -t application/octet-stream \
    -d file:///storage/emulated/0/Download/Spectrum/tapes/zedex.tap \
    -n dev.ldlab.zedex.debug/dev.ldlab.zedex.EmulatorActivity
```

A `file://` URI is not something one app may hand another, but `am` is not an
app and the activity takes it — which is the shortest way to a running demo
without driving the picker. On a 128 or a +3, press Enter on *Loader* first.

The app is a handful of classes: `EmulatorActivity` holds the menus,
`SpectrumKeyboardView` the keyboard, `Storage` decides where things live,
`Recorder` takes frames off the emulation thread and `GifRecording` /
`Mp4Recording` turn them into files.

## Building

Requires the Android SDK with NDK r27, plus `autoconf`-era build tools on the
host (`make`, `perl`, `pkg-config`, and a host `gcc` for libspectrum's
build-time codegen).

### The upstream version is pinned

The two upstream tarballs are not in git. The build script downloads them into
`vendor/` on first run, verifies their SHA-256, and never writes to them again.

**An upstream release cannot arrive on its own.** The version is written down
twice and both have to agree, so there is no "latest" for a new Fuse to slip
into:

```sh
FUSE_VER="1.9.0"          # in the URL: .../fuse/$FUSE_VER/fuse-$FUSE_VER.tar.gz
LIBSPECTRUM_VER="1.6.2"
```

and each `fetch` carries the hash of the exact tarball it expects. A release
re-rolled under the same name fails the checksum and stops the build rather
than compiling something nobody looked at.

**Raising it is deliberate, and it is three steps.** Change the version, put in
the new hash, and rebuild from clean:

```sh
curl -fsSL -o - https://downloads.sourceforge.net/project/fuse-emulator/fuse/1.10.0/fuse-1.10.0.tar.gz | sha256sum
# edit FUSE_VER and the hash beside it in scripts/build-native.sh
./scripts/build-native.sh clean && ./scripts/build-native.sh
```

Rebuild from clean because `fetch` skips a version whose folder is already
there — so a bumped number with the old tree still in `vendor/` would quietly
build the old code. And read the upstream changelog for the UI layer before
trusting the result: the backend in `native/ui/android` stands in for `ui/fb`
and takes `fuse_OBJECTS`, `fuse_LDADD` and two weakened symbols from the
generated Makefile, so it follows upstream by asking rather than by copying —
but a UI entry point that changes shape is still a compile error waiting for
whoever bumps the number.

Three ABIs: `arm64-v8a`, `armeabi-v7a` and `x86_64` — every phone in use, and
the emulator. There is no 32-bit x86, which no device has needed for years.

```sh
./scripts/build-native.sh              # all three; a couple of minutes cold
ABIS=x86_64 ./scripts/build-native.sh  # single ABI, while iterating
./scripts/build-native.sh clean

./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Three build types, and one of them is for Play

| | | |
| --- | --- | --- |
| `assembleDebug` | `app-debug.apk` | `dev.ldlab.zedex.debug`, the repository's debug key |
| `assembleRelease` | `app-release.apk` | what the GitHub release carries |
| `assemblePlay`, `bundlePlay` | `app-play.apk`, `app-play.aab` | **what goes to Google Play** |

`play` is `release` with All files access taken out of the manifest by
`app/src/play/AndroidManifest.xml`, four lines of `tools:node="remove"`. Play
judges an app by the permissions its manifest asks for rather than by what it
does with them, and `MANAGE_EXTERNAL_STORAGE` is only allowed where an app
cannot work without it — which this one can, the default folder being
`Documents/Zedex`, which scoped storage grants for nothing. Declaring it anyway
means defending it in the All files access declaration on every release, with
the honest answer being "so the user can put the folder where they like", which
the policy refuses. The builds nobody reviews keep the permission and offer it
as a choice; see *Storage* below.

The same split carries the **updater**. `src/sideload/java` holds the real one
and is compiled into `debug` and `release`; `src/noupdate/java` holds a stub that
answers no and is compiled into `play`. Two source sets rather than a flag in one
class, so the Play artifact does not contain the code at all — and its manifest
has `REQUEST_INSTALL_PACKAGES` removed with the same `tools:node="remove"`.
Verify both after touching any of it:

```sh
# neither the permission nor the code, in the Play APK
aapt2 dump permissions app/build/outputs/apk/play/app-play.apk | grep INSTALL_PACKAGES
unzip -p app/build/outputs/apk/play/app-play.apk classes.dex | strings | grep releases/latest
```

### What a release publishes, and how the numbers are read

Four files, and two of them are for the app rather than for a person:

| | |
| --- | --- |
| `Zedex-<v>.apk` | the build |
| `Zedex-<v>.apk.sha256` | its hash, for anyone checking by hand |
| `latest.json` | version, APK URL and hash — what the installed app reads |
| `alive.txt` | nothing worth reading; it exists to be counted |

The app asks `/releases/latest/download/latest.json`, which is a permanent
redirect to the newest release's copy, so the URL never changes. It is an asset
and not `api.github.com` for two unrelated reasons: the API's sixty requests an
hour are *per IP* and a carrier NAT is one IP for a great many phones, and an
asset is counted where an API call is not.

That count is the project's only measure of use, and reading it is one request:

```sh
curl -s https://api.github.com/repos/dimitriuz/zedex/releases | python3 -c "
import json,sys
for r in json.load(sys.stdin):
    print(r['tag_name'], {a['name']: a['download_count'] for a in r['assets']})"
```

`latest.json` counts app starts with the check on; `alive.txt`, being fetched from
the release the running build came from, counts them per version — which is the
figure to look at before dropping support for anything. Both are cumulative and
GitHub keeps no history, so a daily snapshot is the only way to see a rate. And
both include your own testing and any CI that fetches them, so treat small
numbers as noise.

**Assets on a draft release are not reachable** at those URLs until the draft is
published, so a tagged build offers nobody an update until you press publish.

Testing the updater needs a **release** build, since it refuses a debuggable one,
and a version older than the newest release so there is something to offer:
`./gradlew assembleRelease -PzedexVersionName=0.9.0`. It will get as far as
Android's installer and stop there, because a locally built release is signed
with the debug key while the APK on GitHub is signed with the real one.

**Keep Android Studio's Build Variant on `debug`.** Adding a third build type
changed the variant list, and Studio's remembered selection does not always
survive that — it lands on `play` or `release`, both of which install as
`dev.ldlab.zedex` rather than `dev.ldlab.zedex.debug`. Run then fails with

```
Activity class {dev.ldlab.zedex/dev.ldlab.zedex.EmulatorActivity} does not exist
```

which reads like a manifest problem and is not one: it is the wrong variant, and
the package it names is simply not installed. *View ▸ Tool Windows ▸ Build
Variants*, set `app` to `debug`. Neither of the other two is debuggable anyway,
so breakpoints would not work in them even when they do run.

A build type rather than a product flavour **on purpose**. Flavours rename every
existing task and output — `assembleDebug` becomes `assembleFullDebug`,
`connectedDebugAndroidTest` becomes `connectedFullDebugAndroidTest`,
`app-release.apk` becomes `app-full-release.apk` — and both workflows, this file
and the scripts all name those. A build type only adds tasks.

The upload is the **`.aab`**, not the APK. Tagging a release builds it: the
release workflow runs `assembleRelease bundlePlay`, checks it, and leaves it as
a **run artifact** called `Zedex-<version>-play` rather than a release asset,
because an `.aab` cannot be installed and one sitting beside the APK on the
release page is a support question waiting to happen. Download that and give it
to the Console.

Building it by hand needs the real key, and **the debug key must never sign
it**: the first upload registers its certificate as the app's upload key, and
`app/debug.keystore` is in this repository with the published default password,
so that one would let anybody with a clone sign for Zedex. A key reset request
is the only way back. With `ZEDEX_KEYSTORE` unset, `bundlePlay` is signed with
exactly that key, so a local bundle is for looking at and not for uploading.

```sh
env ZEDEX_KEYSTORE=... ZEDEX_KEYSTORE_PASSWORD=... \
    ZEDEX_KEY_ALIAS=... ZEDEX_KEY_PASSWORD=... ./gradlew bundlePlay

aab=app/build/outputs/bundle/play/app-play.aab
unzip -p "$aab" base/manifest/AndroidManifest.xml | strings | grep permission.
# android.permission.INTERNET   — and nothing else

jarsigner -verify -verbose:summary -certs "$aab" | grep 'Signed by'
# anything but CN=Android Debug
```

`-verbose:summary` is not optional in that last one. `jarsigner -verify -certs`
on its own prints no certificate at all, so a grep for the debug key finds
nothing and passes whatever it was handed - a check that always succeeds. The
workflow makes the same two checks, and the ABIs, on every tag.

### 16 KB pages, and why a bump is not just a number

Play's target-API floor moves; it is 35 as of August 2026, and `targetSdk` in
`app/build.gradle` follows it. Two things come with targeting 35 or later.

**Native libraries must be 16 KB page aligned.** Android 15 can run with 16 KB
pages, and a library whose `LOAD` segments are 4 KB aligned cannot be mapped on
such a device at all — it is not a warning to be lived with. The NDK's own CMake
and ndk-build toolchains pass the flag; `scripts/build-native.sh` drives the
target clang by hand and so has to say it itself, `-Wl,-z,max-page-size=16384`,
on both `LDFLAGS` and the explicit `libfuse.so` link line. The script checks the
result and stops if it is wrong:

```sh
readelf -lW app/src/main/jniLibs/arm64-v8a/libfuse.so | grep LOAD
# the alignment column must be 0x4000, not 0x1000
```

So **raising `targetSdk` means re-running the native build**, not only Gradle. A
4 KB library is invisible on every device anyone is likely to test on.

**Edge-to-edge is enforced, and the display cutout with it.** This was the
expensive half. A window is laid out into the cutout whether it asks or not —
the mode that used to letterboxed it away from a camera hole is read as
`ALWAYS` now, and there is no opting out — and hiding the system bars does not
help, because a hidden bar reports a zero inset while `displayCutout()` does
not. On a phone with a hole the quick bar's icons came out underneath it and the
settings page drew its title across its own tabs. Neither appears on an emulator
until a cutout is turned on:

```sh
adb shell cmd overlay enable --user 0 \
    com.android.internal.display.cutout.emulation.hole
adb reboot          # the overlay does not apply until the display is remade
adb shell dumpsys window | grep -o 'DisplayCutout{[^}]*}'
```

`EmulatorLayout` now keeps a `safe` rect: `arrange()` is handed the window minus
it and still works from 0,0, and `placeChild` adds the offset, so no branch of
that method knows a cutout exists. Every other screen builds its own view tree
and calls `SafeArea.fit()` on `android.R.id.content`. Verified on an API 36
emulator with a 136 px hole, both ways up — sideways it moves to one end, which
is where the joystick and the key buttons are — and the whole instrumentation
suite passes with it on.

### Preferences have no types

`scripts/check-prefs.py` compares every `putX` against every `getX` for each
preference key and fails on a mismatch. Worth running after touching anything
that reads settings, because the failure mode is invisible in testing: a getter
of the wrong type throws only when the key is *present*, and on a fresh install
every key is absent. `joystickType` is an `Int`; `getString` on it shipped a
crash in 1.1.0 that no test on a clean device could have caught.

### Bug reports and the crash catcher

*About Zedex › Report a problem* builds `Diagnostics.report()`, shows it in an
editable box and hands it to a mail app. `Crashes` installs a process-wide
`UncaughtExceptionHandler` that writes the trace plus the same report to
`files/crash.txt`; the next start offers it once and deletes it either way.

Forcing a crash is the only sane way to test that half:

```sh
adb shell am crash dev.ldlab.zedex.debug     # writes files/crash.txt
adb shell run-as dev.ldlab.zedex.debug cat files/crash.txt
adb shell am start -n dev.ldlab.zedex.debug/dev.ldlab.zedex.EmulatorActivity
```

`am crash` arrives as a `CrashedByAdbException` on the main thread, which is a
real uncaught exception as far as the handler is concerned.

**Sending needs the `<queries>` block in the manifest.** Android 11 and later hide
other apps from one that has not said what it is looking for, and that includes
hiding them from the chooser — without it the Send button finds no mail app on a
device that has one.

### Native debug symbols

`build-native.sh` keeps the unstripped library beside the stripped one:
`app/src/main/jniLibs/` gets the stripped 1.8 MB that ships, and
`build-native/symbols/<abi>/libfuse.so` the 2.1 MB with the symbol table. The
release workflow zips the second into `Zedex-<version>-symbols.zip` and puts it
in the same run artifact as the bundle; upload it in the Console under *App
bundle explorer › Downloads › upload native debug symbols*. Without it a native
crash report is a list of addresses.

Neither `build-native/` nor `jniLibs/` is in git, so both are rebuilt by any
build and there is nothing to keep in step.

**The debug build's data folder is its own**, `Documents/Zedex-debug`, set by
`R.string.data_folder` in `app/src/debug/res/values/strings.xml`. It has to be:
the two builds are different packages and so different uids, and scoped storage
gives an app only the files it wrote, so sharing a folder means whichever
installed second finds it empty and cannot write into it either — the filenames
it wants belong to files it is not allowed to see. That shows up as *ROMs
needed* with nothing in the log to explain it.

**The debug build is a package of its own**, `dev.ldlab.zedex.debug`, called
*Zedex debug* on the launcher. It installs beside the release build rather than
replacing it — which it could not do anyway, the two being signed with
different keys — so both can be on a device at once and neither needs an
uninstall. It has its own settings, its own storage permission and its own data
folder, so anything addressed to the app by name needs the right one of the
two. Fuse finding its data files relative to `argv[0]` is what makes this free;
see *Data files and environment* in [INTERNALS.md](INTERNALS.md).

**Debug builds are signed with `app/debug.keystore`, which is in the
repository.** That is what lets one debug APK replace another: left to itself
Gradle invents a debug key per machine and keeps it in
`~/.android/debug.keystore`, so a build from CI — where that file does not
exist and is created fresh for the run — carries a key nothing else has, and
`adb install -r` answers `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Three builds
meant three certificates and three uninstalls, CI against CI included.

Nothing is given away by committing it. It is the stock Android debug
certificate — `CN=Android Debug`, alias `androiddebugkey`, the published
default password — it is not the release key, and it cannot update anything
installed from a store. It signs debug builds, and release builds made without
a real key, which are the two things that never leave the bench. Anything installed
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

`StatesTest` proves a snapshot is a snapshot rather than a file of the right
size: the border is set to a colour, the state saved, the border changed, the
state loaded, and the border read back off a screenshot. Rename and delete are
checked against the files, thumbnail included.

`PokesTest` searches the shipped database by name — the same index a fingerprint
reaches — and takes a typed poke all the way into memory, with a BASIC reporter
PEEKing the address and saying so in the border. It pokes above RAMTOP, which the
reporter lowers itself: the printer buffer at 23296 is only free on a 48K, and on
a 128 the byte was gone before the next PEEK saw it.

`RecentsTest` is the only test that reaches `Media.stage()`, because that only
happens for a real document. `MediaStore` provides one and an `ACTION_VIEW`
intent carries it in, which is the path a file manager's hand-over takes: the
md5, the persistable grant, the write-back origin and the recent list are all
there, and `Emulator.open()` — which calls Fuse directly — misses every one.

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

The ROMs decide what can run, and every test skips rather than fails when the
folder it was pointed at has none.

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

Both workflows then check the APK really contains all three ABIs and `fuse.font`,
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

The build also stamps two string resources out of git - `build_commit` and
`build_date` - which is what the About screen shows beside the version. From git
and not from the clock, so the same tree gives the same APK and the build cache
still works; "which commit" is the more useful answer anyway. A source download
with no git history leaves them empty and the screen simply says less.

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
`assembleRelease` produce an `app-release.apk` signed with the real key.

**With none of them set it is signed with the debug key instead**, and that is
deliberate. Unsigned is an APK nothing can install, and a local key of its own
would be a third certificate: Android will not let one certificate update
another, so a release built on the bench could not replace the debug build on
the device, or be replaced by it. Sharing the debug key makes the two
interchangeable — `adb install -r` either way round, no uninstall. The release
workflow always sets the variables, so nothing anyone else installs is signed
this way.

## Next steps

The list under [Not yet](../README.md#not-yet) is roughly in the order that
would add the most. A peripherals screen is the single change that unlocks the
widest range of hardware, since all of it is emulated already.

Where some of those land:

- Shaders — CRT, scanline, sharp-bilinear — go in the fragment shader in
  `native/ui/android/android_gl.c`, the only code that touches a pixel
- A debugger front end would sit on the core's own debugging API

The suite covers the disk story, capture, the joystick and the hotkeys, save
states, the cheats and the recent files. Writing a disk **back over the document
it came from** is the one dangerous path with no test: `disk_write` truncates
before it knows it has anything to write, so a failure destroys the original. It
needs a formatted disk opened from a document, which is two minutes of TR-DOS
formatting plus a made-up MediaStore URI. Worth doing.
