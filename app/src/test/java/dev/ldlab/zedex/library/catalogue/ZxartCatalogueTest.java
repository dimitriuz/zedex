package dev.ldlab.zedex.library.catalogue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.ldlab.zedex.library.scrape.Pace;

import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Locale;

/**
 * zxart as somewhere to browse.
 *
 * On the JVM against captured replies, which is where a catalogue's decisions
 * live: which shelves exist, what a page asks for, what a row means, and what
 * gets dropped. Nothing here reaches the network - {@code Fixtures.Canned}
 * answers - and nothing here is Android.
 *
 * <b>Three corrections to the task brief's own test code, all made before this
 * ran once:</b>
 * <ol>
 *   <li>{@code aSearchAsksForWhatWasTyped} and {@code theTreeIsAskedForOnce}
 *       use {@code catalogue.shelves().get(0)} for the search shelf rather
 *       than the {@code shelf(String)} helper below, which always answers
 *       {@code Accepts.NOTHING} - the brief says this in prose after the
 *       code and it is applied here.</li>
 *   <li>{@code categoriesYieldShelvesAndThenBoth} queues a second reply,
 *       {@code Fixtures.PROD_LICENCE_TO_KILL}, for opening the Games root.
 *       The brief's own listing queued only the tree - one reply for two
 *       real requests (the tree, then the Games category's own filtered
 *       search) - which cannot pass under any implementation that does not
 *       fabricate rows: {@code Fixtures.Canned} answers a spent queue with
 *       {@code {"responseStatus" absent}}, which {@code ZxartApi.ask} reads
 *       as MALFORMED, and even if it did not, the category tree's own body
 *       carries no {@code zxProd} rows to reuse. Licence to Kill is under
 *       Games (see {@code aRowsKindIsItsRootCategory}), so it is what a real
 *       {@code filter:zxProdCategory=92177} request would plausibly answer
 *       with among its 23,162.</li>
 * </ol>
 */
public class ZxartCatalogueTest {

    @Before
    public void forgetThePacing() {
        Pace.forget();
    }

    private static ZxartCatalogue catalogue(Fixtures.Canned http) {
        return new ZxartCatalogue(http, Locale.ENGLISH);
    }

    /**
     * Three shelves, and <b>no A-Z</b>.
     *
     * Every title-prefix filter zxart might have had is ignored -
     * zxProdTitleStart returned all 58,032 - so a letter picker cannot be
     * built, and a shelf that cannot be built is not declared. The want is
     * served by the alphabetical sort instead. This is the seam's whole
     * argument in one assertion.
     */
    @Test
    public void theShelvesAreTheOnesThatCanBeBuilt() {
        List<Catalogue.Shelf> shelves = catalogue(new Fixtures.Canned()).shelves();

        assertEquals(3, shelves.size());
        assertEquals(ZxartCatalogue.SHELF_SEARCH, shelves.get(0).id());
        assertTrue(shelves.get(0).accepts(Catalogue.Shelf.Accepts.TEXT));
        assertEquals(ZxartCatalogue.SHELF_CATEGORIES, shelves.get(1).id());
        assertEquals(ZxartCatalogue.SHELF_EVERYTHING, shelves.get(2).id());

        for (Catalogue.Shelf shelf : shelves) {
            assertFalse("no shelf takes a letter, because no filter accepts one",
                        shelf.accepts(Catalogue.Shelf.Accepts.LETTER));
        }
    }

    /** shelves() makes no request: the tab calls it on the UI thread while it
     *  is being built. */
    @Test
    public void decliningToAskAnythingToDeclareShelves() {
        Fixtures.Canned http = new Fixtures.Canned();
        catalogue(http).shelves();

        assertTrue(http.asked.isEmpty());
    }

