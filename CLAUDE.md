# Zedex — working notes

A ZX Spectrum emulator for Android, built on an unmodified Fuse core. `README.md` is for people using the app and nothing else — keep build, test and release material out of it. `docs/DEVELOPING.md` covers building, driving it from adb, the tests and releases; `docs/INTERNALS.md` how the core is wired in. This file is the operational knowledge that is easy to get wrong and expensive to rediscover.

## Hard rules

- **Never modify `vendor/`.** The Android backend is swapped in at link time (`--with-fb`, skip `ui/fb`, link `native/ui/android`, weaken two symbols with `llvm-objcopy`) — reaching for a patch usually means looking in the wrong layer.
- **A Fuse change is a patch in git, never an edit in place.** The build compiles a copy with `native/patches/*.patch` applied (`scripts/fuse-src.sh`, in `build-native/src`, a git repo tagged `upstream` at the pristine tarball) — `fuse-src.sh save` turns commits into the series, `reset` proves a fresh clone gets the same tree. A build tree remembers the source dir it was configured against, so pointing it at the patched tree while still configured for `vendor/` links a library with none of the patches, silently; the baseline is the tarball, not `vendor/`, since Fuse's codegen writes `settings.c` into the source dir. See *Patching Fuse* in `docs/DEVELOPING.md`.
- **The upstream version is pinned and stays pinned** (`FUSE_VER`, `LIBSPECTRUM_VER` in `scripts/build-native.sh`, SHA-256 checked). Bumping needs the version, the hash, *and* `build-native.sh clean` — `fetch` skips a version already extracted, so a bumped number over an old `vendor/` builds the old code and says nothing.
- **The debug build is a package of its own**, `dev.ldlab.zedex.debug` — `appops`/`run-as`/`am start` need the right one. Fuse finds its data relative to `argv[0]` now, so the `PKG` baked into `FUSEDATADIR` is only a fallback nothing reaches.
- **Only swallow a key Fuse can use.** Consuming the volume keys so Fuse could ignore them is how the phone's volume buttons stopped working; `FuseNative.mapsKey()` checks Fuse's own keysym table and passes unknowns to `super`.
- **The version lives only in `version.properties`.** `versionCode` is `major*10000 + minor*100 + patch`; the release workflow checks the tag against it, so `git tag v1.2.3` on `version=1.2.2` fails before it builds.
- **`app/debug.keystore` is committed on purpose** — Gradle's own debug key is per machine, so no CI build could update a local one, or another CI build's.
- **`targetSdk` is Play's floor, and it moves** (36 since August 2027; 35 before that). It is independent of `minSdk`, which stays at 30 — the target says which behaviours the app has been tested against, not who may install it, and Android 11 and 12 are still supported. Targeting 35+ needs **16 KB page aligned** native libs (`-Wl,-z,max-page-size=16384`): the NDK's CMake toolchain sets it, but a hand-driven cross-compile gets the 4 KB default, which runs everywhere and is unmappable on a 16 KB device — the script asserts `0x4000`. Bumping `targetSdk` means re-running the native build, not just Gradle. Targeting 36 additionally turns **predictive back on by default**, so an activity that handled `KEYCODE_BACK` in `onKeyDown` silently stops being asked — both that claim it register a dispatcher callback from API 33 and keep the old path for 30–32 — and makes Android **ignore orientation, resizability and aspect-ratio requests on displays 600dp and wider**, which costs nothing here because the app asks for none of them.
- **Everything of ours stays inside the safe area.** API 35 lays a window into the display cutout unconditionally (letterbox mode reads `ALWAYS`), and hiding the system bars does not help — a hidden bar reports a zero inset but `displayCutout()` does not.
  - `EmulatorLayout` keeps a `safe` rect: `arrange()` gets the window minus it, `placeChild` adds the offset back — no other code needs to know a cutout exists.
  - Everything else calls `SafeArea.fit()` on `android.R.id.content`.
  - Test with a cutout — AVDs have none: `adb shell cmd overlay enable --user 0 com.android.internal.display.cutout.emulation.hole`, then reboot; `dumpsys window | grep DisplayCutout` should show a top inset.
