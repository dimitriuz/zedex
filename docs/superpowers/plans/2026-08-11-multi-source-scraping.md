# Multi-source scraping Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A scrape consults several sources in a priority order the user sets, each filling in what the ones before it left out, never overwriting.

**Architecture:** `Provider` is not touched. `Scrapers` grows from "which provider" to "which ones, in order". A new `Blend` runs one game across them — search each, merge the facts, write or stage the media — and `Merge` holds the field-by-field rule as pure code. `Sweep` and `ScrapeOneGame` take a list where they took one. Design: `docs/superpowers/specs/2026-08-11-multi-source-scraping-design.md`.

**Tech Stack:** Java 17, Android (minSdk 30, targetSdk 36), Gradle, JUnit4, UI Automator / AndroidX Test.

## Global Constraints

- **Never modify `vendor/`.** Nothing in this plan goes near it.
- **A new string is nine files.** `values`, `values-cs`, `values-de`, `values-es`, `values-fr`, `values-it`, `values-pl`, `values-ru`, `values-uk`. `scripts/check-strings.py` fails on an unknown key or a disagreeing format specifier.
- **`scripts/check-prefs.py` must stay clean.** A preference is read as the type it was written as. `KEY_SCRAPERS` is `putString`/`getString` everywhere.
- **A member another layer needs must be `public`**; package-private stops at the package boundary.
- **Build collaborators in `onCreate`, never as field initialisers.**
- **Commit subjects take a conventional prefix** (`feat:`, `fix:`, `docs:`, `test:`, `refactor:`) and the body explains *why*.
- **Nothing on screen may change its `contentDescription` continuously** — it breaks the accessibility tree and takes the whole UI Automator suite down.
- **`ImageView.getAccessibilityClassName()` returns `android.widget.ImageView`** however it is subclassed; UI Automator selectors must match the framework class.
- **Branch:** `feature/multi-source-scraping`, already created, with the spec committed.

**Build and test commands** (`JAVA_HOME` is needed because the default JDK is newer than 21):

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug
env JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest --tests '*ClassName*'
env JAVA_HOME=/opt/android-studio/jbr ./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=dev.ldlab.zedex.library.scrape.ClassName
scripts/check-strings.py
scripts/check-prefs.py
```

Instrumentation runs on an **emulator**, not the tablet, and a run uninstalls the app afterwards — restore the data folder as `CLAUDE.md`'s *Device setup* section describes.

## File Structure

**Created**

| File | Responsibility |
|---|---|
| `app/src/main/java/dev/ldlab/zedex/library/scrape/Merge.java` | The field-by-field merge rule, pure `Meta` → `Meta`, no Android |
| `app/src/main/java/dev/ldlab/zedex/library/scrape/Blend.java` | One game across the ordered sources: search, merge, write or stage |
| `app/src/main/java/dev/ldlab/zedex/screen/ArtworkChoice.java` | The review sheet — old against new, one row per contested folder |
| `app/src/main/java/dev/ldlab/zedex/screen/ScraperOrder.java` | The settings dialog: which sources, in what order |
| `app/src/test/java/dev/ldlab/zedex/library/meta/MetaSourcesTest.java` | The contributor list and the three predicates that read it |
| `app/src/test/java/dev/ldlab/zedex/library/scrape/MergeTest.java` | The merge rule, including the reflective every-field test |
| `app/src/androidTest/java/dev/ldlab/zedex/library/scrape/ScrapersTest.java` | Order, enabling, migration |
| `app/src/androidTest/java/dev/ldlab/zedex/library/scrape/BlendTest.java` | The loop, both media policies, staging, contested |
| `app/src/androidTest/java/dev/ldlab/zedex/library/scrape/Fakes.java` | The fake `Provider`/`Http` shared by `BlendTest` and `SweepTest` |
| `app/src/androidTest/java/dev/ldlab/zedex/screen/ArtworkChoiceTest.java` | The sheet, on a device |

**Modified**

| File | Change |
|---|---|
| `library/meta/Meta.java` | `sources()`, generalised `isEsde()`/`isMine()`, `Builder.contributor()` |
| `library/meta/Artwork.java` | `stagingFileFor`, `stagingRoot`, `clearStaging`, `removeOthers` |
| `library/scrape/Downloads.java` | Takes a `Destination`; stops calling `Artwork.forget` |
| `library/scrape/Scrape.java` | `apply` forgets in a `finally`; `wouldOverwriteAHandEdit` deleted |
| `library/scrape/Scrapers.java` | `enabled()`, `save()`, migration; `preferred`/`withAccount` gone |
| `library/scrape/Sweep.java` | Takes `List<Provider>`; per-source isolation; `Tally.yours` gone |
| `storage/Prefs.java` | `KEY_SCRAPERS` |
| `screen/ScrapeOneGame.java` | Uses `Blend`; shows the sheet |
| `screen/ScrapeManyActivity.java` | List of providers; "at most"; `yours` line gone |
| `screen/SettingsActivity.java` | The ordered source dialog |
| `res/xml/settings_library.xml` | `ListPreference` → `Preference` |
| `res/values*/strings.xml` (×9) | New strings; `scrape_many_yours` and `scrape_overwrite` removed |
| `docs/INTERNALS.md`, `docs/LIBRARY.md`, `README.md`, `CLAUDE.md` | The feature, and the rules it turns into operational knowledge |

---

### Task 1: `Meta` carries a list of contributors

**Files:**
- Modify: `app/src/main/java/dev/ldlab/zedex/library/meta/Meta.java`
- Test: `app/src/test/java/dev/ldlab/zedex/library/meta/MetaSourcesTest.java` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: `List<String> Meta.sources()`, `Meta.Builder contributor(String name)`, and `isEsde()`/`isMine()` with their new meanings. Every later task depends on these.

`Meta` is pure Java with no Android imports, so this is a JVM test.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/ldlab/zedex/library/meta/MetaSourcesTest.java`:

```java
package dev.ldlab.zedex.library.meta;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * Who wrote a row, now that more than one thing can have.
 *
 * The field is one string and stays one string - an existing single-name row
 * has to go on reading correctly, which is the whole reason the list is
 * joined into the field that already exists rather than stored beside it.
 */
public class MetaSourcesTest {

    @Test
    public void anOldSingleNameRowReadsAsOneContributor() {
        Meta one = Meta.at("./A.tap").source("ZXInfo").build();

        assertEquals(Collections.singletonList("ZXInfo"), one.sources());
    }

    @Test
    public void aRowWithNoSourceHasNoContributors() {
        assertEquals(Collections.emptyList(), Meta.at("./A.tap").build().sources());
    }

    @Test
    public void contributorsAreKeptInTheOrderTheyContributed() {
        Meta two = Meta.at("./A.tap")
                .contributor("ZXInfo").contributor("ScreenScraper").build();

        assertEquals(Arrays.asList("ZXInfo", "ScreenScraper"), two.sources());
        assertEquals("ZXInfo, ScreenScraper", two.source);
    }

    @Test
    public void aContributorIsNeverListedTwice() {
        Meta twice = Meta.at("./A.tap")
                .contributor("ZXInfo").contributor("ZXInfo").build();

        assertEquals(Collections.singletonList("ZXInfo"), twice.sources());
    }

    /** A link may replace what only it wrote, and nothing else. */
    @Test
    public void aLinkOwnsARowOnlyWhenEsdeIsTheSoleContributor() {
        assertTrue(Meta.at("./A.tap").build().isEsde());
        assertTrue(Meta.at("./A.tap").source(Meta.ESDE).build().isEsde());

        assertFalse(Meta.at("./A.tap")
                        .contributor(Meta.ESDE).contributor("ZXInfo").build().isEsde());
    }

    /** Among the contributors, not the only one: a scraped row somebody then
     *  corrected is still theirs. */
    @Test
    public void aHandEditCountsHoweverManyOthersContributed() {
        assertTrue(Meta.at("./A.tap").source(Meta.USER).build().isMine());
        assertTrue(Meta.at("./A.tap")
                       .contributor("ZXInfo").contributor(Meta.USER).build().isMine());

        assertFalse(Meta.at("./A.tap").contributor("ZXInfo").build().isMine());
    }

    /** Editing a field adds the user to the row rather than erasing the
     *  record of which services were asked. */
    @Test
    public void editingAFieldAddsTheUserAndKeepsTheRest() {
        Meta scraped = Meta.at("./A.tap").contributor("ZXInfo").genre("Action").build();

        Meta edited = scraped.with(Meta.Field.GENRE, "Puzzle");

        assertEquals(Arrays.asList("ZXInfo", Meta.USER), edited.sources());
        assertEquals("Puzzle", edited.genre);
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest --tests '*MetaSourcesTest*'
```

Expected: compilation failure — `cannot find symbol: method sources()` and `method contributor(String)`.

- [ ] **Step 3: Add the list to `Meta`**

In `Meta.java`, add beside the `ESDE`/`USER` constants:

```java
    /**
     * How the contributors are joined into the one field that has always
     * held this.
     *
     * A row is written by more than one thing now - a link, a hand edit and
     * any number of providers, each filling in what the ones before left out
     * - and this stays a single string so that a store written by an older
     * build still reads: one name is a one-element list, which is exactly
     * what it always meant.
     */
    private static final String SOURCE_SEPARATOR = ", ";

    /** Everyone who has written something into this row, in the order they
     *  did. Empty when nothing has. */
    public List<String> sources() {
        if (source == null || source.isEmpty()) return Collections.emptyList();

        List<String> names = new ArrayList<>();
        for (String one : source.split(",")) {
            String trimmed = one.trim();
            if (!trimmed.isEmpty()) names.add(trimmed);
        }
        return Collections.unmodifiableList(names);
    }
```

Replace `isMine()` and `isEsde()` with:

```java
    /**
     * Whether somebody typed something into this row.
     *
     * <b>Among the contributors, not the only one.</b> A row a provider
     * scraped and a person then corrected is still theirs, and the whole
     * point of a scrape that only fills gaps is that the two can share a row
     * without either losing anything.
     */
    public boolean isMine() {
        return sources().contains(USER);
    }

    /**
     * Whether an ES-DE link owns this row and may replace it.
     *
     * <b>Only when ES-DE is the only contributor.</b> The rule was "a link
     * replaces only what ES-DE brought over", and with several contributors
     * per row that has to mean "brought over all of it": a row a link started
     * and a provider then filled in is no longer ES-DE's to replace, or
     * relinking would throw away the scrape.
     */
    public boolean isEsde() {
        List<String> who = sources();
        return who.isEmpty() || (who.size() == 1 && ESDE.equals(who.get(0)));
    }
```

Add to `Builder`, next to `source`:

```java
        /**
         * Adds one contributor, if it is not already listed.
         *
         * Appended rather than prepended: the order is the order they
         * contributed, and under a priority scrape that is also the order of
         * priority - which is worth being able to read back even though
         * nothing does yet. Adding one twice is a no-op, so re-scraping from
         * the same service does not grow the field for ever.
         */
        public Builder contributor(String name) {
            if (name == null || name.isEmpty()) return this;

            List<String> who = new ArrayList<>();
            if (source != null && !source.isEmpty()) {
                for (String one : source.split(",")) {
                    String trimmed = one.trim();
                    if (!trimmed.isEmpty()) who.add(trimmed);
                }
            }

            if (!who.contains(name)) who.add(name);

            return source(String.join(SOURCE_SEPARATOR, who));
        }
```

Change `with(Field, String)` from `.source(USER)` to `.contributor(USER)`:

```java
    public Meta with(Field field, String value) {
        return but().set(field, value).contributor(USER).build();
    }
```

- [ ] **Step 4: Run the test and watch it pass**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest --tests '*MetaSourcesTest*'
```

Expected: PASS, 7 tests.

- [ ] **Step 5: Run the whole JVM tier — `Meta` is read everywhere**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```sh
git add app/src/main/java/dev/ldlab/zedex/library/meta/Meta.java \
        app/src/test/java/dev/ldlab/zedex/library/meta/MetaSourcesTest.java
git commit -m "feat: a row can name more than one contributor

More than one thing writes a row now - a link, a hand edit, and any
number of providers each filling in what the ones before left out - so
the field that held one name holds a list, joined into the same string
so a store written by an older build still reads.

The two predicates that own a row are generalised rather than replaced:
a link may replace a row only when ES-DE is its sole contributor, and a
hand edit counts whenever the user is among them."
```

---

### Task 2: `Merge` — the field-by-field rule

**Files:**
- Create: `app/src/main/java/dev/ldlab/zedex/library/scrape/Merge.java`
- Test: `app/src/test/java/dev/ldlab/zedex/library/scrape/MergeTest.java` (create)

**Interfaces:**
- Consumes: `Meta.but()`, `Meta.Builder`.
- Produces: `public static Meta Merge.of(Meta base, Meta addition)` — `base` wins field by field unless its value is null; a list wins unless it is empty. Task 5 calls it.

Pure `Meta` in, pure `Meta` out, no `Context` — so it is a JVM test, and the reflective check below is the reason it is a class of its own rather than a private method on `Blend`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/ldlab/zedex/library/scrape/MergeTest.java`:

```java
package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.library.meta.Meta;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * One rule for every field: a later source may fill a gap and may never
 * overwrite.
 *
 * That single rule is what makes scraping from several services safe, and it
 * is why there is no rule per field to get wrong - see the design note, and
 * the paragraph in Scrapers that this reverses.
 */
public class MergeTest {

    @Test
    public void whatTheBaseHasIsKept() {
        Meta base = Meta.at("./A.tap").name("Manic Miner").genre("Arcade").build();
        Meta addition = Meta.at(null).name("MANIC MINER").genre("Platform").build();

        Meta merged = Merge.of(base, addition);

        assertEquals("Manic Miner", merged.name);
        assertEquals("Arcade", merged.genre);
    }

    @Test
    public void whatTheBaseLacksIsTakenFromTheAddition() {
        Meta base = Meta.at("./A.tap").name("Manic Miner").build();
        Meta addition = Meta.at(null).publisher("Bug-Byte").price("£5.95").build();

        Meta merged = Merge.of(base, addition);

        assertEquals("Manic Miner", merged.name);
        assertEquals("Bug-Byte", merged.publisher);
        assertEquals("£5.95", merged.price);
    }

    /** Empty is absent for a list, which is the one place the two differ. */
    @Test
    public void anEmptyListIsAGapAndANonEmptyOneIsNot() {
        Meta base = Meta.at("./A.tap").inputs(Collections.emptyList()).build();
        Meta addition = Meta.at(null).inputs(Arrays.asList("Kempston", "Cursor")).build();

        assertEquals(Arrays.asList("Kempston", "Cursor"), Merge.of(base, addition).inputs);

        Meta held = Meta.at("./A.tap").inputs(Collections.singletonList("Sinclair 1")).build();

        assertEquals(Collections.singletonList("Sinclair 1"), Merge.of(held, addition).inputs);
    }

    @Test
    public void thePathAndTheContributorsAreTheBasesOwn() {
        Meta base = Meta.at("./A.tap").contributor("ZXInfo").build();
        Meta addition = Meta.at("./somewhere-else.tap").source("ScreenScraper").build();

        Meta merged = Merge.of(base, addition);

        assertEquals("./A.tap", merged.path);
        assertEquals(Collections.singletonList("ZXInfo"), merged.sources());
    }

    /**
     * Every field, without naming them.
     *
     * Meta grew from eight fields to twenty and will grow again, and a field
     * added there and forgotten here would silently never merge - the exact
     * failure Meta's own class doc was written about, and one nothing else
     * would catch: the store would simply never carry that field for anybody
     * whose first source did not have it.
     *
     * So this walks the class rather than a list. path and source are
     * excluded because Blend sets both itself.
     */
    @Test
    public void everyFieldIsMerged() throws Exception {
        Meta base = Meta.at("./A.tap").build();
        Meta addition = everythingSet();

        Meta merged = Merge.of(base, addition);

        for (Field field : Meta.class.getDeclaredFields()) {
            if (!Modifier.isPublic(field.getModifiers())
                    || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (field.getName().equals("path") || field.getName().equals("source")) continue;

            Object value = field.get(merged);
            assertNotNull("Merge.of does not carry " + field.getName()
                          + " - add it to the method", value);

            if (value instanceof List) {
                assertTrue("Merge.of does not carry the list " + field.getName(),
                           !((List<?>) value).isEmpty());
            }
        }
    }

    /** Every field of Meta with something in it. Kept beside the test that
     *  uses it so that adding a field here is the obvious fix when the test
     *  above says one is missing. */
    private static Meta everythingSet() {
        return Meta.at(null)
                .name("Manic Miner").desc("A miner.")
                .developer("Matthew Smith").publisher("Bug-Byte")
                .genre("Arcade Game").subgenre("Platform")
                .released("19831001T000000").players("1").rating("0.9")
                .keymap("0:left = q").machine("ZX-Spectrum 48K")
                .inputs(Collections.singletonList("Cursor"))
                .authors(Collections.singletonList("Matthew Smith"))
                .price("£5.95").series("Miner Willy")
                .seriesGames(Collections.singletonList(new Meta.Link("2", "Jet Set Willy")))
                .compilations(Collections.singletonList(new Meta.Link("3", "They Sold a Million")))
                .contents(Collections.singletonList(new Meta.Link("4", "Something")))
                .build();
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest --tests '*MergeTest*'
```

Expected: compilation failure — `cannot find symbol: class Merge`.

- [ ] **Step 3: Write `Merge`**

Create `app/src/main/java/dev/ldlab/zedex/library/scrape/Merge.java`:

