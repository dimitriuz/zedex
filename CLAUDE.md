# Zedex — working notes

A ZX Spectrum emulator for Android, built on an unmodified Fuse core.
`README.md` is for people using the app and nothing else — keep build, test
and release material out of it. `docs/DEVELOPING.md` covers building, driving
it from adb, the tests and releases; `docs/INTERNALS.md` how the core is wired
in. This file is the operational knowledge that is easy to get wrong and
expensive to rediscover.

## Hard rules

- **Never modify `vendor/`.** It is what was downloaded, and it stays that way.
  The Android backend is swapped in at link time — configure with `--with-fb`,
  then never compile `ui/fb` and link `native/ui/android` in its place. Two
  symbols are overridden with `llvm-objcopy --weaken-symbol`. Almost nothing
  needs a patch, and reaching for one is usually a sign of looking in the wrong
  layer.
- **What does need one is a patch in git, never an edit in place.** The build
  compiles a *copy* of the release with `native/patches/*.patch` applied, made
  by `scripts/fuse-src.sh` in `build-native/src`; with no patches it is the
  release byte for byte. The copy is a git repository whose first commit is the
  pristine tarball, tagged `upstream`, so `scripts/fuse-src.sh save` turns
  commits into the series and `reset` proves a fresh clone gets the same tree.
  Two traps, both of which cost a build that looked fine: **a build tree
  remembers the source directory it was configured against** — pointed at the
  patched tree while configured for `vendor/` it finds every object up to date
  and links a library with none of the patches in it, which is why the source
  path is in `.package` beside the package name; and **the baseline is the
  tarball, not `vendor/`**, because Fuse's perl codegen writes `settings.c` and
  the z80 tables into the *source* directory and a tree that has been built in
  holds files a fresh checkout would not. See *Patching Fuse* in
  `docs/DEVELOPING.md`.
- **The upstream version is pinned and stays pinned.** `FUSE_VER` and
  `LIBSPECTRUM_VER` in `scripts/build-native.sh` go into the URL, and each
  tarball's SHA-256 is checked, so no release arrives by itself. Raising one
  means the version *and* its hash *and* `build-native.sh clean` — `fetch`
  skips a version already extracted, so a bumped number over an old
  `vendor/` builds the old code and says nothing. See *The upstream version is
  pinned* in `docs/DEVELOPING.md`.
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
- **`targetSdk` is Play's floor, and it moves.** 35 since August 2026. Raising
  it is not only a number: an app targeting 35 or later must have **16 KB page
  aligned** native libraries, which is `-Wl,-z,max-page-size=16384` on the link
  line in `scripts/build-native.sh`. The NDK's own CMake toolchain passes it; a
  hand-driven cross-compile like ours gets the 4 KB default, and a 4 KB library
  builds, installs and runs on every current device while being unmappable on a
  16 KB one. The script asserts `0x4000` at the end for exactly that reason.
  **Bumping `targetSdk` therefore means re-running the native build**, not just
  Gradle.
- **Everything of ours stays inside the safe area, and the cutout is the reason.**
  Targeting API 35 lays a window into the display cutout whether it asks or not:
  the mode that used to letterbox it away from a camera hole is read as
  `ALWAYS`, and it cannot be opted out of. Hiding the system bars does not help
  — a bar that is hidden reports a zero inset, but `displayCutout()` does not.
  So both of these were wrong on a phone with a hole, and neither showed up on
  an emulator without one: the quick bar's icons sat under the camera, and the
  settings page drew its title over its own tabs.
  - `EmulatorLayout` keeps a `safe` rect. `arrange()` is given the window minus
    it and works from 0,0 as it always did; `placeChild` adds the offset. No
    branch of that method knows a cutout exists, which is the only reason this
    was a small change.
  - Everything else — settings, states, about, the hotkeys and the profile
    editor — builds its own view tree and gets `SafeArea.fit()` on
    `android.R.id.content`. Padding is the whole of what a column needs.
  - **Test it with a cutout, because the AVDs have none.**
    `adb shell cmd overlay enable --user 0
    com.android.internal.display.cutout.emulation.hole`, then reboot — the
    overlay does not take effect until the display is reconfigured. `dumpsys
    window | grep DisplayCutout` should report a top inset. Sideways it moves to
    one end, which is where the joystick and the keys are.
