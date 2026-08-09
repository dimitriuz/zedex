package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.library.meta.Meta;

import android.content.Context;
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
 * ScreenScraper.fr, through its api2.
 *
 * The first of three, and the one the app's existing metadata already looks
 * like: ES-DE scrapes from here by default, so a collection half-linked and
 * half-scraped stays consistent rather than mixing two services' idea of what
 * a game is called.
 *
 * <b>Hash first, filename second.</b> The API matches a dump by its MD5 and
 * answers with exactly one game when it knows it, which is what lets a scrape
 * fill itself in without asking anybody anything. A file the database has
 * never seen falls through to a search on the filename, which is where several
 * candidates come from and where a person has to choose. Both are one request;
 * the fallback is not a second round trip unless the first found nothing.
 *
 * <b>Every reply carries the quota</b> - today's count, today's allowance and
 * how many requests may be in flight - so a multi-scrape can pace itself
 * rather than discovering the limit by hitting it. See {@link Quota}.
 *
 * Credentials come from the build; see {@code app/build.gradle}. Absent is a
 * supported state and {@link #configured} is how everything else finds out.
 * A user's own ScreenScraper login is optional and buys a real quota and more
 * threads - the fields are here from the start because everything above is
 * built on what they return, even though nothing writes them yet.
 */
public final class ScreenScraper implements Provider {

    private static final String TAG = "Zedex";

    private static final String BASE = "https://api.screenscraper.fr/api2/";

    /** ScreenScraper's own id for the ZX Spectrum. */
    private static final int SYSTEM = 76;

    /** What this app calls itself to the API. Their logs are how a
     *  misbehaving client gets noticed, so it says which one it is. */
    private static final String SOFTNAME = "Zedex";

    /**
     * ScreenScraper's media types, mapped onto the folder names this app's
     * media folder uses - which are ES-DE's, so that everything downstream
     * treats all three providers identically. See {@code Artwork}.
     *
     * In preference order within each folder: the API offers several regions
     * and several kinds that mean roughly the same thing, and the first that
     * exists wins. {@code box-2D} is the cover a shelf would show; {@code ss}
     * is an in-game screenshot; {@code sstitle} is the title screen.
     */
    private static final Map<String, String[]> MEDIA_FOLDERS = mediaFolders();

    private static Map<String, String[]> mediaFolders() {
        Map<String, String[]> folders = new LinkedHashMap<>();
        folders.put("covers", new String[] { "box-2D", "box-texture", "box-3D" });
        folders.put("backcovers", new String[] { "box-2D-back" });
        folders.put("physicalmedia", new String[] { "support-2D", "support-texture" });
        folders.put("miximages", new String[] { "mixrbv1", "mixrbv2" });
        folders.put("screenshots", new String[] { "ss" });
        folders.put("titlescreens", new String[] { "sstitle" });
        return Collections.unmodifiableMap(folders);
    }

    private static final String VIDEO_FOLDER = "videos";
    private static final String[] VIDEO_TYPES = { "video-normalized", "video" };

    private static final String MANUAL_FOLDER = "manuals";
    private static final String[] MANUAL_TYPES = { "manuel" };

    private final Http http;
    private final String devId;
    private final String devPassword;

    /** The user's own ScreenScraper login, or empty. Optional, and what buys
     *  a usable daily allowance and more than one thread. */
    private final String userName;
    private final String userPassword;

    private Quota quota = Quota.unknown();

    public ScreenScraper(Context context, Http http) {
        this(http,
             context.getString(R.string.screenscraper_dev_id),
             context.getString(R.string.screenscraper_dev_password),
             "", "");
    }

    ScreenScraper(Http http, String devId, String devPassword,
                  String userName, String userPassword) {
        this.http = http;
        this.devId = devId == null ? "" : devId;
        this.devPassword = devPassword == null ? "" : devPassword;
        this.userName = userName == null ? "" : userName;
        this.userPassword = userPassword == null ? "" : userPassword;
    }

    /**
     * Extra query parameters, for the live tests only.
     *
     * ScreenScraper has a debug mode that can force the quota counters, the
     * account's level and so its thread allowance - which is the only way to
     * see a real 429 or a real thread refusal rather than one written from
     * the documentation. Those are exactly the paths a multi-scrape is built
     * on and exactly the ones a live service will not produce on request.
     *
     * Package private and reachable only through the package-private
     * constructor, so nothing the app builds can set it: {@link
     * #ScreenScraper(Context, Http)} does not take it and there is no setter
     * on the interface. The debug password itself is never stored here, in
     * the build, or in the APK - the test that uses it takes it from an
     * instrumentation argument, so it lives on somebody's command line and
     * nowhere else.
     */
    private String debugQuery = "";

    void debugWith(String query) {
        debugQuery = query == null ? "" : query;
    }

    @Override
    public String name() {
        return "ScreenScraper";
    }

    @Override
    public boolean configured() {
        return !devId.isEmpty() && !devPassword.isEmpty();
    }

    @Override
    public Quota quota() {
        return quota;
    }

    // --- searching ----------------------------------------------------------------

    @Override
    public List<Candidate> search(Game game) throws ScrapeException {
        requireConfigured();

        // The exact question first. A hash the database knows answers with one
        // game and no ambiguity at all, which is the whole reason to pay for
        // reading the file.
        String md5 = game.md5();
        if (md5 != null && !md5.isEmpty()) {
            JSONObject found = ask(query(game, md5, null));
            if (found != null) return Collections.singletonList(candidate(found, true));
        }

        // And the vague one. Everything from here may be several games, and
        // none of them is certain.
        JSONObject byName = ask(query(game, null, game.filename()));
        if (byName == null) return Collections.emptyList();

        return Collections.singletonList(candidate(byName, false));
    }

    /**
     * One {@code jeuInfos} request.
     *
     * The romnom search and the md5 search are the same endpoint with
     * different arguments, which is why this is one method: ScreenScraper does
     * not have a separate "search" - it answers with its single best match and
     * says how it got there. Several candidates arrive as a game whose
     * {@code noms} carry alternatives, not as a list of games.
     */
    private String query(Game game, String md5, String romName) {
        StringBuilder url = new StringBuilder(BASE).append("jeuInfos.php?output=json");

        add(url, "devid", devId);
        add(url, "devpassword", devPassword);
        add(url, "softname", SOFTNAME);

        if (!userName.isEmpty()) {
            add(url, "ssid", userName);
            add(url, "sspassword", userPassword);
        }

        add(url, "systemeid", String.valueOf(SYSTEM));

        if (md5 != null) add(url, "md5", md5);
        if (romName != null) add(url, "romnom", romName);
        if (game.size() > 0) add(url, "romtaille", String.valueOf(game.size()));

        // Empty in every build; see debugWith.
        url.append(debugQuery);

        return url.toString();
    }

    private static void add(StringBuilder url, String key, String value) {
        url.append('&').append(key).append('=').append(Uri.encode(value));
    }

    /**
     * Asks, and turns everything that is not a game into the right refusal.
     *
     * @return the {@code jeu} object, or null when the service simply does not
     *         know this game - which is an ordinary answer for most of a
     *         Spectrum collection and deliberately not an exception.
     */
    private JSONObject ask(String url) throws ScrapeException {
        Http.Reply reply;

        try {
            reply = http.get(url);
        } catch (IOException e) {
            throw new ScrapeException(ScrapeException.Kind.NETWORK, "cannot reach " + BASE, e);
        }

        if (!reply.ok()) throw refusal(reply);

        JSONObject response;
        try {
            response = new JSONObject(reply.body).getJSONObject("response");
        } catch (JSONException e) {
            // A 200 whose body is not the shape expected. ScreenScraper does
            // answer some refusals this way rather than with a status.
            throw softRefusal(reply.body, e);
        }

        readQuota(response);

        JSONObject game = response.optJSONObject("jeu");
        return game;
    }

    /**
     * What a non-200 means.
     *
     * The codes are ScreenScraper's own and each wants different handling from
     * a multi-scrape, which is why they are told apart rather than collapsed
     * into "it failed".
     */
    @Override
    public ScrapeException refusalFor(int status) {
        return refusal(status, "");
    }

    private static ScrapeException refusal(Http.Reply reply) {
        String detail = reply.body.trim();
        if (detail.length() > 200) detail = detail.substring(0, 200);

        return refusal(reply.status, detail);
    }

    private static ScrapeException refusal(int status, String detail) {
        switch (status) {
            case 401:
            case 403:
            case 426:
                return new ScrapeException(ScrapeException.Kind.BAD_CREDENTIALS,
                        "ScreenScraper refused the credentials: " + detail);
            case 423:
                return new ScrapeException(ScrapeException.Kind.CLOSED,
                        "ScreenScraper is closed to this account: " + detail);
            case 429:
                return new ScrapeException(ScrapeException.Kind.QUOTA_EXCEEDED,
                        "no requests left today: " + detail);
            case 430:
            case 431:
                return new ScrapeException(ScrapeException.Kind.THREAD_LIMIT,
                        "too many requests at once: " + detail);
            case 404:
                // Their "no such game" for some queries. Not a failure.
                return new ScrapeException(ScrapeException.Kind.MALFORMED,
                        "nothing found, reported as 404");
            default:
                return new ScrapeException(ScrapeException.Kind.NETWORK,
                        "HTTP " + status + ": " + detail);
        }
    }

    /** A 200 that is not a game. Their text is the only clue, so it is read
     *  rather than guessed at. */
    private static ScrapeException softRefusal(String body, Throwable cause) {
        String lower = body == null ? "" : body.toLowerCase(java.util.Locale.ROOT);

        if (lower.contains("quota")) {
            return new ScrapeException(ScrapeException.Kind.QUOTA_EXCEEDED, body, cause);
        }
        if (lower.contains("closed") || lower.contains("ferme")) {
            return new ScrapeException(ScrapeException.Kind.CLOSED, body, cause);
        }
        if (lower.contains("erreur de login") || lower.contains("api totalement fermé")) {
            return new ScrapeException(ScrapeException.Kind.BAD_CREDENTIALS, body, cause);
        }
        return new ScrapeException(ScrapeException.Kind.MALFORMED,
                "cannot read ScreenScraper's reply", cause);
    }

    /** Their {@code ssuser} block, which rides along with every answer. */
    private void readQuota(JSONObject response) {
        JSONObject user = response.optJSONObject("ssuser");
        if (user == null) return;

        quota = new Quota(
                number(user.optString("requeststoday", "")),
                number(user.optString("maxrequestsperday", "")),
                number(user.optString("maxthreads", "")));
    }

    private static int number(String text) {
        try {
            return text == null || text.isEmpty() ? -1 : Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // --- fetching ------------------------------------------------------------------

    /**
     * ScreenScraper answers a search with the whole game already, so a fetch
     * has nothing left to ask.
     *
     * The candidate carries its own reply, parsed once. That is not true of
     * every provider - the interface allows a second request - but making one
     * here would spend a second of the day's allowance to be told what is
     * already in hand, and the allowance is the scarce thing.
     */
    @Override
    public Scraped fetch(Candidate candidate, Wanted wanted) throws ScrapeException {
        requireConfigured();

        JSONObject game = parsed.get(candidate.handle);
        if (game == null) {
            throw new ScrapeException(ScrapeException.Kind.MALFORMED,
                    "that candidate is not one of this provider's");
        }

        return new Scraped(meta(game), wanted.any() ? media(game, wanted)
                                                    : Collections.emptyList());
    }

    /** Every {@code jeu} this instance has parsed, by handle - see {@link
     *  #fetch}. Small: a search answers with one game, and a scrape is one
     *  game at a time. */
    private final Map<String, JSONObject> parsed = new LinkedHashMap<>();

    private Candidate candidate(JSONObject game, boolean exact) {
        String handle = game.optString("id", "");
        parsed.put(handle, game);

        return new Candidate(handle, bestName(game), year(game), company(game, "editeur"), exact);
    }

    /**
     * The facts, as {@link Meta} holds them.
     *
     * The source is left for the caller to set: whether a scraped row counts
     * as this app's own is a question about ownership, not about the reply,
     * and it is settled where the ownership rules live.
     */
    private Meta meta(JSONObject game) {
        return new Meta(
                null,                      // the caller knows the path; the API does not
                bestName(game),
                text(game, "synopsis"),
                company(game, "developpeur"),
                company(game, "editeur"),
                genre(game),
                released(game),
                players(game),
                rating(game),
                null);
    }

    /**
     * The name for the region a person here is most likely to want.
     *
     * ScreenScraper answers with every region's title at once. World first,
     * then Europe, then the rest, and finally whatever is there - a Spectrum
     * game with only a Spanish title is still better named than not named.
     */
    private static String bestName(JSONObject game) {
        return regional(game.optJSONArray("noms"), "text",
                        new String[] { "wor", "eu", "ss", "us", "jp" });
    }

    private static String genre(JSONObject game) {
        JSONArray genres = game.optJSONArray("genres");
        if (genres == null) return null;

        List<String> names = new ArrayList<>();
        for (int at = 0; at < genres.length(); at++) {
            JSONObject one = genres.optJSONObject(at);
            String name = one == null ? null : language(one.optJSONArray("noms"));
            if (name != null && !name.isEmpty() && !names.contains(name)) names.add(name);
        }

        // Joined the way ES-DE writes a compound genre, which Filters.genresOf
        // already knows how to split again.
        return names.isEmpty() ? null : android.text.TextUtils.join(", ", names);
    }

    private static String released(JSONObject game) {
        String date = regional(game.optJSONArray("dates"), "text",
                               new String[] { "wor", "eu", "ss", "us", "jp" });
        if (date == null || date.length() < 4) return null;

        // ES-DE's own stamp, which is what Meta.year reads and what the editor
        // writes - see Meta#released. Their date is a year, or a full one.
        String year = date.substring(0, 4);
        return year.matches("\\d{4}") ? year + "0101T000000" : null;
    }

    private static String players(JSONObject game) {
        return text(game, "joueurs");
    }

    /**
     * Their rating is out of twenty; {@link Meta#rating} is a fraction of one.
     *
     * Converted here rather than stored as theirs, because everything that
     * reads it - the pane's stars, the filter's threshold - is built on the
     * fraction, and a second scale in the store would be a second thing to get
     * wrong.
     */
    private static String rating(JSONObject game) {
        String note = text(game, "note");
        if (note == null) return null;

        try {
            float outOfTwenty = Float.parseFloat(note.trim());
            if (outOfTwenty < 0f || outOfTwenty > 20f) return null;

            return String.format(java.util.Locale.ROOT, "%.4f", outOfTwenty / 20f);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String year(JSONObject game) {
        String stamp = released(game);
        return stamp == null ? null : stamp.substring(0, 4);
    }

    private static String company(JSONObject game, String key) {
        JSONObject who = game.optJSONObject(key);
        if (who == null) return null;

        String name = who.optString("text", "");
        return name.isEmpty() ? null : name;
    }

    /** A {@code text} field that may be a plain string or a per-language
     *  array, which their schema is inconsistent about. */
    private static String text(JSONObject game, String key) {
        Object value = game.opt(key);

        if (value instanceof String) {
            String plain = (String) value;
            return plain.isEmpty() ? null : plain;
        }
        if (value instanceof JSONObject) {
            String nested = ((JSONObject) value).optString("text", "");
            return nested.isEmpty() ? null : nested;
        }
        if (value instanceof JSONArray) {
            return language((JSONArray) value);
        }
        return null;
    }

    /** English first, then whatever is there - a description in French beats
     *  no description. */
    private static String language(JSONArray entries) {
        return pick(entries, "langue", new String[] { "en", "wor" }, "text");
    }

    private static String regional(JSONArray entries, String field, String[] order) {
        return pick(entries, "region", order, field);
    }

    private static String pick(JSONArray entries, String key, String[] order, String field) {
        if (entries == null) return null;

        for (String wanted : order) {
            for (int at = 0; at < entries.length(); at++) {
                JSONObject one = entries.optJSONObject(at);
                if (one == null) continue;

                if (wanted.equalsIgnoreCase(one.optString(key, ""))) {
                    String value = one.optString(field, "");
                    if (!value.isEmpty()) return value;
                }
            }
        }

        for (int at = 0; at < entries.length(); at++) {
            JSONObject one = entries.optJSONObject(at);
            if (one == null) continue;

            String value = one.optString(field, "");
            if (!value.isEmpty()) return value;
        }
        return null;
    }

    // --- media ------------------------------------------------------------------------

    /**
     * Their media list, filtered to what was asked for and named in this app's
     * own folders.
     *
     * At most one per folder, and the first type in each folder's preference
     * order that actually exists - the same rule {@code Artwork} reads by, so
     * what is written is what will be found.
     */
    private List<Medium> media(JSONObject game, Wanted wanted) {
        JSONArray all = game.optJSONArray("medias");
        if (all == null) return Collections.emptyList();

        List<Medium> found = new ArrayList<>();

        for (Map.Entry<String, String[]> folder : MEDIA_FOLDERS.entrySet()) {
            if (!wanted.wants(folder.getKey())) continue;

            Medium one = firstOf(all, folder.getKey(), folder.getValue());
            if (one != null) found.add(one);
        }

        if (wanted.wants(VIDEO_FOLDER)) {
            Medium one = firstOf(all, VIDEO_FOLDER, VIDEO_TYPES);
            if (one != null) found.add(one);
        }
        if (wanted.wants(MANUAL_FOLDER)) {
            Medium one = firstOf(all, MANUAL_FOLDER, MANUAL_TYPES);
            if (one != null) found.add(one);
        }

        return found;
    }

    private static Medium firstOf(JSONArray all, String folder, String[] types) {
        for (String type : types) {
            for (int at = 0; at < all.length(); at++) {
                JSONObject one = all.optJSONObject(at);
                if (one == null) continue;
                if (!type.equalsIgnoreCase(one.optString("type", ""))) continue;

                String url = one.optString("url", "");
                if (url.isEmpty()) continue;

                String extension = one.optString("format", "");
                if (extension.isEmpty()) extension = "png";

                String md5 = one.optString("md5", "");
                return new Medium(folder, url, extension, md5.isEmpty() ? null : md5);
            }
        }
        return null;
    }

    private void requireConfigured() throws ScrapeException {
        if (!configured()) {
            throw new ScrapeException(ScrapeException.Kind.NOT_CONFIGURED,
                    "this build has no ScreenScraper developer account");
        }
    }
}
