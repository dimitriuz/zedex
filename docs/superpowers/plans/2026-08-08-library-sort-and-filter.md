# Library Sort and Filter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sort the library by released year, file format and rating, and filter it by format, genre, rating threshold, developer and publisher.

**Architecture:** All the decision-making is pure Java with no Android types — `library/Filters` (what is set, what matches), `library/Sorting` (comparators, unknown-last) and `library/Facets` (distinct values and counts). `LibraryActivity` holds one `Filters` and calls them. Two UIs drive that one object: a toolbar button for touch, and `OptionsDialog`'s new pages for a gamepad.

**Tech Stack:** Java 17, Android SDK 36 (minSdk 30), AndroidX RecyclerView/Preference, JUnit 4. No new runtime dependencies.

## Global Constraints

- **Design spec:** `docs/superpowers/specs/2026-08-08-library-sort-and-filter-design.md`. Read it before Task 1.
- **A new string is nine files.** Every `values/strings.xml` key must also be added to `values-cs`, `-de`, `-es`, `-fr`, `-it`, `-pl`, `-ru`, `-uk`. Run `python3 scripts/check-strings.py` before every commit; it must print `8 translations agree with values/`.
- **Preference types never change.** `librarySort` is a String, `librarySortDescending` a boolean. Run `python3 scripts/check-prefs.py` before every commit; it must print `every preference is read as it is written`.
- **Never translate `*_values` arrays.** They are compared with `equals`.
- **Filters are session-only.** Never written to `SharedPreferences`. Sort stays persistent as it is today.
- **Unknown values sort last in both directions**, and an unrated game is not a game rated zero.
- **Build:** `env JAVA_HOME=/opt/android-studio/jbr ./gradlew --no-daemon assembleDebug`. Gradle only packages the prebuilt `.so`; no native change here, so no `build-native.sh` run is needed.
- **Unit tests:** `env JAVA_HOME=/opt/android-studio/jbr ./gradlew --no-daemon testDebugUnitTest`.
- **Instrumentation:** install with `adb install -r` and run `adb shell am instrument -w ...`; never `connectedAndroidTest`, which uninstalls the app and wipes its data. Set `secondScreen` to `false` first or the panel borrows the controls onto another display and the tests tap an empty screen.
- **Commit subjects** take a conventional prefix: `feat:`, `fix:`, `test:`, `refactor:`, `docs:`. The body explains *why*.

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/dev/ldlab/zedex/library/Filters.java` | **New.** What is filtered and whether an entry matches. No Android types. |
| `app/src/main/java/dev/ldlab/zedex/library/Sorting.java` | **New.** One comparator per sort field, unknown-last. No Android types. |
| `app/src/main/java/dev/ldlab/zedex/library/Facets.java` | **New.** Distinct values and counts per field, from a collection of `Meta`. No Android types. |
| `app/src/main/java/dev/ldlab/zedex/library/meta/Meta.java` | Gains `ratingOutOfFive()`, a number rather than a formatted string. |
| `app/src/main/java/dev/ldlab/zedex/library/meta/Metadata.java` | Gains `all(Context)` so `Facets` has something to walk. |
| `app/src/main/java/dev/ldlab/zedex/screen/LibraryActivity.java` | Holds the `Filters`, applies it, flattens when it is on, shows the chips. |
| `app/src/main/java/dev/ldlab/zedex/library/ui/OptionsDialog.java` | Three pages, and a value list one level deeper. |
| `app/src/test/java/dev/ldlab/zedex/library/*.java` | **New tier.** Unit tests for the three pure classes. |
| `app/src/androidTest/java/dev/ldlab/zedex/FilterTest.java` | **New.** That both ways in reach the same state, on a device. |

---

### Task 1: A JVM test tier, and `Filters`

**Files:**
- Modify: `app/build.gradle` (the `dependencies` block)
- Create: `app/src/main/java/dev/ldlab/zedex/library/Filters.java`
- Create: `app/src/test/java/dev/ldlab/zedex/library/FiltersTest.java`

**Interfaces:**
- Consumes: `dev.ldlab.zedex.library.Entry` (fields `kind`, `name`, `size`, `modified`), `dev.ldlab.zedex.library.meta.Meta` (fields `genre`, `developer`, `publisher`, `rating`), `dev.ldlab.zedex.library.Types.extension(String)`.
- Produces: `Filters` with `Field` enum (`FORMAT`, `GENRE`, `DEVELOPER`, `PUBLISHER`), `isEmpty()`, `activeFieldCount()`, `chosen(Field)`, `toggle(Field,String)`, `clear(Field)`, `clearAll()`, `minStars()`, `setMinStars(float)`, `matches(Entry,Meta)`, and the statics `genresOf(String)` and `formatOf(Entry)`.

- [ ] **Step 1: Add the unit-test tier**

In `app/build.gradle`, inside the existing `dependencies { ... }` block, immediately above the `androidTestImplementation` lines, add:

```groovy
    /*
     * The JVM tier. Everything in library/ that decides something - what a
     * filter matches, how a field sorts, which values exist - is plain Java
     * with no Android types, so it can be tested here in seconds rather than
     * on a device in minutes. See docs/superpowers/specs for why that
     * boundary was drawn where it was.
     */
    testImplementation 'junit:junit:4.13.2'
```

- [ ] **Step 2: Write the failing test**

Create `app/src/test/java/dev/ldlab/zedex/library/FiltersTest.java`:

```java
package dev.ldlab.zedex.library;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.ldlab.zedex.library.meta.Meta;

import org.junit.Test;

import java.util.Arrays;

/**
 * What a filter matches, and what it leaves alone.
 *
 * On the JVM, not a device: none of this touches Android, and the whole point
 * of Filters being a class of its own is that the answers can be checked in
 * seconds.
 */
public class FiltersTest {

    private Meta meta(String genre, String developer, String publisher, String rating) {
        return new Meta("./g.tap", "G", null, developer, publisher, genre,
                        null, null, rating, "esde");
    }

    private Entry file(String name) {
        return new Entry(Entry.Kind.FILE, name, null, null, 1024, 0);
    }

    @Test
    public void nothingSetMatchesEverything() {
        Filters filters = new Filters();

        assertTrue(filters.isEmpty());
        assertEquals(0, filters.activeFieldCount());
        assertTrue(filters.matches(file("a.tap"), null));
        assertTrue(filters.matches(file("a.tap"), meta("Platform", "Ocean", "Ocean", "0.9")));
    }

    @Test
    public void orWithinAField() {
        Filters filters = new Filters();
        filters.toggle(Filters.Field.FORMAT, "tap");
        filters.toggle(Filters.Field.FORMAT, "tzx");

        assertTrue(filters.matches(file("a.tap"), null));
        assertTrue(filters.matches(file("a.tzx"), null));
        assertFalse(filters.matches(file("a.z80"), null));
    }

    @Test
    public void andAcrossFields() {
        Filters filters = new Filters();
        filters.toggle(Filters.Field.FORMAT, "tap");
        filters.toggle(Filters.Field.GENRE, "Platform");

        assertTrue(filters.matches(file("a.tap"), meta("Platform", null, null, null)));
        assertFalse(filters.matches(file("a.tzx"), meta("Platform", null, null, null)));
        assertFalse(filters.matches(file("a.tap"), meta("Racing", null, null, null)));
    }

