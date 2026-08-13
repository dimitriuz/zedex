# Game details: one action bar, at the bottom of the words

**Date:** 2026-08-13
**Status:** approved, not yet implemented

## What this changes

The game's details are shown in three places, and each of them ends its actions
differently: a row of three text buttons across the top, a `QuickBar` of four
icons across the top, and a 50/50 row of two text buttons at the bottom — with
manual and music additionally floating over the artwork's corner in two of them.
This makes all three the same shape: a text button for the primary action taking
the remaining width, then fixed 48dp icon buttons, at the bottom of the words
lane. Nothing floats over the artwork any more.

That shape is not invented here. `DetailPane`, the library's own right-hand
pane, already ends in exactly it — the action button, then ⓘ, 📖 and 🎵 as 48dp
icons, in that order. This is the other two places following the one already in
the codebase, and the icon order below is `DetailPane`'s.

## Where the row goes

At the bottom of the **words** lane, *outside* the `ScrollView`.

`GameInfoView` already states the reason in its own comment and it holds for all
of them: a description long enough to scroll must never carry the button that
starts the game out of reach with it.

In landscape this puts the row under the text and beside the image, which is
what was asked for. In portrait, where the media is above and the words below,
the same code lands it at the bottom of the screen at full width. There is no
orientation special case — the row is a child of the words lane and the lane is
what moves.

Note the two screens disagree about which side the words are on:
`GameInfoActivity` puts words left and media right (weights 3 and 2);
`GameInfoView` puts media left and words right. Neither changes here. "Left of
the image" is a description of `GameInfoActivity` in landscape, not a new rule.

## The three places

### 1. `GameInfoActivity` opened from the library — `EXTRA_URI` present

    [      PLAY      ] [📖] [🎵] [‹]

- `actionRow` moves from the top of the outer column to the bottom of the words
  lane inside `page()`.
- Play stays an `android.widget.Button` with `R.string.library_play`, weight 1,
  taking whatever width the icons leave.
- Manual becomes a 48dp `ImageButton` with `ic_manual`, keeping its current
  behaviour exactly: built hidden, revealed only when `loadManualButton`
  answers that this game has one.
- Music becomes a 48dp `ImageButton` with `ic_music` in the row rather than over
  the artwork, on the same terms: built hidden, revealed only when
  `loadMusicButton` finds a scraped `.ay`. Its action is unchanged —
  `EmulatorActivity` with `EXTRA_MUSIC`, because a tune is the Spectrum running
  the game's own driver and that is where the Spectrum is.
- The last button stops being a text button reading "Library" and becomes a 48dp
  `ImageButton` with `ic_chevron_left`, described as `R.string.menu_back`
  ("Back"). **Its action does not change** — it is the same `finish()`, and
  finishing this screen is what returns to the library.

`R.string.menu_back` already exists and is already translated, so this adds no
new string and does not trigger the nine-files rule.

### 2. `GameInfoActivity` opened from the machine — `EXTRA_URI` absent

    [‹] [📖] [🎵] [☰] [✕]

- The `QuickBar` at the top of the outer column is replaced by the same row in
  the same place as variant 1. Five 48dp icons, no Play — the game is already
  running, so there is nothing to start.
- Every action is unchanged:
  - `‹` — `finish()`. The machine is what is behind this screen, so finishing
    returns to the emulator. The icon changes from `ic_chip` to
    `ic_chevron_left` so both variants say "back" the same way; the content
    description becomes `R.string.menu_back`.
  - `📖` — `openTheManual()`, hidden until resolved, as now.
  - `🎵` — as in variant 1, hidden until resolved. `loadMusicButton` is already
    called for both variants, so this needs no new condition.
  - `☰` — `EmulatorActivity` with `EXTRA_OPEN_MENU`, then `finish()`. A sheet
    built over another activity's window is not something this activity can
    raise, which is why it asks rather than opens.
  - `✕` — `LibraryActivity` with `EXTRA_FROM_MENU`, `REORDER_TO_FRONT` and
    `NEW_TASK`, then `finish()`.

