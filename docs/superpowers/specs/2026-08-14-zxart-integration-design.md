# zxart.ee — the second catalogue, and a fourth scraping source

*Design, 2026-08-14. Measured against the live service on that date; every
figure below came from a reply that was captured and kept.*

zxart.ee shaped the `Catalogue` seam and was never built. This is that piece,
plus the scraping half the same client makes almost free, plus the two archives
zxart is actually famous for — its graphics and its music.

## What is being built

Three pieces behind one client, in this order:

1. **`ZxartCatalogue`** over `zxProd` — a second browsable archive in the
   library's fourth tab, with a source row to choose between it and ZXInfo, and
   a sort control that gives every shelf a *Top rated* reading.
2. **Music and graphics shelves** over `zxMusic` and `zxPicture` — 29,672 tunes
   and 19,408 pictures, imported the way books and magazines already are.
3. **`Zxart implements Provider`** — a fourth scraping source, which can
   identify a file *exactly* where the others guess, and which states a game's
   machine and interface where `Suggested` infers them.

Each piece is separately shippable. The first proves the client.

## What was measured, and how

Thirty-odd identified requests, spaced 2.5s, under a `Zedex/1.4.12`
User-Agent, each reply saved. **A deliberate nonsense parameter was the control
throughout**, because zxart's failure mode is silence: `zzznonsense:xyz` as a
path segment and `filter:zzznonsense=xyz` both came back **byte-identical** to
no parameter at all — 2,415 bytes, `totalAmount: 58032`. Every claim below that
says "works" means *the reply differed from that control in the way the
parameter's name promises*, and every claim that says "ignored" means it did
not differ at all.

That is not a formality. Seven plausible names are ignored, including the one
this project's own earlier notes recorded as the title search.

### The grammar

```
https://zxart.ee/api/action:filter/export:<entity>/language:<lang>/start:<n>/limit:<n>/filter:<name>=<value>/order:<field>,<dir>/
```

Wrapped in `{totalAmount, start, limit, responseData: {<entity>: [...]},
responseStatus}`.

| | |
|---|---|
| Entities that work | `zxProd` 58,032 · `zxRelease` 106,244 · `zxProdCategory` 285 · `zxMusic` 29,672 · `zxPicture` 19,408 · `author` |
| Entities that 500 | `zxFile`, `publisher`. `group` parses and answers nothing for a publisher id |
| Filters that work | `zxProdId`, `zxProdSearch`, `zxProdCategory`, `authorId` (on `zxProd` *and* on `author`), `zxProdId` on the `zxRelease` export, `zxMusicId`, `zxPictureId`, `zxProdMinRating`, `structureDateModified` |
| Filters **ignored** | `zxProdTitleSearch`, `zxProdTitle`, `zxProdTitleStart`, `zxProdMd5`, `zxReleaseMd5`, `zxProdImportId`, `zxProdWosId`, and `action:search` with `query:` |
| Orders that work | `votes,desc` (the rating), `date,desc`, `title,asc` — on prods, and `votes,desc` on music and pictures too |
| Orders **ignored** | `rating,desc`, `votesAmount,desc` |
| Language | `rus` is the default; `eng` and `spa` translate `categoriesString`, the category tree's titles, picture `tags` and the `url` field |
| Language does **not** affect search | measured: `zxProdSearch=dizzy` under `eng` and under `rus` gave `totalAmount` 189 both times, the same ten ids **in the same order**, the same titles. Only the reply's size differs (12,441 against 13,988 bytes), because the translated fields are bigger — so a client that reads only ids, titles and years can ask in any language and get the same answer |

Four of those are rules rather than observations:

- **`limit:` is never omitted.** Absent, it defaults to **1000** — a 1.18 MB
  reply, measured.
- **`start:` is an item offset, not a page number.** The opposite of ZXInfo's
  `offset`, where size-and-stride confusion has cost a bug already. Here
  `start = page * size` and `seenBefore = start`.
- **`types:` is never sent.** zxart's own author documents
  `types:zxProd,zxRelease` for fetching a prod with its releases; it answers
  **HTTP 500**, with and without `action:filter`. A prod and its releases are
  two requests.
- **Every filter and order name the app sends is a constant in one place**,
  with the date it was measured. An unknown name is ignored, so a typo is a
  search that quietly matches everything — 58,032 results reading as success.

### What a prod and a release carry