- **The debug build's data folder must not be the release build's** (`R.string.data_folder`: `Zedex` vs `Zedex-debug`). Separate uids under scoped storage mean the second install finds the shared name empty and unwritable — symptom is *ROMs needed* with nothing in the log to say why. No space in the name either, or an unquoted `adb shell ls` silently lists the other build's folder instead and looks like it worked.
- **The overlay keyboard gives way sideways, never downwards** — sideways there is room *between* the joystick and fire, never beneath them, so it keeps full height (1095x390, measured); clamping height instead (the old behaviour) shrank a full 128K plate to 295px against the real 454. Upright the controls sit under the picture, so height yields there instead.
- **A preference's type is whatever wrote it, and the wrong getter throws.** `joystickType` is `putInt`; `getString` on it throws only when the key is *present*, so it passes every fresh-install test and crashes on the first device where the setting's been touched — how 1.1.0 shipped a crash in the bug reporter. Check the writer before reading; run `scripts/check-prefs.py`.
- **New code that reads existing state must be tested against *used* state.** A fresh install has no prefs, states, recents, or non-default keyboard, so it only exercises the "absent" branches.
- **A bug report is shown before it is sent, and that is the whole design.** `Diagnostics.report()` is built on request, `Feedback` edits it, the user's own mail app sends it — nothing gathered in the background, no server (`docs/PRIVACY.md` says so). `DiagnosticsTest` checks for no identifier and no email address.
- **`ACTION_SENDTO` needs a `<queries>` block or it resolves to nothing.** Android 11 hides other apps from one that has not declared what it looks for, even from the chooser — Send found no mail app on a phone with three. `Crashes` writes `files/crash.txt`; `adb shell am crash <package>` tests the offer without waiting for a real one.
- **Never stop publishing what a shipped client fetches.** 1.1.0 asks for `/releases/latest/download/latest.json`; switching to the redirect without still publishing that file 404s every 1.1.0 install for ever with no way out. Keep old endpoints answering until download counts show nobody's on the old one.
- **A settings-page permission has no `onActivityResult`.** Only `onResume` can notice the answer, or the update stalls after the grant until a restart — `Updater.resumeIfAllowed` handles it.
- **The update check reads a release asset, not the releases API.** `api.github.com` allows sixty unauthenticated requests/hour *per IP*, and a carrier NAT is one address for many phones. The newest version comes from the redirect on `/releases/latest`; the counted file is the `.sha256` beside the APK, the project's only anonymous usage measure — which ties the app to the workflow's asset naming (`Zedex-<version>.apk`). Disclosed in `docs/PRIVACY.md` and the README.
- **The Play build cannot update itself, and must not look as though it could.** The updater exists twice — `src/sideload/java` (debug, release) and a `src/noupdate/java` stub (play) — and the Play manifest strips `REQUEST_INSTALL_PACKAGES` too. CI checks all three against the Play **bundle**: no `MANAGE_EXTERNAL_STORAGE`, no `REQUEST_INSTALL_PACKAGES`, and no `github.com/dimitriuz/zedex/releases` in any dex. That last is the literal to grep for — `releases/latest` is never one (`Updater` builds the path at runtime), and the class name is in both bundles since the stub is also called `Updater`; measured, the base URL is in the debug dex three times and the Play bundle's not at all. `assemblePlay` is still never run in CI — only `bundlePlay` — so the `app-play.apk` the by-hand commands in `docs/DEVELOPING.md` name is not among its artifacts. Holding the permission is not permission to install: the user must still allow the source in a page of Android's own, checked before the download.
- **The Play build has no All files access, and that is the whole point of it.** `assemblePlay`/`bundlePlay` strip `MANAGE_EXTERNAL_STORAGE` with `tools:node="remove"` in `app/src/play/AndroidManifest.xml` — Play judges the manifest, not the use. A build **type**, not a flavour, since flavours rename `assembleDebug`/`app-release.apk`, which everything else names. `Storage.canAskForAnyFolder()` checks the manifest so nothing offers a permission the build does not declare.
- **What scoped storage allows, measured, not assumed** (API 36, permission denied):
  - `mkdirs` on `/storage/emulated/0/Zedex` returns **false**, ENOENT — no root-level folder without the permission; `Documents/Zedex` can, plain path (`Storage.documentsRoot()`).
  - A user-made root folder: `canRead()` true, `canWrite()` false, `list()` empty, and `mkdirs` of a subfolder returns **true** while writing into it gives EPERM — why `Storage.isWritable()` probes with a write, not `mkdirs`.
  - A file **anyone else** wrote into the app's folder is invisible (`exists()`/`length()` correct, `list()` omits it, opening gives EACCES) — files must come through the picker, not be copied in by hand.
  - **`exists()` is not "already there".** A file an uninstalled package left exists and reports the right length but cannot be opened (MediaProvider drops ownership; reinstall gets a new uid) — `installRoms` read that as "the user's own, leave it" and skipped all twenty-nine ROMs. Test is `canRead() && length() > 0` (`Storage.romsDirectory`).
  - Images under `Documents` are **not** in `MediaStore.Images` — captures go to `Pictures/Zedex` instead (`Storage.capturesDirectory()`).
  - A public collection refuses a mismatched type: `Pictures` takes png, gif and mp4, `Movies` takes mp4 but **EPERM**s png/gif, neither takes `.tap` — all captures go to `Pictures` (splitting mp4 to `Movies` breaks the default GIF recording).
  - **A folder cannot be tested by writing a file into it** — `.zedex` has no extension `Pictures` recognises and is refused even where the folder works. Probe with `mkdir` instead; `WritableTest` skips itself once All files access is granted.
  - **The probe's name must be new each time** — MediaProvider keeps a row per path that can outlive the file, so `createNewFile` refuses a "taken" name `exists()` calls absent. The probe carries a `nanoTime` now.
  - **Read the whole stack before believing which folder was refused** — "cannot write to `Documents/Zedex`" was `moveData` asking about `Pictures/Zedex` two frames down (`Storage.refused()` logs folder and why).
  - Writing the file is not enough for the gallery — `MediaScannerConnection.scanFile` is needed (`Capture.announce()` calls it from the write-complete callback).
