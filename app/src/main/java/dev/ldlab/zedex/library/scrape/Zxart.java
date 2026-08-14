package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.library.meta.Meta;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * zxart.ee, the demoscene and games archive, as a scraping source.
 *
 * <b>The confirmation is the whole reason this provider is worth having
 * beside the other two.</b> zxart has no md5 filter to ask with -
 * {@code zxProdMd5} and {@code zxReleaseMd5} are both among the names {@link
 * ZxartApi} measured as ignored - but every release lists {@code
 * releaseStructure}, a recursive tree carrying an md5 for the archive
 * <em>and</em> for every file packed inside it. So a name search's
 * candidates can be confirmed against the file actually on disk: an unzipped
 * {@code .tzx} matches the same row its zip does, because both hashes live on
 * the same release. No other source here can be certain about a renamed
 * file - ZXInfo's {@code /filecheck} only answers for TOSEC-named ones, and
 * ScreenScraper matches by name exactly as this class's own guesses do.
 *
 * <b>Confirmation is bounded at three candidates.</b> Each one is a paced
 * request against an archive that blocks on behaviour patterns rather than a
 * published limit - see {@link ZxartApi#MINIMUM_INTERVAL_MS} - so a search
 * for a common word ("head" alone is 271 matches) cannot be allowed to spend
 * one request per hit. The first three are checked, in the order the search
 * ranked them; the fourth is never asked about, however promising its name.
 *
 * <b>A name match is never {@code exact}, and a hash match always is.</b>
 * {@link Candidate#exact} is what lets a scrape fill itself in without
 * asking - see {@code ZxInfo}'s own comment on the same field - so a guess
 * dressed up as a certainty would be one game's cover on another for ever.
 * When nothing confirms, the name candidates stand exactly as ranked, marked
 * as the guesses they are; {@code Merge}'s fill-gaps rule is what bounds the
 * damage a wrong one can do.
 *
 * <b>{@code fetch} is minimal for now.</b> It fills {@link Meta} with what a
 * prod row plainly carries on its own - the title, the year, and the broad
 * kind of thing it is, read off {@code categoriesString}'s own topmost
 * segment - and answers no media at all. The artwork mapping ({@code
 * releaseStructure}'s inlays, screens and manuals) is Task 14's job and
 * {@code hardwareRequired} is Task 15's; both need a release fetched, which
 * this method does not do yet, precisely so the two remain additions to this
 * method rather than a second network shape bolted on beside it.
 *
 * No Android types - no {@code Uri}, no {@code Log} - for the same reason
 * {@link ZxartApi} has none: {@code unitTests.returnDefaultValues} answers
 * null for anything from {@code android.*} in a JVM test, so this whole class
 * runs, and is tested, on the JVM.
 */
public final class Zxart implements Provider {

    public static final String NAME = "zxart";

    /** How many name candidates a search offers - the same figure {@code
     *  ZxInfo} uses: enough to find the right one among re-releases and
     *  hacks, few enough to read in a dialog. */
    private static final int CANDIDATES = 10;

    /** How many of those candidates are ever confirmed by hash. See the class
     *  javadoc's own paragraph on why three and not "however many it takes". */
    private static final int CONFIRM_LIMIT = 3;

    /** What a release-list request asks for - the size {@code
     *  ZxartCatalogue.item}'s own lookup uses, comfortably past the richest
     *  prod measured (24 releases inside a limit of 50). */
    private static final int RELEASE_PAGE_SIZE = 50;

    private final ZxartApi api;
    private final String language;

    public Zxart(Http http, Locale locale) {
        this.api = new ZxartApi(http);
        this.language = ZxartApi.language(locale);
    }

    @Override
    public String name() {
        return NAME;
    }

    /** Always. zxart's API takes no credentials, so a clean clone can scrape
     *  with this provider from the start. */
    @Override
    public boolean configured() {
        return true;
    }

    /** Null, and permanently so - not "not yet asked", which is what {@link
     *  Provider#quota}'s own javadoc describes. zxart reports no allowance in
     *  any reply, and there is none to report: unlike {@code ZxInfo}, which
     *  answers {@link Quota#unknown()} for the same reason, this provider has
     *  nothing a caller could usefully treat as "ask again later" versus
     *  "there was never a number". */
    @Override
    public Quota quota() {
        return null;
    }

    /**
     * Five, whatever media are wanted - the ceiling, not the average.
     *
     * One search, up to three confirmations, and one prod fetch: the three a
     * single candidate actually costs when the very first name match
     * confirms is a search, one confirmation and one fetch. The ceiling
     * prices the worst case instead - three candidates checked before
     * settling, none of them free - because this number is what a screen
     * shows before somebody commits a whole collection to it, and
     * understating that is worse than a number nobody's game actually spends
     * in full. Media cost nothing extra: every one zxart offers is a static
     * file, exactly as {@code ZxInfo}'s are, which is why this does not grow
     * with {@code wanted} the way ScreenScraper's would.
     */
    @Override
    public int costPerGame(Wanted wanted) {
        return 5;
    }

    // --- finding a game ---------------------------------------------------------------

    /**
     * A name search, then up to three of its candidates confirmed by hash.
     *
     * <b>No hash, no confirmation, and that is the commonest case.</b>
     * {@link Game#md5} is a supplier because taking it means reading the
     * whole file through the documents provider - expensive under a tree
     * grant - so a caller that has not paid for it yet gets exactly the name
     * search and nothing more: asking for a release list per candidate would
     * spend a paced request comparing against a hash that does not exist.
     */
    @Override
    public List<Candidate> search(Game game) throws ScrapeException {
        String title = ZxInfo.titleOf(game.filename());
        if (title.isEmpty()) return Collections.emptyList();

        JSONObject reply = api.ask(new ZxartApi.Ask(ZxartApi.PROD)
                .language(language)
                .page(0, CANDIDATES)
                .filter(ZxartApi.FILTER_SEARCH, title));

        List<Candidate> found = new ArrayList<>();
        for (JSONObject row : ZxartApi.rows(reply, ZxartApi.PROD)) {
            found.add(candidateFrom(row));
        }

        String md5 = game.md5();
        if (md5 == null || md5.isEmpty()) return found;

        int confirm = Math.min(CONFIRM_LIMIT, found.size());
        for (int at = 0; at < confirm; at++) {
            if (confirmedByHash(found.get(at).handle, md5)) {
                found.set(at, exact(found.get(at)));
                break;
            }
        }

        return found;
    }

    /** A row from {@code export:zxProd}, as a guess - never {@link
     *  Candidate#exact}, whatever the ranking says. No publisher: a prod
     *  names {@code publishersIds} and nothing resolves them, the same
     *  measured fact {@code ZxartCatalogue.itemFrom} states at length. */
    private static Candidate candidateFrom(JSONObject row) {
        String id = Integer.toString(row.optInt("id", 0));
        int year = row.optInt("year", 0);

        return new Candidate(id,
                             ZxartApi.unescape(row.optString("title", "")),
                             year > 0 ? Integer.toString(year) : null,
                             null,
                             false);
    }

    /** {@code candidate}, marked certain - everything else about it carried
     *  over unchanged. */
    private static Candidate exact(Candidate candidate) {
        return new Candidate(candidate.handle, candidate.name, candidate.year,
                             candidate.publisher, true);
    }

    /**
     * Whether any release of {@code prodId} carries {@code md5} anywhere in
     * its {@code releaseStructure}.
     *
     * One request for every release of the prod - {@code
     * export:zxRelease/filter:zxProdId=} - since the hash could be on any of
     * them: a prod with a tape release and a disk release confirms from
     * whichever one somebody actually has.
     */
    private boolean confirmedByHash(String prodId, String md5) throws ScrapeException {
        JSONObject reply = api.ask(new ZxartApi.Ask(ZxartApi.RELEASE)
                .language(language)
                .page(0, RELEASE_PAGE_SIZE)
                .filter(ZxartApi.FILTER_PROD_ID, prodId));

        for (JSONObject release : ZxartApi.rows(reply, ZxartApi.RELEASE)) {
            if (walkForHash(release.optJSONArray("releaseStructure"), md5)) return true;
        }

        return false;
    }

    /**
     * {@code releaseStructure}, walked depth-first for a case-insensitive
     * match on {@code md5} - the zip is the root node and its {@code items}
     * hold the files packed inside it, which may themselves hold items.
     * Returns on the first hit, since which file matched does not matter,
     * only that one did.
     */
    private static boolean walkForHash(JSONArray items, String md5) {
        if (items == null) return false;

        for (int at = 0; at < items.length(); at++) {
            JSONObject item = items.optJSONObject(at);
            if (item == null) continue;

            if (md5.equalsIgnoreCase(item.optString("md5", ""))) return true;
            if (walkForHash(item.optJSONArray("items"), md5)) return true;
        }

        return false;
    }

    // --- fetching one --------------------------------------------------------------------

    /**
     * The prod alone, for now - see the class javadoc's "fetch is minimal"
     * paragraph for why the release list, the artwork and the hardware
     * words are left for Tasks 14 and 15 rather than half-built here.
     */
    @Override
    public Scraped fetch(Candidate candidate, Wanted wanted) throws ScrapeException {
        JSONObject reply = api.ask(new ZxartApi.Ask(ZxartApi.PROD)
                .language(language)
                .filter(ZxartApi.FILTER_PROD_ID, candidate.handle));

        List<JSONObject> rows = ZxartApi.rows(reply, ZxartApi.PROD);
        if (rows.isEmpty()) {
            throw new ScrapeException(ScrapeException.Kind.MALFORMED,
                                      "zxart has no prod " + candidate.handle);
        }

        return new Scraped(metaFrom(rows.get(0)), Collections.<Medium>emptyList());
    }

    /** Title, year and the broad kind of thing this is - what a prod row
     *  plainly carries without a second request. */
    private static Meta metaFrom(JSONObject prod) {
        int year = prod.optInt("year", 0);

        return Meta.at(null)
                .name(ZxartApi.unescape(prod.optString("title", null)))
                .released(year > 0 ? year + "0101T000000" : null)
                .genre(genreOf(prod))
                .build();
    }

    /**
     * {@link Meta#genre}'s own words are "the broad kind of thing this is",
     * and {@code categoriesString} is zxart's breadcrumb from root to leaf in
     * whichever language was asked for - {@code "Games/Action/Maze/Isometric
     * Maze Games"} for entry 100938, requested in English. The topmost
     * segment is the broad kind; the rest is the narrower classification
     * {@code ZxartCatalogue}'s own category tree already exists to resolve,
     * which this method does not need and does not walk.
     */
    private static String genreOf(JSONObject prod) {
        String path = ZxartApi.unescape(prod.optString("categoriesString", ""));
        if (path.isEmpty()) return null;

        int slash = path.indexOf('/');
        return slash < 0 ? path : path.substring(0, slash);
    }

    @Override
    public ScrapeException refusalFor(int status) {
        return api.refusalFor(status);
    }
}
