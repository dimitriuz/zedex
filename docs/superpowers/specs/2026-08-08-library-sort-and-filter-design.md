# Library: sorting and filtering by scraped metadata

Design, 8 August 2026. Approved in conversation; see the decisions section for
what was chosen against what.

## Why

The library knows a great deal about a scraped collection and sorts by three
things, none of them scraped: name, file date, size. On the collection this was
designed against — 803 games — the store holds a genre for 693 of them, a
developer for 673, a publisher for 758, a release date for 709 and a rating for
326. None of it can be sorted or filtered by, so finding "the Ocean platformers
worth playing" means knowing which they are already.

## What is being built

**Sorting** gains three fields and loses one. The list becomes:

| Field | From | Notes |
|---|---|---|
| Name | the entry | unchanged, still the default |
| Size | the file | unchanged |
| Released | `Meta.released` | the year, as `Meta.year()` already derives it |
| Format | the file's extension | groups `tzx` together, then `z80`, then `tap` |
| Rating | `Meta.rating` | ES-DE's 0–1 fraction, compared as a number |

**File date is removed.** It was one of two things called a date once "Released"
existed, and it is the one nobody asked for. See *Migration* — the preference
may still say `date`.

**Filtering** is new: five fields, reached from a `Filter` button in the toolbar
by touch and from the options dialog by gamepad — see *Two ways in, and they
are not the same widget* below.

| Field | Shape | Distinct values in the reference collection |
|---|---|---|
| Format | multi-select | 7 |
| Genre | multi-select, comma-split | 63 raw, roughly 25 after splitting |
| Rating | threshold: 3+, 3.5+, 4+, 4.5+ | — |
| Developer | multi-select, searchable | 277 |
| Publisher | multi-select, searchable | 196 |

Every list is built from the collection itself, with a count beside each value.
Nothing is offered that would match nothing.

Genres are split on commas before being offered: ES-DE writes compound values
like `Racing, Driving`, and a person looking for racing games should not have to
know which other genre it was filed beside.

Filters combine **AND across fields, OR within a field** — `Format ∈ {tap, tzx}`
AND `Genre ∈ {Platform}`. That is the combination people actually want and the
only one worth the ambiguity of explaining.

## One way in

The toolbar had a button each for sort, for the list/grid view, and — after the
first cut of this design — one for filter as well. Three buttons, two of which
opened different widgets: an Android `PopupMenu` for sort, a direct toggle for
the view, and a sheet for the filter. A gamepad reached none of them, and had
`OptionsDialog` instead, which held sort and view but no filter.

That is now one **Options** button, opening the same dialog for touch and for a
pad:

```
View   ▸        List · Grid
Sort   ▸        Name · Size · Released · Format · Rating, and the direction
Filter ▸        Format · Genre · Rating · Developer · Publisher
```

Each row opens a page of its own and says what it is currently set to — `Sort ▸
Rating, highest first` — so checking without changing is one tap. Filter says
how many fields are set, or nothing when none are. Back goes up a page, and
dismisses from the top; that is the shape the emulator's ☰ sheet already uses,
and the rule CLAUDE.md records for it: a row that was a dialog is a page now,
and it commits by its own name rather than by an OK button.

**What this costs, honestly.** Flipping between list and grid was one tap and
becomes three. That is the price of not having a toolbar that grows a button
per option, and of the pad and the finger reaching the same thing — a filter
that only touch could open, or a view toggle only a pad could not reach, is the
sort of split that ends with two implementations disagreeing.

**What it buys** is that there is one widget to build, one to test, and one
place where "what is the library showing" is answered. The earlier design had
the filter sheet and the dialog's filter page as one widget entered at two
points precisely to avoid that drift; with one entry point the problem does not
arise.

## Behaviour

**A filter flattens the library.** With any filter on, the list is every match
from the content folder down, ignoring folder structure. This is the whole point:
in a collection nested five deep, "Shooters rated 4+" is not a question about the
folder you happen to be standing in.

**An active filter is always visible.** The breadcrumb is replaced by the active
filter as chips — `Platform · 4+ ×` — and one tap clears. The empty state says a
filter is hiding things rather than "nothing here", because a filter that has
excluded everything looks exactly like a library that has lost its games. The
review of this app found the same failure shape twice: a silent empty answer that
is indistinguishable from a broken one.

**Filters last the session; sort lasts for ever.** Deliberately inconsistent.
Sort is a preference — how you like your library. A filter is a question you are
asking now, and the person most likely to meet a forgotten one is the person who
set it weeks ago and has since concluded the app is broken.

**Unknown values sort last, in both directions.** A game with no rating is not a
game rated zero. Sorting by rating descending must open on the best games, and
reversing it must not bury them under 477 unrated ones. The same rule covers a
missing release year and a missing genre.

