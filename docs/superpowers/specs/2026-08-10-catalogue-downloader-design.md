# The catalogue: browsing what exists, and importing it

Design, 10 August 2026. Approved in conversation; see the decisions section for
what was chosen against what.

## Why

The app can now say a great deal about a game you already have. It has nothing
to say about the forty thousand you do not. ZXDB holds 39,666 Spectrum entries
and this app can open thirteen of the formats they come in; the only reason a
person cannot reach them from here is that nobody has built the way in.

The scraping seam does not answer this. `Provider` is asked "here is a game I
have — what do you know about it?", and every one of its methods assumes the
file already exists locally: `search(Game)` takes a local game, `fetch` fills in
a row that is already in the store, `costPerGame` prices a sweep over a
collection. Browsing a catalogue is the other question, and it needs its own
seam.

## What is being built

A fourth tab in the library — beside Browse, Favourites and Recent — that
searches and browses an online catalogue and imports what you choose into your
own library, with its details and artwork already attached.

**ZXInfo first, zxart.ee next.** Both will also be scraping providers. A site
implements `Catalogue`, `Provider`, or both; ScreenScraper stays a scraper and
is never asked to be browsable.

## The seam

```java
public interface Catalogue {
    String name();
    List<Shelf> shelves();                                  // how it can be browsed
    Page open(Shelf shelf, Query query, int page) throws ScrapeException;
    Item item(String id) throws ScrapeException;            // versions and their files
    ScrapeException refusalFor(int status);                 // as Provider already does
}
```

Five methods, one of them borrowed from the seam next door.

### A shelf is a declared way in, not a method

Each shelf carries a label and what it accepts — free text, a letter, filters,
or nothing. ZXInfo declares four: search-with-filters (its `/search` takes
query, genre, year, publisher, machine and more as parameters), A–Z (it has a
by-letter endpoint), newest-first and random — the last two being `sort` and
`offset=random` on the same search. zxart declares whatever it actually
supports. The tab renders what it is given.

A `Query` is what a shelf was given: free text, a letter, and the filters the
shelf declared it accepts. A shelf that accepts none ignores it.

This is the whole of what makes the seam universal. A browsing mode is data, so
a catalogue never owes an answer it has not got, and adding one later does not
widen the interface for everybody else.

### Everything is paged, because both sites are

ZXInfo takes `size` and `offset`; zxart takes `limit` and `start` and returns
`totalAmount`. `Page` is the items, the total where the catalogue gives one, and
whether there is more.

### An Item is a title with versions; a version has files

The shape both sites really have — ZXDB's `releases[]` each with `files[]`,
zxart's `releasesIds` fetched separately. An `Item` also carries:

- the catalogue's **own word for what it is** (`genreType` on ZXInfo,
  `categoriesString` on zxart), untouched — see *Where imports land*;
- its **availability** (`availability` on ZXInfo, `legalStatus` on zxart);
- a **picture url** when the catalogue offers one.

A file carries its format, its size and its **absolute url** — not a path to be
joined onto a base. ZXDB's own recordings live on `archive.org` rather than on
the Spectrum archive, so a catalogue's files can be spread across hosts and the
downloader must follow what it is given. **The catalogue lists files;
this app decides which it can open**, from `Types.OPENABLE` — read, never
copied, so the two cannot drift. A catalogue is never asked to know what a
Spectrum is.

### Availability is a fact, not a filter

Entries that were never released, or are missing, stay in the list — greyed,
with the reason where the catalogue gives one. A catalogue that silently omits
things looks broken, and "announced and cancelled" is a fact about a game worth
reading.

### The two seams meet at the entry id

`Provider.fetch` already takes a `Candidate` whose handle *is* the catalogue's
entry id. So an import hands that id straight to the scraper: no second search,
no name matching, and the certainty a name match can never promise.

## The screen

A tab that opens on the shelves the catalogue declared: a search box, an A–Z,
and whatever lists it offers. Results fill the grid the library already draws;
tapping one fills the same detail pane, with **Import** where Play sits and
*Other versions…* when there is more than one.

