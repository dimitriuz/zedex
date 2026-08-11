# Scraping from several sources, in a priority order

A scrape asks one service today. This makes it ask several, in an order the
user sets, each one filling in what the ones before it left out.

## Why, and why the last answer was different

`Scrapers`' class doc currently says merging sources was considered and
rejected:

> Merging fields from two was considered and rejected: two sources disagreeing
> about a name or a year needs a rule per field, ownership stops being one
> provider name, and every conflict is invisible when it goes wrong.

That was three objections, and this design answers each rather than ignoring
them:

- **A rule per field** — there is one rule for every field: a source may fill a
  gap and may never overwrite. Priority order decides who gets the gap.
- **Ownership** — `Meta.source` becomes a list of contributors instead of one
  name. Ownership is still legible; it is simply plural.
- **Invisible conflicts** — the only conflict a user can lose anything to is a
  picture, and a picture is the one thing that can be shown. Contested artwork
  is never overwritten silently: a sweep declines it, and a one-game scrape
  puts the alternatives on screen side by side.

The reversal is therefore not "we decided the risk was acceptable". It is that
the three things that made merging dangerous do not arise under fill-gaps-only
with an explicit priority.

## What the user gets

- Settings names which sources to use and in what order.
- A scrape consults them in that order and takes what is missing from each.
- A sweep never replaces artwork that is already there, so it costs no media
  requests for a game that already has its pictures.
- A one-game scrape fetches from every enabled source and, where a new picture
  differs from the one on disk, shows both and asks which to keep.

## Decisions taken, with their alternatives

| Question | Decided | Rejected |
|---|---|---|
| How much provenance does the store keep? | One line of contributor names | Per-field provenance (a store format change for re-scrape precision nothing yet needs); a single first-contributor name (loses which services were asked) |
| What does re-scraping do to text? | Always top up, never overwrite | A "rebuild this row" mode; replace-for-one-game, top-up-in-a-sweep |
| How is an image conflict asked about? | One review sheet at the end of a one-game scrape | A modal per contested image; keeping every version in the folder |
| How does a later source identify the game? | By the title an earlier source gave; one exact title match counts as certain | Hash matches only (throws away most of a second source); ask for every source (a dialog per source per game) |
| When does a sweep stop consulting sources? | Only when every field is filled and every wanted folder has a file | A "well described" bar; always ask everything |
| Where does the multi-source loop live? | An orchestration layer above `Provider` | A composite `Provider` (hides the per-source attribution the feature exists to show); merging in `Metadata` (every reader in the app pays) |

## The store

`Meta.source` becomes a comma-joined list of contributors, in the order they
contributed — `"ZXInfo, ScreenScraper"`, `"esde, ZXInfo"`,
`"user, ZXInfo, ScreenScraper"`. No file format change and no migration: an
existing single-name row already reads as a one-element list.

New on `Meta`:

```java
public List<String> sources()   // split on ", ", empty when null
```

Three predicates read it, each generalised so its present meaning survives:

| | today | with a list |
|---|---|---|
| `isEsde()` — may an ES-DE link replace this row? | `source == null \|\| "esde"` | ES-DE is the **only** contributor |
| `isMine()` — did somebody type this? | `source == "user"` | `"user"` is **among** the contributors |
| `Sweep.Only.NOT_SCRAPED` | `isEsde() \|\| isMine()` | **no provider name** among the contributors |

`Meta.with(Field, value)` prepends `"user"` rather than replacing the whole
value, so a hand edit stops erasing the record of which services were asked.

### Two consequences, accepted deliberately

- **`Scrape.wouldOverwriteAHandEdit` and its confirmation dialog go.** Under
  fill-gaps-only a scrape cannot overwrite a hand edit — a typed value is
  non-null, so nothing touches it. `Sweep.Tally.yours` loses its meaning with
  it, so one counter and its string come out of nine language files.
- **A field somebody deliberately cleared will be refilled.** `Meta.Builder`
  turns `""` into null, so "I deleted this wrong description" is
  indistinguishable from "nobody ever wrote one". Distinguishing them means
  tombstones in the store; the consequence is recorded instead of built.

## `Scrapers` — which sources, in what order

`Scrapers` grows from "which provider a scrape uses" to "which ones, in what
order". It stays the one place that knows there is more than one.