```java
package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.library.meta.Meta;

import java.util.List;

/**
 * Two answers about the same game, combined.
 *
 * <b>One rule for every field: a gap may be filled, a value may never be
 * replaced.</b> That is the whole of it, and it is what makes scraping from
 * several services safe to do at all - {@code Scrapers} used to record that
 * merging was rejected because "two sources disagreeing about a name or a
 * year needs a rule per field", and there is no rule per field here because
 * the priority order has already decided who gets asked first. Whoever
 * answers first keeps the field.
 *
 * Its own class rather than a method on {@link Blend} for one reason: it is
 * the only part of the loop with no {@code Context} in it, so it can be
 * tested on the JVM - including {@code MergeTest.everyFieldIsMerged}, which
 * walks {@link Meta} by reflection so that a field added there and forgotten
 * here cannot go unnoticed.
 */
public final class Merge {

    private Merge() {
    }

    /**
     * {@code base}, with anything it is missing taken from {@code addition}.
     *
     * {@code path} and {@code source} are the base's own and are never taken
     * from the addition: the path is the key this row is stored under, and
     * the contributor list is {@link Blend}'s to write once it knows who
     * actually contributed something.
     *
     * A null addition answers the base unchanged, which is what a source that
     * knew nothing amounts to.
     */
    public static Meta of(Meta base, Meta addition) {
        if (addition == null) return base;
        if (base == null) return addition;

        return base.but()
                .name(first(base.name, addition.name))
                .desc(first(base.desc, addition.desc))
                .developer(first(base.developer, addition.developer))
                .publisher(first(base.publisher, addition.publisher))
                .genre(first(base.genre, addition.genre))
                .subgenre(first(base.subgenre, addition.subgenre))
                .released(first(base.released, addition.released))
                .players(first(base.players, addition.players))
                .rating(first(base.rating, addition.rating))
                .keymap(first(base.keymap, addition.keymap))
                .machine(first(base.machine, addition.machine))
                .price(first(base.price, addition.price))
                .series(first(base.series, addition.series))
                .inputs(firstList(base.inputs, addition.inputs))
                .authors(firstList(base.authors, addition.authors))
                .seriesGames(firstList(base.seriesGames, addition.seriesGames))
                .compilations(firstList(base.compilations, addition.compilations))
                .contents(firstList(base.contents, addition.contents))
                .build();
    }

    private static String first(String base, String addition) {
        return base != null && !base.isEmpty() ? base : addition;
    }

    /** Empty is absent for a list - {@link Meta} keeps lists empty rather
     *  than null, so there is no other way for one to say it has nothing. */
    private static <T> List<T> firstList(List<T> base, List<T> addition) {
        return base != null && !base.isEmpty() ? base : addition;
    }
}
```

- [ ] **Step 4: Run the test and watch it pass**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest --tests '*MergeTest*'
```

Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```sh
git add app/src/main/java/dev/ldlab/zedex/library/scrape/Merge.java \
        app/src/test/java/dev/ldlab/zedex/library/scrape/MergeTest.java
git commit -m "feat: combine two answers about one game, gaps only

One rule for every field - a gap may be filled and a value may never be
replaced - which is what removes the objection Scrapers recorded against
merging: there is no rule per field to get wrong, because the priority
order has already decided who is asked first.

Its own class because it is the only part of the loop without a Context
in it, and so the only part a JVM test can walk by reflection. That test
is the point: Meta has twenty fields and will have more, and one added
there and forgotten here would silently never merge for anybody."
```

---

### Task 3: `Scrapers` holds an ordered, enabled list

**Files:**
- Modify: `app/src/main/java/dev/ldlab/zedex/library/scrape/Scrapers.java`
- Modify: `app/src/main/java/dev/ldlab/zedex/storage/Prefs.java`
- Test: `app/src/androidTest/java/dev/ldlab/zedex/library/scrape/ScrapersTest.java` (create)

**Interfaces:**
- Consumes: `Provider.name()`, `Provider.configured()`.
- Produces: `List<Provider> Scrapers.enabled(Context)`, `void Scrapers.save(Context, List<String>)`, `List<String> Scrapers.names(Context)`, `boolean Scrapers.any(Context)`, `Provider.Wanted Scrapers.wanted(Context)`, `String Prefs.KEY_SCRAPERS`. `preferred()` and `withAccount()` are removed; every caller becomes `enabled()`.

Instrumentation, not JVM: it reads `SharedPreferences` and builds real providers.

- [ ] **Step 1: Write the failing test**

Create `app/src/androidTest/java/dev/ldlab/zedex/library/scrape/ScrapersTest.java`:

```java
package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.storage.Prefs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Which sources a scrape asks, and in what order.
 *
 * The bench's own choice is put back afterwards: it is the user's device and
 * the setting they left is the one they were using.
 */
@RunWith(AndroidJUnit4.class)
public class ScrapersTest {

    private Context context;
    private SharedPreferences preferences;
    private String theirs;
    private String theirOldOne;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        preferences = context.getSharedPreferences(Prefs.PREFS, Context.MODE_PRIVATE);

        theirs = preferences.getString(Prefs.KEY_SCRAPERS, null);
        theirOldOne = preferences.getString(Prefs.KEY_SCRAPER, null);
    }

    @After
    public void putItBack() {
        preferences.edit()
                .putString(Prefs.KEY_SCRAPERS, theirs)
                .putString(Prefs.KEY_SCRAPER, theirOldOne)
                .apply();
    }

    private void stored(String scrapers, String oldOne) {
        preferences.edit()
                .putString(Prefs.KEY_SCRAPERS, scrapers)
                .putString(Prefs.KEY_SCRAPER, oldOne)
                .apply();
    }

    private static List<String> namesOf(List<Provider> providers) {
        List<String> names = new ArrayList<>();
        for (Provider provider : providers) names.add(provider.name());
        return names;
    }

    /** Nobody has ever chosen: everything this build has, in its own order. */
    @Test
    public void withNothingStoredEverySourceIsEnabled() {
        stored(null, null);

        assertEquals(Scrapers.names(context), namesOf(Scrapers.enabled(context)));
    }

    /**
     * An older build stored one name, and that was a decision.
     *
     * Migrated faithfully rather than generously: turning "ScreenScraper" into
     * "both of them" would widen what the app fetches, and what it spends a
     * ScreenScraper allowance on, because a feature arrived. That is not ours
     * to decide.
     */
    @Test
    public void anOlderSingleChoiceMigratesToThatOneAlone() {
        List<String> available = Scrapers.names(context);
        assumeTrue("this build has only one source", available.size() > 1);

        stored(null, available.get(1));

        assertEquals(Collections.singletonList(available.get(1)),
                     namesOf(Scrapers.enabled(context)));
    }

    @Test
    public void theStoredOrderIsTheOrderTheyAreAsked() {
        List<String> available = Scrapers.names(context);
        assumeTrue("this build has only one source", available.size() > 1);

        List<String> backwards = new ArrayList<>(available);
        Collections.reverse(backwards);

        Scrapers.save(context, backwards);

        assertEquals(backwards, namesOf(Scrapers.enabled(context)));
    }

    @Test
    public void aSourceLeftOutIsNotAsked() {
        List<String> available = Scrapers.names(context);
        assumeTrue("this build has only one source", available.size() > 1);

        Scrapers.save(context, Collections.singletonList(available.get(0)));

        assertEquals(Collections.singletonList(available.get(0)),
                     namesOf(Scrapers.enabled(context)));
        assertTrue(Scrapers.any(context));
    }

    /** Choosing none is a real choice, and it is not the same as never having
     *  chosen - which is exactly the trap the media setting beside this one
     *  already carries a warning about. */
    @Test
    public void choosingNoneMeansNoneRatherThanTheDefault() {
        Scrapers.save(context, Collections.emptyList());

        assertTrue(Scrapers.enabled(context).isEmpty());
        assertFalse(Scrapers.any(context));
    }

    /** A name from a build that had credentials this one has not. */
    @Test
    public void aStoredNameThisBuildDoesNotHaveIsIgnored() {
        List<String> available = Scrapers.names(context);

        List<String> withAGhost = new ArrayList<>(available);
        withAGhost.add("A Service That Went Away");

        Scrapers.save(context, withAGhost);

        assertEquals(available, namesOf(Scrapers.enabled(context)));
    }

    @Test
    public void savingOneNameIsReadBackAsAString() {
        Scrapers.save(context, Arrays.asList("ZXInfo"));

        // The check-prefs rule, asserted rather than assumed: a getter of the
        // wrong type throws only when the key is present, which is how a
        // mismatch survives every fresh-install test.
        assertEquals("ZXInfo", preferences.getString(Prefs.KEY_SCRAPERS, null));
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=dev.ldlab.zedex.library.scrape.ScrapersTest
```

Expected: compilation failure — `cannot find symbol: method enabled(Context)`, `method save(Context,List)`, `variable KEY_SCRAPERS`.

- [ ] **Step 3: Add the key to `Prefs`**

In `Prefs.java`, add immediately above `KEY_SCRAPER` and leave `KEY_SCRAPER` in place:

```java
    /**
     * Which services a scrape asks, by {@code Provider.name()}, in the order
     * it asks them - one per line.
     *
     * A {@code String} and not a {@code StringSet}, although it is a set of
     * choices, because a set has no order and the order <em>is</em> the
     * feature: the first source to answer about a field keeps it, and every
     * later one may only fill what is still missing.
     *
     * A new key rather than a reuse of {@link #KEY_SCRAPER}, which held a
     * single name written by a {@code ListPreference}. The two must never
     * disagree about what they hold - see {@code scripts/check-prefs.py} for
     * why a key with two types is a crash that survives every test on a fresh
     * install.
     *
     * <b>Absent is not empty.</b> Nothing stored means nobody has chosen and
     * every source this build has is used; an empty value means somebody
     * deliberately turned them all off, which switches scraping off and is a
     * real thing to want. The same distinction {@link #KEY_SCRAPE_MEDIA}
     * turns on, and it is lost the moment the two are collapsed.
     */
    public static final String KEY_SCRAPERS = "scrapers";
```

Add a line to `KEY_SCRAPER`'s own doc, replacing its last paragraph:

```java
     * <b>Superseded by {@link #KEY_SCRAPERS}</b>, which holds several names in
     * priority order. This is read once to migrate a choice made by an older
     * build - faithfully, meaning that one name becomes that one source and
     * the others off - and never written again.
```

- [ ] **Step 4: Rewrite `Scrapers`**

Replace the class doc's "One service answers everything" paragraph and the `preferred`/`withAccount`/`chosen` methods. The whole of `Scrapers.java` after the change:

```java
package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.storage.Prefs;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which providers a scrape uses, and in what order.
 *
 * The one place that knows there is more than one, so no entry point grows its
 * own {@code new ScreenScraper(...)} and its own idea of what to do when none
 * is configured.
 *
 * <b>Several services answer, in a priority order.</b> This used to say the
 * opposite - that one service answered everything, and that merging two was
 * rejected because "two sources disagreeing about a name or a year needs a
 * rule per field, ownership stops being one provider name, and every conflict
 * is invisible when it goes wrong". Each of those is answered rather than
 * overridden:
 *
 * <ul>
 * <li>There is one rule for every field, not a rule per field: a source may
 *     fill a gap and may never overwrite ({@link Merge}). The order decides
 *     who gets the gap.</li>
 * <li>Ownership is still legible; it is plural. {@code Meta.source} is a list
 *     of contributors, and the two predicates that read it generalised
 *     cleanly - a link owns a row only when ES-DE is its sole contributor.</li>
 * <li>The only conflict anybody can lose something to is a picture, and a
 *     picture is the one thing that can be shown. A sweep never replaces one;
 *     a one-game scrape puts the alternatives on screen side by side.</li>
 * </ul>
 *
 * Rows keep carrying the names of whoever wrote them, so {@code
 * Sweep.Only.NOT_SCRAPED} still means "not scraped by this app" rather than
 * "not scraped by whichever is currently first".
 */
public final class Scrapers {

    private Scrapers() {
    }

    /** One per line - a service name has spaces in it and could one day have
     *  a comma, and a newline is the one character it will not have. */
    private static final String SEPARATOR = "\n";

    /**
     * Every provider this build can offer, best first.
     *
     * ZXInfo needs no credentials at all, so it is always here; ScreenScraper
     * is only here when the build was given a developer id and password, which
     * a source clone was not. That ordering is the default priority order for
     * anybody who has never chosen.
     */
    public static List<Provider> all(Context context) {
        return all(context, null, null);
    }

    /**
     * Every provider, with the user's own ScreenScraper account when they have
     * set one.
     *
     * Their login buys a real daily allowance and is the only mitigation
     * available for the fact that the shared developer credentials are in the
     * APK and readable - see {@code Prefs.KEY_SCRAPER_USER}. It does not
     * replace the developer id, which identifies the application and is sent
     * either way.
     *
     * The account is ScreenScraper's alone. ZXInfo has no accounts, so a login
     * set here changes nothing for it - worth knowing rather than surprising.
     */
    private static List<Provider> all(Context context, String user, String password) {
        List<Provider> providers = new ArrayList<>();

        Provider screenScraper = user == null
                ? new ScreenScraper(context, new Http.Real(context))
                : new ScreenScraper(context, new Http.Real(context), user, password);
        if (screenScraper.configured()) providers.add(screenScraper);

        providers.add(new ZxInfo(new Http.Real(context)));

        return providers;
    }

    /** The names, for the settings list. Not translated: they are the
     *  services' own names. */
    public static List<String> names(Context context) {
        List<String> names = new ArrayList<>();
        for (Provider provider : all(context)) names.add(provider.name());
        return names;
    }

    /**
     * The sources to ask, in the order to ask them, with the user's own
     * account applied.
     *
     * Empty when this build can scrape from nothing, and empty when somebody
     * has turned every source off - which is a choice and not a fault.
     *
     * A stored name this build does not have - a provider removed, or a build
     * without the credentials the choice was made against - is skipped rather
     * than failing the lot.
     */
    public static List<Provider> enabled(Context context) {
        SharedPreferences preferences =
                context.getSharedPreferences(Prefs.PREFS, Context.MODE_PRIVATE);

        String user = preferences.getString(Prefs.KEY_SCRAPER_USER, "");
        String password = preferences.getString(Prefs.KEY_SCRAPER_PASSWORD, "");

        // Half a login is a request that authenticates as nobody and is
        // refused, which reads to the user as the service being broken.
        boolean hasAccount = user != null && !user.trim().isEmpty()
                && password != null && !password.isEmpty();

        List<Provider> available = hasAccount
                ? all(context, user.trim(), password)
                : all(context);

        List<String> wanted = order(preferences, available);

        List<Provider> chosen = new ArrayList<>();
        for (String name : wanted) {
            for (Provider provider : available) {
                if (provider.name().equals(name)) {
                    chosen.add(provider);
                    break;
                }
            }
        }
        return chosen;
    }

    /**
     * The stored order, or what to do when there is none.
     *
     * <b>Absent is not empty.</b> Nothing stored means nobody has chosen and
     * everything is used; an empty value means somebody turned them all off.
     * {@code getString} answers null for the first and "" for the second, and
     * collapsing the two would make "none" unselectable - the same trap
     * {@code Prefs.KEY_SCRAPE_MEDIA} carries a warning about.
     */
    private static List<String> order(SharedPreferences preferences,
                                      List<Provider> available) {
        String stored = preferences.getString(Prefs.KEY_SCRAPERS, null);

        if (stored == null) return migrated(preferences, available);
        if (stored.isEmpty()) return new ArrayList<>();

        List<String> names = new ArrayList<>();
        for (String line : stored.split(SEPARATOR)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) names.add(trimmed);
        }
        return names;
    }

    /**
     * What an older build's single choice becomes.
     *
     * <b>Faithfully, not generously:</b> one stored name becomes that one
     * source and every other off. Widening what the app fetches - and what it
     * spends a ScreenScraper allowance on - because a feature arrived is not a
     * decision to make on somebody's behalf. Nothing stored at all means
     * nobody ever chose, and that gets the new default: everything, in
     * {@link #all}'s order.
     *
     * Not written back. Reading it every time costs one string lookup, and
     * writing it would turn "the user has never chosen" into "the user chose
     * exactly this", which is a lie that cannot be undone.
     */
    private static List<String> migrated(SharedPreferences preferences,
                                         List<Provider> available) {
        String single = preferences.getString(Prefs.KEY_SCRAPER, null);

        if (single != null && !single.isEmpty()) {
            return new ArrayList<>(java.util.Collections.singletonList(single));
        }

        List<String> everything = new ArrayList<>();
        for (Provider provider : available) everything.add(provider.name());
        return everything;
    }

    /** Stores the sources to use, in order. An empty list is stored as an
     *  empty value, which is "none" and not "nobody has chosen". */
    public static void save(Context context, List<String> namesInOrder) {
        context.getSharedPreferences(Prefs.PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(Prefs.KEY_SCRAPERS, String.join(SEPARATOR, namesInOrder))
                .apply();
    }

    /** Whether anything can be scraped from at all. Every menu row checks this
     *  before offering itself. */
    public static boolean any(Context context) {
        return !enabled(context).isEmpty();
    }

    /**
     * Which media a scrape should fetch, from the person's own choice.
     *
     * One place, read by both entry points - the popup's one-game scrape and
     * the sweep - so that the two cannot disagree about what a scrape takes.
     *
     * <b>Absent is not empty.</b> Nothing stored means nobody has chosen and
     * the default applies; an empty set means somebody deliberately chose
     * metadata only, which is legitimate and the cheapest scrape there is.
     */
    public static Provider.Wanted wanted(Context context) {
        Set<String> chosen = context
                .getSharedPreferences(Prefs.PREFS, Context.MODE_PRIVATE)
                .getStringSet(Prefs.KEY_SCRAPE_MEDIA, null);

        return chosen == null ? Provider.Wanted.usual() : Provider.Wanted.of(chosen);
    }
}
```

Note the unused imports (`Arrays`, `LinkedHashSet`) — remove any the compiler flags.

- [ ] **Step 5: Fix the three callers so it compiles**

`Scrapers.preferred` and `Scrapers.withAccount` are gone. Change each call site to take the first enabled provider for now; tasks 6–8 replace them properly.

- `screen/ScrapeOneGame.java:58` — `Provider provider = Scrapers.withAccount(activity);` becomes:
  ```java
  List<Provider> sources = Scrapers.enabled(activity);
  if (sources.isEmpty()) return;
  Provider provider = sources.get(0);
  ```
- `screen/ScrapeManyActivity.java:142` — `provider = Scrapers.withAccount(this);` becomes:
  ```java
  List<Provider> sources = Scrapers.enabled(this);
  provider = sources.isEmpty() ? null : sources.get(0);
  ```
- `screen/SettingsActivity.java:2011-2022` — the `ListPreference` block: replace `Scrapers.preferred(getActivity())` with:
  ```java
  java.util.List<Provider> using = Scrapers.enabled(getActivity());
  scraper.setSummary(using.isEmpty() ? "" : using.get(0).name());
  ```

Then find any others:

```sh
grep -rn "Scrapers.preferred\|Scrapers.withAccount" app/src
```

Expected after the edits: no matches.

- [ ] **Step 6: Build, then run the test**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug
scripts/check-prefs.py
env JAVA_HOME=/opt/android-studio/jbr ./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=dev.ldlab.zedex.library.scrape.ScrapersTest
```

Expected: build OK, `check-prefs.py` clean, 7 tests PASS (two may report as skipped on a build with only ZXInfo — that is the `assumeTrue`, and it is correct).

- [ ] **Step 7: Commit**

```sh
git add app/src/main/java/dev/ldlab/zedex/library/scrape/Scrapers.java \
        app/src/main/java/dev/ldlab/zedex/storage/Prefs.java \
        app/src/main/java/dev/ldlab/zedex/screen/ScrapeOneGame.java \
        app/src/main/java/dev/ldlab/zedex/screen/ScrapeManyActivity.java \
        app/src/main/java/dev/ldlab/zedex/screen/SettingsActivity.java \
        app/src/androidTest/java/dev/ldlab/zedex/library/scrape/ScrapersTest.java
git commit -m "feat: several sources, in an order

Scrapers grows from which provider to which ones and in what order. A
new key holds them, one name per line: a String and not a StringSet
because a set has no order, and the order is the feature.

An older build's single choice migrates faithfully rather than
generously - that one source, the others off. Widening what the app
fetches, and what it spends a ScreenScraper allowance on, because a
feature arrived is not ours to decide. Nothing stored at all is somebody
who never chose, and gets the new default of everything.

The three call sites take the first enabled source for now; the tasks
that follow give each of them the whole list."
```

---

### Task 4: `Downloads` writes where it is told

**Files:**
- Modify: `app/src/main/java/dev/ldlab/zedex/library/meta/Artwork.java`
- Modify: `app/src/main/java/dev/ldlab/zedex/library/scrape/Downloads.java`
- Modify: `app/src/main/java/dev/ldlab/zedex/library/scrape/Scrape.java`
- Test: `app/src/androidTest/java/dev/ldlab/zedex/library/scrape/DownloadsTest.java` (modify)

**Interfaces:**
- Consumes: `Artwork.fileFor(Context, String, String, String)`.
- Produces:
  - `interface Downloads.Destination { File fileFor(String folder, String extension); }`
  - `Downloads.Result fetch(Context, Http, Provider, String path, List<Medium>, Destination)`
  - `Downloads.Destination Downloads.into(Context, String relativePath)` — the media folder
  - `Downloads.Destination Downloads.staging(Context, String relativePath)` — the staging area
  - `File Artwork.stagingFileFor(Context, String, String, String)`, `File Artwork.stagingRoot(Context)`, `void Artwork.clearStaging(Context)`, `void Artwork.removeOthers(Context, String relativePath, String folder, String keepExtension)`

A pure refactor with one behaviour change: `Downloads.fetch` no longer calls `Artwork.forget`, and `Scrape.apply` does it instead, in a `finally`.

- [ ] **Step 1: Write the failing test**

Append to `app/src/androidTest/java/dev/ldlab/zedex/library/scrape/DownloadsTest.java`:

```java
    /**
     * A download goes where the caller says, not where Downloads assumes.
     *
     * The seam a staged scrape needs: a one-game scrape fetches every source's
     * pictures before anybody chooses between them, so they cannot land on top
     * of what is already on disk on the way past.
     */
    @Test
    public void mediaLandWhereTheDestinationSays() throws Exception {
        File elsewhere = new File(context.getCacheDir(), "downloads-test-" + System.nanoTime());
        assertTrue(elsewhere.mkdirs());

        try {
            Downloads.Destination into = (folder, extension) -> {
                File file = new File(new File(elsewhere, folder), "Game." + extension);
                File parent = file.getParentFile();
                if (parent != null) parent.mkdirs();
                return file;
            };

            Medium cover = new Medium("covers", "png", "http://example.invalid/cover.png", null);

            Downloads.Result result = Downloads.fetch(
                    context, new WritesBytes("a cover".getBytes()), new NoRefusals(),
                    "./Game.tap", Collections.singletonList(cover), into);

            assertEquals(1, result.saved);
            assertTrue(new File(new File(elsewhere, "covers"), "Game.png").isFile());
            // And nothing at all in the media folder, which is the whole point.
            assertFalse(Artwork.fileFor(context, "./Game.tap", "covers", "png").isFile());
        } finally {
            deleteTree(elsewhere);
        }
    }

    /** An Http that writes the same bytes for every url. */
    private static final class WritesBytes implements Http {
        private final byte[] bytes;

        WritesBytes(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override public Reply get(String url) {
            throw new AssertionError("no page should be fetched");
        }

        @Override public String save(String url, File into) throws IOException {
            File parent = into.getParentFile();
            if (parent != null) parent.mkdirs();

            try (java.io.FileOutputStream out = new java.io.FileOutputStream(into)) {
                out.write(bytes);
            }
            return null;   // no hash offered, which is ZXInfo's ordinary case
        }
    }

    private static final class NoRefusals implements Provider {
        @Override public String name() { return "Fake"; }
        @Override public boolean configured() { return true; }
        @Override public java.util.List<Candidate> search(Game game) { return null; }
        @Override public Scraped fetch(Candidate candidate, Wanted wanted) { return null; }
        @Override public Quota quota() { return Quota.unknown(); }
        @Override public int costPerGame(Wanted wanted) { return 1; }

        @Override public ScrapeException refusalFor(int status) {
            return new ScrapeException(ScrapeException.Kind.NETWORK, "status " + status);
        }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
```

Add whatever imports the file is missing (`java.io.IOException`, `java.util.Collections`, `dev.ldlab.zedex.library.meta.Artwork`, `assertFalse`). Read the top of `DownloadsTest.java` first — it already has a `context` field and some of these.

- [ ] **Step 2: Run it and watch it fail**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=dev.ldlab.zedex.library.scrape.DownloadsTest
```

Expected: compilation failure — `cannot find symbol: class Destination`, and `fetch` takes five arguments.

- [ ] **Step 3: Add the staging half of `Artwork`**

In `Artwork.java`, beside `fileFor`:

```java
    /**
     * Where a scrape puts a picture nobody has chosen yet.
     *
     * A folder of the media folder's own, so a staged cover is under the same
     * root and a move into place is a rename rather than a copy across
     * filesystems. Nothing reads it: {@link #PICTURE_FOLDERS} names the
     * folders it looks in, and this is not one of them, so a staged file is
     * invisible to every part of the app until it is committed.
     */
    private static final String STAGING = ".staging";

    public static File stagingRoot(Context context) {
        return new File(Storage.mediaDirectory(context), STAGING);
    }

    /** {@link #fileFor}, but in the staging area. */
    public static File stagingFileFor(Context context, String relativePath,
                                      String folder, String extension) {
        File file = new File(new File(stagingRoot(context), folder),
                             withoutExtension(relativePath) + "." + extension);

        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            Log.w(TAG, "cannot make " + parent);
        }
        return file;
    }

    /**
     * Empties the staging area.
     *
     * <b>Called on the way in, not on the way out.</b> A scrape killed
     * mid-flight - the activity gone, the process gone, somebody pressing
     * Back - never reaches its own cleanup, and a leftover from last time
     * would be offered as though this run had fetched it. The same lesson
     * {@code RecentsTest.dropAnyLeftOver} is in the suite for.
     */
    public static void clearStaging(Context context) {
        deleteTree(stagingRoot(context));
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);

        if (file.exists() && !file.delete()) Log.w(TAG, "cannot remove " + file);
    }

    /**
     * Removes this game's other files in one folder, keeping the extension
     * named.
     *
     * A cover replaced by one from another service is often a different
     * format, and {@link #PICTURE_EXTENSIONS} resolves png before jpg - so a
     * new {@code covers/X.jpg} written beside an old {@code covers/X.png}
     * leaves the old one on screen and the choice looking as though it did
     * nothing at all.
     */
    public static void removeOthers(Context context, String relativePath,
                                    String folder, String keepExtension) {
        for (String extension : allExtensions()) {
            if (extension.equalsIgnoreCase(keepExtension)) continue;

            File other = new File(new File(Storage.mediaDirectory(context), folder),
                                  withoutExtension(relativePath) + "." + extension);
            if (other.isFile() && !other.delete()) Log.w(TAG, "cannot remove " + other);
        }
    }

    /** Every extension anything under the media folder is ever written with,
     *  so that replacing one file removes the others that would outrank it. */
    private static String[] allExtensions() {
        return new String[] {
            "png", "jpg", VIDEO_EXTENSION, "pdf", "txt", POKE_EXTENSION, MUSIC_EXTENSION,
        };
    }
