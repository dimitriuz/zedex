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