    /** ES-DE writes compound genres; a person looking for racing games should
     *  not have to know what it was filed beside. */
    @Test
    public void genresAreSplitOnCommas() {
        assertEquals(Arrays.asList("Racing", "Driving"),
                     Filters.genresOf("Racing, Driving"));
        assertEquals(Arrays.asList("Racing", "Driving"),
                     Filters.genresOf("  Racing ,Driving  "));
        assertEquals(Arrays.asList("Platform"), Filters.genresOf("Platform"));
        assertTrue(Filters.genresOf(null).isEmpty());
        assertTrue(Filters.genresOf("  ").isEmpty());

        Filters filters = new Filters();
        filters.toggle(Filters.Field.GENRE, "Driving");
        assertTrue(filters.matches(file("a.tap"), meta("Racing, Driving", null, null, null)));
    }

    /**
     * ES-DE's 0.9 is exactly 4.5 out of five. A comparison that is
     * accidentally strict loses every top-rated game in the collection.
     */
    @Test
    public void theRatingThresholdIsInclusive() {
        Filters filters = new Filters();
        filters.setMinStars(4.5f);

        assertTrue(filters.matches(file("a.tap"), meta(null, null, null, "0.9")));
        assertTrue(filters.matches(file("a.tap"), meta(null, null, null, "1.0")));
        assertFalse(filters.matches(file("a.tap"), meta(null, null, null, "0.8")));
    }

    /** An unrated game is not a game rated zero: a threshold excludes it. */
    @Test
    public void unratedIsExcludedByAThreshold() {
        Filters filters = new Filters();
        filters.setMinStars(3f);

        assertFalse(filters.matches(file("a.tap"), meta(null, null, null, null)));
        assertFalse(filters.matches(file("a.tap"), null));
    }

    /** A game ES-DE never scraped has no genre, so a genre filter excludes it
     *  rather than letting it through for lack of an opinion. */
    @Test
    public void unscrapedIsExcludedByAMetadataFilter() {
        Filters filters = new Filters();
        filters.toggle(Filters.Field.DEVELOPER, "Ocean");

        assertFalse(filters.matches(file("a.tap"), null));
        assertFalse(filters.matches(file("a.tap"), meta(null, null, null, null)));
        assertTrue(filters.matches(file("a.tap"), meta(null, "Ocean", null, null)));
    }

    @Test
    public void clearingPutsItBack() {
        Filters filters = new Filters();
        filters.toggle(Filters.Field.FORMAT, "tap");
        filters.setMinStars(4f);
        assertEquals(2, filters.activeFieldCount());

        filters.clear(Filters.Field.FORMAT);
        assertEquals(1, filters.activeFieldCount());

        filters.clearAll();
        assertTrue(filters.isEmpty());
        assertEquals(0f, filters.minStars(), 0.001f);
    }

    /** Toggling the same value twice takes it off again. */
    @Test
    public void toggleIsAToggle() {
        Filters filters = new Filters();
        filters.toggle(Filters.Field.FORMAT, "tap");
        assertTrue(filters.chosen(Filters.Field.FORMAT).contains("tap"));

        filters.toggle(Filters.Field.FORMAT, "tap");
        assertTrue(filters.chosen(Filters.Field.FORMAT).isEmpty());
        assertTrue(filters.isEmpty());
    }

    @Test
    public void formatIsTheExtensionLowercased() {
        assertEquals("tap", Filters.formatOf(file("Game.TAP")));
        assertEquals("tzx", Filters.formatOf(file("a.b.tzx")));
        assertEquals("", Filters.formatOf(file("noextension")));
    }
}
```

- [ ] **Step 3: Run it to make sure it fails**

Run: `env JAVA_HOME=/opt/android-studio/jbr ./gradlew --no-daemon testDebugUnitTest`
Expected: FAIL to compile — `cannot find symbol: class Filters`.

- [ ] **Step 4: Write `Filters`**

Create `app/src/main/java/dev/ldlab/zedex/library/Filters.java`:

```java
package dev.ldlab.zedex.library;

import dev.ldlab.zedex.library.meta.Meta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the library is currently narrowed to, and whether a game is in it.
 *
 * Plain Java on purpose: no Context, no views, nothing from Android. That is
 * what lets the awkward parts - compound genres, an inclusive threshold, the
 * difference between unrated and rated zero - be checked on the JVM in seconds
 * rather than on a device, and it keeps the rules in one place instead of
 * spread through the listing code.
 *
 * Held for the session and never written to preferences. Sort is a preference,
 * because it is how somebody likes their library; a filter is a question they
 * are asking now, and the person most likely to meet a forgotten one is the
 * person who set it weeks ago and has since decided the app is broken.
 *
 * Values combine as OR within a field and AND across fields: tap or tzx, and
 * Platform. That is the combination people actually want.
 */
public final class Filters {

    /** The four list-shaped fields. The rating is a threshold, not a list, so
     *  it is not one of these - see {@link #minStars}. */
    public enum Field { FORMAT, GENRE, DEVELOPER, PUBLISHER }

    private final Map<Field, Set<String>> chosen = new EnumMap<>(Field.class);

    /** Out of five, so it reads the way the pane shows it. Zero is off. */
    private float minStars;

    public Filters() {
        for (Field field : Field.values()) chosen.put(field, new LinkedHashSet<>());
    }

    /** Nothing is narrowed, so the library is showing everything. */
    public boolean isEmpty() {
        return activeFieldCount() == 0;
    }

    /** How many fields are set, for the row that says so without listing them. */
    public int activeFieldCount() {
        int active = minStars > 0f ? 1 : 0;
        for (Field field : Field.values()) {
            if (!chosen.get(field).isEmpty()) active++;
        }
        return active;
    }

    /** What is picked for one field; never null, and not to be written to. */
    public Set<String> chosen(Field field) {
        return Collections.unmodifiableSet(chosen.get(field));
    }

    /** On if it was off, off if it was on. */
    public void toggle(Field field, String value) {
        Set<String> set = chosen.get(field);
        if (!set.remove(value)) set.add(value);
    }

    public void clear(Field field) {
        chosen.get(field).clear();
    }

    public void clearAll() {
        for (Field field : Field.values()) chosen.get(field).clear();
        minStars = 0f;
    }

    public float minStars() {
        return minStars;
    }

    /** Out of five. Zero turns the threshold off. */
    public void setMinStars(float stars) {
        minStars = stars;
    }

    /**
     * Whether this game is in what is currently being shown.
     *
     * {@code meta} is null for a game the store knows nothing about, and every
     * metadata filter excludes it: a game with no genre is not a game of every
     * genre. The format filter is the exception, since the filename always
     * answers it.
     */
    public boolean matches(Entry entry, Meta meta) {
        if (!matchesField(Field.FORMAT, formatOf(entry))) return false;

        if (!chosen.get(Field.GENRE).isEmpty()) {
            boolean any = false;
            for (String genre : genresOf(meta == null ? null : meta.genre)) {
                if (chosen.get(Field.GENRE).contains(genre)) { any = true; break; }
            }
            if (!any) return false;
        }

        if (!matchesField(Field.DEVELOPER, meta == null ? null : meta.developer)) return false;
        if (!matchesField(Field.PUBLISHER, meta == null ? null : meta.publisher)) return false;

        if (minStars > 0f) {
            float stars = meta == null ? -1f : meta.ratingOutOfFive();

            // Not >=  with a tolerance of nothing: ES-DE's 0.9 is exactly 4.5,
            // and a strict comparison here would drop every best-rated game in
            // the collection.
            if (stars < 0f || stars + 0.0001f < minStars) return false;
        }

        return true;
    }

