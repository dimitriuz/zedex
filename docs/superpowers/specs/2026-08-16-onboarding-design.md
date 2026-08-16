# Onboarding: a first run that asks, and an app that explains itself

**Date:** 2026-08-16
**Status:** approved, not yet implemented

## The problem

Two problems, and the second one is a bug.

**The app never introduces itself.** The first start asks two questions — where
to keep things, where to open things from — and then hands over a Spectrum. The
quick bar fades after three seconds and never says it will come back if the
picture is tapped. The library has four tabs and a detail pane nobody is told
about. The archive is forty thousand entries behind a tab whose name does not
say what is in it. Every one of those is discoverable by poking, and most people
do not poke.

**And the two questions are not being asked either.** `StartPanel.setupNeeded`
decides whether this is a first run with

```java
if (preferences.getBoolean(Storage.KEY_SETUP_DONE, false)) return false;
return preferences.getAll().isEmpty();
```

`LibraryActivity` is the launcher. Its `onCreate` calls
`SettingsActivity.migrateIfNeeded` — which writes `libraryMigrated=true`
unconditionally — and *then* hands over to `EmulatorActivity`, which asks
`setupNeeded`. By that point the preferences file is not empty, so the answer is
always no and the folders panel never appears on a fresh install. Supporting
evidence on the bench: `fuse.xml` holds `libraryMigrated` and has no `setupDone`
in it at all.

`SettingsActivity` had already learned this lesson for its own migration and
wrote it down — "a fresh install writes several of its own within moments of
starting … so by the time anything calls this, an empty preferences file is not
a thing that reliably still exists to test" — and `setupNeeded` is the caller
that did not get the memo.

## What this builds

1. **A guided first run** — eight pages, every one skippable, covering language,
   folders, machine, controls, screen, library, and scraping.
2. **A guide** — coach marks on the real controls, fired the first time the
   machine, the library and the archive are actually seen, once each.
3. **The gate fixed** — a first-run question that a launcher writing a
   preference cannot silently answer for it.

Both halves are re-runnable from Settings.

## Decisions taken, and why

**Coach marks on the real UI, not illustrated cards in the wizard.** A card
saying what the library is, shown before the library exists, is a thing to read;
a ring round the actual tab rail the first time it appears is a thing to see.
It also keeps the wizard about settings, which is the only thing a wizard is
good at.

**Everything is skippable, including all of it at once.** This codebase has a
stated position on screens between somebody and the machine they came for —
`StartPanel`'s own note on the demo tape calls one a toll booth — and a
seven-question wizard is exactly that shape. Page one carries *Set it up later*,
every page carries *Skip*, and Back means the same thing as the skip button.

**Skip writes nothing.** A skipped page and a page never reached are the same
state, so the app's own defaults stay in force and nothing has to know how far
the wizard got.

**Per-screen guide flags, not one.** Someone who has had the app for a year gets
the archive's marks the first time they open the archive, and is not handed a
welcome wizard they have no use for. Someone who skipped the wizard still gets
the marks.

## Where it lives

A new **`welcome/`** layer beside `menu`, `view`, `storage`:

| file | what it is |
|---|---|
| `welcome/Step.java` | one page: `title()`, `blurb()`, `body(Context)`, `apply()` |
| `welcome/Steps.java` | the ordered list, built per build and per answer |
| `welcome/Coach.java` | the overlay: scrim, hole, caption, *Next* |
| `welcome/Tour.java` | a named sequence of marks and its preferences flag |

and one activity, `screen/WelcomeActivity`, extending `ZedexActivity` — so it
gets `attachBaseContext`'s language wrapping, the title set in the app's
language rather than the phone's, and `claimBack`. The manifest gets
`.screen.WelcomeActivity`, not exported, `Theme.DeviceDefault` like its
neighbours.

**Called *Welcome* and not *Setup*, deliberately.** `library/Setup` is what
somebody decided about how one game should run; `menu/SetupUi` offers what a
scraped record suggests. Both are per-game. A third `Setup` meaning something
else entirely is how a reader loses an afternoon.