```java
List<Provider> enabled(Context)   // in priority order, with the user's account applied
List<Provider> all(Context)       // everything this build has, for the settings screen
void           save(Context, List<String> namesInOrder)
boolean        any(Context)       // unchanged in meaning: can anything be scraped at all
Provider.Wanted wanted(Context)   // unchanged
```

`preferred()` and `withAccount()` collapse into `enabled()`, which applies the
ScreenScraper account exactly as `withAccount` does today — the account is
ScreenScraper's alone and changes nothing for any other source.

### The preference

`Prefs.KEY_SCRAPERS`, a `String`: the **enabled** source names in priority
order, newline-joined.

- A `String` and not a `StringSet` because a set has no order, and order is the
  feature.
- A new key and not a reuse of `KEY_SCRAPER`, which was written with
  `putString` as a single name — `scripts/check-prefs.py` exists to catch that
  class of collision, and the two keys must not disagree about what they hold.

**Migration is faithful, not generous.** A stored `KEY_SCRAPER` becomes that
one source enabled and every other source *off*. Widening what the app fetches,
and what it spends a ScreenScraper allowance on, is not a decision to make on
somebody's behalf because a feature arrived. Nothing stored at all — nobody
ever chose — becomes every source enabled in `Scrapers.all()` order, which is
the new default. `KEY_SCRAPER` is read for migration and never written again.

## `Blend` — one game across the ordered sources

A new class in `library/scrape`, with no screen in it: the plural counterpart to
`Scrape`, and the same two-call shape, for the same reason — everything that
needs a person happens between the two calls.

```java
Blend.Result run(Context, List<Provider> sources, Http, Entry, String path,
                 Provider.Wanted wanted, Media media, Chooser chooser);

/** Installs the chosen staged media: at most one per folder, and a folder
 *  named by none of them keeps whatever is already there. */
void commit(Context, String path, List<Staged> chosen);
```

```java
enum Media { FILL_GAPS, OFFER_ALTERNATIVES }

interface Chooser {
    /** Null to skip this source for this game. Called on the worker thread. */
    Candidate choose(String sourceName, List<Candidate> found, String game);
}

final class Result {
    public final Meta meta;              // merged, already stored by run()
    public final List<Staged> media;     // in priority order
    public final List<String> consulted; // source names that contributed
    public final List<ScrapeException> failures;   // one per source that threw
}

final class Staged {
    public final String folder;      // "covers"
    public final String extension;
    public final File   file;        // in the staging area
    public final String source;      // which provider offered it
    public final boolean contested;  // something is already there, and differs
}
```

### The loop, per game

```
known = the stored row for this path, or empty
for each source, in priority order:
    if media == FILL_GAPS and nothing left to gain: stop
    candidates = source.search(game)      first source: the filename
                                          later sources: known.name, when there is one
    chosen =  certain(candidates)                                      -> use it
           |  exactly one candidate whose title equals known.name      -> use it, no dialog
           |  chooser.choose(source, candidates)                       -> may be null: skip
    scraped = source.fetch(chosen, wantedFrom(source))
    known   = merge(known, scraped.meta)         known wins, field by field
    stage(scraped.media)                         never over the real file
store known
```

**Searching a later source by title** needs no change to any provider. Both
derive their search term from `Provider.Game.filename()` — ZXInfo through
`ZxInfo.titleOf`, ScreenScraper as `romnom` — so it is a decorator around
`Game` whose `filename()` answers the known title. `Candidate.name` is what the
exact-title comparison reads.

**Title comparison** is case-insensitive and trims, and nothing more. Anything
fuzzier is a guess, and a guess acted on silently is one game's cover on
another for ever.

**`wantedFrom(source)`** is the only place the two policies differ:

- `FILL_GAPS` (sweep) — only the folders with nothing in them yet, so a source
  is never asked for a cover that exists. That is the whole of "do not rewrite
  artwork we already have", and it is also why it costs no request.
- `OFFER_ALTERNATIVES` (one game) — every wanted folder from every source. The
  sheet cannot offer a choice it did not fetch.

**Stopping early** applies to `FILL_GAPS` only: nothing left to gain means every
`Meta` field a provider can supply is filled — everything but `path`, which is
the key, and `source`, which is ours — and every wanted folder has a file.
`OFFER_ALTERNATIVES` never stops early: collecting the alternatives is what it
is for.