    private boolean matchesField(Field field, String value) {
        Set<String> wanted = chosen.get(field);
        if (wanted.isEmpty()) return true;

        return value != null && wanted.contains(value);
    }

    /**
     * One genre string as the genres it actually names.
     *
     * ES-DE writes compound values - {@code "Racing, Driving"} - and offering
     * those whole would make a person looking for racing games guess what it
     * was filed beside. Order is kept so a caller can show the first as the
     * principal one.
     */
    public static List<String> genresOf(String genre) {
        List<String> names = new ArrayList<>();
        if (genre == null) return names;

        for (String part : genre.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) names.add(trimmed);
        }

        return names;
    }

    /** The file's extension; empty when it has none. {@code Types.extension}
     *  already lowercases and already answers "" rather than null. */
    public static String formatOf(Entry entry) {
        return Types.extension(entry.name);
    }
}
```

- [ ] **Step 5: Add `Meta.ratingOutOfFive()`**

`Meta.stars()` formats for the screen and cannot be compared. In `app/src/main/java/dev/ldlab/zedex/library/meta/Meta.java`, add this immediately above `stars()`:

```java
    /**
     * The rating out of five as a number, or {@code -1} when there is none.
     *
     * Separate from {@link #stars}, which formats for a screen and is no use
     * for a comparison - it is localised, so a decimal point is a comma in
     * half the languages this app ships in.
     */
    public float ratingOutOfFive() {
        if (rating == null || rating.isEmpty()) return -1f;

        try {
            float fraction = Float.parseFloat(rating.trim());
            if (fraction < 0f || fraction > 1f) return -1f;

            return fraction * 5f;
        } catch (NumberFormatException e) {
            return -1f;
        }
    }
```

and rewrite the body of `stars()` to use it, leaving its doc comment as it is:

```java
    public String stars() {
        float out = ratingOutOfFive();
        if (out < 0f) return null;

        return String.format(java.util.Locale.getDefault(), "%.1f", out);
    }
```

- [ ] **Step 6: Run the tests**

Run: `env JAVA_HOME=/opt/android-studio/jbr ./gradlew --no-daemon testDebugUnitTest`
Expected: PASS, 9 tests.

Then confirm the app still builds: `env JAVA_HOME=/opt/android-studio/jbr ./gradlew --no-daemon assembleDebug`

- [ ] **Step 7: Commit**

```bash
git add app/build.gradle app/src/test app/src/main/java/dev/ldlab/zedex/library/Filters.java \
        app/src/main/java/dev/ldlab/zedex/library/meta/Meta.java
git commit -m "feat: what the library is narrowed to, and a tier to test it on

Filters holds what is picked and answers whether a game is in it. Plain Java -
no Context, no views - so the awkward parts can be checked in seconds instead of
on a device: compound genres split on commas, a threshold that must include
ES-DE's own 0.9 as exactly 4.5, and the difference between a game with no rating
and a game rated nought.

This is also the app's first JVM test tier. There was none, so a pure string
comparison cost an emulator, an install and an uninstall that wiped the data
folder; nine tests here run in about a second."
```

---

### Task 2: Sorting, with the new fields and unknown last

**Files:**
- Create: `app/src/main/java/dev/ldlab/zedex/library/Sorting.java`
- Create: `app/src/test/java/dev/ldlab/zedex/library/SortingTest.java`

**Interfaces:**
- Consumes: `Entry`, `Meta`, `Filters.formatOf(Entry)`, `Meta.ratingOutOfFive()`, `Meta.year()`.
- Produces: `Sorting.FIELDS` (a `String[]`), the constants `NAME`, `SIZE`, `RELEASED`, `FORMAT`, `RATING`, the interface `Sorting.Lookup { Meta of(Entry); }`, `Sorting.comparator(String field, boolean descending, Lookup lookup)`, and `Sorting.fieldOrDefault(String stored)`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/ldlab/zedex/library/SortingTest.java`:

```java
package dev.ldlab.zedex.library;

import static org.junit.Assert.assertEquals;

import dev.ldlab.zedex.library.meta.Meta;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SortingTest {

    private final Map<String, Meta> store = new HashMap<>();

    private Entry file(String name, long size) {
        return new Entry(Entry.Kind.FILE, name, null, null, size, 0);
    }

    private void scrape(String name, String released, String rating) {
        store.put(name, new Meta("./" + name, name, null, null, null, null,
                                 released, null, rating, "esde"));
    }

    private List<String> sorted(String field, boolean descending, Entry... entries) {
        List<Entry> list = new ArrayList<>(Arrays.asList(entries));
        Collections.sort(list, Sorting.comparator(field, descending, e -> store.get(e.name)));

        List<String> names = new ArrayList<>();
        for (Entry entry : list) names.add(entry.name);
        return names;
    }

    @Test
    public void byNameIsCaseInsensitive() {
        assertEquals(Arrays.asList("apple", "Banana", "cherry"),
                sorted(Sorting.NAME, false, file("Banana", 1), file("cherry", 1), file("apple", 1)));
    }

    @Test
    public void bySize() {
        assertEquals(Arrays.asList("small", "big"),
                sorted(Sorting.SIZE, false, file("big", 900), file("small", 10)));
        assertEquals(Arrays.asList("big", "small"),
                sorted(Sorting.SIZE, true, file("big", 900), file("small", 10)));
    }

    @Test
    public void byFormatGroupsExtensions() {
        assertEquals(Arrays.asList("b.tap", "a.tzx", "c.z80"),
                sorted(Sorting.FORMAT, false, file("c.z80", 1), file("a.tzx", 1), file("b.tap", 1)));
    }

    @Test
    public void byReleasedYear() {
        scrape("old", "19870101T000000", null);
        scrape("new", "20200101T000000", null);

        assertEquals(Arrays.asList("old", "new"),
                sorted(Sorting.RELEASED, false, file("new", 1), file("old", 1)));
    }

    @Test
    public void byRating() {
        scrape("good", null, "0.9");
        scrape("poor", null, "0.2");

        assertEquals(Arrays.asList("poor", "good"),
                sorted(Sorting.RATING, false, file("good", 1), file("poor", 1)));
        assertEquals(Arrays.asList("good", "poor"),
                sorted(Sorting.RATING, true, file("good", 1), file("poor", 1)));
    }

    /**
     * The case that is easy to get wrong by reversing the whole comparator:
     * descending by rating must open on the best games, and ascending must not
     * open on the ones that have no rating at all.
     */
    @Test
    public void unknownSortsLastInBothDirections() {
        scrape("rated", null, "0.8");
        // "unrated" is deliberately absent from the store

        assertEquals(Arrays.asList("rated", "unrated"),
                sorted(Sorting.RATING, false, file("unrated", 1), file("rated", 1)));
        assertEquals(Arrays.asList("rated", "unrated"),
                sorted(Sorting.RATING, true, file("unrated", 1), file("rated", 1)));
    }

    @Test
    public void unknownYearSortsLastToo() {
        scrape("dated", "19900101T000000", null);

        assertEquals(Arrays.asList("dated", "undated"),
                sorted(Sorting.RELEASED, false, file("undated", 1), file("dated", 1)));
        assertEquals(Arrays.asList("dated", "undated"),
                sorted(Sorting.RELEASED, true, file("undated", 1), file("dated", 1)));
    }

    /** A stored field this build no longer has must not select nothing. */
    @Test
    public void anUnknownStoredFieldFallsBackToName() {
        assertEquals(Sorting.NAME, Sorting.fieldOrDefault("date"));
        assertEquals(Sorting.NAME, Sorting.fieldOrDefault(null));
        assertEquals(Sorting.NAME, Sorting.fieldOrDefault("nonsense"));
        assertEquals(Sorting.RATING, Sorting.fieldOrDefault(Sorting.RATING));
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `env JAVA_HOME=/opt/android-studio/jbr ./gradlew --no-daemon testDebugUnitTest`
Expected: FAIL to compile — `cannot find symbol: class Sorting`.

- [ ] **Step 3: Write `Sorting`**

Create `app/src/main/java/dev/ldlab/zedex/library/Sorting.java`:

```java
package dev.ldlab.zedex.library;

