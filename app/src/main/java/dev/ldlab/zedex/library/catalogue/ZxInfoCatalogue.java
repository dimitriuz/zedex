package dev.ldlab.zedex.library.catalogue;

import android.net.Uri;
import android.util.Log;

import dev.ldlab.zedex.library.scrape.Http;
import dev.ldlab.zedex.library.scrape.Pace;
import dev.ldlab.zedex.library.scrape.ScrapeException;
import dev.ldlab.zedex.library.scrape.ZxInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * ZXInfo, the API over ZXDB, as something to browse.
 *
 * A class of its own rather than more methods on {@code ZxInfo}: that one is
 * already the longest file in the package and the two answer different
 * questions - one is asked about a file somebody has, this one about the
 * 39,666 they do not. They share the hosts and the pacing, which is the part
 * that must not be duplicated, and nothing else.
 *
 * <b>Paced through {@link Pace}, per host.</b> There is no published rate
 * limit; the API asks clients to identify themselves and behave. This address
 * was blocked once at the network layer for "behaviour patterns" and it took
 * an email to lift.
 *
 * <b>Nothing this class sends narrows a search.</b> PENTAGON is a sibling of
 * ZXSPECTRUM in ZXInfo's scheme rather than a variant of it, so a machine filter
 * silently drops the Pentagon demoscene - most of what arrives as .trd and .scl.
 * Every filter can only lose the right answer. {@code ZxInfoCatalogueTest}'s
 * {@code assertNoFilter} greps the URL of every shelf for one.
 *
 * <b>One shelf is filtered anyway, and no URL says so.</b> Surprise me asks
 * {@code games/random/{total}}, whose own documentation says it draws from six
 * genre categories - adventure, arcade, casual, game, sport and strategy - and
 * only from entries carrying both a loading and an in-game screen. That is
 * baked into the endpoint, so nothing is sent and nothing can be asserted: it is
 * written down here because it cannot be tested. It costs a real behaviour this
 * shelf used to have - a surprise can no longer be a utility, a demo, or
 * anything with no picture, where the old whole-database draw could be all
 * three. Taken as the trade for an endpoint that genuinely resamples, since
 * a surprise that answers the same ten entries twice is not one. Every other
 * shelf still reaches the whole database.
 *
 * <b>mode=compact.</b> Measured on one game: 10,286 bytes against full's
 * 45,318, with every field this app reads byte-identical. tiny has no
 * controls.
 *
 * <h3>What each shelf actually asks for</h3>
 *
 * <b>The specification is a static document and reading it is free.</b> This
 * class was written without it, on the grounds that a bare request to that
 * host is what lost this app its address the first time - which confused two
 * different things. The address was lost to unidentified calls against the
 * <em>API</em>; {@code swagger_v3.yaml} is a document, not a call against it,
 * and fetching it costs nothing and tells you what every parameter here is
 * worth. Guessing instead shipped an A-Z built out of a search because an
 * endpoint "that may or may not exist" was judged not worth looking up. It
 * existed. Look it up first; measure what it does after.
 *
 * Each shelf below therefore says what the specification states and what has
 * actually been seen from the live service, which are not the same thing - a
 * parameter this service does not know is <em>ignored</em>, not refused:
 *
 * <ul>
 *   <li><b>Search</b> is {@code search?query=&mode=compact&size=&offset=}.
 *       <b>Three of those four are proven and the fourth was not.</b> {@code
 *       query}, {@code mode=compact} and {@code size} are the shape {@code
 *       ZxInfo.byName} has been using against the live service for as long as
 *       there has been a ZXInfo provider - though not this exact string:
 *       {@code byName} also sends {@code titlesonly=true}, which a catalogue
 *       must not, and it asks for one page and so <b>sends no {@code offset}
 *       at all</b>. This class's own first live call proved the reply's shape
 *       - 30 rows of a stated 153, and the two-host rule visible inside that
 *       single reply, one entry's picture under {@code /zxdb/} and the next
 *       two under {@code /zxscreens/} - and, being page zero, said nothing
 *       whatever about {@code offset}. Asked later: it is a <b>page number</b>
 *       and this class had been sending a row number. See {@link
 *       #searchFor}.</li>
 *   <li><b>A-Z</b> opens onto twenty-seven sub-shelves - one per letter, and
 *       one for {@code #}, which is this service's own argument for the titles
 *       that begin with a digit and which is drawn as {@code 0-9}. There are
 *       839 of those, measured, and until the twenty-seventh shelf existed
 *       every one of them was in the database and out of the app: A-Z was a
 *       twenty-six-way partition of a set with twenty-seven parts. Each shelf
 *       asks <b>{@code games/byletter/{letter}}</b> - an endpoint of
 *       the service's own. Sub-shelves rather than a letter picker of the
 *       screen's own: a page may carry shelves, {@link #genres} already works
 *       that way, and the tab already knows how to descend into one - a shelf
 *       needing a widget nothing else needs is a seam in the wrong place.
 *       <b>This was a search until the specification was read.</b> It sent
 *       {@code query=<letter>&sort=title_asc} on a guess that the endpoint
 *       "may or may not exist". It does, and it was in the documentation all
 *       along. The guess that mattered was {@code query=}, which is a
 *       <em>full-text</em> match over the whole record and not "title begins
 *       with" - so the Q shelf was every entry mentioning a Q anywhere, in an
 *       order chosen by relevance. Worth being exact about the other one:
 *       {@code title_asc} turns out to be a real value, one of the six the
 *       {@code sort} enum lists, so that guess was right. It being right did
 *       not help - a correct sort over the wrong rows is still the wrong
 *       shelf, and it is why this looked like it worked. The endpoint needs no
 *       sort at all: it comes back alphabetical. See {@link #letterFor}.</li>
 *   <li><b>Newest</b> asks for {@code sort=date_desc}, and <b>the sort is
 *       honoured</b> - verified 2026-08-10, because an ignored sort parameter
 *       answers 200 with plausible rows and looks exactly like one that
 *       worked. Thirty rows came back, the first ten all dated 2026 with
 *       descending ids. Read from the years rather than from a second
 *       unsorted request: a list whose first rows are all this year is
 *       sorted.</li>
 *   <li><b>Surprise me</b> asks <b>{@code games/random/{total}}</b>, an
 *       endpoint of the service's own, and <b>it resamples</b> - two identical
 *       requests answered thirty entries each with not one id in common. It
 *       used to send {@code search?offset=random}, which returned the identical
 *       ten twice and had to be capped at one page for that reason. That cap
 *       went with the search and a different one took its place: this shelf
 *       stops at {@link #RANDOM_PAGES} pages, three hundred games, because a
 *       list nothing ever ends is the crawler shape that lost this app its
 *       address once. Its page reports {@link Page#UNKNOWN_TOTAL} - the total
 *       that comes back is the 10,000 cap, and nothing here is walking through
 *       an ordering to be counted against - so nothing measures anything with
 *       this shelf either. See {@link #randomFor}.</li>
 *   <li><b>Similar games</b> is not a declared shelf at all: it is built on
 *       demand from an entry's id by {@link #similarTo}, offered by whatever
 *       has that entry in front of somebody, and asks
 *       <b>{@code games/morelikethis/{game-id}}</b>. One page of thirty, since
 *       that endpoint takes a size and no offset. The same mechanism as a
 *       letter and a genre - an id carrying an id - which is why nothing on the
 *       screen had to learn anything new. See {@link #likeFor}.</li>
 *   <li><b>Categories</b> reads {@code metadata/} and hands the genres back as
 *       sub-shelves. Verified 2026-08-10, and it corrected the name: the array
 *       is <b>{@code genretypes}</b>, plural, beside {@code machinetypes} and
 *       {@code features} - see {@link #genres}, which reads either. A genre
 *       list that comes back empty falls back to {@link Kinds#ZXDB_VOCABULARY}
 *       rather than opening onto nothing.</li>
 *   <li><b>A genre sub-shelf</b> searches {@code genretype=…}, singular, which
 *       is what {@code /search} takes - and <b>the filter is applied</b>,
 *       verified 2026-08-10. {@code genretype=Utility} answered with a total of
 *       6,436 and thirty rows every one of which is a Utility. The total is
 *       the part that proves it: an unfiltered search over this database
 *       reports the 10,000 cap below, so a total beneath the cap cannot be an
 *       unfiltered one. Worth a request because a wrong parameter name here
 *       would have been <em>ignored rather than refused</em> - a category
 *       quietly containing the entire database, which reads on screen as a
 *       category that works.</li>
 * </ul>
 *
 * <b>A total of exactly 10,000 is a cap and not a count - do not print it.</b>
 * The unfiltered Newest shelf reported one, where the database holds about
 * 39,666. It is Elasticsearch's default limit on counting and, at the same
 * time, about as deep as a paged search may go - so it is the right number to
 * page against, and {@code Page.hasMore} stops the list within a page of the
 * window rather than walking on for ever, which is why it is passed through
 * unaltered. Not exactly at it: thirty does not divide ten thousand, so the
 * last request this makes is {@code size=30&offset=333} - page 333, rows 9,990
 * to 10,019 - which reaches a little past the window and may be refused. One
 * refusal at the bottom of ten thousand rows is a good trade for arithmetic
 * nobody has to maintain, and it is written down here rather than guarded
 * against because it has not been seen. It is a lie as a result count, and the lie is worst on the broad
 * shelves where somebody is most likely to read one. Whatever draws these rows
 * must treat the cap as "at least this many" and not as "this many". A shelf
 * narrow enough to have a real total gets one: the search that proved this
 * class answered 153, and the Utility genre above 6,436.
 */
public final class ZxInfoCatalogue implements Catalogue {

    private static final String TAG = "Zedex";

    /** What a page of the grid asks for. Big enough that a screen's worth is
     *  one request, small enough that flinging past it does not pull a
     *  megabyte of JSON that nobody reads. */
    private static final int PAGE_SIZE = 30;

    /**
     * What a shelf that will throw most of a page away asks for instead - see
     * {@link #sizeFor}.
     *
     * The two unpaged shelves keep {@link #PAGE_SIZE} whatever the query says.
     * Surprise me and More like this each hand back one page of their own
     * choosing and cannot be asked for the next one, so the size is not a
     * stride there but a statement of how big those shelves are, and a filter
     * is no reason to change what they mean.
     */
    private static final int SIFTING_PAGE_SIZE = 100;

    static final String SHELF_SEARCH = "search";
    static final String SHELF_LETTER = "letter";
    static final String SHELF_NEWEST = "newest";
    static final String SHELF_RANDOM = "random";
    static final String SHELF_GENRES = "genres";

    /** A sub-shelf yielded by {@link #SHELF_GENRES} carries the genre as its
     *  id behind this prefix, so {@link #open} can tell one from a declared
     *  shelf without a second field. */
    private static final String GENRE_PREFIX = "genre:";

    /** The same trick for {@link #SHELF_LETTER}'s own twenty-seven: the
     *  letter is the id behind this, so {@link #open} can tell "the A-Z shelf"
     *  from "the letter Q" without a second field. */
    private static final String LETTER_PREFIX = "letter:";

    /** And again for {@link #similarTo}, whose id carries the entry every row
     *  on the shelf is meant to be like. */
    private static final String MORE_PREFIX = "more:";

    /**
     * How far Surprise me goes: ten pages, three hundred games.
     *
     * <b>An endless scroll is a crawler; a deliberate act repeated is a
     * client.</b> {@code games/random/30} resamples, so nothing in the service's
     * answers ever ends this shelf - no total, and no page that can come back
     * empty. Left alone it pages for ever, one paced request per fling, against
     * an address that was blocked once at the network layer for "behaviour
     * patterns" rather than for volume. Every other shelf here has a visible
     * floor: a broad search stops at page 334, the Z shelf at 57. This one is
     * the only list in the app that could be scrolled all night, which is
     * exactly the shape that got the address taken away. Backing out and tapping
     * the shelf again draws a fresh three hundred, and that is a person asking
     * rather than a fling continuing.
     *
     * <b>The API request is the small half of a page.</b> Thirty rows are one
     * paced call <em>and up to thirty unpaced thumbnail fetches</em> against the
     * media hosts - {@code Thumbnails} is deliberately not a {@link Pace}
     * client, on the grounds that a cover is a static file rather than an API
     * call. That reasoning holds for a shelf somebody revisits, where the covers
     * are already decoded; on this one every draw is new, so the cache misses by
     * construction and the real cost of a fling is about thirty-one requests
     * rather than one.
     *
     * <b>Ten pages is a choice and not a measurement</b>, because the
     * measurement cannot be had: this endpoint draws only from six genre
     * categories and only from entries carrying both screens, so the pool is
     * smaller than the database and nobody has counted it. Somewhere past a few
     * hundred rows a person is being surprised by games they have already been
     * shown. Three hundred is more than anybody scrolls in one sitting and small
     * enough that the pool's unknown size does not matter.
     */
    private static final int RANDOM_PAGES = 10;

    /** What A-Z opens onto. Latin only, and deliberately: this is a search
     *  term handed to a service whose titles are indexed in it, not an
     *  alphabet for the phone's language. */
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /**
     * The twenty-seventh shelf, and the service's own name for it.
     *
     * {@code GET /games/byletter/{letter}} documents its argument as "a-z - or
     * # for numbers (case insensitive)", so a title beginning with a digit is
     * reachable by exactly one character and by no letter at all. Without this
     * shelf those entries are in the database and out of the app: A-Z is a
     * twenty-six-way partition of a set that has twenty-seven parts.
     */
    private static final String DIGITS = "#";

    /**
     * What that shelf is called on screen.
     *
     * {@code #} is what the path takes and not what a person reads - it is a
     * punctuation mark that means "number" to a programmer and nothing to
     * anybody else. Not a string resource, for the same reason the letters are
     * not: digits are digits in every language this app speaks, and a shelf
     * label is the catalogue's own word rather than the app's.
     */
    private static final String DIGITS_LABEL = "0-9";

    /**
     * Wrappers rather than formats - what a file is inside one of these is
     * what decides whether this app can open it, so a {@code .tzx.zip} is a
     * tzx. See {@code Catalogue.Download#format}.
     */
    private static final List<String> WRAPPERS = Arrays.asList("zip", "gz");

    /** What a thumbnail can be drawn from. A record's first screen is
     *  sometimes a raw {@code .scr} memory dump, which an image view renders
     *  as nothing at all - indistinguishable on screen from a game with no
     *  picture. Turning one into a bitmap is {@code ScreenDump}'s job and
     *  belongs at import, not in a grid row. */
    private static final List<String> PICTURES = Arrays.asList("png", "jpg", "jpeg", "gif");

    private final Http http;

    public ZxInfoCatalogue(Http http) {
        this.http = http;
    }

    @Override
    public String name() {
        return "ZXInfo";
    }

    /** No credentials to be missing - which is why this is the catalogue that
     *  ships first. */
    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public List<Shelf> shelves() {
        return Arrays.asList(
                new Shelf(SHELF_SEARCH, "Search", Shelf.Accepts.TEXT),
                // Takes nothing: the letter is chosen by descending into one
                // of the twenty-seven shelves this yields, not by handing it a
                // query. See open().
                new Shelf(SHELF_LETTER, "A-Z", Shelf.Accepts.NOTHING),
                new Shelf(SHELF_GENRES, "Categories", Shelf.Accepts.NOTHING),
                new Shelf(SHELF_NEWEST, "Newest", Shelf.Accepts.NOTHING),
                new Shelf(SHELF_RANDOM, "Surprise me", Shelf.Accepts.NOTHING));
    }

    /**
     * {@code DEFAULT} and {@code TOP}, and nothing more.
     *
     * <b>{@code sort=score_desc} is honoured</b> - measured in Task 1: the same
     * query against the same index snapshot, one shard, answered a different
     * order with it than without, a tied group at score 77.102264 reordering in
     * a way a shard artefact cannot explain. It is relevance rather than a
     * community rating - this service keeps no votes - and "Top" is still the
     * honest word for it: it is the best this index can say about which result
     * matters most, exactly as zxart's votes are the best that archive can say.
     *
     * <b>{@code NEWEST} stays a shelf, not a sort.</b> It already ships as one,
     * using {@code sort=date_desc}, and turning it into a second declared sort
     * here would be two ways to ask this catalogue for the same ordering.
     *
     * <b>No {@code ALPHABETICAL}.</b> Nobody has measured a title sort against
     * this service, and this seam does not guess at a parameter the way zxart's
     * {@code order:title,asc} was guessed at and then measured - see {@code
     * ZxartApi}'s own history of names that looked plausible and were ignored.
     */
    @Override
    public List<Sort> sorts() {
        return Arrays.asList(Sort.DEFAULT, Sort.TOP);
    }

    /**
     * {@code &sort=score_desc} for {@link Sort#TOP}, nothing for {@link
     * Sort#DEFAULT} - one less name to be wrong about, exactly as {@code
     * ZxartCatalogue.orderFor} leaves its own default unspelled.
     *
     * <b>Only where a shelf is actually a search.</b> The plain search and a
     * genre both go through {@link #searchFor}, which is what the measurement
     * in {@link #sorts()} was taken against; {@link #letterFor}, {@link
     * #randomFor} and {@link #likeFor} are different endpoints this was never
     * asked of, and the letter shelf already comes back alphabetical with no
     * sort at all.
     */
    private static String sortParam(Query query) {
        return query != null && query.sort() == Sort.TOP ? "&sort=score_desc" : "";
    }

    @Override
    public Page open(Shelf shelf, Query query, int page) throws ScrapeException {
        if (SHELF_GENRES.equals(shelf.id())) return genres();

        // Twenty-six shelves and no request. A page carrying shelves rather
        // than items is the mechanism Categories already uses; this is the
        // second thing to rest on it, which is worth something in itself,
        // since zxart's whole category tree will.
        if (SHELF_LETTER.equals(shelf.id())) return letters();

        // More like this is one page, and the second one is empty on purpose.
        //
        // The endpoint takes a size and no offset - it answers with the best
        // matches and there is no way to ask for the next ones - so page two of
        // this shelf would be page one again, the same thirty rows appended to
        // the thirty already on screen. And nothing stops it, because the total
        // is unknown and Page.hasMore reads unknown-plus-non-empty as "there is
        // more": an endless grid of duplicates, one paced request per fling,
        // against the host that blocked this app once already.
        //
        // Ended here rather than in hasMore, whose contract is right and which
        // other shelves depend on. The only honest thing to do with a shelf
        // that cannot page is to stop after the first one; the total stays
        // UNKNOWN_TOTAL because there genuinely is no count, and it is the
        // empty second page that ends the list. It costs no request: this
        // returns before pathFor is reached.
        if (shelf.id().startsWith(MORE_PREFIX) && page > 0) {
            return new Page(null, null, page * sizeFor(query), Page.UNKNOWN_TOTAL);
        }

        // And Surprise me stops at three hundred - see RANDOM_PAGES for why
        // there is a bound at all and why it is this one.
        //
        // The same mechanism as the guard above and for the same reason: an
        // empty page is what every unpaged shelf here ends with, Page.hasMore
        // reads it as the end whatever a total claims, and that contract is
        // right and is not being touched. It costs no request either - this
        // returns before pathFor is reached. The bound is on the page number
        // rather than on anything remembered, so backing out to the roots and
        // opening the shelf again starts at page zero and draws a fresh three
        // hundred: CatalogueView.restart() sets page to 0 for every descent,
        // and this endpoint resamples, so re-opening resumes nothing.
        if (SHELF_RANDOM.equals(shelf.id()) && page >= RANDOM_PAGES) {
            return new Page(null, null, page * sizeFor(query), Page.UNKNOWN_TOTAL);
        }

        // Two shelves cannot count. A random one because the rows are drawn
        // afresh every time, so nothing the service reports says how much of it
        // this list has seen - what comes back is Elasticsearch's 10,000 cap
        // anyway; a more-like-this one because it hands back one page of the
        // best matches, and a count of everything Elasticsearch thought similar
        // is a number the list can never reach - drawn beside the shelf's name
        // it reads as a shelf that stopped early.
        boolean countable = !SHELF_RANDOM.equals(shelf.id())
                && !shelf.id().startsWith(MORE_PREFIX);

        // The size and the stride are one decision, taken once: offset is a
        // page number at this endpoint, so a shelf that asks for a hundred rows
        // and counts by thirty walks straight past its own end.
        int size = sizeFor(query);

        return rows(pathFor(shelf, query, page, size), page * size, countable);
    }

    /**
     * How many rows to ask for at once.
     *
     * <b>Bigger for a shelf that is going to sift, and measured rather than
     * chosen.</b> The app filters by format itself - this service has no such
     * parameter and ignores an invented one rather than refusing it - so a live
     * TRD filter keeps 4.3% of what arrives, 1.3 rows out of every thirty. What
     * that costs is mostly the round trip and the 250ms this app waits between
     * calls, not the bytes: thirty entries take about 0.45s and a hundred about
     * 0.53s, so three hundred entries read ten pages at a time take 4.5s and
     * three pages at a time take 1.6s - the same entries, the same rows kept,
     * roughly the same bytes, a third of the wall clock.
     *
     * Thirty stays for an ordinary shelf, where every row that arrives is drawn
     * and a bigger page would only be more bytes for rows nobody has scrolled
     * to yet.
     */
    private static int sizeFor(Query query) {
        return query != null && query.isSifting() ? SIFTING_PAGE_SIZE : PAGE_SIZE;
    }

    @Override
    public Item item(String id) throws ScrapeException {
        JSONObject reply = object(ask("games/" + Uri.encode(id) + "?mode=compact"));
        JSONObject source = reply == null ? null : reply.optJSONObject("_source");

        return source == null ? null : itemFrom(id, source);
    }

    /**
     * The way in to "games like this one", built round the entry's own id.
     *
     * Nothing is fetched here - a shelf is a way in, and the request happens
     * when somebody opens it, which is what lets this be called while a pane is
     * being laid out. The id travels inside the shelf's own id, exactly as a
     * letter and a genre do, so the screen that opens it needs to know nothing
     * new.
     *
     * Null for an entry with no id, which is not a shape the service sends -
     * every hit carries {@code _id} and {@link #rows} drops the ones that do
     * not - but is cheaper to refuse than to send {@code games/morelikethis/}
     * with nothing on the end of it.
     */
    @Override
    public Shelf similarTo(Item item, String label) {
        if (item == null || item.id() == null || item.id().isEmpty()) return null;

        return new Shelf(MORE_PREFIX + item.id(), label, Shelf.Accepts.NOTHING);
    }

    /** True: a search hit's own {@code _source} carries {@code releases} and
     *  {@code additionalDownloads} byte-identical to the record's, so {@link
     *  #itemFrom} builds every row's {@link Item#formats()} without a request
     *  of its own - see that method's javadoc. That is the whole reason a
     *  format filter over this catalogue costs nothing. */
    @Override
    public boolean knowsFormats() {
        return true;
    }

    @Override
    public ScrapeException refusalFor(int status) {
        // Deliberately the same kinds ZxInfo raises, and for the same
        // reason: ScrapeException.worthWaiting() is what tells a hiccup from
        // a wall, and a screen that treated them alike would tell somebody to
        // come back tomorrow over a 503.
        if (status == 429 || status == 403) {
            return new ScrapeException(ScrapeException.Kind.CLOSED,
                    "ZXInfo refused with " + status + ", which is how it says"
                    + " an address has been asking too often");
        }

        if (status >= 500) {
            return new ScrapeException(ScrapeException.Kind.NETWORK,
                                       "ZXInfo answered " + status);
        }

        return new ScrapeException(ScrapeException.Kind.MALFORMED,
                                   "ZXInfo answered " + status);
    }

    // --- what each shelf asks for -------------------------------------------------------

    /**
     * One shelf and one page of it, as a path.
     *
     * A genre sub-shelf is looked for first and by prefix: it is the only
     * shelf whose id is not a constant here, since it came off the wire.
     */
    private static String pathFor(Shelf shelf, Query query, int page, int size) {
        String id = shelf.id();

        if (id.startsWith(GENRE_PREFIX)) {
            return searchFor("genretype=" + Uri.encode(id.substring(GENRE_PREFIX.length()))
                             + sortParam(query), page, size);
        }

        if (id.startsWith(LETTER_PREFIX)) {
            return letterFor(id.substring(LETTER_PREFIX.length()), page, size);
        }

        if (id.startsWith(MORE_PREFIX)) {
            return likeFor(id.substring(MORE_PREFIX.length()));
        }

        if (SHELF_NEWEST.equals(id)) {
            return searchFor("sort=date_desc", page, size);
        }

        if (SHELF_RANDOM.equals(id)) {
            return randomFor();
        }

        return searchFor("query=" + Uri.encode(query.text()) + sortParam(query), page, size);
    }

    /**
     * The one search shape, with whatever distinguishes a shelf in front of it.
     *
     * Every shelf that <em>is</em> a search goes through here; A-Z is not one
     * and goes through {@link #letterFor}. Both end in {@link #paged}, which is
     * where {@code mode=compact} and the page size are stated once. There is no
     * {@code machinetype} and no {@code contenttype} anywhere in this file, and
     * that is asserted on the URL in the test because no reply would ever
     * reveal it.
     *
     * <b>{@code offset} is a page number, not a row number - measured.</b> The
     * specification calls it "the page offset for pagination", which reads
     * either way, and this sent {@code page * size} until somebody asked the
     * live service for both readings at once: a search answering {@code
     * total=153} was asked with {@code size=30&offset=30} and came back
     * <em>empty</em>. A row offset of 30 into 153 rows cannot be empty, so the
     * service had been asked for page thirty of five. Every shelf in this app
     * therefore stopped dead after its first thirty rows - a 200, a page of
     * nothing, {@code Page.hasMore} correctly reading an empty page as the end
     * - which on screen is a catalogue that simply has thirty of everything.
     * With {@code offset=page} the same search's second page came back with
     * thirty more, sharing no id with the first.
     */
    private static String searchFor(String criterion, int page, int size) {
        return paged("search", criterion, page, size);
    }

    /**
     * One letter of A-Z, which is an endpoint of its own and not a search.
     *
     * <b>{@code GET /games/byletter/{letter}} - the letter is in the path.</b>
     * The specification calls it "a-z - or # for numbers (case insensitive)"
     * and summarises the operation as "Fetches list of entries starting with a
     * specific letter", which is the thing a search could never be asked to
     * promise: {@code query=Q} is a full-text match and would have answered
     * with every record mentioning a Q anywhere in it. Nothing here sorts,
     * either - the rows arrive alphabetical, so the {@code sort=title_asc} the
     * search carried is not needed here rather than not allowed.
     *
     * <b>It takes the same {@code mode}, {@code size} and {@code offset} as
     * {@code /search}</b>, so this shares {@link #paged} with it - but its
     * {@code mode} <b>defaults to {@code tiny}, where {@code /search} defaults
     * to {@code compact}</b>, and tiny has no controls. So asking for compact
     * explicitly is load-bearing on this endpoint in a way it never was on the
     * search: leaving it off here would quietly change what comes back.
     *
     * The reply is the same {@code hits.hits[]._source} envelope a search
     * answers with, which is why {@link #rows} reads both without knowing
     * which it asked.
     *
     * <b>Measured live, 2026-08-11, two requests.</b> {@code letter=Z} answered
     * {@code total=1701} - a real count, well inside the 10,000 cap - with
     * thirty rows every one of whose titles begins with Z, and in alphabetical
     * order with nothing having asked for one. {@code offset} is a <b>page
     * number</b> here too, which had to be measured rather than assumed: the
     * specification's wording is the same "the page offset for pagination" that
     * read either way on {@code /search} and was wrong there. Page 1 shared
     * <em>no</em> id with page 0 and opened on {@code Z80 CPU Microprocessor
     * Instant Reference Card}, which follows page 0's last row {@code Z80
     * COLOSS} - where a row offset of 1 would have opened on page 0's
     * <em>second</em> row instead. Both readings were asked at once, so one
     * pair of requests settled it.
     *
     * <b>{@code #} is the twenty-seventh argument and it works</b> - see
     * {@link #DIGITS}. Measured the same way, 2026-08-11, two requests: {@code
     * games/byletter/%23} answered {@code total=839}, {@code "relation":"eq"},
     * with thirty rows every one of whose titles begins with a digit ({@code
     * 007 BEEP Copier}, {@code 0Score}, {@code 1 Line 3D Maze}), and page 1
     * shared no id with page 0 and opened on {@code 1 Line Action}, following
     * page 0's last row {@code 1 Line 3D Mazes}. So the digit shelf pages by
     * page number exactly as the letters do, and 839 entries were unreachable
     * from A-Z before it existed.
     *
     * The {@code #} is encoded to {@code %23} by the same {@link Uri#encode}
     * call the letters go through - which is the other reason it is encoded at
     * all: a raw {@code #} in a URL is a fragment marker, and everything after
     * it would never leave the phone.
     */
    private static String letterFor(String letter, int page, int size) {
        // Encoded although a letter never needs it: what arrives here is a
        // substring of a shelf id, and the one way this can go wrong is the
        // LETTER_PREFIX leaking into the path.
        return paged("games/byletter/" + Uri.encode(letter), "", page, size);
    }

    /**
     * Surprise me, on the endpoint whose whole job this is.
     *
     * <b>{@code GET /games/random/{total}} - the count is in the path, and
     * there is no {@code offset} and no {@code size}.</b> "Fetches list of
     * random entries", from the adventure, arcade, casual, game, sport and
     * strategy categories, all of them with loading and in-game screens. So
     * this asks for thirty of them and there is nothing else to say to it: no
     * page to ask for, because a page number would mean an ordering, and a
     * random list has none.
     *
     * <b>Measured live, 2026-08-11, three requests. It resamples</b>, which is
     * the whole difference: two identical requests one after the other answered
     * thirty entries each and shared <b>not one id</b>, where {@code
     * search?offset=random} answered the identical ten twice. So the one-page
     * cap this shelf used to carry is gone, and a fling brings thirty games
     * nobody has seen rather than the thirty already on screen.
     *
     * The reply is the same {@code hits.hits[]._source} envelope a search
     * answers with - 96,394 bytes for thirty compact entries - so {@link #rows}
     * reads it without knowing which endpoint it asked. Its {@code hits.total}
     * is {@code {"value":10000,"relation":"gte"}}: Elasticsearch's cap, not a
     * count, and no count of anything this list is walking through, which is
     * why the shelf stays uncountable in {@link #open}.
     *
     * <b>{@code mode} is honoured here</b> - the same call at {@code tiny}
     * answered 49,998 bytes against compact's 96,394 - which had to be asked
     * rather than assumed, since a parameter this service does not know is
     * ignored rather than refused. It is sent although this endpoint's default
     * is already {@code compact}, the one endpoint of the four whose default is
     * what this app wants: a default is the service's to change, and {@code
     * byletter}'s is {@code tiny}, which would have quietly dropped {@code
     * controls} had it been left off there.
     *
     * <b>Nothing in the service's answers ends this shelf</b>, which is why
     * {@link #open} does: with no total and a page that is never empty, {@code
     * Page.hasMore} would answer true for ever. It stops at {@link
     * #RANDOM_PAGES}, ten pages, and that constant carries the reasons - the
     * thirty unpaced thumbnail fetches behind each paced request, the fact that
     * every other shelf in the app has a floor and this one would not, and the
     * pool this endpoint actually draws from.
     *
     * <b>The pool is not the database.</b> Six genre categories, and only
     * entries with both screens: how many that is has not been counted, so
     * nothing here says. Two pages may share a game by chance, each being an
     * independent draw from it; that is what "surprise me" means and not a fault
     * to correct.
     */
    private static String randomFor() {
        return "games/random/" + PAGE_SIZE + "?mode=compact";
    }

    /**
     * Games like one game, which is an endpoint of its own too.
     *
     * <b>{@code GET /games/morelikethis/{game-id}}</b> - "Fetches list of
     * similar entries", Elasticsearch's own {@code more_like_this} over
     * machine type, genre type, genre sub-type and content type. It takes
     * {@code mode} and {@code size} and, like {@link #randomFor}, <b>no {@code
     * offset}</b>: it answers with one page of the best matches and there is no
     * second page to ask for, which is why {@link #open} ends the shelf after
     * the first.
     *
     * <b>Measured live, 2026-08-11, three requests.</b> {@code
     * morelikethis/0002259} - Head over Heels - answered 200 with the same
     * {@code hits.hits[]._source} envelope, thirty rows in descending {@code
     * _score} (8.19 at the top) out of a stated total of <b>1,858</b>, and
     * {@code "relation":"eq"} rather than the {@code "gte"} that marks a capped
     * count. It is deterministic: the same call at {@code mode=tiny} came back
     * with the same thirty ids in the same order, at 60,283 bytes against
     * compact's 104,386 - so the order is the query's rather than chance, and
     * {@code mode} is honoured here too.
     *
     * <b>The id in the path is what decides, proven with one that could have
     * been wrong.</b> A shelf built from the right game and a shelf built from
     * nothing would look equally plausible on screen, so a second entry was
     * asked for: {@code morelikethis/0027393} - "007 BEEP Copier", a utility -
     * answered thirty tape and disk copiers ({@code Kopykat 3}, {@code Fast
     * Copy}, {@code Wild Disk Copier}) with <b>no id in common</b> with Head
     * over Heels' thirty, which were Dizzy games and arcade adventures.
     *
     * <b>The 1,858 is not printed</b> - see {@link #open}, which keeps this
     * shelf uncountable. Thirty of them are shown and the next thirty cannot be
     * asked for, so a count beside the shelf's own name is a number the list
     * can never reach, which reads as a shelf that stopped early.
     *
     * The id is encoded for the same reason a letter is - what arrives here is
     * a substring of a shelf id, and the one way this goes wrong is the prefix
     * leaking into the path.
     */
    private static String likeFor(String id) {
        return "games/morelikethis/" + Uri.encode(id) + "?mode=compact&size=" + PAGE_SIZE;
    }

    /**
     * The tail every paged shelf shares: compact, thirty, and which page.
     *
     * One place so that a shelf cannot quietly acquire a different page size
     * or drop {@code mode=compact} - and so the two shapes above differ only
     * in what they are asking, never in how they are read. The four paths
     * that are not paged build their own: {@code item} and {@link #genres},
     * which ask about one thing each, and {@link #randomFor} and {@link
     * #likeFor}, whose endpoints take no {@code offset} at all.
     */
    private static String paged(String path, String criterion, int page, int size) {
        return path + "?" + (criterion.isEmpty() ? "" : criterion + "&")
                + "mode=compact&size=" + size + "&offset=" + page;
    }

    // --- reading a page ------------------------------------------------------------------

    private Page rows(String path, int seenBefore, boolean countable) throws ScrapeException {
        JSONObject reply = object(ask(path));

        // A 404 from a search is "nothing here", which ends the list rather
        // than failing it - the same reading ZxInfo.ask gives it. The total is
        // UNKNOWN_TOTAL and not zero: no count came back, and a zero here is
        // drawn beside the shelf's own name as "· 0", which states as a fact
        // about the catalogue what is only this app failing to get an answer.
        // Either way the empty page ends the list, which is Page.hasMore's own
        // rule and not this count's.
        if (reply == null) return new Page(null, null, seenBefore, Page.UNKNOWN_TOTAL);

        List<Item> items = new ArrayList<>();

        for (JSONObject hit : hits(reply)) {
            JSONObject source = hit.optJSONObject("_source");
            if (source == null) continue;

            // Through text() like everything else: optString answers the
            // string "null" for a JSON null, and that id would go straight
            // back out as games/null?mode=compact.
            String id = text(hit, "_id");
            if (id == null) continue;

            items.add(itemFrom(id, source));
        }

        return new Page(items, null, seenBefore,
                        countable ? totalOf(reply) : Page.UNKNOWN_TOTAL);
    }

    /**
     * The A-Z shelf: one sub-shelf per letter, no items, and no request.
     *
     * The letter travels in the sub-shelf's id rather than in a {@link Query},
     * which is what lets the screen stay ignorant of letters entirely - it
     * descends into a shelf here exactly as it descends into a genre. The
     * label is the letter itself, since that is both what somebody reads and,
     * behind {@link #LETTER_PREFIX}, what the search is built from.
     *
     * {@link #ALPHABET} is Latin and only Latin, deliberately - see its own
     * comment. The shelves are built here every time this is called rather
     * than once into a constant: it is twenty-seven small objects on a tap, and
     * a shared mutable list handed out through {@link Page} is a worse thing to
     * own.
     *
     * <b>Twenty-seven, and the last one is {@link #DIGITS}.</b> The endpoint
     * takes {@code #} for the titles that start with a digit, so leaving it out
     * put part of the database beyond every way into it. It goes after Z rather
     * than before A because the shelf above is called A-Z: the letters keep the
     * positions somebody reaching for one expects, and the extra shelf follows
     * them.
     */
    private Page letters() {
        List<Shelf> found = new ArrayList<>(ALPHABET.length() + 1);

        for (int at = 0; at < ALPHABET.length(); at++) {
            String letter = String.valueOf(ALPHABET.charAt(at));
            found.add(new Shelf(LETTER_PREFIX + letter, letter, Shelf.Accepts.NOTHING));
        }

        found.add(new Shelf(LETTER_PREFIX + DIGITS, DIGITS_LABEL, Shelf.Accepts.NOTHING));

        return new Page(null, found, 0, Page.UNKNOWN_TOTAL);
    }

    /**
     * The Categories shelf: sub-shelves, no items.
     *
     * The genre is carried in the sub-shelf's id rather than in its label,
     * because the label is what somebody reads and the id is what the next
     * request is built from - and here they happen to be the same words, which
     * is exactly the sort of coincidence that stops being one.
     *
     * <b>The array is {@code genretypes}, plural.</b> Measured 2026-08-10: the
     * reply's three top-level keys are {@code machinetypes}, {@code genretypes}
     * and {@code features}, 5,289 bytes in all - comfortably inside the two
     * megabytes {@code Http.Real} will read, so the hard-coded fallback the
     * brief allowed for size is not needed.
     *
     * The singular is still tried after it, and it is worth being exact about
     * why, because the reason is not that the two are equally likely. The
     * fixture this class was first written against said {@code genretype}, and
     * that fixture was written from memory rather than captured from a reply -
     * so it was never a recording and is not evidence of anything the service
     * has ever sent. The parser and the fixture agreed with each other and both
     * disagreed with the service, which is the one class of mistake a canned
     * test cannot catch. The singular is kept only as a cheap second guess, at
     * no cost, in the same spirit that had {@code ZxInfo.byHash} right about
     * {@code entry_id} before anybody could check. The verified name is first.
     */
    private Page genres() throws ScrapeException {
        JSONObject reply = object(ask("metadata/"));
        List<Shelf> found = new ArrayList<>();

        JSONArray genres = reply == null ? null : reply.optJSONArray("genretypes");
        if (genres == null && reply != null) genres = reply.optJSONArray("genretype");

        for (int at = 0; genres != null && at < genres.length(); at++) {
            String key = keyOf(genres.opt(at));

            if (key != null) found.add(new Shelf(GENRE_PREFIX + key, key, Shelf.Accepts.NOTHING));
        }

        // Never an empty Categories shelf. The vocabulary is recorded from
        // ZXDB's own dump and is the answer to this question when the service
        // cannot be read - a shelf that opens onto nothing is indistinguishable
        // from a broken screen, and this is the one shelf whose contents this
        // app already knows without asking. Logged, because falling back
        // silently is how a wrong key name survives a release.
        if (found.isEmpty()) {
            Log.w(TAG, "ZXInfo's metadata carried no genres; using the recorded vocabulary");

            for (String genre : Kinds.ZXDB_VOCABULARY) {
                found.add(new Shelf(GENRE_PREFIX + genre, genre, Shelf.Accepts.NOTHING));
            }
        }

        return new Page(null, found, 0, Page.UNKNOWN_TOTAL);
    }

    /**
     * One bucket's name.
     *
     * {@code key} is Elasticsearch's own word for it and is what this app has
     * recorded, but the bucket's <em>inner</em> shape was never read back from
     * a live reply - the one sanctioned request established the array's name
     * and not its contents - so a couple of ordinary alternatives are tried
     * rather than answering nothing. A bare string is allowed for the same
     * reason: it is what a list of values looks like when it carries no counts.
     */
    private static String keyOf(Object bucket) {
        if (bucket instanceof String) {
            String value = ((String) bucket).trim();
            return value.isEmpty() ? null : value;
        }

        if (!(bucket instanceof JSONObject)) return null;

        JSONObject entry = (JSONObject) bucket;

        for (String field : new String[] { "key", "name", "value" }) {
            String value = text(entry, field);
            if (value != null) return value;
        }

        return null;
    }

    /**
     * How many the service says there are.
     *
     * Lenient about the shape: Elasticsearch reports this as an object with a
     * {@code value} in the version behind ZXInfo and as a bare number in
     * older ones, and a total read wrong is a list that stops early or never
     * stops. Unknown rather than zero when it cannot be read at all, since
     * {@code Page.hasMore} treats unknown as "ask again" and an empty page
     * ends the list either way.
     */
    private static int totalOf(JSONObject reply) {
        JSONObject hits = reply.optJSONObject("hits");
        if (hits == null) return Page.UNKNOWN_TOTAL;

        JSONObject total = hits.optJSONObject("total");
        if (total != null) return total.optInt("value", Page.UNKNOWN_TOTAL);

        return hits.optInt("total", Page.UNKNOWN_TOTAL);
    }

    // --- reading one entry ---------------------------------------------------------------

    /**
     * One entry, from a search hit or from a whole record.
     *
     * The same fields either way - a search in {@code compact} answers with
     * the record's own {@code _source}, so a row drawn from a list and a row
     * drawn from a fetch cannot disagree.
     *
     * <b>{@code availability} is not always there, and absent is a third
     * answer.</b> One of the first three rows a live search brought back
     * carries none at all - a 2024 release, so not an obscure corner of the
     * database. There are three states here and not two: available, explicitly
     * something else, and unstated. {@code Item.available} folds the third into
     * the second, which is the right reading of "is this definitely
     * available" and is that type's own rule, deliberately not changed here.
     *
     * <b>The drawing code must not inherit that rule blindly.</b> Greying a row
     * is a different question from "is it definitely available", and answering
     * it with this one greys a perfectly ordinary new release and then has no
     * reason to show beside it, because {@link Item#availability} is null. Where
     * the reason is null the row should say nothing rather than say why.
     *
     * <b>A row carries its files too, and they cost nothing.</b> This used to
     * read the releases only for {@code /games}, on the belief that a search
     * hit did not carry them. Measured against the live service: a {@code
     * mode=compact} search hit's {@code _source} carries {@code releases},
     * {@code additionalDownloads} and {@code screens}, and every field {@link
     * #versions} reads is byte-identical to the record's own - what {@code
     * /games} adds is twenty-one <em>other</em> fields, {@code controls} and
     * {@code series} among them, which is why the pane still fetches it.
     *
     * So a list row already knows which formats it holds, without a request
     * per row, and that is what makes filtering a shelf by format possible at
     * all: the service has no such filter - {@code format}, {@code filetype}
     * and {@code downloadtype} are all silently ignored, measured against a
     * deliberate nonsense parameter as the control.
     */
    private static Item itemFrom(String id, JSONObject source) {
        return new Item(id,
                        text(source, "title"),
                        year(source),
                        publisher(source),
                        text(source, "genreType"),
                        text(source, "availability"),
                        picture(source),
                        versions(source));
    }

    /**
     * The first screen that can be drawn, on whichever host it lives.
     *
     * <b>The two-host rule, and it is not this class's to restate</b> - see
     * {@code ZxInfo.urlFor}. {@code /pub/} and {@code /zxdb/} are on the
     * archive; {@code /zxscreens/}, which is every rendered loading screen, is
     * on ZXInfo's own media host and 404s on the archive. Both arrive inside
     * the same array, so the array is no guide and the prefix is the only
     * thing that decides.
     */
    private static String picture(JSONObject source) {
        JSONArray screens = source.optJSONArray("screens");

        for (int at = 0; screens != null && at < screens.length(); at++) {
            JSONObject screen = screens.optJSONObject(at);
            String path = screen == null ? null : text(screen, "url");

            if (path == null || !PICTURES.contains(extensionOf(path))) continue;

            return urlFor(path);
        }

        return null;
    }

    /**
     * The releases, in the order the record lists them.
     *
     * Nothing re-sorts them: their order is ZXDB's own statement about which
     * came first, and this app has no better source - see {@code Pick}, which
     * takes the first as the original.
     */
    private static List<Version> versions(JSONObject source) {
        List<Version> found = new ArrayList<>();
        JSONArray releases = source.optJSONArray("releases");

        // A recording is not a release, and this record says so: measured over
        // every captured reply that has one, an rzx is in additionalDownloads
        // and never in a release's files - six of them, none the other way.
        // Read only from the releases, Pick.recording was null for every entry
        // in the database, exactly as Pick.forGame was; the fixture that hid it
        // put the rzx in a release because it was written from memory. They are
        // hung off the first version rather than one of their own, since Pick
        // looks across all of them and a version of nothing but a recording
        // would draw as a release nobody published.
        List<Download> recordings = recordings(source);

        for (int at = 0; releases != null && at < releases.length(); at++) {
            JSONObject release = releases.optJSONObject(at);
            if (release == null) continue;

            List<Download> files = files(release);
            if (found.isEmpty()) files.addAll(recordings);

            // The publisher is what tells two releases apart here. ZXDB does
            // carry alternative titles - under releaseTitles, and most releases
            // have none - but a label that is empty for four rows of five tells
            // nothing apart, so the publisher stays.
            found.add(new Version(publisher(release), releaseYear(release), files));
        }

        // Nothing to hang them off: an entry with a recording and no release at
        // all is a thing ZXDB holds, and losing the recording there would be
        // silent.
        if (found.isEmpty() && !recordings.isEmpty()) {
            found.add(new Version(null, null, recordings));
        }

        return found;
    }

    /**
     * The playthroughs, out of the entry's own downloads.
     *
     * {@code additionalDownloads} is mostly pictures, manuals, pokes and
     * music, which are the scraping provider's business and not a catalogue's
     * - what this takes from it is the one thing there that can be handed to
     * the emulator. Told by {@link Pick#isRecording}, so what counts as one is
     * defined in a single place.
     */
    private static List<Download> recordings(JSONObject source) {
        List<Download> found = new ArrayList<>();
        JSONArray downloads = source.optJSONArray("additionalDownloads");

        for (int at = 0; downloads != null && at < downloads.length(); at++) {
            JSONObject file = downloads.optJSONObject(at);
            String path = file == null ? null : text(file, "path");

            if (path == null || !served(path)) continue;

            Download download = new Download(urlFor(path), formatOf(file, path),
                                             file.optLong("size", -1));

            if (Pick.isRecording(download)) found.add(download);
        }

        return found;
    }

    private static List<Download> files(JSONObject release) {
        List<Download> found = new ArrayList<>();
        JSONArray files = release.optJSONArray("files");

        for (int at = 0; files != null && at < files.length(); at++) {
            JSONObject file = files.optJSONObject(at);
            String path = file == null ? null : text(file, "path");

            if (path == null || !served(path)) continue;

            found.add(new Download(urlFor(path), formatOf(file, path),
                                   file.optLong("size", -1)));
        }

        return found;
    }

    /** Where ZXDB puts a file it holds a record of and will not hand over -
     *  see {@link #served}. */
    private static final String WITHHELD = "/denied/";

    /**
     * Whether the archive will actually give this file up.
     *
     * <b>A path under {@code /denied/} is a 404, every time, and it is the
     * path that says so rather than the entry.</b> Measured on 2026-08-14,
     * after "Play the recording" answered "That did not arrive." for Atic
     * Atac: {@code /denied/entries/0009305/AticAtac.rzx.zip} and its {@code
     * .tzx.zip} both 404, while {@code
     * /zxdb/sinclair/entries/0009305/AticAtac.jpg} - the same entry's inlay -
     * is served; the same on 1942, 1943 and 11-a-Side Soccer. Over 240 sampled
     * entries the prefix appeared only under an availability of "Distribution
     * denied", and every one of those entries carried served paths too.
     *
     * So it is dropped here, at the parse, rather than anywhere further down.
     * A file this app cannot fetch is not a file this entry <em>holds</em>,
     * and everything downstream reads it that way for free: {@code Pick}
     * answers null instead of naming a 404, the pane offers no button rather
     * than one that can only fail, and a format filter stops listing games
     * whose only rzx or tzx nobody can have - which is where this was noticed,
     * an RZX shelf offering Atic Atac.
     *
     * <b>Per file and never per entry.</b> Dropping a denied entry outright
     * would take its covers, maps, adverts and poke files with it - all of
     * them served, all of them things somebody looked the entry up for - and
     * the row itself is worth keeping either way: a game that exists and is
     * not distributed is a real thing to find, which is what the greyed row
     * and its stated reason are for.
     */
    private static boolean served(String path) {
        return !path.startsWith(WITHHELD);
    }

    /**
     * An absolute url from whatever the record holds.
     *
     * <b>Not this class's rule, and no longer this class's copy of it.</b>
     * Which host a path is relative to, and the fact that some paths are
     * already whole urls, are both measured facts about ZXDB rather than
     * anything a catalogue decides - so they live in one place, {@code
     * ZxInfo.urlFor}, and this only names it. The copy that used to be here
     * was the one with the already-absolute check in it while {@code
     * ZxInfo.collect}, two files away, went without.
     */
    private static String urlFor(String path) {
        return ZxInfo.urlFor(path);
    }

    /**
     * What is inside, not what it is wrapped in.
     *
     * <b>{@code format} is a human phrase and never a bare extension.</b>
     * Measured over captured replies, every value it takes: {@code Perfect
     * tape (TZX)}, {@code Tape (TAP)}, {@code TR-DOS disk (TRD)}, {@code
     * TR-DOS disk (SCL)}, {@code ROM image dump (ROM)}, {@code Game recording
     * (RZX)}, {@code Screen dump (SCR)}, {@code Document (PDF)}, {@code
     * Document (TXT)}, {@code Pokes (POK)}, {@code Music (AY)}, {@code Music
     * (MP3)}, {@code Picture (JPG)}, {@code Picture (PNG)}, {@code Picture
     * (GIF)} and a bare {@code Picture}. Returning it as it stands - which
     * this did, pinned by a fixture that said {@code "format":"TZX"} because
     * it was written from memory - makes every format {@code Pick.PREFERENCE}
     * matches with {@code equals} unmatchable, so nothing in the database can
     * be imported and every record reads as one the Spectrum cannot open. The
     * whole feature was inert and every test passed.
     *
     * So the parenthesised code is what is read, and where there is none - the
     * bare {@code Picture} above - the path answers, which is exactly what
     * {@code ZxInfo.extensionOf} next door does with the same vocabulary and
     * says so in its own comment: it "says 'Picture' for a png and 'Picture
     * (JPG)' for a jpg". The two must not disagree about this service's words.
     *
     * The path is also what answers where the stated code is a wrapper, since
     * what decides whether this app can open a file is what is inside it:
     * {@code HeadOverHeels.tzx.zip} is a tzx. The stated code is preferred
     * over the path rather than the other way about because a path here can be
     * a whole url that need not end in a file name - see {@link #extensionOf}.
     * Every path in every captured reply is in fact relative and named, so
     * that ordering costs nothing measurable either way; it is the cheaper way
     * round for a record where the path cannot answer.
     */
    private static String formatOf(JSONObject file, String path) {
        String stated = code(text(file, "format"));

        return stated == null || WRAPPERS.contains(stated) ? inner(path) : stated;
    }

    /**
     * The code out of a stated format - "Perfect tape (TZX)" is a tzx.
     *
     * Null rather than a guess where the phrase carries no code, so
     * {@link #formatOf} falls through to the path. What is accepted is
     * deliberately narrow: one parenthesised word, no space in it, and no
     * longer than four characters - long enough for every extension this
     * plan knows about ({@code tzx}, {@code jpeg}, {@code dsk}, ...) and no
     * longer. A phrase whose brackets hold prose rather than an extension -
     * nothing measured has this shape, but {@code "Compilation (multiload)"}
     * would be one - is not caught by the no-spaces rule alone, and taking it
     * as a format would be worse than falling through to the path: {@code
     * Pick} would look for it in {@code PREFERENCE}, not find it, and the
     * file would be dropped silently rather than opened by its own
     * extension. The length limit is the cheap guard against that: an
     * extension is short, a word that merely lacks a space is not
     * necessarily one.
     */
    private static String code(String format) {
        if (format == null) return null;

        int open = format.lastIndexOf('(');
        int close = format.lastIndexOf(')');
        if (open < 0 || close < open) return null;

        String code = format.substring(open + 1, close).trim().toLowerCase(Locale.ROOT);

        if (code.isEmpty() || code.length() > 4) return null;

        for (int at = 0; at < code.length(); at++) {
            if (!Character.isLetterOrDigit(code.charAt(at))) return null;
        }

        return code;
    }

    /** The extension under one wrapper, or the extension itself. */
    private static String inner(String path) {
        String extension = extensionOf(path);
        if (!WRAPPERS.contains(extension)) return extension;

        return extensionOf(path.substring(0, path.length() - extension.length() - 1));
    }

    /**
     * The extension, <b>of the last path segment only</b>.
     *
     * A path here can be a whole url - ZXDB's recordings are on archive.org -
     * and a url need not end in a file name. Reading from the last dot in the
     * whole string makes {@code https://archive.org/download/zx_rzx/Foo} an
     * extension of {@code org/download/zx_rzx/foo}: harmless where the record
     * states a real format, and a plausible-looking wrong answer where it does
     * not, which is the worse of the two failures.
     */
    private static String extensionOf(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);

        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";

        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    private static String publisher(JSONObject of) {
        JSONArray publishers = of.optJSONArray("publishers");
        if (publishers == null || publishers.length() == 0) return null;

        JSONObject first = publishers.optJSONObject(0);
        return first == null ? null : text(first, "name");
    }

    private static String year(JSONObject source) {
        int year = source.optInt("originalYearOfRelease", 0);
        return year > 0 ? Integer.toString(year) : null;
    }

    /**
     * A release's year.
     *
     * <b>{@code yearOfRelease}, measured.</b> This read {@code releaseYear},
     * which appears nowhere in a live record - so every version this class
     * ever answered with had a null year, and the list somebody chooses a
     * version from showed none. The old name came from the same fixture that
     * invented {@code "format":"TZX"}; it is not kept as a second guess,
     * because it was never something the service sent.
     */
    private static String releaseYear(JSONObject release) {
        int year = release.optInt("yearOfRelease", 0);
        return year > 0 ? Integer.toString(year) : null;
    }

    // --- asking ---------------------------------------------------------------------------

    /**
     * One request, or null when the service says it has nothing.
     *
     * The same body as {@code ZxInfo.ask}, deliberately: one shape for one
     * service, so a refusal means the same thing whichever question was being
     * asked. The pacing is the part that matters and it is not this object's -
     * {@link Pace} counts per host, so a sweep scraping and a grid browsing
     * queue behind one another instead of halving the interval between them.
     */
    private String ask(String path) throws ScrapeException {
        Pace.before(ZxInfo.API_HOST, ZxInfo.MINIMUM_INTERVAL_MS);

        try {
            Http.Reply reply = http.get(ZxInfo.API + path);

            if (reply.status == 404) return null;
            if (!reply.ok()) throw refusalFor(reply.status);

            return reply.body;
        } catch (Http.Refused refused) {
            throw refusalFor(refused.status);
        } catch (IOException e) {
            throw new ScrapeException(ScrapeException.Kind.NETWORK,
                                      "cannot reach ZXInfo: " + e.getMessage(), e);
        }
    }

    private JSONObject object(String body) throws ScrapeException {
        if (body == null || body.isEmpty()) return null;

        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            Log.w(TAG, "ZXInfo sent something that is not JSON");
            throw new ScrapeException(ScrapeException.Kind.MALFORMED,
                                      "ZXInfo sent something that is not JSON");
        }
    }

    private static List<JSONObject> hits(JSONObject reply) {
        List<JSONObject> found = new ArrayList<>();

        JSONObject outer = reply.optJSONObject("hits");
        JSONArray inner = outer == null ? null : outer.optJSONArray("hits");

        for (int at = 0; inner != null && at < inner.length(); at++) {
            JSONObject hit = inner.optJSONObject(at);
            if (hit != null) found.add(hit);
        }

        return found;
    }

    /** Null rather than the string "null", which is what optString gives for
     *  a JSON null and what would otherwise be drawn as a publisher. */
    private static String text(JSONObject from, String key) {
        if (from.isNull(key)) return null;

        String value = from.optString(key, "").trim();
        return value.isEmpty() ? null : value;
    }
}
