package dev.ldlab.zedex.library.catalogue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The seam's own value types, and the counting.
 *
 * All of it on the JVM because none of it is Android and because the piece
 * that goes wrong is arithmetic nobody looks at: a page count off by one is
 * either a grid that stops one page early - which reads as a catalogue that
 * has fewer games than it has - or one that asks for ever, which reads as
 * nothing at all and costs a request every time the list is flung.
 */
public class CatalogueTest {

    private static Catalogue.Item anItem(String id) {
        return new Catalogue.Item(id, "Head over Heels", "1987", "Ocean Software Ltd",
                                  "Arcade Game", "Available", null,
                                  Collections.<Catalogue.Version>emptyList());
    }

    // --- paging ----------------------------------------------------------------------

    /** A full page against a known total has more behind it. */
    @Test
    public void afullPageOfAknownTotalHasMore() {
        Catalogue.Page page = new Catalogue.Page(
                Arrays.asList(anItem("1"), anItem("2")),
                Collections.<Catalogue.Shelf>emptyList(), 2, 10);

        assertTrue(page.hasMore());
        assertEquals(10, page.total());
    }

    /**
     * The last page does not.
     *
     * Counted from what has been seen rather than from the page number, since
     * a shelf may hand back a short page in the middle - which is the whole
     * reason this is not `items.size() == pageSize`.
     */
    @Test
    public void thelastPageOfAknownTotalDoesNot() {
        Catalogue.Page page = new Catalogue.Page(
                Arrays.asList(anItem("9"), anItem("10")),
                Collections.<Catalogue.Shelf>emptyList(), 8, 10);

        assertFalse(page.hasMore());
    }

    /**
     * An unknown total is judged by whether anything came at all.
     *
     * Some shelves cannot say how many there are - a random one has no total
     * and never will - so "did this page bring anything" is the only question
     * left. An empty page ends the list; a non-empty one is worth asking
     * again after.
     */
    @Test
    public void anunknownTotalIsJudgedByWhetherAnythingCame() {
        Catalogue.Page some = new Catalogue.Page(
                Arrays.asList(anItem("1")), Collections.<Catalogue.Shelf>emptyList(),
                0, Catalogue.Page.UNKNOWN_TOTAL);
        Catalogue.Page none = new Catalogue.Page(
                Collections.<Catalogue.Item>emptyList(),
                Collections.<Catalogue.Shelf>emptyList(),
                0, Catalogue.Page.UNKNOWN_TOTAL);

        assertTrue(some.hasMore());
        assertFalse(none.hasMore());
    }

    /** A page of nothing at all is the end, whatever the total claims - a
     *  total that disagrees with an empty page is a service being wrong, and
     *  believing it asks for ever. */
    @Test
    public void anemptyPageEndsTheListEvenAgainstAtotal() {
        Catalogue.Page page = new Catalogue.Page(
                Collections.<Catalogue.Item>emptyList(),
                Collections.<Catalogue.Shelf>emptyList(), 0, 500);

        assertFalse(page.hasMore());
    }

    /**
     * A page of categories and no items is the end of the paging, and still
     * carries its shelves.
     *
     * <b>Not "sub-shelves count as arrival"</b>, which is what this was called
     * and is the opposite of what it proves: {@link Catalogue.Page#hasMore}
     * reads {@code items.isEmpty()} and nothing else, so a page of nothing but
     * shelves ends the list exactly as an empty one does - which is right, and
     * is why Categories and A-Z are one page each. What the shelves being there
     * has to survive is the caller then <em>not</em> asking for another page.
     */
    @Test
    public void apageOfShelvesEndsThePagingAndKeepsItsShelves() {
        Catalogue.Page page = new Catalogue.Page(
                Collections.<Catalogue.Item>emptyList(),
                Arrays.asList(new Catalogue.Shelf("92177", "Games",
                                                  Catalogue.Shelf.Accepts.NOTHING)),
                0, Catalogue.Page.UNKNOWN_TOTAL);

        assertFalse("a page of shelves has nothing more to page through",
                    page.hasMore());
        assertEquals(1, page.shelves().size());
    }