- **`ui_statusbar_update` is ours too** — `ui/widget/widget.c`'s stub discards it, so it is weakened like `ui_error_specific`; `native/ui/android/android_status.c` keeps it for the activity lamps.
- **Fuse's core is single threaded.** Everything from the UI thread goes through the command queue in `native/ui/android/android_bridge.c`, drained on the emulation thread from `ui_event()` — never call into Fuse from Java directly.
- **Nothing on screen may change its `contentDescription` continuously** — each change is a window-content-changed event, so the accessibility tree never settles and UI Automator fails with *the ☰ button never appeared* (the activity lamps did this and took the whole suite down).
- **A second `FileProvider` needs a class of its own** — two `<provider>` entries naming `androidx.core.content.FileProvider` collapse onto one instance and refuse every URI on the other authority. `library/meta/EsdeManuals` exists for this reason alone.
- **`FLAG_GRANT_READ_URI_PERMISSION` alone does not let another app read our `content://`** — grant explicitly to every activity `queryIntentActivities` returns, before starting it; that query only answers thanks to a `<queries>` entry, so the two are load-bearing together.
- **ES-DE's two files are its syntax, not ours, and one replaces rather than merges.** `frontend/EsDe.java` writes `ES-DE/custom_systems/es_find_rules.xml` and `.../es_systems.xml` (Android syntax differs from desktop, per ES-DE's own INSTALL.md).
  - A `<system>` in `custom_systems` **replaces** the bundled one of the same name, commands and all — the `zxspectrum` entry carries ES-DE's own Fuse and Speccy commands too, or the user loses those emulators.
  - Nothing is overwritten: both files are parsed, ours added if absent, and written back — a second run changes nothing, and user edits stay.
  - `<queries>` again — `getPackageInfo` on an undeclared package throws `NameNotFound` as though ES-DE were absent; both its package names are declared.
  - `%ROMPROVIDER%` needs no permission or folder — ES-DE's read grant on launch is not persistable, so the game sits in *Open recent…* until `Recents.forget` drops it.
  - Two ways in: with All files access, ordinary files; without it, `ACTION_OPEN_DOCUMENT_TREE` + resolver writes (`EsDe.Place` is the seam, grant persisted under `esdeTree`) — the second is why this works on Play.
    - Do not declare `MANAGE_EXTERNAL_STORAGE` in the Play build for this — Play's permitted uses exclude an emulator, and this feature proves SAF already serves.
    - `openOutputStream(uri, "wt")`, not `"w"` — without truncate, a shorter document over a longer one keeps the old tail and parses as neither.
    - Check the folder is ES-DE's before writing — a wrong folder takes both files happily and ES-DE never reads them, looking exactly like success (`looksLikeEsDe` checks the name or its folders).
    - `EXTRA_INITIAL_URI` opens the picker inside `/storage/emulated/0/ES-DE`, one tap. Verified on an emulator.
  - The data folder cannot use this trick — Fuse opens ROMs/states/disks by stdio path, and a tree grant confers no path access outside app dirs and public collections, hence `Documents/Zedex` stays the default.
  - Test against a real ES-DE, reading `ES-DE/logs/es_log.txt` — it confirms the `%ROMPROVIDER%` expansion. `adb shell input keyevent KEYCODE_ENTER` walks into a system and launches a game without touching the screen.
