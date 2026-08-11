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
        store.put(name, Meta.at("./" + name)
                .name(name).released(released).rating(rating)
                .source(Meta.ESDE)
                .build());
    }

    /**
     * As the app is shipped: scraped names shown.
     *
     * Safe for every test that uses it, because {@link #scrape} names a game
     * after its own file - so the shown name and the filename are the same
     * string and the ordering is whatever it was. The tests that care about
     * the two differing say so, by taking the flag explicitly.
     */
    private List<String> sorted(String field, boolean descending, Entry... entries) {
        return sorted(field, descending, true, entries);
    }

    @Test
    public void byNameIsCaseInsensitive() {
        assertEquals(Arrays.asList("apple", "Banana", "cherry"),
                sorted(Sorting.NAME, false, file("Banana", 1), file("cherry", 1), file("apple", 1)));
    }

    /** A scraped title under a different letter from the file it came from. */
    private void titled(String filename, String title) {
        store.put(filename, Meta.at("./" + filename).name(title).source("ZXInfo").build());
    }

    /**
     * A game sorts under the name the row shows, not the one on disk.
     *
     * The whole of the bug this covers: a Russian release lands as
     * zvezdnoenasledie.trd, a scrape identifies it as "Star Inheritance", the
     * row draws that title - and the list went on filing it under Z, where
     * nobody looking for it would ever go. Neither re-listing nor restarting
     * helped, because nothing was stale: the list simply ordered by one string
     * and drew another.
     */
    @Test
    public void byNameUsesTheScrapedTitleWhereThereIsOne() {
        titled("zvezdnoenasledie.trd", "Star Inheritance");

        assertEquals(Arrays.asList("aaa.tap", "zvezdnoenasledie.trd", "Tornado.tap"),
                sorted(Sorting.NAME, false, showingScrapedNames(),
                       file("Tornado.tap", 1), file("zvezdnoenasledie.trd", 1),
                       file("aaa.tap", 1)));
    }

    /**
     * And under the filename when scraped names are turned off.
     *
     * Sorting by a name the row is not showing would be worse than the bug:
     * the letters would be right for a title nobody can see.
     */
    @Test
    public void byNameUsesTheFilenameWhenScrapedNamesAreNotShown() {
        titled("zvezdnoenasledie.trd", "Star Inheritance");

        assertEquals(Arrays.asList("aaa.tap", "Tornado.tap", "zvezdnoenasledie.trd"),
                sorted(Sorting.NAME, false, showingFilenames(),
                       file("Tornado.tap", 1), file("zvezdnoenasledie.trd", 1),
                       file("aaa.tap", 1)));
    }

    /** An unscraped row beside a scraped one still sorts by what it shows,
     *  which for it is the filename. */
    @Test
    public void ascrapedAndAnUnscrapedRowAreOrderedByWhatEachShows() {
        titled("zvezdnoenasledie.trd", "Star Inheritance");

        assertEquals(Arrays.asList("Rick Dangerous.tap", "zvezdnoenasledie.trd"),
                sorted(Sorting.NAME, false, showingScrapedNames(),
                       file("zvezdnoenasledie.trd", 1), file("Rick Dangerous.tap", 1)));
    }

    /** An empty scraped name is not a name - it must not sort everything
     *  scraped-but-unnamed to the top. */
    @Test
    public void anEmptyScrapedNameFallsBackToTheFilename() {
        store.put("beta.tap", Meta.at("./beta.tap").name("").source("ZXInfo").build());

        assertEquals(Arrays.asList("alpha.tap", "beta.tap", "gamma.tap"),
                sorted(Sorting.NAME, false, showingScrapedNames(),
                       file("gamma.tap", 1), file("beta.tap", 1), file("alpha.tap", 1)));
    }

    /** The scraped title is the tie-break too, not just the field. */
    @Test
    public void thescrapedTitleBreaksTiesOnAnotherField() {
        titled("zvezdnoenasledie.trd", "Star Inheritance");
        titled("bbb.tap", "Zulu Warrior");

        assertEquals(Arrays.asList("zvezdnoenasledie.trd", "bbb.tap"),
                sorted(Sorting.SIZE, false, showingScrapedNames(),
                       file("bbb.tap", 100), file("zvezdnoenasledie.trd", 100)));
    }

    private static boolean showingScrapedNames() {
        return true;
    }

    private static boolean showingFilenames() {
        return false;
    }

    private List<String> sorted(String field, boolean descending, boolean scrapedNames,
                                Entry... entries) {
        List<Entry> list = new ArrayList<>(Arrays.asList(entries));
        Collections.sort(list, Sorting.comparator(field, descending, scrapedNames,
                                                  e -> store.get(e.name)));

        List<String> names = new ArrayList<>();
        for (Entry entry : list) names.add(entry.name);
        return names;
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

    @Test
    public void unknownSizeSortsLastToo() {
        assertEquals(Arrays.asList("sized", "unsized"),
                sorted(Sorting.SIZE, false, file("unsized", -1), file("sized", 100)));
        assertEquals(Arrays.asList("sized", "unsized"),
                sorted(Sorting.SIZE, true, file("unsized", -1), file("sized", 100)));
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