    // --- guarding against a list mutated after handing it over -----------------------

    /**
     * A page must not see a change to the list the caller made it from.
     *
     * {@code Page} stores the caller's own reference and only wraps it in an
     * unmodifiable view at accessor time - a view, not a copy, which stops the
     * accessor's caller mutating the list but does nothing about the
     * constructor's caller, who still holds the original reference. Task 5's
     * ZXInfo parser builds a {@code Page} per page of results from an
     * {@code ArrayList}; if it ever reused or cleared that list between pages,
     * a page already handed out would quietly change under its owner - games
     * would appear to show another page's items, which reads as a service bug
     * rather than a mutability bug here.
     */
    @Test
    public void apageIsNotChangedByEditingTheListAfterHandingItOver() {
        List<Catalogue.Item> mutable = new ArrayList<Catalogue.Item>();
        mutable.add(anItem("1"));

        Catalogue.Page page = new Catalogue.Page(
                mutable, Collections.<Catalogue.Shelf>emptyList(), 0, 2);

        mutable.add(anItem("2"));
        mutable.clear();

        assertEquals(1, page.items().size());
        assertEquals("1", page.items().get(0).id());
    }

    // --- what a shelf accepts --------------------------------------------------------

    /** A shelf that takes nothing ignores whatever it is handed, rather than
     *  refusing it - the tab hands the same Query to every shelf. */
    @Test
    public void ashelfThatAcceptsNothingIgnoresTheQuery() {
        Catalogue.Shelf shelf = new Catalogue.Shelf("newest", "Newest",
                                                    Catalogue.Shelf.Accepts.NOTHING);

        assertFalse(shelf.accepts(Catalogue.Shelf.Accepts.TEXT));
        assertTrue(shelf.accepts(Catalogue.Shelf.Accepts.NOTHING));
    }

    @Test
    public void ashelfSaysWhatItTakes() {
        Catalogue.Shelf search = new Catalogue.Shelf("search", "Search",
                                                     Catalogue.Shelf.Accepts.TEXT);
        Catalogue.Shelf letters = new Catalogue.Shelf("az", "A-Z",
                                                      Catalogue.Shelf.Accepts.LETTER);

        assertTrue(search.accepts(Catalogue.Shelf.Accepts.TEXT));
        assertTrue(letters.accepts(Catalogue.Shelf.Accepts.LETTER));
        assertFalse(letters.accepts(Catalogue.Shelf.Accepts.TEXT));
    }

    // --- availability ----------------------------------------------------------------

    /**
     * Available is the only word that means available.
     *
     * Everything else - "Never released", "MIA", "Distribution denied" - is a
     * fact about the game and stays on the list, greyed. Guessing the other
     * way round would hide whichever states a future vocabulary adds, silently,
     * which is the failure this app has already had once with machine types.
     */
    @Test
    public void anythingButAvailableIsNot() {
        assertTrue(anItemAvailable("Available").available());
        assertFalse(anItemAvailable("Never released").available());
        assertFalse(anItemAvailable("MIA").available());
        assertFalse(anItemAvailable(null).available());

        assertTrue("case is the service's business",
                   anItemAvailable("available").available());
    }

    private static Catalogue.Item anItemAvailable(String availability) {
        return new Catalogue.Item("1", "A game", null, null, null, availability, null,
                                  Collections.<Catalogue.Version>emptyList());
    }

    // --- a download ------------------------------------------------------------------

    /** Absolute, always. ZXDB's own recordings live on archive.org, so a
     *  path joined onto one base host would fetch every one of them from the
     *  wrong place - which is a 404 and looks exactly like a game that has
     *  none. */
    @Test
    public void adownloadCarriesAwholeUrl() {
        Catalogue.Download file = new Catalogue.Download(
                "https://archive.org/download/x/HeadOverHeels.rzx.zip", "rzx", 41232);

        assertEquals("rzx", file.format());
        assertEquals(41232, file.size());
        assertTrue(file.url().startsWith("https://"));
    }
}