A prod: `id`, `title`, `year` (96.5% have one), `youtubeId` (47.1%),
`legalStatus`, `publishersIds`, `groupsIds`, `releasesIds`, `imagesUrls`
(100%), `maps` (38.4%), `rzx` (47.9%), `authorsInfo`, `votes` and
`votesAmount`, `connectedCategoriesIds`, `categoriesString`, and `importIds`
holding `zxdb`, `wos`, `vt`, sometimes `pouet`.

A release: `file` (absolute), `fileName`, `releaseFormat`, `releaseType`,
`year`, `hardwareRequired`, `inlays`, `ads`, `instructions`, `prodId`, and
**`releaseStructure`** — a recursive tree carrying an **md5 and a size for
every file, the ones inside the zip included**.

*(Coverage figures are from a contiguous 1,000-prod slice, which is one import
batch and therefore not a random sample; they are order-of-magnitude, not
census.)*

## Piece 1 — the catalogue

### The client

**`library/scrape/ZxartApi`** — the only thing that knows the grammar. In
`scrape/` beside `Http` because both halves use it, and the catalogue package
already depends on that one and not the reverse.

It owns the URL building, the paging arithmetic, the language choice, the
pacing and the parsing, and three details that are easy to get wrong:

- **Refusals.** 429 and 403 → `CLOSED`, ≥500 → `NETWORK`, `responseStatus`
  other than `success` → `MALFORMED`. With the caveat in a comment that **zxart
  answers 500 with an empty body for a request it does not understand**, so a
  500 on every call is our bug and not their bad day.
- **Titles arrive HTML-escaped** — `Girl &amp; Sea`, `Shoot &#039;em up` — so
  unescaping happens here, once, rather than in each caller.
- **Pacing is 250ms per host** (`Pace.before("zxart.ee", 250)`), independent of
  ZXInfo's because `Pace` keys on the host. See *Manners*.

Language is the app's own locale mapped to `rus`/`eng`/`spa`, English for the
other six of the app's nine.

### Shelves and sorts

| Shelf | Accepts | How |
|---|---|---|
| Search | TEXT | `filter:zxProdSearch=<text>`; pages properly (`start:3` gives distinct rows; "head" → 271) |
| Categories | NOTHING | the tree, one 13.5 KB request |
| Everything | NOTHING | no filter, all 58,032 |

| Sort | |
|---|---|
| Default | no `order:` |
| Top rated | `order:votes,desc` |
| Newest | `order:date,desc` |
| A–Z | `order:title,asc` |

**There is no A–Z *shelf*.** Every title-prefix filter is ignored, so a
letter-picker cannot be built — but ordering by title can, which is the same
want served a different way. A shelf that cannot be built is simply not
declared; that is what the seam is for.

**`similarTo` is same-category**, `filter:zxProdCategory=<the prod's own
leaf>`, because the caller's label is "Games like this one" and a leaf category
is what that means. Author browsing (`filter:authorId` — measured, 13 prods for
Raffaele Cecco) is a later shelf with its own words, not this one wearing the
wrong ones.

**Sorting is a control, not a shelf.** `Catalogue.Query` gains a sort and
`Catalogue` declares which it honours:

```java
enum Sort { DEFAULT, TOP, NEWEST, ALPHABETICAL }
default List<Sort> sorts() { return Collections.singletonList(Sort.DEFAULT); }
```

`CatalogueView` gains a third row beside Source and Format — "Sort · Top
rated" — shown only when a catalogue declares more than one, and changing it
`restart()`s the current shelf so the sort applies *inside* whatever is on
screen: Top inside Games, Top inside a search, Top inside a sub-category. Four
new strings, which is nine files each. **ZXInfo's `sort=score_desc` is
measured now, and it works.** `GET /v3/search?query=head&size=3&mode=compact`
against the same with `&sort=score_desc` appended, same index snapshot
(`zxinfo-20260723-075659`), single shard both times (`_shards.total: 1`, so
tie order is deterministic and not a distributed-shard artefact): both
answered `total 119` and the same top hit (`0045541 Head On`, score
`210.13988`), but the tied group at score `77.102264` held entirely different
records in positions two and three — `Beach-Head`/`Big Head` with no `sort`,
`Head Control`/`Hammer-Head` with `sort=score_desc`. Same query, same index,
same primary score, different tie order: the parameter is honoured, not
ignored.