**A hash beats a title.** A later source that is certain is used even when its
title differs from the one already known — a hash match is the file itself, and
a title is what somebody typed on a shelf. The disagreement is logged and
nothing more; the merged row keeps the earlier name, because the earlier source
had priority.

**Who writes `source`.** `Blend` does, at the end of the loop: the contributors
already on the row, in the order they are already in, then each source that
contributed something this time and is not already listed. So `"esde"` becomes
`"esde, ZXInfo"`, and a hand-edited row stays `"user, …"` with the new source
appended. A source consulted that contributed nothing is not listed — the field
records who wrote something, not who was asked.

### The merge

One method, `merge(base, addition)`: for each field, `base` wins unless it is
null; a list wins unless it is empty. Built through `Meta.but()`, never a
positional constructor.

Nineteen fields and growing, and a field added to `Meta` later and forgotten
here would silently never merge — the exact failure `Meta`'s own class doc was
written about. The test is therefore reflective: build a base with everything
null and an addition with everything set, merge, then walk `Meta`'s public
fields — all but `path` and `source`, which `Blend` sets itself — and assert not
one came back null. A new field cannot be forgotten without that test going red.

### Staging

`OFFER_ALTERNATIVES` writes every medium into
`<media>/.staging/<folder>/<stem>.<ext>` and moves it into place only at
`commit` — contested or not, since whether it is contested is only knowable
once it has been downloaded.

`FILL_GAPS` writes straight to `Artwork.fileFor` and never stages. It only ever
asks for folders that are empty, so there is nothing there to protect and
nothing to choose between; staging would be a copy per game per medium bought
for nothing. The two differ by which `Destination` they are handed and in no
other way.

Three small changes make the staging possible:

- `Downloads` takes a `Destination` (`File fileFor(folder, extension)`) instead
  of calling `Artwork.fileFor` itself — including on the `.scr` conversion and
  the music unzip, which call it directly today.
- `Artwork.forget(path)` moves out of `Downloads.fetch` into its callers, since
  staging must not invalidate the cache. `Scrape.apply` calls it in a `finally`,
  keeping the existing guarantee that what did arrive becomes visible even when
  the rest was refused.
- `commit` deletes every other extension for that stem in the folder before
  moving. Without it a new `covers/X.jpg` sits beside the old `covers/X.png` and
  `Artwork` goes on showing the png, so the choice looks as though it did
  nothing.

**Leftovers are cleared on the way in, not on the way out.** A scrape killed
mid-flight never reaches its cleanup; the same lesson `RecentsTest.dropAnyLeftOver`
is in the repo for.

**Contested** means something is already in that folder and it is not the same
bytes — compared by length, then MD5. Identical bytes are dropped without a
word: two sources with the same scan is common and is not a question worth
asking.

### Errors

Per source, not per game. A source that throws is logged and recorded in
`Result.failures`, and the loop moves to the next one; the game gets whatever
the sources that did answer gave it. A game is a failure only when every source
failed.

In a sweep, a quota, credential or closed-service refusal disables **that
source** for the rest of the run rather than stopping the run: ZXInfo has no
quota and no reason to stop because ScreenScraper ran out. The run stops when no
source is left standing.

## The one-game flow

```
worker:  Blend.run(..., OFFER_ALTERNATIVES, chooser)
             chooser blocks on a latch for candidate dialogs, exactly as
             Sweep.Watcher.chooseFrom already does
   UI:   nothing contested  -> commit every staged medium, and the toast as today
         something contested -> the sheet
worker:  Blend.commit(path, what the sheet chose)
```

"Nothing contested" still commits: an uncontested medium is one nothing was
holding, and it has still only been staged.

The download is finished before the sheet appears, so nothing blocks on the
sheet.

### The sheet

`ArtworkChoice`, a new class in `screen`, building a dialog — not an activity.
It is a step inside a flow `LibraryActivity` already owns, and an
activity-for-result would mean marshalling staged files through an `Intent` for
no gain.

A `ScrollView` of one row per contested folder; each row is the folder's name
and a horizontal strip of tiles, exactly one selected:

```
covers          [ yours ] [ ZXInfo ] [ ScreenScraper ]
screenshots     [ yours ] [ ZXInfo ]
titlescreens              [ ZXInfo ]        <- nothing there before; preselected
                                    [ Cancel ]  [ Save ]
```