- **A new string is nine files, an activity is one line.** Eight languages follow `values/strings.xml`; `scripts/check-strings.py` fails on an unknown key or a **disagreeing format specifier** (`%1$s` vs `%1$d` is a `ClassCastException` only a non-English reader ever sees) — a *missing* key is counted, not failed, since a translation may be in progress. A `<plurals>` is checked too, but not form for form: which forms a language has is the language's business (Russian and Polish need `few` and `many`, English has only `one` and `other`), so what is enforced is that every form takes the same arguments as English, that `other` exists, and that a name is a plural in every language or none. `*_values` arrays are Fuse's own words, compared with `strcmp` — never translate them.
  - Every activity needs `attachBaseContext` — resources come from the context it was built on, or a new screen is stuck in the phone's language.
  - `android:label` is resolved in the phone's language, not the app's — the four screens with a title set it in `onCreate` instead.
  - The emulator screen is not recreated for a locale change (`configChanges` includes `locale`) — `onResume` compares the tag and calls `recreate()` itself.
  - One mechanism, not two: Android 13's per-app language is not used (it does not exist on 11/12, which `minSdk 30` supports). The preference is the only truth, and an empty one follows the system locale — test with `adb shell cmd locale set-app-locales dev.ldlab.zedex.debug --locales pl-PL` (SELinux blocks setting `persist.sys.locale` directly on an emulator).