- **The debug build's data folder must not be the release build's.**
  `R.string.data_folder` is `Zedex` in `src/main` and `Zedex-debug` in
  `src/debug`, and that difference is load bearing. The two are separate
  packages and so separate uids; scoped storage hands an app only the files it
  wrote itself, so pointed at one folder the second one installed finds it empty
  *and* cannot write into it, the names it wants being taken by files it is not
  allowed to see. The symptom is *ROMs needed* on a machine where the other
  build ran first, with nothing in the log to say why. No space in the name
  either: every `adb shell ls` and script that names the folder would need
  quoting, and one that forgot silently listed the release build's folder
  instead and looked like it had worked.
- **The overlay keyboard gives way sideways, never downwards.** Sideways the
  joystick is at the two ends of the window, so the keyboard is held clear of the
  pad on the left and of fire and its keys on the right and is then as tall as the
  keyboard below the picture is allowed to be - identical, in fact: 1095x390 of
  keys either way, measured. Clamping its *height* against the bottom of those
  controls instead, which is what it used to do, made a full 128K plate 295px tall
  where the real one is 454 and the keys a third of the size. There is room
  between the controls; there was never room beneath them. Upright is the other
  way about - the controls sit under the picture with nowhere sideways to go - so
  there the height is what yields, and that branch is unchanged.
- **A preference's type is whatever wrote it, and the wrong getter throws.**
  `joystickType` is `putInt` in three places; `getString` on it is a
  ClassCastException. The trap is that it throws only when the key is *present* -
  `getString` on an absent key returns the default quite happily - so a mismatch
  passes every test on a fresh install and crashes on the first device where
  somebody has changed that setting. That is how 1.1.0 shipped a crash in the bug
  reporter, of all things. **Check the writer before reading a preference**, and
  run `scripts/check-prefs.py`, which compares every `putX` against every `getX`
  and exits non-zero on a mismatch. Where a report or anything else has no
  business caring about the type, read `getAll()` and print what is there -
  `Diagnostics.settings` does.
- **New code that reads existing state must be tested against *used* state.**
  The same lesson, one level up. A fresh install has no preferences, no save
  states, an empty recent list and a default keyboard, so a test on one exercises
  every "absent" branch and none of the others. Set the thing first - change the
  setting, load a game, fill the folder - and then run the new code.
- **A bug report is shown before it is sent, and that is the whole design.**
  `Diagnostics.report()` is built on request, `Feedback` puts it in an editable
  box, and the user's own mail app sends it. Nothing is gathered in the
  background, there is no server, and `docs/PRIVACY.md` says so - so a change
  that sent anything automatically, or added an identifier to make grouping
  easier, would make that policy untrue. `DiagnosticsTest` pins the keys a report
  must carry and checks it holds no identifier and no email address.
- **`ACTION_SENDTO` needs a `<queries>` block or it silently resolves to
  nothing.** Android 11 hides other apps from an app that has not declared what
  it looks for, including from the chooser - so the Send button finds no mail app
  on a phone that has three, and looks like a broken button. `mailto` is declared
  in the main manifest for exactly this. `Crashes` writes the last crash to
  `files/crash.txt` and the next start offers it once, deleting it either way;
  `adb shell am crash <package>` is how to test that without waiting for a real one.
- **Never stop publishing what a shipped client fetches.** The updater's endpoint
  is a protocol with every copy already installed, and they cannot be changed.
  1.1.0 asks for `/releases/latest/download/latest.json`; the switch to reading the
  redirect stopped publishing it, and v1.1.1 went out without it - so every 1.1.0
  install got a 404 and said nothing, for ever, with no way out from inside the
  app. It cost nothing only because v1.1.0 was never published: the one install was
  a phone on this desk, mended by uploading `latest.json` to v1.1.1 by hand. Before
  changing how the check works again, keep the old endpoint answering until the
  per-version download counts show nobody is on the old version.
- **A permission that lives in a settings page has no result to wait for.** Allowing
  the app to install packages opens a page of Android's own; there is no
  `onActivityResult`, so the only way to notice the answer is to look again in
  `onResume`. Without that the update stopped dead: permission granted, nothing
  downloaded, the offer back only after a restart - reported by a user, and fairly.
  `Updater.resumeIfAllowed` carries on, and only for somebody who actually went to
  the page.
