# The library

A screen the app starts on: the content folder, browsable, with folders and zip
archives to walk into and a game at the end of it. Decided 2026-08-06, before
any code; this is what was chosen and why, so that the choices can be argued
with later rather than rediscovered.

## What it is

The content folder — the one already chosen in *Settings › Library* and held as
a persisted SAF grant in `contentTree` — shown as a list or a grid, with folders
and `.zip` archives you can enter. Opening a file loads it into the machine if
it is a type the emulator supports; anything else is not shown at all.

Four tabs: **Browse**, **Favorites**, **Recents** and **Catalogue**. It opens on
Browse, because the folder tree is what the library is for and it is the same
every time; a screen that changes under you depending on what you played last is
harder to learn, and Recents is one tap away.

The first three are three views of what you have. **Catalogue** is the one that
is not: it browses a service — ZXInfo's ~40,000 entries, by search, by letter,
by category or by what arrived most recently — and imports a title into the
content folder with its cover and details already attached. It came later than
the rest of this document (see *The fourth tab: the catalogue*, below) and it is built only
when there is a catalogue to browse, so a build with none shows three tabs and
says nothing about a fourth.

Beside all of it, eventually, a pane of metadata and artwork for whatever is
selected.

## Decisions

**It is its own activity, and the two cross over explicitly.** `LibraryActivity`
is the launcher; opening a game starts `EmulatorActivity`. With the library
switched off the launcher goes straight to the machine, as it does today.

**Back does not carry you between them, and the first draft of this document was
wrong to say it would.** Two things that were already true stop it, and both are
right:

- `EmulatorActivity` is `android:launchMode="singleInstance"`, so it lives in a
  task of its own and has never been in anybody's back stack. That is what
  makes a second game reuse the one instance — two of them would be two Fuse
  cores in one process — and it is not up for changing.
- Its `onKeyDown` swallows BACK to open its own menu, deliberately: a machine is
  not a page to be backed out of, and a Spectrum put away by accident is a
  Spectrum whose RAM has gone.

So each direction is a row of its own, and neither disturbs the other's task:

```
LibraryActivity  ──  open a game  ─────────→  EmulatorActivity
      (task A)                                     (task B)
                 ←──  ☰ › Library  ──────────
                 ──   ⌗ Machine   ─────────→
```

- **☰ › Library**, in the emulator's sheet, brings the library's task forward.
  Shown only when the library is in use — it asks `startsInLibrary`, the same
  one question the launcher asks — so it never leads nowhere.
- **The Machine action** in the library's bar starts `EmulatorActivity` by
  component with **no action and no data**, so nothing is loaded and the machine
  comes forward exactly as it was left. Opening a *game* is the other thing, and
  it loads.

**Nothing needs to pause the machine on the way across.** `EmulatorActivity`'s
`onPause` already sets `pausedByAndroid` and calls `FuseNative.setPaused(true)`,
so emulation stops the moment the library takes the window. It stops there
deliberately: Android's pause and the user's own are kept apart, and only the
user's survives coming back, so the machine resumes by itself on return. A
second pause laid on top would mean coming back to a stopped machine and a play
button, every time.

**On for new installs, off for updates.** A fresh install starts in the library;
anyone updating from 1.3.1 keeps landing on the machine until they turn it on.
Changing what the app does on launch, for everyone, without asking, is not a
thing to do to people who have had it one way for a year. One migration flag in
the preferences pays for that.

**A content folder is the gate.** The library cannot be switched on without one:
the switch in *Settings* is disabled, saying so, with the folder row directly
above it. So the default above is a default *preference*, not a default screen —
a fresh install with no folder chosen still starts on the machine, and choosing
a folder is what makes the library the thing it opens on. An update is off
either way until somebody turns it on, which then requires the folder like
anything else.

There is no implicit fallback to the data folder or to *Documents*. A browser
pointed at a folder the user did not choose shows them a folder they did not
mean, and the interesting failure is the empty one: scoped storage hands an app
only what it wrote itself, so a plausible-looking default would come up empty on
exactly the devices where somebody else put the games there.

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

