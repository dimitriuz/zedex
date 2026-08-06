# The library

A screen the app starts on: the content folder, browsable, with folders and zip
archives to walk into and a game at the end of it. Decided 2026-08-06, before
any code; this is what was chosen and why, so that the choices can be argued
with later rather than rediscovered.

## What it is

The content folder — the one already chosen in *Settings › App* and held as a
persisted SAF grant in `contentTree` — shown as a list or a grid, with folders
and `.zip` archives you can enter. Opening a file loads it into the machine if
it is a type the emulator supports; anything else is not shown at all.

Beside it, eventually, a pane of metadata and artwork for whatever is selected.

## Decisions

**It is its own activity, and the emulator sits on top of it.** `LibraryActivity`
becomes the launcher; opening a game starts `EmulatorActivity` over it, and Back
comes back. That gets the behaviour for free from the back stack: the machine
pauses when it loses the window — which it already does — so a game is exactly
where it was left, and nothing about the emulator's lifecycle, orientation
handling or key routing has to learn about a browser.

```
launcher → LibraryActivity
                ↓ open game
         EmulatorActivity
                ↑ Back — machine paused, still loaded
```

With the library switched off the launcher goes straight to the machine, as it
does today.

**On for new installs, off for updates.** A fresh install starts in the library;
anyone updating from 1.3.1 keeps landing on the machine until they turn it on.
Changing what the app does on launch, for everyone, without asking, is not a
thing to do to people who have had it one way for a year. One migration flag in
the preferences pays for that.

**One root: the content folder.** Not the data folder as well — the tapes and
disks Zedex wrote are already reachable from the emulator's own menus, and a
browser that mixes a person's collection with the app's own working files has to
explain itself. With no content folder chosen the library says so and offers the
picker.

**`Open file…` stays.** The library browses the content folder; the system
picker reaches everything else — a download, another app's folder, a stick.
They do different jobs.

**Metadata lives in the app's data folder**, in ES-DE's shape plus whatever we
need beyond it, rather than beside the games. The content folder can be
read-only — a shared drive, a card — and a library that cannot record what has
been played is worse than one that does not travel.

**Zip only.** `java.util.zip` is in the platform and covers nearly every
Spectrum download. Entering a zip lists the supported entries inside it; opening
one extracts that entry to the cache and hands it over exactly as a picked file
is handed over today, through `Media.stage()`, so the MD5 the poke database
matches on is still the file as distributed. `.7z` is in ES-DE's extension list
and would need a dependency; it can come later if anyone asks.

## The first pull request

Browse and launch, and nothing else:

- the content folder, folders and zips as folders;
- list and grid, switchable;
- files the emulator cannot open hidden;
- opening a file loads it into the machine;
- sort, and a search box that filters as you type — a folder of two thousand
  tapes is the case this screen exists for;
- the setting, and the migration that leaves existing installs alone.

The metadata pane, artwork and video are the second pull request on the same
branch. Designing the layout with the pane in mind, and shipping without it.

## Notes for building it

- **Listing is `DocumentsContract`, not `File`.** The content folder is a tree
  grant; `DocumentFile.listFiles()` is one query per child and is famously slow
  on a large folder. Query the children directly with the columns wanted —
  document id, display name, mime type — in one cursor.
- **RecyclerView is already on the classpath**, transitively through
  `androidx.preference` (→ `appcompat` → `recyclerview`). If the library uses
  it, declare it in `app/build.gradle` rather than relying on that: it is one
  line, it changes the APK by nothing measurable, and the alternative is a
  hand-rolled recycling list for a folder of thousands.
- **The supported extensions already exist in one place**: `EsDe.java`'s
  `EXTENSIONS`, which is what ES-DE is told the app can open. The library must
  agree with it, so it should read from a shared list rather than a second copy
  — two lists that can disagree about `.udi` is a bug nobody would find.
- **`EmulatorActivity` keeps its `VIEW` intent filter and its own launcher
  path.** `am start` in the scripts and docs, and the ES-DE hand-off, both
  address it directly; none of that may break.

## Still open

- How metadata is keyed: by path, as ES-DE does, or by the MD5 `Media.stage()`
  already computes, which survives a rename and a move. For the second PR.
- Whether the grid, with no artwork scraped, shows anything better than a name
  and an icon by type.
- Whether the library should be built focus-first now, since it is the obvious
  Android TV home screen — see `docs/ANDROID-TV.md`.
