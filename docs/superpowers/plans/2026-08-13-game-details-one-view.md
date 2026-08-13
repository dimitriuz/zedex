# Game details: one view, one action bar — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `GameInfoView` the single implementation of a game's details, with an action row each host configures, and reduce `GameInfoActivity` to a shell around it.

**Architecture:** `GameInfoView` (a `LinearLayout` in `dev.ldlab.zedex.library.ui`) owns the layout, the words, the gallery, and the two icons it can answer for itself off-thread (manual, music). Hosts add their own leading/trailing icon actions and an optional primary text button, the way `QuickBar.addAction` already works. `GameInfoActivity` reads its three intent extras, configures the row, and hands over.

**Tech Stack:** Java 17, Android SDK 36 / minSdk 30, no Robolectric (`unitTests.returnDefaultValues = true`), JUnit 4 for JVM tests, UI Automator for instrumentation.

**Spec:** `docs/superpowers/specs/2026-08-13-game-details-action-bar-design.md`

## Global Constraints

- **Never modify `vendor/`.** Nothing in this plan should go near it.
- **No new strings.** Every label used here already exists: `library_play`, `library_manual`, `library_title`, `menu_back`, `menu_button`, `music_title`, `info_authors`, `info_price`, `info_series`, `info_compilations`, `info_contents`. Adding one would mean nine files and `scripts/check-strings.py`.
- **No new drawables.** `ic_chevron_left`, `ic_manual`, `ic_music`, `ic_menu`, `ic_close` all exist.
- **Build native first only if C changes.** Nothing here touches C, so `./gradlew assembleDebug` alone is correct.
- **`JAVA_HOME=/opt/android-studio/jbr`** on every Gradle command.
- **Build collaborators in `onCreate`, never as field initialisers** — field initialisers run before `preferences` is set.
- **A member another layer needs must be `public`;** package-private stops at the package boundary. `GameInfoView` is in `library.ui`, `GameInfoActivity` in `screen`.
- **When a class leaves, read what it left behind** — comments do not move with the code. Every task that deletes code rewrites the comments that referred to it.
- **Device is the AYN Thor Lite, serial `c74eb68e`.** It has two displays; `am start --display 0` pins to the main screen or an activity opens on the panel instead.
- **Commit subjects take a conventional prefix** (`feat:`, `fix:`, `refactor:`, `test:`, `docs:`), body explains *why*.
- Work on branch `fix/back-is-the-devices-not-ours`'s successor — create `feature/game-details-one-view` from `main` before Task 1.

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `app/src/main/java/dev/ldlab/zedex/library/ui/GameInfoText.java` | Pure string helpers over `Meta` — no Android types, so JVM-testable | **create** |
| `app/src/test/java/dev/ldlab/zedex/library/ui/GameInfoTextTest.java` | Pins those helpers across the move | **create** |
| `app/src/main/java/dev/ldlab/zedex/library/ui/GameInfoView.java` | The one game-details view: layout, words, extras, gallery, configurable action row | **modify** |
| `app/src/main/java/dev/ldlab/zedex/screen/GameInfoActivity.java` | Shell: read extras, configure row, hand over | **modify** (shrinks ~681 → ~150 lines) |
| `app/src/main/java/dev/ldlab/zedex/screen/SecondScreen.java` | Panel host — one added call | **modify** |
| `app/src/androidTest/java/dev/ldlab/zedex/GameInfoBarTest.java` | Asserts the two activity row configurations by content description | **create** |

`GameInfoText` is a new file rather than statics on `GameInfoView` on purpose: a JVM test that touches `GameInfoView` has to load a `LinearLayout` subclass against stubbed android.jar, and a pure class sidesteps that entirely.

---

### Task 1: Extract the pure helpers, pinned by a JVM test

`seriesLine` and `titlesOf` are pure static string builders on `GameInfoActivity` and are about to move. Pinning them first means the move cannot silently change them.

**Files:**
- Create: `app/src/main/java/dev/ldlab/zedex/library/ui/GameInfoText.java`
- Create: `app/src/test/java/dev/ldlab/zedex/library/ui/GameInfoTextTest.java`
- Modify: `app/src/main/java/dev/ldlab/zedex/screen/GameInfoActivity.java` (delete the two private copies, call the new class)

**Interfaces:**
- Consumes: `Meta`, `Meta.Link`, `Meta.at(String)` → `Meta.Builder`, `Builder.series(String)`, `Builder.seriesGames(List<Link>)`, `Builder.build()`
- Produces: `GameInfoText.seriesLine(Meta)` → `String` or null; `GameInfoText.titlesOf(List<Meta.Link>)` → `String` or null

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/ldlab/zedex/library/ui/GameInfoTextTest.java`:

```java
package dev.ldlab.zedex.library.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import dev.ldlab.zedex.library.meta.Meta;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * The two helpers that survived the merge of GameInfoActivity into
 * GameInfoView. They are here, in a class of their own with no Android type
 * in it, precisely so this test can exist: a JVM test cannot load a
 * LinearLayout subclass against a stubbed android.jar, and these are the only
 * part of that screen worth pinning on the JVM.
 */