**And each of the two is reproducible, which is what closes the argument.**
Two rows differing between two calls is also what a live index or a run-to-run
tie-break would look like, and "single shard" does not rule either out — so
both URLs were asked a second time, minutes later: byte-identical replies, 9,349
bytes without `sort` and 5,665 with, the same records in the same positions each
time. The unsorted call is stable, the sorted call is stable, and they differ
from each other. Nothing varying underneath explains that; the parameter does.
The differing payload *size* is a second witness: sorting changes what comes
back, not merely the order it comes back in. ZXInfo therefore declares `TOP` as well as `DEFAULT` — `NEWEST`
already exists as its own shelf via `sort=date_desc` rather than through this
control, and `ALPHABETICAL` stays unmeasured (not attempted; not one of the
four things this task was scoped to measure).
(Note: the brief's probe URL for this,
`https://api.zxinfo.dk/api/zxinfo/search?...`, 404s — that path is stale.
The app's own base is `https://api.zxinfo.dk/v3/` (`ZxInfo.API`); the
measurement above used the corrected `/v3/search` path.)

Two honest caveats: rows with equal ratings can shuffle between pages, which is
what any unstable sort does over a paged API; and `votes` is an average, so a
5.0 from two people could outrank a 4.7 from thirty-five. zxart's own top rows
each carried 24–35 votes, so the field looks weighted already — if it turns out
not to be, `filter:zxProdMinRating` (2,433 prods at ≥4) is the lever, added on
evidence rather than pre-emptively.

### The category tree, and the folder it decides

One request returns all 285 categories, nested through a `categories:
[childIds]` array, with nine roots: **Games** (23,162 prods), **System
Software**, **Misc**, **Educational**, **Compilation**, **Demoscene** (12,466),
**Press**, **Applications**, **Series**. Root categories roll up — filtering by
a root id returns its children's prods.

**Categories uses both halves of a `Page`.** Opening it yields nine root
sub-shelves and no items. Opening *Games* yields its ten child sub-shelves
**and** its 23,162 prods, paged. That is the first thing in this codebase to
fill `items` and `shelves` in one page, which is what the seam always claimed
to be for.

**The tree is held for the session, and it is also the folder mapping.** A
prod's `connectedCategoriesIds` are leaves; walking them up to a root decides
`Downloaded/<kind>/` **by id and never by a word**, which is what makes
localised labels safe. The nine map onto the existing folders:

| zxart root | folder |
|---|---|
| Games | `Games` |
| System Software, Applications, Educational | `Applications` |
| Compilation | `Compilations` |
| Press | `Magazines` |
| Demoscene | `Demoscene` |
| Misc, Series | `Other` |

5.3% of prods sit under more than one root; those resolve through `Kinds`'
existing precedence, so a compilation of games still lands in Compilations.

### `legalStatus` is translated, not passed through

Over 1,000 prods: `unknown` 975, `forbidden` 21, `unreleased` 3, `recovered` 1.
**The word "available" never appears.** Passed through raw into
`Item.availability`, `CatalogueAdapter.greyed` — stated *and* not available —
would grey **every zxart row in the catalogue**.

So: `unknown` → **null**, nothing stated and nothing greyed; `forbidden`,
`unreleased`, `recovered` pass through verbatim as the row's stated reason.
Nothing gates a download on this — `available()` feeds only the greying — so
getting it wrong is cosmetic and total, which is exactly the kind of fault that
ships.

### `item(id)`, and the one seam change

`item(id)` is **two requests**: the prod, then
`export:zxRelease/filter:zxProdId=<id>`, which returned all 24 of *Licence to
Kill*'s releases in one call. A `Version` is a release — label from
`releaseType` and `hardwareRequired`, format from `releaseFormat` (already the
*inner* format: `LicenceToKill.tzx.zip` is a `tzx`, exactly what `Download`
promises), size from `releaseStructure`'s root node.

**A prod row carries `releasesIds` and no files**, so `Item.formats()` is empty
for every list row and the screen's format filter would reject the entire
catalogue. `Catalogue` grows one predicate:

```java
default boolean knowsFormats() { return false; }   // ZXInfo overrides it true
```

`CatalogueView` hides `formatRow` when it answers false and never sets
`Query.sifting()`, since a bigger page for sifting is meaningless when nothing
is sifted. The alternative — reading "no formats known" as "keep everything" —
makes the filter appear to work and do nothing, which is the same class of
fault as a chooser that changes nothing.

