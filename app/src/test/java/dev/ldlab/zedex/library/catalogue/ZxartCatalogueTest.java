package dev.ldlab.zedex.library.catalogue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.ldlab.zedex.library.scrape.Pace;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
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
 * <b>Corrections to the task brief's own test code, made before this ran
 * once:</b>
 * <ol>
 *   <li>{@code aSearchAsksForWhatWasTyped} and {@code theTreeIsAskedForOnce}
 *       use {@code catalogue.shelves().get(0)} for the search shelf rather
 *       than the {@code shelf(String)} helper below, which always answers
 *       {@code Accepts.NOTHING} - the brief says this in prose after the
 *       code and it is applied here.</li>
 *   <li>{@code categoriesYieldShelvesAndThenBoth} queues a second reply,
 *       {@code Fixtures.PROD_SEARCH}, for opening the Games root. The
 *       brief's own listing queued only the tree - one reply for two real
 *       requests (the tree, then the Games category's own filtered search) -
 *       which cannot pass under any implementation that does not fabricate
 *       rows: {@code Fixtures.Canned} answers a spent queue with {@code
 *       {"responseStatus" absent}}, which {@code ZxartApi.ask} reads as
 *       MALFORMED, and even if it did not, the category tree's own body
 *       carries no {@code zxProd} rows to reuse. Neither {@code PROD_SEARCH}
 *       nor {@code PROD_LICENCE_TO_KILL} (used here in an earlier revision)
 *       was captured from that exact {@code filter:zxProdCategory=92177}
 *       request, but a six-row search reply is the better stand-in of the
 *       two: the point of this test is that a root page carries child
 *       shelves <em>and</em> items together, and a shelf that plausibly holds
 *       several rows says that more plainly than one that happens to hold
 *       exactly one.</li>
 * </ol>
 *
 * <b>Round-one review fixes, on top of the above:</b> {@code
 * itemCostsThreeRequestsColdAndTwoWarm} replaces {@code oneItemIsTwoRequests}
 * - the old name stopped being true the moment {@code item()} started
 * ensuring the tree, and nothing in that test caught it; {@code
 * openingSimilarToResolvesTheLeafOnce} is new, covering {@code open}'s side of
 * resolving a leaf category on demand now that {@code similarTo} no longer
 * remembers one; and {@code titlesAreUnescaped} no longer asserts on {@code
 * onlyItem()}, whose title has nothing escaped in it to prove anything with -
 * see that test's own javadoc for what it asserts on instead and why.
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
     * Five shelves, and <b>no A-Z</b>.
     *
     * Every title-prefix filter zxart might have had is ignored -
     * zxProdTitleStart returned all 58,032 - so a letter picker cannot be
     * built, and a shelf that cannot be built is not declared. The want is
     * served by the alphabetical sort instead. This is the seam's whole
     * argument in one assertion.
     *
     * <b>Updated for Task 11, honestly: this was three until Music and
     * Graphics were added.</b> The count and the two new indices are the only
     * change - the {@code Accepts.LETTER} loop is untouched and still pins
     * the argument above, which is just as true of the two new root shelves
     * as of the original three: neither takes a letter either.
     */
    @Test
    public void theShelvesAreTheOnesThatCanBeBuilt() {
        List<Catalogue.Shelf> shelves = catalogue(new Fixtures.Canned()).shelves();

        assertEquals(5, shelves.size());
        assertEquals(ZxartCatalogue.SHELF_SEARCH, shelves.get(0).id());
        assertTrue(shelves.get(0).accepts(Catalogue.Shelf.Accepts.TEXT));
        assertEquals(ZxartCatalogue.SHELF_CATEGORIES, shelves.get(1).id());
        assertEquals(ZxartCatalogue.SHELF_EVERYTHING, shelves.get(2).id());
        assertEquals(ZxartCatalogue.SHELF_MUSIC, shelves.get(3).id());
        assertEquals(ZxartCatalogue.SHELF_GRAPHICS, shelves.get(4).id());

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
                                                    .then(Fixtures.PROD_SEARCH);
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

    /**
     * A hand-built reply, not one of {@code Fixtures}' captured ones.
     *
     * None of the six {@code zxProd}/{@code zxProdCategory}/{@code zxRelease}
     * fixtures this task uses carries a genuinely escaped {@code title} to
     * prove {@link ZxartCatalogue}'s own unescaping against - checked with a
     * grep of the whole {@code Fixtures} file for {@code &amp;}, {@code
     * &quot;}, {@code &lt;}, {@code &gt;} and {@code &#039;} before writing
     * this. The one place {@code &#039;} does appear is {@code
     * Fixtures.PROD_LICENCE_TO_KILL}'s own {@code categoriesString} - the
     * field the task brief says to ignore entirely, since it is Russian and a
     * kind is decided by id, never by word - and the category tree's own
     * {@code title} fields are <em>already</em> plain ({@code CATEGORY_TREE}
     * holds {@code "Shoot 'em up (Shmups)"} and {@code "Shoot 'em Up"} with a
     * literal apostrophe each, not {@code &#039;}). The only fixture with a
     * genuinely escaped {@code title} is {@code Fixtures.PICTURE_ROW} -
     * {@code "Girl &amp; Sea"} - and that is a {@code zxPicture} row, the
     * wrong shape for a {@code zxProd} reply and Task 11's fixture rather than
     * this task's.
     *
     * So this reuses the one pairing {@code ZxartApiTest} already measures
     * and pins for {@link dev.ldlab.zedex.library.scrape.ZxartApi#unescape}
     * itself - {@code "doom&#039;er"} to {@code "doom'er"} - inside a reply
     * shaped like a real one, to prove {@code itemFrom} actually calls it.
     * It is not re-measuring the escape table, which is not this class's fact
     * to hold; it is proving the wiring.
     */
    private static final String PROD_WITH_ESCAPED_TITLE =
            "{\"totalAmount\":1,\"responseData\":{\"zxProd\":[{\"id\":1,\"title\":\"doom&#039;er\"}]},"
            + "\"responseStatus\":\"success\"}";

    /** Titles arrive escaped and must not reach a row that way - see {@link
     *  #PROD_WITH_ESCAPED_TITLE} for why this cannot be shown with a captured
     *  fixture and what it asserts on instead. */
    @Test
    public void titlesAreUnescaped() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.CATEGORY_TREE)
                                                    .then(PROD_WITH_ESCAPED_TITLE);

        Catalogue.Item item = catalogue(http).open(shelf(ZxartCatalogue.SHELF_EVERYTHING),
                                                   Catalogue.Query.none(), 0).items().get(0);

        assertEquals("doom'er", item.title());
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

    /** Inherits {@link Catalogue#knowsFormats()}'s false default rather than
     *  overriding it - see this class's own javadoc, "knowsFormats(): not
     *  overridden". */
    @Test
    public void zxartCannotAnswerForFormats() {
        assertFalse(catalogue(new Fixtures.Canned()).knowsFormats());
    }

    /** And so it is never asked to sift: a bigger page for a filter that is
     *  not applied is bytes nobody wanted. */
    @Test
    public void aZxartQueryIsNeverSifting() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.CATEGORY_TREE)
                                                    .then(Fixtures.PROD_SEARCH);
        catalogue(http).open(catalogue(http).shelves().get(0),
                             Catalogue.Query.text("head").sifting(), 0);

        assertTrue("a sifting hint changes nothing here, and must not silently"
                   + " triple the page size", lastAsked(http).contains("limit:30"));
    }

    /**
     * A cold instance's first {@code item()} is three requests - the tree,
     * then the prod, then its releases - because the tree is what turns a
     * leaf category into a folder and is fetched once per instance and held;
     * every {@code item()} after that, on the same instance, is exactly two.
     *
     * Replaces {@code oneItemIsTwoRequests}, which asserted only the item's
     * own fields and so kept passing - having stopped being true - the moment
     * {@code item()} started ensuring the tree.
     */
    @Test
    public void itemCostsThreeRequestsColdAndTwoWarm() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.CATEGORY_TREE)
                                                    .then(Fixtures.PROD_LICENCE_TO_KILL)
                                                    .then(Fixtures.RELEASES_LICENCE_TO_KILL)
                                                    .then(Fixtures.PROD_LICENCE_TO_KILL)
                                                    .then(Fixtures.RELEASES_LICENCE_TO_KILL);
        ZxartCatalogue zxart = catalogue(http);

        Catalogue.Item first = zxart.item("92668");

        assertNotNull(first);
        assertFalse(first.versions().isEmpty());
        assertEquals("cold: tree, prod, releases", 3, http.asked.size());
        assertTrue("the tree is the first request on a cold instance",
                   http.asked.get(0).contains("export:zxProdCategory"));

        Catalogue.Download file = first.versions().get(0).files().get(0);
        assertEquals("tzx", file.format());
        assertTrue(file.url().startsWith("https://zxart.ee/releasefile/"));
        assertEquals(41330, file.size());

        Catalogue.Item second = zxart.item("92668");

        assertNotNull(second);
        assertEquals("warm: the tree is held, so this costs two more, not three",
                     5, http.asked.size());
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

    /**
     * similarTo is a way in and costs nothing until opened - the pane calls
     * it while laying out, on the UI thread.
     *
     * The shelf's id carries the prod's own id, not a leaf category: {@code
     * open} is what resolves one, and only once this shelf is actually
     * opened - see {@code openingSimilarToResolvesTheLeafOnce}.
     */
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
        assertEquals(ZxartCatalogue.MORE_PREFIX + item.id(), like.id());
        assertEquals(before, http.asked.size());
    }

    /**
     * Opening the shelf {@code similarTo} returns resolves the leaf category
     * it names - one request to find the prod's own leaf, then the same
     * filtered search a category shelf makes with it.
     *
     * Licence to Kill's first leaf is 523395 ("Run 'n' Gun", under Games) -
     * see {@code aRowsKindIsItsRootCategory} and the task brief's controller
     * notes for that fact. {@code Fixtures.PROD_SEARCH} stands in for the
     * "similar" page's own reply, exactly as it does for {@code
     * categoriesYieldShelvesAndThenBoth} - nothing was captured from this
     * exact request either.
     */
    @Test
    public void openingSimilarToResolvesTheLeafOnce() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.CATEGORY_TREE)
                                                    .then(Fixtures.PROD_LICENCE_TO_KILL)
                                                    .then(Fixtures.PROD_LICENCE_TO_KILL)
                                                    .then(Fixtures.PROD_SEARCH);
        ZxartCatalogue zxart = catalogue(http);
        Catalogue.Item item = zxart.open(shelf(ZxartCatalogue.SHELF_EVERYTHING),
                                         Catalogue.Query.none(), 0).items().get(0);
        Catalogue.Shelf like = zxart.similarTo(item, "Games like this one");

        Catalogue.Page page = zxart.open(like, Catalogue.Query.none(), 0);

        assertEquals(4, http.asked.size());
        assertTrue("resolving the leaf is a plain lookup of the prod's own id",
                   http.asked.get(2).contains("filter:zxProdId=" + item.id()));
        assertTrue("and then the leaf filters the actual page, same as a category shelf",
                   http.asked.get(3).contains("filter:zxProdCategory=523395"));
        assertFalse(page.items().isEmpty());
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

    // --- Task 8: the sort control --------------------------------------------------------

    /** Four sorts, and every one of them measured. rating,desc and
     *  votesAmount,desc are ignored by the service; votes,desc is what works,
     *  on prods, music and pictures alike. */
    @Test
    public void zxartOffersTheSortsItCanHonour() {
        List<Catalogue.Sort> sorts = catalogue(new Fixtures.Canned()).sorts();

        assertEquals(Arrays.asList(Catalogue.Sort.DEFAULT, Catalogue.Sort.TOP,
                                   Catalogue.Sort.NEWEST, Catalogue.Sort.ALPHABETICAL),
                     sorts);
    }

    /** A sort is an order segment, applied to whichever shelf is open - here
     *  Everything, the shelf carrying no filter at all. */
    @Test
    public void aSortIsAnOrderSegment() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.CATEGORY_TREE)
                                                    .then(Fixtures.PROD_SEARCH);
        ZxartCatalogue zxart = catalogue(http);

        zxart.open(zxart.shelves().get(2),
                   Catalogue.Query.none().sortedBy(Catalogue.Sort.TOP), 0);

        assertTrue(lastAsked(http).contains("order:votes,desc"));
    }

    /**
     * The sort applies inside a shelf, which is the whole point of its being
     * a control rather than a shelf of its own: Top inside Games, not Top
     * instead of Games.
     *
     * {@code zxart.shelves().get(1)} is {@code SHELF_CATEGORIES}; opening it
     * yields the roots, and this descends into the first of those - a real
     * category filter, not the unfiltered Everything shelf the sort is proved
     * against above.
     */
    @Test
    public void aSortAppliesInsideACategory() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.CATEGORY_TREE)
                                                    .then(Fixtures.PROD_SEARCH);
        ZxartCatalogue zxart = catalogue(http);
        Catalogue.Page roots = zxart.open(zxart.shelves().get(1), Catalogue.Query.none(), 0);

        zxart.open(roots.shelves().get(0),
                   Catalogue.Query.none().sortedBy(Catalogue.Sort.TOP), 0);

        assertTrue(lastAsked(http).contains("filter:zxProdCategory=92177"));
        assertTrue(lastAsked(http).contains("order:votes,desc"));
    }

    /** The default asks for no order at all, rather than for the service's
     *  default spelled out - one less name to be wrong about. */
    @Test
    public void theDefaultSortAsksForNothing() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.CATEGORY_TREE)
                                                    .then(Fixtures.PROD_SEARCH);
        ZxartCatalogue zxart = catalogue(http);
        zxart.open(zxart.shelves().get(2), Catalogue.Query.none(), 0);

        assertFalse(lastAsked(http).contains("order:"));
    }

    /** A query carries its sort through the copies sifting() makes, or a
     *  filtered shelf would silently lose it. */
    @Test
    public void aSortSurvivesTheSiftingCopy() {
        Catalogue.Query query = Catalogue.Query.text("head")
                .sortedBy(Catalogue.Sort.NEWEST).sifting();

        assertEquals(Catalogue.Sort.NEWEST, query.sort());
        assertTrue(query.isSifting());
    }

    // --- Task 11: music and graphics -----------------------------------------------------

    /** Five roots now, and the two new ones are ways in rather than screens:
     *  opening one yields its own sub-shelves, the mechanism Categories uses. */
    @Test
    public void musicAndGraphicsAreShelvesAtTheRoot() {
        List<Catalogue.Shelf> shelves = catalogue(new Fixtures.Canned()).shelves();

        assertEquals(5, shelves.size());
        assertEquals(ZxartCatalogue.SHELF_MUSIC, shelves.get(3).id());
        assertEquals(ZxartCatalogue.SHELF_GRAPHICS, shelves.get(4).id());
    }

    @Test
    public void openingMusicYieldsSubShelvesAndNoRequest() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned();
        Catalogue.Page page = catalogue(http).open(
                new Catalogue.Shelf(ZxartCatalogue.SHELF_MUSIC, "Music",
                                    Catalogue.Shelf.Accepts.NOTHING),
                Catalogue.Query.none(), 0);

        assertFalse(page.shelves().isEmpty());
        assertTrue(page.items().isEmpty());
        assertTrue(http.asked.isEmpty());
    }

    /**
     * A tune is one version and two files, the playable one first.
     *
     * The ogg is what any phone can play and the PT3 is the original worth
     * keeping. Order is load-bearing: Pick.otherFile answers with the first
     * file that is neither picture nor program, and Open hands that to the
     * phone.
     */
    @Test
    public void aTuneIsTheOggThenTheOriginal() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.MUSIC_ROW);
        Catalogue.Page page = catalogue(http).open(musicSubShelf(), Catalogue.Query.none(), 0);
        Catalogue.Item tune = page.items().get(0);

        assertEquals("Music", tune.kind());
        assertEquals(Kinds.MUSIC, Kinds.folderFor(tune.kind()));

        List<Catalogue.Download> files = tune.versions().get(0).files();
        assertEquals("ogg", files.get(0).format());
        assertEquals("mt3", files.get(1).format());
        assertEquals("ogg", Pick.otherFile(tune).format());
    }

    /** A picture is the rendered PNG then the screen dump, for the same
     *  reason and with the same consequence. */
    @Test
    public void aPictureIsThePngThenTheDump() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.PICTURE_ROW);
        Catalogue.Item art = catalogue(http)
                .open(graphicsSubShelf(), Catalogue.Query.none(), 0).items().get(0);

        assertEquals("Graphics", art.kind());
        assertEquals(Kinds.GRAPHICS, Kinds.folderFor(art.kind()));
        assertEquals("png", art.versions().get(0).files().get(0).format());
        assertEquals("scr", art.versions().get(0).files().get(1).format());
        assertEquals("png", Pick.otherFile(art).format());
    }

    /** Top rated is the sort these two exist for, and it is the same order
     *  name: measured, the reply calls the field rating and the order answers
     *  only to votes. */
    @Test
    public void musicSortsByTheSameOrderNameAsProds() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.MUSIC_ROW);
        catalogue(http).open(musicSubShelf(),
                             Catalogue.Query.none().sortedBy(Catalogue.Sort.TOP), 0);

        assertTrue(lastAsked(http).contains("export:zxMusic"));
        assertTrue(lastAsked(http).contains("order:votes,desc"));
    }

    /** Both sub-shelves and both entities cost no request to descend into -
     *  the root shelf yields them, and opening one is the very first request
     *  made, exactly as {@link #openingMusicYieldsSubShelvesAndNoRequest}
     *  pins for Music. */
    @Test
    public void openingGraphicsYieldsSubShelvesAndNoRequest() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned();
        Catalogue.Page page = catalogue(http).open(
                new Catalogue.Shelf(ZxartCatalogue.SHELF_GRAPHICS, "Graphics",
                                    Catalogue.Shelf.Accepts.NOTHING),
                Catalogue.Query.none(), 0);

        assertFalse(page.shelves().isEmpty());
        assertTrue(page.items().isEmpty());
        assertTrue(http.asked.isEmpty());
    }

    /**
     * Both Search sub-shelves are real filters, measured against this
     * feature's own spec, which had guessed they would be ignored the way
     * most zxart title filters are - see {@code ZxartApi.FILTER_MUSIC_SEARCH}
     * and {@code FILTER_PICTURE_SEARCH}'s own javadoc for the numbers.
     */
    @Test
    public void searchAsksWithTheMeasuredFilterNames() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.MUSIC_ROW)
                                                    .then(Fixtures.PICTURE_ROW);
        ZxartCatalogue zxart = catalogue(http);

        zxart.open(new Catalogue.Shelf(ZxartCatalogue.MUSIC_PREFIX + "search", "Search",
                                       Catalogue.Shelf.Accepts.TEXT),
                  Catalogue.Query.text("beyond"), 0);
        assertTrue(http.asked.get(0).contains("filter:zxMusicSearch=beyond"));

        zxart.open(new Catalogue.Shelf(ZxartCatalogue.GRAPHICS_PREFIX + "search", "Search",
                                       Catalogue.Shelf.Accepts.TEXT),
                  Catalogue.Query.text("girl"), 0);
        assertTrue(http.asked.get(1).contains("filter:zxPictureSearch=girl"));
    }

    /**
     * item() answers a music or a picture id, and resolves the author's name
     * along the way - the one fact these two entities have that a prod does
     * not.
     */
    @Test
    public void itemAnswersAMusicIdWithItsAuthor() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.MUSIC_ROW)
                                                    .then(Fixtures.AUTHOR_RAFFAELE_CECCO);
        Catalogue.Item tune = catalogue(http).item(ZxartCatalogue.MUSIC_PREFIX + "19636");

        assertEquals("Music", tune.kind());
        assertEquals("Raffaele Cecco", tune.publisher());
        assertTrue(http.asked.get(1).contains("export:author"));
        assertTrue(http.asked.get(1).contains("filter:authorId=7744"));
    }

    /** {@link #itemAnswersAMusicIdWithItsAuthor}'s twin, for a picture - and
     *  pictureUrl is the rendered PNG, since the entity <em>is</em> the
     *  picture. */
    @Test
    public void itemAnswersAPictureIdWithItsAuthor() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.PICTURE_ROW)
                                                    .then(Fixtures.AUTHOR_RAFFAELE_CECCO);
        Catalogue.Item art = catalogue(http).item(ZxartCatalogue.GRAPHICS_PREFIX + "2232");

        assertEquals("Graphics", art.kind());
        assertEquals("Raffaele Cecco", art.publisher());
        assertTrue(art.pictureUrl().contains("zximages"));
    }

    /**
     * Review round 1: nothing above actually pins the request <em>count</em>
     * for a list page - {@code aTuneIsTheOggThenTheOriginal} only checks the
     * rows it got back, and {@code Fixtures.Canned} answers an exhausted
     * queue with a silent empty success rather than failing, so a regression
     * that moved {@code authorNameOf} into {@code openMusic}'s per-row loop
     * would still leave every field assertion passing while quietly turning
     * one request into two. This is the count, asserted on its own, with a
     * single-row fixture - the smallest one that can distinguish "made a
     * request" from "made the right number".
     */
    @Test
    public void openingMusicIsExactlyOneRequest() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.MUSIC_ROW);
        catalogue(http).open(musicSubShelf(), Catalogue.Query.none(), 0);

        assertEquals(1, http.asked.size());
    }

    /** {@link #openingMusicIsExactlyOneRequest}'s twin, for Graphics. */
    @Test
    public void openingGraphicsIsExactlyOneRequest() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.PICTURE_ROW);
        catalogue(http).open(graphicsSubShelf(), Catalogue.Query.none(), 0);

        assertEquals(1, http.asked.size());
    }

    /**
     * The count that a single-row fixture cannot tell apart from a per-row
     * bug: one row and thirty rows cost {@code Fixtures.Canned} the same one
     * reply either way, so only a page carrying <em>several</em> rows proves
     * the request is made once per page rather than once per row. {@code
     * Fixtures.MUSIC_SEARCH} is a real capture of three rows out of the ten
     * {@code zxMusicSearch=beyond} actually matches - the largest multi-row
     * music reply on file.
     */
    @Test
    public void openingMusicIsOneRequestForAWholePage() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.MUSIC_SEARCH);
        Catalogue.Page page = catalogue(http).open(musicSubShelf(), Catalogue.Query.none(), 0);

        assertEquals(3, page.items().size());
        assertEquals(1, http.asked.size());
    }

    /** {@link #openingMusicIsOneRequestForAWholePage}'s twin, for Graphics -
     *  {@code Fixtures.PICTURE_SEARCH}, three of the 124 {@code
     *  zxPictureSearch=girl} rows. */
    @Test
    public void openingGraphicsIsOneRequestForAWholePage() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.PICTURE_SEARCH);
        Catalogue.Page page = catalogue(http).open(graphicsSubShelf(), Catalogue.Query.none(), 0);

        assertEquals(3, page.items().size());
        assertEquals(1, http.asked.size());
    }

    private static Catalogue.Shelf musicSubShelf() {
        return new Catalogue.Shelf(ZxartCatalogue.MUSIC_PREFIX + "everything", "Everything",
                                   Catalogue.Shelf.Accepts.NOTHING);
    }

    private static Catalogue.Shelf graphicsSubShelf() {
        return new Catalogue.Shelf(ZxartCatalogue.GRAPHICS_PREFIX + "everything", "Everything",
                                   Catalogue.Shelf.Accepts.NOTHING);
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