- **The update check reads a release asset, not the releases API, and both
  reasons matter.** `api.github.com` allows sixty unauthenticated requests an
  hour *per IP*, and a carrier NAT is one address for a great many phones - so
  the API would fail for exactly the users who share one, silently. And an asset
  has a `download_count`, which is the only anonymous measure of use this project
  has, and it needs no asset of its own: the newest version comes from the
  **redirect** on `/releases/latest`, whose `Location` header is
  `.../releases/tag/v1.1.1`, and the counted file is the `.sha256` a release
  already publishes beside its APK. Fetching this build's own `.sha256` counts one
  start of this version; the APK's count is how many took an update. **This ties
  the app to the asset naming the workflow uses** - `Zedex-<version>.apk` - and
  that coupling is the price of a release page holding nothing but the two files a
  person would want. The counting is disclosed in `docs/PRIVACY.md` and the README, and
  it has to stay disclosed - it is the one thing in the app that could make
  "no usage statistics" untrue.
- **The Play build cannot update itself, and must not look as though it could.**
  Play updates its own apps, and one of its own downloading an APK and installing
  it is against its policy - the permission alone is something a review stops to
  ask about. So the updater exists twice: `src/sideload/java` for real, compiled
  into debug and release, and `src/noupdate/java` as a stub that answers no,
  compiled into `play`. The Play manifest removes `REQUEST_INSTALL_PACKAGES`
  beside All files access. Checked in CI and worth checking by hand after any
  change here — the Play APK must contain neither the permission nor the string
  `releases/latest`. `Updater.available()` also refuses a debuggable build (the
  GitHub APK is the release package, signed with the release key) and any install
  whose installer was Play, so the sideload APK put on some other store stays
  quiet too. **Holding the permission is not permission to install**: the user
  must allow the app as a source in a page of Android's own, which is why the
  check happens before the download rather than after fourteen megabytes.
- **The Play build has no All files access, and that is the whole point of it.**
  `assembleDebug` and `assembleRelease` declare `MANAGE_EXTERNAL_STORAGE`;
  `assemblePlay`/`bundlePlay` strip it in `app/src/play/AndroidManifest.xml` with
  `tools:node="remove"`, and the `.aab` from `bundlePlay` is what Google Play
  gets. Play judges the manifest, not the use, and the app works without it. A
  build **type**, not a flavour: flavours rename `assembleDebug`,
  `connectedDebugAndroidTest` and `app-release.apk`, which the workflows, the
  docs and the scripts all name. Nothing may offer to grant a permission the
  running build does not declare — `Storage.canAskForAnyFolder()` asks the
  manifest, and the folder chooser and the first-run grant row both check it.
