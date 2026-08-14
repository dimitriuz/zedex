package dev.ldlab.zedex.library.scrape;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one thing that knows how to talk to zxart.ee.
 *
 * <b>Every name here was measured against the live service on 2026-08-14, and
 * an unrecognised name is ignored rather than refused.</b> That is the whole
 * reason this class exists as a vocabulary rather than as string
 * concatenation at each call site: a filter zxart does not recognise returns
 * the unfiltered 58,032 rows with `responseStatus: "success"`, which reads
 * exactly like a search that matched everything. Measured with a deliberate
 * nonsense parameter as the control, both as a path segment and inside
 * `filter:`; both were byte-identical to no parameter at all.
 *
 * Deliberately free of Android types - no {@code Uri}, no {@code Log}, no
 * {@code Context} - so the grammar, the paging arithmetic and the parsing are
 * tested on the JVM in seconds. {@code unitTests.returnDefaultValues} means an
 * Android type reached from a unit test answers null without complaining, so
 * this is a rule rather than a preference. Its own percent-encoder exists for
 * the same reason.
 *
 * See docs/superpowers/specs/2026-08-14-zxart-integration-design.md.
 */
public final class ZxartApi {

    public static final String HOST = "zxart.ee";
    private static final String API = "https://zxart.ee/api/";

    /**
     * The same interval ZXInfo gets, and per host rather than per object.
     *
     * zxart publishes no rate limit, and its robots.txt disallows {@code /api}
     * to every agent - so the traffic is kept to what a person actually asked
     * for: one request at a time, nothing speculative, no prefetch. See the
     * design's <em>Manners</em>.
     */
    public static final long MINIMUM_INTERVAL_MS = 250;

    // --- the entities, measured -----------------------------------------------------

    public static final String PROD = "zxProd";
    public static final String RELEASE = "zxRelease";
    public static final String CATEGORY = "zxProdCategory";
    public static final String MUSIC = "zxMusic";
    public static final String PICTURE = "zxPicture";
    public static final String AUTHOR = "author";

    // --- the filters that work ------------------------------------------------------

    public static final String FILTER_PROD_ID = "zxProdId";
    public static final String FILTER_SEARCH = "zxProdSearch";
    public static final String FILTER_CATEGORY = "zxProdCategory";
    public static final String FILTER_AUTHOR = "authorId";
    public static final String FILTER_CATEGORIES_ALL = "zxProdCategoryAll";
    public static final String FILTER_MUSIC_ID = "zxMusicId";
    public static final String FILTER_PICTURE_ID = "zxPictureId";

    /**
     * Measured 2026-08-14, against this feature's own spec, which had guessed
     * these would be ignored like most title filters here: {@code beyond}
     * against {@link #MUSIC} returned 10 rows of 29,672, {@code girl} against
     * {@link #PICTURE} returned 124 of 19,408, every visible title carrying
     * the term, against a control whose signature is the <em>exact</em>
     * unfiltered total. Both genuinely filter.
     */
    public static final String FILTER_MUSIC_SEARCH = "zxMusicSearch";
    public static final String FILTER_PICTURE_SEARCH = "zxPictureSearch";

    /**
     * Names that are <b>ignored</b>, kept so nobody reaches for one again.
     *
     * {@code zxProdTitleSearch}, {@code zxProdTitle}, {@code zxProdTitleStart},
     * {@code zxProdMd5}, {@code zxReleaseMd5}, {@code zxProdImportId},
     * {@code zxProdWosId}, and {@code action:search} with {@code query:}. The
     * first of those is what this project's own earlier notes recorded as the
     * title search; it returns everything.
     */

    // --- the orders that work -------------------------------------------------------

    public static final String ORDER_TOP = "votes,desc";
    public static final String ORDER_NEWEST = "date,desc";
    public static final String ORDER_TITLE = "title,asc";

    /* Ignored: order:rating,desc and order:votesAmount,desc. The reply calls
     * the field `rating` on music and pictures and `votes` on prods, and the
     * order only ever answers to `votes` - on all three. */

    private final Http http;

    public ZxartApi(Http http) {
        this.http = http;
    }

    /**
     * One request, parsed, or null when the service says it has nothing.
     *
     * The pacing is not this object's business beyond calling it: {@link Pace}
     * counts per host, so a sweep scraping and a grid browsing queue behind
     * one another rather than halving the interval between them.
     */
    public JSONObject ask(Ask what) throws ScrapeException {
        Pace.before(HOST, MINIMUM_INTERVAL_MS);

        String body;

        try {
            Http.Reply reply = http.get(API + what.path());

            if (reply.status == 404) return null;
            if (!reply.ok()) throw refusalFor(reply.status);

            body = reply.body;
        } catch (Http.Refused refused) {
            throw refusalFor(refused.status);
        } catch (IOException e) {
            throw new ScrapeException(ScrapeException.Kind.NETWORK,
                                      "cannot reach zxart: " + e.getMessage(), e);
        }

        if (body == null || body.isEmpty()) return null;

        JSONObject reply;

        try {
            reply = new JSONObject(body);
        } catch (JSONException e) {
            throw new ScrapeException(ScrapeException.Kind.MALFORMED,
                                      "zxart sent something that is not JSON");
        }

        if (!"success".equals(reply.optString("responseStatus", ""))) {
            throw new ScrapeException(ScrapeException.Kind.MALFORMED,
                                      "zxart answered without success");
        }

        return reply;
    }