## `StartPanel` splits

`StartPanel`'s javadoc already describes two unrelated jobs under two headings.
They stop sharing a class.

**Moves into the wizard's folders step:** `showSetup`, `describeFolders`,
`finishSetup`, `chooseDataFolder`, `chooseContentFolder`, `asking()`,
`REQUEST_DATA_TREE`, `REQUEST_CONTENT_TREE`, and the `setTakeover` coupling that
only existed to keep the quick bar reachable while the panel covered the screen.

**Stays exactly where it is:** everything about ROMs. "There are no ROMs" is a
thing that can happen at any time — a data folder pointed somewhere they are
not, a folder full of the wrong ones — and not only on day one.

`EmulatorActivity` loses `roms.asking()` from `startEmulator` and the
`StartPanel.setupNeeded` call from `onCreate`.

## The gate

One question, asked by both entry activities, in a place they both already
reach — `Prefs`:

```java
public static boolean welcomeNeeded(Context context, SharedPreferences prefs) {
    if (prefs.getBoolean(Storage.KEY_SETUP_DONE, false)) return false;
    return !isUpdate(context);
}
```

`isUpdate` is the `firstInstallTime != lastUpdateTime` test already written in
`SettingsActivity`, moved here so both callers share one copy —
`migrateLibraryDefault` calls `Prefs.isUpdate` afterwards rather than keeping a
second. Its own javadoc explains why it exists in place of reading the
preferences file, and that reason applies identically to this question. Any
failure to read the package info answers "yes, this is an update", which is the
conservative direction: it declines to interrogate somebody who may have been
playing for a month.

Asked by **both** `LibraryActivity.onCreate` (before its hand-over decision) and
`EmulatorActivity` (before `startEmulator`), because a file manager's
`ACTION_VIEW` reaches the second without the first ever running.

**Its own commit, ahead of the wizard**, with its own test — the bug is live
today, the fix is three lines, and it should be reviewable and backportable
without eight pages of wizard attached to it.

## The eight pages

Every page: a title, one sentence, a body, `[Skip]` and `[Next ›]`.

**`Steps` is asked which page comes next, not built once at the start.** Page 5
depends on whether page 1 chose a content folder, and a list settled before page
1 was answered cannot know. So the wizard holds the answers so far and asks for
the next applicable page each time it advances — which also means Back walks the
pages that were actually shown.

### 0 — Welcome, and the language

The two belong on one page. Choosing a language `recreate()`s the activity back
onto page 0, so the proof that it worked is the page you are standing on.