- **What scoped storage allows, measured rather than assumed.** All of this on
  API 36 with the permission denied, and none of it is guessable:
  - `mkdirs` on `/storage/emulated/0/Zedex` returns **false** and the write
    fails ENOENT. A folder at the *root* of shared storage cannot be made
    without the permission. `Documents/Zedex` can, and works by plain path,
    which is what Fuse's stdio needs — hence `Storage.documentsRoot()`.
  - A root-level folder the **user** made reads `canRead()` true, `canWrite()`
    false, `list()` empty — and `mkdirs` of a subfolder inside it returns
    **true** while writing a file gives EPERM. That pair is why
    `Storage.isWritable()` creates a probe file instead of trusting `mkdirs`; it
    is the only test that gets this right, and it correctly refuses the folder.
  - A file **anyone else** wrote into the app's own folder is invisible:
    `exists()` true and `length()` right, but `list()` omits it and opening it
    gives EACCES. So "copy your tapes into the folder" cannot work; they come
    through the picker, which stages them. A folder that reads as empty is the
    failure mode, not an error.
  - **`exists()` is not "already there".** A file left by an install that is
    gone exists, reports the right length, and cannot be opened — MediaProvider
    clears the ownership of what an uninstalled package wrote, and a reinstall
    gets a new uid. `installRoms` took that for "the user's own ROM, leave it"
    and skipped all twenty-nine, so a reinstall came up on the ROMs panel
    offering to download a set into the folder it could not write. The test is
    `canRead() && length() > 0`; anything else is in the way, and a name in the
    way that cannot be deleted means the folder is no use for ROMs. See
    `Storage.romsDirectory` for where they go instead.
  - Images written under `Documents` are **not** in `MediaStore.Images`, so a
    screenshot there does not reach the gallery. That is why captures are not in
    the data folder at all: `Storage.capturesDirectory()` is `Pictures/Zedex`.
  - **A public collection refuses what does not match it.** `Pictures` takes
    `png`, `gif` and `mp4`; `Movies` takes `mp4` and answers **EPERM** for `png`
    and `gif`; neither takes `.tap`. So all three captures go to `Pictures` — the
    obvious split, MP4s to `Movies`, would have failed on the GIF, which is the
    recording the app makes by default, and only on the build nobody drives by
    hand.
  - **So a folder cannot be tested by writing a file into it.**
    `Storage.isWritable` probed with `.zedex`, which `Pictures` refuses for
    having no extension it knows — "cannot write to
    `/storage/emulated/0/Pictures/Zedex`", on a tablet where every screenshot
    the app puts there works. It probes with `mkdir` now: a directory has no
    type to disagree with, and mkdir is refused by exactly the permission the
    question is about. `WritableTest` holds both halves, and skips itself where
    All files access is granted, since MediaProvider then steps out of the way
    and the old probe passes.
  - **And the probe's name must be new each time.** MediaProvider keeps a row
    per path, and a row can outlive the file — one written while the app held
    All files access does. Then `createNewFile` is refused for a name that is
    *taken* while `exists()` says it is *absent*, which is exactly the pair the
    old code read as "this folder is no good", silently. That is what refused a
    tablet's `Documents/Zedex`, and what made the same tap work an hour later
    once something else had cleared the row — an hour spent looking at the
    folder, which was never the problem. The probe carries a `nanoTime` now.
  - **Read the whole stack before believing which folder was refused.**
    "Cannot write to `Documents/Zedex`" was `moveData` asking about
    `Pictures/Zedex` two frames down, and the toast named the folder the user
    had picked. `Storage.refused()` logs the folder and why for this reason.
  - **Writing the file is not enough to reach the gallery.** MediaStore had no
    row at all for a PNG that was on disk in `Pictures/Zedex` until
    `MediaScannerConnection.scanFile` was called; `Capture.announce()` does it
    for every capture, from the callback that fires when the file is really
    written.
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
- **ES-DE's two files are its syntax, not ours, and one of them replaces
  rather than merges.** `frontend/EsDe.java` writes
  `ES-DE/custom_systems/es_find_rules.xml` and `.../es_systems.xml`; both are
  documented in ES-DE's own INSTALL.md and the Android syntax differs from the
  desktop one, so read that before changing a variable.
  - A file in `custom_systems` **complements** the bundled configuration, but a
    `<system>` in it **replaces** the bundled system of the same name, commands
    and all. So the entry written for `zxspectrum` carries ES-DE's own Fuse and
    Speccy commands as well as ours; writing only ours would take the user's
    other two emulators away. The price is that our copy of their block freezes
    their extension list at the version it was copied from.
  - Nothing is overwritten. Both files are parsed, ours is added if absent, and
    they are written back — so a second run changes nothing, and a file the user
    wrote themselves keeps everything in it.
  - **`<queries>` again.** `getPackageInfo` on an undeclared package throws
    `NameNotFound` exactly as though ES-DE were not installed, so both of its
    package names are in the manifest's `queries` block. Without them the row
    hides itself on the very devices it is for.
  - **`%ROMPROVIDER%` means no permission and no folder to point at**: ES-DE
    grants read access to that one file as it launches. The cost is that the
    grant is not persistable, so the game lands in *Open recent…* and can only
    be reopened while ES-DE's grant lives; after that `Recents.forget` drops it,
    which is the existing behaviour for any one-off hand-over.
  - **Two ways in, and the second is why this works on Play at all.** With All
    files access it writes ordinary files; without it the user grants ES-DE's
    folder through `ACTION_OPEN_DOCUMENT_TREE` and the same code writes
    documents through the resolver — `EsDe.Place` is the seam, and everything
    above it is identical. The grant is persisted under `esdeTree`, so the
    picker appears once; it is tried before asking, and only a lost grant brings
    it back.
    - **Do not declare `MANAGE_EXTERNAL_STORAGE` in the Play build to get this.**
      Play's permitted uses are file managers, document management, backup,
      anti-virus, search, encryption and device migration — an emulator is none
      of them — and the policy says to use it only where SAF or MediaStore
      cannot serve. Undeclared or unapproved use risks the listing and the
      account. This feature is the proof that SAF serves.
    - `openOutputStream(uri, "wt")` and not `"w"`: without the truncate a
      shorter document written over a longer one keeps the tail of the old one
      and parses as neither.
    - **Check the folder is ES-DE's before writing into it.** A folder that is
      not takes both files quite happily and ES-DE never reads them, which would
      look exactly like success; `looksLikeEsDe` wants the name or one of the
      folders ES-DE always has, and says so plainly otherwise.
    - `DocumentsContract.EXTRA_INITIAL_URI` with a document URI for
      `/storage/emulated/0/ES-DE` opens the picker inside that folder, which
      turns the pick into one tap. Verified on an emulator.
  - The data folder is the other half of this and **cannot** be done the same
    way: Fuse opens ROMs, states and disks by path through stdio, and a tree
    grant confers no path access outside app-specific directories and public
    collections. That is why `Documents/Zedex` is the default.
  - **Test it against a real ES-DE**, and read `ES-DE/logs/es_log.txt` — it says
    which configuration files it parsed and expands the launch command, which is
    how "Data: %ROMPROVIDER% expanded: content://org.es_de.frontend.files/..."
    was confirmed. ES-DE takes d-pad keyevents, so `adb shell input keyevent
    KEYCODE_ENTER` walks into a system and launches a game without touching the
    screen.