**A game is identified by its path, with its hash kept alongside.** The document
path is the key: it is cheap, it is known while browsing, and it is how ES-DE
does it. The MD5 that `Media.stage()` already computes when a game is loaded is
recorded next to it, so a later version can notice a file that moved or was
renamed and repair the key rather than losing the entry. Keying by hash alone
would be prettier and is not possible: nothing in a folder could be shown as a
favourite until every file in it had been read from end to end, which is the
folder of two thousand tapes again. A zip entry's key is the archive and the
entry within it.

**Recents is the list the app already keeps.** `Recents` holds what *Open
recent…* shows in the emulator's menu, and the tab is a second view of it rather
than a second list. Two consequences to build for: its entries can point outside
the content folder — a download, a hand-off from ES-DE — and their grants can
die, which `Recents.forget` already handles. *Open recent…* stays in the
emulator's menu for the same reason *Open file…* does.

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
- all the tabs it had then — Browse, Favorites, Recents; Catalogue came later
  and is described at the foot of this document;
- the setting, its content-folder gate, and the migration that leaves existing
  installs alone.

Favourites need a store of their own now — paths and their hashes, in the data
folder — which the metadata PR then absorbs rather than leaves behind. It is
worth the small duplication: having every tab from the start settles the layout
before any artwork arrives, and Recents costs almost nothing because the list is
already there. That bet paid for the tab bar and the list layout: the fourth
tab, two pull requests later, reused both untouched. It did not pay for the pad
— `LibraryActivity.buildPadNav`, built for a list, gained branches gated on
`Tab.CATALOGUE` in six of its methods (move, page, activate, back, search,
options) once the catalogue tab arrived with a view of its own that a cursor
cannot walk the same way. The last two are the ones somebody notices: without
`search`, X brings the keyboard up over a field nobody can see and types into
the library's own; without `options`, Select opens a dialog about the list
behind the catalogue.

The metadata pane, artwork and video are the second pull request, on
`feature/library-metadata`. The layout was designed with the pane in it and
shipped without its contents.

## The second pull request: linking to ES-DE

The pane gets filled from ES-DE, because the person who has scraped a Spectrum
collection has almost certainly scraped it there, and asking them to do it twice
is a poor way to introduce ourselves. Fetching from an API of our own, and
editing an entry by hand, come after this and are what the store is shaped for.

**A new *Library* tab in Settings owns it**: a Link button, what the last link
found, and Unlink. The library itself offers it once, quietly, when nothing has
been linked and ES-DE is installed — a feature only reachable by somebody who
goes reading the settings tabs is a feature most people never find.

**Matching is by path, and assumes one folder.** ES-DE keys a game by its path
relative to its own ROM folder — `./GOTY/GOTY 2020 […]/Dizzy VIII.tap` — so when
the content folder *is* ES-DE's `zxspectrum` folder the two line up exactly,
subfolders and all. That is the ordinary setup and the only one that can be
matched without guessing; a folder that does not line up produces no matches
rather than wrong ones, and says so.

**Metadata is copied; media is not.** A link reads ES-DE's
`gamelists/zxspectrum/gamelist.xml` and writes `library/metadata.json` into the
data folder — our own file, keyed by the game's path.

That store used to be a gamelist too, borrowing ES-DE's schema so it opened as
something familiar. It stopped paying: every field ES-DE has no room for meant
another `zedex*` element they will never read, in a file they own and rewrite,
and providers offer plenty they have no room for. Our own format says so, holds
lists without inventing a convention per field, and can carry characters XML
1.0 cannot — see `Metadata.write` for the single control byte that once took
803 games' metadata down with it. Artwork stays where ES-DE
put it and is referenced by its path *relative to the media folder*, resolved
each time it is drawn — so ES-DE re-scraping a game, or replacing its cover,
needs no re-link, and a picture that has gone is a missing picture rather than a
broken reference.

**ES-DE's media folder is not always beside ES-DE.** It records the answer in
`settings/es_settings.xml` as `MediaDirectory`, which lives inside the folder we
already hold a grant for; empty means the default `downloaded_media` beside it.
Only when the answer points outside our grant do we ask for that folder, and then
remember it as a grant of its own.

