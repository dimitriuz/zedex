package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.library.meta.Meta;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * <b>Every request this class makes asks in English, and there is no
 * constructor parameter that could change that any more.</b> Task 13 kept a
 * {@code Locale} for {@link #search} on the unverified assumption that
 * zxart's {@code language:} segment might affect search matching or ranking
 * even where it does not change the fields the provider reads - it measured
 * only that {@code fetch}'s own fields were the same either way. That
 * assumption is now checked and false: {@code filter:zxProdSearch=dizzy}
 * asked with {@code language:eng} and with {@code language:rus} answered
 * {@code totalAmount 189} both times, the same ten ids in the same order,
 * the same ten titles - the replies differ in size only ({@code
 * review/zxart/dizzy-eng.json} 12,441 bytes against {@code
 * dizzy-rus.json} 13,988) because {@code categoriesString} and the tags come
 * back translated, and the search path reads neither. So the language
 * segment controls nothing this provider reads, on any path, and carrying a
 * {@code Locale} through the constructor for a value that changes nothing
 * would be pretending otherwise - the honest shape is a provider with no
 * locale at all. See {@link #LANGUAGE}.
 *
 * <b>{@code fetch} fills in the artwork, the maps, the advert and the text
 * manual - Task 14 - and leaves {@code hardwareRequired} alone for Task
 * 15.</b> See {@link #mediaFrom} for the mapping and {@link #collectRelease}
 * for the per-release half of it.
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

    /** The one language every request this class ever makes asks in - see
     *  the class javadoc's measurement. Fixed rather than read from a
     *  locale, because there is no longer a parameter that could reach it. */
    private static final String LANGUAGE = "eng";

    private final ZxartApi api;

    public Zxart(Http http) {
        this.api = new ZxartApi(http);
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
     * Six, whatever media are wanted - the ceiling, not the average.
     *
     * One search, up to three confirmations, one prod fetch and one release
     * list: {@code inlays}, {@code ads} and {@code instructions} - the
     * artwork, the advert and the text manual - live on a release, not on
     * the prod, so a {@code fetch} that resolves any of {@code covers},
     * {@code backcovers}, {@code physicalmedia}, {@code adverts} or {@code
     * manuals} has to ask for the release list too (see {@link
     * #needsReleases}). Six prices the worst case, not what a single game
     * actually spends: a candidate confirmed on the first try and a {@code
     * wanted} that only asks for {@code titlescreens}/{@code screenshots}/
     * {@code maps} - all three read straight off the prod - costs three
     * (search, one confirmation, one prod) and never asks for a release list
     * at all. The ceiling is what a screen shows before somebody commits a
     * whole collection to it, and understating that is worse than a number
     * nobody's game actually spends in full. Media cost nothing extra beyond
     * the one release-list request: every one zxart offers is a static file,
     * exactly as {@code ZxInfo}'s are, which is why this does not grow
     * further with {@code wanted} the way ScreenScraper's would.
     */
    @Override
    public int costPerGame(Wanted wanted) {
        return 6;
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
     *
     * <b>{@code game.md5()} is called at most once, and only when there is
     * at least one candidate to confirm.</b> A search with nothing to show
     * has nothing to hash against either - the empty-candidates check runs
     * before the hash is ever taken, so a game this provider has never heard
     * of costs no I/O beyond the one search request, whatever the file looks
     * like on disk. The supplier contract ("called at most once") is {@link
     * Game#md5}'s own; the reason it matters here is that calling it
     * speculatively, before knowing there is anything to compare it against,
     * would read a whole disk image through the documents provider for
     * nothing.
     */
    @Override
    public List<Candidate> search(Game game) throws ScrapeException {
        String title = ZxInfo.titleOf(game.filename());
        if (title.isEmpty()) return Collections.emptyList();

        JSONObject reply = api.ask(new ZxartApi.Ask(ZxartApi.PROD)
                .language(LANGUAGE)
                .page(0, CANDIDATES)
                .filter(ZxartApi.FILTER_SEARCH, title));

        List<Candidate> found = new ArrayList<>();
        for (JSONObject row : ZxartApi.rows(reply, ZxartApi.PROD)) {
            found.add(candidateFrom(row));
        }
        if (found.isEmpty()) return found;

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
        for (JSONObject release : releasesOf(prodId)) {
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

    /** Every release of {@code prodId}, one request, {@link #LANGUAGE}
     *  always - see the class javadoc's measurement of why there is no
     *  other language to ask in. Shared by {@link #confirmedByHash} and
     *  {@link #fetch}, which both need the same list for different reasons -
     *  one to hash-walk it, the other to read its inlays. */
    private List<JSONObject> releasesOf(String prodId) throws ScrapeException {
        JSONObject reply = api.ask(new ZxartApi.Ask(ZxartApi.RELEASE)
                .language(LANGUAGE)
                .page(0, RELEASE_PAGE_SIZE)
                .filter(ZxartApi.FILTER_PROD_ID, prodId));

        return ZxartApi.rows(reply, ZxartApi.RELEASE);
    }

    // --- fetching one --------------------------------------------------------------------

    /**
     * The prod, plus a release list when {@code wanted} needs one.
     *
     * <b>The release list is not cached from {@link #search}'s
     * confirmation, on purpose.</b> It looks free - {@code search} may
     * already have fetched exactly this list to confirm a candidate - and it
     * is not: {@code search()} and {@code fetch()} are not guaranteed
     * adjacent. {@code ScrapeOneGame} can put a per-source chooser dialog
     * between them, and {@code Blend}/{@code Sweep} interleave games and
     * sources, so a single-entry cache keyed on "the last confirmed
     * candidate" would sometimes answer with another entry's releases and
     * sometimes fall back to a second request anyway - a stale-data risk
     * bought with no reliable saving. A second request when both happen to
     * want it is the honest cost.
     */
    @Override
    public Scraped fetch(Candidate candidate, Wanted wanted) throws ScrapeException {
        JSONObject reply = api.ask(new ZxartApi.Ask(ZxartApi.PROD)
                .language(LANGUAGE)
                .filter(ZxartApi.FILTER_PROD_ID, candidate.handle));

        List<JSONObject> rows = ZxartApi.rows(reply, ZxartApi.PROD);
        if (rows.isEmpty()) {
            throw new ScrapeException(ScrapeException.Kind.MALFORMED,
                                      "zxart has no prod " + candidate.handle);
        }
        JSONObject prod = rows.get(0);

        List<JSONObject> releases = needsReleases(wanted)
                ? releasesOf(candidate.handle) : Collections.<JSONObject>emptyList();

        return new Scraped(metaFrom(prod), mediaFrom(prod, releases, wanted));
    }

    /** Whether {@code wanted} names any folder that only a release can
     *  supply - {@link #collectRelease}'s own set. Answering false spares
     *  the request entirely, which is what keeps a metadata-only fetch (or
     *  one after only {@code titlescreens}/{@code screenshots}/{@code maps})
     *  down at a single request. */
    private static boolean needsReleases(Wanted wanted) {
        return wanted.wants("covers") || wanted.wants("backcovers")
                || wanted.wants("physicalmedia") || wanted.wants("adverts")
                || wanted.wants("manuals");
    }

    /**
     * Title, year and the broad kind of thing this is - what a prod row
     * plainly carries without a second request.
     */
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
     * and {@code categoriesString} is zxart's breadcrumb from root to leaf -
     * {@code "Games/Action/Maze/Isometric Maze Games"} for entry 100938,
     * always in {@link #LANGUAGE} since this is read from a {@link #fetch}
     * reply. The topmost segment is the broad kind; the rest is the narrower
     * classification {@code ZxartCatalogue}'s own category tree already
     * exists to resolve, which this method does not need and does not walk.
     */
    private static String genreOf(JSONObject prod) {
        String path = ZxartApi.unescape(prod.optString("categoriesString", ""));
        if (path.isEmpty()) return null;

        int slash = path.indexOf('/');
        return slash < 0 ? path : path.substring(0, slash);
    }

    // --- media --------------------------------------------------------------------------

    /**
     * Every wanted medium, mapped <b>by rule and never by position</b>.
     *
     * <b>Two endpoints, two halves of the picture.</b> {@code imagesUrls}
     * and {@code maps} are the prod's own - one entry per prod, not per
     * release - so {@link #collectImages} and the {@code maps} line below
     * run once, straight off {@code prod}. {@code inlays}, {@code ads} and
     * {@code instructions} live on a release instead, which is why {@code
     * releases} is a list at all: see {@link #collectRelease}.
     *
     * <b>A folder keeps the first thing it is given, across every release in
     * the order zxart itself returned them.</b> The same rule {@code
     * ZxInfo.collect} uses for its two overlapping arrays, extended here to
     * a whole list of releases rather than one array - and it answers
     * controller note 4's question directly. A prod with two boxed editions
     * (measured on entry 92668, Licence to Kill: an original release, id
     * 92671, and a 1991 Hit Squad rerelease, id 92675, each with its own
     * front/back/media inlays) ends up with <em>all three</em> of {@code
     * covers}/{@code backcovers}/{@code physicalmedia} from whichever
     * release zxart lists first that has any of them - id 92671 here, since
     * {@code releasesIds} lists the original before the rerelease - and the
     * second edition's genuinely different pictures are never looked at,
     * because the folders they would fill are already taken. This was
     * chosen over two alternatives: filtering to {@code releaseType:
     * "original"} would leave a prod with no original release (a rerelease
     * is sometimes all zxart has) with no artwork at all though a release
     * carries some; and a true union - taking each release's <em>best</em>
     * inlay per folder rather than the first release with any - would let a
     * cover from one edition sit beside a back from a visually different
     * one, which is worse than a coherent set from a single edition. Taking
     * the first release with anything, whole, is the one option that always
     * has an answer when any release does and never mixes editions within a
     * folder set. It costs nothing extra either way: every release's {@code
     * inlays}/{@code ads}/{@code instructions} already arrived in the one
     * release-list reply {@link #fetch} asked for.
     */
    private static List<Medium> mediaFrom(JSONObject prod, List<JSONObject> releases,
                                          Wanted wanted) {
        Map<String, Medium> byFolder = new LinkedHashMap<>();

        collectImages(byFolder, prod.optJSONArray("imagesUrls"), wanted);
        collectFirst(byFolder, prod.optJSONArray("maps"), "maps", wanted);

        for (JSONObject release : releases) {
            collectRelease(byFolder, release, wanted);
        }

        return new ArrayList<>(byFolder.values());
    }

    /**
     * The rendered loading screen and the screenshots, told apart by path.
     *
     * Measured: {@code imagesUrls} holds one {@code zximages/id=...} entry -
     * zxart's own renderer, always first in every prod checked - followed by
     * {@code /screenshot/id:.../name.gif} entries. Taking the first by
     * position would be right until an entry arrives without a rendered
     * screen, and then it would file a screenshot as the title screen for
     * ever; matching the path instead costs nothing and never makes that
     * mistake. Only the first screenshot is kept - {@code screenshots} is a
     * one-file folder exactly as {@code titlescreens} is, see {@code
     * Artwork.PICTURE_FOLDERS} - so a prod with five screenshots offers one,
     * the same "folder keeps the first thing it is given" rule as
     * everywhere else in this method.
     *
     * <b>{@code "png"}, stated for the rendered screen, not parsed.</b>
     * {@code zximages/id=92669;pal=srgb;type=standard;zoom=1} carries no
     * extension at all - the same fact {@code ZxartCatalogue.pictureFrom}
     * documents and asserts for the same url shape, measured there by hand
     * against the service's actual bytes.
     */
    private static void collectImages(Map<String, Medium> into, JSONArray images,
                                      Wanted wanted) {
        if (images == null) return;

        for (int at = 0; at < images.length(); at++) {
            String url = images.optString(at, "");
            if (url.isEmpty()) continue;

            String folder;
            String extension;
            if (url.contains("zximages")) {
                folder = "titlescreens";
                extension = "png";
            } else if (url.contains("/screenshot/")) {
                folder = "screenshots";
                extension = extensionOf(url);
            } else {
                continue;
            }

            if (!wanted.wants(folder) || into.containsKey(folder)) continue;
            into.put(folder, new Medium(folder, url, extension, null));
        }
    }

    /**
     * One release's {@code inlays}, {@code ads} and {@code instructions} -
     * package-private so {@code ZxartTest} can drive the inlay-suffix rule
     * directly against one captured release row, without paying for the
     * whole prod-plus-release-list flow {@link #fetch} needs and this rule
     * does not.
     */
    static void collectRelease(Map<String, Medium> into, JSONObject release, Wanted wanted) {
        collectInlays(into, release.optJSONArray("inlays"), wanted);
        collectFirst(into, release.optJSONArray("ads"), "adverts", wanted);
        collectInstructions(into, release.optJSONArray("instructions"), wanted);
    }

    /**
     * {@code inlays}, mapped by the suffix on each file's own name - never
     * by where it sits in the array.
     *
     * Measured over 400 releases in Task 1: no suffix at all (166) and
     * {@code _Front} (41) are the cover, {@code _Back} (101) the back,
     * {@code _Media} (99) the photograph of the tape or disk, and about a
     * fifth carry a side or edition marker ({@code _2}, {@code _3}, {@code
     * _SideA}, {@code _SideB}, {@code _2Back}, {@code _FrontCase}, {@code
     * _GoldenCase}, {@code _WhiteCase}) that maps to no folder this app has
     * and is skipped rather than guessed at - see {@link #inlayFolderOf}.
     * Inventing a fourth folder for those is not this feature's business,
     * and filing one of them as a cover because it happened to be first in
     * the array is exactly the bug a rule keyed on the suffix cannot make.
     */
    private static void collectInlays(Map<String, Medium> into, JSONArray inlays,
                                      Wanted wanted) {
        if (inlays == null) return;

        for (int at = 0; at < inlays.length(); at++) {
            String url = inlays.optString(at, "");
            if (url.isEmpty()) continue;

            String folder = inlayFolderOf(filenameOf(url));
            if (folder == null || !wanted.wants(folder) || into.containsKey(folder)) continue;

            into.put(folder, new Medium(folder, url, extensionOf(url), null));
        }
    }

    /**
     * The folder one inlay filename belongs in, from its suffix alone - or
     * null when the suffix names a side or edition this app has no folder
     * for. {@code stem} is the filename without its extension; the suffix is
     * whatever follows the <em>last</em> underscore, matched exactly rather
     * than by {@code startsWith}/{@code contains} - {@code _FrontCase} must
     * not match {@code _Front}, and {@code _2Back} must not match {@code
     * _Back}, both of which a looser match would get wrong.
     */
    private static String inlayFolderOf(String filename) {
        String stem = withoutExtension(filename);
        int underscore = stem.lastIndexOf('_');
        if (underscore < 0) return "covers";

        switch (stem.substring(underscore + 1)) {
            case "Front": return "covers";
            case "Back": return "backcovers";
            case "Media": return "physicalmedia";
            default: return null;
        }
    }

    /** The first url in {@code urls} - {@code maps} and {@code ads} are
     *  both one-file folders here exactly as {@code covers} is, so the same
     *  "folder keeps the first thing it is given" rule applies with nothing
     *  to classify by suffix. */
    private static void collectFirst(Map<String, Medium> into, JSONArray urls, String folder,
                                     Wanted wanted) {
        if (urls == null || urls.length() == 0) return;
        if (!wanted.wants(folder) || into.containsKey(folder)) return;

        String url = urls.optString(0, "");
        if (url.isEmpty()) return;

        into.put(folder, new Medium(folder, url, extensionOf(url), null));
    }

    /**
     * {@code instructions}, a PDF preferred over a text transcription - the
     * same preference {@code Artwork.MANUAL_EXTENSIONS} states for exactly
     * the same reason: where a release offers both, the PDF is usually the
     * better document. Chosen by scanning the whole array for an extension
     * rather than trusting array order - a release can carry several
     * (Head over Heels' release 100941 lists four: a PDF, two text files and
     * a second PDF) and nothing in zxart's own reply documents which comes
     * first.
     */
    private static void collectInstructions(Map<String, Medium> into, JSONArray instructions,
                                            Wanted wanted) {
        if (instructions == null) return;
        if (!wanted.wants("manuals") || into.containsKey("manuals")) return;

        String chosen = firstWithExtension(instructions, "pdf");
        if (chosen == null) chosen = firstWithExtension(instructions, "txt");
        if (chosen == null) return;

        into.put("manuals", new Medium("manuals", chosen, extensionOf(chosen), null));
    }

    private static String firstWithExtension(JSONArray urls, String extension) {
        for (int at = 0; at < urls.length(); at++) {
            String url = urls.optString(at, "");
            if (extension.equals(extensionOf(url))) return url;
        }
        return null;
    }

    /** The {@code filename:} segment of a zxart media url - every one of
     *  them ends {@code mode:download/filename:Name.ext} - or the last path
     *  segment when there is no such marker, which is belt and braces rather
     *  than a shape actually seen. */
    private static String filenameOf(String url) {
        int marker = url.lastIndexOf("filename:");
        if (marker >= 0) return url.substring(marker + "filename:".length());

        int slash = url.lastIndexOf('/');
        return slash < 0 ? url : url.substring(slash + 1);
    }

    private static String withoutExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }

    /**
     * The extension of a url's own filename, lower or upper case as given -
     * {@link Medium}'s constructor does not normalise it, so callers of
     * {@code Downloads} see whatever this returns. A copy of {@code
     * ZxartCatalogue}'s own method rather than a shared one - the two must
     * not disagree, and each is tested against this class's own fixtures.
     */
    private static String extensionOf(String url) {
        if (url == null) return "";

        String noQuery = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        int dot = noQuery.lastIndexOf('.');
        int slash = noQuery.lastIndexOf('/');

        return dot > slash ? noQuery.substring(dot + 1) : "";
    }

    @Override
    public ScrapeException refusalFor(int status) {
        return api.refusalFor(status);
    }
}
