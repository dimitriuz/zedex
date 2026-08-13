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
        return Meta.at("./g.tap")
                .name("G").developer(developer).publisher(publisher).genre(genre)
                .rating(rating)
                .source(Meta.ESDE)
                .build();
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

    // --- status ----------------------------------------------------------------------

    private static Meta row(String playCount, String completed) {
        return Meta.at("./A.tap").playCount(playCount).completed(completed).build();
    }

    /** Finished, and only finished. */
    @Test
    public void completedTakesTheFinishedOnes() {
        Filters filters = new Filters();
        filters.toggle(Filters.Field.STATUS, Filters.COMPLETED);

        assertTrue(filters.matches(file("A.tap"), row("3", "true")));
        assertFalse(filters.matches(file("A.tap"), row("3", "false")));
        assertFalse("nobody has said, which is not the same as finished",
                    filters.matches(file("A.tap"), row("3", null)));
    }

    /**
     * Not finished takes both of the other two.
     *
     * "Said no" and "nobody has said" are stored apart - see Meta.completed -
     * and this is the question that reads them the same way, which is what
     * somebody looking for what is left to play means.
     */
    @Test
    public void notCompletedTakesTheRestIncludingTheUnsaid() {
        Filters filters = new Filters();
        filters.toggle(Filters.Field.STATUS, Filters.NOT_COMPLETED);

        assertTrue(filters.matches(file("A.tap"), row("0", "false")));
        assertTrue(filters.matches(file("A.tap"), row("0", null)));
        assertFalse(filters.matches(file("A.tap"), row("9", "true")));
    }

    /** Opened before is a count above zero, and nothing to do with finishing
     *  it. */
    @Test
    public void openedBeforeIsAcountAboveZero() {
        Filters filters = new Filters();
        filters.toggle(Filters.Field.STATUS, Filters.PLAYED);

        assertTrue(filters.matches(file("A.tap"), row("1", null)));
        assertFalse(filters.matches(file("A.tap"), row("0", null)));
        assertFalse(filters.matches(file("A.tap"), row(null, "true")));
    }

    /** Two of the three is either of them, like every other field here. */
    @Test
    public void twoStatusesAreOrEdTogether() {
        Filters filters = new Filters();
        filters.toggle(Filters.Field.STATUS, Filters.COMPLETED);
        filters.toggle(Filters.Field.STATUS, Filters.PLAYED);

        assertTrue("finished but never opened in this app", 
                   filters.matches(file("A.tap"), row(null, "true")));
        assertTrue("opened but not finished",
                   filters.matches(file("A.tap"), row("2", null)));
        assertFalse("neither", filters.matches(file("A.tap"), row("0", "false")));
    }

    /**
     * A game the store knows nothing about answers no to all three - except
     * "not completed", which it plainly is.
     *
     * The same reading every other metadata filter takes of an absent row, and
     * the reason it is spelled out: a game with no genre is not a game of
     * every genre, and a game nobody has recorded anything about has not been
     * finished either.
     */
    @Test
    public void anunknownRowIsUnfinishedAndUnplayed() {
        Filters completed = new Filters();
        completed.toggle(Filters.Field.STATUS, Filters.COMPLETED);
        assertFalse(completed.matches(file("A.tap"), null));

        Filters played = new Filters();
        played.toggle(Filters.Field.STATUS, Filters.PLAYED);
        assertFalse(played.matches(file("A.tap"), null));

        Filters unfinished = new Filters();
        unfinished.toggle(Filters.Field.STATUS, Filters.NOT_COMPLETED);
        assertTrue(unfinished.matches(file("A.tap"), null));
    }

    /** And a count that is not a number is no count at all, rather than a
     *  crash - the store is a file somebody can edit. */
    @Test
    public void anunreadableCountIsZero() {
        assertEquals(0, row("many", null).plays());
        assertEquals(0, row("-3", null).plays());
        assertEquals(4, row(" 4 ", null).plays());
    }
}