public class GameInfoTextTest {

    @Test
    public void titlesAreJoinedWithCommas() {
        assertEquals("Chaos, Rebelstar", GameInfoText.titlesOf(Arrays.asList(
                new Meta.Link("1", "Chaos"), new Meta.Link("2", "Rebelstar"))));
    }

    @Test
    public void noTitlesAtAllIsNullRatherThanEmpty() {
        assertNull(GameInfoText.titlesOf(null));
        assertNull(GameInfoText.titlesOf(Collections.emptyList()));
    }

    @Test
    public void aSeriesWithOtherGamesNamesBothAcrossADash() {
        Meta meta = Meta.at("./g.tap")
                .series("Lords of Chaos")
                .seriesGames(Collections.singletonList(new Meta.Link("1", "Chaos")))
                .build();

        assertEquals("Lords of Chaos — Chaos", GameInfoText.seriesLine(meta));
    }

    @Test
    public void aSeriesWithNoOtherGamesIsJustItsName() {
        Meta meta = Meta.at("./g.tap").series("Lords of Chaos").build();
        assertEquals("Lords of Chaos", GameInfoText.seriesLine(meta));
    }

    /** The row belongs to a series nobody named, which is not the same as
     *  belonging to none - the other games are still worth listing. */
    @Test
    public void otherGamesWithNoSeriesNameAreStillListed() {
        Meta meta = Meta.at("./g.tap")
                .seriesGames(Collections.singletonList(new Meta.Link("1", "Chaos")))
                .build();

        assertEquals("Chaos", GameInfoText.seriesLine(meta));
    }