    /** The rows of one entity, never null. */
    public static List<JSONObject> rows(JSONObject reply, String entity) {
        List<JSONObject> found = new ArrayList<>();
        if (reply == null) return found;

        JSONObject data = reply.optJSONObject("responseData");
        JSONArray list = data == null ? null : data.optJSONArray(entity);

        for (int at = 0; list != null && at < list.length(); at++) {
            JSONObject row = list.optJSONObject(at);
            if (row != null) found.add(row);
        }

        return found;
    }

    /** The service's own count. Real, unlike ZXInfo's 10,000 window cap, so a
     *  shelf can always print it. */
    public static int totalOf(JSONObject reply) {
        return reply == null ? 0 : reply.optInt("totalAmount", 0);
    }

    /**
     * What a bare status means.
     *
     * <b>A 500 from zxart is as likely to be our bug as their bad day.</b> It
     * answers 500 with an empty body for a request it does not understand -
     * measured on {@code export:zxFile}, {@code export:publisher} and the
     * documented-but-broken {@code types:zxProd,zxRelease}. So a 500 on every
     * request means a name in this file is wrong, and one 500 in a hundred is
     * the network. Both are NETWORK because both are worth retrying; the
     * difference shows up in the log, not in the kind.
     */
    public ScrapeException refusalFor(int status) {
        if (status == 429 || status == 403) {
            return new ScrapeException(ScrapeException.Kind.CLOSED,
                    "zxart refused with " + status + ", which is how an archive"
                    + " says an address has been asking too often");
        }

        if (status >= 500) {
            return new ScrapeException(ScrapeException.Kind.NETWORK,
                                       "zxart answered " + status);
        }

        return new ScrapeException(ScrapeException.Kind.MALFORMED,
                                   "zxart answered " + status);
    }

    // --- building a path ------------------------------------------------------------

    /**
     * One request, as a path.
     *
     * A builder rather than six overloads because the segments are optional in
     * combination and their <em>order</em> is fixed; a caller assembling them
     * by hand is a caller who can put {@code order:} before {@code limit:} and
     * find out that zxart ignores what it does not expect.
     */
    public static final class Ask {

        private final String entity;
        private String language = "eng";
        private int start;
        private int limit = 30;
        private String filterName;
        private String filterValue;
        private String order;

        public Ask(String entity) {
            this.entity = entity;
        }

        public Ask language(String code) {
            this.language = code;
            return this;
        }

        /** @param page zero-based; {@code start} is the row it begins at. */
        public Ask page(int page, int size) {
            this.limit = size;
            this.start = page * size;
            return this;
        }

        public Ask filter(String name, String value) {
            this.filterName = name;
            this.filterValue = value;
            return this;
        }

        /** A filter that is a flag rather than a pair - {@code
         *  filter:zxProdCategoryAll}. */
        public Ask flag(String name) {
            this.filterName = name;
            this.filterValue = null;
            return this;
        }

        public Ask order(String order) {
            this.order = order;
            return this;
        }

        public int start() {
            return start;
        }

        public String path() {
            StringBuilder path = new StringBuilder("action:filter/export:")
                    .append(entity)
                    .append("/language:").append(language)
                    .append("/start:").append(start)
                    .append("/limit:").append(limit)
                    .append('/');

            if (filterName != null) {
                path.append("filter:").append(filterName);
                if (filterValue != null) path.append('=').append(encode(filterValue));
                path.append('/');
            }

            if (order != null) path.append("order:").append(order).append('/');

            return path.toString();
        }
    }

    /**
     * Percent-encoding, ours.
     *
     * {@code Uri.encode} would keep this class off the JVM and {@code
     * URLEncoder} turns a space into {@code +}, which is a form encoding: the
     * request measured as working used {@code %20}. Everything outside the
     * unreserved set goes as {@code %XX} over UTF-8, which is safe inside a
     * path segment whatever the value is - a title with a slash, a colon or a
     * semicolon in it would otherwise become extra segments.
     */
    public static String encode(String value) {
        if (value == null) return "";

        StringBuilder out = new StringBuilder();
        byte[] bytes;

        try {
            bytes = value.getBytes("UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            return "";
        }

        for (byte raw : bytes) {
            int b = raw & 0xff;
            boolean unreserved = (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z')
                    || (b >= '0' && b <= '9') || b == '-' || b == '_' || b == '.' || b == '~';

            if (unreserved) {
                out.append((char) b);
            } else {
                out.append('%').append(String.format(Locale.ROOT, "%02X", b));
            }
        }

        return out.toString();
    }

    /**
     * Which of zxart's three languages to ask in.
     *
     * The app speaks nine and zxart three, so six of them read English rather
     * than the Russian zxart defaults to. Taking the language matters because
     * it is what {@code categoriesString}, the category tree's titles and a
     * picture's tags come back in - and it is exactly why an import's folder
     * is decided from a category <em>id</em> and never from a word.
     */
    public static String language(Locale locale) {
        String tag = locale == null ? "" : locale.getLanguage();

        if ("ru".equals(tag)) return "rus";
        if ("es".equals(tag)) return "spa";

        return "eng";
    }

    /**
     * The five escapes zxart's own text arrives with.
     *
     * Measured: {@code Girl &amp; Sea}, {@code Shoot &#039;em up (Shmups)},
     * {@code doom&#039;er}. Done once here rather than in each caller, because
     * a title that reaches a row unescaped is a title somebody sees.
     */
    public static String unescape(String text) {
        if (text == null) return null;

        return text.replace("&#039;", "'")
                   .replace("&quot;", "\"")
                   .replace("&lt;", "<")
                   .replace("&gt;", ">")
                   .replace("&amp;", "&");
    }
}