### Choosing the source

A second `TextView` beside `formatRow`, the idiom this view already uses:
"Catalogue · zxart", tapped for a chooser, writing `Prefs.KEY_CATALOGUE` —
which `Catalogues.preferred` has read since day one with nothing writing it.
`CatalogueView` gains `setCatalogue()`, which abandons in-flight work and shows
the new roots; `LibraryActivity` keeps building the view once. `Catalogues.all`
gains zxart behind its own `configured()`, which is true — no credentials.

## Piece 2 — music and graphics

Both ride the path books and magazines already take: `Imports.document` files
by `Kinds.folderFor(item.kind())` with `Keep.WHATEVER_ARRIVED`, and the pane
offers **Open** rather than Play, with the mime the provider states.

- **`Kinds` gains `Music` and `Graphics`**, matched on the entity's own word.
  The catalogue sets `kind` to "Music" or "Graphics" because that is what the
  entity *is*; whether a tune is PT3 or beeper belongs in the version label,
  not in a folder name.
- **The rendered picture is a PNG, and the service will not tell you so.**
  Measured 2026-08-14 by fetching one: the `zximages` endpoint answers 200 with
  the PNG magic number (`89 50 4E 47 0D 0A 1A 0A`) and **no `Content-Type`
  header at all**. Its url carries no extension either — it is
  `zximages/id=2232;border=0;pal=srgb;type=standard;zoom=1`. So neither the url
  nor the reply says what the bytes are, and nothing downstream can sniff it:
  the catalogue states `png` from this measurement, and the imported file must
  be *named* `.png` or the phone is handed an extensionless blob it has no
  handler for. This is the one place in the feature where a `Download.format`
  cannot be read off the thing it describes.
- **A tune is one version, two files**: the ogg on `music.zxart.ee` first, the
  PT3/mt3 original second. A picture likewise: the rendered PNG first, the
  `.scr` second. The order is load-bearing — `Pick.otherFile` answers with the
  first file that is neither a picture nor for the machine.
- **`Pick.PICTURES` gains `scr`.** Without it a picture entry's `.scr` reads as
  "not a picture", wins over the PNG, and is handed to a phone with nothing to
  open it. A `.scr` *is* a picture — this app renders them already — and with
  it listed the PNG wins by position, while a `.scr`-only entry still answers
  with the `.scr`.
- **`<queries>` gains `audio/*`.** Android 11+ hides every handler for an
  undeclared mime, so Open on a tune would resolve to nothing — the
  `ACTION_SENDTO` fault again.

**One catalogue, not three**, and the root gains exactly two shelves: *Music*
and *Graphics*. Each yields its own sub-shelves when opened — Everything, and
Search where the service turns out to have one — through the same mechanism
Categories uses, so the root list stays five rows long and neither entity needs
a screen or a chooser of its own. The sorts apply inside them as anywhere else,
and Top rated is the one people want here, since these archives are ranked by a
community that voted: 4.88 down for pictures, 4.80 for tunes.

**`zxMusicSearch` and `zxPictureSearch` are measured now, and both work.**
`filter:zxMusicSearch=beyond` against `export:zxMusic` answered `totalAmount:
10` (control: 29,672 unfiltered) with rows titled *Beyond Time*, *Something
from beyond*, *Beyond The Road* — the query term present in every title, not
the unfiltered first page. `filter:zxPictureSearch=girl` against
`export:zxPicture` answered `totalAmount: 124` (control: 19,408) with rows
titled *Girl & Sea*, *Car & girl*, *RizcGirl*. Both are a `success` response
with a total two-to-three orders of magnitude below the unfiltered count and
rows that visibly match the search term, which is what a working filter looks
like and what the zxart entities table's control (`zzznonsense` returning the
unfiltered total byte-identical) says an ignored one does not. So: **a Search
sub-shelf is declared for both Music and Graphics** — the "costs a shelf and
nothing else" branch does not apply here.

Author names *are* resolvable here where publisher names were not
(`export:author/filter:authorId=`), but one id per request — comma-joining
three ids returned one — so it happens in `item()` for the pane, never per row.

## Piece 3 — the provider