- **A stale working tree is the one way this can be silently wrong.** `fuse-src.sh ensure` used to only check the tree *existed*, so a cached `build-native/` in CI restored Fuse from before a patch and never applied it — nothing rebuilt, since the objects were cached too, and `android_bridge.c` (always recompiled) failed calling a function only the missing patch declared. `ensure` now compares the tree's commits against `native/patches` and remakes it when the series has moved on.
- **`settings.c`/`.h` are generated, and only the source tree's copy counts** — a quoted `#include` from the source tree wins over any `-I`, so a regenerated build-tree copy loses and the compile fails on a struct member demonstrably there. After editing `settings.dat`, run `scripts/fuse-src.sh regen`. Test the cold start too — only a fresh launch's command line goes through `settings.c`.
- **Turbo is not just the speed setting.** 7MHz doubles tstates in a frame still a fiftieth of a second, so `machine_timings` is multiplied as a whole — but the AY keeps its own 1.75MHz clock (`AY_CLOCK_RATIO * machine_turbo_factor()`, or tunes play an octave up), `ULA_CONTENTION_SIZE` must cover the doubled frame (143360 vs 80000, unbounded index), and the contention array must be refilled on frame-length change. Timings change only at **end of frame** (`machine_set_turbo()`, from the command drain).
- **A setting has to be applied as well as stored** — either `SettingsActivity`'s `onSharedPreferenceChanged` pushes it into Fuse live, or `EmulatorActivity.onResume` re-reads it; otherwise it does nothing until next launch (the keyboard type bug looked broken but was just late).
- **In the direction a list scrolls, `MATCH_PARENT` becomes `UNSPECIFIED`** (`RecyclerView.LayoutManager.getChildMeasureSpec`) — an `ImageView` with no bitmap measures zero and `LinearLayoutManager` fills the viewport for ever: 1.9 GB and 663 threads, killed by the kernel. Give pages the pager's own measured width explicitly.
- **`PagerSnapHelper` corrects a misaligned rest a frame *after* the `IDLE` you read** — its correction (`smoothScrollBy`) lands next frame while the state still reads `IDLE`, so acting on it lights the dot for a page already being left. Check the page is flush first.
- **Never mutate a `RecyclerView` from a layout-change listener** — it runs during layout, and `setLayoutManager` there left the library's grid empty on cold start. `post(...)` it instead.
- **A `Presentation` draws above every activity window on its display, including *another app's*** — the panel's step-aside counts our own activities via lifecycle callbacks, so a third-party viewer launched onto that display renders invisibly underneath. `onTopResumedActivityChanged` is the signal to come back; the host activity stays `RESUMED` throughout, and `onPause` never fires.
- **`ES-DE/settings/es_settings.xml` is not well-formed XML** — a declaration and ~174 sibling elements with no root, so a plain parse throws and every scraped picture silently disappears. Wrap the content in a synthetic root; an unreadable file falls back to ES-DE's default media folder.
- **ES-DE's `manuals` folder holds PDFs, not images** — not another picture folder.
- **One predicate must not answer two questions.** `startsInLibrary` gated both "where does the app start" and "is there a library at all" — turning the setting off removed the only way back in, and the activity's own gate asked the same question again even after the menu row was fixed.
- **The library's metadata store is `library/metadata.json`, and is ours.** It was ES-DE's `gamelist.xml` with `zedex*` elements bolted on, which stopped scaling the moment a provider offered fields their schema has no room for — do not put another one in there. Reading *ES-DE's* gamelist is a separate job and still XML (`EsdeLink`). The change also retired a whole failure mode: one scraped description ending in U+0001 was unrepresentable in XML 1.0, so the file this app wrote could not be read back and 803 games silently became "never linked".
- **`Meta` is built, never constructed positionally.** Twelve fields and growing; the old constructor dropped one silently (`Scrape.owned` lost the key map, nothing failed or logged). Use `Meta.at(path)` and `meta.but()`.
- **`keymap` and `inputs` are different facts from different services.** ScreenScraper's `sp2kcfg` is which key each pad button sends; ZXInfo's `controls` is which devices the game listens to. One lays out the buttons, the other picks the interface.
- **Every ScreenScraper medium is a request; every ZXInfo medium is free.** A cover there is a `mediaJeu.php` call against the day's allowance, and here a static file on `spectrumcomputing.co.uk`. That is why cost lives on `Provider.costPerGame` and not on `Wanted` — the same 200 games are ~800 requests against one and ~400 against the other.
- **ScreenScraper does not refuse when you are over quota.** Forcing the counter to 100000 against an allowance of 10000 still answered 200 with a real candidate, so `Sweep` paces on the counters in every reply rather than waiting to be told. Unknown quota does not stop a run — refusing to try on a guess is worse than one refused request.
- **ZXInfo blocks an address that arrives without a `User-Agent`**, at the network layer, and it took an email to lift — *“you have been jailed because of bad requests… there’s no hard limit, it’s all based on behaviour patterns”*. DNS still resolves and port 443 is actively refused, which from one vantage point is indistinguishable from the host being down. Their spec asks clients to identify themselves and says access without it risks being treated as a crawler. `Http.Real` sends `Zedex/<version> (+url)` — the version from the package manager, which is why it takes a `Context` and why there is no second constructor that could go out without one — and `ZxInfo` paces itself at 500ms because there is no quota to pace against. Neither is optional, and a bare `curl` against that host is how the address was lost in the first place.
- **`PENTAGON` is a sibling of `ZXSPECTRUM` in ZXInfo's scheme, not a variant of it.** Filtering a search to Spectrum silently excludes the Pentagon demoscene, which is most of what arrives as `.trd` and `.scl`. Scraping identifies a file somebody already has, so it filters by *nothing* — every filter can only lose the right answer.
- **The scraper credentials are in the APK and cannot be hidden there.** `aapt2 dump resources` prints them with no decompiler; R8 leaves resource values alone, a constant stays in the dex, a `.so` stays in `strings`, and encrypting ships the key. They are sealed (`Secrets`) only so they are not *greppable*, and the real mitigation is the optional per-user account. Do not reopen this.
- **`FuseNative.machineIds()` is empty until Fuse is running; `joystickTypeNames()` is not.** A test that reads the first and skips on empty reports "OK" having asserted nothing — which is how `SuggestedContractTest` passed while a mapping pointed at an id Fuse does not have. Launch the emulator first, then *assert* the array rather than assuming it.
- **Fuse has no joystick called the keyboard.** Its eight are None, Cursor, Kempston, Sinclair 1/2, Timex 1/2, Fuller; the pad's keyboard mode is *ours*, `Controls.JOYSTICK_KEYBOARD` (1000), appended after Fuse's list everywhere somebody picks one. Looking it up by name in `joystickTypeNames()` finds nothing and never will — how the setup dialog shipped with the option missing while the code that built it read correctly. Choose an interface through `ControlsUi.chooseJoystickType`: the setting, Fuse and the pad's layout are three things, and it is all three or none.
- **The metadata store does not read itself.** `Metadata.forPath` answers out of a cache that only `ensureLoaded`/`refresh` fills, and neither may run on the UI thread — so a game opened without the library having run first in that process finds an empty store and reads as unscraped, silently. `clear()` leaves the cache non-null and empty, so an `ensureLoaded` after it does nothing.
- **Every way of opening a game meets at `Media.Host.opened`** — a file manager's hand-over, the library, the picker, *Open recent…*. A hook hung on `ACTION_VIEW` covers one of the four, and `EmulatorActivity.gameOpened` is where the other three arrive too.

