package dev.ldlab.zedex.library;

import dev.ldlab.zedex.library.meta.Meta;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the library shows, asked without a device.
 *
 * This was the first half of {@code LibraryActivity.applyFilterSort} until it
 * became {@link Shortlist}, and until then the only way to find out what it
 * did was to run the emulator and read the screen - which is why {@code
 * FilterTest} exists and takes fifty seconds. These are the same questions,
 * in milliseconds, and they can ask things a screen cannot easily be made to
 * show: that the metadata lookup is asked once per row and not once per
 * comparison, that the search matches the name and nothing else, that folders
 * hold their place.
 *
 * Entries here carry a null {@code uri}, which is what {@link FacetsTest}
 * already does: nothing under test reaches for one - the lookup is supplied
 * by the caller, and the cache is keyed by the row object rather than by
 * {@link Entry#key()} for exactly that kind of reason.
 */
public class ShortlistTest {

    private static Entry file(String name, long size) {
        return new Entry(Entry.Kind.FILE, name, null, null, size, 0);
    }

    private static Entry folder(String name) {
        return new Entry(Entry.Kind.FOLDER, name, null, null, 0, 0);
    }

    private static Meta meta(String genre, String developer, String year) {
        return new Meta("./g.tap", null, null, developer, null, genre,
                        year, null, null, "esde");
    }

    /** No metadata for anything - the commonest case in a real collection,
     *  and the one every unfiltered sort takes. */
    private static final Sorting.Lookup NOTHING_SCRAPED = entry -> null;

    private static List<String> names(List<Entry> entries) {
        List<String> names = new ArrayList<>();
        for (Entry entry : entries) names.add(entry.name);
        return names;
    }

    /** Unfiltered, unsearched, sorted by name: what the screen opens on. */
    private static List<Entry> plain(List<Entry> loaded) {
        return Shortlist.of(loaded, "", new Filters(), false,
                            Sorting.NAME, false, NOTHING_SCRAPED);
    }

    // --- the search box --------------------------------------------------------

    @Test
    public void theSearchMatchesAnywhereInTheNameAndIgnoresCase() {
        List<Entry> loaded = Arrays.asList(
                file("Manic Miner.tap", 1), file("Jet Set Willy.tap", 1),
                file("MINER 2049er.tap", 1));

        // Sorted case-insensitively too - see Sorting.comparator - so the
        // capitalised one does not lead simply for being capitalised.
        assertEquals(Arrays.asList("Manic Miner.tap", "MINER 2049er.tap"),
                     names(Shortlist.of(loaded, "miner", new Filters(), false,
                                        Sorting.NAME, false, NOTHING_SCRAPED)));
    }

    /** An empty box narrows nothing - it is not a search for the empty
     *  string, which every name contains. */
    @Test
    public void anEmptySearchShowsEverything() {
        List<Entry> loaded = Arrays.asList(file("a.tap", 1), file("b.tap", 1));

        assertEquals(2, plain(loaded).size());
    }

    /** The search reads the name, never the folder a row came from or
     *  anything scraped about it. */
    @Test
    public void theSearchDoesNotReadMetadata() {
        List<Entry> loaded = Arrays.asList(file("xyz.tap", 1));
        Sorting.Lookup scraped = entry -> meta("Platform", "Ocean", "1984");

        assertEquals(0, Shortlist.of(loaded, "Ocean", new Filters(), false,
                                     Sorting.NAME, false, scraped).size());
    }

    // --- folders ---------------------------------------------------------------

    /** Folders first and alphabetical, whatever the sort says - they are how
     *  Browse is walked through, not a game to weigh by size. */
    @Test
    public void foldersLeadAndKeepTheirOwnOrder() {
        List<Entry> loaded = Arrays.asList(
                file("big.tap", 900), folder("Zeta"), file("small.tap", 1), folder("alpha"));

        assertEquals(Arrays.asList("alpha", "Zeta", "big.tap", "small.tap"),
                     names(Shortlist.of(loaded, "", new Filters(), false,
                                        Sorting.SIZE, true, NOTHING_SCRAPED)));
    }

    /** A flattened list has no folders to hold apart, so nothing is held
     *  apart - the whole list sorts as one. */
    @Test
    public void aFlatListDoesNotLeadWithFolders() {
        Filters filters = new Filters();
        filters.toggle(Filters.Field.FORMAT, "tap");

        List<Entry> loaded = Arrays.asList(
                folder("Zeta"), file("a.tap", 1), folder("alpha"));

        // Folders are never filtered out either way - they are how you move,
        // not what you are looking for - so all three survive, and sort
        // together.
        assertEquals(Arrays.asList("a.tap", "alpha", "Zeta"),
                     names(Shortlist.of(loaded, "", filters, true,
                                        Sorting.NAME, false, NOTHING_SCRAPED)));
    }

    // --- the filter ------------------------------------------------------------

    @Test
    public void aFilterNarrowsToWhatMatchesIt() {
        Filters filters = new Filters();
        filters.toggle(Filters.Field.GENRE, "Platform");

        List<Entry> loaded = Arrays.asList(
                file("platformer.tap", 1), file("racer.tap", 1), file("unscraped.tap", 1));

        Map<String, Meta> store = new HashMap<>();
        store.put("platformer.tap", meta("Platform", null, null));
        store.put("racer.tap", meta("Racing", null, null));

        assertEquals(Arrays.asList("platformer.tap"),
                     names(Shortlist.of(loaded, "", filters, true,
                                        Sorting.NAME, false, e -> store.get(e.name))));
    }

    /**
     * Not flat, and the filter is not consulted at all - which is how a
     * filter set in Browse and left in place stops narrowing Favourites and
     * Recent. See the design spec, "Filtering applies to Browse only".
     */
    @Test
    public void aFilterIsIgnoredWhereItDoesNotApply() {
        Filters filters = new Filters();
        filters.toggle(Filters.Field.GENRE, "Platform");

        List<Entry> loaded = Arrays.asList(file("racer.tap", 1));

        assertEquals(1, Shortlist.of(loaded, "", filters, false,
                                     Sorting.NAME, false,
                                     e -> meta("Racing", null, null)).size());
    }

    /** Both narrowings apply at once, and neither excuses the other. */
    @Test
    public void aSearchAndAFilterCompound() {
        Filters filters = new Filters();
        filters.toggle(Filters.Field.FORMAT, "tap");

        List<Entry> loaded = Arrays.asList(
                file("manic.tap", 1), file("manic.tzx", 1), file("jet.tap", 1));

        assertEquals(Arrays.asList("manic.tap"),
                     names(Shortlist.of(loaded, "manic", filters, true,
                                        Sorting.NAME, false, NOTHING_SCRAPED)));
    }

    // --- what it costs ---------------------------------------------------------

    /**
     * The metadata lookup is asked once per row for the whole call, however
     * many comparisons the sort makes.
     *
     * The reason {@link Shortlist} holds a cache rather than handing the
     * caller's lookup straight to the comparator. {@code Sorting.comparator}
     * asks per comparison and {@code Collections.sort} calls it O(n log n)
     * times; the filter pass asks once more per row on top. Each ask is a
     * content-folder read and a store lookup on a device, and this test is
     * the only place that can prove it happens once - a screen cannot show
     * the difference between a fast list and a slow one.
     */
    @Test
    public void theLookupIsAskedOncePerRow() {
        Filters filters = new Filters();
        filters.toggle(Filters.Field.GENRE, "Platform");

        List<Entry> loaded = new ArrayList<>();
        for (int i = 0; i < 40; i++) loaded.add(file("game" + i + ".tap", i));

        Map<Entry, Integer> asked = new HashMap<>();

        // RELEASED, so the comparator genuinely wants a Meta for every
        // comparison - NAME and SIZE never ask at all, and would prove
        // nothing about the cache.
        List<Entry> shown = Shortlist.of(loaded, "", filters, true,
                                         Sorting.RELEASED, false, entry -> {
            asked.merge(entry, 1, Integer::sum);
            return meta("Platform", null, "198" + (entry.size % 10));
        });

        assertEquals(40, shown.size());
        assertEquals(40, asked.size());

        int most = 0;
        for (int count : asked.values()) most = Math.max(most, count);
        assertEquals("the lookup was asked more than once for some row", 1, most);
    }
}