import dev.ldlab.zedex.library.meta.Meta;

import java.util.Comparator;
import java.util.Locale;

/**
 * The order the library is in.
 *
 * Plain Java, like {@link Filters} and for the same reason: the rule that
 * matters here - a game with no rating is not a game rated nought - is easy to
 * write as a comparator that simply reverses, and easy to check here that it
 * does not.
 */
public final class Sorting {

    public static final String NAME = "name";
    public static final String SIZE = "size";
    public static final String RELEASED = "released";
    public static final String FORMAT = "format";
    public static final String RATING = "rating";

    /** In the order the pickers show them. */
    public static final String[] FIELDS = { NAME, SIZE, RELEASED, FORMAT, RATING };

    /** How a caller reaches the metadata for an entry, without this class
     *  needing a Context to do it. */
    public interface Lookup {
        Meta of(Entry entry);
    }

    private Sorting() {
    }

    /**
     * The stored field, or {@link #NAME} when it is one this build does not
     * have.
     *
     * "date" was a field once. A stored value with no matching entry is how
     * this app has drawn a blank row before, so an unrecognised one resolves
     * to the default rather than to nothing.
     */
    public static String fieldOrDefault(String stored) {
        for (String field : FIELDS) {
            if (field.equals(stored)) return field;
        }
        return NAME;
    }

    public static Comparator<Entry> comparator(String field, boolean descending,
                                               Lookup lookup) {
        String chosen = fieldOrDefault(field);

        return (left, right) -> {
            // Whether a value is known is decided before direction is applied,
            // and always the same way round: descending by rating opens on the
            // best games, and ascending does not open on the ones that have no
            // rating at all. Reversing the whole comparison would do exactly
            // that.
            boolean hasLeft = has(chosen, left, lookup);
            boolean hasRight = has(chosen, right, lookup);

            if (hasLeft != hasRight) return hasLeft ? -1 : 1;
            if (!hasLeft) return byName(left, right);

            int order = compareValues(chosen, left, right, lookup);
            if (order != 0) return descending ? -order : order;

            // A stable, meaningful tie-break, so two games of the same year do
            // not swap places between one listing and the next.
            return byName(left, right);
        };
    }

    private static boolean has(String field, Entry entry, Lookup lookup) {
        switch (field) {
            case RELEASED: {
                Meta meta = lookup.of(entry);
                return meta != null && meta.year() != null;
            }
            case RATING: {
                Meta meta = lookup.of(entry);
                return meta != null && meta.ratingOutOfFive() >= 0f;
            }
            case FORMAT:
                return !Filters.formatOf(entry).isEmpty();
            default:
                return true;   // a name and a size are always known
        }
    }

    private static int compareValues(String field, Entry left, Entry right,
                                     Lookup lookup) {
        switch (field) {
            case SIZE:
                return Long.compare(left.size, right.size);

            case FORMAT:
                return Filters.formatOf(left).compareTo(Filters.formatOf(right));

            case RELEASED:
                return lookup.of(left).year().compareTo(lookup.of(right).year());

            case RATING:
                return Float.compare(lookup.of(left).ratingOutOfFive(),
                                     lookup.of(right).ratingOutOfFive());

            default:
                return byName(left, right);
        }
    }