```

- [ ] **Step 4: Give `Downloads` a `Destination`**

In `Downloads.java`:

Add the interface and the two factories, above `Result`:

```java
    /**
     * Where one game's media should be written.
     *
     * Here rather than assumed, because a one-game scrape fetches from every
     * source before anybody chooses between them: those files must not land on
     * top of what is already on disk on the way past. A sweep passes {@link
     * #into} and writes straight to the media folder, since it only ever asks
     * for folders that are empty and so has nothing to protect.
     */
    public interface Destination {
        File fileFor(String folder, String extension);
    }

    /** The media folder itself - what a scrape that has nothing to choose
     *  between writes to. */
    public static Destination into(Context context, String relativePath) {
        return (folder, extension) -> Artwork.fileFor(context, relativePath, folder, extension);
    }

    /** The staging area, for media that will be chosen between before any of
     *  them is kept. */
    public static Destination staging(Context context, String relativePath) {
        return (folder, extension) ->
                Artwork.stagingFileFor(context, relativePath, folder, extension);
    }
```

Change `fetch`'s signature and drop the `Artwork.forget` block:

```java
    public static Result fetch(Context context, Http http, Provider provider,
                               String relativePath, List<Medium> media,
                               Destination destination)
            throws ScrapeException {
        int saved = 0;
        int failed = 0;

        for (Medium medium : media) {
            try {
                if (save(context, http, relativePath, medium, destination)) saved++;
                else failed++;
            } catch (Http.Refused refused) {
                stopIfHopeless(provider, refused);
                failed++;
            }
        }

        return new Result(saved, failed);
    }
```

`Artwork.forget` is now the caller's, because a staged file must **not** invalidate the cache — the file is not where anything looks yet. Note that in the javadoc:

```java
    /**
     * Fetches every medium for one game into {@code destination}.
     *
     * <b>The caller forgets the game from {@code Artwork}'s caches, not this
     * method.</b> A miss is cached, so a cover just written stays invisible
     * until something clears it - but a staged file is not where anything
     * looks, and forgetting on its account would throw away the lookups for
     * nothing. {@code Scrape.apply} and {@code Blend.commit} each do it at the
     * point the files actually become visible.
     */
```

Thread `destination` through the three private methods that currently call `Artwork.fileFor`:

```java
    private static boolean save(Context context, Http http, String relativePath,
                                Medium medium, Destination destination) throws Http.Refused {
        File into = destination.fileFor(medium.folder, medium.extension);
        ...
            if (isScreenDump(medium)) {
                return convert(context, relativePath, medium.folder, into, destination);
            }

            if (isZippedMusic(medium)) {
                return unzip(context, relativePath, medium.folder, into, destination);
            }
        ...
    }

    private static boolean convert(Context context, String relativePath,
                                   String folder, File dump, Destination destination) {
        File into = destination.fileFor(folder, ScreenPicture.EXTENSION);
        ...
    }

    private static boolean unzip(Context context, String relativePath,
                                 String folder, File zip, Destination destination) {
        File into = destination.fileFor(folder, "ay");
        ...
    }
```

- [ ] **Step 5: Make `Scrape.apply` forget, in a `finally`**

In `Scrape.java`, replace the body of `apply`:

```java
    public static Downloads.Result apply(Context context, Provider provider, Http http,
                                         Candidate candidate, String path,
                                         Provider.Wanted wanted) throws ScrapeException {
        Provider.Scraped scraped = provider.fetch(candidate, wanted);

        Metadata.put(context, owned(scraped.meta, path, provider.name()));

        try {
            return Downloads.fetch(context, http, provider, path, scraped.media,
                                   Downloads.into(context, path));
        } finally {
            // Whatever went wrong, what did arrive has to become visible - a
            // scrape stopped by a spent quota still fetched the covers it got
            // to, and leaving them behind a cached miss would waste them.
            // Downloads used to do this and cannot any more: it no longer
            // knows whether it wrote where anybody looks.
            Artwork.forget(path);
        }
    }
```

Add `import dev.ldlab.zedex.library.meta.Artwork;` to `Scrape.java`.

- [ ] **Step 6: Build and run the download tests**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug
env JAVA_HOME=/opt/android-studio/jbr ./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=dev.ldlab.zedex.library.scrape.DownloadsTest
env JAVA_HOME=/opt/android-studio/jbr ./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=dev.ldlab.zedex.library.scrape.ScrapeTest
```

Expected: both PASS. `ScrapeTest` is the regression that says the refactor changed nothing.

- [ ] **Step 7: Commit**

```sh
git add app/src/main/java/dev/ldlab/zedex/library/meta/Artwork.java \
        app/src/main/java/dev/ldlab/zedex/library/scrape/Downloads.java \
        app/src/main/java/dev/ldlab/zedex/library/scrape/Scrape.java \
        app/src/androidTest/java/dev/ldlab/zedex/library/scrape/DownloadsTest.java
git commit -m "refactor: a download goes where the caller says

The seam a staged scrape needs. Scraping one game from several sources
fetches every source's pictures before anybody chooses between them, so
they cannot land on top of what is already on disk on the way past.

Artwork gains a staging area under the media folder - a rename into
place rather than a copy across filesystems - and removeOthers, because
a new covers/X.jpg beside an old covers/X.png leaves the old one on
screen: png outranks jpg, and the choice looks as though it did nothing.

Artwork.forget moves out of Downloads and into its callers. Downloads no
longer knows whether it wrote anywhere anybody looks."
```

---

### Task 5: `Blend` — the loop, filling gaps

**Files:**
- Create: `app/src/main/java/dev/ldlab/zedex/library/scrape/Blend.java`
- Create: `app/src/androidTest/java/dev/ldlab/zedex/library/scrape/Fakes.java`
- Test: `app/src/androidTest/java/dev/ldlab/zedex/library/scrape/BlendTest.java` (create)

**Interfaces:**
- Consumes: `Merge.of`, `Scrape.candidates`, `Scrape.certain`, `Downloads.fetch`/`into`/`staging`, `Metadata.put`, `Artwork.picture`.
- Produces: everything in the code block below. Tasks 6, 7 and 8 all call `Blend.run`.

```java
public enum  Blend.Media { FILL_GAPS, OFFER_ALTERNATIVES }
public interface Blend.Chooser {
    Candidate choose(String sourceName, List<Candidate> found, String game);
}
public interface Blend.Cancellable { boolean cancelled(); }
public final class Blend.Staged {
    public final String folder, extension, source;
    public final File file;
    public final boolean contested;
    public final File existing;          // what is already on disk, or null
}
public final class Blend.Failure {
    public final String source;
    public final ScrapeException why;
}
public final class Blend.Result {
    public final Meta meta;
    public final int installed;          // files written straight to the media folder
    public final List<Staged> staged;    // awaiting commit
    public final List<String> consulted;
    public final List<Failure> failures;
    public final boolean ambiguous;      // somebody was asked and did not choose
    public boolean anythingContested();
}
public static Blend.Result run(Context, List<Provider> sources, Http, Entry,
                               String path, Provider.Wanted, Blend.Media,
                               Blend.Chooser, Blend.Cancellable);
public static int commit(Context, String path, List<Blend.Staged> chosen);
public static Blend.Staged staged(String folder, String extension, String source,
                                  File file, boolean contested, File existing);
```

**`existing`, `ambiguous`, `Cancellable` and the `staged` factory are defined here and not retrofitted later**, although only tasks 7 and 8 read them. Widening a constructor across three tasks is how an implementer reading one task in isolation gets it wrong; each is documented below at the point it is declared.

This task builds the whole class but tests only the `FILL_GAPS` half; task 6 tests staging.

- [ ] **Step 1: Extract the shared fakes**

Create `app/src/androidTest/java/dev/ldlab/zedex/library/scrape/Fakes.java` by lifting `Fake`, `Answer` and `NoHttp` out of `SweepTest` verbatim, making them package-visible, and adding what `Blend` needs:

```java
package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.library.meta.Meta;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Providers and an Http with no service behind them.
 *
 * Shared by {@code SweepTest} and {@code BlendTest} rather than private to
 * one, since both need the same thing for the same reason: every branch a
 * real run can take - a spent quota, refused credentials, a thread limit -
 * is one a fake can produce on demand and none of them can be arranged
 * reliably against a live service.
 */
final class Fakes {

    private Fakes() {
    }

    static Candidate exact(String name) {
        return new Candidate("h-" + name, name, "1987", "Imagine", true);
    }

    static Candidate guess(String name) {
        return new Candidate("h-" + name, name, "1987", "Imagine", false);
    }

    /** What a fake answers a search with: candidates, or a reason it cannot. */
    interface Answer {
        List<Candidate> to(Provider.Game game) throws ScrapeException;
    }

    /** What a fake answers a fetch with. */
    interface Facts {
        Meta about(Candidate candidate);
    }

    /**
     * A provider with no service behind it.
     *
     * Records what it was asked, which is how the tests that care about a
     * request <em>not</em> being made can say so.
     */
    static final class Fake implements Provider {

        final List<String> searched = new ArrayList<>();
        final List<String> fetched = new ArrayList<>();

        /** Which folders it was asked to resolve, per fetch. */
        final List<java.util.Set<String>> wantedOf = new ArrayList<>();

        private final String name;

        Answer answer = game -> Collections.singletonList(exact(game.filename()));
        Facts facts = candidate -> Meta.at(null)
                .name(candidate.name).desc("from the fake")
                .developer("Taito").publisher("Imagine")
                .genre("Action").released("19870101T000000")
                .players("1").rating("0.7500")
                .build();

        List<Medium> media = Collections.emptyList();
        Quota quota = Quota.unknown();

        Fake() {
            this("Fake");
        }

        Fake(String name) {
            this.name = name;
        }

        @Override public String name() { return name; }
        @Override public boolean configured() { return true; }
        @Override public Quota quota() { return quota; }

        /** The same arithmetic ScreenScraper uses, since these tests were
         *  written against a provider whose media are requests. */
        @Override public int costPerGame(Wanted wanted) { return 1 + wanted.requests(); }

        @Override
        public List<Candidate> search(Game game) throws ScrapeException {
            searched.add(game.filename());
            return answer.to(game);
        }

        @Override
        public Scraped fetch(Candidate candidate, Wanted wanted) {
            fetched.add(candidate.name);
            wantedOf.add(wanted.folders());

            List<Medium> mine = new ArrayList<>();
            for (Medium medium : media) {
                if (wanted.wants(medium.folder)) mine.add(medium);
            }
            return new Scraped(facts.about(candidate), mine);
        }

        @Override
        public ScrapeException refusalFor(int status) {
            return new ScrapeException(ScrapeException.Kind.NETWORK, "status " + status);
        }
    }

    /** No medium is ever asked for by the tests that use this. */
    static class NoHttp implements Http {
        @Override public Reply get(String url) {
            throw new AssertionError("nothing should be fetching a page itself");
        }
        @Override public String save(String url, File into) {
            throw new AssertionError("no media were wanted");
        }
    }

    /** Writes the url's own bytes, so two sources' pictures differ and one
     *  source's picture is stable. */
    static final class WritesTheUrl implements Http {
        @Override public Reply get(String url) {
            throw new AssertionError("nothing should be fetching a page itself");
        }

        @Override public String save(String url, File into) throws IOException {
            File parent = into.getParentFile();
            if (parent != null) parent.mkdirs();

            try (FileOutputStream out = new FileOutputStream(into)) {
                out.write(url.getBytes("UTF-8"));
            }
            return null;
        }
    }
}
```