## Building

Native first, then Gradle. Gradle only packages the prebuilt `.so`, so **changing C code and running `adb install` without `./gradlew assembleDebug` in between installs the old library**.

```sh
./scripts/build-native.sh                 # both ABIs, ~90s cold
ABIS=x86_64 ./scripts/build-native.sh     # one ABI while iterating
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`JAVA_HOME` is needed whenever the default JDK is newer than 21.

`app/src/main/assets/fuse/` is staged by the same script and is not in git — nothing in the app reads it any more, so a tree without it still builds and runs; `./scripts/build-native.sh` puts it back.

## Tests

`app/src/androidTest` — UI Automator. Run one class while iterating; the whole suite only for refactors or large features.

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=dev.ldlab.zedex.CaptureTest
```

Things learned the hard way, all recorded in the tests' own comments:

- **Run instrumentation on an emulator, not the tablet** — MIUI refuses Gradle's install with `INSTALL_FAILED_USER_RESTRICTED` unless *Install via USB* is on, and `connectedAndroidTest` **uninstalls first**, leaving no app and no way back without touching the phone. `adb install -r` + `am instrument` avoids the uninstall.
- **`Emulator.useDataFolder()` wants ROMs in `/sdcard/Download/Spectrum/roms`** — without them every test in the class fails before it can be skipped. `adb push roms/*.rom` puts it back.
- **A test sets the world it needs; it must not inherit the bench's** — the machine, the second screen, the orientation, the **display it comes up on**, and every setting it depends on. A bench left configured for the last thing worked on by hand is the normal case, not the exception.
  - **The display has to be said, and then checked.** An AVD with a second display hands the activity to whichever one last had focus, and the task stays there across launches; screenshots and taps always mean the default display, so `borderColour()` reads the other screen's launcher — white, where green was wanted. Nothing earlier can catch it: the accessibility tree spans every display, so the keyboard is found wherever it is and the launch reads as a success. `Emulator.launch()` pins it with `ActivityOptions.setLaunchDisplayId` and asserts it landed. This failed on `main` too — latent in the harness, exposed by a bench with two displays.
  - The same shape from the other side: `secondScreen` left on cost ten of thirty-eight, all reading *nothing on screen says Controls* — the panel is a `Presentation` on display 9 and UI Automator taps display 0, so the taps landed nowhere near the app.
  - **Wait for the condition, never for a duration.** A filter does not land when its sheet closes: `load()` walks the whole content tree through the documents provider off the UI thread, and how long that takes depends on the tree, the provider's caches and what else the device has been doing. `FilterTest` sampled the row count after a 400ms sleep — it passed alone and failed behind three emulator classes, reading the pre-filter count and reporting it as a filter that changed nothing. `awaitRowCount` polls until the count says something the caller will believe, and the caller's own assertion still reports the failure.
  - **A skip must turn on a fact, never on a wait.** `NewDiskTest` decided "the ROMs are missing" from what the screen said three seconds after a machine change, so a machine that had not finished changing skipped the whole test — silently, in twenty seconds, having formatted nothing and reported OK. It skipped and ran alternately on identical code. The skip now asks whether the ROM file exists, which no timing changes, and the interface appearing is an `assertTrue`. A real run takes about four minutes; anything near twenty seconds did nothing.
  - **A picker left open by hand outlives `am force-stop`.** It belongs to another package, so stopping the app does not touch it, and the next launch comes up behind it — after which every reading is of the wrong screen and the symptom is a key that is not on the keyboard. Cost an hour of bisecting builds for a regression that was not there. `Screen.assertHere()` now fails when nothing of ours is resumed and says what to do: `adb shell am force-stop com.google.android.documentsui`.
  - **A killed run leaves its fixtures behind, and MediaStore does not overwrite.** Asked for a name that is taken it makes `uitest-recent (1).tap`, so the next run fails on what reads like a naming bug in the app. Clear leftovers on the way *in* — `RecentsTest.dropAnyLeftOver` — since a run stopped by hand never reaches its `@After`.
  - Restore whatever you turn off afterwards — it is the user's device, and the setting they left on is the one they were using.