**Pressing Link again replaces.** What came from ES-DE is written again, games
that appeared are added, and entries whose games are gone are dropped. Nothing of
the user's own is at risk yet because nothing is editable yet — and when editing
arrives, our fields are already kept apart from ES-DE's, which is what the
separate namespace in the file is for.

**What the pane shows**: the name, the description, developer, publisher and
year, and one picture — the first of `covers`, `miximages`, `screenshots`,
`titlescreens` that exists, so a partly scraped collection still looks furnished.
A scraped **video** plays muted three seconds after the cursor comes to rest, and
stops when it moves on: long enough that walking through a list does not start a
dozen of them, short enough to feel like an answer to stopping.

**The list shows the scraped name**, with the filename kept in the pane —
"Wonderful Dizzy" rather than "Dizzy VIII - Wonderful Dizzy (2020) v1.0.tap",
which is the single biggest thing metadata buys a browser. It is a setting,
defaulting to on, because a collection is somebody's own and some people want to
see what is actually on the disk.

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

## The fourth tab: the catalogue

Every other tab is a view of what somebody already has. This one is a view of
what they have not, and it is the reason the library is worth opening on a phone
with an empty folder.

**A catalogue is not a scraper, and the seam says so.** `Provider` answers "here
is a file — what do you know about it?", and every one of its methods assumes
the file exists. `Catalogue` answers the other question. A service may implement
one, the other or both; ScreenScraper implements only `Provider` and is never
asked to be browsable.

**A way in is data, not a method.** A catalogue declares its shelves — Search,
A–Z, Categories, Newest, Surprise me — and the tab renders what it is handed, so
a second catalogue never owes an answer it has not got. A shelf whose children
have to be fetched declares itself and yields the rest as **sub-shelves inside a
page**: that is how a category tree and a twenty-seven shelf A–Z fit through
a seam with a method for neither, and it is what keeps `shelves()` requestless
and safe to call while the tab is being built.

**Nothing is fetched speculatively** — a page when the grid reaches it, a cover
when its row is on screen, and nothing at all until a shelf is opened. This
app's address has been blocked once for looking like a crawler, and pacing is
counted per *host* rather than per object for the same reason: a provider
scraping and a catalogue browsing are two objects and one address.

**The pane looks like `DetailPane` and is not it.** `DetailPane` and
`EntryAdapter` are written throughout in terms of `Entry` — a `Uri`, a size, a
modified time, a path inside an archive — and a catalogue item has none of the
four. So `CatalogueAdapter` and `CataloguePane` are classes of their own that sit
in the same places and draw the same way. The sharing is of appearance, not of
code.

**An import goes to `Downloaded/<kind>/` inside the content folder**, never to
its root — without that there is no telling afterwards what the app added from
what somebody put there themselves. The kind is the catalogue's own word mapped
into seven folders of ours (`Kinds`), with one inversion: a **recording** is
filed by the file's kind rather than the entry's, because a recording of Bomb
Jack in the Games folder is not Bomb Jack. It opens itself when it arrives, since
that is what the button offered.

**Importing needs write access, and the content folder has never had it.** Every
grant this app took on that folder was read-only, correctly, until there was
something to write. A grant cannot be widened in place, so the ask happens at the
first import rather than at startup: it names the folder, opens the picker at it,
and carries on with the import that prompted it. Somebody who only browses is
never asked.

**Details come from the catalogue's own service.** The entry id goes straight
through to `Provider.fetch` as an already-matched candidate — certain against the
service that issued it and meaningless to any other — so the provider is chosen
by matching the catalogue's name, and where nothing matches the file is imported
with no details rather than with somebody else's.

## Still open

- Whether the grid, with no artwork scraped, shows anything better than a name
  and an icon by type.
- zxart, the second catalogue. It shaped the seam — the category tree is why a
  page carries sub-shelves — and is not built.
- Bulk import. One thing at a time; a queue is a different feature with
  different manners.
- Whether the library should be built focus-first now, since it is the obvious
  Android TV home screen — see `docs/ANDROID-TV.md`.
- What a favourite means for a folder, or for a zip full of games, as opposed to
  a single file.