    @Test
    public void neitherIsNull() {
        assertNull(GameInfoText.seriesLine(Meta.at("./g.tap").build()));
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest --tests '*GameInfoTextTest*'
```

Expected: compilation failure — `cannot find symbol: class GameInfoText`.

- [ ] **Step 3: Create the class**

Create `app/src/main/java/dev/ldlab/zedex/library/ui/GameInfoText.java`:

```java
package dev.ldlab.zedex.library.ui;

import dev.ldlab.zedex.library.meta.Meta;

import java.util.List;

/**
 * The parts of a game's details that are string work and nothing else.
 *
 * Split out of {@code GameInfoActivity} when that screen was folded into
 * {@link GameInfoView}, and kept free of every Android type on purpose: this
 * project has no Robolectric ({@code unitTests.returnDefaultValues = true}),
 * so a JVM test cannot load a {@code LinearLayout} subclass, and these are the
 * only part of that screen worth pinning on the JVM. See
 * {@code GameInfoTextTest}.
 */
public final class GameInfoText {

    private GameInfoText() {
    }

    /** The titles of other entries, comma separated. The ids travel with them
     *  in the store and nothing reads them yet - see {@link Meta.Link}. */
    public static String titlesOf(List<Meta.Link> links) {
        if (links == null || links.isEmpty()) return null;

        StringBuilder text = new StringBuilder();

        for (Meta.Link link : links) {
            if (text.length() > 0) text.append(", ");
            text.append(link.title);
        }

        return text.toString();
    }

    /** The series' name, and the rest of it after a dash where the record
     *  names any - "Lords of Chaos — Chaos". */
    public static String seriesLine(Meta meta) {
        String rest = titlesOf(meta.seriesGames);

        if (meta.series == null) return rest;
        return rest == null ? meta.series : meta.series + " — " + rest;
    }
}
```

- [ ] **Step 4: Run the test and watch it pass**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest --tests '*GameInfoTextTest*'
```

Expected: PASS, 6 tests.

- [ ] **Step 5: Point `GameInfoActivity` at it**

In `app/src/main/java/dev/ldlab/zedex/screen/GameInfoActivity.java`:
1. Delete the private `titlesOf` method (around line 553) and the private `seriesLine` method (around line 620).
2. Add `import dev.ldlab.zedex.library.ui.GameInfoText;`
3. In `show(Meta)`, change the three call sites:

```java
extra(R.string.info_series, GameInfoText.seriesLine(meta));
extra(R.string.info_compilations, GameInfoText.titlesOf(meta.compilations));
extra(R.string.info_contents, GameInfoText.titlesOf(meta.contents));
```

- [ ] **Step 6: Build**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```sh
git add app/src/main/java/dev/ldlab/zedex/library/ui/GameInfoText.java \
        app/src/test/java/dev/ldlab/zedex/library/ui/GameInfoTextTest.java \
        app/src/main/java/dev/ldlab/zedex/screen/GameInfoActivity.java
git commit -m "refactor: the details screen's string work, in a class a JVM test can reach

seriesLine and titlesOf are about to move into GameInfoView, which a JVM
test cannot load: there is no Robolectric here, so a LinearLayout subclass
against a stubbed android.jar is not something testDebugUnitTest can
instantiate. Pure statics in a class of their own can be pinned before the
move rather than after it."
```

---

### Task 2: The extras rows move into `GameInfoView`

The panel does not show authors, price, series, compilations or contents. That is drift, not a decision — this is where it is corrected.

**Files:**
- Modify: `app/src/main/java/dev/ldlab/zedex/library/ui/GameInfoView.java`

**Interfaces:**
- Consumes: `GameInfoText.seriesLine(Meta)`, `GameInfoText.titlesOf(List<Meta.Link>)` from Task 1
- Produces: nothing new public. `GameInfoView.show(Meta)` now populates an `extras` column.

- [ ] **Step 1: Add the `extras` field**

Beside the other `TextView` fields near the top of `GameInfoView` (around line 114, with `musicButton` and `playButton`):

```java
    /** The rows under the description: authors, price, series, compilations,
     *  contents. A column of its own because it is rebuilt whenever the store
     *  answers, and the panel went without it until this view became the one
     *  implementation of these details rather than the lesser of two. */
    private final LinearLayout extras;
```

- [ ] **Step 2: Build it into the words column**

In the constructor, immediately after the `description` view is added to `words` (after `words.addView(description, wrap());`, around line 325):

```java
        // Under the description, because these are the long tail: a quarter
        // of entries have a price, six per cent a series, and a row that is
        // usually absent belongs below the one thing somebody came to read.
        extras = new LinearLayout(context);
        extras.setOrientation(VERTICAL);
        extras.setPadding(0, pixels(20), 0, 0);
        words.addView(extras, wrap());
```

- [ ] **Step 3: Add the `extra` helper**

Add as a private method on `GameInfoView` (put it directly after `show(Meta)`):

```java
    /**
     * A labelled fact, or nothing at all.
     *
     * Nothing at all is the common case - see {@link #extras} - and an empty
     * row with a heading over it would claim the database was asked and had
     * no answer, when mostly it was never asked.
     *
     * A point larger than the screen's own version of this throughout, for
     * the same reason every other size here is: this is read at a slight
     * distance on a fixed panel, not close up in the hand.
     */
    private void extra(int label, String value) {
        if (value == null || value.trim().isEmpty()) return;

        TextView heading = new TextView(getContext());
        heading.setText(label);
        heading.setTextColor(Palette.MUTED);
        heading.setTextSize(13);
        heading.setPadding(0, pixels(12), 0, 0);
        extras.addView(heading, wrap());

        TextView text = new TextView(getContext());
        text.setText(value.trim());
        text.setTextColor(Palette.TEXT);
        text.setTextSize(16);
        text.setLineSpacing(pixels(3), 1f);
        extras.addView(text, wrap());
    }
```

- [ ] **Step 4: Populate them in `show(Meta)`**

At the end of `GameInfoView.show(Meta)` (after the `description` block, around line 719):

```java
        extras.removeAllViews();
        extra(R.string.info_authors, String.join(", ", meta.authors));
        extra(R.string.info_price, meta.price);
        extra(R.string.info_series, GameInfoText.seriesLine(meta));
        extra(R.string.info_compilations, GameInfoText.titlesOf(meta.compilations));
        extra(R.string.info_contents, GameInfoText.titlesOf(meta.contents));
```

- [ ] **Step 5: Clear them in `clear()`**

In `GameInfoView.clear()` (around line 663), beside the other views being reset:

```java
        extras.removeAllViews();
```

- [ ] **Step 6: Build**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug
adb -s c74eb68e install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: BUILD SUCCESSFUL, `Success`.

- [ ] **Step 7: Verify on the panel**

The panel needs `secondScreen` on. Turn it on, open the library, select a game known to be scraped (`Atic Atac` in the test collection has authors and a year), and read the panel:

```sh
adb -s c74eb68e shell am start --display 0 -n dev.ldlab.zedex.debug/dev.ldlab.zedex.screen.LibraryActivity
# select a scraped game, then photograph the panel:
adb -s c74eb68e shell dumpsys SurfaceFlinger --display-id      # note the id for display 4
adb -s c74eb68e exec-out screencap -p -d <surfaceflinger-id> > panel.png
```

Expected: under the description, an **Authors** heading with a value. A game with no scraped authors shows no heading at all — check one of those too, and confirm no empty headings appear.

- [ ] **Step 8: Commit**

```sh
git add app/src/main/java/dev/ldlab/zedex/library/ui/GameInfoView.java
git commit -m "feat: the panel shows what the details screen always did

Authors, price, series, compilations and contents were on GameInfoActivity
and not on GameInfoView, which is drift rather than a decision: the two are
the same screen and show(Meta) is otherwise identical in both. The panel
gets them ahead of the merge so the change is visible on its own rather
than buried in it."
```

---

### Task 3: The configurable action row

The row is built once here, and the manual and music icons become the view's own rather than a text button and a thing floating over the artwork.

**Files:**
- Modify: `app/src/main/java/dev/ldlab/zedex/library/ui/GameInfoView.java`
- Modify: `app/src/main/java/dev/ldlab/zedex/screen/SecondScreen.java:226` area

**Interfaces:**
- Produces, all `public` because `screen` is a different package from `library.ui`:
  - `void setPrimaryAction(int labelRes, Runnable action)`
  - `void addLeadingAction(int iconRes, int descriptionRes, Runnable action)`
  - `void addTrailingAction(int iconRes, int descriptionRes, Runnable action)`
  - `void setOffersManual(boolean)` — unchanged signature and meaning
- Row order is always: leading… , primary, manual, music, trailing…

- [ ] **Step 1: Replace the row's fields**

In `GameInfoView`, replace the `playButton` and `rowManual` field declarations (around lines 115–120) with:

```java
    /** The row at the foot of the words lane. Rebuilt by {@link #rebuildRow}
     *  whenever a host adds to it, because the order is fixed - leading,
     *  primary, manual, music, trailing - and a host may add in any order. */
    private final LinearLayout actionRow;

    private final List<View> leadingActions = new ArrayList<>();
    private final List<View> trailingActions = new ArrayList<>();

    /** The one text button, or null where the host wants none - which is the
     *  details screen opened from a running machine, where there is nothing
     *  to start. */
    private Button primaryButton;

    /** The manual and the music are this view's own, not a host's: it is this
     *  view that asks Artwork for them off the UI thread and reveals each
     *  only when the answer arrives. A host says whether it wants the manual
     *  offered at all ({@link #setOffersManual}) and nothing more. */
    private final ImageButton rowManual;
    private final ImageButton rowMusic;
```

Add the imports `java.util.ArrayList`, `java.util.List`, `android.view.View` if not already present.

- [ ] **Step 2: Build the row in the constructor**

Replace the whole `actionRow` construction block (currently around lines 358–384, from `LinearLayout actionRow = new LinearLayout(context);` to `wordsLane.addView(actionRow, playParams);`) with:

```java
        // The row at the foot of the words lane, outside the scroller - the
        // rule every host of this view shares: a description long enough to
        // scroll must never carry the button that starts the game out of
        // reach with it.
        //
        // The shape is DetailPane's, which had it first: one text button
        // taking whatever the icons leave, then fixed 48dp icons, in the
        // order action then manual then music.
        actionRow = new LinearLayout(context);
        actionRow.setOrientation(HORIZONTAL);
        actionRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        rowManual = icon(R.drawable.ic_manual, R.string.library_manual);
        rowMusic = icon(R.drawable.ic_music, R.string.music_title);

        rebuildRow();

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        rowParams.leftMargin = rowParams.rightMargin = pixels(24);
        rowParams.topMargin = pixels(16);
        rowParams.bottomMargin = pixels(24);
        wordsLane.addView(actionRow, rowParams);
```

- [ ] **Step 3: Add the row's helpers**

Add these private methods to `GameInfoView`:

```java
    /** One 48dp icon button, built the way DetailPane builds its own. */
    private ImageButton icon(int iconRes, int descriptionRes) {
        ImageButton button = new ImageButton(getContext());
        button.setImageResource(iconRes);
        button.setColorFilter(Palette.MUTED);
        button.setBackground(Ripple.make(getResources().getDisplayMetrics().density));
        button.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        button.setContentDescription(getContext().getString(descriptionRes));
        button.setVisibility(View.GONE);
        return button;
    }

    /**
     * The row, in its one order: leading, primary, manual, music, trailing.
     *
     * Rebuilt rather than inserted into, because a host adds in whatever
     * order suits it and the order on screen is not that one - the details
     * screen opened from the machine adds its back icon first and its menu
     * and close icons after, and they must land on opposite sides of two
     * buttons it never mentions.
     */
    private void rebuildRow() {
        actionRow.removeAllViews();

        for (View action : leadingActions) actionRow.addView(action, iconParams());
        if (primaryButton != null) {
            actionRow.addView(primaryButton, new LinearLayout.LayoutParams(
                    0, LayoutParams.WRAP_CONTENT, 1f));
        }
        actionRow.addView(rowManual, iconParams());
        actionRow.addView(rowMusic, iconParams());
        for (View action : trailingActions) actionRow.addView(action, iconParams());
    }

    private LinearLayout.LayoutParams iconParams() {
        return new LinearLayout.LayoutParams(pixels(48), pixels(48));
    }
```

- [ ] **Step 4: Add the three public setters**

```java
    /**
     * The one text button, and what it does - Play, everywhere it appears.
     *
     * Never called means no text button at all, which is the details screen
     * opened from a running machine: the game is already going, so there is
     * nothing to start.
     */
    public void setPrimaryAction(int labelRes, Runnable action) {
        primaryButton = new Button(getContext());
        primaryButton.setText(labelRes);
        primaryButton.setOnClickListener(v -> action.run());
        rebuildRow();
    }

    /** An icon before the manual. */
    public void addLeadingAction(int iconRes, int descriptionRes, Runnable action) {
        leadingActions.add(visibleAction(iconRes, descriptionRes, action));
        rebuildRow();
    }

    /** An icon after the music. */
    public void addTrailingAction(int iconRes, int descriptionRes, Runnable action) {
        trailingActions.add(visibleAction(iconRes, descriptionRes, action));
        rebuildRow();
    }

    /** A host's icon, which unlike the manual and the music is shown at once:
     *  the host knows whether its own action exists and this view does not. */
    private ImageButton visibleAction(int iconRes, int descriptionRes, Runnable action) {
        ImageButton button = icon(iconRes, descriptionRes);
        button.setVisibility(View.VISIBLE);
        button.setOnClickListener(v -> action.run());
        return button;
    }
```

- [ ] **Step 5: Move the music out of the cover box**

1. Delete the `musicButton` construction block and its `coverBox.addView(musicButton, musicParams);` (around lines 250–266), including the stacking-offset comment — that offset made room for a manual button that was never there.
2. Delete the `disc()` method (around line 552) and its javadoc. It exists solely so a button sitting on artwork wins against pale and dark box art; a button in a row needs no such thing.
3. Replace every remaining `musicButton` reference with `rowMusic`.
4. Delete the `musicButton` field.

- [ ] **Step 6: Point Play, the manual and the music at the new widgets**

1. In `showEntry`, the manual block (around lines 521–539) now sets `rowManual` — it is an `ImageButton`, so `setOnClickListener` and `setVisibility` are unchanged; nothing there needs editing beyond the field already being renamed in place.
2. Delete `playButton` and `updatePlayVisibility()`. `setOnPlay(Runnable)` becomes:

```java
    /**
     * What Play does here, when this view's host offers it at all.
     *
     * Kept as a setter of its own rather than folded into
     * {@link #setPrimaryAction} because SecondScreen sets the listener once,
     * at construction, and learns much later whether there is a game to
     * play - see the panel's own showEntry/clear pair.
     */
    public void setOnPlay(Runnable listener) {
        onPlay = listener;
    }
```

3. Wherever `updatePlayVisibility()` was called, set the primary button's visibility instead:

```java
        if (primaryButton != null) {
            primaryButton.setVisibility(canPlay ? View.VISIBLE : View.GONE);
        }
```

- [ ] **Step 7: Hide the music in `clear()`**

In `clear()`, beside `rowManual.setVisibility(View.GONE);`:

```java
        rowMusic.setVisibility(View.GONE);
```

- [ ] **Step 8: `SecondScreen` asks for its Play button**

In `SecondScreen.java`, beside the existing `infoView.setOffersManual(!hasControls);` (line 226):

```java
        infoView.setPrimaryAction(R.string.library_play, () -> {
            if (onPlay != null) onPlay.run();
        });
```

Check the surrounding code for how `onPlay` is reached from there; if `SecondScreen` holds it in a field passed to `infoView.setOnPlay`, keep that call and have the primary action delegate to the view's own `onPlay` instead — do not introduce a second listener.

- [ ] **Step 9: Build and install**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug
adb -s c74eb68e install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 10: Verify the panel's row**

With `secondScreen` on, open the library and select a game, then screenshot the panel (see Task 2 Step 7 for the `screencap -d` incantation).

Expected: at the foot of the words lane, `[ PLAY ] [📖] [🎵]` — Play wide, two 48dp icons. A game with no manual shows no 📖; a game with no `.ay` shows no 🎵. **Nothing floats over the cover.**

- [ ] **Step 11: Commit**

```sh
git add app/src/main/java/dev/ldlab/zedex/library/ui/GameInfoView.java \
        app/src/main/java/dev/ldlab/zedex/screen/SecondScreen.java
git commit -m "feat: one action row, and the host says what is on it

The view owns the row's shape and the two icons it can answer for itself -
it is this view that asks Artwork for a manual and a tune off the UI
thread, so it is this view that reveals them. A host owns which other
actions exist and what they do, which is what keeps this class from
needing to know about EmulatorActivity or LibraryActivity.

Order is fixed at leading, primary, manual, music, trailing, so the row is
rebuilt rather than inserted into: a host adds in whatever order suits it,
and the details screen opened from a machine puts its back icon on one
side of two buttons it never mentions and its menu and close on the other.

The music button stops floating over the artwork, which takes disc() with
it - a background that wins against pale and dark box art is for a button
sitting on a picture, and this one no longer is."
```

---

### Task 4: One arrangement — words left, media right

The two screens put the media on opposite sides. Merging forces one, and this is it. **The panel visibly swaps sides here**, which is why it is a task of its own rather than a line inside the merge.

**Files:**
- Modify: `app/src/main/java/dev/ldlab/zedex/library/ui/GameInfoView.java` (the landscape branch of the constructor, around lines 384–400)

- [ ] **Step 1: Swap the landscape branch**

Replace the landscape branch so the words lane is added first:

```java
        if (landscape) {
            // Words left, media right - the arrangement GameInfoActivity had,
            // and the one this view took when that screen was folded into it.
            // Not a parameter: a knob for which side the picture sits on is
            // the kind that multiplies, and one arrangement is the point of
            // having one implementation.
            LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(
                    0, LayoutParams.MATCH_PARENT, LANDSCAPE_WORDS_WEIGHT);
            wordsParams.rightMargin = pixels(LANE_GAP_DP);
            addView(wordsLane, wordsParams);
            addView(media, new LinearLayout.LayoutParams(
                    0, LayoutParams.MATCH_PARENT, LANDSCAPE_MEDIA_WEIGHT));
        } else {
```

Note the margin moves with the lane: it was on the media because the media led.

Leave the portrait branch alone — media above, words below, which both screens already agreed on.

- [ ] **Step 2: Build and install**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug
adb -s c74eb68e install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 3: Verify both orientations on the panel**

Screenshot the panel in landscape: words on the **left**, picture on the **right**, action row at the foot of the words. Then rotate and confirm portrait is unchanged (picture above, words below, row at the bottom).

- [ ] **Step 4: Commit**

```sh
git add app/src/main/java/dev/ldlab/zedex/library/ui/GameInfoView.java
git commit -m "refactor: the picture goes on the right, here as on the screen

The two implementations of these details disagreed about which side the
media sits on in landscape - the screen put words left, the panel put them
right - and folding one into the other has to pick. It picks the screen's,
which is the one the action row was described against.

The panel therefore swaps sides, which is the one deliberate visible change
to the second screen in this work and is why it is its own commit rather
than a line inside the merge."
```

---

### Task 5: `GameInfoActivity` becomes a shell

**Files:**
- Modify: `app/src/main/java/dev/ldlab/zedex/screen/GameInfoActivity.java`
- Create: `app/src/androidTest/java/dev/ldlab/zedex/GameInfoBarTest.java`

**Interfaces:**
- Consumes: `GameInfoView.setPrimaryAction`, `addLeadingAction`, `addTrailingAction`, `showEntry(String, String)`, `release()` from Task 3
- Produces: `GameInfoActivity` keeps `EXTRA_PATH`, `EXTRA_NAME`, `EXTRA_URI` — both callers (`LibraryActivity:2049`, `EmulatorActivity:1134`) are untouched.

- [ ] **Step 1: Write the failing instrumentation test**

Create `app/src/androidTest/java/dev/ldlab/zedex/GameInfoBarTest.java`:

```java
package dev.ldlab.zedex;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;

import org.junit.Test;
import org.junit.runner.RunWith;
import androidx.test.ext.junit.runners.AndroidJUnit4;

/**
 * The details screen wears one of two rows, and which one turns on whether a
 * machine is behind it - EXTRA_URI present means it came from the library and
 * there is a game to start; absent means it came from the machine's own ⓘ.
 *
 * Asserted by content description rather than by class, because
 * ImageView.getAccessibilityClassName() answers android.widget.ImageView
 * whatever you subclass it into - see CLAUDE.md - and because the labels are
 * the app's own words in the app's own language, which is why each is read
 * from resources here rather than typed in English.
 */
@RunWith(AndroidJUnit4.class)
public class GameInfoBarTest {

    private static final long TIMEOUT = 8000;

    @Test
    public void openedFromTheLibraryItOffersPlayAndBackAndNotTheMachinesMenu() {
        Screen screen = Screen.get();
        UiDevice device = screen.device();

        Emulator.launch();
        Library.open();
        Library.selectAnyGame();
        Library.openDetails();

        device.wait(Until.hasObject(By.descContains(screen.text(R.string.menu_back))), TIMEOUT);

        assertNotNull("no Back icon on the row",
                device.findObject(By.descContains(screen.text(R.string.menu_back))));
        assertNotNull("no Play button on the row",
                device.findObject(By.text(screen.text(R.string.library_play))));
        assertNull("the machine's menu belongs only to the machine's own variant",
                device.findObject(By.descContains(screen.text(R.string.menu_button))));
    }
}
```

**Before writing this, read `app/src/androidTest/java/dev/ldlab/zedex/DetailPaneTest.java` and `Screen.java`** for the helpers this project actually has — `Screen.get()`, `Screen.assertHere()`, how a string resource is read in the app's language, and whether a `Library` helper exists. Replace `Library.open()`, `Library.selectAnyGame()` and `Library.openDetails()` with whatever those files really provide; if there is no such helper, drive the library the way `DetailPaneTest` does and keep the assertions above verbatim.

- [ ] **Step 2: Run it and watch it fail**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug assembleDebugAndroidTest
adb -s c74eb68e install -r app/build/outputs/apk/debug/app-debug.apk
adb -s c74eb68e install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s c74eb68e shell am instrument -w -r -e class dev.ldlab.zedex.GameInfoBarTest \
    dev.ldlab.zedex.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: FAIL — "no Back icon on the row". The screen still shows a text button reading "Library".

Read the count against the number of `@Test` methods, and note `-r` reports `ASSUMPTION_FAILURE` by name instead of folding it into `OK`.

- [ ] **Step 3: Replace the activity's body**

Rewrite `GameInfoActivity` down to this. Everything else it owns — `words`, `media`, `load`, `show`, `extra`, `factsLine`, `append`, `openViewer`, `loadManualButton`, `loadMusicButton`, `artworkHeight`, `wrap`, `pixels`, `withBar`, `actionRow`, `page`, `openTheManual`, the `Gallery`, `Handler`, `TextView` and `ImageButton` fields, `ARTWORK_TARGET_DP`, and the `onOptionsItemSelected` override — is deleted, because `GameInfoView` already has it or has its own.

```java
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // The manifest label is resolved in the phone's language rather than
        // this screen's, so the title is set here; see Language. Still set
        // although there is no title strip: the task switcher reads it.
        setTitle(R.string.library_info);
        if (getActionBar() != null) getActionBar().hide();

        String path = getIntent().getStringExtra(EXTRA_PATH);
        String name = getIntent().getStringExtra(EXTRA_NAME);
        String file = getIntent().getStringExtra(EXTRA_URI);

        view = new GameInfoView(this);
        configureRow(file, path);

        setContentView(view);
        fitToSafeArea();

        if (path != null) view.showEntry(path, name);
    }

    @Override
    protected void onPause() {
        super.onPause();

        // One of the three times a video must not be left running - see
        // CLAUDE.md.
        view.release();
    }

    /**
     * Which row this screen wears, and the rule underneath it: the bar
     * reflects whether there is a machine behind this screen.
     *
     * {@link #EXTRA_URI} present means the library sent us and nothing is
     * running, so the game's own actions belong here and Play leads. Absent
     * means the machine's own ⓘ sent us and the machine is behind this
     * window, so what is wanted is the way back to it.
     *
     * Back leads in one and trails in the other, which is deliberate: from
     * the machine it is the reason you are leaving, and from the library it
     * is the last resort after Play.
     */
    private void configureRow(String file, String path) {
        if (file != null) {
            Uri uri = Uri.parse(file);

            view.setPrimaryAction(R.string.library_play, () -> {
                // The same hand-over a row in the library makes - see
                // LibraryActivity.openGame, whose own comment explains why
                // the grant travels with it.
                startActivity(new Intent(Intent.ACTION_VIEW, uri, this, EmulatorActivity.class)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .putExtra(EmulatorActivity.EXTRA_LIBRARY_PATH, path));
                finish();
            });

            // Finishing this screen is what returns to the library, so this
            // needs no intent of its own.
            view.addTrailingAction(R.drawable.ic_chevron_left, R.string.menu_back, this::finish);
            return;
        }

        // The machine is behind this window, so finishing is going back to it.
        view.addLeadingAction(R.drawable.ic_chevron_left, R.string.menu_back, this::finish);

        // The machine's own menu, which only the machine can open: a sheet
        // built over another activity's window is not something a second
        // activity can raise, so this asks rather than opens.
        view.addTrailingAction(R.drawable.ic_menu, R.string.menu_button, () -> {
            startActivity(new Intent(this, EmulatorActivity.class)
                    .putExtra(EmulatorActivity.EXTRA_OPEN_MENU, true));
            finish();
        });

        // Out of the game altogether: what it does is close the content, and
        // where it leaves you is the library.
        view.addTrailingAction(R.drawable.ic_close, R.string.library_title, () -> {
            startActivity(new Intent(this, LibraryActivity.class)
                    .putExtra(LibraryActivity.EXTRA_FROM_MENU, true)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                              | Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
        });
    }
```

with the single field:

```java
    private GameInfoView view;
```

Built in `onCreate` and never as a field initialiser — those run before `preferences` is set.

Update the class javadoc: it currently explains a bar this screen builds itself, and that bar is now the shared view's.

- [ ] **Step 4: Build, install, run the test**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug assembleDebugAndroidTest
adb -s c74eb68e install -r app/build/outputs/apk/debug/app-debug.apk
adb -s c74eb68e install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s c74eb68e shell am instrument -w -r -e class dev.ldlab.zedex.GameInfoBarTest \
    dev.ldlab.zedex.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: PASS.

- [ ] **Step 5: Check nothing shared moved under `DetailPane`**

```sh
adb -s c74eb68e shell am instrument -w -r -e class dev.ldlab.zedex.DetailPaneTest \
    dev.ldlab.zedex.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: PASS, 2 tests, unmodified. `DetailPane` is step 2 of the spec and is untouched here.

- [ ] **Step 6: Verify both variants by hand, both orientations**

From the library — expect `[ PLAY ] [📖] [🎵] [‹]` at the foot of the words:

```sh
adb -s c74eb68e shell am start --display 0 -n dev.ldlab.zedex.debug/dev.ldlab.zedex.screen.LibraryActivity
# select a game, tap ⓘ, screenshot
adb -s c74eb68e exec-out screencap -p > from-library.png
```

From the machine — expect `[‹] [📖] [🎵] [☰] [✕]` and no Play:

```sh
adb -s c74eb68e shell am start --display 0 -n dev.ldlab.zedex.debug/dev.ldlab.zedex.EmulatorActivity
# open a game, tap ⓘ on the quick bar, screenshot
adb -s c74eb68e exec-out screencap -p > from-machine.png
```

Check in both: a game **with no manual and no music** shows neither icon; a game **with** music (about one in fifty — pick one deliberately) shows 🎵 and tapping it reaches the machine. Rotate and repeat: the row follows the words lane, which swaps ends between orientations.

Then confirm each icon still does what it did — `‹` from the machine returns to the emulator, `‹` from the library returns to the library, `☰` opens the machine's sheet, `✕` lands in the library.

- [ ] **Step 7: Commit**

```sh
git add app/src/main/java/dev/ldlab/zedex/screen/GameInfoActivity.java \
        app/src/androidTest/java/dev/ldlab/zedex/GameInfoBarTest.java
git commit -m "refactor: the details screen is the details view, in an activity

GameInfoActivity and GameInfoView were two implementations of one screen -
show(Meta) was character-for-character identical but for one line and the
extras block - and they had drifted apart in both directions: the panel had
no extras rows, the screen had no autoplay and no measured cover.

The activity is now what is actually different about being an activity:
three intent extras, which row to configure, and release() on pause. It
goes from 681 lines to about 150, and the screen inherits autoplay and
measured cover sizing along with everything else.

GameInfoBarTest asserts the two rows by content description rather than by
class, since ImageView.getAccessibilityClassName answers ImageView whatever
you subclass it into."
```

---

## Self-Review

**Spec coverage.** Action row shape and per-host contents → Tasks 3 and 5. Manual/music owned by the view → Task 3. No corner buttons, `disc()` removed → Task 3. Panel gains extras → Task 2. Words left / media right → Task 4. Activity becomes a shell, listed member by member → Task 5. `QuickBar` no longer used by `GameInfoActivity` → Task 5 Step 3. `menu_back` reused, no new strings → Global Constraints. `DetailPaneTest` unchanged and passing → Task 5 Step 5. Both orientations, a game with no manual/music, a game with music → Task 5 Step 6. Steps 2 and 3 of the spec are out of scope here and are not given tasks, by design.

**Placeholders.** One deliberate instruction to read before writing rather than a code placeholder: Task 5 Step 1 says to read `DetailPaneTest` and `Screen.java` for the real test helper names, because inventing `Library.open()` here would be worse than saying plainly that the helpers must be looked up. The assertions themselves are given in full.

**Type consistency.** `setPrimaryAction(int, Runnable)`, `addLeadingAction(int, int, Runnable)`, `addTrailingAction(int, int, Runnable)`, `showEntry(String, String)`, `release()`, `setOffersManual(boolean)`, `setOnPlay(Runnable)` are used in Task 5 exactly as defined in Task 3. `GameInfoText.seriesLine(Meta)` and `GameInfoText.titlesOf(List<Meta.Link>)` are used in Task 2 exactly as defined in Task 1. `rowManual`/`rowMusic` are named consistently from Task 3 onward, replacing `musicButton` and the `Button rowManual`.

**Known risk, called out rather than designed around.** Task 3 Step 6 touches `updatePlayVisibility` and `setOnPlay`, whose exact interaction with `SecondScreen`'s `showEntry`/`clear` pair is the one place this plan reads rather than dictates. If the panel's Play button appears for a folder, or fails to appear for a game, that is where to look.