Then delete `Fake`, `Answer`, `exact`, `guess` and `NoHttp` from `SweepTest.java` and point it at `Fakes` (`Fakes.Fake`, `Fakes.exact(...)`, `new Fakes.NoHttp()`). `SweepTest`'s `Writes` stays where it is — it is that test's own.

Run `SweepTest` before going further; it must still pass unchanged:

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=dev.ldlab.zedex.library.scrape.SweepTest
```

- [ ] **Step 2: Commit the extraction on its own**

```sh
git add app/src/androidTest/java/dev/ldlab/zedex/library/scrape/Fakes.java \
        app/src/androidTest/java/dev/ldlab/zedex/library/scrape/SweepTest.java
git commit -m "test: share the fake provider between the sweep and what comes next

Lifted out of SweepTest unchanged, plus a fetch that honours what it was
asked for and an Http that writes the url as the file's bytes - which is
how two sources come to offer pictures that genuinely differ."
```

- [ ] **Step 3: Write the failing test**

Create `app/src/androidTest/java/dev/ldlab/zedex/library/scrape/BlendTest.java`. Model the `setUp`/`putItBack` store handling on `SweepTest`'s — read it and copy the pattern, since the bench's store is somebody's whole scraped collection.

```java
package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.library.Entry;
import dev.ldlab.zedex.library.meta.Artwork;
import dev.ldlab.zedex.library.meta.Meta;
import dev.ldlab.zedex.library.meta.Metadata;
import dev.ldlab.zedex.storage.Storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * One game across several sources, in order.
 *
 * The rule under test throughout: a source may fill a gap and may never
 * overwrite. Everything else here - the title search, stopping early, which
 * folders a later source is asked for - follows from it.
 */
@RunWith(AndroidJUnit4.class)
public class BlendTest {

    private static final String PATH = "./zedex-blend-test/Game.tap";

    private Context context;
    private File store;
    private byte[] theirs;

    @Before
    public void setUp() throws IOException {
        context = ApplicationProvider.getApplicationContext();

        File root = Storage.root(context);
        assumeTrue("the data folder is not usable on this device: " + root,
                   root.isDirectory() && Storage.isWritable(root));

        store = new File(Storage.libraryDirectory(context), "metadata.json");
        theirs = store.isFile() ? Files.readAllBytes(store.toPath()) : null;

        Metadata.clear(context);
        Artwork.clearStaging(context);
        clearMediaFor(PATH);
    }

    @After
    public void putItBack() throws IOException {
        Artwork.clearStaging(context);
        clearMediaFor(PATH);

        if (store == null) return;

        if (theirs == null) {
            store.delete();
        } else {
            try (FileOutputStream out = new FileOutputStream(store)) {
                out.write(theirs);
            }
        }
        Metadata.clear(context);
    }

    private void clearMediaFor(String path) {
        for (String folder : Arrays.asList("covers", "screenshots", "titlescreens")) {
            for (String extension : Arrays.asList("png", "jpg")) {
                File file = Artwork.fileFor(context, path, folder, extension);
                if (file.isFile()) file.delete();
            }
        }
        Artwork.forget(path);
    }

    /** A row with no uri behind it: nothing here reads bytes, because no fake
     *  ever asks for a hash. */
    private static Entry game(String name) {
        Entry entry = new Entry();
        entry.name = name;
        entry.kind = Entry.Kind.FILE;
        entry.size = 4096;
        return entry;
    }

    private Blend.Result run(List<Provider> sources, Http http, Blend.Media media,
                             Provider.Wanted wanted, Blend.Chooser chooser) {
        return Blend.run(context, sources, http, game("Game.tap"), PATH,
                         wanted, media, chooser, () -> false);
    }

    /** Asks for nothing and answers nothing: the tests that must not be asked
     *  assert on this having been left alone. */
    private static final class NeverAsked implements Blend.Chooser {
        int asked;

        @Override
        public Candidate choose(String sourceName, List<Candidate> found, String game) {
            asked++;
            return null;
        }
    }

    // --- the merge, end to end ----------------------------------------------------

    /**
     * The second source fills what the first left out and touches nothing
     * else.
     */
    @Test
    public void alaterSourceFillsGapsAndOverwritesNothing() {
        Fakes.Fake first = new Fakes.Fake("First");
        first.facts = candidate -> Meta.at(null).name("Manic Miner").genre("Arcade").build();

        Fakes.Fake second = new Fakes.Fake("Second");
        second.facts = candidate -> Meta.at(null)
                .name("MANIC MINER").genre("Platform").publisher("Bug-Byte").build();

        Blend.Result result = run(Arrays.asList(first, second), new Fakes.NoHttp(),
                                  Blend.Media.FILL_GAPS, Provider.Wanted.nothing(),
                                  new NeverAsked());

        assertEquals("Manic Miner", result.meta.name);
        assertEquals("Arcade", result.meta.genre);
        assertEquals("Bug-Byte", result.meta.publisher);
        assertEquals(Arrays.asList("First", "Second"), result.meta.sources());
    }

    /** And the store has it, not just the answer. */
    @Test
    public void theMergedRowIsStored() {
        Fakes.Fake only = new Fakes.Fake("Only");

        run(Collections.singletonList(only), new Fakes.NoHttp(),
            Blend.Media.FILL_GAPS, Provider.Wanted.nothing(), new NeverAsked());

        Meta stored = Metadata.forPath(context, PATH);
        assertNotNull("nothing was written to the store", stored);
        assertEquals(PATH, stored.path);
        assertEquals(Collections.singletonList("Only"), stored.sources());
    }

    /** A source that contributed nothing is not listed as a contributor. */
    @Test
    public void aSourceThatKnewNothingIsNotAContributor() {
        Fakes.Fake first = new Fakes.Fake("First");
        Fakes.Fake silent = new Fakes.Fake("Silent");
        silent.answer = gameAsked -> Collections.emptyList();

        Blend.Result result = run(Arrays.asList(first, silent), new Fakes.NoHttp(),
                                  Blend.Media.FILL_GAPS, Provider.Wanted.nothing(),
                                  new NeverAsked());

        assertEquals(Collections.singletonList("First"), result.meta.sources());
    }

    // --- which game a later source thinks it is -----------------------------------

    /**
     * A later source is asked about the title, not the filename.
     *
     * Which is what most of them match well on: "MANICM~1.tap" is a filename,
     * and "Manic Miner" is what is in a database.
     */
    @Test
    public void alaterSourceIsAskedAboutTheTitleTheFirstGave() {
        Fakes.Fake first = new Fakes.Fake("First");
        first.facts = candidate -> Meta.at(null).name("Manic Miner").build();

        Fakes.Fake second = new Fakes.Fake("Second");

        run(Arrays.asList(first, second), new Fakes.NoHttp(),
            Blend.Media.FILL_GAPS, Provider.Wanted.nothing(), new NeverAsked());

        assertEquals(Collections.singletonList("Game.tap"), first.searched);
        assertEquals(Collections.singletonList("Manic Miner"), second.searched);
    }

    /** One guess whose title is the known one needs nobody. */
    @Test
    public void oneExactTitleMatchIsCertainEnough() {
        Fakes.Fake first = new Fakes.Fake("First");
        first.facts = candidate -> Meta.at(null).name("Manic Miner").build();

        Fakes.Fake second = new Fakes.Fake("Second");
        second.answer = gameAsked ->
                Collections.singletonList(Fakes.guess("  manic miner  "));

        NeverAsked chooser = new NeverAsked();

        Blend.Result result = run(Arrays.asList(first, second), new Fakes.NoHttp(),
                                  Blend.Media.FILL_GAPS, Provider.Wanted.nothing(),
                                  chooser);

        assertEquals("nobody should have been asked", 0, chooser.asked);
        assertEquals(Arrays.asList("First", "Second"), result.meta.sources());
    }

    /** Three guesses is a question, however close one of them looks. */
    @Test
    public void severalGuessesAreAskedAbout() {
        Fakes.Fake first = new Fakes.Fake("First");
        first.facts = candidate -> Meta.at(null).name("Manic Miner").build();

        Fakes.Fake second = new Fakes.Fake("Second");
        second.answer = gameAsked -> Arrays.asList(
                Fakes.guess("Manic Miner"), Fakes.guess("Manic Miner 2"),
                Fakes.guess("Mining"));

        NeverAsked chooser = new NeverAsked();

        Blend.Result result = run(Arrays.asList(first, second), new Fakes.NoHttp(),
                                  Blend.Media.FILL_GAPS, Provider.Wanted.nothing(),
                                  chooser);

        assertEquals(1, chooser.asked);

        // Asked and unanswered is not the same as never heard of: a sweep
        // counts the two separately, because one is an afternoon with the
        // chooser and the other is the service's coverage.
        assertTrue(result.ambiguous);
    }

    @Test
    public void nothingFoundIsNotAmbiguous() {
        Fakes.Fake silent = new Fakes.Fake("Silent");
        silent.answer = gameAsked -> Collections.emptyList();

        Blend.Result result = run(Collections.singletonList(silent), new Fakes.NoHttp(),
                                  Blend.Media.FILL_GAPS, Provider.Wanted.nothing(),
                                  new NeverAsked());

        assertFalse(result.ambiguous);
    }

    /** A hash is the file itself; a title is what somebody typed on a shelf. */
    @Test
    public void aCertainMatchWinsEvenWhenItsTitleDisagrees() {
        Fakes.Fake first = new Fakes.Fake("First");
        first.facts = candidate -> Meta.at(null).name("Manic Miner").build();

        Fakes.Fake second = new Fakes.Fake("Second");
        second.answer = gameAsked ->
                Collections.singletonList(Fakes.exact("Wanted: Monty Mole"));
        second.facts = candidate -> Meta.at(null).publisher("Gremlin").build();

        NeverAsked chooser = new NeverAsked();

        Blend.Result result = run(Arrays.asList(first, second), new Fakes.NoHttp(),
                                  Blend.Media.FILL_GAPS, Provider.Wanted.nothing(),
                                  chooser);

        assertEquals(0, chooser.asked);
        assertEquals("Manic Miner", result.meta.name);
        assertEquals("Gremlin", result.meta.publisher);
    }

    // --- what a sweep costs -------------------------------------------------------

    /**
     * A source is never asked for a folder that already has a picture.
     *
     * The whole of "do not rewrite artwork we already have", and the reason it
     * costs nothing: a ScreenScraper cover is a mediaJeu.php call against the
     * day's allowance.
     */
    @Test
    public void alaterSourceIsAskedOnlyForTheFoldersStillEmpty() throws IOException {
        write(Artwork.fileFor(context, PATH, "covers", "png"), "a cover already here");
        Artwork.forget(PATH);

        Fakes.Fake only = new Fakes.Fake("Only");

        run(Collections.singletonList(only), new Fakes.NoHttp(), Blend.Media.FILL_GAPS,
            Provider.Wanted.of("covers", "screenshots"), new NeverAsked());

        assertEquals(1, only.wantedOf.size());
        assertEquals(Collections.singleton("screenshots"), only.wantedOf.get(0));
    }

    /** Nothing left to gain is not asked at all. */
    @Test
    public void asourceIsNotConsultedWhenThereIsNothingItCouldAdd() throws IOException {
        Metadata.put(context, everythingKnown());
        write(Artwork.fileFor(context, PATH, "covers", "png"), "a cover already here");
        Artwork.forget(PATH);

        Fakes.Fake first = new Fakes.Fake("First");

        run(Collections.singletonList(first), new Fakes.NoHttp(), Blend.Media.FILL_GAPS,
            Provider.Wanted.of("covers"), new NeverAsked());

        assertTrue("a source was asked with nothing to gain", first.searched.isEmpty());
    }

    /** Every field Meta carries, so that "nothing left to gain" is true. */
    private Meta everythingKnown() {
        return Meta.at(PATH)
                .name("Manic Miner").desc("A miner.")
                .developer("Matthew Smith").publisher("Bug-Byte")
                .genre("Arcade Game").subgenre("Platform")
                .released("19831001T000000").players("1").rating("0.9")
                .keymap("0:left = q").machine("ZX-Spectrum 48K")
                .inputs(Collections.singletonList("Cursor"))
                .authors(Collections.singletonList("Matthew Smith"))
                .price("£5.95").series("Miner Willy")
                .seriesGames(Collections.singletonList(new Meta.Link("2", "Jet Set Willy")))
                .compilations(Collections.singletonList(new Meta.Link("3", "Compilation")))
                .contents(Collections.singletonList(new Meta.Link("4", "Something")))
                .contributor("Someone")
                .build();
    }

    // --- one source failing is not the game failing -------------------------------

    @Test
    public void asourceThatThrowsIsRecordedAndTheRestAreStillAsked() {
        Fakes.Fake broken = new Fakes.Fake("Broken");
        broken.answer = gameAsked -> {
            throw new ScrapeException(ScrapeException.Kind.QUOTA_EXCEEDED, "spent");
        };

        Fakes.Fake working = new Fakes.Fake("Working");

        Blend.Result result = run(Arrays.asList(broken, working), new Fakes.NoHttp(),
                                  Blend.Media.FILL_GAPS, Provider.Wanted.nothing(),
                                  new NeverAsked());

        assertEquals(1, result.failures.size());
        assertEquals("Broken", result.failures.get(0).source);
        assertEquals(ScrapeException.Kind.QUOTA_EXCEEDED, result.failures.get(0).why.kind);

        assertEquals(Collections.singletonList("Working"), result.meta.sources());
    }

    private static void write(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();

        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes("UTF-8"));
        }
    }
}
```

- [ ] **Step 4: Run it and watch it fail**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=dev.ldlab.zedex.library.scrape.BlendTest
```

Expected: compilation failure — `cannot find symbol: class Blend`.

- [ ] **Step 5: Write `Blend`**

Create `app/src/main/java/dev/ldlab/zedex/library/scrape/Blend.java`:

```java
package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.library.Entry;
import dev.ldlab.zedex.library.meta.Artwork;
import dev.ldlab.zedex.library.meta.Meta;
import dev.ldlab.zedex.library.meta.Metadata;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One game across several sources, in order, with no screen anywhere in it.
 *
 * The plural counterpart to {@link Scrape}, and the same shape for the same
 * reason: everything that needs a person happens <em>between</em> two calls,
 * so the loop itself can be run against fakes with no network and no device
 * dialog.
 *
 * <b>A source may fill a gap and may never overwrite.</b> That one rule is the
 * whole design - see {@link Merge}, and the paragraph in {@link Scrapers}
 * explaining what it replaced. Everything else here follows from it: a later
 * source is only asked for the folders still empty, a source with nothing left
 * to add is not asked at all, and no answer can undo an earlier one.
 */
public final class Blend {

    private static final String TAG = "Zedex";

    private Blend() {
    }

    /**
     * What a run does about media, which is the only place the two callers
     * differ.
     */
    public enum Media {

        /**
         * Only the folders with nothing in them, written straight to the media
         * folder.
         *
         * A sweep. Nothing is ever replaced, so there is nothing to protect
         * and nothing to choose between - and a source is never asked for a
         * cover that exists, which for ScreenScraper is a {@code mediaJeu.php}
         * call not made against the day's allowance.
         */
        FILL_GAPS,

        /**
         * Every wanted folder from every source, into the staging area.
         *
         * Scraping one game by hand, where the person is present and can be
         * shown the alternatives. Nothing is installed until {@link #commit}.
         */
        OFFER_ALTERNATIVES,
    }

    /**
     * Which of several a source found, from whoever is watching.
     *
     * Called on the calling thread, which is never the UI thread. Null leaves
     * this source out for this game, which is a real answer and not a failure.
     */
    public interface Chooser {
        Candidate choose(String sourceName, List<Candidate> found, String game);
    }

    /** Whether whoever asked for this has given up - a sweep's Cancel, or
     *  nothing at all for a single game. */
    public interface Cancellable {
        boolean cancelled();
    }

    /** One picture waiting to be chosen between. */
    public static final class Staged {

        public final String folder;
        public final String extension;

        /** Which source offered it, for the sheet to name. */
        public final String source;

        /** In the staging area, not where anything looks. */
        public final File file;

        /** Something is already in that folder, and it is not these bytes. */
        public final boolean contested;

        /**
         * What is already on disk in that folder, or null when it was empty.
         *
         * The other half of the question the sheet asks - it draws this
         * against {@link #file} - and carried here rather than looked up
         * there, because only this class knows which extension the existing
         * one turned out to have.
         */
        public final File existing;

        Staged(String folder, String extension, String source, File file,
               boolean contested, File existing) {
            this.folder = folder;
            this.extension = extension;
            this.source = source;
            this.file = file;
            this.contested = contested;
            this.existing = existing;
        }
    }

    /**
     * One, without a network behind it.
     *
     * Public for {@code ArtworkChoiceTest}, which is in another package and
     * needs the sheet's input without a live scrape to produce it. Nothing in
     * the app calls this - {@link #run} is the only thing that makes one for
     * real.
     */
    public static Staged staged(String folder, String extension, String source,
                                File file, boolean contested, File existing) {
        return new Staged(folder, extension, source, file, contested, existing);
    }

    /** One source's refusal, kept rather than thrown: the others are still
     *  worth asking. */
    public static final class Failure {
        public final String source;
        public final ScrapeException why;

        Failure(String source, ScrapeException why) {
            this.source = source;
            this.why = why;
        }
    }

    /** What a run came to. */
    public static final class Result {

        /** The merged row, already stored. */
        public final Meta meta;

        /** Files written straight into the media folder - {@link
         *  Media#FILL_GAPS} only. */
        public final int installed;

        /** Files waiting for {@link #commit} - {@link Media#OFFER_ALTERNATIVES}
         *  only. */
        public final List<Staged> staged;

        /** The sources that contributed something. */
        public final List<String> consulted;

        public final List<Failure> failures;

        /**
         * Whether some source found candidates that nobody chose between.
         *
         * The difference between "never heard of it" and "an afternoon with
         * the chooser", which a sweep's tally reports as two different
         * numbers because they need two different things done about them.
         */
        public final boolean ambiguous;

        Result(Meta meta, int installed, List<Staged> staged, List<String> consulted,
               List<Failure> failures, boolean ambiguous) {
            this.meta = meta;
            this.installed = installed;
            this.staged = Collections.unmodifiableList(staged);
            this.consulted = Collections.unmodifiableList(consulted);
            this.failures = Collections.unmodifiableList(failures);
            this.ambiguous = ambiguous;
        }

        /** Whether anybody has to be asked anything about the pictures. */
        public boolean anythingContested() {
            for (Staged one : staged) {
                if (one.contested) return true;
            }
            return false;
        }
    }

    /**
     * Every source in turn, each filling in what the ones before left out.
     *
     * The facts are stored before this returns - deliberately, and for the
     * reason {@code Scrape.apply} stores them before fetching media: a scrape
     * that got the metadata and then met a spent quota has still improved the
     * row, and a person who cancels the picture sheet should not lose it.
     *
     * @param path the game's key, from {@code Metadata.relativePath}
     */
    public static Result run(Context context, List<Provider> sources, Http http,
                             Entry entry, String path, Provider.Wanted wanted,
                             Media media, Chooser chooser, Cancellable cancel) {
        Meta known = Metadata.forPath(context, path);
        if (known == null) known = Meta.at(path).build();

        List<String> consulted = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        List<Staged> staged = new ArrayList<>();
        int installed = 0;
        boolean ambiguous = false;

        if (media == Media.OFFER_ALTERNATIVES) {
            // On the way in, not on the way out: a scrape killed mid-flight
            // never reaches its own cleanup, and last run's leftover would be
            // offered as though this run had fetched it.
            Artwork.clearStaging(context);
        }

        for (Provider source : sources) {
            Provider.Wanted mine = wantedFrom(context, path, wanted, media, staged);

            if (media == Media.FILL_GAPS && nothingLeftToGain(known, mine)) break;

            final Meta soFar = known;

            try {
                // Retried per step rather than per source: a fetch that failed
                // after its search succeeded is re-fetched, not re-searched,
                // or the retry costs an extra request against the day's
                // allowance every time.
                Identified who = attempt(
                        () -> identify(context, source, entry, path, soFar, chooser),
                        cancel);

                if (who.chosen == null) {
                    // Found something and nobody would say which. Not the same
                    // as never having heard of it, and a sweep counts the two
                    // separately because they need different things done about
                    // them. Read off the search that already happened - asking
                    // again would be a second request for a fact we hold.
                    if (who.hadCandidates) ambiguous = true;
                    continue;
                }

                Provider.Scraped answer = attempt(() -> source.fetch(who.chosen, mine),
                                                  cancel);

                known = Merge.of(known, answer.meta);
                consulted.add(source.name());

                if (media == Media.FILL_GAPS) {
                    installed += Downloads.fetch(context, http, source, path,
                                                 answer.media,
                                                 Downloads.into(context, path)).saved;
                } else {
                    staged.addAll(stage(context, http, source, path, answer.media));
                }
            } catch (ScrapeException e) {
                // One source's refusal is not the game's: the others may well
                // answer, and a game with three of its facts is better than a
                // game with none.
                Log.w(TAG, source.name() + " could not answer about " + path, e);
                failures.add(new Failure(source.name(), e));
            }
        }

        Meta.Builder built = known.but().path(path);
        for (String name : consulted) built.contributor(name);

        Meta merged = built.build();
        Metadata.put(context, merged);

        if (installed > 0) Artwork.forget(path);

        return new Result(merged, installed, staged, consulted, failures, ambiguous);
    }

    /**
     * Installs the chosen staged media.
     *
     * At most one per folder; a folder named by none of them keeps whatever is
     * already there, which is what makes "Save without touching anything" a
     * no-op rather than a decision.
     *
     * @return how many files were installed
     */
    public static int commit(Context context, String path, List<Staged> chosen) {
        int installed = 0;

        for (Staged one : chosen) {
            File into = Artwork.fileFor(context, path, one.folder, one.extension);

            if (into.isFile() && !into.delete()) {
                Log.w(TAG, "cannot replace " + into);
                continue;
            }

            if (!one.file.renameTo(into)) {
                Log.w(TAG, "cannot install " + one.file + " as " + into);
                continue;
            }

            // The loser has to go, not merely be left unwritten: png outranks
            // jpg, so a new jpg beside an old png leaves the old one on screen
            // and the choice looks as though it did nothing.
            Artwork.removeOthers(context, path, one.folder, one.extension);
            installed++;
        }

        Artwork.clearStaging(context);
        if (installed > 0) Artwork.forget(path);

        return installed;
    }

    // --- which game is it ---------------------------------------------------------

    /**
     * Which entry this source thinks the file is, or null to leave it out.
     *
     * The first source is asked about the filename, because that is all
     * anybody knows. Every later one is asked about the <em>title</em> an
     * earlier source gave, which is what most services match well on - and a
     * single answer whose title is that title needs nobody, since it is the
     * same fact from two directions.
     *
     * <b>A hash still beats a title.</b> A source certain of its answer is
     * used even when the title disagrees: a hash match is the file itself,
     * where a title is what somebody typed on a shelf. The earlier name is
     * kept anyway - that source had priority.
     */
    /** What a search came to: which entry, and whether there was anything to
     *  choose between. The second is why this is not just a {@link Candidate} -
     *  "nobody chose" and "nothing was found" are different outcomes and a
     *  null cannot say which. */
    private static final class Identified {
        final Candidate chosen;
        final boolean hadCandidates;

        Identified(Candidate chosen, boolean hadCandidates) {
            this.chosen = chosen;
            this.hadCandidates = hadCandidates;
        }
    }

    private static Identified identify(Context context, Provider source, Entry entry,
                                       String path, Meta known, Chooser chooser)
            throws ScrapeException {
        String title = known.name;

        List<Candidate> found = title == null
                ? Scrape.candidates(context, source, entry, path)
                : source.search(byTitle(entry, path, title));

        if (found.isEmpty()) return new Identified(null, false);
        if (Scrape.certain(found)) return new Identified(found.get(0), true);

        if (title != null && found.size() == 1 && sameTitle(found.get(0).name, title)) {
            return new Identified(found.get(0), true);
        }

        return new Identified(chooser.choose(source.name(), found, entry.name), true);
    }

    // --- waiting out the failures worth waiting out --------------------------------

    /** How many times a step whose failure is worth waiting out is tried. */
    private static final int ATTEMPTS = 3;

    /** Between attempts, multiplied by which attempt this is: two seconds,
     *  then four. A thread limit clears in about that; longer would make a
     *  wobble halfway through a collection cost minutes. */
    private static final long BACKOFF_MS = 2000;

    /** How often the back-off looks up to see whether Cancel was pressed.
     *  Sleeping the whole two seconds would make Cancel appear broken. */
    private static final long CANCEL_POLL_MS = 200;

    /** One request, so that {@link #attempt} can retry either half of a scrape
     *  without knowing which it is holding. */
    private interface Step<T> {
        T run() throws ScrapeException;
    }

    /**
     * Runs one step, waiting out the failures that are worth waiting out.
     *
     * Only the two {@code ScrapeException.worthWaiting} kinds are retried. A
     * thread limit is the ordinary reason a loop this shape stumbles and it
     * clears by itself in a second or two; a network that went away often
     * comes back. Everything else is thrown at once, because trying a refused
     * password three times is three refusals.
     *
     * <b>Here rather than in {@code Sweep}, where it used to be.</b> The retry
     * has to sit inside the per-source loop or a wobble at one service ends
     * that game at every service - and it is per <em>step</em> rather than per
     * source, so a fetch that failed after its search succeeded is re-fetched
     * and not re-searched. Re-searching would cost an extra request against
     * the day's allowance every single time.
     */
    private static <T> T attempt(Step<T> step, Cancellable cancel) throws ScrapeException {
        ScrapeException last = null;

        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            if (attempt > 1 && !pause(BACKOFF_MS * (attempt - 1), cancel)) break;

            try {
                return step.run();
            } catch (ScrapeException e) {
                if (!e.worthWaiting()) throw e;

                Log.w(TAG, "attempt " + attempt + " of " + ATTEMPTS + " met " + e.kind);
                last = e;
            }
        }

        throw last;
    }

    /** Waits, looking up often enough that Cancel still means something. False
     *  if it was cancelled or interrupted, in which case the caller should stop
     *  rather than try again. */
    private static boolean pause(long millis, Cancellable cancel) {
        long until = android.os.SystemClock.uptimeMillis() + millis;

        while (android.os.SystemClock.uptimeMillis() < until) {
            if (cancel.cancelled()) return false;

            try {
                Thread.sleep(CANCEL_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        return !cancel.cancelled();
    }

    /**
     * The game as a later source should be asked about it: by title.
     *
     * A decorator rather than a change to {@link Provider}, because both
     * providers already derive their search term from {@code filename()} -
     * ZXInfo through {@code ZxInfo.titleOf}, ScreenScraper as {@code romnom} -
     * so handing them the title is all it takes.
     *
     * The hash is deliberately still offered: a later source that can match on
     * it should, and reading it is what {@code Provider.Game} makes lazy.
     */
    private static Provider.Game byTitle(Entry entry, String path, String title) {
        return new Provider.Game() {
            @Override public String path() { return path; }
            @Override public String filename() { return title; }
            @Override public long size() { return entry.size; }
            @Override public String md5() { return null; }
        };
    }

    /** Case and surrounding space, and nothing more. Anything fuzzier is a
     *  guess, and a guess acted on silently is one game's cover on another for
     *  ever. */
    private static boolean sameTitle(String one, String other) {
        if (one == null || other == null) return false;

        return one.trim().toLowerCase(Locale.ROOT)
                .equals(other.trim().toLowerCase(Locale.ROOT));
    }

    // --- what to ask for ----------------------------------------------------------

    /**
     * Which folders this source should be asked to resolve.
     *
     * Under {@link Media#OFFER_ALTERNATIVES}, all of them: the sheet cannot
     * offer a choice it did not fetch. Under {@link Media#FILL_GAPS}, only the
     * folders with nothing in them - including nothing staged by an earlier
     * source in this same run.
     */
    private static Provider.Wanted wantedFrom(Context context, String path,
                                              Provider.Wanted wanted, Media media,
                                              List<Staged> staged) {
        if (media == Media.OFFER_ALTERNATIVES) return wanted;

        Set<String> empty = new LinkedHashSet<>();

        for (String folder : wanted.folders()) {
            if (!hasSomething(context, path, folder, staged)) empty.add(folder);
        }
        return Provider.Wanted.of(empty);
    }

    private static boolean hasSomething(Context context, String path, String folder,
                                        List<Staged> staged) {
        for (Staged one : staged) {
            if (one.folder.equals(folder)) return true;
        }
        return existing(context, path, folder) != null;
    }

    /**
     * Whether every source has been asked everything worth asking.
     *
     * Precise rather than a "well described" bar: every field a provider can
     * supply, and every wanted folder. In practice it almost never triggers,
     * which is the honest answer - a second source usually does have something
     * to add, and pretending otherwise would only hide the cost.
     */
    private static boolean nothingLeftToGain(Meta known, Provider.Wanted stillWanted) {
        if (stillWanted.any()) return false;

        for (Meta.Field field : Meta.Field.values()) {
            if (known.get(field) == null) return false;
        }

        return known.desc != null && known.keymap != null && known.price != null
                && known.series != null
                && !known.authors.isEmpty() && !known.seriesGames.isEmpty()
                && !known.compilations.isEmpty() && !known.contents.isEmpty();
    }

    // --- staging ------------------------------------------------------------------

    /** Fetches one source's media into the staging area and says which of them
     *  something is already holding. */
    private static List<Staged> stage(Context context, Http http, Provider source,
                                      String path, List<Medium> media)
            throws ScrapeException {
        List<Staged> staged = new ArrayList<>();
        if (media.isEmpty()) return staged;

        // Per source, so two sources' covers do not overwrite each other on
        // the way in - the folder is the same, and so is the game's stem.
        Downloads.Destination into = (folder, extension) ->
                Artwork.stagingFileFor(context, path, folder + "/" + source.name(),
                                       extension);

        Downloads.fetch(context, http, source, path, media, into);

        for (Medium medium : media) {
            File file = into.fileFor(medium.folder, medium.extension);
            if (!file.isFile() || file.length() == 0) continue;

            File already = existing(context, path, medium.folder);

            staged.add(new Staged(medium.folder, medium.extension, source.name(), file,
                                  differs(already, file), already));
        }
        return staged;
    }

    /**
     * Whether this file is a question.
     *
     * Nothing there is no question - there is nothing to lose. The same bytes
     * is no question either: two services carrying the same scan is common,
     * and asking about it would be asking somebody to choose between a picture
     * and itself.
     */
    private static boolean differs(File existing, File staged) {
        if (existing == null) return false;

        if (existing.length() != staged.length()) return true;

        String mine = md5Of(staged);
        String theirs = md5Of(existing);

        return mine == null || theirs == null || !mine.equals(theirs);
    }

    /** This game's file in one media folder, whatever extension it was written
     *  with, or null. */
    static File existing(Context context, String path, String folder) {
        for (String extension : new String[] { "png", "jpg", "mp4", "pdf", "txt",
                                               "pok", "ay" }) {
            File file = Artwork.fileFor(context, path, folder, extension);
            if (file.isFile() && file.length() > 0) return file;
        }
        return null;
    }

    private static String md5Of(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[64 * 1024];

            for (int read; (read = in.read(buffer)) != -1; ) md5.update(buffer, 0, read);

            StringBuilder hex = new StringBuilder(32);
            for (byte b : md5.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            Log.w(TAG, "cannot hash " + file, e);
            return null;
        }
    }
}
```

**Note on `Artwork.fileFor` and the staged sub-folder:** `stage` passes `folder + "/" + source.name()` so two sources' covers do not collide in staging. `Artwork.stagingFileFor` joins that onto the staging root, so the file lands at `.staging/covers/ZXInfo/<stem>.png`. That works because `fileFor` already makes parent directories.

- [ ] **Step 6: Run the test and watch it pass**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=dev.ldlab.zedex.library.scrape.BlendTest
```

Expected: PASS, 11 tests.

- [ ] **Step 7: Commit**

```sh
git add app/src/main/java/dev/ldlab/zedex/library/scrape/Blend.java \
        app/src/androidTest/java/dev/ldlab/zedex/library/scrape/BlendTest.java
git commit -m "feat: one game across several sources, in order

The plural counterpart to Scrape, and the same shape: everything needing
a person happens between two calls, so the loop runs against fakes with
no network and no dialog.

A later source is asked about the title an earlier one gave rather than
about the filename, which is what services match well on - and a single
answer carrying that same title needs nobody, being the same fact from
two directions. A hash still beats a title: a certain match is the file
itself, where a title is what somebody typed on a shelf.

One source refusing is not the game failing. The others are still asked,
and what they know is still written."
```

---

### Task 6: Staging, contested pictures, and `commit`

**Files:**
- Test: `app/src/androidTest/java/dev/ldlab/zedex/library/scrape/BlendTest.java` (modify)
- Modify: `app/src/main/java/dev/ldlab/zedex/library/scrape/Blend.java` if anything is found wanting

**Interfaces:**
- Consumes: everything task 5 produced.
- Produces: no new signatures — this proves the `OFFER_ALTERNATIVES` half that task 5 wrote.

- [ ] **Step 1: Write the failing tests**

Append to `BlendTest.java`:

```java
    // --- offering alternatives ----------------------------------------------------

    private static Medium picture(String folder, String url) {
        return new Medium(folder, "png", url, null);
    }

    /** Nothing on disk: a picture is taken, and it is nobody's question. */
    @Test
    public void afreshPictureIsStagedAndIsNotContested() {
        Fakes.Fake only = new Fakes.Fake("Only");
        only.media = Collections.singletonList(picture("covers", "http://only/cover"));

        Blend.Result result = run(Collections.singletonList(only), new Fakes.WritesTheUrl(),
                                  Blend.Media.OFFER_ALTERNATIVES,
                                  Provider.Wanted.of("covers"), new NeverAsked());

        assertEquals(1, result.staged.size());
        assertEquals("covers", result.staged.get(0).folder);
        assertEquals("Only", result.staged.get(0).source);
        assertFalse(result.staged.get(0).contested);
        assertFalse("nothing may be installed before commit",
                    Artwork.fileFor(context, PATH, "covers", "png").isFile());
    }

    /** Different bytes over something already there is the question the sheet
     *  exists to ask. */
    @Test
    public void adifferentPictureOverOneWeHaveIsContested() throws IOException {
        write(Artwork.fileFor(context, PATH, "covers", "png"), "an older cover");
        Artwork.forget(PATH);

        Fakes.Fake only = new Fakes.Fake("Only");
        only.media = Collections.singletonList(picture("covers", "http://only/cover"));

        Blend.Result result = run(Collections.singletonList(only), new Fakes.WritesTheUrl(),
                                  Blend.Media.OFFER_ALTERNATIVES,
                                  Provider.Wanted.of("covers"), new NeverAsked());

        assertEquals(1, result.staged.size());
        assertTrue(result.staged.get(0).contested);
        assertTrue(result.anythingContested());
    }

    /**
     * The same picture is not a question.
     *
     * Two services carrying the same scan is common, and asking about it would
     * be asking somebody to choose between a picture and itself.
     */
    @Test
    public void thesamePictureIsNotContested() throws IOException {
        write(Artwork.fileFor(context, PATH, "covers", "png"), "http://only/cover");
        Artwork.forget(PATH);

        Fakes.Fake only = new Fakes.Fake("Only");
        only.media = Collections.singletonList(picture("covers", "http://only/cover"));

        Blend.Result result = run(Collections.singletonList(only), new Fakes.WritesTheUrl(),
                                  Blend.Media.OFFER_ALTERNATIVES,
                                  Provider.Wanted.of("covers"), new NeverAsked());

        assertEquals(1, result.staged.size());
        assertFalse(result.staged.get(0).contested);
        assertFalse(result.anythingContested());
    }

    /** Two sources, one folder, two files - neither on top of the other. */
    @Test
    public void twoSourcesEachKeepTheirOwnCover() {
        Fakes.Fake first = new Fakes.Fake("First");
        first.media = Collections.singletonList(picture("covers", "http://first/cover"));

        Fakes.Fake second = new Fakes.Fake("Second");
        second.media = Collections.singletonList(picture("covers", "http://second/cover"));

        Blend.Result result = run(Arrays.asList(first, second), new Fakes.WritesTheUrl(),
                                  Blend.Media.OFFER_ALTERNATIVES,
                                  Provider.Wanted.of("covers"), new NeverAsked());

        assertEquals(2, result.staged.size());

        List<String> offered = new ArrayList<>();
        for (Blend.Staged one : result.staged) offered.add(one.source);
        assertEquals(Arrays.asList("First", "Second"), offered);

        assertFalse("the two staged covers are the same file",
                    result.staged.get(0).file.equals(result.staged.get(1).file));
    }

    // --- committing ---------------------------------------------------------------

    @Test
    public void committingInstallsTheChosenPictureAndEmptiesTheStagingArea() {
        Fakes.Fake only = new Fakes.Fake("Only");
        only.media = Collections.singletonList(picture("covers", "http://only/cover"));

        Blend.Result result = run(Collections.singletonList(only), new Fakes.WritesTheUrl(),
                                  Blend.Media.OFFER_ALTERNATIVES,
                                  Provider.Wanted.of("covers"), new NeverAsked());

        assertEquals(1, Blend.commit(context, PATH, result.staged));

        File installed = Artwork.fileFor(context, PATH, "covers", "png");
        assertTrue(installed.isFile());
        assertFalse("the staging area was left behind",
                    Artwork.stagingRoot(context).exists());
    }

    @Test
    public void committingNothingChangesNothing() throws IOException {
        write(Artwork.fileFor(context, PATH, "covers", "png"), "an older cover");
        Artwork.forget(PATH);

        Fakes.Fake only = new Fakes.Fake("Only");
        only.media = Collections.singletonList(picture("covers", "http://only/cover"));

        run(Collections.singletonList(only), new Fakes.WritesTheUrl(),
            Blend.Media.OFFER_ALTERNATIVES, Provider.Wanted.of("covers"),
            new NeverAsked());

        assertEquals(0, Blend.commit(context, PATH, Collections.emptyList()));

        assertEquals("an older cover",
                     new String(Files.readAllBytes(
                             Artwork.fileFor(context, PATH, "covers", "png").toPath()),
                                "UTF-8"));
    }

    /**
     * The loser goes, not merely stays unwritten.
     *
     * png outranks jpg in Artwork's own order, so a chosen jpg written beside
     * an unchosen png leaves the png on screen - and the choice looks exactly
     * as though it did nothing.
     */
    @Test
    public void installingOneExtensionRemovesTheOther() throws IOException {
        write(Artwork.fileFor(context, PATH, "covers", "png"), "an older png cover");
        Artwork.forget(PATH);

        Fakes.Fake only = new Fakes.Fake("Only");
        only.media = Collections.singletonList(
                new Medium("covers", "jpg", "http://only/cover", null));

        Blend.Result result = run(Collections.singletonList(only), new Fakes.WritesTheUrl(),
                                  Blend.Media.OFFER_ALTERNATIVES,
                                  Provider.Wanted.of("covers"), new NeverAsked());

        assertEquals(1, Blend.commit(context, PATH, result.staged));

        assertTrue(Artwork.fileFor(context, PATH, "covers", "jpg").isFile());
        assertFalse("the png it replaced is still there and still outranks it",
                    Artwork.fileFor(context, PATH, "covers", "png").isFile());
    }

    /** A run killed last time must not offer its leftovers as this run's
     *  findings. */
    @Test
    public void aleftoverFromAKilledRunIsClearedOnTheWayIn() throws IOException {
        write(Artwork.stagingFileFor(context, PATH, "covers/Ghost", "png"),
              "from a run that died");

        Fakes.Fake only = new Fakes.Fake("Only");
        only.media = Collections.singletonList(picture("covers", "http://only/cover"));

        Blend.Result result = run(Collections.singletonList(only), new Fakes.WritesTheUrl(),
                                  Blend.Media.OFFER_ALTERNATIVES,
                                  Provider.Wanted.of("covers"), new NeverAsked());

        assertEquals(1, result.staged.size());
        assertEquals("Only", result.staged.get(0).source);
    }