- **A new string is nine files, and an activity is one line.** The app is
  translated into eight languages besides English; `values/strings.xml` is the
  original and `values-{de,es,fr,it,pl,cs,ru,uk}/` follow it. A string with no
  translation falls back to English, which is a fine way to ship one and an
  embarrassing way to ship fifty — **`scripts/check-strings.py`** counts them,
  and fails on a key no longer in `values/` or on a **format specifier that
  disagrees**. That last one is why it exists: `%1$s` here and `%1$d` there is a
  ClassCastException from `String.format` at the moment that one string is
  shown, in a language nobody testing in English will ever see. What must *not*
  be translated is listed in the script: `*_values` arrays are Fuse's own words
  and it compares them with `strcmp`.
  - **Every activity needs `attachBaseContext`.** Resources come from the
    context an activity was built on, so a screen speaks whatever `Language.wrap`
    put there when it opened. A new activity without that line is in the phone's
    language while the rest of the app is in the chosen one.
  - **`android:label` is resolved in the phone's language, not the app's.** The
    system reads it out of the manifest with its own resources, so the four
    screens that show a title set it in `onCreate` instead. Settings said
    *Settings* over Russian tabs until they did.
  - **The emulator screen is not recreated for a locale change**, because its
    `configChanges` includes `locale`; its menus were built with the words of
    the language chosen when it opened, so `onResume` compares the tag and calls
    `recreate()` itself. Verified with the machine still running afterwards.
  - **One mechanism, not two.** Android 13 has a per-app language of its own,
    and the app deliberately does not use it: it does not exist on 11 or 12,
    which `minSdk 30` still supports, and two controls able to disagree is
    worse than one. The preference is the only truth. It composes anyway - with
    the preference empty the app follows whatever locale the system hands it,
    which is how *Same as the phone* works and why `adb shell cmd locale
    set-app-locales dev.ldlab.zedex.debug --locales pl-PL` is a good way to test
    that path on an emulator whose `persist.sys.locale` SELinux will not let you
    set.
- **A setting has to be applied as well as stored.** Two places do that and a
  new setting belongs in one of them: `SettingsActivity`'s
  `onSharedPreferenceChanged`, which pushes into Fuse as the value changes, or
  `EmulatorActivity.onResume`, which re-reads what that screen may have changed
  while it had the window. A preference nothing reads again is a setting that
  does nothing until the app is next launched — the keyboard type was exactly
  that, and it looked broken rather than late.

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

`app/src/main/assets/fuse/` is staged by the same script and is not in git.
Nothing in the app reads it any more — the keyboards are drawn — so a tree
without it builds and runs; `./scripts/build-native.sh` puts it back.

