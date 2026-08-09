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