**Folders still sort before files** in an unfiltered Browse, as today. A filtered
list is flat and has no folders in it.

**Filtering applies to Browse only.** Favourites and Recent are already
answers to a question — what you marked, what you played — and narrowing them
further is a different feature. The Filter button is hidden in those tabs rather
than shown and ignored, and switching to one of them does not clear a filter set
in Browse: coming back finds it as it was left.

## Where the code goes

**`library/Filters`** — new, and the heart of it. Holds what is currently
selected and answers `matches(Entry, Meta)`. Pure logic: no Android types, no
`Context`, no views. It owns the comma-splitting, the AND/OR combination and the
threshold comparison, so those are one thing in one place rather than conditions
spread through the listing code.

**`library/meta/Metadata`** gains a way to walk the store once and return the
distinct values and counts per field. One walk, off the UI thread, feeding every
list in the filter sheet.

**`library/ui/OptionsDialog`** — the gamepad path — grows the three pages and
the way back between them. It already builds a column of choosable rows and
already shows which one is current; what it has no notion of is depth, and that
is what this adds. The filter value lists are the same row-building code one
level further in, not a second widget.

**The filter sheet the toolbar button opens** is that same row-building code
again, entered at the Filter page rather than at the top. One widget, two
starting points - not a second implementation of a value list that could drift
from the first.

**`screen/LibraryActivity`** gains a `Filters` field, a call to it where the
listing is assembled, and the chip row. It does not gain the matching logic. The
file is 2,600 lines and the review flagged it for exactly this; this feature adds
a collaborator rather than a sixth responsibility.

**The flattened walk** reuses what `Listing` already does recursively for search
rather than growing a second traversal.

## Testing

`Filters` is pure logic over `Entry` and `Meta`, which makes it the first thing
in this app that can be unit-tested on the JVM with no device and no refactoring
— the gap the code review called the highest-value one open. This spec therefore
also creates `app/src/test` and the `testImplementation junit` dependency.

Worth pinning:

- comma-splitting, including `Racing, Driving` and stray whitespace
- AND across fields, OR within one
- the threshold boundary: ES-DE's `0.9` scales to exactly `4.5`, so the `4.5+`
  threshold must include it — a comparison that is accidentally strict loses
  every top-rated game in the collection
- unknown sorts last, in *both* directions — the case that is easy to get wrong
  by implementing it as a comparator that simply reverses
- a filter matching nothing returns empty rather than everything

Device-level checks stay in the instrumentation suite: that the chips appear,
that clearing restores the folder you were in, that each of the dialog's three
pages opens and goes back, and that an unrecognised stored sort field does not
leave a blank row. The one worth writing deliberately is that both ways in reach
the same state — a filter set through the toolbar shows as set in the gamepad
dialog, and the reverse.

## An unrecognised stored sort

There is no migration to write: nothing is publicly released, so no device holds
a `librarySort` this build cannot read except a bench that chose the old file
date.

The fallback matters anyway, as correctness rather than as a migration story: a
stored sort field that no longer exists must resolve to Name, not to nothing.
This codebase has drawn a blank settings row exactly that way before, from a
stored value with no matching entry. `scripts/check-prefs.py` still applies —
`librarySort` is written as a String and must stay one.

## Decisions, and what they were chosen against

**Flattened rather than folder-scoped filtering.** Filtering within the current
folder is more predictable and nearly useless: one folder rarely holds enough
games for a genre to narrow anything.

**A filter sheet rather than a smarter search box.** Extending the existing search
to match any field is far cheaper and cannot express "rating above 4", cannot
combine two fields, and is undiscoverable.

**Developer and publisher get searchable lists** rather than being reachable only
by tapping a value in the details pane. The pane gesture is a good idea and can
come later; on its own it means you cannot ask for Ocean's games without first
finding one.

**File date removed rather than renamed.** Renaming it to "File date" would have
kept a sort nobody asked for and cost nine translations to relabel.

**Three pages rather than one longer list**, in the gamepad dialog. Sort and
view already share an unlabelled column; five filter fields would have made it a
dozen rows with no grouping. Pages also give each group somewhere to say what it
is currently set to.

**A toolbar button rather than pages for touch too.** Touch never opens that
dialog, so putting Filter only there would have hidden the feature from everyone
without a controller. A button is also one tap where a page is two.

## Not in this change

- Tapping a developer in the pane to filter by it. A natural follow-on, and not
  needed for the sheet to be useful.
- Saved filters, or a "smart folder" concept.
- Filtering by players, or by whether a game has artwork at all.
- Sorting inside the Favourites and Recent tabs by the new fields. They already
  have their own order — added, and last played — and changing that is a separate
  question.