**Thumbnails load lazily, from the file host rather than the API.** Only rows on
screen, through the bounded cache the local grid already uses. On both sites the
pictures are static files on an ordinary web host rather than API calls, so they
cost nothing against a quota and look like web traffic. A catalogue with no
picture gives a text row, exactly as an unscraped local game does today.

**Paging is scrolling.** The next page is fetched when the grid nears the end.

**A failed page is a row, not an empty grid** — what arrived stays, with a retry
under it and the reason from `refusalFor`.

The tab appears only when a catalogue is configured, the way the scrape rows
already hide themselves.

## The import

1. **Downloaded to the cache first.** SAF writes are not atomic and a
   half-written `.tap` is indistinguishable from a real one. Fetched, checked,
   and only then created in the tree — a failed download leaves nothing behind.
2. **Checked against the stated size** — of the download as delivered, which
   for these is the zip. ZXDB gives a size per file and no checksum, so length
   is what there is. Short means discarded, before anything is unpacked.
3. **Written through SAF** — `DocumentsContract.createDocument` under the
   content folder already granted. The same route the ES-DE files take, and the
   reason this works on the Play build, which has no All-files access.
4. **Unzipped on the way in**, as the music already is. One openable file inside
   yields that file; several — a multi-load game in parts — become a folder
   named after the game, which the library already browses. Anything in the zip
   that is not an openable format is left behind.
5. **Named as the file inside the zip is named** — not as the zip is. The
   archive ships `HeadOverHeels.tap.zip` containing `HeadOverHeels.tap`, and it
   is the inner name that is already unique and TOSEC-ish. The store keys on
   path, so an import and a hand-copied file look the same to everything
   downstream.
6. **Already there is not an error.** An existing name means the import says so
   and offers to open it, rather than writing a second copy.
7. **Then the entry id goes to the scraper**, and details and artwork arrive per
   the existing *What to fetch* setting. One flow, not two, so an imported game
   appears with its cover rather than as a filename.
8. **Then the caches are told.** The tree listing and `Artwork` are both keyed by
   path, and a new file neither knows about is invisible until they are cleared.

### Where imports land

`Downloaded/<kind>/`, made on demand. Grounded in ZXDB's own 23 genre types,
42,828 entries:

| Folder | From | Entries |
|---|---|---:|
| Games | Arcade, Adventure, Puzzle, Casual, Sport, Strategy, Game | ~22,000 |
| Applications | Utility, Programming, Emulator, Replacement ROM | ~7,600 |
| Compilations | Compilation, Covertape, Box Set | ~3,600 |
| Magazines | Electronic Magazine, Book, E-Book | ~3,400 |
| Demoscene | Demoscene, Tech Demo, Animation | ~1,400 |
| Recordings | any entry's RZX, whatever its genre — see *Recordings* | 5,352 have one |
| Other | General, Hardware, Advertising, and anything unrecognised | ~4,800 |

Three rules keep this from repeating the ZX81 mistake, where a table written
from one collection offered a 16K Spectrum for every ZX81 program in the
database:

- **The category is the catalogue's; the folder is ours.** zxart gets its own
  mapping to the same six folders, because its vocabulary is different.
- **Unknown falls to `Other`** — never a guess, never dropped. `General` alone is
  3,650 entries, and a genre added upstream has to land somewhere sensible.
- **The table is asserted against the recorded vocabulary**, in both directions,
  as `Suggested`'s machine table now is.

An entry can be a game *and* a compilation. First match wins, in the order
above, so a compilation of games goes to Compilations — otherwise the rule is
not a rule.

### Recordings

An RZX is a recording of somebody playing, and 5,352 entries — 13.5% — have
one. They are worth importing and they are not games, so:

- a recording is **never** the file chosen for "import this game";
- an entry that has one offers **Play the recording** beside Import;
- an imported recording goes to `Downloaded/Recordings`, whatever the entry's
  genre says, because the folder scheme answers "what kind of thing is this
  file" and a recording of Bomb Jack in your Games folder is not Bomb Jack.

That is the one place the file's kind outranks the entry's category. Everything
else follows the table above.

### Which file

One tap imports **the original release** — the one the catalogue lists first —
in the first of these formats it offers:

`tzx` → `tap` → `trd` → `scl` → `dsk` → `mgt` → `img` → `udi` → `szx` → `z80` → `sna`