**`library/scrape/Zxart implements Provider`** — no credentials, no quota, and
`costPerGame` = **5** whatever media are wanted: one search, up to three release
lists for the confirmation below, and one prod. Media cost nothing, because
every one is a static file on an ordinary host, exactly as ZXInfo's are.

Five is the honest ceiling rather than the common case — a search that answers
with one candidate confirms in one request and a `fetch` reuses the release list
that confirmation already fetched, so the usual game is three. `costPerGame`
prices a sweep somebody is about to commit a collection to, and a figure that
flattered the average by a factor of two would be the ZXInfo-versus-ScreenScraper
mistake again with the sign reversed.

**Identification is a name search confirmed by hash.** One `zxProdSearch` on
the title derived from the filename; then for the top three candidates,
`export:zxRelease/filter:zxProdId=` and a recursive walk of `releaseStructure`
looking for the file's md5. The zip's md5 and every inner file's are both
listed, so an unzipped `.tzx` matches the same row its zip does. A hit is
*exact* — which nothing else in this app can offer, since ZXInfo's `/filecheck`
only answers for TOSEC-named files. No hit falls back to the ordinary name
match, where `Merge`'s fill-gaps rule bounds what a wrong guess can cost.

There is no md5 *filter* — `zxProdMd5` and `zxReleaseMd5` are both ignored —
which is why confirmation costs a request per candidate rather than being the
first thing asked.

`fetch` also writes `Meta.genre`, and it is **the topmost segment of
`categoriesString`** — zxart's own root-to-leaf breadcrumb, so
`Games/Action/Maze/Isometric Maze Games` becomes `Games`. `Meta.genre` means the
broad kind of thing an entry is, and the narrowing below the root is what
`ZxartCatalogue`'s category tree already resolves; `Zxart.genreOf` therefore does
not walk it. Pinned in `ZxartTest` against `Fixtures.PROD_SEARCH`, which is an
English capture — `PROD_LICENCE_TO_KILL`'s own `categoriesString` is Cyrillic and
could not pin an English claim. (Undeclared here until the branch's final review;
the code shipped in Task 14 and this line is the design catching up with it.)

`fetch` is two requests and maps by **rule, not position**:

| Folder | From |
|---|---|
| `titlescreens` | the `zximages/…` entry of `imagesUrls` — the rendered loading screen |
| `screenshots` | the `/screenshot/…` entries of the same array |
| `covers`, `backcovers`, `physicalmedia` | `inlays`: front first, then the `_Back` and `_Media` suffixes — a heuristic, so it gets a fixture and an unrecognised name falls through rather than guessing. **Measured** over 400 releases (`hw-sample.json` + `inlay-sample.json`, a contiguous `zxRelease` slice, 178 with any `inlays` at all), by suffix — the segment after the last `_` in the filename, or `front` when there is none: `front` 166, `Back` 101, `Media` 99, `2` 43, `Front` 41 (a second, capitalised spelling of the same thing the no-suffix case already covers), `SideA` 13, `SideB` 11, `3` 7, `Tape` 5, `4` 2, `FrontCase` 1, `FrontCase2` 1, `GoldenCase` 1, `WhiteCase` 1, `YellowCase` 1, `2Back` 1, `2Front` 1, `2-Back` 1, `2-Front` 1. The three the heuristic already names cover the bulk (`front`+`Front` 207, `Back` 101, `Media` 99 of 400 total suffix hits), but a fifth of inlays carry something else — numbered sides (`2`/`3`/`4`, `SideA`/`SideB`, `2Back`/`2Front`/`2-Back`/`2-Front`, for a multi-tape or multi-disk release) and edition-specific case art (`FrontCase*`, `*Case` in gold/white/yellow). None of these is a fourth folder this design names; they fall through the existing "unrecognised name falls through" rule rather than being guessed at. |
| `maps` | `maps` |
| `adverts` | `ads` |
| `manuals` | `instructions`, which are `.txt` — already a manual extension, and `InstructionsActivity` renders one properly rather than letting another app re-wrap it |

**`hardwareRequired` is the prize.** `["zx128","ay","kempston","int2_2"]` is a
*stated* machine and interface where `Suggested` infers them from a genre and a
machine-type string. It gets the same discipline as the tables it joins: a
recorded vocabulary, asserted in both directions, and an unknown value mapping
to nothing.

