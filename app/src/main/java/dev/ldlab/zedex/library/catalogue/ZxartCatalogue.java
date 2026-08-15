package dev.ldlab.zedex.library.catalogue;

import dev.ldlab.zedex.library.scrape.Http;
import dev.ldlab.zedex.library.scrape.ScrapeException;
import dev.ldlab.zedex.library.scrape.ZxartApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * zxart.ee, the demoscene and games archive, as something to browse.
 *
 * <b>Five shelves, and that is what the service can actually build.</b> A
 * search box, a category tree that rolls its roots up into their own prods,
 * everything, and two more ways in - Music and Graphics - that are not
 * screens of their own but roots that yield their own sub-shelves, the same
 * mechanism Categories uses. No A-Z anywhere, because {@code ZxartApi}'s own
 * measurements found every title-prefix filter ignored rather than refused:
 * {@code zxProdTitleStart} answers the unfiltered 58,032 every time. The want
 * an alphabet serves elsewhere is left to {@code sorts()} (Task 8) instead of
 * a shelf this service cannot build.
 *
 * <h3>Music and Graphics: two more entities, no category tree involved</h3>
 *
 * {@link #SHELF_MUSIC} and {@link #SHELF_GRAPHICS} make <b>no request</b> -
 * opening either yields exactly two sub-shelves, Everything and Search, built
 * from nothing but the shelf's own id, and both filters are real: {@code
 * ZxartApi.FILTER_MUSIC_SEARCH}/{@code FILTER_PICTURE_SEARCH} were measured
 * against the live service on 2026-08-14 and do filter, which corrects this
 * feature's own spec (it had guessed they would be ignored, the way most
 * zxart title filters are). Opening one of the two sub-shelves is one request
 * - {@code export:zxMusic} or {@code export:zxPicture} - and never touches
 * {@link #tree()}: a tune or a picture has no category to resolve a folder
 * from, {@link Item#kind()} is simply {@code "Music"} or {@code "Graphics"},
 * the entities' own words, which {@link Kinds#folderFor} already maps (Task
 * 5). That is also why {@link #item} can answer a music or picture id without
 * the three/two-request tree dance a prod costs - see {@link #musicFrom} and
 * {@link #pictureFrom}.
 *
 * <b>Neither entity has a publisher, and both have something better: an
 * author that actually resolves.</b> {@code export:author/filter:authorId=}
 * answers with a name - unlike a prod's {@code publishersIds}, which nothing
 * can resolve - so {@link #item} fetches it, once, for the pane. A list row
 * never does: thirty rows would be thirty paced requests for a name nobody
 * asked to see yet, exactly the reasoning that keeps a prod's releases off
 * its own list rows.
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
 * <b>A tree that cannot be fetched must not take a <em>row</em> down with
 * it - and must not be remembered as an answer.</b> {@link #tree()} catches
 * whatever {@link ZxartApi#ask} throws and falls back to {@code
 * ZxartTree.from(Collections.emptyList())} - an empty, but usable, tree -
 * rather than letting a {@link ScrapeException} escape from {@link #kindOf}. A
 * row built against an empty tree simply gets no kind ({@code
 * Kinds.folderFor(null)} is {@code Other}, a real answer and not a failure),
 * which is a far smaller loss than turning a working shelf into an error
 * screen because a folder name could not be worked out.
 *
 * <b>Only a tree that actually arrived is cached, though.</b> Caching the
 * empty fallback the way a success is cached looked like the same thrift {@code
 * Pace} rests on and was a defect of its own: one transient refusal made {@link
 * #categories()} answer zero sub-shelves - <em>Nothing here.</em> on screen,
 * where every other refusal draws a failure row with <b>Try again</b> - and
 * left every row built for the rest of the session with no kind at all, so
 * every import landed in {@code Downloaded/Other}, silently, in somebody's own
 * collection. So the refusal is remembered only for the length of the call that
 * met it ({@link #treeRefusal}, cleared at every entry point): one request per
 * page rather than one per row, which is the thrift that was actually wanted,
 * and the next shelf opened asks again. {@link #categories()} lets the refusal
 * through, because a screen made of nothing but the tree can honestly fail.
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
 *
 * <h3>{@code knowsFormats()}: not overridden, and that is the whole point</h3>
 *
 * A prod row here carries {@code releasesIds} and nothing else - {@link
 * #itemFrom(JSONObject)} builds a list row with an empty version list, on
 * purpose, because resolving even one release would be a request per row on
 * a shelf of thousands. So {@link Item#formats()} is honestly empty for
 * every row this catalogue ever lists, and inheriting {@link
 * Catalogue#knowsFormats()}'s {@code false} default is what stops the
 * screen's format filter reading that emptiness as "holds nothing" - which
 * would reject every prod in the archive - rather than "not asked yet".
 */
public final class ZxartCatalogue implements Catalogue {

    static final String SHELF_SEARCH = "search";
    static final String SHELF_CATEGORIES = "categories";
    static final String SHELF_EVERYTHING = "everything";
    static final String SHELF_MUSIC = "music";
    static final String SHELF_GRAPHICS = "graphics";

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

    /**
     * Two jobs, same prefix, never confused because they live in different
     * methods: a sub-shelf id ({@code "music:everything"}, {@code
     * "music:search"}, built by {@link #musicRoots()} and read by {@link
     * #open}) and an {@link Item#id()} ({@code "music:19636"}, built by
     * {@link #musicFrom} and read by {@link #item}). zxart's own numbering is
     * per-entity - a prod, a tune and a picture can legitimately share a bare
     * numeric id - so an unprefixed id would be ambiguous the moment {@link
     * #item} had to decide which of three tables to ask; a prod's own id
     * stays bare because {@link #item}'s prod branch was here first and nothing
     * needs it disambiguated from a shelf id.
     */
    static final String MUSIC_PREFIX = "music:";

    /** {@link #MUSIC_PREFIX}'s twin, for {@code zxPicture}. */
    static final String GRAPHICS_PREFIX = "graphics:";

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
     *  row's kind. Only ever a tree the service actually answered with - see
     *  the class javadoc's <em>category tree</em> section, and {@link
     *  #treeRefusal} for what a refusal is remembered as instead. */
    private ZxartTree tree;

    /**
     * The refusal this call met asking for the tree, or null.
     *
     * <b>Not a cache - a within-one-call memo.</b> {@link #open} and {@link
     * #item} clear it on the way in, so a service that refused the tree is
     * asked again the next time somebody opens a shelf, while a page that
     * needs a kind for thirty rows still costs exactly one refused request
     * rather than thirty. The failure is a fact about a moment; the tree is a
     * fact about the archive, and only the second is worth keeping.
     */
    private ScrapeException treeRefusal;

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
                new Shelf(SHELF_EVERYTHING, "Everything", Shelf.Accepts.NOTHING),
                // Both take NOTHING at the root: like Categories, they are a
                // way in rather than a screen, and take nothing themselves -
                // it is their own sub-shelves, Everything and Search, that
                // take TEXT. See the class javadoc's "Music and Graphics"
                // section.
                new Shelf(SHELF_MUSIC, "Music", Shelf.Accepts.NOTHING),
                new Shelf(SHELF_GRAPHICS, "Graphics", Shelf.Accepts.NOTHING));
    }

    /**
     * All four - the whole reason {@link Catalogue.Sort#ALPHABETICAL} exists at
     * all. zxart cannot build an A-Z shelf ({@code zxProdTitleStart} is ignored,
     * measured, and answers the unfiltered 58,032 every time) but {@code
     * order:title,asc} works, so the alphabet a shelf could not build comes back
     * as a sort instead.
     */
    @Override
    public List<Sort> sorts() {
        return Arrays.asList(Sort.DEFAULT, Sort.TOP, Sort.NEWEST, Sort.ALPHABETICAL);
    }

    /**
     * All four on a prod shelf; {@code DEFAULT} and {@code TOP} on a music or
     * graphics one, <b>because that is what was measured there</b>.
     *
     * {@code order:votes,desc} was checked on all three of this service's
     * entities - prods, {@code zxMusic} and {@code zxPicture} - and {@code
     * order:date,desc} and {@code order:title,asc} on <b>prods only</b> (see
     * {@code review/zxart/ord-music-votes.json} and {@code
     * ord-picture-votes.json} for the two that were). Sending the other two to
     * {@code export:zxMusic} and {@code export:zxPicture} anyway is the exact
     * mistake this whole file is written against: <b>an unrecognised order
     * name here is ignored, not refused</b>, so the reply comes back 200 with
     * {@code responseStatus: success} and rows in whatever order the service
     * felt like - a control that reports success and does nothing. Two
     * requests would settle it; until somebody makes them, the honest
     * declaration is the one that has evidence behind it. See {@link
     * Catalogue#sortsFor}.
     *
     * The Music and Graphics roots themselves - and Categories - yield
     * sub-shelves rather than rows, so they answer {@code DEFAULT} alone: there
     * is nothing on those screens for an ordering to order.
     */
    @Override
    public List<Sort> sortsFor(Shelf shelf) {
        if (shelf == null) return sorts();

        String id = shelf.id();

        if (id.startsWith(MUSIC_PREFIX) || id.startsWith(GRAPHICS_PREFIX)) {
            return Arrays.asList(Sort.DEFAULT, Sort.TOP);
        }

        // The three that are prod searches, plus a category and a "similar"
        // shelf - every one of them export:zxProd, which is what the four were
        // measured against. Categories/Music/Graphics are ways in and order
        // nothing; the roots keep DEFAULT alone.
        boolean prods = SHELF_SEARCH.equals(id)
                || SHELF_EVERYTHING.equals(id)
                || id.startsWith(CATEGORY_PREFIX)
                || id.startsWith(MORE_PREFIX);

        return prods ? sorts() : Collections.singletonList(Sort.DEFAULT);
    }

    /**
     * Measured 2026-08-14: {@code votes} is the average rating and the only
     * name the order answers to - {@code rating} and {@code votesAmount} are
     * both ignored, on all three entities this service holds (prods, music,
     * pictures). The default asks for no order at all, rather than spelling out
     * the service's own default - one less name to be wrong about.
     */
    private static String orderFor(Query query) {
        switch (query == null ? Sort.DEFAULT : query.sort()) {
            case TOP:          return ZxartApi.ORDER_TOP;
            case NEWEST:       return ZxartApi.ORDER_NEWEST;
            case ALPHABETICAL: return ZxartApi.ORDER_TITLE;
            default:           return null;
        }
    }

    /**
     * The same question for a tune or a picture, where only {@link
     * ZxartApi#ORDER_TOP} has ever been asked of the endpoint.
     *
     * <b>Enforced here as well as declared in {@link #sortsFor}</b>, and not
     * out of belt-and-braces habit: {@code sortsFor} is what the screen reads
     * to decide which control to show, and this is what decides what actually
     * goes on the wire. An order this service does not recognise is ignored
     * rather than refused, so a caller that never consulted {@code sortsFor} -
     * a future screen, a test, {@code Query.sortedBy} surviving a descent that
     * forgot to reset - would otherwise send {@code date,desc} to {@code
     * export:zxMusic} and get a successful-looking reply in no particular
     * order. Anything but TOP asks for no order at all, which is the one
     * answer that cannot be wrong.
     */
    private static String entityOrderFor(Query query) {
        Sort sort = query == null ? Sort.DEFAULT : query.sort();

        return sort == Sort.TOP ? ZxartApi.ORDER_TOP : null;
    }

    @Override
    public Page open(Shelf shelf, Query query, int page) throws ScrapeException {
        // A new page is a new chance for the tree: last call's refusal is a
        // fact about last call, and remembering it any longer than that filed
        // a whole session's imports under Other. See treeRefusal.
        treeRefusal = null;

        if (SHELF_CATEGORIES.equals(shelf.id())) return categories();

        // Music and Graphics roots: no request, same as Categories - see
        // musicRoots()/graphicsRoots(). Their sub-shelves are handled next,
        // also before tree() - a tune or a picture has no category to
        // resolve a kind from, so neither ever needs the tree at all, unlike
        // every branch below this point.
        if (SHELF_MUSIC.equals(shelf.id())) return musicRoots();
        if (SHELF_GRAPHICS.equals(shelf.id())) return graphicsRoots();
        if (shelf.id().startsWith(MUSIC_PREFIX)) return openMusic(shelf, query, page);
        if (shelf.id().startsWith(GRAPHICS_PREFIX)) return openGraphics(shelf, query, page);

        // Ensured before this shelf's own request, and not lazily inside
        // itemFrom/kindOf as the task brief's own outline first suggested:
        // every test here queues the tree's own reply first and the shelf's
        // second, and the tree has to be the first request actually sent for
        // that to be more than a coincidence. See the class javadoc.
        tree();

        ZxartApi.Ask ask = new ZxartApi.Ask(ZxartApi.PROD)
                .language(language)
                .page(page, PAGE_SIZE);

        // Applies to every branch below, including a "similar" shelf's own
        // resolved-leaf filter - the sort is a property of the page asked for,
        // not of which filter picked its rows.
        String order = orderFor(query);
        if (order != null) ask.order(order);

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

        // Only when the title search itself came up empty - see
        // appendAuthorMatches's own javadoc for why a title search that
        // already found something is never touched.
        if (SHELF_SEARCH.equals(shelf.id()) && page == 0 && items.isEmpty()
                && !query.text().trim().isEmpty()) {
            appendAuthorMatches(items, query.text());
        }

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
        // The same fresh start open() takes - see treeRefusal.
        treeRefusal = null;

        // A music or picture id, told apart from a prod's by the same prefix
        // its shelf carries - see MUSIC_PREFIX's own javadoc. Neither branch
        // touches tree(): the entity's kind is fixed to its own word, not
        // resolved from a category.
        if (id != null && id.startsWith(MUSIC_PREFIX)) {
            return musicItem(idIn(id, MUSIC_PREFIX));
        }
        if (id != null && id.startsWith(GRAPHICS_PREFIX)) {
            return pictureItem(idIn(id, GRAPHICS_PREFIX));
        }

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

        List<Version> versions = versionsFrom(ZxartApi.rows(releaseReply, ZxartApi.RELEASE),
                                              recordingsOf(prodRows.get(0)));

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

    // --- music and pictures: two more entities, no category tree involved ------------

    /** {@link #SHELF_MUSIC}'s two sub-shelves - see the class javadoc's
     *  "Music and Graphics" section for why this makes no request. */
    private Page musicRoots() {
        List<Shelf> found = Arrays.asList(
                new Shelf(MUSIC_PREFIX + "everything", "Everything", Shelf.Accepts.NOTHING),
                new Shelf(MUSIC_PREFIX + "search", "Search", Shelf.Accepts.TEXT));

        return new Page(null, found, 0, Page.UNKNOWN_TOTAL);
    }

    /** {@link #musicRoots()}'s twin, for {@link #SHELF_GRAPHICS}. */
    private Page graphicsRoots() {
        List<Shelf> found = Arrays.asList(
                new Shelf(GRAPHICS_PREFIX + "everything", "Everything", Shelf.Accepts.NOTHING),
                new Shelf(GRAPHICS_PREFIX + "search", "Search", Shelf.Accepts.TEXT));

        return new Page(null, found, 0, Page.UNKNOWN_TOTAL);
    }

    /** One page of tunes - {@code export:zxMusic}, filtered by {@link
     *  ZxartApi#FILTER_MUSIC_SEARCH} on the Search sub-shelf and unfiltered on
     *  Everything. No sub-shelves of its own: unlike a prod's category, a
     *  tune's kind never rolls up into anything to descend into. */
    private Page openMusic(Shelf shelf, Query query, int page) throws ScrapeException {
        ZxartApi.Ask ask = new ZxartApi.Ask(ZxartApi.MUSIC).language(language).page(page, PAGE_SIZE);

        // Only votes,desc, whatever the query asks for - see entityOrderFor.
        String order = entityOrderFor(query);
        if (order != null) ask.order(order);
        if ((MUSIC_PREFIX + "search").equals(shelf.id())) {
            ask.filter(ZxartApi.FILTER_MUSIC_SEARCH, query.text());
        }

        JSONObject reply = api.ask(ask);
        List<Item> items = new ArrayList<>();
        for (JSONObject row : ZxartApi.rows(reply, ZxartApi.MUSIC)) items.add(musicFrom(row));

        return new Page(items, null, page * PAGE_SIZE, ZxartApi.totalOf(reply));
    }

    /** {@link #openMusic}'s twin, for {@code export:zxPicture} and {@link
     *  ZxartApi#FILTER_PICTURE_SEARCH}. */
    private Page openGraphics(Shelf shelf, Query query, int page) throws ScrapeException {
        ZxartApi.Ask ask = new ZxartApi.Ask(ZxartApi.PICTURE).language(language).page(page, PAGE_SIZE);

        // ...and the same for a picture.
        String order = entityOrderFor(query);
        if (order != null) ask.order(order);
        if ((GRAPHICS_PREFIX + "search").equals(shelf.id())) {
            ask.filter(ZxartApi.FILTER_PICTURE_SEARCH, query.text());
        }

        JSONObject reply = api.ask(ask);
        List<Item> items = new ArrayList<>();
        for (JSONObject row : ZxartApi.rows(reply, ZxartApi.PICTURE)) items.add(pictureFrom(row));

        return new Page(items, null, page * PAGE_SIZE, ZxartApi.totalOf(reply));
    }

    /** {@code export:zxMusic}, filtered to one id, plus the author's name -
     *  the two-request shape {@link #item} promises for these entities,
     *  against a prod's three-or-two. */
    private Item musicItem(String id) throws ScrapeException {
        JSONObject reply = api.ask(new ZxartApi.Ask(ZxartApi.MUSIC)
                .language(language)
                .filter(ZxartApi.FILTER_MUSIC_ID, id));

        List<JSONObject> rows = ZxartApi.rows(reply, ZxartApi.MUSIC);
        if (rows.isEmpty()) return null;

        JSONObject row = rows.get(0);
        return musicFrom(row, authorNameOf(row));
    }

    /** {@link #musicItem}'s twin, for {@code export:zxPicture}. */
    private Item pictureItem(String id) throws ScrapeException {
        JSONObject reply = api.ask(new ZxartApi.Ask(ZxartApi.PICTURE)
                .language(language)
                .filter(ZxartApi.FILTER_PICTURE_ID, id));

        List<JSONObject> rows = ZxartApi.rows(reply, ZxartApi.PICTURE);
        if (rows.isEmpty()) return null;

        JSONObject row = rows.get(0);
        return pictureFrom(row, authorNameOf(row));
    }

    /**
     * A list row: no author, for the reason a prod's list row carries no
     * releases - thirty rows would be thirty paced requests for a name
     * nobody has asked to see yet.
     */
    private static Item musicFrom(JSONObject row) {
        return musicFrom(row, null);
    }

    /**
     * A tune as an item.
     *
     * kind is "Music" - the entity's own word, which is what Kinds maps - and
     * the version carries the ogg first and the original second. That order is
     * not cosmetic: Pick.otherFile answers with the first file that is neither
     * a picture nor for the machine, and that is what the pane's Open hands to
     * the phone. The ogg is playable anywhere; a .pt3 is not.
     *
     * No size is stated for either, so Download carries -1 - which is honest
     * and is what a catalogue that does not say looks like.
     *
     * <b>{@code type} names the tracker format and {@code originalFileName}
     * names the file - and they disagree.</b> Measured on {@code
     * Fixtures.MUSIC_ROW}: {@code type} is {@code "PT3"} while the original
     * file is a {@code .mt3}. What decides whether Fuse - or anything else -
     * can open a file is what is inside it, so the format here comes from the
     * filename, exactly as {@code HeadOverHeels.tzx.zip} being a tzx does
     * elsewhere in this feature; {@code type} is kept as the version's own
     * label instead of being discarded, since it is still the truest short
     * name for what the tune actually is.
     *
     * {@code publisher} carries the author's name where given - null on a
     * list row, resolved once by {@link #musicItem} for the pane - since
     * there is nowhere else on {@link Item} to put a fact a prod's own
     * {@code itemFrom} has no equivalent for.
     */
    private static Item musicFrom(JSONObject row, String author) {
        String id = Integer.toString(row.optInt("id", 0));
        int year = row.optInt("year", 0);

        String ogg = row.optString("mp3FilePath", "");
        String original = row.optString("originalUrl", "");

        List<Download> files = new ArrayList<>();
        if (!ogg.isEmpty()) files.add(new Download(ogg, "ogg", -1));
        if (!original.isEmpty()) files.add(new Download(original, extensionOf(original), -1));

        String label = row.optString("type", "");
        Version version = new Version(label.isEmpty() ? null : label,
                                      year > 0 ? Integer.toString(year) : null, files);

        return Item.builder(MUSIC_PREFIX + id)
                .title(ZxartApi.unescape(row.optString("title", "")))
                .year(year > 0 ? Integer.toString(year) : null)
                .publisher(author)
                .kind(Kinds.MUSIC)
                // no legalStatus on this entity - nothing to state
                // no rendered picture for a tune - imagesUrls does not exist here
                .versions(Collections.singletonList(version))
                // youtubeId lives on a prod, not a tune
                .build();
    }

    /** A list row: no author - see {@link #musicFrom(JSONObject)}. */
    private static Item pictureFrom(JSONObject row) {
        return pictureFrom(row, null);
    }

    /**
     * A picture as an item.
     *
     * kind is "Graphics", zxPicture's own root word. The version carries the
     * rendered PNG first and the original {@code .scr} second - the same
     * reason a tune lists its ogg first: {@link Pick#otherFile} answers with
     * the first file that is neither a picture nor for the machine, except
     * here <em>both</em> files are pictures, so {@link Pick#otherFile} keeps
     * the first one it sees rather than falling past it - the PNG, which is
     * what the pane's Open should hand to the phone rather than a raw
     * Spectrum screen dump nothing but this app understands.
     *
     * <b>The PNG's format is stated, not derived - the one file in this whole
     * feature whose format cannot be read off the thing it describes.</b>
     * {@code imageUrl} carries no extension at all ({@code
     * zximages/id=2232;border=0;pal=srgb;type=standard;zoom=1}) and the
     * service sends no {@code Content-Type} either. Fetched and checked by
     * hand on 2026-08-14: the reply is 200 with the PNG magic number {@code
     * 89 50 4E 47 0D 0A 1A 0A} and nothing else to go on. So {@code "png"} is
     * asserted here from that one measurement rather than parsed from
     * anything - see {@code ZxartCatalogueTest} and this feature's own
     * progress notes for the same fact recorded twice on purpose, because the
     * next reader's instinct will be to look at the url for it and find
     * nothing there.
     */
    private static Item pictureFrom(JSONObject row, String author) {
        String id = Integer.toString(row.optInt("id", 0));
        int year = row.optInt("year", 0);

        String rendered = row.optString("imageUrl", "");
        String original = row.optString("originalUrl", "");

        List<Download> files = new ArrayList<>();
        // "png", stated - see this method's own javadoc.
        if (!rendered.isEmpty()) files.add(new Download(rendered, "png", -1));
        if (!original.isEmpty()) files.add(new Download(original, extensionOf(original), -1));

        Version version = new Version(null, year > 0 ? Integer.toString(year) : null, files);

        return Item.builder(GRAPHICS_PREFIX + id)
                .title(ZxartApi.unescape(row.optString("title", "")))
                .year(year > 0 ? Integer.toString(year) : null)
                .publisher(author)
                .kind(Kinds.GRAPHICS)
                // no legalStatus on this entity
                .pictureUrl(rendered.isEmpty() ? null : rendered)
                .versions(Collections.singletonList(version))
                // youtubeId lives on a prod, not a picture
                .images(rendered.isEmpty() ? Collections.emptyList()
                                            : Collections.singletonList(rendered))
                .build();
    }

    /**
     * Folds a matching author's own games into an already-fetched Search
     * page, in place - two more requests, and only when the page's title
     * search came up with nothing at all.
     *
     * <b>Why the title search alone misses this.</b> {@code
     * zxProdSearch=Zosya} answers nothing: a prod's title rarely names the
     * group that made it. But {@code export:author/filter:authorSearch=}
     * resolves the same word to an author row - measured 2026-08-15 against
     * "Zosya", which found "ZOSYA entertainment", id 351455 - and that id
     * then narrows {@link #PROD} via {@link ZxartApi#FILTER_AUTHOR} to
     * exactly the four games the group made. So a name search is two
     * requests chained through an id, never one filter on prods directly -
     * there is no such filter to ask for.
     *
     * <b>Only when the title search is empty - never as well as it.</b>
     * {@code authorSearch} is a substring match, not an exact one: measured
     * the same day, {@code head} matches 21 authors including "HeadSoft",
     * {@code dizzy} matches "DizZy", {@code elite} matches "britelite". Any
     * of those folded into a search that had already found the title
     * everyone typed the word for - Head Over Heels, a Dizzy game, an Elite
     * release - would splice an unrelated author's whole catalogue into a
     * search that already had its real answer. Gating on the title search's
     * own page being empty is what keeps this a rescue for a search that
     * found nothing rather than noise on one that already worked; {@link
     * #open} only calls this when {@code items} is empty.
     *
     * <b>Page 0 only, and {@code total} is untouched.</b> A search this
     * triggers on has nothing to page through in the first place - an empty
     * page 0 - so there is no later page whose own arithmetic could be
     * disturbed by rows spliced in here.
     *
     * <b>A refusal here is swallowed, deliberately</b> - the same call
     * {@link #tree()} makes for its own failure. This is a bonus on top of a
     * search that has already answered "nothing"; losing the fold-in must
     * not turn that into a thrown exception instead of the same honest empty
     * shelf the search would have been without it.
     */
    private void appendAuthorMatches(List<Item> items, String text) {
        List<Item> found;
        try {
            found = authorGamesFor(text);
        } catch (ScrapeException failed) {
            return;
        }

        Set<String> already = new HashSet<>();
        for (Item item : items) already.add(item.id());

        for (Item item : found) if (already.add(item.id())) items.add(item);
    }

    /** {@code text} resolved to an author, then that author's own games - or
     *  empty, when the word names no author at all. See {@link
     *  #appendAuthorMatches}, its only caller, for the reasoning. */
    private List<Item> authorGamesFor(String text) throws ScrapeException {
        JSONObject authorReply = api.ask(new ZxartApi.Ask(ZxartApi.AUTHOR)
                .language(language)
                .page(0, 1)
                .filter(ZxartApi.FILTER_AUTHOR_SEARCH, text));

        List<JSONObject> authorRows = ZxartApi.rows(authorReply, ZxartApi.AUTHOR);
        if (authorRows.isEmpty()) return Collections.emptyList();

        int authorId = authorRows.get(0).optInt("id", 0);

        JSONObject prodReply = api.ask(new ZxartApi.Ask(ZxartApi.PROD)
                .language(language)
                .page(0, PAGE_SIZE)
                .filter(ZxartApi.FILTER_AUTHOR, Integer.toString(authorId)));

        List<Item> games = new ArrayList<>();
        for (JSONObject row : ZxartApi.rows(prodReply, ZxartApi.PROD)) games.add(itemFrom(row));
        return games;
    }

    /**
     * The first {@code authorIds} entry's name, or null.
     *
     * One request, one id: comma-joining several into one filter was tried
     * against the live service and returned a single row for three ids asked
     * for, so there is no batch to use instead. Only the first author is
     * asked for the same reason a prod's list rows carry no releases - most
     * tunes and pictures here name one author anyway, and a name is a detail
     * for the pane, not a fact worth a request per collaborator.
     */
    private String authorNameOf(JSONObject row) throws ScrapeException {
        JSONArray authors = row.optJSONArray("authorIds");
        if (authors == null || authors.length() == 0) return null;

        JSONObject reply = api.ask(new ZxartApi.Ask(ZxartApi.AUTHOR)
                .language(language)
                .filter(ZxartApi.FILTER_AUTHOR, Integer.toString(authors.optInt(0, 0))));

        List<JSONObject> rows = ZxartApi.rows(reply, ZxartApi.AUTHOR);
        return rows.isEmpty() ? null : ZxartApi.unescape(rows.get(0).optString("title", ""));
    }

    /**
     * The extension of a url's own filename, lower or upper case as given -
     * {@link Download}'s constructor lower-cases it - or empty when there is
     * none. A dot has to fall after the last {@code /} to count: a folder
     * name with a dot in it must not be misread as an extension.
     */
    private static String extensionOf(String url) {
        if (url == null) return "";

        String noQuery = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        int dot = noQuery.lastIndexOf('.');
        int slash = noQuery.lastIndexOf('/');

        return dot > slash ? noQuery.substring(dot + 1) : "";
    }

    // --- the category tree, held for the session ------------------------------------

    /**
     * The tree, fetched on first need and held once it has arrived - or an
     * empty one, for a caller that would rather have a row with no kind than
     * no row.
     *
     * <b>A failure here is swallowed, deliberately - but never kept.</b> Every
     * row still has to be built when the tree cannot be: with no kind, which
     * is a real answer ({@code Kinds.folderFor(null)} is {@code Other}) and
     * not a failure, so a {@link ScrapeException} reaching {@link #kindOf}
     * from here would turn a working shelf into an error screen over nothing
     * worse than a folder name. What is remembered is only the refusal this
     * <em>call</em> met - see {@link #treeRefusal} - so the request is not
     * repeated per row and is repeated on the next shelf opened.
     *
     * Callers that can honestly fail use {@link #treeOrRefuse()} instead.
     */
    private ZxartTree tree() {
        try {
            return treeOrRefuse();
        } catch (ScrapeException refused) {
            return ZxartTree.from(Collections.<JSONObject>emptyList());
        }
    }

    /**
     * The tree, or the refusal that stopped it arriving.
     *
     * For {@link #categories()}, whose whole page <em>is</em> the tree: an
     * empty shelf there says "this archive has no categories", which is a
     * claim about zxart rather than about one request, and it comes with no
     * <b>Try again</b> because nothing failed as far as the screen could tell.
     * Every other refusal in this app draws a failure row; this one used to
     * draw {@code Nothing here.}
     */
    private ZxartTree treeOrRefuse() throws ScrapeException {
        if (tree != null) return tree;
        if (treeRefusal != null) throw treeRefusal;

        try {
            JSONObject reply = api.ask(new ZxartApi.Ask(ZxartApi.CATEGORY)
                    .language(language)
                    .page(0, TREE_PAGE_SIZE)
                    .flag(ZxartApi.FILTER_CATEGORIES_ALL));

            tree = ZxartTree.from(ZxartApi.rows(reply, ZxartApi.CATEGORY));
        } catch (ScrapeException refused) {
            treeRefusal = refused;
            throw refused;
        }

        return tree;
    }

    /** The Categories shelf: nine sub-shelves, no items, no page beyond zero -
     *  the tree does not paginate. Throws rather than answering an empty page
     *  when the tree was refused - see {@link #treeOrRefuse()}. */
    private Page categories() throws ScrapeException {
        List<Shelf> found = new ArrayList<>();

        for (ZxartTree.Node root : treeOrRefuse().roots()) {
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

        // videoLink straight off the same row, no extra request: a search
        // hit and a single-id lookup answer the identical shape, measured
        // (see ZxartApi.watchUrlOf) - so a list row already carries this
        // before Catalogue.item is ever asked, which is what lets
        // CataloguePane decide whether to show the icon the moment a title
        // is tapped, the same way it already decides similarButton.
        //
        // No developer, description or rating either - the same measurement
        // that found no publisher found nothing to read for any of these
        // three on a prod, and zxart's own images are real: picturesOf reads
        // every entry of imagesUrls, not only the first the row draws.
        List<String> images = picturesOf(row);

        return Item.builder(id)
                .title(ZxartApi.unescape(row.optString("title", "")))
                .year(year > 0 ? Integer.toString(year) : null)
                .kind(kindOf(leaf))
                // The leaf's own title, a finer word than kindOf's root -
                // "Arcade" beside "Games", say - free once the tree is
                // fetched, which kindOf already costs. Null for the same
                // -1 kindOf itself answers null for.
                .category(leaf < 0 ? null : tree().titleOf(leaf))
                .availability(availabilityOf(row))
                .pictureUrl(images.isEmpty() ? null : images.get(0))
                .versions(versions)
                .videoLink(ZxartApi.watchUrlOf(row))
                .images(images)
                .build();
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

    /** Every entry of {@code imagesUrls}, in the record's own order - the
     *  first is the rendered loading screen, present on 100% of the
     *  measured slice; empty for a prod with none. */
    private static List<String> picturesOf(JSONObject row) {
        List<String> found = new ArrayList<>();
        JSONArray images = row.optJSONArray("imagesUrls");

        for (int at = 0; images != null && at < images.length(); at++) {
            String url = images.optString(at, null);
            if (url != null) found.add(url);
        }

        return found;
    }

    // --- one prod's releases, fetched only by item() ----------------------------------

    /**
     * Every release, in the order the reply lists them - nothing here
     * re-sorts: that order is zxart's own statement about which came first,
     * exactly as {@code ZxInfoCatalogue.versions} treats ZXDB's.
     *
     * <b>{@code recordings} are hung off the first release, the same seam
     * {@code ZxInfoCatalogue.versions} uses for the same reason</b>: {@link
     * Pick#recording} looks across every version a prod has, so which one
     * carries the file is only ever a question of where to put it, never of
     * which the pane will find. A prod with no releases at all but a
     * recording - not measured here, but not ruled out either - gets a
     * version of nothing else, so the recording is never silently dropped
     * for want of somewhere to hang it.
     */
    private static List<Version> versionsFrom(List<JSONObject> releases,
                                              List<Download> recordings) {
        List<Version> found = new ArrayList<>();

        for (JSONObject release : releases) {
            List<Download> files = filesFor(release);
            if (found.isEmpty()) files.addAll(recordings);

            found.add(new Version(labelFor(release), yearOf(release), files));
        }

        if (found.isEmpty() && !recordings.isEmpty()) {
            found.add(new Version(null, null, recordings));
        }

        return found;
    }

    /**
     * The recording, if the prod has one - its own {@code rzx} array, never
     * a release's file.
     *
     * Measured against Licence to Kill (id 92668): its entry carries {@code
     * rzx:["https://zxart.ee/release/id:554313/mode:download/filename:lice
     * ncetokill.zip"]} sitting apart from {@code releasesIds} entirely, on
     * the prod row itself - so this reads no more than {@link #item} already
     * fetched, no request of its own. {@link Pick#isRecording} tells a
     * recording from a game by its format alone, so this states {@code
     * "rzx"} rather than reading the url's own extension: Licence to Kill's
     * own link ends plain {@code .zip}, not {@code .rzx.zip}, the same
     * reasoning that states {@code "png"} for a rendered picture in {@link
     * #pictureFrom} regardless of what its url happens to end in.
     */
    private static List<Download> recordingsOf(JSONObject row) {
        List<Download> found = new ArrayList<>();
        JSONArray rzx = row.optJSONArray("rzx");

        for (int at = 0; rzx != null && at < rzx.length(); at++) {
            String url = rzx.optString(at, "");
            if (!url.isEmpty()) found.add(new Download(url, "rzx", -1));
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
     *
     * <b>Mutable, on purpose</b> - {@link #versionsFrom} folds a recording
     * into the first release's own list this way, the same as {@code
     * ZxInfoCatalogue.files} does for ZXDB's.
     */
    private static List<Download> filesFor(JSONObject release) {
        String url = release.optString("file", "");
        if (url.isEmpty()) return new ArrayList<>();

        JSONArray formats = release.optJSONArray("releaseFormat");
        String format = formats != null && formats.length() > 0 ? formats.optString(0, "") : "";

        JSONArray structure = release.optJSONArray("releaseStructure");
        long size = -1;

        if (structure != null && structure.length() > 0) {
            JSONObject first = structure.optJSONObject(0);
            if (first != null) size = first.optLong("size", -1);
        }

        List<Download> found = new ArrayList<>();
        found.add(new Download(url, format, size));
        return found;
    }
}