- **Sample the picture, not a view's bounds** — `borderColour()` computes the 4:3 quad the way the renderer does; the window's corner is wrong once the bar takes a strip, the `SurfaceView`'s bounds are wrong sideways.
- **Ask for portrait, not for natural** — on a tablet natural *is* landscape.
- Instrumentation runs **inside the app's process**, so `am force-stop` kills the test with it.
- **A run uninstalls the app afterwards**, wiping prefs and storage permission — the next launch forgets the data folder. See *Device setup*.
- `UiObject2.longClick()` holds the platform long-press timeout, the same 400ms the keyboard latches at — hold with a zero-length swipe instead.
- **A fresh install comes up on the first-run panel, not the machine** — it sits over the quick bar, so every menu test fails with *the ☰ button never appeared* (fifteen of thirty-four did once); since Gradle uninstalls first, **every** run starts this way. `Emulator.launch()` answers with `R.string.setup_start`, not English, since the app has languages now.
- **Read the *first* failure, not the count** — one flake cascades into every later class failing the same way.
- **☰ fades out after three seconds** — `Emulator.menu()` taps the picture to reveal it every time, first.
- **A BASIC program belongs on a tape, not the keyboard** — `TapeProgram` writes a real `.tap`; typing costs 150ms a character. Exception: a machine not at a BASIC prompt, where autoloading types `LOAD ""` itself.
- The emulated screen cannot be read, but a **screenshot of the device** can — have the test program report what it saw in the border colour (`Emulator.borderColour()`).
- **Do not drive the picker; make a document instead** — a `MediaStore` `content://` URI plus `ACTION_VIEW` takes the same path a file manager's hand-over does, the only way to reach `Media.stage()`. `Emulator.open()` calls Fuse directly and misses it.
- **UI Automator switches accessibility on**, so anything guarded by `AccessibilityManager.isEnabled()` behaves differently under test — a crash on latching a shift once shipped for exactly this reason.
- **At a BASIC prompt the first key of a line is a keyword** — `B` gives `BORDER`; typing the six letters gives `BORDER ORDER` and a syntax error.
- **Poke above RAMTOP** — the printer buffer at 23296 is only free on a 48K; on a 128 the byte is gone before the next `PEEK` sees it. `CLEAR` down to 32767 and use 32768.
- **`By.desc` is an exact match** — card buttons are described `Rename "Tujad"`, quotes and all, so `find()` uses `descContains`.
- **A row that was a dialog is a page now** — the button commits by its own name (*Save as…*, *Delete*), and there is no OK.

## Device setup, after a test run

```sh
# ...and dev.ldlab.zedex.debug for a build straight off the bench.
adb shell appops set dev.ldlab.zedex MANAGE_EXTERNAL_STORAGE allow
adb shell "run-as dev.ldlab.zedex mkdir -p shared_prefs"
# shared_prefs/fuse.xml:
#   <string name="statesRoot">/storage/emulated/0/Download/Spectrum</string>
```

Use the canonical `/storage/emulated/0/...`; `/sdcard` is a symlink and string comparisons against `getExternalStorageDirectory()` will not match it.

**`MANAGE_EXTERNAL_STORAGE` is a uid op, and taking it away needs `--uid`.** `appops set <pkg> ... deny` writes a package line that `Environment.isExternalStorageManager()` ignores once the uid has been allowed — `appops get` shows both, the uid one first, and that is the one that counts. Revoke with `appops set --uid <pkg> MANAGE_EXTERNAL_STORAGE deny`. The package must be installed for either: `appops` on an absent one says *No UID for …*.