Ten choices from `@array/language_values` (the empty string is "follow the
system", which is the default and stays the default). Writes `language`.

Carries `[Set it up later]`, which is the same exit as every other way out.

### 1 — Folders

`StartPanel`'s existing implementation, moved: the device's roots, the short one
at the root of storage offered whether or not the permission to make it is held,
`ACTION_OPEN_DOCUMENT_TREE` for the content tree, and the All-files round trip
answered in `onResume` — a settings-page permission has no `onActivityResult`.
`Storage.canAskForAnyFolder` still decides what is offered, so the Play build
shows a shorter list.

Writes `statesRoot` and `contentTree`.

### 2 — Machine

**The one page that cannot read Fuse's own list.** `FuseNative.machineIds()` is
empty until Fuse is running, which is why `SettingsActivity.populateMachines`
disables its row when it comes back empty, and the wizard runs before Fuse
starts by design.

So a curated six, as `{id, name}` pairs in this app's own spelling — the
spelling `Suggested.MACHINES` already uses:

| id | shown as |
|---|---|
| `48` | ZX Spectrum 48K |
| `128` | ZX Spectrum 128K |
| `plus2` | ZX Spectrum +2 |
| `plus2a` | ZX Spectrum +2A |
| `plus3` | ZX Spectrum +3 |
| `pentagon` | Pentagon 128 |

with a line saying the full list is in Settings › Machine. The model names are
literals, not resources: they are product names, like Kempston. The one-line
description under each is a resource.

Writes `machine`, whose default is `"128"` in both `settings_machine.xml` and
`Machine.DEFAULT_MACHINE` — checked, they agree, so a skip is safe.

A device test asserts every one of those six ids is in
`FuseNative.machineIds()` *after launching the emulator*. Asserted and never
skipped on an empty array: a test that reads the array, finds it empty and skips
reports `OK` having asserted nothing, which is how a mapping pointing at an id
Fuse does not have once passed review.

### 3 — Controls

**A real keyboard, drawn live.** `SpectrumKeyboardView` touches `FuseNative`
only on a key *press*; drawing is pure Canvas, which is why `ProfileActivity`
can already show a plate with no machine running. The page holds one, at the
chosen skin, redrawn as the choice changes, with touch disabled so no key is
ever sent to a Fuse that is not there. The `SYSTEM` skin has no keys of its own
to draw, so a note stands in its place.

Joysticks come from `FuseNative.joystickTypeNames()`, which — unlike
`machineIds()` — is populated before Fuse runs, with
`Controls.JOYSTICK_KEYBOARD` (1000) appended after Fuse's eight. Fuse has no
joystick called the keyboard; that mode is ours, and looking it up by name in
Fuse's list finds nothing and never will.

Writes `keyboardSkin` (a string) and `joystickType` (**`putInt`** — the wrong
getter on this key throws only when the key is present, so it passes every
fresh-install test and crashes on the first device where the setting has been
touched).

### 4 — Screen

Four filters (`Filter`: off, scanlines, CRT, both) and three borders (`Border`:
full, slim, none).

**Bundled stills, because a live preview is impossible here.** The filter is a
GL shader in `native/ui/android/android_gl.c` operating on Fuse's framebuffer.
There is no Fuse when the wizard runs, and a Java reimplementation for preview
purposes would be a second copy of the shader that nothing keeps in step with
the first. So: four small PNGs captured from the real emulator with each filter
on, and the border shown by how much of the same picture is drawn.

They are captures rather than drawings, so they cannot flatter the filter into
looking like something it is not. Nothing enforces that they are recaptured when
the shader changes; that is a known and accepted gap, and it is recorded in
`docs/DEVELOPING.md` rather than beside the files — `res/drawable` takes
resources and nothing else, so a README dropped in there is a build failure.

Writes `filter` and `border`.

### 5 — Library and archive

Whether the app opens on the library, and which archive to browse.

Dropped entirely when `Catalogues.any` is false, or when no content folder was
chosen at page 1 — `startsInLibrary` is false without one whatever the switch
says, so offering the switch would be offering a setting that cannot take
effect.

Writes `library` and `catalogueProvider`.

### 6 — Scraping

Sources and order through the existing `ScraperOrder`, then the optional
ScreenScraper account.

**Nothing starts fetching because a wizard was walked.** This page sets up who
would be asked, not a sweep.

The account note says why one of your own is better than the shared one: the
app's own credentials are in the APK and readable by anyone who wants them, so
anyone scraping a whole collection using their own account is what keeps the
shared one carrying casual use. Both fields are kept out of backup and device
transfer already (`backup_rules.xml`).

Writes `scrapers`, `scraperUser`, `scraperPassword`.

### 7 — Done

A ✓ summary of what was answered, and:

- **The intro tape note**, which lives on today's first-run panel and belongs
  here for the same reason: said on the way past rather than asked about.
- **Feedback and issues** → `github.com/dimitriuz/zedex/issues`.
- **The two support links**, `ko-fi` and `buymeacoffee`.

The three URLs move out of `AboutActivity` into `screen/Links` so there is one
copy of each. **`AboutActivity`'s own comment currently says "this screen is the
only place they appear" and stops being true** — it is rewritten to say where
they appear now. A changed policy is fine; a comment that lies about it is not.

Two things that look like landmines and are not, both measured:

- `https` needs no `<queries>` entry. A web intent carries its own exemption
  from package-visibility filtering, which a bare scheme like `mailto` does not.
- CI's Play-bundle check greps for the literal
  `github.com/dimitriuz/zedex/releases`. `/issues` is not that string, and
  `Updater` builds the releases path at runtime, which is why the check works at
  all.

`[Start the machine]` finishes.

## Finishing, whichever way out

Skip-all on page 0, `[Start the machine]` on page 7, and Back **from page 0**
all reach one method, which does what `finishSetup` does today:

```
Storage.pinRoot(activity)        // or a permission granted later moves the folder
Storage.createFolders(activity)
Storage.installRoms(activity)
Storage.installDemo(activity)
setupDone = true
```

None of that depends on how many pages were answered, so there is no exit that
leaves storage half-built.

**And then it decides where to go**, because it is the thing that has just
settled `library` and `contentTree` — the two `startsInLibrary` reads. Two
routes, chosen by an `EXTRA_RETURN` on the launching intent:

- **From `LibraryActivity`'s launcher path** (`EXTRA_RETURN` absent).
  `LibraryActivity` starts the wizard and finishes itself immediately, before
  building anything — it cannot make its own hand-over decision, since the
  answer it depends on is what the wizard is about to write. The wizard then
  starts `LibraryActivity` or `EmulatorActivity` per `startsInLibrary` and
  finishes.
- **From an activity that stays alive** (`EXTRA_RETURN` set): `EmulatorActivity`
  reached by a file manager's `ACTION_VIEW`, and the Settings row. The wizard
  just finishes. `EmulatorActivity` is `singleInstance`, so it is still there
  with its original intent, and its `onResume` runs `startEmulator` again — the
  gate now answers no, and the file it was opened with is opened.

The one case that needs saying out loud: a first-ever launch that is a file
manager's hand-over gets the wizard first and its file afterwards, rather than
having the wizard skipped for it. The alternative — pinning the folders to
defaults quietly so the file opens now — would settle where everything is kept
without asking, which is the exact question the first run exists to put.

**Back walks backwards, and leaves from page 0.** `WelcomeActivity` inherits
`ZedexActivity.claimBack` — which every screen here needs, because the device
that drops the platform's own back invocation drops only that one — and its
callback goes to the previous page shown, or, when there is no previous page, to
the method above rather than a bare `finish()`. So there is no way out of the
wizard that skips the storage work, and no way to leave by accident from the
middle of it.

`pinRoot` writes `statesRoot` even when page 1 was skipped, and that is
deliberate and pre-existing: leaving the default unrecorded would let a
permission granted later silently move where the app looks. It is the one thing
finishing writes that no page was answered for.

## The guide

### `Coach`

One overlay view added to `android.R.id.content`: a scrim, a rounded hole cut at
the target's bounds mapped into the overlay's coordinates, a caption card placed
above or below the hole depending on where there is room, and a *Next*. It
consumes every touch and key event while it is up, so nothing behind it can be
half-pressed and no `GamepadCursor` steals focus mid-mark.

**The caption is set once per mark and never animated.** Nothing on screen may
change its `contentDescription` continuously: each change is a
window-content-changed event, the accessibility tree never settles, and UI
Automator fails with *the ☰ button never appeared*. The activity lamps did this
once and took the whole suite down.

*Next* is a real focusable view with a name, so a screen reader, a gamepad and
`scripts/ui-tap.py` can each reach it.

### `Tour`

A preferences flag plus a list of (target supplier, caption). Targets are
suppliers and not views: the view may not be laid out yet, or may not exist in
this configuration at all.

`arm(activity)` checks the flag, posts so that layout has happened, and
**declines quietly if any target is missing** — leaving the flag unset, so it
fires properly next time rather than ringing empty space.

**The flag is set when the last mark is dismissed or the tour is skipped, never
when it starts.** A tour interrupted by a rotation or a kill has not been given.

### The three tours

| flag | fires | marks |
|---|---|---|
| `guideMachine` | `EmulatorActivity`, once the machine is up | the picture (*tap here any time to bring the bar back*), Files, Controls, ☰ |
| `guideLibrary` | `LibraryActivity.show(Tab.BROWSE)` | the tab rail, the toolbar's search/filter/sort, a game row, the detail pane's Play/ⓘ |
| `guideCatalogue` | `LibraryActivity.show(Tab.CATALOGUE)` | the shelves, the search, the pane's Play/Import/Open |

The picture goes first in the machine's tour because the bar fading after three
seconds with no hint that a tap brings it back is the least discoverable thing
in the app.

**The machine's tour holds the bar up while it runs**, through a one-method hook
on the activity — otherwise the bar fades out from under its own explanation.

**And it stands down when the bar is on the panel.** With `secondScreen` on the
quick bar is borrowed by a `Presentation` on the other display; an overlay in
the activity's window would ring a bar that is not there. It declines by the
same rule as a missing target, and the flag stays unset.

`Coach` is a view in our own window, so `StepAside` and both panels' step-aside
logic are untouched by any of this.

## Settings gets the way back in

A `Getting started` category in `settings_app.xml`, two rows:

- **Run the setup again** — starts `WelcomeActivity`. Clears nothing, so leaving
  it half-done cannot lose a setting.
- **Show the guide again** — clears the three guide flags, so the marks fire
  next time each screen is opened.

Two rows and not one: re-running seven questions and re-arming three overlays
are different wants.

## Strings

Roughly ninety new keys, across nine files. `scripts/check-strings.py` runs over
the lot — an unknown key or a disagreeing format specifier fails; a missing
translation is counted, not failed.

Model names stay literals (product names, like Kempston). Everything else is a
resource, including the skin names, which already are: a literal is one
`check-strings.py` can never see, because it never reaches `strings.xml`.

## Testing

**JVM**

- `StepsTest` — list composition: no archive page without a catalogue or a
  content folder, a shorter Play wizard, and every page's `apply()` writing
  exactly the keys it claims.
- `WelcomeGateTest` — the new first-run question. Pins the case that is broken
  today: a preferences file with something already in it, on an install that has
  never been updated, still needs the wizard.

**Device**

- `WelcomeTest` — walks the wizard, asserting each page wrote its preference and
  that Skip wrote nothing.
- `GuideTest` — clears the three flags and checks each tour fires once, and not
  twice.
- `MachineIdsContractTest` — the curated six against `FuseNative.machineIds()`
  after launching the emulator. Asserted, never skipped on empty.

**The harness changes.** `Emulator.launch()` stops tapping
`R.string.setup_start` and instead sets `setupDone` and the three guide flags as
part of setting the world it needs — the rule this project already states, since
a test that inherits the bench's state is a test that passes or fails on what
somebody last did by hand. Deterministic, unlike a `tapIfPresent` racing a
posted overlay.

## Build order

1. The gate fix, with `WelcomeGateTest`. Its own commit, and self-contained:
   `isUpdate` moves into `Prefs`, `StartPanel.setupNeeded` asks it instead of
   `getAll().isEmpty()`, and today's folders panel starts appearing again. The
   wizard replaces that panel in step 2; until then the fix stands on its own
   and can go out without any of what follows.
2. `WelcomeActivity`, `Step`, `Steps`, and pages 0, 1 and 7 — the wizard end to
   end, with the folders logic moved out of `StartPanel` and that class's ROM
   half left alone.
3. Pages 2–6.
4. `Coach` and `Tour`, and the machine's tour.
5. The library's and the archive's tours.
6. The two Settings rows, `screen/Links`, and `AboutActivity`'s corrected
   comment.
7. Translations, and `scripts/check-strings.py`.

## Not doing

- **No live filter preview.** See page 4.
- **No tour of anything else.** Settings, the game details screen and the
  scraping sweep are all reached deliberately by somebody who went looking, and
  a mark on a screen you chose to open explains nothing you did not just ask
  for.
- **No "you have not scraped anything yet" nudges.** Nothing here starts a
  background fetch, and nothing counts whether the guide was read.
