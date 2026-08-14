package dev.ldlab.zedex.library.catalogue;

import dev.ldlab.zedex.library.scrape.Http;
import dev.ldlab.zedex.library.scrape.ScrapeException;
import dev.ldlab.zedex.library.scrape.ZxartApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * zxart.ee, the demoscene and games archive, as something to browse.
 *
 * <b>Three shelves, and that is what the service can actually build.</b> A
 * search box, a category tree that rolls its roots up into their own prods,
 * and everything - no A-Z, because {@code ZxartApi}'s own measurements found
 * every title-prefix filter ignored rather than refused: {@code
 * zxProdTitleStart} answers the unfiltered 58,032 every time. The want an
 * alphabet serves elsewhere is left to {@code sorts()} (Task 8) instead of a
 * shelf this service cannot build.
 *
 * <b>No Android types</b> - no {@code Uri}, no {@code Log} - so this runs on
 * the JVM in {@code ZxartCatalogueTest} in well under a second, against
 * captured replies. {@link ZxartApi#encode} is what lets a URL be built
 * without {@code Uri}; the alternative would answer null under {@code
 * unitTests.returnDefaultValues} and every assertion after it would pass
 * having tested nothing.
 *
 * <h3>The category tree: fetched once, and never fetched in the constructor</h3>
 *
 * {@link #tree()} is the only place that talks to {@code export:zxProdCategory}.
 * It is called lazily - the first shelf that needs a kind, a set of children,
 * or the Categories roots themselves triggers it - and the result is held for
 * the life of this object: thirteen kilobytes and 285 rows once a session, not
 * once a row. Every shelf that will need a kind calls it <b>before</b> making
 * its own request, deliberately, so that a session opening Categories and then
 * Search issues the tree request first and the search second - which is also
 * the order every test in {@code ZxartCatalogueTest} queues its fixtures in.
 *
 * <b>A tree that cannot be fetched must not take a shelf down with it.</b>
 * {@link #tree()} catches whatever {@link ZxartApi#ask} throws and falls back
 * to {@code ZxartTree.from(Collections.emptyList())} - an empty, but usable,
 * tree - rather than letting a {@link ScrapeException} escape from {@link
 * #kindOf}. A row built against an empty tree simply gets no kind ({@code
 * Kinds.folderFor(null)} is {@code Other}, a real answer and not a failure),
 * which is a far smaller loss than turning a working shelf into an error
 * screen because a folder name could not be worked out. The failure - and the
 * fallback - is cached exactly like a success: a service that just refused
 * the tree once is not asked again on every single row of every page for the
 * rest of the session.
 *
 * <h3>Categories: shelves and items on the same page</h3>
 *
 * Opening {@link #SHELF_CATEGORIES} yields nine sub-shelves and no items - see
 * {@link #categories()}. Opening one of <em>those</em> - a root, id {@link
 * #CATEGORY_PREFIX} plus the tree's own category id - yields both: its
 * children as sub-shelves and its own prods as items, because zxart's roots
 * roll up. Opening Games is ten sub-shelves and 23,162 prods on the one page,
 * which is the first thing in this codebase to fill {@link Page#shelves()}
 * and {@link Page#items()} at once - the mechanism {@link Page}'s own javadoc
 * always described sub-shelves as being for.
 *
 * <h3>{@code item()}: two requests once the tree is held, three on a cold
 * instance</h3>
 *
 * {@code types:zxProd,zxRelease} - the shape zxart's own documentation
 * describes for fetching a prod and its releases together - answers HTTP 500.
 * So {@link #item} asks twice on top of whatever {@link #tree()} costs: {@code
 * export:zxProd} filtered by {@link ZxartApi#FILTER_PROD_ID} for the row, then
 * {@code export:zxRelease} filtered the same way for every release of it.
 * {@link #tree()} is what turns a leaf category into the folder word {@link
 * Item#kind()} carries, and it is fetched once per instance and held - so the
 * very first {@link #item} (or {@link #open}) call of a session is three
 * requests, tree then prod then releases, and every {@link #item} call after
 * it on the same instance is exactly two. {@code
 * ZxartCatalogueTest.itemCostsThreeRequestsColdAndTwoWarm} pins both counts.
 * The caller still asks once either way; how many requests that costs is this
 * catalogue's own business, exactly as {@link Catalogue#item}'s own javadoc
 * allows.
 *
 * <h3>A row's kind, and how {@link #similarTo} finds "similar" without one</h3>
 *
 * A prod names its categories by leaf id ({@code connectedCategoriesIds}) and
 * never by word - zxart answers in whichever of three languages this app
 * asked for, so a folder decided by matching a word would be right in one
 * language and {@code Other} in the rest. {@link #kindOf} walks the first leaf
 * that {@link ZxartTree#rootOf} can resolve up to one of {@link
 * Kinds#ZXART_ROOTS} and answers with that root's own English word, which
 * becomes {@link Item#kind()} untouched.
 *
 * {@link Catalogue.Item} carries no field for the leaf id that produced that
 * word, so {@link #similarTo} cannot build a filter from it - and {@link
 * #similarTo} must not make a request either, since the pane calls it on the
 * UI thread while laying out. So the shelf it returns carries the prod's own
 * id behind {@link #MORE_PREFIX}, and {@link #open} resolves the leaf - one
 * more request, {@link #leafOfProd} - only once that shelf is actually
 * opened, exactly where {@link #categories()}, {@link #childrenOf} and {@link
 * #tree()} already defer their own cost to.
 *
 * <b>This used to be a {@code Map<String, String>} from an item's id to the
 * leaf {@link #leafOf} found for it, filled in as every row was built.</b> It
 * was the wrong shape, for a reason this codebase has hit more than once (see
 * {@code StepAside}'s remove-on-destroy set and the {@code RecyclerView} that
 * measured zero and ate 1.9 GB, both in this project's own working notes): a
 * catalogue instance lives as long as the library tab, nothing ever cleared
 * the map, and every row of every shelf ever browsed added an entry that
 * outlived the pane that built it. It was also a silently wrong answer rather
 * than an honest one - an item this instance had not happened to build a row
 * for (a different session, a stale reference) made {@link #similarTo} answer
 * null, indistinguishable from "this game genuinely names no category", when
 * the true answer was "not asked yet". Resolving on open costs one request
 * only when the shelf somebody was actually offered is actually opened, which
 * for a shelf never opened is nothing at all - strictly less than an
 * unbounded map paid for every row regardless.
 */
public final class ZxartCatalogue implements Catalogue {

    static final String SHELF_SEARCH = "search";
    static final String SHELF_CATEGORIES = "categories";
    static final String SHELF_EVERYTHING = "everything";

    /** A sub-shelf yielded by {@link #categories()} or {@link #childrenOf}
     *  carries the category id behind this prefix, so {@link #open} can tell
     *  one from a declared shelf without a second field - the same trick
     *  {@code ZxInfoCatalogue}'s {@code GENRE_PREFIX} and {@code
     *  LETTER_PREFIX} use. */
    static final String CATEGORY_PREFIX = "category:";

    /** And again for {@link #similarTo}, whose id carries the <b>prod's own
     *  id</b> - {@link #open} resolves the leaf category only once this shelf
     *  is opened, via {@link #leafOfProd}, rather than up front. See the class
     *  javadoc's "similarTo" section for why. */
    static final String MORE_PREFIX = "more:";

    /** What a page of the grid asks for. Thirty, like ZXInfo's, because a
     *  screenful is a screenful whichever archive it came from. */
    private static final int PAGE_SIZE = 30;

    /** What the one request that builds {@link #tree} asks for. 1,000 rather
     *  than {@link #PAGE_SIZE}: the whole tree is 285 rows in 13.5 KB,
     *  measured (see {@code ZxartTree}'s own javadoc) and the point of fetching
     *  it at all is to hold every category for the life of this object - a
     *  tree fetched thirty rows at a time would need up to ten paced requests
     *  before the first shelf could be drawn. */
    private static final int TREE_PAGE_SIZE = 1000;

    /** What the release request in {@link #item} asks for. Fifty, because
     *  that is what the captured request behind {@code
     *  Fixtures.RELEASES_LICENCE_TO_KILL} actually asked for and it was
     *  comfortably enough - 24 releases inside a limit of 50 - for the
     *  richest prod measured. A prod with more releases than this would need
     *  a second page, which nothing here asks for yet: the two-request shape
     *  {@link #item} promises is one page of releases, not all of them at any
     *  cost. */
    private static final int RELEASE_PAGE_SIZE = 50;

    private final ZxartApi api;
    private final String language;

    /** Fetched once and held: it is the Categories shelf and it is every
     *  row's kind. See the class javadoc's <em>category tree</em> section for
     *  what happens when it cannot be fetched at all. */
    private ZxartTree tree;

    public ZxartCatalogue(Http http, Locale locale) {
        this.api = new ZxartApi(http);
        this.language = ZxartApi.language(locale);
    }

    @Override
    public String name() {
        return "zxart";
    }

    /** No credentials to be missing - zxart's API takes none. */
    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public List<Shelf> shelves() {
        return Arrays.asList(
                new Shelf(SHELF_SEARCH, "Search", Shelf.Accepts.TEXT),
                new Shelf(SHELF_CATEGORIES, "Categories", Shelf.Accepts.NOTHING),
                new Shelf(SHELF_EVERYTHING, "Everything", Shelf.Accepts.NOTHING));
    }

    @Override
    public Page open(Shelf shelf, Query query, int page) throws ScrapeException {
        if (SHELF_CATEGORIES.equals(shelf.id())) return categories();

        // Ensured before this shelf's own request, and not lazily inside
        // itemFrom/kindOf as the task brief's own outline first suggested:
        // every test here queues the tree's own reply first and the shelf's
        // second, and the tree has to be the first request actually sent for
        // that to be more than a coincidence. See the class javadoc.
        tree();

        ZxartApi.Ask ask = new ZxartApi.Ask(ZxartApi.PROD)
                .language(language)
                .page(page, PAGE_SIZE);

        if (SHELF_SEARCH.equals(shelf.id())) {
            ask.filter(ZxartApi.FILTER_SEARCH, query.text());
        } else if (shelf.id().startsWith(CATEGORY_PREFIX)) {
            ask.filter(ZxartApi.FILTER_CATEGORY, idIn(shelf.id(), CATEGORY_PREFIX));
        } else if (shelf.id().startsWith(MORE_PREFIX)) {
            // similarTo hands over the prod's own id, not a leaf - resolved
            // here, the one place a request is allowed, rather than paid for
            // every row a shelf happens to show. No leaf, no request worth
            // making: an unfiltered "similar" would be everything, which is a
            // worse answer than an empty shelf to a question that could not
            // be resolved.
            String leaf = leafOfProd(idIn(shelf.id(), MORE_PREFIX));
            if (leaf == null) return new Page(null, null, page * PAGE_SIZE, Page.UNKNOWN_TOTAL);

            ask.filter(ZxartApi.FILTER_CATEGORY, leaf);
        }
        // SHELF_EVERYTHING carries no filter at all - every prod, unfiltered.

        JSONObject reply = api.ask(ask);
        List<Item> items = new ArrayList<>();

        for (JSONObject row : ZxartApi.rows(reply, ZxartApi.PROD)) items.add(itemFrom(row));

        // A root category carries its children as well as its prods: roots
        // roll up, so opening Games is ten sub-shelves and 23,162 items on the
        // one page. Only at page zero - a later page of the same shelf is more
        // of the same prods and does not need to repeat shelves already drawn.
        List<Shelf> below = shelf.id().startsWith(CATEGORY_PREFIX) && page == 0
                ? childrenOf(Integer.parseInt(idIn(shelf.id(), CATEGORY_PREFIX)))
                : null;

        return new Page(items, below, page * PAGE_SIZE, ZxartApi.totalOf(reply));
    }

    @Override
    public Item item(String id) throws ScrapeException {
        // Ensured first, for the same reason open() ensures it first: the
        // fixture order every test in ZxartCatalogueTest queues - tree, prod,
        // releases - is only true of the requests this class actually sends
        // if the tree is asked for before the prod is.
        tree();

        JSONObject prodReply = api.ask(new ZxartApi.Ask(ZxartApi.PROD)
                .language(language)
                .filter(ZxartApi.FILTER_PROD_ID, id));

        List<JSONObject> prodRows = ZxartApi.rows(prodReply, ZxartApi.PROD);
        if (prodRows.isEmpty()) return null;

        JSONObject releaseReply = api.ask(new ZxartApi.Ask(ZxartApi.RELEASE)
                .language(language)
                .page(0, RELEASE_PAGE_SIZE)
                .filter(ZxartApi.FILTER_PROD_ID, id));

        List<Version> versions = versionsFrom(ZxartApi.rows(releaseReply, ZxartApi.RELEASE));

        return itemFrom(prodRows.get(0), versions);
    }

    /**
     * A way in to "games like this one", carrying the prod's own id.
     *
     * <b>Makes no request</b> - the pane calls this on the UI thread while
     * laying out - so the leaf category that decides what "similar" means is
     * resolved later, inside {@link #open}, only if this shelf is actually
     * opened. See the class javadoc's "similarTo" section for why this is a
     * pure construction rather than a lookup into anything remembered from
     * when the item was built.
     *
     * Null only for an item with no id, which is not a shape any row built by
     * this class produces but is cheaper to refuse here than to build a shelf
     * {@link #open} could do nothing with.
     */
    @Override
    public Shelf similarTo(Item item, String label) {
        if (item == null || item.id() == null || item.id().isEmpty()) return null;

        return new Shelf(MORE_PREFIX + item.id(), label, Shelf.Accepts.NOTHING);
    }

    @Override
    public ScrapeException refusalFor(int status) {
        // zxart's own reading of a bare status - see ZxartApi.refusalFor,
        // which this only names, exactly as ZxInfoCatalogue names ZxInfo's.
        return api.refusalFor(status);
    }

    // --- the category tree, held for the session ------------------------------------

    /**
     * The tree, fetched on first need and held for the life of this object.
     *
     * <b>A failure here is swallowed, deliberately, and cached like a
     * success.</b> Every row still has to be built even when the tree cannot
     * be - with no kind, which is a real answer ({@code Kinds.folderFor(null)}
     * is {@code Other}) and not a failure - so a {@link ScrapeException}
     * reaching {@link #kindOf} from here would turn a working shelf into an
     * error screen over nothing worse than a folder name. Caching the empty
     * fallback rather than retrying on every row is the same reasoning {@code
     * Pace} rests on: a service that refused this once is not asked again a
     * hundred times a page for the rest of the session.
     */
    private ZxartTree tree() {
        if (tree != null) return tree;

        try {
            JSONObject reply = api.ask(new ZxartApi.Ask(ZxartApi.CATEGORY)
                    .language(language)
                    .page(0, TREE_PAGE_SIZE)
                    .flag(ZxartApi.FILTER_CATEGORIES_ALL));

            tree = ZxartTree.from(ZxartApi.rows(reply, ZxartApi.CATEGORY));
        } catch (ScrapeException refused) {
            tree = ZxartTree.from(Collections.<JSONObject>emptyList());
        }

        return tree;
    }

    /** The Categories shelf: nine sub-shelves, no items, no page beyond zero -
     *  the tree does not paginate. */
    private Page categories() {
        List<Shelf> found = new ArrayList<>();

        for (ZxartTree.Node root : tree().roots()) {
            found.add(new Shelf(CATEGORY_PREFIX + root.id(), root.title(), Shelf.Accepts.NOTHING));
        }

        return new Page(null, found, 0, Page.UNKNOWN_TOTAL);
    }

    /** A root's children, as sub-shelves of their own - see {@link #open}'s
     *  "roots roll up" comment for why this rides alongside items rather than
     *  needing a shelf of its own. */
    private List<Shelf> childrenOf(int id) {
        List<Shelf> found = new ArrayList<>();

        for (ZxartTree.Node child : tree().childrenOf(id)) {
            found.add(new Shelf(CATEGORY_PREFIX + child.id(), child.title(), Shelf.Accepts.NOTHING));
        }

        return found;
    }

    private static String idIn(String shelfId, String prefix) {
        return shelfId.substring(prefix.length());
    }

    // --- one prod, as a row or as a full item ----------------------------------------

    /** A list row: no versions, because a prod names {@code releasesIds} and
     *  nothing else - resolving them here would be one request per row. See
     *  {@code knowsFormats()}, Task 7, for what stops a format filter reading
     *  that as "holds nothing" rather than "not asked yet". */
    private Item itemFrom(JSONObject row) {
        return itemFrom(row, Collections.<Version>emptyList());
    }

    /**
     * One prod, at whatever versions the caller already has.
     *
     * <b>No publisher, ever, and that is measured rather than lazy.</b> A prod
     * carries {@code publishersIds} and nothing that resolves them: {@code
     * export:publisher} answers HTTP 500, {@code export:group} parses and
     * answers nothing for a publisher id, and {@code export:author} - which
     * does work for authors - has no row for one. Comma-joining ids returns
     * one row, so there is no batch either. {@link Item#describe()} skips
     * whichever of year and publisher is unknown, so a zxart row reads "Head
     * over Heels (1987)" and nothing is left dangling.
     */
    private Item itemFrom(JSONObject row, List<Version> versions) {
        String id = Integer.toString(row.optInt("id", 0));
        int year = row.optInt("year", 0);
        int leaf = leafOf(row);

        return new Item(id,
                        ZxartApi.unescape(row.optString("title", "")),
                        year > 0 ? Integer.toString(year) : null,
                        null,                       // no publisher: see above
                        kindOf(leaf),
                        availabilityOf(row),
                        pictureOf(row),
                        versions);
    }

    /**
     * The first leaf of {@code connectedCategoriesIds} that this app's tree
     * can resolve up to one of {@link Kinds#ZXART_ROOTS}, or -1.
     *
     * <b>-1 rather than a guess</b> - a category the tree has never heard of
     * (added upstream since the tree was fetched, or a tree that could not be
     * fetched at all) is skipped in favour of the next leaf rather than
     * silently filing the whole prod under {@code Other} when a later leaf
     * would have resolved. Kept apart from {@link #kindOf} so a caller that
     * only wants the id - {@link #leafOfProd}, resolving {@link #similarTo}'s
     * shelf - is not made to look up a word it will throw away.
     */
    private int leafOf(JSONObject row) {
        JSONArray leaves = row.optJSONArray("connectedCategoriesIds");

        for (int at = 0; leaves != null && at < leaves.length(); at++) {
            int candidate = leaves.optInt(at, 0);
            if (Kinds.zxartRoot(tree().rootOf(candidate)) != null) return candidate;
        }

        return -1;
    }

    /** The English root word for {@code leaf} - {@code Kinds.folderFor} maps
     *  it to a folder, and {@link Item#kind()} carries it untouched. Null for
     *  -1, which is what leaves {@code Kinds.folderFor(null)} to answer
     *  {@code Other} rather than this class guessing at one. */
    private String kindOf(int leaf) {
        return leaf < 0 ? null : Kinds.zxartRoot(tree().rootOf(leaf));
    }

    /**
     * The leaf category a prod names, fetched fresh - the one request {@link
     * #similarTo}'s shelf costs, and only when it is opened.
     *
     * A second, genuine {@code export:zxProd} lookup rather than anything
     * remembered from when the item was first built: see the class javadoc's
     * "similarTo" section for why a per-item map was the wrong place to keep
     * this. Null for a prod that can no longer be found, or one that names no
     * leaf this app's tree can resolve - either way {@link #open} answers an
     * empty, uncountable page rather than falling back to every prod, which
     * would make a "similar" shelf show whatever the archive has instead of
     * nothing at all.
     */
    private String leafOfProd(String prodId) throws ScrapeException {
        JSONObject reply = api.ask(new ZxartApi.Ask(ZxartApi.PROD)
                .language(language)
                .filter(ZxartApi.FILTER_PROD_ID, prodId));

        List<JSONObject> rows = ZxartApi.rows(reply, ZxartApi.PROD);
        if (rows.isEmpty()) return null;

        int leaf = leafOf(rows.get(0));
        return leaf < 0 ? null : Integer.toString(leaf);
    }

    /**
     * What the service <em>stated</em>, or null.
     *
     * "unknown" is not a statement and must not become one: 975 of 1,000
     * measured rows say it, the word "available" never appears at all, and
     * {@code CatalogueAdapter.greyed} greys anything stated that is not
     * available. Left raw, this catalogue would draw every row greyed with no
     * reason given.
     */
    private static String availabilityOf(JSONObject row) {
        String stated = row.optString("legalStatus", "");

        return stated.isEmpty() || "unknown".equals(stated) ? null : stated;
    }

    /** The first of {@code imagesUrls} - the rendered loading screen, present
     *  on 100% of the measured slice - or null for a prod with none. */
    private static String pictureOf(JSONObject row) {
        JSONArray images = row.optJSONArray("imagesUrls");

        return images != null && images.length() > 0 ? images.optString(0, null) : null;
    }

    // --- one prod's releases, fetched only by item() ----------------------------------

    /** Every release, in the order the reply lists them - nothing here
     *  re-sorts: that order is zxart's own statement about which came first,
     *  exactly as {@code ZxInfoCatalogue.versions} treats ZXDB's. */
    private static List<Version> versionsFrom(List<JSONObject> releases) {
        List<Version> found = new ArrayList<>();

        for (JSONObject release : releases) {
            found.add(new Version(labelFor(release), yearOf(release), filesFor(release)));
        }

        return found;
    }

    /** What tells two releases apart here: {@code releaseType} - "original",
     *  "port", "hack" - and what it needs to run, joined with spaces so a
     *  label reads "original zx128 ay kempston int2_2" rather than naming one
     *  and hiding the rest. Null rather than empty when a release states
     *  neither, so {@link Catalogue.Version#label()}'s own "or null" holds. */
    private static String labelFor(JSONObject release) {
        StringBuilder label = new StringBuilder(release.optString("releaseType", ""));
        JSONArray hardware = release.optJSONArray("hardwareRequired");

        for (int at = 0; hardware != null && at < hardware.length(); at++) {
            label.append(' ').append(hardware.optString(at, ""));
        }

        String built = label.toString().trim();
        return built.isEmpty() ? null : built;
    }

    private static String yearOf(JSONObject release) {
        int year = release.optInt("year", 0);
        return year > 0 ? Integer.toString(year) : null;
    }

    /**
     * One release's one file - {@code file}, {@code releaseFormat[0]}
     * lower-cased, and the size out of {@code releaseStructure[0].size}.
     *
     * A single download rather than a list built from a request per file: a
     * release is one archive on zxart, and {@code releaseStructure} beyond its
     * own first entry is what is packed <em>inside</em> that archive - the
     * loader, the screen, the pokes - not a second file to offer alongside it.
     */
    private static List<Download> filesFor(JSONObject release) {
        String url = release.optString("file", "");
        if (url.isEmpty()) return Collections.emptyList();

        JSONArray formats = release.optJSONArray("releaseFormat");
        String format = formats != null && formats.length() > 0 ? formats.optString(0, "") : "";

        JSONArray structure = release.optJSONArray("releaseStructure");
        long size = -1;

        if (structure != null && structure.length() > 0) {
            JSONObject first = structure.optJSONObject(0);
            if (first != null) size = first.optLong("size", -1);
        }

        return Collections.singletonList(new Download(url, format, size));
    }
}
