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
 * <b>Filtered by nothing.</b> PENTAGON is a sibling of ZXSPECTRUM in ZXInfo's
 * scheme rather than a variant of it, so a machine filter silently drops the
 * Pentagon demoscene - most of what arrives as .trd and .scl. Every filter can
 * only lose the right answer.
 *
 * <b>mode=compact.</b> Measured on one game: 10,286 bytes against full's
 * 45,318, with every field this app reads byte-identical. tiny has no
 * controls.
 *
 * <h3>What each shelf actually asks for</h3>
 *
 * The specification was not fetched to write this - a bare request to that
 * host is what lost this app its address the first time - so each shelf is
 * built from something already proven or is written down here as unproven:
 *
 * <ul>
 *   <li><b>Search</b> is the shape {@code ZxInfo.byName} has been using
 *       against the live service: {@code search?query=&mode=compact&size=&offset=}.
 *       Proven, and proven again by the one live call this class was written
 *       with - 30 rows of a stated 153, and the two-host rule visible inside
 *       that single reply: one entry's picture came back under {@code /zxdb/}
 *       and the next two under {@code /zxscreens/}.</li>
 *   <li><b>A-Z</b> opens onto twenty-six sub-shelves, one per letter, and each
 *       of those is that same search with its letter as the query and a title
 *       sort, rather than a path of its own. Sub-shelves rather than a letter
 *       picker of the screen's own: a page may carry shelves, {@link #genres}
 *       already works that way, and the tab already knows how to descend into
 *       one - a shelf needing a widget nothing else needs is a seam in the
 *       wrong place. <b>A shelf is data, and one implemented a different way
 *       is still the same shelf</b> - nothing above
 *       this interface can tell, and a by-letter endpoint that may or may not
 *       exist is not worth a request to find out. <b>{@code sort=title_asc} is
 *       a guessed value and is unverified</b> - the parameter itself is proven,
 *       since {@code date_desc} was checked on the same one, but nothing has
 *       confirmed the service knows this value. It fails gently: an unrecognised
 *       sort leaves the shelf in relevance order rather than alphabetical, which
 *       is a worse A-Z and not a wrong one.</li>
 *   <li><b>Newest</b> asks for {@code sort=date_desc}, and <b>the sort is
 *       honoured</b> - verified 2026-08-10, because an ignored sort parameter
 *       answers 200 with plausible rows and looks exactly like one that
 *       worked. Thirty rows came back, the first ten all dated 2026 with
 *       descending ids. Read from the years rather than from a second
 *       unsorted request: a list whose first rows are all this year is
 *       sorted.</li>
 *   <li><b>Surprise me</b> is {@code offset=random}. Also the honest name for
 *       it: two successive requests with that offset returned the identical ten
 *       entries, so it is a shelf worth having and not a sampler worth
 *       trusting - which is why its page reports {@link Page#UNKNOWN_TOTAL}
 *       and why nothing measures anything with it.</li>
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
 * last request this makes is {@code offset=9990&size=30}, which reaches a
 * little past the window and may be refused. One refusal at the bottom of ten
 * thousand rows is a good trade for arithmetic nobody has to maintain, and it
 * is written down here rather than guarded against because it has not been
 * seen. It is a lie as a result count, and the lie is worst on the broad
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

    static final String SHELF_SEARCH = "search";
    static final String SHELF_LETTER = "letter";
    static final String SHELF_NEWEST = "newest";
    static final String SHELF_RANDOM = "random";
    static final String SHELF_GENRES = "genres";

    /** A sub-shelf yielded by {@link #SHELF_GENRES} carries the genre as its
     *  id behind this prefix, so {@link #open} can tell one from a declared
     *  shelf without a second field. */
    private static final String GENRE_PREFIX = "genre:";

    /** The same trick for {@link #SHELF_LETTER}'s own twenty-six: the letter
     *  is the id behind this, so {@link #open} can tell "the A-Z shelf" from
     *  "the letter Q" without a second field. */
    private static final String LETTER_PREFIX = "letter:";

    /** What A-Z opens onto. Latin only, and deliberately: this is a search
     *  term handed to a service whose titles are indexed in it, not an
     *  alphabet for the phone's language. */
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

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
                // of the twenty-six shelves this yields, not by handing it a
                // query. See open().
                new Shelf(SHELF_LETTER, "A-Z", Shelf.Accepts.NOTHING),
                new Shelf(SHELF_GENRES, "Categories", Shelf.Accepts.NOTHING),
                new Shelf(SHELF_NEWEST, "Newest", Shelf.Accepts.NOTHING),
                new Shelf(SHELF_RANDOM, "Surprise me", Shelf.Accepts.NOTHING));
    }

    @Override
    public Page open(Shelf shelf, Query query, int page) throws ScrapeException {
        if (SHELF_GENRES.equals(shelf.id())) return genres();

        // Twenty-six shelves and no request. A page carrying shelves rather
        // than items is the mechanism Categories already uses; this is the
        // second thing to rest on it, which is worth something in itself,
        // since zxart's whole category tree will.
        if (SHELF_LETTER.equals(shelf.id())) return letters();

        // A surprise is one page, and the second one is empty on purpose.
        //
        // offset=random does not resample: two successive requests with it
        // returned the identical ten entries. So page two of this shelf is
        // page one again - the same URL, the same thirty rows, appended to the
        // thirty already on screen. And nothing stops it, because the total is
        // unknown and Page.hasMore reads unknown-plus-non-empty as "there is
        // more": an endless grid of duplicates, one paced request per fling,
        // against the host that blocked this app once already.
        //
        // Ended here rather than in hasMore, whose contract is right and which
        // other shelves depend on. The only honest thing to do with a shelf
        // that cannot page is to stop after the first one; the total stays
        // UNKNOWN_TOTAL because there genuinely is no count, and it is the
        // empty second page that ends the list.
        if (SHELF_RANDOM.equals(shelf.id()) && page > 0) {
            return new Page(null, null, page * PAGE_SIZE, Page.UNKNOWN_TOTAL);
        }

        // A random shelf cannot count either: the offset is not an index into
        // anything, so the total the service reports says nothing about how
        // much of it this list has seen.
        boolean countable = !SHELF_RANDOM.equals(shelf.id());

        return rows(pathFor(shelf, query, page), page * PAGE_SIZE, countable);
    }

    @Override
    public Item item(String id) throws ScrapeException {
        JSONObject reply = object(ask("games/" + Uri.encode(id) + "?mode=compact"));
        JSONObject source = reply == null ? null : reply.optJSONObject("_source");

        return source == null ? null : itemFrom(id, source, true);
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
    private static String pathFor(Shelf shelf, Query query, int page) {
        int offset = page * PAGE_SIZE;
        String id = shelf.id();

        if (id.startsWith(GENRE_PREFIX)) {
            return searchFor("genretype=" + Uri.encode(id.substring(GENRE_PREFIX.length())),
                             offset);
        }

        if (id.startsWith(LETTER_PREFIX)) {
            return searchFor("query=" + Uri.encode(id.substring(LETTER_PREFIX.length()))
                             + "&sort=title_asc", offset);
        }

        if (SHELF_NEWEST.equals(id)) {
            return searchFor("sort=date_desc", offset);
        }

        if (SHELF_RANDOM.equals(id)) {
            // Not paged, because there is nothing to page through: the offset
            // is the whole of what makes it random. Asking for page two of it
            // asks the same question again.
            return "search?mode=compact&size=" + PAGE_SIZE + "&offset=random";
        }

        return searchFor("query=" + Uri.encode(query.text()), offset);
    }

    /**
     * The one search shape, with whatever distinguishes a shelf in front of it.
     *
     * Every shelf goes through here so that {@code mode=compact} and the
     * absence of a filter are stated once. There is no {@code machinetype} and
     * no {@code contenttype} anywhere in this file, and that is asserted on the
     * URL in the test because no reply would ever reveal it.
     */
    private static String searchFor(String criterion, int offset) {
        return "search?" + criterion + "&mode=compact&size=" + PAGE_SIZE + "&offset=" + offset;
    }

    // --- reading a page ------------------------------------------------------------------

    private Page rows(String path, int seenBefore, boolean countable) throws ScrapeException {
        JSONObject reply = object(ask(path));

        // A 404 from a search is "nothing here", which ends the list rather
        // than failing it - the same reading ZxInfo.ask gives it.
        if (reply == null) return new Page(null, null, seenBefore, 0);

        List<Item> items = new ArrayList<>();

        for (JSONObject hit : hits(reply)) {
            JSONObject source = hit.optJSONObject("_source");
            if (source == null) continue;

            // Through text() like everything else: optString answers the
            // string "null" for a JSON null, and that id would go straight
            // back out as games/null?mode=compact.
            String id = text(hit, "_id");
            if (id == null) continue;

            items.add(itemFrom(id, source, false));
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
     * comment. Twenty-six shelves are built here every time this is called
     * rather than once into a constant: it is twenty-six small objects on a
     * tap, and a shared mutable list handed out through {@link Page} is a
     * worse thing to own.
     */
    private Page letters() {
        List<Shelf> found = new ArrayList<>(ALPHABET.length());

        for (int at = 0; at < ALPHABET.length(); at++) {
            String letter = String.valueOf(ALPHABET.charAt(at));
            found.add(new Shelf(LETTER_PREFIX + letter, letter, Shelf.Accepts.NOTHING));
        }

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
     * @param full whether to read the releases, which only {@code /games}
     *             carries and which are what makes that call worth making.
     */
    private static Item itemFrom(String id, JSONObject source, boolean full) {
        return new Item(id,
                        text(source, "title"),
                        year(source),
                        publisher(source),
                        text(source, "genreType"),
                        text(source, "availability"),
                        picture(source),
                        full ? versions(source) : null);
    }

    /**
     * The first screen that can be drawn, on whichever host it lives.
     *
     * <b>The two-host rule, and it is not this class's to restate</b> - see
     * {@code ZxInfo.hostOf}. {@code /pub/} and {@code /zxdb/} are on the
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

        for (int at = 0; releases != null && at < releases.length(); at++) {
            JSONObject release = releases.optJSONObject(at);
            if (release == null) continue;

            // The publisher is what tells two releases apart here. ZXDB does
            // carry alternative titles, but under a key nobody has recorded
            // from a live reply, and a field name taken from a specification
            // rather than an answer is how this app came to believe /filecheck
            // said "id" when it says "entry_id".
            found.add(new Version(publisher(release), releaseYear(release), files(release)));
        }

        return found;
    }

    private static List<Download> files(JSONObject release) {
        List<Download> found = new ArrayList<>();
        JSONArray files = release.optJSONArray("files");

        for (int at = 0; files != null && at < files.length(); at++) {
            JSONObject file = files.optJSONObject(at);
            String path = file == null ? null : text(file, "path");

            if (path == null) continue;

            found.add(new Download(urlFor(path), formatOf(file, path),
                                   file.optLong("size", -1)));
        }

        return found;
    }

    /**
     * An absolute url from whatever the record holds.
     *
     * <b>A path that is already a url is used as it is.</b> ZXDB's own RZX
     * recordings live on archive.org and arrive whole; joining one onto a base
     * makes a spectrumcomputing.co.uk url with an https:// in the middle of
     * it, which 404s and looks exactly like a game with no recording.
     */
    private static String urlFor(String path) {
        return path.startsWith("http") ? path : ZxInfo.hostOf(path) + path;
    }

    /**
     * What is inside, not what it is wrapped in.
     *
     * The record's own {@code format} is right for nearly everything - "TZX",
     * "Z80", "RZX" - and useless where it says "ZIP", which is the wrapper
     * every one of these is served in. There the path answers instead:
     * {@code HeadOverHeels.tzx.zip} is a tzx.
     */
    private static String formatOf(JSONObject file, String path) {
        String format = text(file, "format");
        format = format == null ? "" : format.toLowerCase(Locale.ROOT);

        return format.isEmpty() || WRAPPERS.contains(format) ? inner(path) : format;
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

    private static String releaseYear(JSONObject release) {
        int year = release.optInt("releaseYear", 0);
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
        Pace.before("api.zxinfo.dk", ZxInfo.MINIMUM_INTERVAL_MS);

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