```

Add `import dev.ldlab.zedex.library.scrape.Medium;` if the package import is missing — `Medium` is in the same package, so it is not needed.

- [ ] **Step 2: Run and watch it fail**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=dev.ldlab.zedex.library.scrape.BlendTest
```

Expected: some pass and some fail. Read the **first** failure, not the count.

- [ ] **Step 3: Fix whatever the tests found**

The likely two, both in `Blend`:

1. `Medium`'s constructor argument order — check `Medium.java` and correct the test's `picture()` helper if it disagrees.
2. `existing()` must not see a staged file. It calls `Artwork.fileFor`, which is the media folder, so it does not — but confirm `Artwork.stagingRoot` is under `mediaDirectory` and that `PICTURE_FOLDERS` never names `.staging`.

Make the minimal change that turns each failure green, and re-run after each.

- [ ] **Step 4: Run the whole test and watch it pass**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=dev.ldlab.zedex.library.scrape.BlendTest
```

Expected: PASS, 19 tests.

- [ ] **Step 5: Commit**

```sh
git add app/src/main/java/dev/ldlab/zedex/library/scrape/Blend.java \
        app/src/androidTest/java/dev/ldlab/zedex/library/scrape/BlendTest.java
git commit -m "feat: stage a one-game scrape's pictures until somebody chooses

Every source's pictures are fetched before anybody is asked about any of
them, into a folder of the media folder's own that nothing reads - so
what is already on disk survives until a choice is made, and Cancel
costs only the download.

Contested means something is there and it is not the same bytes. The
same scan from two services is not a question, and asking would be
asking somebody to choose between a picture and itself.

Installing removes the other extensions for that game and folder: png
outranks jpg, so a chosen jpg beside an unchosen png leaves the png on
screen and the choice looks as though it did nothing."
```

---

### Task 7: `Sweep` asks every source

**Files:**
- Modify: `app/src/main/java/dev/ldlab/zedex/library/scrape/Sweep.java`
- Modify: `app/src/main/java/dev/ldlab/zedex/library/scrape/Scrape.java` (delete `wouldOverwriteAHandEdit`)
- Modify: `app/src/main/java/dev/ldlab/zedex/screen/ScrapeManyActivity.java`
- Test: `app/src/androidTest/java/dev/ldlab/zedex/library/scrape/SweepTest.java` (modify)

**Interfaces:**
- Consumes: `Blend.run`, `Blend.Media.FILL_GAPS`, `Blend.Chooser`, `Blend.Failure`.
- Produces:
  - `Sweep.Tally Sweep.run(Context, List<Provider> sources, Http, List<Entry>, Provider.Wanted, Sweep.Conflicts, Sweep.Watcher)`
  - `Sweep.Choice Sweep.Watcher.chooseFrom(String sourceName, List<Candidate> found, String game)` — one more parameter, so the dialog can say who is asking
  - `Sweep.Tally` without `yours`

- [ ] **Step 1: Write the failing tests**

Add to `SweepTest.java`:

```java
    /**
     * Every source is asked about every game, in the order given.
     *
     * The order is the priority: whoever answers first about a field keeps it.
     */
    @Test
    public void everySourceIsAskedAboutEveryGame() {
        Fakes.Fake first = new Fakes.Fake("First");
        Fakes.Fake second = new Fakes.Fake("Second");
        Watching watcher = new Watching();

        Sweep.Tally tally = Sweep.run(context, Arrays.asList(first, second),
                                      new Fakes.NoHttp(), games("A.tap", "B.tap"),
                                      Provider.Wanted.nothing(), Sweep.Conflicts.SKIP,
                                      watcher);

        assertEquals(Arrays.asList("A.tap", "B.tap"), first.searched);
        assertEquals(2, second.searched.size());
        assertEquals(2, tally.scraped);
    }

    /**
     * A source that runs out is dropped; the rest of the run carries on.
     *
     * ZXInfo has no quota and no reason to stop because ScreenScraper ran out
     * - which is the whole argument for isolating a refusal to the source that
     * made it.
     */
    @Test
    public void asourceThatRunsOutIsDroppedAndTheOthersCarryOn() {
        Fakes.Fake spent = new Fakes.Fake("Spent");
        spent.answer = game -> {
            throw new ScrapeException(ScrapeException.Kind.QUOTA_EXCEEDED, "spent");
        };

        Fakes.Fake free = new Fakes.Fake("Free");
        Watching watcher = new Watching();

        Sweep.Tally tally = Sweep.run(context, Arrays.asList(spent, free),
                                      new Fakes.NoHttp(),
                                      games("A.tap", "B.tap", "C.tap"),
                                      Provider.Wanted.nothing(), Sweep.Conflicts.SKIP,
                                      watcher);

        assertEquals("the spent source was asked more than once", 1, spent.searched.size());
        assertEquals(3, free.searched.size());
        assertNull("the run should not have stopped", tally.stopped);
        assertEquals(3, tally.scraped);
    }

    /** When the last source goes, the run stops and says why. */
    @Test
    public void theRunStopsWhenNoSourceIsLeft() {
        Fakes.Fake spent = new Fakes.Fake("Spent");
        spent.answer = game -> {
            throw new ScrapeException(ScrapeException.Kind.QUOTA_EXCEEDED, "spent");
        };

        Sweep.Tally tally = Sweep.run(context, Collections.singletonList(spent),
                                      new Fakes.NoHttp(), games("A.tap", "B.tap"),
                                      Provider.Wanted.nothing(), Sweep.Conflicts.SKIP,
                                      new Watching());

        assertNotNull(tally.stopped);
        assertEquals(ScrapeException.Kind.QUOTA_EXCEEDED, tally.stopped.kind);
    }

    /**
     * A hand edit is no longer a reason to leave a game alone.
     *
     * It cannot be overwritten - a typed value is not a gap - so skipping the
     * game would only mean the fields the person did *not* type stay empty for
     * ever.
     */
    @Test
    public void ahandEditedRowIsToppedUpRatherThanSkipped() {
        Metadata.put(context, Meta.at(pathOf("A.tap"))
                .genre("Puzzle").contributor(Meta.USER).build());

        Fakes.Fake source = new Fakes.Fake("Source");

        Sweep.Tally tally = Sweep.run(context, Collections.singletonList(source),
                                      new Fakes.NoHttp(), games("A.tap"),
                                      Provider.Wanted.nothing(), Sweep.Conflicts.SKIP,
                                      new Watching());

        assertEquals(1, tally.scraped);

        Meta after = Metadata.forPath(context, pathOf("A.tap"));
        assertEquals("the hand-typed genre was overwritten", "Puzzle", after.genre);
        assertNotNull("nothing was filled in around it", after.publisher);
        assertTrue(after.isMine());
    }
```

`pathOf(String)` is a helper `SweepTest` will need if it does not have one — it builds the same key `Metadata.relativePath` gives for that fixture. Read how the existing tests build their entries and mirror it.

Then update every existing call in `SweepTest`: `Sweep.run(context, provider, ...)` becomes `Sweep.run(context, Collections.singletonList(provider), ...)`, `Watching.chooseFrom` gains the leading `String sourceName`, and any assertion on `tally.yours` is deleted along with the test that only asserted it.

- [ ] **Step 2: Run and watch it fail**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=dev.ldlab.zedex.library.scrape.SweepTest
```

Expected: compilation failure — `run` does not take a list, `chooseFrom` takes two arguments.

- [ ] **Step 3: Rewrite `Sweep`'s middle**

Change `Watcher.chooseFrom` and document why the name is there:

```java
        /**
         * Which of several, from a person.
         *
         * {@code sourceName} because more than one service is asked about each
         * game now, and "choose one of these five" without saying who is
         * asking is a question with a fact missing - the same file matches
         * differently at each service, and which one is offering these five is
         * part of judging them.
         *
         * Called under {@link Conflicts#ASK} only, and it <b>blocks the sweep
         * thread until somebody answers</b>. There is deliberately no timeout:
         * a run that skipped a game because nobody was looking would be worse
         * than one that waits, and Cancel is right there.
         */
        Choice chooseFrom(String sourceName, List<Candidate> found, String game);
```

Replace `run` and `one`:

```java
    public static Tally run(Context context, List<Provider> sources, Http http,
                            List<Entry> entries, Provider.Wanted wanted,
                            Conflicts conflicts, Watcher watcher) {
        Tally tally = new Tally();
        tally.total = entries.size();

        // Sources are dropped as they run out, so this shrinks. A quota or a
        // refused password is every remaining game for *that* service and
        // nothing at all for the others - ZXInfo has no allowance to spend.
        List<Provider> live = new ArrayList<>(sources);
        ScrapeException last = null;

        for (Entry entry : entries) {
            if (watcher.cancelled()) {
                tally.cancelled = true;
                return tally;
            }

            dropTheSpent(live, wanted);

            if (live.isEmpty()) {
                tally.stopped = last != null ? last : new ScrapeException(
                        ScrapeException.Kind.QUOTA_EXCEEDED,
                        "every source is out of allowance");
                return tally;
            }

            watcher.at(tally.done, tally.total, entry.name);

            conflicts = one(context, live, http, entry, wanted, conflicts, watcher, tally);

            for (Blend.Failure failure : lastFailures) {
                if (isHopeless(failure.why)) {
                    last = failure.why;
                    drop(live, failure.source);
                }
            }

            tally.done++;
        }

        return tally;
    }

    /** The failures the last game met, so run() can drop the sources that made
     *  them. A field rather than a return value because one() already returns
     *  the conflict policy, and a two-value return would be a class for one
     *  caller. */
    private static List<Blend.Failure> lastFailures = Collections.emptyList();
```

**That static field is wrong** — `Sweep` is called from one thread at a time today, but a static mutable is exactly the trap `Pace` documents. Use a small holder instead:

```java
    /** What one game came to, beyond the tally: which sources refused, so the
     *  run can drop them. */
    private static final class Outcome {
        Conflicts conflicts;
        List<Blend.Failure> failures = Collections.emptyList();
    }
```

and have `one` return an `Outcome`. Write it that way; do not ship the static.

```java
    private static Outcome one(Context context, List<Provider> live, Http http,
                               Entry entry, Provider.Wanted wanted, Conflicts conflicts,
                               Watcher watcher, Tally tally) {
        Outcome outcome = new Outcome();
        outcome.conflicts = conflicts;

        String path = Metadata.relativePath(context, entry.uri);

        if (path == null) {
            // select() drops these, so reaching here means the tree moved
            // under the run.
            tally.failed++;
            return outcome;
        }

        Blend.Chooser chooser = (sourceName, found, game) -> {
            switch (outcome.conflicts) {
                case BEST:
                    return found.get(0);

                case ASK:
                    Choice choice = watcher.chooseFrom(sourceName, found, game);
                    if (choice.stopAsking) outcome.conflicts = Conflicts.SKIP;
                    return choice.candidate;

                case SKIP:
                default:
                    return null;
            }
        };

        Blend.Result result = Blend.run(context, live, http, entry, path, wanted,
                                        Blend.Media.FILL_GAPS, chooser,
                                        watcher::cancelled);

        outcome.failures = result.failures;

        if (!result.consulted.isEmpty()) {
            tally.scraped++;
            tally.media += result.installed;
        } else if (!result.failures.isEmpty()) {
            tally.failed++;
        } else if (result.ambiguous) {
            // Found, and nobody would say which. An afternoon with the
            // chooser, which is a different thing to act on than a service
            // that has never heard of the game.
            tally.ambiguous++;
        } else {
            tally.unknown++;
        }

        return outcome;
    }
```

`Blend.Result.ambiguous` was declared in task 5 for exactly this line.

Add `dropTheSpent`, `drop` and `isHopeless`, replacing `spent` and `carryOnOrStop`:

```java
    /**
     * Drops any source that cannot afford another game.
     *
     * <b>Asked rather than waited for.</b> ScreenScraper does not refuse when
     * an account is over its allowance - forcing the counter to 100000 against
     * an allowance of 10000 still answered 200 with a real candidate - so the
     * counters it puts in every reply are the only warning there is.
     *
     * Unknown does not drop anything: {@link Quota#left} answers -1 before the
     * first reply and whenever the service did not say, and refusing to try on
     * a guess is worse than one refused request.
     */
    private static void dropTheSpent(List<Provider> live, Provider.Wanted wanted) {
        for (java.util.Iterator<Provider> each = live.iterator(); each.hasNext(); ) {
            Provider provider = each.next();

            Quota quota = provider.quota();
            if (quota == null) continue;

            int left = quota.left();
            if (left >= 0 && left < provider.costPerGame(wanted)) {
                Log.w(TAG, provider.name() + " has " + left
                           + " left and a game costs more; dropping it for this run");
                each.remove();
            }
        }
    }

    private static void drop(List<Provider> live, String name) {
        for (java.util.Iterator<Provider> each = live.iterator(); each.hasNext(); ) {
            if (each.next().name().equals(name)) {
                Log.w(TAG, "dropping " + name + " for the rest of the run");
                each.remove();
                return;
            }
        }
    }

    /**
     * Whether a refusal is one that source will keep making.
     *
     * The same line {@code Downloads} draws for one game's media, from the
     * same enum and for the same reason - and now drawn per source rather than
     * per run, because one service running out says nothing about another.
     */
    private static boolean isHopeless(ScrapeException e) {
        switch (e.kind) {
            case QUOTA_EXCEEDED:
            case BAD_CREDENTIALS:
            case CLOSED:
            case NOT_CONFIGURED:
                return true;
            default:
                return false;
        }
    }
```

The `Blend.run` call inside that method passes the watcher as the `Cancellable`, so Cancel is still noticed during a back-off:

```java
        Blend.Result result = Blend.run(context, live, http, entry, path, wanted,
                                        Blend.Media.FILL_GAPS, chooser,
                                        watcher::cancelled);
```

**Delete `Tally.yours`, `attempt`, `Step`, `pause`, `ATTEMPTS`, `BACKOFF_MS` and `CANCEL_POLL_MS` from `Sweep`.** Task 5 moved the retry into `Blend`, where it has to be: it has to sit inside the per-source loop, or a wobble at one service ends that game at every service. `Blend.run` does not throw, so a retry wrapped around it here could never fire.

- [ ] **Step 4: Reword `Only.EVERYTHING`, which now means something thinner**

Its javadoc says "The lot, re-fetching what is already there", and it cannot any more. Replace it:

```java
        /**
         * The lot, including games this app has already scraped.
         *
         * <b>It no longer re-fetches what is there.</b> Nothing does: a scrape
         * fills gaps and never overwrites, so this differs from {@link
         * #NOT_SCRAPED} only in also revisiting games that already carry a
         * provider's name - which is worth having when a source has been added
         * to the order since the last run, and is worth nothing otherwise.
         *
         * Kept rather than removed because people have used it, and because
         * "ask the new source about everything" is exactly what somebody wants
         * the first time they turn a second source on.
         */
        EVERYTHING,
```

- [ ] **Step 5: Delete `Scrape.wouldOverwriteAHandEdit`**

Remove the method and its javadoc from `Scrape.java`, and the block in `ScrapeOneGame.scrape` that calls it along with the `R.string.scrape_overwrite` dialog.

```sh
grep -rn "wouldOverwriteAHandEdit\|scrape_overwrite" app/src
```

Expected after the edit: matches only in `res/values*/strings.xml`, cleaned up in task 9.

- [ ] **Step 6: Update `ScrapeManyActivity`**

- `provider` becomes `private List<Provider> sources;`, built in `onCreate` from `Scrapers.enabled(this)`.
- `showEstimate`: sum the cost and say "at most".
  ```java
      int perGame = 0;
      for (Provider source : sources) perGame += source.costPerGame(Scrapers.wanted(this));
      int requests = games * perGame;
  ```