**Measured** over the same 400-release slice as the inlay count above (97 of
400 releases carry a non-empty `hardwareRequired`): `zx48` 58, `kempston` 35,
`zx128` 33, `int2_2` 27, `zx+3` 20, `ay` 12, `int2_1` 10, `cursor` 7, `zx16` 7.
That is the full vocabulary this slice showed — nine values, a mix of machine
(`zx48`, `zx128`, `zx16`, `zx+3`) and interface (`kempston`, `int2_2`, `int2_1`,
`cursor`, `ay`) in the one array, which is why the design reads it as stating
both rather than picking one. `int2_1`/`int2_2` read as Interface II ports one
and two rather than two different interfaces. This is one contiguous slice
(the same caveat the coverage figures elsewhere in this document already carry)
and not a census — a table built from it should still leave an unknown value
mapping to nothing, the ZX81-16K mistake being exactly the alternative.

**`youtubeId` becomes a link, not a medium.** 47.1% of prods have one, so it is
worth keeping:

- **`Meta` gains `videoLink`**, a full URL rather than a bare id, so a future
  source offering something else fits the same field. The store is
  `library/metadata.json`, which is ours, and `Meta` is built through `but()` —
  a thirteenth field costs a builder line and a reader.
- **It is not the `videos` folder.** That holds an mp4 the gallery plays
  inline; this is a hand-over to another app. Conflating them would have the
  gallery trying to play a URL.
- **Two places show it**, both of which already have a row for this kind of
  action: `CataloguePane` for a catalogue row, before anything is imported, and
  `GameInfoView`'s action row for a game in the library, beside its manual and
  music icons — shown only when there is a link, the rule `rowManual` follows.
- **`<queries>` gains an `<intent>` for `ACTION_VIEW` on `https`.** Without it
  `queryIntentActivities` answers nothing on Android 11+, so "is there anything
  that can open a link" says no on a phone with three browsers, and the icon
  hides itself for the wrong reason.

Two things deliberately not taken: zxart's music is per-author and not
per-game, so it is a catalogue shelf and never a game's music; and nothing here
offers pokes.

**zxart goes last in `Scrapers.all`**, after ScreenScraper and ZXInfo. A new
source should not outrank a proven one by default, and the order is the user's
to change.

## Manners

zxart's `robots.txt` **disallows every path this app touches**, and names
`ClaudeBot` with `Disallow: /`. The whole list, from `review/zxart/robots.txt`:
`/about/contact-us/`, `/simple`, **`/release`**, `/zxfile`, `/remote`,
`/banner`, `/api`, `/zipItems`, **`/file`**, `/print`, `/redirect`. This
section said `/api` alone until the branch's final review, and the two in bold
are why that mattered: every inlay, advert, instruction and map url is
`/release/id:…` and every game download is `/releasefile/…`, so the media half
of the feature is under a disallowed prefix too, not only the lookups. The API
is published, documented by the site's own author and served happily to an
identified client, and this app is a user-initiated client rather than a robot
— but the site has stated a preference, and the ZXInfo precedent says the
address can be lost and that an email lifts it.

So:

- **250ms between requests**, per host, and never a bare `curl` against it
  while working on this. This is the **API** calls — `ZxartApi`, through
  `Pace.before` — and not the media ones; see the gap below.
- **Nothing is fetched speculatively** — a page when the grid reaches it, a
  thumbnail when its row is on screen. No prefetch, no warming, no background
  sync. The difference between a client and a crawler is exactly this.
- **Known gap: media requests are not paced, and zxart serves them from the
  same host.** `Thumbnails` and `Downloads` fetch on `Work`'s shared pool —
  `max(4, cores)` lanes — with no `Pace` call, on an argument inherited from
  ZXInfo, where the API host and the picture hosts are different addresses.
  They are one address here, so a thirty-row page is a burst of concurrent
  unpaced requests at the address `ZxartApi` paces at 250ms. Stated rather
  than fixed: **the measurement is still to take** — how long a page of thirty
  covers takes at the current lane count against a paced alternative — and an
  interval chosen without it trades a usable grid for a guess.
- **The identity header is not optional.** `Http.Real` already sends
  `Zedex/<version>`.
- **Write to moroz1999** describing what the app does, how often it asks and
  why — a plan task, before the piece ships, not after a block. **Drafted
  2026-08-14**, not yet sent — the draft is
  `.superpowers/sdd/2026-08-14-zxart-integration/zxart-email-draft.md`, addressed
  to `moroz1999@gmail.com` (found in a quoted comment on the blog post named in
  the task brief, not published on zxart.ee itself — worth confirming before
  it goes out). Sending it is the maintainer's call; once sent, replace this
  line with the date.

