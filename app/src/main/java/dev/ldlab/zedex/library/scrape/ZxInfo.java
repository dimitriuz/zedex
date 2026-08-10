package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.library.meta.Meta;

import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ZXInfo, the API over ZXDB - the World of Spectrum database.
 *
 * The second provider, and the first test of whether {@link Provider} was
 * drawn in the right place. Nothing above it changed to make this fit.
 *
 * <b>Spectrum only, and that is the point.</b> ScreenScraper knows every
 * system and knows this one shallowly; ZXDB <em>is</em> this one. Ratings come
 * from hundreds of votes rather than a handful, and it carries whole
 * categories a general scraper has no column for - which machine a game wants,
 * which joystick it speaks, demos and magazines and utilities alongside games.
 *
 * Three differences from ScreenScraper matter to the code rather than the
 * wording:
 *
 * <ul>
 *   <li><b>Media are free.</b> A cover here is a static file on
 *       spectrumcomputing.co.uk, not an API call, so it costs nothing against
 *       anything - see {@link #costPerGame}, which is why that question moved
 *       onto the provider.</li>
 *   <li><b>There is no quota to pace against.</b> Nothing is reported, so
 *       this class paces itself - see {@code MINIMUM_INTERVAL_MS}, and note
 *       that it is not an optional courtesy.</li>
 *   <li><b>No credentials at all</b>, so {@link #configured} is always true
 *       and this provider works in a build made from a clean clone.</li>
 * </ul>
 *
 * <b>Nothing is filtered.</b> {@code /search} offers contenttype, genretype,
 * machinetype and tosectype and this uses none of them. Scraping identifies a
 * file somebody already has, so a filter cannot improve an answer and can only
 * lose one: {@code genretype=GAMES} would drop demos and magazines,
 * {@code machinetype=ZXSPECTRUM} would drop Pentagon work - PENTAGON is a
 * sibling of it in their scheme, not a variant - and most {@code .trd} and
 * {@code .scl} in a collection is exactly that.
 */
public final class ZxInfo implements Provider {

    private static final String TAG = "Zedex";

    private static final String BASE = "https://api.zxinfo.dk/v3/";

    /** Where the files themselves live. Most of what a record names is
     *  relative to this, and none of it is an API call. */
    private static final String FILES = "https://spectrumcomputing.co.uk";

    /**
     * And where the rest of it lives, which is not the same host.
     *
     * <b>A record's paths are relative to two different places.</b> Anything
     * under {@code /pub/} or {@code /zxdb/} is on the archive; anything under
     * {@code /zxscreens/} - which is every rendered loading screen - is on
     * ZXInfo's own media host and 404s on the archive. Both appear inside the
     * same {@code screens} array of the same record, so the array is no guide
     * and the prefix is the only thing that decides.
     *
     * Measured after a scrape wrote a cover and a screenshot and no title
     * screen at all: every loading screen this provider has ever offered was
     * fetched from the wrong host and quietly discarded as a 404, and the
     * failure looked exactly like a game that had none.
     */
    private static final String SCREENS = "https://zxinfo.dk/media";

    /** The prefix that means ZXInfo's own host - see {@link #SCREENS}. */
    private static final String SCREENS_PREFIX = "/zxscreens/";

    /** How many candidates a name search offers. Enough to find the right one
     *  among re-releases and hacks, few enough to read in a dialog. */
    private static final int CANDIDATES = 10;

    private final Http http;

    public ZxInfo(Http http) {
        this.http = http;
    }

    @Override
    public String name() {
        return "ZXInfo";
    }

    /** Always. There is nothing to configure, which is the nicest thing about
     *  this provider and the reason a source clone can scrape at all. */
    @Override
    public boolean configured() {
        return true;
    }

    /** None is reported, ever. {@code Sweep} treats unknown as "do not stop",
     *  which is right - the pacing that keeps this polite is
     *  {@code MINIMUM_INTERVAL_MS}, applied by this class to itself. */
    @Override
    public Quota quota() {
        return Quota.unknown();
    }

    /**
     * Two: the lookup, and then the record.
     *
     * Both paths cost the same. A hash match answers with an id and a title
     * and nothing else, so the record is a second call; a name search answers
     * with candidates, and the one that gets chosen is a second call too.
     * Media are static files and cost nothing, which is the whole reason this
     * question belongs on the provider rather than on {@code Wanted} - the
     * same arithmetic against ScreenScraper is one plus a request per picture.
     */
    @Override
    public int costPerGame(Wanted wanted) {
        return 2;
    }

    /**
     * Half a second between requests, and it is not politeness.
     *
     * There is no published rate limit - the API's own documentation asks
     * clients to be good citizens and to identify themselves, and leaves it
     * there. What is known is what happened while this was being written: a
     * few dozen requests from one address, with no User-Agent, and that
     * address was blocked at the network layer for hours. A sweep is eight
     * hundred games and sixteen hundred requests.
     *
     * So this is the difference between a working feature and getting users
     * blocked, and the number is a guess made deliberately on the safe side.
     * A round trip is a couple of hundred milliseconds anyway, so the real
     * cost of it is far less than it looks.
     *
     * <b>Enforced here rather than by the caller.</b> This class is the only
     * thing that knows when it last asked anything, and it is asked from two
     * places - one game from the popup and eight hundred from a sweep - so a
     * caller-side delay would have to be got right twice and would still miss
     * the second request each game makes.
     */
    private static final long MINIMUM_INTERVAL_MS = 500;

    /** When the last request went out, for {@link #pace}. Per instance, which
     *  matches how it is used: one provider for the life of a sweep. */
    private long lastAsked;

    private void pace() {
        long since = android.os.SystemClock.elapsedRealtime() - lastAsked;

        if (lastAsked != 0 && since < MINIMUM_INTERVAL_MS) {
            try {
                Thread.sleep(MINIMUM_INTERVAL_MS - since);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        lastAsked = android.os.SystemClock.elapsedRealtime();
    }

    // --- finding a game ------------------------------------------------------------

    /**
     * The hash first, the name second - the same shape ScreenScraper uses, for
     * the same reason: a hash the database knows is an answer nobody has to be
     * asked about.
     */
    @Override
    public List<Candidate> search(Game game) throws ScrapeException {
        String md5 = game.md5();

        if (md5 != null && !md5.isEmpty()) {
            Candidate exact = byHash(md5);
            if (exact != null) return Collections.singletonList(exact);
        }

        return byName(game.filename());
    }

    /**
     * {@code /filecheck/{hash}} - the whole reason this provider can fill a
     * collection in unattended.
     *
     * <b>The reply is a flat object keyed {@code entry_id}</b> - verified
     * against the service, and recorded in {@code ZxInfoTest.BY_HASH}. The
     * specification only promises "id and title for found entry"; what arrives
     * carries the machine type, the genre, the publishers and the TOSEC
     * filename as well, which a later version of this could read instead of
     * spending a {@code /games} call on. The leniency about which key holds
     * the id is kept: it costs nothing, it is what made this right before
     * anybody could check, and answering null rather than throwing lets an
     * unreadable reply fall through to the name search exactly as an unknown
     * hash does.
     */
    private Candidate byHash(String md5) throws ScrapeException {
        JSONObject found = object(ask("filecheck/" + Uri.encode(md5)));
        if (found == null) return null;

        String id = firstString(found, "id", "entry_id", "_id");
        if (id == null || id.isEmpty()) return null;

        // Certain, and the only place in this class that says so: a hash
        // matched, which is the one thing that is not a guess.
        return new Candidate(id, firstString(found, "title", "name"), null, null, true);
    }

    /**
     * {@code /search}, on the title alone.
     *
     * {@code titlesonly} because the default matches publishers and authors
     * too, and a file called "Ocean.tap" should not bring back everything
     * Ocean ever published. Their own ranking does the rest, and does it
     * better than anything written here would: the documentation says original
     * entries are prioritised over modified ones, which is exactly the
     * question a collection full of hacks and re-releases asks.
     */
    private List<Candidate> byName(String filename) throws ScrapeException {
        String title = titleOf(filename);
        if (title.isEmpty()) return Collections.emptyList();

        JSONObject reply = object(ask("search?query=" + Uri.encode(title)
                                      + "&titlesonly=true&mode=compact"
                                      + "&size=" + CANDIDATES));
        if (reply == null) return Collections.emptyList();

        List<Candidate> found = new ArrayList<>();

        for (JSONObject hit : hits(reply)) {
            JSONObject source = hit.optJSONObject("_source");
            if (source == null) continue;

            String id = hit.optString("_id", "");
            if (id.isEmpty()) continue;

            // Never exact: a name match is a guess however good the ranking,
            // and acting on a guess silently is one game's cover on another.
            found.add(new Candidate(id, source.optString("title", null),
                                    year(source), publisher(source), false));
        }

        return found;
    }

    /**
     * A filename, reduced to something worth searching for.
     *
     * A Spectrum collection is full of names like
     * {@code Arkanoid 48K (1987)(Imagine) (1).tzx} - the extension, the
     * machine, the year, the publisher and a disambiguating number, none of
     * which belongs in a title search. Everything from the first bracket is
     * dropped, which is where all of that lives by convention.
     */
    static String titleOf(String filename) {
        if (filename == null) return "";

        String title = filename;

        int dot = title.lastIndexOf('.');
        if (dot > 0) title = title.substring(0, dot);

        int bracket = title.length();
        for (String opener : new String[] { "(", "[", "{" }) {
            int at = title.indexOf(opener);
            if (at > 0 && at < bracket) bracket = at;
        }
        title = title.substring(0, bracket);

        // "48K", "128K" and the rest ride outside the brackets often enough to
        // be worth removing, and never appear in a real title.
        title = title.replaceAll("(?i)\\b(48|128)k\\b", " ");

        return title.replaceAll("\\s+", " ").trim();
    }

    // --- fetching one ----------------------------------------------------------------

    @Override
    public Scraped fetch(Candidate candidate, Wanted wanted) throws ScrapeException {
        JSONObject reply = object(ask("games/" + Uri.encode(candidate.handle) + "?mode=compact"));
        if (reply == null) throw malformed("no record for entry " + candidate.handle);

        JSONObject game = reply.optJSONObject("_source");
        if (game == null) throw malformed("entry " + candidate.handle + " carries no _source");

        return new Scraped(meta(game), media(game, wanted));
    }

    /**
     * The facts, as {@link Meta} holds them.
     *
     * The source is left for the caller, the same as every provider: whether a
     * scraped row counts as this app's own is a question about ownership, not
     * about the reply.
     *
     * Two of these are judgement calls worth naming. {@code remarks} becomes
     * the description because ZXDB has no description as such and remarks is
     * the only prose about the game itself. And the <em>developer</em> is the
     * first author, a person, where ES-DE and ScreenScraper both mean a
     * company there - ZXDB keeps people and companies apart, and a person who
     * wrote the game is closer to what that row is for than the publisher
     * repeated twice.
     */
    private static Meta meta(JSONObject game) {
        return Meta.at(null)
                .name(game.optString("title", null))
                .desc(text(game, "remarks"))
                .developer(firstAuthor(game))
                .publisher(publisher(game))
                .genre(text(game, "genreType"))
                .subgenre(text(game, "genreSubType"))
                .released(released(game))
                .players(players(game))
                .rating(rating(game))
                .machine(text(game, "machineType"))
                .inputs(inputs(game))
                .authors(authors(game))
                .price(price(game))
                .series(seriesName(game))
                .seriesGames(seriesGames(game))
                .compilations(links(game, "inCompilations"))
                .contents(links(game, "compilationContents"))
                .build();
    }

    /**
     * Who made it, with a role where there is one.
     *
     * ZXDB gives the main creators no role at all and keeps them for
     * specialists - a load screen, the music - so this reads as a list of
     * names with the occasional qualifier rather than a table of jobs. Where
     * somebody has several, they are joined: "(Music, Load Screen)".
     *
     * The first of these is also the developer - see {@link #firstAuthor} -
     * which is not a duplication worth removing: one is the row every other
     * provider fills in, and this is the credits.
     */
    private static List<String> authors(JSONObject game) {
        List<String> found = new ArrayList<>();
        JSONArray authors = game.optJSONArray("authors");
        if (authors == null) return found;

        for (int at = 0; at < authors.length(); at++) {
            JSONObject author = authors.optJSONObject(at);
            if (author == null) continue;

            String name = text(author, "name");
            if (name == null) continue;

            String roles = roles(author);
            found.add(roles == null ? name : name + " (" + roles + ")");
        }

        return found;
    }

    /** This author's roles as one phrase, or null where the record names
     *  none - which is most of them. */
    private static String roles(JSONObject author) {
        JSONArray roles = author.optJSONArray("roles");
        if (roles == null || roles.length() == 0) return null;

        List<String> named = new ArrayList<>();

        for (int at = 0; at < roles.length(); at++) {
            JSONObject role = roles.optJSONObject(at);
            String name = role == null ? null : text(role, "roleName");

            if (name != null) named.add(name);
        }

        return named.isEmpty() ? null : String.join(", ", named);
    }

    /**
     * What it cost, formatted the way the record spells it.
     *
     * {@code prefix} says which side the symbol goes: a pound sign leads and
     * a "Ptas" follows, and getting it the wrong way round makes a price
     * nobody in either country would recognise.
     */
    private static String price(JSONObject game) {
        JSONObject price = game.optJSONObject("originalPrice");
        if (price == null) return null;

        String amount = text(price, "amount");
        if (amount == null) return null;

        String currency = text(price, "currency");
        if (currency == null) return amount;

        return price.optInt("prefix", 0) == 1 ? currency + amount
                                              : amount + " " + currency;
    }

    /** The series' own name, which ZXDB hangs off each of its members
     *  rather than stating once. */
    private static String seriesName(JSONObject game) {
        JSONArray series = game.optJSONArray("series");
        if (series == null) return null;

        for (int at = 0; at < series.length(); at++) {
            JSONObject one = series.optJSONObject(at);
            String name = one == null ? null : text(one, "groupName");

            if (name != null) return name;
        }

        return null;
    }

    /**
     * The rest of the series.
     *
     * <b>Without this game.</b> ZXDB's list includes the entry you asked
     * about, and a series that lists the game being looked at as one of its
     * related games reads as a mistake. Matched on the title rather than the
     * id, because the id of the entry being fetched is not in the reply body.
     */
    private static List<Meta.Link> seriesGames(JSONObject game) {
        List<Meta.Link> found = new ArrayList<>();
        String title = game.optString("title", null);

        for (Meta.Link link : links(game, "series")) {
            if (title != null && title.equals(link.title)) continue;
            found.add(link);
        }

        return found;
    }

    /**
     * Another entry named in this one: its id and its title.
     *
     * One shape for four different fields, because ZXDB uses one -
     * {@code series}, {@code inCompilations} and {@code compilationContents}
     * are all arrays of the same object with different extras hung off them.
     */
    private static List<Meta.Link> links(JSONObject game, String field) {
        List<Meta.Link> found = new ArrayList<>();
        JSONArray entries = game.optJSONArray(field);
        if (entries == null) return found;

        for (int at = 0; at < entries.length(); at++) {
            JSONObject entry = entries.optJSONObject(at);
            if (entry == null) continue;

            String title = text(entry, "title");
            int id = entry.optInt("entry_id", -1);

            if (title == null || id < 0) continue;
            found.add(new Meta.Link(Integer.toString(id), title));
        }

        return found;
    }

    /** ES-DE's stamp, from a year alone - the same shape ScreenScraper's
     *  dates are turned into, so the two providers sort together. */
    private static String released(JSONObject game) {
        int year = game.optInt("originalYearOfRelease", 0);
        return year > 0 ? year + "0101T000000" : null;
    }

    private static String players(JSONObject game) {
        int players = game.optInt("numberOfPlayers", 0);
        return players > 0 ? Integer.toString(players) : null;
    }

    /**
     * ZXDB scores out of ten; the store keeps a fraction, as ES-DE writes it.
     *
     * Worth having where ScreenScraper's often is not: this one is 8.48 from
     * 756 votes on a well-known game, where a general scraper has a handful.
     */
    private static String rating(JSONObject game) {
        JSONObject score = game.optJSONObject("score");
        if (score == null) return null;

        double outOf10 = score.optDouble("score", -1);
        if (outOf10 < 0) return null;

        return String.format(java.util.Locale.US, "%.4f", outOf10 / 10.0);
    }

    /**
     * Which input devices the game accepts.
     *
     * ZXDB keeps these as objects with one key, so this flattens them - the
     * shape carries nothing the list does not. What they are <em>for</em> is
     * choosing the joystick interface, which is a separate piece of work; this
     * only records what the record says.
     */
    private static List<String> inputs(JSONObject game) {
        JSONArray controls = game.optJSONArray("controls");
        if (controls == null) return Collections.emptyList();

        List<String> found = new ArrayList<>();

        for (int at = 0; at < controls.length(); at++) {
            JSONObject one = controls.optJSONObject(at);
            String control = one == null ? null : text(one, "control");
            if (control != null) found.add(control);
        }

        return found;
    }

    private static String firstAuthor(JSONObject game) {
        JSONArray authors = game.optJSONArray("authors");
        if (authors == null || authors.length() == 0) return null;

        JSONObject first = authors.optJSONObject(0);
        return first == null ? null : text(first, "name");
    }

    private static String publisher(JSONObject game) {
        JSONArray publishers = game.optJSONArray("publishers");
        if (publishers == null || publishers.length() == 0) return null;

        JSONObject first = publishers.optJSONObject(0);
        return first == null ? null : text(first, "name");
    }

    private static String year(JSONObject game) {
        int year = game.optInt("originalYearOfRelease", 0);
        return year > 0 ? Integer.toString(year) : null;
    }

    // --- media --------------------------------------------------------------------------

    /**
     * ZXInfo's own media names, mapped onto the folder names this app's media
     * folder uses - which are ES-DE's, so everything downstream treats all
     * providers identically. See {@code Artwork}.
     *
     * Eight. Five are ES-DE's own; the last two are this app's, because ES-DE
     * has no folder for a map or an advertisement and inventing one is
     * cheaper than not having them. A <em>media scan</em> needed no new
     * folder at all - it is a photograph of the cassette or disk, which is
     * exactly what ES-DE's {@code physicalmedia} holds.
     *
     * Worth having, measured against the whole of ZXDB: a media scan is on
     * 11.8% of Spectrum entries, a game map on 5.3%, an advertisement on
     * 4.0%. What is still left out - the loading screen as a raw {@code
     * .scr}, instructions as text, pokes, music - has no folder yet and is
     * left for the work that gives it one, rather than being fetched into
     * somewhere nothing looks.
     */
    private static final Map<String, String> MEDIA_FOLDERS = mediaFolders();

    private static Map<String, String> mediaFolders() {
        Map<String, String> folders = new LinkedHashMap<>();
        folders.put("Inlay - Front", "covers");
        folders.put("Inlay - Back", "backcovers");
        folders.put("Running screen", "screenshots");
        folders.put("Loading screen", "titlescreens");
        folders.put("Instructions", "manuals");

        // A scan of the cassette or the disk itself - ES-DE's own name for
        // that photograph, so nothing downstream needs telling.
        folders.put("Media scan", "physicalmedia");

        // And two of ours. See Artwork.PICTURE_FOLDERS, which draws them.
        folders.put("Game map", "maps");
        folders.put("Advertisement", "adverts");

        // Not a picture at all: a few lines of text naming cheats, in the
        // format the poke database that ships with the app is built from. On
        // 7.9% of Spectrum entries, and the bundled database finds pokes for
        // about a third of a collection - so this is what tops it up.
        folders.put("POK pokes file", "pokes");

        return Collections.unmodifiableMap(folders);
    }

    /**
     * Everything wanted, from the two places a record keeps it.
     *
     * {@code screens} and {@code additionalDownloads} overlap - a running
     * screen appears in both - so the first found for a folder wins and the
     * rest are ignored, which is also how the preference order within a folder
     * is expressed.
     *
     * No checksums: ZXDB does not publish one per file, so {@link Medium#md5}
     * is null and {@code Downloads} skips the verification it does for
     * ScreenScraper. A truncated download is caught by the length check that
     * follows it either way.
     */
    private static List<Medium> media(JSONObject game, Wanted wanted) {
        Map<String, Medium> byFolder = new LinkedHashMap<>();

        collect(byFolder, game.optJSONArray("screens"), "url", wanted);
        collect(byFolder, game.optJSONArray("additionalDownloads"), "path", wanted);

        return new ArrayList<>(byFolder.values());
    }

    private static void collect(Map<String, Medium> into, JSONArray items,
                                String where, Wanted wanted) {
        if (items == null) return;

        for (int at = 0; at < items.length(); at++) {
            JSONObject item = items.optJSONObject(at);
            if (item == null) continue;

            String folder = MEDIA_FOLDERS.get(item.optString("type", ""));
            if (folder == null || !wanted.wants(folder) || into.containsKey(folder)) continue;

            String path = item.optString(where, "");
            if (path.isEmpty()) continue;

            String format = item.optString("format", "");
            if (!usable(folder, format, path)) continue;

            into.put(folder, new Medium(folder, hostOf(path) + path,
                                        extensionOf(path), null));
        }
    }

    /**
     * Whether this app can actually show the thing.
     *
     * An allow-list rather than a list of things to reject, which is the
     * difference between a new format arriving as a missing picture and
     * arriving as a broken one. Instructions are what make it earn its keep:
     * they come as PDF and as text, and the manual viewer reads PDFs.
     *
     * <b>A {@code .scr} counts, and only for the loading screen.</b> It is
     * raw Spectrum memory - the one format in a record that only this app can
     * make sense of, and the one a third of the database carries its loading
     * screen as, against a fifth with a front inlay. {@code Downloads} turns
     * it into a picture as it lands, so nothing downstream ever meets one.
     *
     * It stays second to a real picture: {@code screens} is read before
     * {@code additionalDownloads} and a folder keeps the first thing it is
     * given, so a game with a PNG loading screen still gets the PNG. The dump
     * is what fills in the far commoner case of a game with neither.
     */
    private static boolean usable(String folder, String format, String path) {
        String extension = extensionOf(path);

        if ("manuals".equals(folder)) return "pdf".equals(extension);
        if ("pokes".equals(folder)) return "pok".equals(extension);
        if ("scr".equals(extension)) return "titlescreens".equals(folder);

        return "png".equals(extension) || "jpg".equals(extension)
                || "jpeg".equals(extension) || "gif".equals(extension);
    }

    /** Which of the two hosts a path is relative to - see {@link #SCREENS}. */
    private static String hostOf(String path) {
        return path.startsWith(SCREENS_PREFIX) ? SCREENS : FILES;
    }

    /** From the path itself rather than the {@code format} text, which says
     *  "Picture" for a png and "Picture (JPG)" for a jpg. */
    private static String extensionOf(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) return "";

        return path.substring(dot + 1).toLowerCase(java.util.Locale.US);
    }

    // --- asking ---------------------------------------------------------------------------

    /**
     * One request, or null when the service says it has nothing.
     *
     * A 404 is an answer and not a failure - most of what is asked about is
     * obscure, and treating "never heard of it" as an error would stop a
     * collection-wide run within a dozen games. That lesson is ScreenScraper's,
     * learned the expensive way, and it applies here identically.
     */
    private String ask(String path) throws ScrapeException {
        pace();

        try {
            Http.Reply reply = http.get(BASE + path);

            if (reply.status == 404) return null;
            if (!reply.ok()) throw refusalFor(reply.status);

            return reply.body;
        } catch (Http.Refused refused) {
            throw refusalFor(refused.status);
        } catch (IOException e) {
            // Never the URL: nothing here carries a credential, but the habit
            // is worth keeping across providers so no future one leaks by
            // being the exception.
            throw new ScrapeException(ScrapeException.Kind.NETWORK,
                                      "cannot reach ZXInfo: " + e.getMessage(), e);
        }
    }

    /**
     * What a status means here.
     *
     * No quota exists, so nothing maps to {@code QUOTA_EXCEEDED} - but 429 and
     * 403 both have to stop a sweep rather than let it carry on hammering,
     * because the way this service says "enough" is by refusing an address
     * for hours. {@code CLOSED} is the kind that stops a run and says so
     * plainly, which is the honest description of being blocked.
     */
    @Override
    public ScrapeException refusalFor(int status) {
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

    private static ScrapeException malformed(String what) {
        return new ScrapeException(ScrapeException.Kind.MALFORMED, what);
    }

    private static JSONObject object(String body) throws ScrapeException {
        if (body == null || body.isEmpty()) return null;

        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            // An array at the top level is legitimate for filecheck; see
            // byHash. Anything else is a reply this build cannot read.
            try {
                JSONArray array = new JSONArray(body);
                return array.length() == 0 ? null : array.optJSONObject(0);
            } catch (JSONException notThatEither) {
                Log.w(TAG, "ZXInfo sent something that is not JSON");
                throw malformed("ZXInfo sent something that is not JSON");
            }
        }
    }

    private static List<JSONObject> hits(JSONObject reply) {
        List<JSONObject> found = new ArrayList<>();

        JSONObject outer = reply.optJSONObject("hits");
        JSONArray inner = outer == null ? null : outer.optJSONArray("hits");
        if (inner == null) return found;

        for (int at = 0; at < inner.length(); at++) {
            JSONObject hit = inner.optJSONObject(at);
            if (hit != null) found.add(hit);
        }

        return found;
    }

    /** The first of these keys that holds something, or null. */
    private static String firstString(JSONObject from, String... keys) {
        for (String key : keys) {
            String value = text(from, key);
            if (value != null) return value;
        }
        return null;
    }

    /** Null rather than the string "null", which is what optString gives for
     *  a JSON null and what would otherwise end up in the store. */
    private static String text(JSONObject from, String key) {
        if (from.isNull(key)) return null;

        String value = from.optString(key, "").trim();
        return value.isEmpty() ? null : value;
    }
}