    private static int byName(Entry left, Entry right) {
        return left.name.toLowerCase(Locale.ROOT)
                        .compareTo(right.name.toLowerCase(Locale.ROOT));
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `env JAVA_HOME=/opt/android-studio/jbr ./gradlew --no-daemon testDebugUnitTest`
Expected: PASS, 17 tests in total.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/ldlab/zedex/library/Sorting.java \
        app/src/test/java/dev/ldlab/zedex/library/SortingTest.java
git commit -m "feat: sort the library by released, format and rating

Three fields the store has always held and nothing could order by. Released
takes the year Meta.year already derives, format groups the extensions, rating
compares ES-DE's own fraction.

Unknown sorts last in both directions, which is the part worth a test: a game
with no rating is not a game rated nought, so descending must open on the best
games and ascending must not open on the four hundred that have no rating at
all. Written as a known-first comparison before direction is applied, because
the obvious implementation - reverse the whole comparator - does exactly the
wrong thing.

fieldOrDefault is here for the field that is going away: a stored value with no
matching entry is how this app has drawn a blank row before."
```

---

### Task 3: The values a filter can offer

**Files:**
- Create: `app/src/main/java/dev/ldlab/zedex/library/Facets.java`
- Create: `app/src/test/java/dev/ldlab/zedex/library/FacetsTest.java`
- Modify: `app/src/main/java/dev/ldlab/zedex/library/meta/Metadata.java` (add `all`)

**Interfaces:**
- Consumes: `Meta`, `Filters.Field`, `Filters.genresOf(String)`.
- Produces: `Facets.Value` (fields `name`, `count`), `Facets.of(Collection<Meta>)` returning `Map<Filters.Field, List<Value>>`, and `Metadata.all(Context)` returning `Collection<Meta>`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/ldlab/zedex/library/FacetsTest.java`:

```java
package dev.ldlab.zedex.library;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.ldlab.zedex.library.meta.Meta;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class FacetsTest {

    private Meta meta(String genre, String developer, String publisher) {
        return new Meta("./g.tap", "G", null, developer, publisher, genre,
                        null, null, null, "esde");
    }

    private List<Facets.Value> values(Map<Filters.Field, List<Facets.Value>> all,
                                      Filters.Field field) {
        return all.get(field);
    }

    @Test
    public void countsValuesAndOrdersByCommonest() {
        Map<Filters.Field, List<Facets.Value>> all = Facets.of(Arrays.asList(
                meta(null, "Ocean", null),
                meta(null, "Ocean", null),
                meta(null, "Dinamic", null)));

        List<Facets.Value> developers = values(all, Filters.Field.DEVELOPER);
        assertEquals(2, developers.size());
        assertEquals("Ocean", developers.get(0).name);
        assertEquals(2, developers.get(0).count);
        assertEquals("Dinamic", developers.get(1).name);
        assertEquals(1, developers.get(1).count);
    }

    /** A compound genre counts for each genre it names. */
    @Test
    public void genresAreSplitAndCountedApart() {
        Map<Filters.Field, List<Facets.Value>> all = Facets.of(Arrays.asList(
                meta("Racing, Driving", null, null),
                meta("Racing", null, null)));

        List<Facets.Value> genres = values(all, Filters.Field.GENRE);
        assertEquals(2, genres.size());
        assertEquals("Racing", genres.get(0).name);
        assertEquals(2, genres.get(0).count);
        assertEquals("Driving", genres.get(1).name);
        assertEquals(1, genres.get(1).count);
    }

    /** Nothing is offered that would match nothing. */
    @Test
    public void absentAndBlankValuesAreNotOffered() {
        Map<Filters.Field, List<Facets.Value>> all = Facets.of(Arrays.asList(
                meta(null, null, "  "),
                meta(null, null, "Ocean")));

        List<Facets.Value> publishers = values(all, Filters.Field.PUBLISHER);
        assertEquals(1, publishers.size());
        assertEquals("Ocean", publishers.get(0).name);
    }

    @Test
    public void anEmptyStoreOffersNothing() {
        Map<Filters.Field, List<Facets.Value>> all = Facets.of(java.util.Collections.emptyList());

        for (Filters.Field field : Filters.Field.values()) {
            assertTrue(field + " should have no values", values(all, field).isEmpty());
        }
    }

    /** Format is not in the store; it comes from the filenames. */
    @Test
    public void formatIsNotTakenFromTheStore() {
        Map<Filters.Field, List<Facets.Value>> all = Facets.of(Arrays.asList(
                meta("Platform", "Ocean", "Ocean")));

        assertTrue(values(all, Filters.Field.FORMAT).isEmpty());
    }

    @Test
    public void formatsComeFromTheEntries() {
        List<Facets.Value> formats = Facets.formatsOf(Arrays.asList(
                new Entry(Entry.Kind.FILE, "a.tap", null, null, 1, 0),
                new Entry(Entry.Kind.FILE, "b.TAP", null, null, 1, 0),
                new Entry(Entry.Kind.FILE, "c.tzx", null, null, 1, 0),
                new Entry(Entry.Kind.FOLDER, "sub", null, null, 0, 0)));

        assertEquals(2, formats.size());
        assertEquals("tap", formats.get(0).name);
        assertEquals(2, formats.get(0).count);
        assertEquals("tzx", formats.get(1).name);
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `env JAVA_HOME=/opt/android-studio/jbr ./gradlew --no-daemon testDebugUnitTest`
Expected: FAIL to compile — `cannot find symbol: class Facets`.

- [ ] **Step 3: Write `Facets`**

Create `app/src/main/java/dev/ldlab/zedex/library/Facets.java`:

```java
package dev.ldlab.zedex.library;

import dev.ldlab.zedex.library.meta.Meta;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which values a filter can actually offer, and how many games each would
 * bring back.
 *
 * Built from the collection rather than from a fixed list, so nothing is ever
 * offered that would match nothing - and so a collection of Spanish budget
 * titles offers its own publishers rather than somebody else's.
 *
 * Commonest first. On the collection this was designed against that puts Ocean
 * and Platform at the top, which is what somebody scrolling a list of 277
 * developers needs.
 */
public final class Facets {

    /** One offerable value, and how many games have it. */
    public static final class Value {
        public final String name;
        public final int count;

        public Value(String name, int count) {
            this.name = name;
            this.count = count;
        }
    }

    private Facets() {
    }

    /**
     * Genre, developer and publisher, from one walk of the store.
     *
     * {@link Filters.Field#FORMAT} comes back empty: a format is a property of
     * the filename, and plenty of games have one without the store knowing
     * anything about them. See {@link #formatsOf}.
     */
    public static Map<Filters.Field, List<Value>> of(Collection<Meta> games) {
        Map<String, Integer> genres = new LinkedHashMap<>();
        Map<String, Integer> developers = new LinkedHashMap<>();
        Map<String, Integer> publishers = new LinkedHashMap<>();

        for (Meta game : games) {
            if (game == null) continue;

            for (String genre : Filters.genresOf(game.genre)) add(genres, genre);
            add(developers, game.developer);
            add(publishers, game.publisher);
        }

        Map<Filters.Field, List<Value>> all = new EnumMap<>(Filters.Field.class);
        all.put(Filters.Field.FORMAT, Collections.emptyList());
        all.put(Filters.Field.GENRE, ranked(genres));
        all.put(Filters.Field.DEVELOPER, ranked(developers));
        all.put(Filters.Field.PUBLISHER, ranked(publishers));

        return all;
    }

    /** The formats present among these entries, commonest first. */
    public static List<Value> formatsOf(Collection<Entry> entries) {
        Map<String, Integer> formats = new LinkedHashMap<>();

        for (Entry entry : entries) {
            if (entry == null || entry.kind != Entry.Kind.FILE) continue;
            add(formats, Filters.formatOf(entry));
        }

        return ranked(formats);
    }

    private static void add(Map<String, Integer> counts, String value) {
        if (value == null) return;

        String trimmed = value.trim();
        if (trimmed.isEmpty()) return;

        Integer had = counts.get(trimmed);
        counts.put(trimmed, had == null ? 1 : had + 1);
    }

    /** Commonest first, and alphabetically within a tie so the order does not
     *  wander between one build of the list and the next. */
    private static List<Value> ranked(Map<String, Integer> counts) {
        List<Value> values = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            values.add(new Value(entry.getKey(), entry.getValue()));
        }

        Collections.sort(values, (left, right) -> {
            if (left.count != right.count) return right.count - left.count;
            return left.name.toLowerCase(Locale.ROOT)
                            .compareTo(right.name.toLowerCase(Locale.ROOT));
        });

        return values;
    }
}
```

- [ ] **Step 4: Give `Metadata` a way to hand over every game**

In `app/src/main/java/dev/ldlab/zedex/library/meta/Metadata.java`, add immediately after the existing `count(Context)` method:

```java
    /**
     * Every game the store knows, for {@code Facets} to count.
     *
     * A copy rather than the live map: the caller walks this on a background
     * thread, and the store can be replaced by a link while it does.
     */
    public static synchronized java.util.Collection<Meta> all(Context context) {
        return new java.util.ArrayList<>(store(context).games.values());
    }
```

- [ ] **Step 5: Run the tests**

Run: `env JAVA_HOME=/opt/android-studio/jbr ./gradlew --no-daemon testDebugUnitTest`
Expected: PASS, 23 tests in total.

Then: `env JAVA_HOME=/opt/android-studio/jbr ./gradlew --no-daemon assembleDebug`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/ldlab/zedex/library/Facets.java \
        app/src/test/java/dev/ldlab/zedex/library/FacetsTest.java \
        app/src/main/java/dev/ldlab/zedex/library/meta/Metadata.java
git commit -m "feat: the values a filter can offer, counted from the collection

Built from the store rather than from a fixed list, so nothing is offered that
would match nothing, and a collection of Spanish budget titles offers its own
publishers rather than somebody else's. Commonest first, since scrolling 277
developers wants Ocean near the top.

A compound genre counts for each genre it names - Racing, Driving is one game
under Racing and one under Driving - which is the same splitting Filters
matches by, in one place rather than two that could disagree.

Format is deliberately not taken from the store: it is a property of the
filename, and plenty of games have one that the store knows nothing about."
```

---

### Task 4: Apply it in the library, and flatten when filtered

**Files:**
- Modify: `app/src/main/java/dev/ldlab/zedex/screen/LibraryActivity.java`

**Interfaces:**
- Consumes: `Filters`, `Sorting`, `Metadata.forPath(Context,String)`, `Metadata.relativePath(Context,Uri)`, `Listing`.
- Produces: the field `filters`, the methods `filtering()`, `onFiltersChanged()`, and a listing that is flat while `filtering()` is true.

- [ ] **Step 1: Replace the sort constants with `Sorting`'s**

In `LibraryActivity`, delete these four lines:

```java
    private static final String SORT_NAME = "name";
    private static final String SORT_DATE = "date";
    private static final String SORT_SIZE = "size";
    private static final String[] SORT_FIELDS = { SORT_NAME, SORT_DATE, SORT_SIZE };
```

and every doc comment above them that names them. Replace all remaining uses:

- `SORT_NAME` → `Sorting.NAME`
- `SORT_SIZE` → `Sorting.SIZE`
- `SORT_FIELDS` → `Sorting.FIELDS`
- `SORT_DATE` → delete the branch that used it (in `showSortMenu`; Step 4 rewrites that method wholesale)

Change the field declaration:

```java
    private String sort = Sorting.NAME;
```

and where `sort` is read back from preferences in `onCreate`, wrap it:

```java
        sort = Sorting.fieldOrDefault(preferences.getString(KEY_SORT, Sorting.NAME));
```

Add the import `import dev.ldlab.zedex.library.Sorting;` beside the other `dev.ldlab.zedex.library` imports.

- [ ] **Step 2: Add the filter state**

Beside the `sort` and `sortDescending` fields, add:

```java
    /**
     * What the library is currently narrowed to. Session-only and deliberately
     * never written to preferences - see the design spec: a filter is a
     * question being asked now, not a preference, and a forgotten one is how a
     * library looks broken.
     */
    private final Filters filters = new Filters();

    /** Whether anything is narrowed, which is also whether the list is flat. */
    private boolean filtering() {
        return !filters.isEmpty() && tab == Tab.BROWSE;
    }
```

Add `import dev.ldlab.zedex.library.Filters;`.

- [ ] **Step 3: Sort and filter through the new classes**

Find `applyFilterSort()`. Replace the comparator it builds with `Sorting`'s, and apply `filters` to the rows. The listing loop that currently keeps an entry when the search box matches becomes:

```java
        for (Entry entry : source) {
            if (!needle.isEmpty()
                    && !entry.name.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }

            // Folders are not filtered - they are how you move, not what you
            // are looking for - and a filtered list is flat and has none.
            if (entry.kind != Entry.Kind.FOLDER && !filters.matches(entry, metaOf(entry))) {
                continue;
            }

            shown.add(entry);
        }

        Collections.sort(shown, Sorting.comparator(sort, sortDescending, this::metaOf));
```

and add the lookup beside it:

```java
    /**
     * The store's entry for a row, or null.
     *
     * Folders and archive members have no path in the store, so they never
     * have metadata; asking anyway would cost a URI round trip per row.
     */
    private Meta metaOf(Entry entry) {
        if (entry.kind == Entry.Kind.FOLDER || entry.inside != null) return null;

        String relativePath = Metadata.relativePath(this, entry.uri);
        return relativePath == null ? null : Metadata.forPath(this, relativePath);
    }
```

Keep whatever the method already does about folders sorting before files, and apply it only when `!filtering()`.

- [ ] **Step 4: Flatten the listing while filtering**

Where `load()` decides what to list, ask for the whole tree when filtering. Immediately before the existing call that lists the current level, add:

```java
            List<Entry> result = filtering()
                    ? Listing.everythingUnder(getContentResolver(), level.uri)
                    : Listing.of(getContentResolver(), level.uri, level.archive);
```

matching the names of whatever `load()` already calls. If `Listing` has no recursive walk, add one modelled on the one search already uses, capped at the same depth, and named `everythingUnder`.

- [ ] **Step 5: Re-list when the filter changes**

Add:

```java
    /** Called by both ways in - the toolbar sheet and the gamepad dialog -
     *  whenever the filter has changed, since the two share one Filters. */
    private void onFiltersChanged() {
        updateFilterChips();
        load();
    }
```

`updateFilterChips()` is written in Task 7; for now give it an empty body with a `// Task 7` comment so this task compiles and runs on its own.

- [ ] **Step 6: Build and run the suite**

```bash
env JAVA_HOME=/opt/android-studio/jbr ./gradlew --no-daemon assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell "run-as dev.ldlab.zedex.debug sed -i 's|name=\"secondScreen\" value=\"true\"|name=\"secondScreen\" value=\"false\"|' shared_prefs/fuse.xml"
adb shell am instrument -w dev.ldlab.zedex.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: the same number of tests as before this task, all passing. If `JoystickTest` and `NewDiskTest` fail on the first run after an install, run it a second time — that is a known flake, documented in CLAUDE.md.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/ldlab/zedex/screen/LibraryActivity.java \
        app/src/main/java/dev/ldlab/zedex/library/Listing.java
git commit -m "feat: the library sorts and filters through the new classes

LibraryActivity gains a Filters and a call to it, not the matching logic - the
file is already 2,600 lines and the review flagged it for exactly that. Sorting
replaces the comparator it built by hand, which also removes the file date sort.

A filter flattens the list to everything under the content folder, because in a
collection nested five deep 'shooters rated 4+' is not a question about the
folder you happen to be standing in. Folders themselves are never filtered:
they are how you move, not what you are looking for."
```

---

### Task 5: The filter sheet, and the toolbar button that opens it

**Files:**
- Modify: `app/src/main/java/dev/ldlab/zedex/library/ui/OptionsDialog.java`
- Modify: `app/src/main/java/dev/ldlab/zedex/screen/LibraryActivity.java`
- Modify: `app/src/main/res/values/strings.xml` and all eight `values-*/strings.xml`

**Interfaces:**
- Consumes: `Filters`, `Facets`, `Metadata.all(Context)`.
- Produces: `OptionsDialog.showFilters(Filters, Map<Filters.Field,List<Facets.Value>>, List<Facets.Value> formats)` and `OptionsDialog.Callbacks.onFiltersChanged()`.

- [ ] **Step 1: Add the strings, in nine files**

To `app/src/main/res/values/strings.xml`, beside the existing `library_sort` keys:

```xml
    <string name="library_filter">Filter</string>
    <string name="library_filter_none">No filter</string>
    <string name="library_filter_format">Format</string>
    <string name="library_filter_genre">Genre</string>
    <string name="library_filter_rating">Rating</string>
    <string name="library_filter_developer">Developer</string>
    <string name="library_filter_publisher">Publisher</string>
    <string name="library_filter_clear">Clear all</string>
    <string name="library_filter_any_rating">Any rating</string>
    <string name="library_sort_released">Released</string>
    <string name="library_sort_format">Format</string>
    <string name="library_sort_rating">Rating</string>
    <string name="library_view">View</string>
    <string name="library_empty_filtered">Nothing matches this filter</string>
```

Translate each into the eight other `values-*/strings.xml`. Suggested wording, which a native speaker should still check:

| key | cs | de | es | fr | it | pl | ru | uk |
|---|---|---|---|---|---|---|---|---|
| library_filter | Filtr | Filter | Filtro | Filtre | Filtro | Filtr | Фильтр | Фільтр |
| library_filter_none | Bez filtru | Kein Filter | Sin filtro | Aucun filtre | Nessun filtro | Bez filtra | Без фильтра | Без фільтра |
| library_filter_format | Formát | Format | Formato | Format | Formato | Format | Формат | Формат |
| library_filter_genre | Žánr | Genre | Género | Genre | Genere | Gatunek | Жанр | Жанр |
| library_filter_rating | Hodnocení | Bewertung | Valoración | Note | Voto | Ocena | Оценка | Оцінка |
| library_filter_developer | Vývojář | Entwickler | Desarrollador | Développeur | Sviluppatore | Twórca | Разработчик | Розробник |
| library_filter_publisher | Vydavatel | Herausgeber | Editor | Éditeur | Editore | Wydawca | Издатель | Видавець |
| library_filter_clear | Zrušit vše | Alle löschen | Borrar todo | Tout effacer | Cancella tutto | Wyczyść wszystko | Сбросить всё | Скинути все |
| library_filter_any_rating | Jakékoli hodnocení | Beliebige Bewertung | Cualquier valoración | Toute note | Qualsiasi voto | Dowolna ocena | Любая оценка | Будь-яка оцінка |
| library_sort_released | Vydáno | Erschienen | Publicado | Sortie | Uscita | Wydano | Год выпуска | Рік випуску |
| library_sort_format | Formát | Format | Formato | Format | Formato | Format | Формат | Формат |
| library_sort_rating | Hodnocení | Bewertung | Valoración | Note | Voto | Ocena | Оценка | Оцінка |
| library_view | Zobrazení | Ansicht | Vista | Affichage | Vista | Widok | Вид | Вигляд |
| library_empty_filtered | Filtru nic neodpovídá | Nichts passt zu diesem Filter | Nada coincide con este filtro | Rien ne correspond à ce filtre | Nessun risultato per questo filtro | Nic nie pasuje do filtra | Ничего не подходит под фильтр | Нічого не відповідає фільтру |

Delete `library_sort_date` from all nine files: nothing uses it once Task 4 has landed.

- [ ] **Step 2: Check the strings**

Run: `python3 scripts/check-strings.py`
Expected: `8 translations agree with values/` and `0 missing, 0 bad format` on every row.

- [ ] **Step 3: Add the filter pages to `OptionsDialog`**

Give `OptionsDialog` a notion of depth. Add to `Callbacks`:

```java
        /** The filter changed; the library re-lists. */
        void onFiltersChanged();
```

Add the entry point, which shows the five filter fields as rows and opens a value list one level deeper:

```java
    /**
     * The filter sheet: five fields, each opening the values the collection
     * actually has.
     *
     * The same row-building code the sort page uses, entered here rather than
     * at the top - one widget with two starting points, not a second list that
     * could drift from the first about, say, whether genres are comma-split.
     */
    public void showFilters(Filters filters,
                            Map<Filters.Field, List<Facets.Value>> values,
                            List<Facets.Value> formats) {
        this.filters = filters;
        this.values = values;
        this.formats = formats;

        page = Page.FILTER;
        rebuild();
    }
```

Rows for a field show the field name and what is picked (`Genre · Platform, Racing`), or nothing when it is not set. Selecting a row shows the values for it, each with its count (`Ocean  42`), a tick beside the picked ones, and `Clear all` at the top when any are. Rating shows `Any rating`, `3+`, `3.5+`, `4+`, `4.5+` as a single choice rather than a multi-select. Every change calls `callbacks.onFiltersChanged()` immediately — a page commits by its own name and there is no OK button, which is the rule CLAUDE.md records for the ☰ sheet.

Fields with more than 20 values get a search box above the list, filtering as you type, so 277 developers is navigable.

- [ ] **Step 4: Add the toolbar button**

In `buildToolbar()`, beside the existing sort button, add a filter button built by the same `toolbarButton(...)` helper the others use, with `R.drawable.ic_filter` and `getString(R.string.library_filter)` as its description. Create `app/src/main/res/drawable/ic_filter.xml` as a 24dp funnel matching the other toolbar icons in stroke weight and colour.

Its click handler:

```java
        filterButton.setOnClickListener(v -> new Thread(() -> {
            // Off the UI thread: this walks the whole store, which is 800
            // games on the collection it was built against.
            Map<Filters.Field, List<Facets.Value>> values =
                    Facets.of(Metadata.all(this));
            List<Facets.Value> formats = Facets.formatsOf(everythingForFacets());

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                optionsDialog.showFilters(filters, values, formats);
            });
        }).start());
```

Hide the button in Favourites and Recent — filtering is Browse only:

```java
        filterButton.setVisibility(tab == Tab.BROWSE ? View.VISIBLE : View.GONE);
```

in `show(Tab)`, beside where `upButton`'s visibility is already set.

- [ ] **Step 5: Build, install, and check by hand**

```bash
env JAVA_HOME=/opt/android-studio/jbr ./gradlew --no-daemon assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.ldlab.zedex.debug/dev.ldlab.zedex.screen.LibraryActivity
```

Check: the filter button appears in Browse and not in Favourites; tapping it lists Format, Genre, Rating, Developer, Publisher; picking a genre narrows the list and flattens it; picking a second genre widens it again (OR within a field); adding a format narrows it (AND across fields).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/ldlab/zedex/library/ui/OptionsDialog.java \
        app/src/main/java/dev/ldlab/zedex/screen/LibraryActivity.java \
        app/src/main/res/values*/strings.xml app/src/main/res/drawable/ic_filter.xml
git commit -m "feat: a filter button, and the sheet behind it

Five fields, each offering what the collection actually has with a count beside
it. Genres are split first, so Racing, Driving is offered as Racing and as
Driving rather than as itself.

In the toolbar because that is the touch path: OptionsDialog is what a gamepad
opens and touch never sees, so a filter reachable only from there would have
been reachable only with a controller. Hidden in Favourites and Recent, which
are already answers to a question.

The values are counted off the UI thread - it walks the whole store, 800 games
on the collection this was built against."
```

---

### Task 6: Three pages for the gamepad

**Files:**
- Modify: `app/src/main/java/dev/ldlab/zedex/library/ui/OptionsDialog.java`
- Modify: `app/src/main/java/dev/ldlab/zedex/screen/LibraryActivity.java` (the `options()` callback)

**Interfaces:**
- Consumes: everything from Task 5.
- Produces: `OptionsDialog.show(...)` opening the three-page menu rather than one flat column.

- [ ] **Step 1: Restructure `show()` into a menu of three**

`show(int sortIndex, boolean descending, boolean grid)` keeps its signature — `LibraryActivity` already calls it that way from `GamepadCursor.Nav.options()` — but now opens a menu of three rows rather than a flat column:

```
View   ▸   List            (or Grid)
Sort   ▸   Rating, highest first
Filter ▸   2 fields         (or the library_filter_none string)
```

Each opens its page; each page has a way back to the three. `Filter`'s page is the one Task 5 built, so it is reached from here and from the toolbar alike.

The direction row stays on the Sort page, where it already is.

- [ ] **Step 2: Keep the pad's own navigation working**

`OptionsDialog` already builds a `GamepadCursor` over its rows. Rebuild that cursor whenever the page changes, so it walks the rows currently shown, and make its `back()` go up a page rather than dismissing — except on the three-row menu, where it dismisses as it does now.

- [ ] **Step 3: Build, install and check with a pad**

```bash
env JAVA_HOME=/opt/android-studio/jbr ./gradlew --no-daemon assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

With a controller connected, open the library, press the button bound to options, and check: the three rows appear; each opens; B goes back a page and dismisses from the top; the row summaries say what is set.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/dev/ldlab/zedex/library/ui/OptionsDialog.java \
        app/src/main/java/dev/ldlab/zedex/screen/LibraryActivity.java
git commit -m "feat: View, Sort and Filter as pages in the gamepad dialog

One flat column of sort fields and List/Grid was already unlabelled; five filter
fields on top would have made a dozen rows with nothing saying which group a row
belongs to. Three pages now, each saying on its own row what it is currently set
to, so checking without changing is one press.

The shape the emulator's sheet already uses, and the rule CLAUDE.md records for
it: a row that was a dialog is a page, and it commits by its own name."
```

---

### Task 7: Say what is being hidden

**Files:**
- Modify: `app/src/main/java/dev/ldlab/zedex/screen/LibraryActivity.java`
- Create: `app/src/androidTest/java/dev/ldlab/zedex/FilterTest.java`

**Interfaces:**
- Consumes: `Filters`, the strings from Task 5.
- Produces: `updateFilterChips()` with a real body, and the empty state distinguishing "filtered" from "empty".

- [ ] **Step 1: Fill in the chips**

Replace the empty `updateFilterChips()` from Task 4. While any filter is on, the breadcrumb row shows what it is and a way to clear it — `Platform · 4+  ×` — and tapping the × calls `filters.clearAll()` then `onFiltersChanged()`. The chip row is `GONE` when nothing is set, and the breadcrumb comes back.

Give the row a `contentDescription` naming the active filter, and the × its own from `library_filter_clear`. Do not draw the × as a bare glyph without a description: a screen reader reads it as "multiplication sign".

- [ ] **Step 2: Tell the empty state apart from an empty folder**

Where `finishLoad` sets `emptyLabel`, add the filtered case before the existing two:

```java
            emptyLabel.setText(filtering() ? R.string.library_empty_filtered
                             : !query.isEmpty() ? R.string.library_empty_search
                             : R.string.library_empty);
```

A filter that has excluded everything otherwise looks exactly like a library that has lost its games — the failure shape this codebase's own review found twice.

- [ ] **Step 3: Write the device test**

Create `app/src/androidTest/java/dev/ldlab/zedex/FilterTest.java`. It covers the
half that can be driven without a controller: setting a filter from the toolbar
flattens the list and shows the chips, and clearing it puts the folder back.

Model it on `RecentsTest`, which is the closest existing example of driving the
library screen, and use `Emulator`'s own helpers rather than raw coordinates.
The assertions that must be present, each with a message naming what failed:

1. **A filter narrows and flattens.** Note how many rows Browse shows at the
   root. Open the filter sheet from the toolbar, choose the commonest genre,
   dismiss. Assert the row count changed, and that a game which lives in a
   subfolder is now visible at the top level — that is the flattening, and it is
   the part a folder-scoped implementation would fail.

2. **The chips say what is on.** Assert a view whose description names the
   chosen genre is on screen while the filter is set.

3. **An empty result says so.** Set a rating threshold of `4.5+` *and* a genre
   that has no highly-rated games, so nothing matches. Assert the empty label
   reads `library_empty_filtered` and not `library_empty` — a filter that has
   excluded everything must not look like a library that has lost its games.

4. **Clearing puts it back.** Tap the chips' clear button. Assert the row count
   returns to what it was in step 1 and the breadcrumb is showing again.

Use `assumeTrue` to skip the whole class when the store has no scraped games —
the emulator this runs on may have no metadata at all, and a test that cannot
find a genre to filter by should skip rather than fail. `Metadata.count` is how
to ask.

Do **not** attempt the gamepad half of the design's "one state behind both". It
needs a connected controller, which no emulator run has; the unit tests cover
the shared state and the reviewer should not expect a device test for it.

- [ ] **Step 4: Run everything**

```bash
python3 scripts/check-strings.py
python3 scripts/check-prefs.py
env JAVA_HOME=/opt/android-studio/jbr ./gradlew --no-daemon testDebugUnitTest
env JAVA_HOME=/opt/android-studio/jbr ./gradlew --no-daemon assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w dev.ldlab.zedex.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: both checkers pass, 23 unit tests pass, the instrumentation suite passes.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/ldlab/zedex/screen/LibraryActivity.java \
        app/src/androidTest/java/dev/ldlab/zedex/FilterTest.java
git commit -m "feat: say what the filter is hiding

An active filter shows as chips where the breadcrumb goes, with one tap to
clear, and the empty state says a filter is hiding things rather than 'nothing
here'. A filter that has excluded everything otherwise looks exactly like a
library that has lost its games - the failure shape this app's own review found
twice, both times a silent empty answer indistinguishable from a broken one.

The chips carry descriptions rather than a bare glyph: a screen reader reads x
as 'multiplication sign'.

And a device test that both ways in reach one state, since two entry points into
one piece of state is the pair that drifts."
```

---

## Where this plan is specification rather than code

Tasks 1 to 4 give the literal code, and their tests are runnable as written.

Tasks 5 to 7 give behaviour and acceptance criteria for the UI, not finished
methods, and that is deliberate rather than an oversight: `OptionsDialog` has to
gain a notion of depth, and what that costs depends on how its row building and
its `GamepadCursor` are currently wired - which the implementer should read
before writing, not have guessed at here. Inventing 200 lines of plausible
Android UI in a plan produces code that looks authoritative and does not
compile.

What those tasks do pin exactly: the strings and their translations, the
signatures crossing between tasks, which of the two entry points gets what, and
the checks that say it works. If any of the three turns out to need a different
shape once the file is read, that is a judgement to make there - the constraint
is the behaviour, not the arrangement.

## Notes for whoever executes this

- **Tasks 1-3 need no device.** They are pure Java with unit tests, and they run in about a second. Do not reach for the emulator until Task 4.
- **The first instrumentation run after an install** often fails `JoystickTest` and `NewDiskTest` at the ☰ menu, and passes on the second run. That is a known flake, not your change; confirm by running the class alone before investigating.
- **Turn `secondScreen` off** before any instrumentation run, or the panel borrows the controls onto another display and every tap lands on an empty screen.
- **`NewDiskTest` fails on a tablet-shaped emulator** whatever the change: it asks for a key called `BREAK SPACE`, which the 128K plate splits into two. Pre-existing; verified against unmodified `main`.