`structureDateModified` filters, so a client that remembered when it last
looked could sync rather than browse. That is state to keep and staleness to
get wrong, and this design has no use for it; recorded because it is what to
reach for if a cached catalogue is ever wanted, and it would be a shame to
build one by polling.

## Testing

**Fixtures are the captured replies**, trimmed and committed as verbatim
constants the way `RECORD_WITH_RECORDING` already is. Where a boundary value
has to be invented, the fixture says it was invented. A fixture written from
memory pins the memory: three defects hid behind exactly that on the ZXInfo
branch, one of which made every entry unimportable while every test was green.

**On the JVM:** the URL grammar, including `start = page * size` and every
measured filter and order name; the paging arithmetic and `totalAmount` →
`total`; the leaf-to-root tree walk; the `Kinds` table against the nine
recorded roots, both directions; the `legalStatus` mapping; the HTML
unescaping; `knowsFormats` false and the sift hint never set; the md5 walk over
a real `releaseStructure`; the `hardwareRequired` table against its recorded
vocabulary.

**On a device:** the source chooser; the sort applied inside a shelf; a
Categories page carrying shelves *and* items; three imports — a game, a tune, a
picture — and the Open hand-over for the last two.

**Mutation-checked**, because each of these fails silently and leaves something
plausible on screen: the folder mapping, `votes` versus `rating` in the order,
the `start` stride, and the `legalStatus` mapping.

## Decisions, and what they were chosen against

| Decision | Rejected | Why |
|---|---|---|
| One client, three pieces | Three catalogues in the chooser | Splits one website's identity three ways and makes "search zxart" mean three searches |
| | One branch for everything | Unreviewable; the provider half depends on vocabulary the first piece measures |
| Source row in the tab | Both catalogues' shelves in one list | Two Search shelves, and every descent, page and filter has to carry which catalogue it came from |
| | A preference in Options only | Nobody who does not open Options learns there is a second archive |
| | A fifth tab | Puts a service's name in the app's chrome and grows with every future catalogue |
| Sort as a control | A "Top rated" shelf | A shelf gives Top over everything and not Top *inside* Games, which is what was asked for |
| Folder by category id | `categoriesString`'s first segment | Ties the folder to a language, so a Russian reader's imports would land in `Other` |
| | A table per language | Three vocabularies to record, and a fourth site language falls silently to `Other` |
| The user's language | Always English | This is a Russian-speaking community's archive and the app already speaks nine languages |
| Name search, md5-confirmed | Name only | Throws away the one thing this source can do that no other can |
| | Confirmed only | Declines every renamed or re-zipped file, which is most of what people have |
| `knowsFormats()` on the seam | Empty formats meaning "keep" | A filter that appears to work and does nothing |
| `legalStatus` translated | Passed through | Greys every row in the catalogue, and gives no reason for it |
| A YouTube link | The `videos` media folder | That folder holds an mp4 the gallery plays; a link is a hand-over |
| zxart last in the scrape order | First | A new source outranking a proven one by default, silently, on everybody's collection |

## Not in this piece

- **Bulk import.** One thing at a time; a queue is a different feature with
  different manners. Unchanged from the first catalogue's design.
- **Syncing on `structureDateModified`.** See *Manners*.
- **Author and party shelves.** `filter:authorId` works and zxart's parties are
  a real way people browse it; both are shelves, which means they are data and
  cost nothing to add later.
- **ZXInfo's `ALPHABETICAL` sort.** `sort=score_desc` was measured during the
  build and works, so ZXInfo ships declaring `TOP` alongside `DEFAULT` — this
  note said that was future work and it was overtaken by the implementation,
  which is corrected here rather than left to read as an outstanding task.
  What genuinely remains unmeasured is a **title** sort on that service: nobody
  has asked ZXInfo for one, so it declares no `ALPHABETICAL` and this design
  does not guess that it would work.
- **The 3,988 archive.org recordings** ZXInfo cannot reach. zxart carries an
  `rzx` for **47.9%** of prods against ZXInfo's reachable 1,353, so this piece
  improves that enormously by accident — but a recording zxart does not hold is
  still out of reach, and guessing archive.org paths from a title remains the
  wrong answer.
