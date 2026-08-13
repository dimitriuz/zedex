# Game details: one view, one action bar

**Date:** 2026-08-13
**Status:** approved, not yet implemented
**Step 1 of 3** — see *Follow-ups* for steps 2 and 3.

## The problem

A game's details are drawn in three places, and every one of them is its own
implementation of the same screen:

| | `GameInfoActivity` (681 lines) | `GameInfoView` (761 lines) |
|---|---|---|
| title, filename, facts, description | identical code | identical code |
| authors, price, series, compilations | yes | **missing** |
| gallery, viewer | yes | yes |
| video autoplay, measured cover sizing | **missing** | yes |
| `clear()`, `release()`, `setOnPlay`, `setOffersManual` | **missing** | yes |
| actions | three text buttons, top | two text buttons, bottom |

`show(Meta)` is character-for-character identical in both but for one line and
the extras block. `factsLine` exists in **four** files — those two plus
`DetailPane` and `CataloguePane`.

They diverged because they were written for different containers at different
times: `GameInfoView` had to be a `View` because a `Presentation` shows views
and a panel needs lifecycle hooks a screen does not, and `GameInfoActivity` came
later and grew the extras rows the panel never got. The panel silently not
showing authors, price, series or compilations was drift, not a decision.

## What this step does

One implementation — `GameInfoView` — with an action row the host configures.
`GameInfoActivity` becomes a thin shell around it. `SecondScreen` keeps its
current calls.

`GameInfoView` stays where it is, in `dev.ldlab.zedex.library.ui`. It is the
more capable of the two and already a `View`; moving it as well as merging into
it is churn for no gain, and the house rule is to extract first and move into
packages after.

What is left of `GameInfoActivity` afterwards, and nothing else:
`attachBaseContext` and the title (inherited from `ZedexActivity`), hiding the
action bar, reading the three intent extras, building one `GameInfoView`,
configuring its row from whether `EXTRA_URI` is present, `setContentView`,
`fitToSafeArea`, `showEntry`, and `onPause` → `release()`. Everything else it
owns today — `words`, `media`, `load`, `show`, `extra`, `titlesOf`,
`seriesLine`, `factsLine`, `append`, `openViewer`, `loadManualButton`,
`loadMusicButton`, `artworkHeight`, `wrap`, `pixels` — either moves into the
view or is already there in duplicate and is deleted.

## The action row

Built once, at the bottom of the **words** lane, *outside* the `ScrollView` —
which is the rule `GameInfoView` already states in its own comment and which
holds for every host: a description long enough to scroll must never carry the
button that starts the game out of reach with it.

The shape is `DetailPane`'s, which already has it: a text button for the primary
action taking the remaining width, then fixed 48dp icon buttons, in the order
action → manual → music.

    library : [      PLAY      ] [📖] [🎵] [‹]
    machine : [‹] [📖] [🎵] [☰] [✕]
    panel   : [      PLAY      ] [📖] [🎵]

### Who owns what

The view owns the row's **shape** and the two icons it can answer for itself.
The host owns **which other actions exist and what they do**. That split is what
keeps the view from needing to know about `EmulatorActivity` or `LibraryActivity`.

- `setPrimaryAction(int labelRes, Runnable)` — the text button, weight 1.
  Never called means no text button, which is the machine variant.
- `addLeadingAction(int icon, int descriptionRes, Runnable)` — 48dp, before the
  manual.
- `addTrailingAction(int icon, int descriptionRes, Runnable)` — 48dp, after the
  music.
- **Manual and music are the view's own**, not host-supplied: it is the view
  that runs the off-thread `Artwork.manual` / `Artwork.music` queries and
  reveals each icon only when the answer arrives. `setOffersManual(boolean)`
  keeps its existing meaning and callers — false where the borrowed quick bar is
  carrying the manual instead. It governs the manual only, not the music.

`addLeadingAction`/`addTrailingAction` deliberately mirror `QuickBar.addAction`,
which is the same idea already in this codebase.

### Per host

**`GameInfoActivity`, opened from the library** (`EXTRA_URI` present)

- primary: `library_play` → `ACTION_VIEW` to `EmulatorActivity` with
  `FLAG_GRANT_READ_URI_PERMISSION` and `EXTRA_LIBRARY_PATH`, then `finish()`.
- trailing: `ic_chevron_left`, described `menu_back` → `finish()`.

The last button stops being a text button reading "Library". **Its action does
not change** — the same `finish()`, and finishing this screen is what returns to
the library. `R.string.menu_back` already exists and is translated, so this adds
no new string and does not trigger the nine-files rule.

**`GameInfoActivity`, opened from the machine** (`EXTRA_URI` absent)

- no primary — the game is already running, so there is nothing to start.
- leading: `ic_chevron_left`, described `menu_back` → `finish()`. The machine is
  what is behind this screen, so finishing returns to the emulator. The icon
  changes from `ic_chip`.
- trailing: `ic_menu` → `EmulatorActivity` with `EXTRA_OPEN_MENU`, then
  `finish()`; then `ic_close` → `LibraryActivity` with `EXTRA_FROM_MENU`,
  `REORDER_TO_FRONT`, `NEW_TASK`, then `finish()`.