## Tests

`app/src/androidTest` — UI Automator. Run one class while iterating; the
whole suite only for refactors or large features.

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=dev.ldlab.zedex.CaptureTest
```

Things learned the hard way, all of them recorded in the tests' own comments:

- **Run the instrumentation tests on an emulator, not the tablet.** MIUI refuses
  Gradle's install session with `INSTALL_FAILED_USER_RESTRICTED` unless *Install
  via USB* is on, and it lapses. `connectedAndroidTest` **uninstalls first**, so a
  refusal there leaves the device with no app at all and no way to put one back
  until somebody touches the phone. A plain `adb install -r` is a different code
  path and can still work when Gradle's does not; `am instrument` then runs a
  class without Gradle, and without the uninstall that wipes the data folder.
- **`Emulator.useDataFolder()` wants ROMs in `/sdcard/Download/Spectrum/roms`.**
  Without them the app comes up on the ROMs panel, `launch()` fails with *the
  keyboard never appeared* before `assumeFalse(needsRoms())` can skip the test,
  and every test in the class fails for a reason that has nothing to do with what
  it tests. `adb push roms/*.rom` puts it back.
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
- **A fresh install comes up on the first-run panel, not the machine.** It sits
  over the quick bar, so ☰ cannot be reached and every test that opens a menu
  fails with *the ☰ button never appeared* — fifteen of thirty-four did, and the
  panel had been there five days before anyone ran the whole suite. Since Gradle
  uninstalls the app first, **every** run starts this way. `Emulator.launch()`
  answers it with `R.string.setup_start` — the app's own string, not the English
  of it, because the app has languages now.
- **Read the *first* failure, not the count.** One test leaving a dialog open, or
  the joystick hidden, makes every later class fail with *the ☰ button never
  appeared* — nine of them once, from a single flake three classes earlier. The
  count says how far the cascade got; only the first line says what happened.
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

**`MANAGE_EXTERNAL_STORAGE` is a uid op, and taking it away needs `--uid`.**
`appops set <pkg> ... deny` writes a package line that
`Environment.isExternalStorageManager()` ignores once the uid has been allowed —
`appops get` shows both, the uid one first, and that is the one that counts.
So a test meant to run without the permission ran with it and skipped instead,
twice. Grant with either; revoke with `appops set --uid <pkg>
MANAGE_EXTERNAL_STORAGE deny`, and read the *uid* line back. The package has to
be installed for either: `appops` on an absent one says *No UID for …*, which is
what a previous test run left behind.

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

`ui-type.py` asks the view hierarchy where each key is, by the name the Spectrum
gives it, so it types on any of the four keyboards and nothing has to be kept in
step when one is redrawn. Neither script can type on the **Android keyboard**
skin - that is the phone's own input method, and on an AVD Gboard hides its keys
because the emulator reports a hardware keyboard.
`adb shell settings put secure show_ime_with_hard_keyboard 1` brings them back. **After switching skins
in a running app, `ui-tap.py` still reports the old skin's key names** — UI
Automator caches the window's tree and nothing the app sends clears it. Relaunch
before driving the keyboard.

Both address things by name, never by coordinate — menus grow, the keyboard is
one picture whose keys are accessibility nodes, and the quick bar is icons whose
only name is their content description (`ui-tap.py` matches that too). ☰ has
**pages**, so a path is several names deep: `"Machine" "Change machine"
"Scorpion"`. The quick bar fades after three seconds, so tap the picture first
to bring it back.

**Test on the hardware when there is any.** Both scripts pick a real phone or
tablet over an emulator when both are attached, and `ANDROID_SERIAL` overrides
them. Prefer it for anything about layout: the two bugs this cost most —
the key buttons vanishing, and a joystick too low to hold — were a tablet's
geometry and dpi, and an emulator agreed with the wrong answer every time.
`adb` itself does not pick, so a bare `adb shell` with both attached fails with
*more than one device*; pass `-s`.

**Check what is in front of the app before believing a measurement.** A settings
page or a file picker taking focus turns "the buttons are gone" into a reading
of the wrong screen — that happened three times in one session. Assert
`ResumedActivity` is `EmulatorActivity`, or screenshot, before trusting a count.

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