Back **leads** here and **trails** in variant 1. That is deliberate, not an
oversight: here it is the primary reason to leave the screen, and there it is
the last resort after Play.

`QuickBar` is no longer used by `GameInfoActivity` at all after this. It stays
where it is — `EmulatorActivity` owns it and lends it to the panel, which is
untouched.

### 3. `GameInfoView` — the second screen

    [      PLAY      ] [📖] [🎵]

- `rowManual` stops being a `Button` with `R.string.library_manual` and becomes
  a 48dp `ImageButton` with `ic_manual`, described by the same string.
- `musicButton` moves out of the cover box and into the row as a 48dp
  `ImageButton`, keeping `ic_music`, its description and its behaviour.
- The row goes from two weight-1 children to one weight-1 child (Play) and two
  fixed 48dp children.
- `setOffersManual(boolean)` keeps its current meaning and its current callers:
  false hides the manual because the borrowed quick bar is carrying it instead.
  It governs the manual only, not the music.
- The panel's borrowed quick bar is not touched.

## Cleanup that comes with it

**`GameInfoActivity.manualButton`** — the `ImageButton` over the top-right corner
of the artwork — is removed. It is already dead: built, set `GONE`, and nothing
ever sets it visible or gives it a click listener. A comment on
`loadManualButton` says it was "left built and hidden rather than deleted". With
the bar carrying a 📖 icon there is no future in which it comes back, so the
field, its construction and its layout params go.

**`GameInfoActivity.musicButton`'s corner placement** goes with it — the button
survives, in the row. Its `FrameLayout.LayoutParams` and the stacking offset
(`pixels(16) + pixels(48) + pixels(8)`, which only existed to sit below the
manual) are deleted rather than adjusted.

**`GameInfoView.musicButton`'s corner placement** likewise, including its own
stacking offset (`pixels(12) + pixels(48) + pixels(8)`) — an offset that already
made room for a manual button that is not there, which is its own small argument
for the move.

**`GameInfoView.disc()`** becomes unused once the music button is no longer
floating over artwork, and is removed. It exists solely to give a button on a
picture a background that wins against both pale and dark box art; a button in a
row does not need one.

After this, `media()` in `GameInfoActivity` and the cover box in `GameInfoView`
contain the gallery and nothing else, and neither needs to be a `FrameLayout`
for the sake of an overlay — though changing the container type is optional and
not required by this design.

Every comment that explains corner-versus-bar or manual-above-music placement is
rewritten rather than left, per the house rule that comments do not move with
the code.

## What is deliberately not changed

- The actions behind every button. This is where they sit and what they look
  like, nothing else.
- `EmulatorActivity`'s own quick bar, `applyBarMode`, `showOnly` and
  `showAllExcept`.
- `DetailPane`, which already has the target shape.
- Which side the words are on in either screen.
- `GameInfoActivity`'s two-ways-in rule (`EXTRA_URI` present or absent decides
  which bar the screen wears). The rule stands; only what each bar looks like
  changes.

## Testing

- `DetailPaneTest` covers the library's pane, which is unchanged — it should
  keep passing untouched and is the check that the shared pattern was not
  disturbed.
- Device verification on the AYN Thor Lite for all three: details from the
  library, details from the machine's ⓘ, and the second screen's panel — in
  both orientations, since the row's position depends on the words lane and the
  lane swaps ends between them.
- A game **without** a manual and **without** music in each place, since both
  icons' whole behaviour is being hidden until an off-thread answer arrives, and
  a screen offering a manual or a tune for a game that has neither is the
  failure this design can most easily introduce. A game **with** music is the
  other side of it and is rarer — about one game in fifty — so it needs
  choosing deliberately rather than being met by chance.