- `showQuota`: show the first source that reports one; hide the line when none does.
- The watcher's `chooseFrom` gains `String sourceName` and puts it in the dialog title.
- Delete the `tally.yours` line at `:699`.
- `Sweep.run(this, sources, new Http.Real(this), entries, ...)`.

- [ ] **Step 7: Build and run the tests**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug
env JAVA_HOME=/opt/android-studio/jbr ./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=dev.ldlab.zedex.library.scrape.SweepTest
env JAVA_HOME=/opt/android-studio/jbr ./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=dev.ldlab.zedex.library.scrape.BlendTest
```

Expected: both PASS. The build will fail on `R.string.scrape_overwrite` only if the string was removed early — it is removed in task 9.

- [ ] **Step 8: Commit**

```sh
git add app/src/main/java/dev/ldlab/zedex/library/scrape/Sweep.java \
        app/src/main/java/dev/ldlab/zedex/library/scrape/Blend.java \
        app/src/main/java/dev/ldlab/zedex/library/scrape/Scrape.java \
        app/src/main/java/dev/ldlab/zedex/screen/ScrapeManyActivity.java \
        app/src/androidTest/java/dev/ldlab/zedex/library/scrape/SweepTest.java \
        app/src/androidTest/java/dev/ldlab/zedex/library/scrape/BlendTest.java
git commit -m "feat: a sweep asks every source, and drops the ones that run out

A refusal belongs to the source that made it. A spent quota or a refused
password is every remaining game for that service and nothing at all for
the others - ZXInfo has no allowance to spend - so the run drops it and
carries on, and stops only when no source is left standing.

The hand-edit skip goes with it. A scrape cannot overwrite a typed value
any more, because a typed value is not a gap, so skipping the game only
meant the fields the person did not type stayed empty for ever.

chooseFrom is told which source is asking: the same file matches
differently at each, and 'choose one of these five' without saying whose
five is a question with a fact missing."
```

---

### Task 8: The one-game scrape and its review sheet

**Files:**
- Create: `app/src/main/java/dev/ldlab/zedex/screen/ArtworkChoice.java`
- Modify: `app/src/main/java/dev/ldlab/zedex/screen/ScrapeOneGame.java`
- Test: `app/src/androidTest/java/dev/ldlab/zedex/screen/ArtworkChoiceTest.java` (create)

**Interfaces:**
- Consumes: `Blend.run`, `Blend.commit`, `Blend.Staged`, `Blend.Result`.
- Produces: `ArtworkChoice.show(Activity, String gameName, List<Blend.Staged> staged, ArtworkChoice.Chosen onSave)` where `interface Chosen { void take(List<Blend.Staged> chosen); }`.

- [ ] **Step 1: Write `ArtworkChoice`**

Create `app/src/main/java/dev/ldlab/zedex/screen/ArtworkChoice.java`:

```java
package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.library.scrape.Blend;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.BitmapFactory;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which of several pictures to keep, for one game.
 *
 * Every source is asked for every picture before any of this is shown - see
 * {@code Blend.Media.OFFER_ALTERNATIVES} - so by the time anybody is asked
 * anything the downloads are finished and nothing is waiting on the answer.
 * That is why this is a dialog and not a blocking call: there is no thread
 * parked behind it.
 *
 * <b>Save with nothing touched changes nothing.</b> Whatever is already on
 * disk starts selected, so the safe answer is the default one and a person who
 * does not understand the question cannot lose a picture by pressing the
 * obvious button. A folder that had nothing preselects the first source that
 * offered one, since there is nothing to lose there.
 */
final class ArtworkChoice {

    /** What the sheet came to: the staged files to install, at most one per
     *  folder. */
    interface Chosen {
        void take(List<Blend.Staged> chosen);
    }

    private ArtworkChoice() {
    }

    /** How wide a tile is drawn, in dp - two and a bit fit across a phone,
     *  which is what makes it obvious the row scrolls sideways. */
    private static final int TILE_DP = 128;

    static void show(Activity activity, String gameName, List<Blend.Staged> staged,
                     Chosen onSave) {
        Map<String, List<Blend.Staged>> byFolder = new LinkedHashMap<>();

        for (Blend.Staged one : staged) {
            List<Blend.Staged> forFolder = byFolder.get(one.folder);
            if (forFolder == null) {
                forFolder = new ArrayList<>();
                byFolder.put(one.folder, forFolder);
            }
            forFolder.add(one);
        }

        // One selection per folder; null means "keep what is already there".
        Map<String, Blend.Staged> selected = new LinkedHashMap<>();

        int padding = dp(activity, 16);

        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(padding, padding, padding, padding);

        for (Map.Entry<String, List<Blend.Staged>> entry : byFolder.entrySet()) {
            String folder = entry.getKey();
            File existing = firstExisting(entry.getValue());

            // Nothing there: the highest-priority offer wins by default, since
            // there is nothing to lose. Something there: it stays.
            selected.put(folder, existing == null ? entry.getValue().get(0) : null);

            rows.addView(label(activity, nameOf(activity, folder)));
            rows.addView(strip(activity, folder, entry.getValue(), existing, selected));
        }

        ScrollView scrolling = new ScrollView(activity);
        scrolling.addView(rows);

        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(activity.getString(R.string.artwork_new_title, gameName))
                .setView(scrolling)
                .setPositiveButton(R.string.artwork_new_save, (dialog, which) -> {
                    List<Blend.Staged> chosen = new ArrayList<>();
                    for (Blend.Staged one : selected.values()) {
                        if (one != null) chosen.add(one);
                    }
                    onSave.take(chosen);
                })
                .setNegativeButton(android.R.string.cancel,
                                   (dialog, which) -> onSave.take(new ArrayList<>()))
                .show();
    }

    /** One folder's choices, side by side. */
    private static View strip(Activity activity, String folder, List<Blend.Staged> offers,
                              File existing, Map<String, Blend.Staged> selected) {
        LinearLayout tiles = new LinearLayout(activity);
        tiles.setOrientation(LinearLayout.HORIZONTAL);

        List<View> all = new ArrayList<>();

        if (existing != null) {
            View yours = tile(activity, existing,
                              activity.getString(R.string.artwork_new_yours),
                              () -> selected.put(folder, null), all);
            all.add(yours);
            tiles.addView(container(activity, yours,
                                    activity.getString(R.string.artwork_new_yours)));
        }

        for (Blend.Staged offer : offers) {
            View tile = tile(activity, offer.file, offer.source,
                             () -> selected.put(folder, offer), all);
            all.add(tile);
            tiles.addView(container(activity, tile, offer.source));
        }

        mark(all, existing == null ? all.get(0) : all.get(all.size() - offers.size()));

        HorizontalScrollView scrolling = new HorizontalScrollView(activity);
        scrolling.addView(tiles);
        return scrolling;
    }

    /**
     * One picture, which is the thing that takes the tap.
     *
     * <b>The description and the click are on the same view</b>, deliberately:
     * a test finds the tile by its description and then taps what it found, so
     * a description on the wrapper and a listener on the picture is a tap that
     * lands on nothing and reads as a sheet that ignores you.
     *
     * The description is set once, at build time, and never changed again -
     * anything on screen whose contentDescription changes continuously makes
     * the accessibility tree never settle, and takes the whole UI Automator
     * suite down with it.
     */
    private static View tile(Activity activity, File picture, String from,
                             Runnable choose, List<View> all) {
        ImageView image = new ImageView(activity);
        image.setLayoutParams(new LinearLayout.LayoutParams(dp(activity, TILE_DP),
                                                            dp(activity, TILE_DP)));
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImageBitmap(BitmapFactory.decodeFile(picture.getAbsolutePath()));
        image.setContentDescription(from);

        image.setOnClickListener(view -> {
            choose.run();
            mark(all, view);
        });
        return image;
    }

    /** The picture with its source written under it. */
    private static View container(Activity activity, View tile, String from) {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(dp(activity, 4), dp(activity, 4), dp(activity, 4), dp(activity, 4));

        TextView who = new TextView(activity);
        who.setText(from);
        who.setGravity(Gravity.CENTER_HORIZONTAL);

        column.addView(tile);
        column.addView(who);
        return column;
    }

    /** Which tile is chosen, shown by the only means that needs no drawable. */
    private static void mark(List<View> all, View chosen) {
        for (View one : all) one.setAlpha(one == chosen ? 1f : 0.4f);
    }

    private static TextView label(Activity activity, String text) {
        TextView label = new TextView(activity);
        label.setText(text);
        label.setPadding(0, dp(activity, 12), 0, 0);
        return label;
    }

    /**
     * A media folder's name in the reader's own language.
     *
     * The two arrays are parallel and already exist for the media setting -
     * {@code scrape_media_folders} is ES-DE's own names, never translated, and
     * {@code scrape_media_entries} is what to call them on a screen. Falling
     * back to the folder name is right for a folder the arrays do not know:
     * an untranslated word beats a blank line.
     */
    private static String nameOf(Activity activity, String folder) {
        String[] folders = activity.getResources().getStringArray(R.array.scrape_media_folders);
        String[] names = activity.getResources().getStringArray(R.array.scrape_media_entries);

        for (int at = 0; at < folders.length && at < names.length; at++) {
            if (folders[at].equals(folder)) return names[at];
        }
        return folder;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    /** Whatever is already on disk for this folder, or null. Every offer for
     *  one folder answers the same file, so the first is enough. */
    private static File firstExisting(List<Blend.Staged> offers) {
        for (Blend.Staged one : offers) {
            if (one.contested) return one.existing;
        }
        return null;
    }
}
```

`Blend.Staged.existing` and `Blend.staged(...)` were both declared in task 5, so nothing in `Blend` changes here.

- [ ] **Step 2: Rewrite `ScrapeOneGame`**

Replace `scrape`, `look` and `write`:

```java
    void scrape(Entry entry) {
        List<Provider> sources = Scrapers.enabled(activity);
        if (sources.isEmpty()) return;

        String path = Metadata.relativePath(activity, entry.uri);
        if (path == null) return;

        // No hand-edit confirmation any more: a scrape fills gaps and cannot
        // overwrite a typed value, so there is nothing to warn about.
        look(sources, entry, path);
    }

    private void look(List<Provider> sources, Entry entry, String path) {
        ProgressDialog waiting = waiting();

        Work.alone("scrape", () -> {
            Blend.Result result = Blend.run(activity, sources, new Http.Real(activity),
                                            entry, path, Scrapers.wanted(activity),
                                            Blend.Media.OFFER_ALTERNATIVES,
                                            this::askOnTheUiThread,
                                            // Nothing to cancel: one game is
                                            // seconds, and the only escape
                                            // offered is the chooser's own.
                                            () -> false);

            activity.runOnUiThread(() -> {
                dismiss(waiting);
                finish(result, entry, path);
            });
        });
    }

    /**
     * Nothing to ask about goes straight in; anything contested gets the
     * sheet.
     *
     * The facts are already stored either way - Blend writes them before this
     * is reached, the same way Scrape.apply writes them before fetching media,
     * and for the same reason: a scrape that got the metadata has still
     * improved the row.
     */
    private void finish(Blend.Result result, Entry entry, String path) {
        if (result.staged.isEmpty()) {
            say(reasonFor(result));
            activity.metadataChanged();
            return;
        }

        if (!result.anythingContested()) {
            commit(result.staged, path, result);
            return;
        }

        ArtworkChoice.show(activity, entry.name, result.staged,
                           chosen -> commit(chosen, path, result));
    }

    private void commit(List<Blend.Staged> chosen, String path, Blend.Result result) {
        Work.alone("scrape-commit", () -> {
            int installed = Blend.commit(activity, path, chosen);

            activity.runOnUiThread(() -> {
                say(installed > 0
                        ? activity.getString(R.string.scrape_done_media, installed)
                        : reasonFor(result));
                activity.metadataChanged();
            });
        });
    }

    /**
     * Which of several, on the UI thread, with the worker parked behind a
     * latch.
     *
     * The same shape {@code Sweep.Watcher.chooseFrom} already uses and for the
     * same reason: the loop cannot go on until somebody says which game this
     * is, and a timeout that guessed would be one game's cover on another for
     * ever.
     */
    private Candidate askOnTheUiThread(String sourceName, List<Candidate> found,
                                       String game) {
        final java.util.concurrent.CountDownLatch answered =
                new java.util.concurrent.CountDownLatch(1);
        final Candidate[] chosen = new Candidate[1];

        String[] labels = new String[found.size()];
        for (int at = 0; at < found.size(); at++) labels[at] = found.get(at).describe();

        activity.runOnUiThread(() -> new AlertDialog.Builder(
                        activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(activity.getString(R.string.scrape_choose_from, sourceName))
                .setItems(labels, (dialog, which) -> {
                    chosen[0] = found.get(which);
                    answered.countDown();
                })
                .setOnCancelListener(dialog -> answered.countDown())
                .setNegativeButton(android.R.string.cancel,
                                   (dialog, which) -> answered.countDown())
                .show());

        try {
            answered.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return chosen[0];
    }

    /** What to tell somebody when no picture arrived: the first source that
     *  refused, or that nothing was found. */
    private String reasonFor(Blend.Result result) {
        if (!result.failures.isEmpty()) return reasonFor(result.failures.get(0).why);
        if (result.consulted.isEmpty()) return activity.getString(R.string.scrape_nothing);

        return activity.getString(R.string.scrape_done);
    }
```

`R.string.scrape_choose_from` is new — `"Which game, according to %1$s?"` — and task 9 adds it in nine languages. `R.string.scrape_choose` is then unused; remove it there too.

- [ ] **Step 3: Write the sheet's test**

The sheet is reached by calling `ArtworkChoice.show` on the UI thread with staged entries the test builds from files it writes — a live contested scrape would need two services to disagree about a game on the bench, which is not a fixture anybody can rely on. `Blend.staged(...)` exists for this, and is public for it.

`ArtworkChoice` is package-visible in `dev.ldlab.zedex.screen`, so the test goes in that package too.

Create `app/src/androidTest/java/dev/ldlab/zedex/screen/ArtworkChoiceTest.java`:

```java
package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.library.meta.Artwork;
import dev.ldlab.zedex.library.scrape.Blend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Choosing between the pictures several sources offered.
 *
 * Driven by calling the sheet directly rather than by scraping: a live
 * contested scrape needs two services to disagree about a game that happens to
 * be on the bench, which is not a fixture anybody can rely on. What is under
 * test is the sheet and what it commits, and both are the same either way.
 */
@RunWith(AndroidJUnit4.class)
public class ArtworkChoiceTest {

    private static final String PATH = "./zedex-sheet-test/Game.tap";
    private static final long TIMEOUT_MS = 5000;

    private Context context;
    private UiDevice device;
    private Activity activity;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

        Artwork.clearStaging(context);
        clearMedia();

        activity = anyActivity();
    }

    @After
    public void tidyUp() {
        Artwork.clearStaging(context);
        clearMedia();
    }

    private void clearMedia() {
        for (String extension : Arrays.asList("png", "jpg")) {
            File file = Artwork.fileFor(context, PATH, "covers", extension);
            if (file.isFile()) file.delete();
        }
        Artwork.forget(PATH);
    }

    /**
     * An activity of ours to hang the dialog on.
     *
     * The library is the one that owns this sheet in the app, and any of ours
     * will do to show it - what is being tested is the dialog, not who
     * launched it.
     */
    private Activity anyActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                LibraryActivity.class.getName(), null, false);

        context.startActivity(
                new android.content.Intent(context, LibraryActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));

        Activity launched = instrumentation.waitForMonitorWithTimeout(monitor, TIMEOUT_MS);
        assertTrue("the library never came up", launched != null);
        return launched;
    }