Tape images first, because they carry the loading scheme and the custom loader
that a snapshot has already thrown away — half of what a Spectrum game is
remembered for happens during loading. Disk images next, in the order the
machines that read them appear. Snapshots last: they always work and they
always start after the part worth seeing.

`gz` is a wrapper rather than a format and is never chosen. **`rzx` is neither
— it is somebody playing the game, and this app can play it back.** Verified on
a device: opening one starts playback, because `utils_open_file` hands an RZX
to `rzx_start_playback_from_buffer`, and 10th Frame duly bowled a frame with
nobody touching the controls. So a recording is never chosen *as the game* — it
is not the game — but it is offered in its own right; see below.

Entries with more than one version also offer *Other versions…*, so a Spanish
re-release or a 128K remake is two taps away rather than the thing you got by
accident.

## Manners

The address was blocked once — *"you have been jailed because of bad requests…
there's no hard limit, it's all based on behaviour patterns"* — so this is not a
footnote.

- **The API is paced; the pictures are not.** Catalogue queries go through the
  `Http` that already sends `Zedex/<version>` and the 500ms spacing `ZxInfo`
  uses. Thumbnails get the identity header and nothing else: throttling ordinary
  image loads to two a second would make the grid unusable and buy nobody
  anything.
- **Nothing is fetched speculatively.** A page when the grid reaches it, a
  thumbnail when its row is on screen. No prefetch, no warming, no background
  sync — the difference between a client and a crawler is exactly this.
- **Never `curl` those hosts bare** while working on this. That is how the
  address was lost.
- **A file may be on a host neither of us chose** — the recordings are on
  `archive.org`. The identity header goes to whatever host a file names, and
  nothing else changes: one file, one request, when somebody asked for it.

## Testing

**Recorded replies, from both sites.** Real ZXInfo and zxart JSON, captured
rather than written to make a parser pass — the practice that caught
`/filecheck` answering `entry_id` where the specification said "id".

**On the JVM:** paging arithmetic; the genre-to-folder table against the
recorded vocabulary, both directions; availability; and the "best file of the
original release" choice.

**On a device:** the import. Writing through SAF into the tree, the folder
appearing, a zip of one file against a zip of several, "already there", and the
library noticing without a restart.

**Mutation-tested:** the format preference and the folder mapping. Both fail
silently — you get *a* file in *a* folder either way.

## Decisions, and what they were chosen against

| Decision | Rejected | Why |
|---|---|---|
| A `Catalogue` seam beside `Provider` | Extending `Provider` | `search(Game)` and `search(text, page)` are different questions; `Provider` is already seven methods and ScreenScraper is not browsable |
| | One method, everything a query | Pushes provider vocabulary into a map of strings and makes the UI guess what a query means |
| Shelves declared as data | A method per browsing mode | A second catalogue would owe answers it has not got |
| Anything openable | Games only | Discards most of what zxart is for, and makes us map and maintain a genre vocabulary to decide |
| | Openable plus standalone media | The library cannot hold things that are not programs |
| Search + A–Z + curated lists | Any one of them | Asked for; the seam supports all three without owing any |
| A fourth library tab | A screen from Options | Buries it beside the maintenance actions |
| | Folded into library search | One screen answering two questions, which this codebase has been bitten by |
| `Downloaded/<kind>/` | The tree root | No way afterwards to tell what the app added from what you did |
| | Ask once and remember | One more question, and one more piece of state that goes stale |
| Details and media on import | The file alone | Throws away a record already in memory that would cost a search to fetch again |
| Best of the original, others on request | Always ask | An extra screen for the common case, about formats most people have no view on |

## Not in this piece

- **A second catalogue.** zxart shapes the seam and is not implemented here.
  Its API is confirmed to work and to differ: `action:filter/export:zxProd`,
  paged by `start`/`limit`, `totalAmount` in the reply, releases as separate
  ids, and its own filter vocabulary — my first guessed filter name was ignored
  and returned all 58,032 entries, which is the argument for the seam in one
  line.
- **Downloading anything that is not a program** — the music and pictures a
  catalogue holds are reachable through scraping already.
- **Bulk import.** One thing at a time; a queue is a different feature with
  different manners.