## Driving the app from a terminal

A plain `adb shell input keyevent KEYCODE_A` reaches the emulator and types an `a` — but nothing shifted, no keyword, and `input text` does nothing at all. Two helpers:

```sh
scripts/ui-tap.py list                    # what is on screen
scripts/ui-tap.py "Media" "Beta Disk A" "Save…"
scripts/ui-type.py 'randomize usr 15616' ENTER
scripts/ui-type.py CS+SS SS+0 ' ' '"test"' ENTER   # extended mode: FORMAT
```

`ui-type.py` asks the view hierarchy where each key is, by the name the Spectrum gives it, so it types on any of the four keyboards. Neither script can type on the **Android keyboard** skin — the phone's own IME, which Gboard hides on an AVD that reports a hardware keyboard; `adb shell settings put secure show_ime_with_hard_keyboard 1` brings them back. **After switching skins, `ui-tap.py` still reports the old skin's key names** — UI Automator caches the window's tree. Relaunch first.

Both address things by name, never by coordinate. ☰ has **pages**, so a path is several names deep: `"Machine" "Change machine" "Scorpion"`. The quick bar fades after three seconds, so tap the picture first to bring it back.

**Test on the hardware when there is any** — both scripts pick a real phone or tablet over an emulator (`ANDROID_SERIAL` overrides); prefer it for layout, since the two costliest bugs (keys vanishing, a joystick too low to hold) were a tablet's geometry and dpi that an emulator agreed the wrong way about. Pass `-s`, since a bare `adb shell` with both attached fails with *more than one device*.

**Check what is in front of the app before believing a measurement** — a settings page or file picker taking focus turns "the buttons are gone" into a reading of the wrong screen. Assert `ResumedActivity` is `EmulatorActivity`, or screenshot, before trusting a count.

**Testing a second display:** `screencap -d` wants the **SurfaceFlinger** id from `adb shell dumpsys SurfaceFlinger --display-id`, not the Android display id (it changes on reconfiguration); `adb shell input -d <android id> tap X Y` uses the **Android** id; `am start --display 0 …` pins an activity to the main screen or it opens on the panel instead.

**`ui-tap.py` caches the view tree across an orientation change** and hands back stale coordinates, which reads as an app bug — tap from a fresh screenshot when something looks wrong.

**Do not drive the device from two places at once** — two agents, or an agent and a person, tapping the same emulator make each other's results untrustworthy.

## Where things live

`FuseNative` and `EmulatorActivity` stay in `dev.ldlab.zedex` and cannot move: the native side exports 71 `Java_dev_ldlab_zedex_FuseNative_*` symbols — 57 in `android_bridge.c`, 12 in `android_state.c`, 2 in `android_window.c` — and does a `FindClass` on that path, and the activity is addressed as `dev.ldlab.zedex/.EmulatorActivity` by `am start` in the scripts and docs. Everything else is in a layer — `machine`, `input`, `storage`, `cheats`, `media`, `view`, `menu`, `screen`; see *How the code is laid out* in `docs/INTERNALS.md`. Adding an activity means the manifest gets `.screen.Name`.

A member another layer needs has to be `public`; package-private stops at the boundary.

## Refactoring this codebase

- **Build collaborators in `onCreate`, never as field initialisers** — those run first and are handed a null `preferences`. It compiles.
- **A `Host` interface wider than about four methods means the seam is wrong** — "Move the menus out" needed fifteen, so the menus stayed; pass a real collaborator instead (`ControlsUi` holds `EmulatorLayout`).
- **Extract first, move into packages after** — every cross-package reference has to become `public`; pay for it once.
- **Never script the `public` widening by indentation** — eight spaces is a method body at a top-level class and a member inside a nested type. Let the compiler name what is invisible; guessing cost an hour and a `git reset --hard`.
- **When a class leaves, read what it left behind** — comments do not move with the code; eleven were lying after this refactor, the worst a fifteen-line explanation stranded above an unrelated constant.

## Conventions

- Commit subjects take a conventional prefix: `feat:`, `fix:`, `docs:`, `chore:`, `test:`, `refactor:`. Body explains *why*.
- Verify features by running them on a device and say what was actually checked. Where something is unverified, the README says so.