Back **leads** here and **trails** in the library variant. Deliberate: here it
is the primary reason to leave the screen, there it is the last resort after
Play.

`QuickBar` is no longer used by `GameInfoActivity` after this. It stays where it
is — `EmulatorActivity` owns it and lends it to the panel, untouched.

**`SecondScreen`** — primary `library_play` → the existing `onPlay` runnable, and
nothing else. Its `setOffersManual`, `setOnForeignScreen`, `setOnPlay`,
`setAutoplay`, `showEntry`, `clear` and `release` calls are unchanged.

## Nothing floats over the artwork any more

Both corner buttons go.

- **`GameInfoActivity.manualButton`** is removed. It is already dead: built, set
  `GONE`, and nothing ever shows it or gives it a click listener — a comment
  says it was "left built and hidden rather than deleted".
- **`GameInfoActivity.musicButton`** survives, in the row. Its
  `FrameLayout.LayoutParams` and the stacking offset that only existed to sit
  below the manual are deleted rather than adjusted.
- **`GameInfoView.musicButton`** likewise, including its own stacking offset
  which already made room for a manual button that is not there.
- **`GameInfoView.disc()`** becomes unused and is removed. It exists solely so a
  button sitting on artwork wins against both pale and dark box art; a button in
  a row needs no such thing.

## One conflict this forces, and how it is settled

The two screens put the media on opposite sides in landscape:
`GameInfoActivity` is words left / media right (weights 3 and 2);
`GameInfoView` is media left / words right. Merging means one of them changes.

**Resolved as words left, media right** — the activity's arrangement, and the
one the action row was described against ("at the bottom, left of the image").
**The panel therefore swaps sides.** That is the one deliberate visible change
to the second screen in this step, and it is called out here rather than
discovered on the device. It is not made a parameter: a knob for this is the
kind that multiplies, and one arrangement is the point of the exercise.

## Other behaviour the merge changes

Each of these is the activity inheriting something the view already does. None
is a regression, and all need looking at on the device:

- **The activity gains video autoplay**, following the same
  `libraryVideoAutoplay` preference the panel reads.
- **The activity gains measured cover sizing** (`applyCoverSize`) in place of
  its fixed `artworkHeight()` and `ARTWORK_TARGET_DP`.
- **The panel gains the extras rows** — authors, price, series, compilations,
  contents. This is the drift being corrected.
- `openViewer` keeps the view's version, which stops the video and announces
  nothing. That is load-bearing for the panel (announcing our own screen is what
  took the panel down for good once) and harmless in the activity.
- `setOnForeignScreen` is never set by the activity, so it stays null and
  nothing is announced.

## What is deliberately not changed

- What any button does. Only where the buttons sit and what they look like.
- `EmulatorActivity`'s own quick bar, `applyBarMode`, `showOnly`,
  `showAllExcept`.
- `DetailPane` — step 2.
- `CataloguePane` — step 3.
- `GameInfoActivity`'s two-ways-in rule: `EXTRA_URI` present or absent decides
  which bar the screen wears, and the panel follows the same rule. The rule
  stands; only what each bar looks like changes.
- The three intent extras `EXTRA_PATH`, `EXTRA_NAME`, `EXTRA_URI` and both
  callers (`LibraryActivity.showInfo`, `EmulatorActivity`'s ⓘ).

## Testing

- `DetailPaneTest` covers the library's pane, untouched in this step. It should
  keep passing without modification, and is the check that nothing shared moved
  under it.
- Device verification on the AYN Thor Lite for all three hosts — details from
  the library, details from the machine's ⓘ, and the panel — **in both
  orientations**, since the row's position depends on the words lane and the
  lane swaps ends between them.
- A game with **no manual and no music** in each host: both icons' whole
  behaviour is being hidden until an off-thread answer arrives, and offering a
  manual or a tune for a game that has neither is the failure this most easily
  introduces.
- A game **with** music, chosen deliberately — a tune belongs to about one game
  in fifty, so it will not be met by chance.
- The panel specifically, for the two changes it takes: the extras rows arriving
  and the media swapping sides.

## Follow-ups, named here so they are not designed twice

**Step 2 — `DetailPane` adopts the shared view, condensed.**
The pane leaves out the description *on purpose*, and it cost something to
learn: "a description does not fit on one line, and the version of this that
tried ran out of room in landscape and squeezed itself down to 26px — a scroll
bar with no room to scroll in." So condensed is a content decision, not a
smaller copy: facts that fit on one line each, no description, no extras, a
weighted spacer holding the buttons at the foot, cover about two fifths of the
pane's height. A density parameter is introduced **then**, not now — step 1 has
one density and an enum with one value in it is not a design. `DetailPane` keeps
`Entry`, containers, `Host`, `refreshFacts`, `standDown` and the ⓘ button; only
its "this entry is a game" rendering moves.

**Step 3 — one `factsLine`.** Four copies today, including `CataloguePane`'s,
which describes catalogue items rather than local games and may not fit the same
helper. Worth checking rather than assuming.