    /** A one-colour picture, so two of them differ by more than their name. */
    private File picture(File into, int colour) throws IOException {
        File parent = into.getParentFile();
        if (parent != null) parent.mkdirs();

        Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(colour);

        try (FileOutputStream out = new FileOutputStream(into)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        return into;
    }

    /** Shows the sheet and answers with what it committed. */
    private List<Blend.Staged> showAndTap(List<Blend.Staged> staged, String tapThis,
                                          String thenPress) throws Exception {
        AtomicReference<List<Blend.Staged>> chosen = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        activity.runOnUiThread(() -> ArtworkChoice.show(
                activity, "Game.tap", staged,
                taken -> {
                    chosen.set(taken);
                    done.countDown();
                }));

        if (tapThis != null) {
            UiObject2 tile = device.wait(Until.findObject(By.desc(tapThis)), TIMEOUT_MS);
            assertTrue("no tile described " + tapThis, tile != null);
            tile.click();
        }

        UiObject2 button = device.wait(Until.findObject(By.text(thenPress)), TIMEOUT_MS);
        assertTrue("no " + thenPress + " button", button != null);
        button.click();

        assertTrue("the sheet never answered", done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        return chosen.get();
    }

    private String save() {
        return context.getString(dev.ldlab.zedex.R.string.artwork_new_save);
    }

    private String yours() {
        return context.getString(dev.ldlab.zedex.R.string.artwork_new_yours);
    }

    /**
     * Save without touching anything keeps what was there.
     *
     * The safe answer is the default one, so somebody who does not understand
     * the question cannot lose a picture by pressing the obvious button.
     */
    @Test
    public void savingWithoutChoosingKeepsThePictureAlreadyThere() throws Exception {
        File mine = picture(Artwork.fileFor(context, PATH, "covers", "png"), Color.GREEN);
        File theirs = picture(Artwork.stagingFileFor(context, PATH, "covers/ZXInfo", "png"),
                              Color.RED);

        List<Blend.Staged> staged = Collections.singletonList(
                Blend.staged("covers", "png", "ZXInfo", theirs, true, mine));

        List<Blend.Staged> chosen = showAndTap(staged, null, save());

        assertTrue("something was committed without being chosen", chosen.isEmpty());
    }

    /** Choosing the new one installs it, and the old extension goes with it. */
    @Test
    public void choosingTheNewPictureReplacesTheOldOneAndItsExtension() throws Exception {
        File mine = picture(Artwork.fileFor(context, PATH, "covers", "png"), Color.GREEN);
        File theirs = picture(Artwork.stagingFileFor(context, PATH, "covers/ZXInfo", "jpg"),
                              Color.RED);

        List<Blend.Staged> staged = Collections.singletonList(
                Blend.staged("covers", "jpg", "ZXInfo", theirs, true, mine));

        List<Blend.Staged> chosen = showAndTap(staged, "ZXInfo", save());

        assertEquals(1, chosen.size());
        assertEquals("ZXInfo", chosen.get(0).source);

        assertEquals(1, Blend.commit(context, PATH, chosen));

        assertTrue(Artwork.fileFor(context, PATH, "covers", "jpg").isFile());
        assertFalse("the png it replaced is still there, and png outranks jpg",
                    Artwork.fileFor(context, PATH, "covers", "png").isFile());
    }

    /**
     * Every offer is on screen, named after the source that made it.
     *
     * Matched on {@code android.widget.ImageView}: a subclassed ImageView
     * still answers the framework class from getAccessibilityClassName, so a
     * selector built from the subclass finds nothing - which looks exactly
     * like a screen that failed to draw.
     */
    @Test
    public void everyOfferIsOnScreenAndNamedAfterItsSource() throws Exception {
        File mine = picture(Artwork.fileFor(context, PATH, "covers", "png"), Color.GREEN);
        File one = picture(Artwork.stagingFileFor(context, PATH, "covers/ZXInfo", "png"),
                           Color.RED);
        File two = picture(Artwork.stagingFileFor(context, PATH, "covers/ScreenScraper", "png"),
                           Color.BLUE);

        List<Blend.Staged> staged = Arrays.asList(
                Blend.staged("covers", "png", "ZXInfo", one, true, mine),
                Blend.staged("covers", "png", "ScreenScraper", two, true, mine));

        CountDownLatch done = new CountDownLatch(1);
        activity.runOnUiThread(() -> ArtworkChoice.show(
                activity, "Game.tap", staged, taken -> done.countDown()));

        for (String who : Arrays.asList(yours(), "ZXInfo", "ScreenScraper")) {
            UiObject2 tile = device.wait(
                    Until.findObject(By.clazz("android.widget.ImageView").desc(who)),
                    TIMEOUT_MS);
            assertTrue("nothing on screen is described " + who, tile != null);
        }

        UiObject2 cancel = device.wait(
                Until.findObject(By.text(context.getString(android.R.string.cancel))),
                TIMEOUT_MS);
        assertTrue("no Cancel button", cancel != null);
        cancel.click();

        assertTrue(done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }
}
```

`ArtworkChoice` is package-visible, so make `show` and `Chosen` visible to the test — they already are, both being package-visible in `dev.ldlab.zedex.screen`.

Two things to expect on the first run, and what each means:

- **The buttons are found by `By.text`**, so the dialog's Save must carry the string the test reads back from resources rather than an English literal — the app has languages, and the bench may not be in English.
- **`By.desc(who)` matches the `ImageView`**, which is also what carries the click listener — that pairing is why `ArtworkChoice.tile` returns the picture rather than a wrapper around it.

- [ ] **Step 4: Build and run**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug
env JAVA_HOME=/opt/android-studio/jbr ./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=dev.ldlab.zedex.screen.ArtworkChoiceTest
```

Expected: PASS. If the tiles are not found, check the selector matches `android.widget.ImageView` and not the layout's class.

- [ ] **Step 5: Commit**

```sh
git add app/src/main/java/dev/ldlab/zedex/screen/ArtworkChoice.java \
        app/src/main/java/dev/ldlab/zedex/screen/ScrapeOneGame.java \
        app/src/main/java/dev/ldlab/zedex/library/scrape/Blend.java \
        app/src/androidTest/java/dev/ldlab/zedex/screen/ArtworkChoiceTest.java
git commit -m "feat: choose between the pictures several sources offered

Every source is asked for every picture before anybody is asked
anything, so by the time the sheet appears the downloads are finished
and no thread is parked behind it.

Save with nothing touched changes nothing: whatever is on disk starts
selected, so the obvious button is the safe one and somebody who does
not understand the question cannot lose a picture by pressing it. A
folder that had nothing preselects the first source that offered one.

The chooser dialog says which source is asking. The same file matches
differently at each, and that is part of judging the five it offered."
```

---

### Task 9: The settings row, and nine files of strings

**Files:**
- Create: `app/src/main/java/dev/ldlab/zedex/screen/ScraperOrder.java`
- Modify: `app/src/main/java/dev/ldlab/zedex/screen/SettingsActivity.java`
- Modify: `app/src/main/res/xml/settings_library.xml`
- Modify: `app/src/main/res/values/strings.xml` and the eight `values-*/strings.xml`

**Interfaces:**
- Consumes: `Scrapers.names`, `Scrapers.enabled`, `Scrapers.save`.
- Produces: no code interface; the settings row that writes `KEY_SCRAPERS`.

- [ ] **Step 1: Replace the preference in XML**

In `res/xml/settings_library.xml`, replace the `ListPreference` block (keeping the comment above it, rewritten):

```xml
        <!--
            Which services answer, and in what order.

            Entries are filled in by SettingsActivity, not listed here: which
            providers a build has depends on whether it was given ScreenScraper
            credentials, and a hard-coded list would offer one that cannot
            work.

            A Preference and not a ListPreference: the choice is several
            services in an order, and no built-in widget expresses that.
        -->
        <Preference
            android:key="scrapers"
            android:title="@string/settings_scraper" />
```

- [ ] **Step 2: Build the dialog in `SettingsActivity`**

Replace the `findPreference(Prefs.KEY_SCRAPER)` block:

```java
            /*
             * Which services answer, and in what order - the order is the
             * priority, since the first to answer about a field keeps it.
             *
             * Checkboxes and arrows rather than drag: with two or three
             * entries drag buys nothing, and arrows are reachable by a pad and
             * by a screen reader, which drag is not.
             *
             * The whole row is hidden when there is only one, because an order
             * of one thing is a question with no answer.
             */
            Preference scrapers = findPreference(Prefs.KEY_SCRAPERS);
            if (scrapers != null) {
                java.util.List<String> available = Scrapers.names(getActivity());
                scrapers.setVisible(available.size() > 1);

                java.util.List<String> using = new java.util.ArrayList<>();
                for (Provider provider : Scrapers.enabled(getActivity())) {
                    using.add(provider.name());
                }

                scrapers.setSummary(using.isEmpty()
                        ? getString(R.string.settings_scrapers_none)
                        : getString(R.string.settings_scrapers_order,
                                    String.join(", ", using)));

                scrapers.setOnPreferenceClickListener(preference -> {
                    ScraperOrder.show(getActivity(), available, using, chosen -> {
                        Scrapers.save(getActivity(), chosen);
                        updateSummaries();
                    });
                    return true;
                });
            }
```

Create `app/src/main/java/dev/ldlab/zedex/screen/ScraperOrder.java`:

```java
package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Which services to scrape from, and in what order.
 *
 * <b>Arrows rather than drag.</b> With two or three entries drag buys nothing,
 * and arrows are reachable by a pad and by a screen reader, which drag is not -
 * this app is driven by a gamepad often enough that a control only a finger
 * can work is a control some people do not have.
 *
 * The list shown is every source this build has, in the order they will be
 * asked, with the disabled ones after the enabled ones. Rebuilt in place on
 * every move rather than animated: it is three rows.
 */
final class ScraperOrder {

    /** What the dialog came to: the enabled names, in order. */
    interface Chosen {
        void take(List<String> namesInOrder);
    }

    private ScraperOrder() {
    }

    static void show(Activity activity, List<String> available, List<String> enabled,
                     Chosen onSave) {
        // Enabled first and in their own order, then whatever is left - which
        // is what the list means: the order it will ask them in.
        List<String> order = new ArrayList<>(enabled);
        for (String name : available) {
            if (!order.contains(name)) order.add(name);
        }

        List<String> ticked = new ArrayList<>(enabled);

        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);

        int padding = Math.round(16 * activity.getResources().getDisplayMetrics().density);
        rows.setPadding(padding, padding, padding, padding);

        draw(activity, rows, order, ticked);

        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.settings_scraper)
                .setView(rows)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    List<String> chosen = new ArrayList<>();
                    for (String name : order) {
                        if (ticked.contains(name)) chosen.add(name);
                    }
                    onSave.take(chosen);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void draw(Activity activity, LinearLayout rows, List<String> order,
                             List<String> ticked) {
        rows.removeAllViews();

        for (int at = 0; at < order.size(); at++) {
            final int position = at;
            String name = order.get(at);

            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            CheckBox box = new CheckBox(activity);
            box.setText(name);
            box.setChecked(ticked.contains(name));
            box.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            box.setOnCheckedChangeListener((view, isChecked) -> {
                if (isChecked) {
                    if (!ticked.contains(name)) ticked.add(name);
                } else {
                    ticked.remove(name);
                }
            });

            row.addView(box);
            row.addView(arrow(activity, "▲", R.string.settings_scrapers_up,
                              position > 0,
                              () -> swap(activity, rows, order, ticked, position,
                                         position - 1)));
            row.addView(arrow(activity, "▼", R.string.settings_scrapers_down,
                              position < order.size() - 1,
                              () -> swap(activity, rows, order, ticked, position,
                                         position + 1)));

            rows.addView(row);
        }
    }

    /**
     * One arrow.
     *
     * Disabled rather than hidden at the ends of the list, so the rows do not
     * change width as things move and the two buttons stay where the finger
     * last found them.
     */
    private static Button arrow(Activity activity, String glyph, int description,
                                boolean usable, Runnable move) {
        Button button = new Button(activity);
        button.setText(glyph);
        button.setContentDescription(activity.getString(description));
        button.setEnabled(usable);
        button.setOnClickListener(view -> move.run());
        return button;
    }

    private static void swap(Activity activity, LinearLayout rows, List<String> order,
                             List<String> ticked, int from, int to) {
        String moved = order.remove(from);
        order.add(to, moved);

        draw(activity, rows, order, ticked);
    }
}
```

- [ ] **Step 3: Add the new strings to `values/strings.xml`**

```xml
    <string name="settings_scrapers_order">In order: %1$s</string>
    <string name="settings_scrapers_none">None chosen — scraping is off</string>
    <string name="settings_scrapers_up">Move up</string>
    <string name="settings_scrapers_down">Move down</string>
    <string name="scrape_choose_from">Which game, according to %1$s?</string>
    <string name="artwork_new_title">New artwork for %1$s</string>
    <string name="artwork_new_yours">Yours</string>
    <string name="artwork_new_save">Save</string>
```

Remove `scrape_overwrite`, `scrape_choose` and the `scrape_many_yours` plural, and change `scrape_many_estimate` from "about" to "at most":

```xml
    <plurals name="scrape_many_estimate">
        <item quantity="one">%1$d game · at most %2$d requests</item>
        <item quantity="other">%1$d games · at most %2$d requests</item>
    </plurals>
```

- [ ] **Step 4: Add the same eight keys to the other eight languages, and make the same three removals**

`values-cs`:
```xml
    <string name="settings_scrapers_order">V pořadí: %1$s</string>
    <string name="settings_scrapers_none">Nic nevybráno — stahování je vypnuté</string>
    <string name="settings_scrapers_up">Posunout nahoru</string>
    <string name="settings_scrapers_down">Posunout dolů</string>
    <string name="scrape_choose_from">Která hra, podle %1$s?</string>
    <string name="artwork_new_title">Nová grafika pro %1$s</string>
    <string name="artwork_new_yours">Vaše</string>
    <string name="artwork_new_save">Uložit</string>
```

`values-de`:
```xml
    <string name="settings_scrapers_order">Reihenfolge: %1$s</string>
    <string name="settings_scrapers_none">Nichts ausgewählt – Scraping ist aus</string>
    <string name="settings_scrapers_up">Nach oben</string>
    <string name="settings_scrapers_down">Nach unten</string>
    <string name="scrape_choose_from">Welches Spiel, laut %1$s?</string>
    <string name="artwork_new_title">Neue Bilder für %1$s</string>
    <string name="artwork_new_yours">Ihre</string>
    <string name="artwork_new_save">Speichern</string>
```

`values-es`:
```xml
    <string name="settings_scrapers_order">En orden: %1$s</string>
    <string name="settings_scrapers_none">Ninguna elegida: el scraping está desactivado</string>
    <string name="settings_scrapers_up">Subir</string>
    <string name="settings_scrapers_down">Bajar</string>
    <string name="scrape_choose_from">¿Qué juego, según %1$s?</string>
    <string name="artwork_new_title">Nuevas imágenes de %1$s</string>
    <string name="artwork_new_yours">Las tuyas</string>
    <string name="artwork_new_save">Guardar</string>
```

`values-fr`:
```xml
    <string name="settings_scrapers_order">Dans l\'ordre : %1$s</string>
    <string name="settings_scrapers_none">Aucune source choisie — le scraping est désactivé</string>
    <string name="settings_scrapers_up">Monter</string>
    <string name="settings_scrapers_down">Descendre</string>
    <string name="scrape_choose_from">Quel jeu, selon %1$s ?</string>
    <string name="artwork_new_title">Nouvelles images pour %1$s</string>
    <string name="artwork_new_yours">Les vôtres</string>
    <string name="artwork_new_save">Enregistrer</string>
```

`values-it`:
```xml
    <string name="settings_scrapers_order">In ordine: %1$s</string>
    <string name="settings_scrapers_none">Nessuna scelta: lo scraping è disattivato</string>
    <string name="settings_scrapers_up">Sposta su</string>
    <string name="settings_scrapers_down">Sposta giù</string>
    <string name="scrape_choose_from">Quale gioco, secondo %1$s?</string>
    <string name="artwork_new_title">Nuove immagini per %1$s</string>
    <string name="artwork_new_yours">Le tue</string>
    <string name="artwork_new_save">Salva</string>
```

`values-pl`:
```xml
    <string name="settings_scrapers_order">W kolejności: %1$s</string>
    <string name="settings_scrapers_none">Nie wybrano żadnego — pobieranie jest wyłączone</string>
    <string name="settings_scrapers_up">Przenieś w górę</string>
    <string name="settings_scrapers_down">Przenieś w dół</string>
    <string name="scrape_choose_from">Która gra, według %1$s?</string>
    <string name="artwork_new_title">Nowe grafiki dla %1$s</string>
    <string name="artwork_new_yours">Twoje</string>
    <string name="artwork_new_save">Zapisz</string>
```

`values-ru`:
```xml
    <string name="settings_scrapers_order">По порядку: %1$s</string>
    <string name="settings_scrapers_none">Ничего не выбрано — сбор данных отключён</string>
    <string name="settings_scrapers_up">Вверх</string>
    <string name="settings_scrapers_down">Вниз</string>
    <string name="scrape_choose_from">Какая игра, по данным %1$s?</string>
    <string name="artwork_new_title">Новые изображения для %1$s</string>
    <string name="artwork_new_yours">Ваше</string>
    <string name="artwork_new_save">Сохранить</string>
```

`values-uk`:
```xml
    <string name="settings_scrapers_order">За порядком: %1$s</string>
    <string name="settings_scrapers_none">Нічого не вибрано — збір даних вимкнено</string>
    <string name="settings_scrapers_up">Вгору</string>
    <string name="settings_scrapers_down">Вниз</string>
    <string name="scrape_choose_from">Яка гра, за даними %1$s?</string>
    <string name="artwork_new_title">Нові зображення для %1$s</string>
    <string name="artwork_new_yours">Ваше</string>
    <string name="artwork_new_save">Зберегти</string>
```

In each of the eight, also delete `scrape_overwrite`, `scrape_choose` and the `scrape_many_yours` plural if they are present, and change the `scrape_many_estimate` wording to that language's equivalent of "at most" ("nejvýše", "höchstens", "como máximo", "au maximum", "al massimo", "najwyżej", "не более", "не більше").

- [ ] **Step 5: Check the strings and the prefs**

```sh
scripts/check-strings.py
scripts/check-prefs.py
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug
```

Expected: both scripts clean, build OK. `check-strings.py` fails loudly on a `%1$s` that became `%1$d` — read what it says rather than guessing.

- [ ] **Step 6: Drive it by hand on a device**

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
scripts/ui-tap.py list
```

Open Settings → Library → the sources row, reorder, uncheck one, come back, and confirm the summary changed. This is a settings screen, so `ui-tap.py`'s cached tree is a trap — relaunch between checks.

- [ ] **Step 7: Commit**

```sh
git add app/src/main/java/dev/ldlab/zedex/screen/ScraperOrder.java \
        app/src/main/java/dev/ldlab/zedex/screen/SettingsActivity.java \
        app/src/main/res/xml/settings_library.xml \
        app/src/main/res/values/strings.xml app/src/main/res/values-*/strings.xml
git commit -m "feat: choose the sources and their order

Checkboxes and arrows rather than drag: with two or three entries drag
buys nothing, and arrows are reachable by a pad and a screen reader,
which drag is not. The row hides itself when a build has only one
source, as it always did.

The sweep's estimate says 'at most' rather than 'about'. It cannot be
exact any more - what a run fetches depends on which folders happen to
be empty - and a number that reads as a measurement while being an upper
bound is worse than one that says what it is."
```

---

### Task 10: The documentation, and the rules worth keeping

**Files:**
- Modify: `docs/INTERNALS.md`, `docs/LIBRARY.md`, `README.md`, `CLAUDE.md`

**Interfaces:** none.

- [ ] **Step 1: `docs/INTERNALS.md`, the *Scraping* section**

Add: the ordered source list; `Blend`'s loop and the two media policies; `Merge`'s one rule; the staging area and why the loser is deleted; per-source failure isolation.

- [ ] **Step 2: `docs/LIBRARY.md`**

One paragraph on what a scrape now does, in the register of the surrounding text.

- [ ] **Step 3: `README.md`**

One line. It is for people using the app; keep the reasoning out.

- [ ] **Step 4: `CLAUDE.md`, the hard-rules list**

Add, in the register of the existing rules:

- A contributor list, not a name: `isEsde` is "the only contributor", `isMine` is "among the contributors", `NOT_SCRAPED` is "no provider name among them".
- Fill gaps, never overwrite — which is what makes several sources safe, and why the hand-edit confirmation went.
- A sweep asks only for the folders that are empty; a one-game scrape asks for everything and stages it.
- Installing a picture deletes the other extensions for that game and folder — png outranks jpg, and a chosen jpg beside an unchosen png looks exactly like a choice that did nothing.
- The staging area is cleared on the way in, not on the way out.
- A refusal belongs to the source that made it, not to the run.

- [ ] **Step 5: Run everything one last time**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest
env JAVA_HOME=/opt/android-studio/jbr ./gradlew connectedDebugAndroidTest
scripts/check-strings.py
scripts/check-prefs.py
```

Expected: green. Read the **first** failure, not the count — one flake cascades into every later class failing the same way.

- [ ] **Step 6: Commit**

```sh
git add docs/INTERNALS.md docs/LIBRARY.md README.md CLAUDE.md
git commit -m "docs: several sources, and what that made true

The operational knowledge, not the change: which predicate answers which
question now that a row has contributors rather than an owner, why a
sweep asks only for the folders that are empty, and why installing a
picture has to delete the others - png outranks jpg, so a chosen jpg
beside an unchosen png looks exactly like a choice that did nothing."
```
