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
new strings, which is nine files each. ZXInfo declares `DEFAULT` only and its
row stays hidden until its own `sort` vocabulary has been measured — it is
real, its Newest shelf uses it, but it has not been measured here and a spec
does not guess.

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

**`zxMusicSearch` and `zxPictureSearch` are unmeasured.** If they are ignored,
the Search sub-shelf is not declared for that entity, which costs a shelf and
nothing else.

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

`fetch` is two requests and maps by **rule, not position**:

| Folder | From |
|---|---|
| `titlescreens` | the `zximages/…` entry of `imagesUrls` — the rendered loading screen |
| `screenshots` | the `/screenshot/…` entries of the same array |
| `covers`, `backcovers`, `physicalmedia` | `inlays`: front first, then the `_Back` and `_Media` suffixes — a heuristic, so it gets a fixture and an unrecognised name falls through rather than guessing |
| `maps` | `maps` |
| `adverts` | `ads` |
| `manuals` | `instructions`, which are `.txt` — already a manual extension, and `InstructionsActivity` renders one properly rather than letting another app re-wrap it |

**`hardwareRequired` is the prize.** `["zx128","ay","kempston","int2_2"]` is a
*stated* machine and interface where `Suggested` infers them from a genre and a
machine-type string. It gets the same discipline as the tables it joins: a
recorded vocabulary, asserted in both directions, and an unknown value mapping
to nothing. **Measuring that vocabulary is a plan task, not a spec guess** —
the ZX81-16K mistake was exactly a table written from one collection.

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

zxart's `robots.txt` **disallows `/api` for every user-agent**, and names
`ClaudeBot` with `Disallow: /`. The API is published, documented by the site's
own author and served happily to an identified client, and this app is a
user-initiated client rather than a robot — but the site has stated a
preference, and the ZXInfo precedent says the address can be lost and that an
email lifts it.

So:

- **250ms between requests**, per host, and never a bare `curl` against it
  while working on this.
- **Nothing is fetched speculatively** — a page when the grid reaches it, a
  thumbnail when its row is on screen. No prefetch, no warming, no background
  sync. The difference between a client and a crawler is exactly this.
- **The identity header is not optional.** `Http.Real` already sends
  `Zedex/<version>`.
- **Write to moroz1999** describing what the app does, how often it asks and
  why — a plan task, before the piece ships, not after a block.

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
- **ZXInfo's own sorts.** Its `sort` parameter is real and unmeasured here.
  Measuring it and declaring the same three is a small task, not a design.
- **The 3,988 archive.org recordings** ZXInfo cannot reach. zxart carries an
  `rzx` for **47.9%** of prods against ZXInfo's reachable 1,353, so this piece
  improves that enormously by accident — but a recording zxart does not hold is
  still out of reach, and guessing archive.org paths from a title remains the
  wrong answer.