It generalises to any number of sources without redesign.

- **Preselection**: whatever is already on disk stays selected, so Save with
  nothing touched changes nothing — the safe default is the no-op. A folder that
  was empty preselects the highest-priority source that offered one, since there
  is nothing to lose there.
- **Cancel** discards the staging area and leaves the facts already written.
  `Blend.run` stores the merged row before the sheet, the same way
  `Scrape.apply` writes facts before media today and for the same reason: a
  scrape that got the metadata has still improved the row.

Two constraints the tests will hold it to, both already paid for in this repo:
the tiles are `ImageView`s, so UI Automator must match `android.widget.ImageView`
however they are subclassed; and no tile's `contentDescription` may change after
layout.

## The sweep

`Sweep.run` takes `List<Provider>` where it took one, and calls `Blend.run` with
`FILL_GAPS`. The existing `Conflicts` enum becomes the `Chooser`'s
implementation — `SKIP` returns null, `BEST` returns the first candidate, `ASK`
posts the dialog and waits — and it applies **per source**, which is the same
question it always asked, asked of each in turn.

`Tally` keeps its shape minus `yours`.

`Only` keeps all three values. Under fill-gaps-only, `EVERYTHING` means "re-ask
every source, and take media for folders that are empty", so it now differs from
`NOT_SCRAPED` only in also revisiting games this app has already scraped. That
is a thinner distinction than it has today and is the honest consequence of the
rule chosen. The value stays and its description is reworded; removing an option
people have used would be worse.

### Cost

Today's estimate is exact: `games × provider.costPerGame(wanted)`. It cannot be,
once the media a run fetches depend on which folders happen to be empty. It
becomes an **upper bound**, summed over the enabled sources, and
`ScrapeManyActivity` says "at most" rather than printing a number that is now
wrong high. One string, nine files.

The per-source quota lines stay per-source: they are different currencies and
always were — a ScreenScraper cover is a `mediaJeu.php` call against the day's
allowance, a ZXInfo cover is a static file.

## Settings

The `ListPreference` becomes a `Preference` opening a custom dialog: a list with
a checkbox and up/down arrows per row. Arrows rather than drag — with two or
three entries drag buys nothing, and arrows are reachable by a pad and by a
screen reader, which drag is not.

The summary reads `"ZXInfo, then ScreenScraper"`, or `"ZXInfo only"`, so what is
configured is legible without opening it. The row stays hidden when the build
has only one source, as it is today.

## Tests

**JVM (`app/src/test`)**, against the existing fake `Provider` and `Http`, no
network:

- `BlendTest` — the merge rule; the reflective every-field-is-merged test;
  stopping early; a later source taking a single exact title match without a
  dialog and asking when there are three; contested detection, including the
  identical-bytes case that must *not* ask.
- `ScrapersTest` — order, enabling and disabling, and the migration from
  `KEY_SCRAPER` in both directions (a stored single name; nothing stored).

**Instrumentation (`app/src/androidTest`)**:

- `ArtworkChoiceTest` — a contested cover, choosing the new one, asserting the
  file on disk changed *and* that the old extension is gone; and that Save with
  nothing touched changes nothing.
- Existing sweep and download tests updated for the list signature.
- A two-source sweep where source #1 supplies the cover makes **no** media
  request of source #2 — the thing only a device run can prove, and the whole
  point of `FILL_GAPS`.

**Scripts**: `scripts/check-strings.py` and `scripts/check-prefs.py` clean. New
strings in nine languages; the removed `yours` string out of all nine.

## Docs

- `docs/INTERNALS.md`, the *Scraping* section — the loop, the merge rule, the
  staging area.
- `docs/LIBRARY.md` — a line on what a scrape now does.
- `README.md` — one line, in its own register.
- `CLAUDE.md` — the rules this design turns into operational knowledge: the
  contributor list and the three predicates that read it; fill-gaps-only;
  staging and the delete-the-loser rule.
- `Scrapers`' class doc — corrected rather than deleted. It currently states
  that merging was considered and rejected; it must say what changed and why the
  reversal is safe, so the next person finds the reasoning and not just the
  reversal.

## Out of scope

Stated so it is not discovered later:

- Per-field provenance.
- A "rebuild this row from scratch" action.
- Keeping more than one picture per folder.
- Choosing artwork during a sweep.
- Any new provider.