    /** A search is one filter and one page, and start is a row offset. */
    @Test
    public void aSearchAsksForWhatWasTyped() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.CATEGORY_TREE)
                                                    .then(Fixtures.PROD_SEARCH);
        ZxartCatalogue zxart = catalogue(http);

        zxart.open(zxart.shelves().get(0), Catalogue.Query.text("head over heels"), 0);

        assertTrue(lastAsked(http).contains("export:zxProd"));
        assertTrue(lastAsked(http).contains("filter:zxProdSearch=head%20over%20heels"));
        assertTrue(lastAsked(http).contains("start:0"));
    }

    /**
     * Opening Categories yields shelves and no items; opening one of those
     * yields <b>both</b>.
     *
     * The first thing in this codebase to fill items and shelves in one page,
     * which is what the seam always claimed sub-shelves were for.
     */
    @Test
    public void categoriesYieldShelvesAndThenBoth() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.CATEGORY_TREE)
                                                    .then(Fixtures.PROD_LICENCE_TO_KILL);
        ZxartCatalogue zxart = catalogue(http);

        Catalogue.Page roots = zxart.open(shelf(ZxartCatalogue.SHELF_CATEGORIES),
                                          Catalogue.Query.none(), 0);

        assertEquals(9, roots.shelves().size());
        assertTrue(roots.items().isEmpty());

        Catalogue.Page games = zxart.open(roots.shelves().get(0), Catalogue.Query.none(), 0);

        assertFalse("a root category has children to descend into",
                    games.shelves().isEmpty());
        assertFalse("and prods of its own, because roots roll up - Games is 23,162",
                    games.items().isEmpty());
    }

    /** The tree is fetched once and held: two shelves opened, one tree
     *  request. Thirteen kilobytes per session, not per shelf. */
    @Test
    public void theTreeIsAskedForOnce() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.CATEGORY_TREE)
                                                    .then(Fixtures.PROD_SEARCH);
        ZxartCatalogue zxart = catalogue(http);

        zxart.open(shelf(ZxartCatalogue.SHELF_CATEGORIES), Catalogue.Query.none(), 0);
        zxart.open(zxart.shelves().get(0), Catalogue.Query.text("head"), 0);

        int trees = 0;
        for (String url : http.asked) {
            if (url.contains("export:zxProdCategory")) trees++;
        }

        assertEquals(1, trees);
    }

    /** A row's kind is the English root word for the leaf it names, which is
     *  what decides its folder later. Licence to Kill is under Games. */
    @Test
    public void aRowsKindIsItsRootCategory() throws Exception {
        Catalogue.Item item = onlyItem();

        assertEquals("Games", item.kind());
        assertEquals(Kinds.GAMES, Kinds.folderFor(item.kind()));
    }

    /**
     * <b>An unknown legalStatus says nothing.</b>
     *
     * Measured over 1,000 prods: 975 "unknown", 21 "forbidden", 3
     * "unreleased", 1 "recovered", and the word "available" never once. Passed
     * through raw, CatalogueAdapter.greyed - stated and not available - would
     * grey every row in the catalogue and give no reason for any of them.
     */
    @Test
    public void unknownAvailabilityIsNotAStatement() throws Exception {
        assertNull(onlyItem().availability());
    }

    /** A stated one is kept verbatim, because it is the row's own reason. */
    @Test
    public void aStatedUnavailabilityIsKept() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.CATEGORY_TREE)
                                                    .then(Fixtures.PROD_FORBIDDEN);
        Catalogue.Page page = catalogue(http).open(shelf(ZxartCatalogue.SHELF_EVERYTHING),
                                                  Catalogue.Query.none(), 0);

        assertEquals("forbidden", page.items().get(0).availability());
        assertFalse(page.items().get(0).available());
    }

    /** Titles arrive escaped and must not reach a row that way. */
    @Test
    public void titlesAreUnescaped() throws Exception {
        assertFalse(onlyItem().title().contains("&"));
    }

    /**
     * A prod row carries no files at all - only releasesIds - so the formats
     * are one request away per row and Item.formats() is honestly empty. Task
     * 7's knowsFormats is what stops the screen's filter rejecting the whole
     * catalogue on the strength of it.
     */
    @Test
    public void aListRowKnowsNoFormats() throws Exception {
        assertTrue(onlyItem().formats().isEmpty());
    }

    /** item() is two requests - the prod, then every release of it in one
     *  call - because types:zxProd,zxRelease answers HTTP 500. */
    @Test
    public void oneItemIsTwoRequests() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.CATEGORY_TREE)
                                                    .then(Fixtures.PROD_LICENCE_TO_KILL)
                                                    .then(Fixtures.RELEASES_LICENCE_TO_KILL);

        Catalogue.Item item = catalogue(http).item("92668");

        assertNotNull(item);
        assertFalse(item.versions().isEmpty());

        Catalogue.Download file = item.versions().get(0).files().get(0);
        assertEquals("tzx", file.format());
        assertTrue(file.url().startsWith("https://zxart.ee/releasefile/"));
        assertEquals(41330, file.size());
    }

    /** A version's label tells two releases apart by what they need, which is
     *  what hardwareRequired is for on this screen. */
    @Test
    public void aVersionSaysWhatItNeeds() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.CATEGORY_TREE)
                                                    .then(Fixtures.PROD_LICENCE_TO_KILL)
                                                    .then(Fixtures.RELEASES_LICENCE_TO_KILL);

        assertTrue(catalogue(http).item("92668").versions().get(0).label()
                   .toLowerCase(Locale.ROOT).contains("zx128"));
    }

    /** similarTo is a way in and costs nothing until opened - the pane calls
     *  it while laying out, on the UI thread. */
    @Test
    public void similarToMakesNoRequest() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.CATEGORY_TREE)
                                                    .then(Fixtures.PROD_LICENCE_TO_KILL);
        ZxartCatalogue zxart = catalogue(http);
        Catalogue.Item item = zxart.open(shelf(ZxartCatalogue.SHELF_EVERYTHING),
                                         Catalogue.Query.none(), 0).items().get(0);

        int before = http.asked.size();
        Catalogue.Shelf like = zxart.similarTo(item, "Games like this one");

        assertNotNull(like);
        assertEquals("Games like this one", like.label());
        assertEquals(before, http.asked.size());
    }

    /** The total is the service's own and is real, so a shelf can print it -
     *  unlike ZXInfo's, which caps at 10,000. */
    @Test
    public void theTotalIsWhatTheServiceSaid() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.CATEGORY_TREE)
                                                    .then(Fixtures.PROD_SEARCH);
        Catalogue.Page page = catalogue(http).open(shelf(ZxartCatalogue.SHELF_SEARCH),
                                                  Catalogue.Query.text("head over heels"), 0);

        assertEquals(6, page.total());
    }

    // --- helpers -----------------------------------------------------------------------

    private Catalogue.Item onlyItem() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.CATEGORY_TREE)
                                                    .then(Fixtures.PROD_LICENCE_TO_KILL);

        return catalogue(http).open(shelf(ZxartCatalogue.SHELF_EVERYTHING),
                                    Catalogue.Query.none(), 0).items().get(0);
    }

    private static Catalogue.Shelf shelf(String id) {
        return new Catalogue.Shelf(id, id, Catalogue.Shelf.Accepts.NOTHING);
    }

    private static String lastAsked(Fixtures.Canned http) {
        return http.asked.get(http.asked.size() - 1);
    }
}
