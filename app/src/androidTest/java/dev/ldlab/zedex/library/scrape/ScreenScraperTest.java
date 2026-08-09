package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.library.meta.Meta;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The ScreenScraper client, without a socket in sight.
 *
 * Every reply here is written by hand, handed over through {@link Http}, and
 * that is deliberate: a scraper tested by scraping only ever exercises the
 * path where everything works, and the paths where it does not are most of
 * what a scraper is. A spent quota, a thread limit, refused credentials and a
 * reply that will not parse are all things that happen on a real collection
 * and none of them can be produced on demand against a live service.
 *
 * Instrumentation rather than JVM because the client uses {@code Uri.encode}
 * to build its query and {@code TextUtils.join} to put a compound genre back
 * together, both of which the stub android.jar answers with null - the tests
 * would pass against nonsense. On a device they are the real thing.
 *
 * The one thing not covered here is whether ScreenScraper actually answers in
 * this shape. That needs credentials and their server, and is a run on the
 * bench rather than a test - see {@code review/scrape-2-screenscraper.md}.
 */
@RunWith(AndroidJUnit4.class)
public class ScreenScraperTest {

    // --- a stand-in for the network ------------------------------------------------

    /** Answers whatever it was told to, and remembers what it was asked. */
    private static final class Canned implements Http {
        private final List<Reply> replies = new ArrayList<>();
        final List<String> asked = new ArrayList<>();
        IOException throwInstead;

        Canned then(int status, String body) {
            replies.add(new Reply(status, body));
            return this;
        }

        @Override
        public Reply get(String url) throws IOException {
            asked.add(url);
            if (throwInstead != null) throw throwInstead;
            if (replies.isEmpty()) return new Reply(200, EMPTY);
            return replies.remove(0);
        }

        @Override
        public String save(String url, File into) {
            throw new UnsupportedOperationException("not this test's business");
        }
    }

    private static final class AGame implements Provider.Game {
        private final String md5;

        AGame(String md5) {
            this.md5 = md5;
        }

        @Override public String path() { return "./games/Batman.tzx"; }
        @Override public String filename() { return "Batman 48K (1986)(Ocean).tzx"; }
        @Override public long size() { return 49152; }
        @Override public String md5() { return md5; }
    }

    private static ScreenScraper scraperOn(Canned http) {
        return new ScreenScraper(http, "devid", "devpassword", "", "");
    }

    // --- what ScreenScraper answers with -------------------------------------------

    private static final String USER =
            "\"ssuser\":{\"id\":\"someone\",\"maxthreads\":\"3\","
            + "\"requeststoday\":\"120\",\"maxrequestsperday\":\"20000\"}";

    private static final String GAME =
            "\"jeu\":{"
            + "\"id\":\"4242\","
            + "\"noms\":[{\"region\":\"fr\",\"text\":\"Batman le film\"},"
            + "          {\"region\":\"wor\",\"text\":\"Batman\"}],"
            + "\"editeur\":{\"id\":\"1\",\"text\":\"Ocean\"},"
            + "\"developpeur\":{\"id\":\"2\",\"text\":\"Probe Software\"},"
            + "\"joueurs\":{\"text\":\"1-2\"},"
            + "\"note\":{\"text\":\"18\"},"
            + "\"synopsis\":[{\"langue\":\"fr\",\"text\":\"Une description\"},"
            + "              {\"langue\":\"en\",\"text\":\"A description\"}],"
            + "\"genres\":[{\"id\":\"7\",\"noms\":[{\"langue\":\"en\",\"text\":\"Platform\"}]},"
            + "            {\"id\":\"8\",\"noms\":[{\"langue\":\"en\",\"text\":\"Action\"}]}],"
            + "\"dates\":[{\"region\":\"wor\",\"text\":\"1989-06-01\"}],"
            + "\"sp2kcfg\":\"# Batman\\n0:left = q ;; left\\n0:a = v ;; jump\","
            + "\"medias\":["
            + " {\"type\":\"box-3D\",\"url\":\"https://x/3d.png\",\"format\":\"png\"},"
            + " {\"type\":\"box-2D\",\"url\":\"https://x/cover.png\",\"format\":\"png\","
            + "  \"md5\":\"aaaa\"},"
            + " {\"type\":\"ss\",\"url\":\"https://x/shot.png\",\"format\":\"png\"},"
            + " {\"type\":\"video\",\"url\":\"https://x/clip.mp4\",\"format\":\"mp4\"},"
            + " {\"type\":\"manuel\",\"url\":\"https://x/man.pdf\",\"format\":\"pdf\"}"
            + "]}";

    private static final String FOUND = "{\"response\":{" + USER + "," + GAME + "}}";
    private static final String EMPTY = "{\"response\":{" + USER + "}}";

    // --- searching ------------------------------------------------------------------

    /**
     * A hash the database knows answers with one game, and the client stops
     * there.
     *
     * The whole reason for hashing at all: one certain candidate is what lets
     * a scrape fill itself in without asking. A second request after a hit
     * would also spend one of the day's allowance to learn nothing.
     */
    @Test
    public void ahashHitIsOneCertainCandidateAndOneRequest() throws Exception {
        Canned http = new Canned().then(200, FOUND);

        List<Candidate> found = scraperOn(http).search(new AGame("d41d8cd98f00b204e9800998ecf8427e"));

        assertEquals(1, found.size());
        assertTrue("a hash hit should be certain", found.get(0).exact);
        assertEquals("Batman", found.get(0).name);
        assertEquals("one request, not two", 1, http.asked.size());
        assertTrue("the md5 was not asked with: " + http.asked.get(0),
                   http.asked.get(0).contains("md5=d41d8cd98f00b204e9800998ecf8427e"));
    }

    /** With no hash to offer, it searches the filename - and says the answer
     *  is not certain, which is what makes the UI ask. */
    @Test
    public void withNoHashItSearchesTheFilenameAndIsNotCertain() throws Exception {
        Canned http = new Canned().then(200, FOUND);

        List<Candidate> found = scraperOn(http).search(new AGame(null));

        assertEquals(1, found.size());
        assertFalse("a name search is a guess, not a certainty", found.get(0).exact);
        assertTrue("the filename was not asked with: " + http.asked.get(0),
                   http.asked.get(0).contains("romnom="));
    }

    /** A hash the database has never seen falls through to the filename -
     *  which is the ordinary case for a Spectrum tape from anywhere but a
     *  preservation set. */
    @Test
    public void ahashItDoesNotKnowFallsBackToTheFilename() throws Exception {
        Canned http = new Canned().then(200, EMPTY).then(200, FOUND);

        List<Candidate> found = scraperOn(http).search(new AGame("nothingknowsthis"));

        assertEquals(1, found.size());
        assertFalse(found.get(0).exact);
        assertEquals("both questions should have been asked", 2, http.asked.size());
        assertTrue(http.asked.get(0).contains("md5="));
        assertTrue(http.asked.get(1).contains("romnom="));
    }

    /** Knowing nothing is an answer, not a failure - most of a collection. */
    @Test
    public void knowingNothingIsAnEmptyListRatherThanAnError() throws Exception {
        Canned http = new Canned().then(200, EMPTY).then(200, EMPTY);

        assertTrue(scraperOn(http).search(new AGame("unknown")).isEmpty());
    }

    /** The system is asked for, or a Spectrum tape matches a game of the same
     *  name on some other machine entirely. */
    @Test
    public void thequeryNamesTheSpectrum() throws Exception {
        Canned http = new Canned().then(200, FOUND);
        scraperOn(http).search(new AGame(null));

        assertTrue("systemeid=76 is missing: " + http.asked.get(0),
                   http.asked.get(0).contains("systemeid=76"));
    }

    /** The filename is escaped - it has spaces and brackets in it, and every
     *  real one does. */
    @Test
    public void thefilenameIsEscapedIntoTheQuery() throws Exception {
        Canned http = new Canned().then(200, FOUND);
        scraperOn(http).search(new AGame(null));

        String asked = http.asked.get(0);
        assertFalse("a raw space went into the URL: " + asked, asked.contains(" "));
        assertTrue("the name was not encoded: " + asked, asked.contains("Batman%20"));
    }

    // --- the quota that rides along ---------------------------------------------------

    /** Every reply carries it, and a multi-scrape is built on it. */
    @Test
    public void thequotaIsReadFromEveryReply() throws Exception {
        Canned http = new Canned().then(200, FOUND);
        ScreenScraper scraper = scraperOn(http);

        assertEquals("nothing asked yet", -1, scraper.quota().left());

        scraper.search(new AGame("d41d8cd98f00b204e9800998ecf8427e"));

        assertEquals(120, scraper.quota().used);
        assertEquals(20000, scraper.quota().allowed);
        assertEquals(3, scraper.quota().threads);
        assertEquals(19880, scraper.quota().left());
        assertFalse(scraper.quota().spent());
    }

    /** An account with nothing left says so, and that is what stops a
     *  multi-scrape before it starts rather than after four hundred games. */
    @Test
    public void aspentQuotaIsVisibleBeforeTheNextRequest() throws Exception {
        String spent = "{\"response\":{\"ssuser\":{\"maxthreads\":\"1\","
                     + "\"requeststoday\":\"20000\",\"maxrequestsperday\":\"20000\"}}}";
        Canned http = new Canned().then(200, spent).then(200, spent);

        ScreenScraper scraper = scraperOn(http);
        scraper.search(new AGame("x"));

        assertEquals(0, scraper.quota().left());
        assertTrue(scraper.quota().spent());
    }

    /** Unstated threads means one - the safe assumption, and what an account
     *  without a subscription actually gets. */
    @Test
    public void unstatedThreadsIsOne() {
        assertEquals(1, Quota.unknown().threads);
        assertEquals(1, new Quota(0, 100, 0).threads);
        assertEquals(1, new Quota(0, 100, -5).threads);
    }

    // --- the refusals, told apart ------------------------------------------------------

    private static void expect(ScrapeException.Kind kind, int status, String body) {
        Canned http = new Canned().then(status, body);

        try {
            scraperOn(http).search(new AGame("x"));
            fail("HTTP " + status + " should have been refused as " + kind);
        } catch (ScrapeException e) {
            assertEquals("HTTP " + status + " was read as " + e.kind, kind, e.kind);
        }
    }

    /**
     * Each code means something different to a multi-scrape, which is the
     * whole reason they are told apart rather than collapsed into "it failed":
     * two of them are reasons to wait and the rest are reasons to stop.
     */
    @Test
    public void everyRefusalIsToldApart() {
        expect(ScrapeException.Kind.QUOTA_EXCEEDED, 429, "quota exceeded");
        expect(ScrapeException.Kind.THREAD_LIMIT, 430, "too many threads");
        expect(ScrapeException.Kind.THREAD_LIMIT, 431, "too many threads");
        expect(ScrapeException.Kind.BAD_CREDENTIALS, 401, "bad login");
        expect(ScrapeException.Kind.BAD_CREDENTIALS, 403, "forbidden");
        expect(ScrapeException.Kind.BAD_CREDENTIALS, 426, "erreur de login");
        expect(ScrapeException.Kind.CLOSED, 423, "closed to non members");
        expect(ScrapeException.Kind.NETWORK, 500, "server exploded");
    }

    /** And the two that mean "wait" say so, because that is the one question a
     *  multi-scrape asks of every failure. */
    @Test
    public void onlyTheRecoverableOnesAreWorthWaitingFor() {
        assertTrue(new ScrapeException(ScrapeException.Kind.THREAD_LIMIT, "").worthWaiting());
        assertTrue(new ScrapeException(ScrapeException.Kind.NETWORK, "").worthWaiting());

        assertFalse(new ScrapeException(ScrapeException.Kind.QUOTA_EXCEEDED, "").worthWaiting());
        assertFalse(new ScrapeException(ScrapeException.Kind.BAD_CREDENTIALS, "").worthWaiting());
        assertFalse(new ScrapeException(ScrapeException.Kind.CLOSED, "").worthWaiting());
    }

    /** A 200 whose body is not JSON at all - which is how ScreenScraper
     *  reports some refusals, in French. */
    @Test
    public void abodyThatWillNotParseIsMalformedRatherThanACrash() {
        expect(ScrapeException.Kind.MALFORMED, 200, "<html>maintenance</html>");
    }

    /** Except when its text says what the trouble is, which is worth reading
     *  rather than throwing away. */
    @Test
    public void aplainTextRefusalIsReadForItsReason() {
        expect(ScrapeException.Kind.QUOTA_EXCEEDED, 200, "Quota de scrape depasse");
        expect(ScrapeException.Kind.CLOSED, 200, "API closed for non members");
    }

    /** No socket at all. */
    @Test
    public void nonetworkIsItsOwnKindOfFailure() {
        Canned http = new Canned();
        http.throwInstead = new IOException("no route to host");

        try {
            scraperOn(http).search(new AGame("x"));
            fail("should have failed");
        } catch (ScrapeException e) {
            assertEquals(ScrapeException.Kind.NETWORK, e.kind);
        }
    }

    /** A build with no developer account asks nothing at all, rather than
     *  making a request that can only be refused. */
    @Test
    public void abuildWithNoAccountAsksNothing() {
        Canned http = new Canned().then(200, FOUND);
        ScreenScraper scraper = new ScreenScraper(http, "", "", "", "");

        assertFalse(scraper.configured());

        try {
            scraper.search(new AGame("x"));
            fail("should have refused before asking");
        } catch (ScrapeException e) {
            assertEquals(ScrapeException.Kind.NOT_CONFIGURED, e.kind);
        }
        assertTrue("it asked anyway", http.asked.isEmpty());
    }

    // --- what a fetch produces ----------------------------------------------------------

    private static Provider.Scraped fetchAll() throws Exception {
        Canned http = new Canned().then(200, FOUND);
        ScreenScraper scraper = scraperOn(http);

        Candidate one = scraper.search(new AGame("d41d8cd98f00b204e9800998ecf8427e")).get(0);
        return scraper.fetch(one, Provider.Wanted.of("covers", "backcovers", "physicalmedia", "miximages",
                                   "screenshots", "titlescreens", "videos", "manuals"));
    }

    /** The facts, in the shape the store keeps them. */
    @Test
    public void thefactsComeBackAsMetaHoldsThem() throws Exception {
        Meta meta = fetchAll().meta;

        assertEquals("Batman", meta.name);
        assertEquals("Probe Software", meta.developer);
        assertEquals("Ocean", meta.publisher);
        assertEquals("1-2", meta.players);
        assertEquals("A description", meta.desc);
    }

    /** World before the regional titles - a Spectrum game with a French title
     *  as well should still be called what it is called here. */
    @Test
    public void thepreferredRegionWinsTheName() throws Exception {
        assertEquals("Batman", fetchAll().meta.name);
    }

    /** English before the rest, for the same reason. */
    @Test
    public void thepreferredLanguageWinsTheDescription() throws Exception {
        assertEquals("A description", fetchAll().meta.desc);
    }

    /**
     * Their rating is out of twenty and {@link Meta#rating} is a fraction.
     *
     * Converted here rather than stored as theirs: the pane's stars and the
     * filter's threshold are both built on the fraction, and a second scale in
     * the store is a second thing to get wrong. 18 of 20 is 0.9, which the
     * pane then shows as 4.5.
     */
    @Test
    public void theratingIsConvertedToTheFractionTheStoreKeeps() throws Exception {
        assertEquals("0.9000", fetchAll().meta.rating);
        assertEquals("4.5", fetchAll().meta.stars());
    }

    /** Their date becomes ES-DE's own stamp, which is what Meta.year reads and
     *  what the hand editor writes. */
    @Test
    public void thedateBecomesTheStampTheStoreKeeps() throws Exception {
        assertEquals("19890101T000000", fetchAll().meta.released);
        assertEquals("1989", fetchAll().meta.year());
    }

    /** Several genres come back joined the way ES-DE writes them, which
     *  Filters.genresOf already knows how to split again. */
    @Test
    public void severalGenresAreJoinedTheWayTheStoreExpects() throws Exception {
        assertEquals("Platform, Action", fetchAll().meta.genre);
    }

    /** The path is the caller's to know - the API has never heard of it - and
     *  so is who owns the row. */
    @Test
    public void thepathAndTheOwnerAreLeftToTheCaller() throws Exception {
        assertNull(fetchAll().meta.path);
        assertNull(fetchAll().meta.source);
    }

    // --- the media list -------------------------------------------------------------------

    private static Medium folder(List<Medium> media, String folder) {
        for (Medium one : media) if (folder.equals(one.folder)) return one;
        return null;
    }

    /** Their vocabulary becomes this app's folders, which are ES-DE's - so
     *  what is written is what Artwork then finds. */
    @Test
    public void theirTypesBecomeOurFolders() throws Exception {
        List<Medium> media = fetchAll().media;

        assertNotNull("no cover", folder(media, "covers"));
        assertNotNull("no screenshot", folder(media, "screenshots"));
        assertNotNull("no video", folder(media, "videos"));
        assertNotNull("no manual", folder(media, "manuals"));
    }

    /**
     * One per folder, and the preferred type wins.
     *
     * The reply offers both a 3D box and a 2D one; the 2D is the cover a shelf
     * shows and is what every row and tile in this app draws.
     */
    @Test
    public void thepreferredTypeWinsAndOnlyOnePerFolder() throws Exception {
        List<Medium> media = fetchAll().media;

        int covers = 0;
        for (Medium one : media) if ("covers".equals(one.folder)) covers++;

        assertEquals("more than one cover was taken", 1, covers);
        assertEquals("https://x/cover.png", folder(media, "covers").url);
    }

    /** The hash rides along, so a truncated download can be told from a whole
     *  one - see Downloads. */
    @Test
    public void themediaHashIsKeptWhenTheProviderGivesOne() throws Exception {
        assertEquals("aaaa", folder(fetchAll().media, "covers").md5);
        assertNull("no hash offered for the screenshot",
                   folder(fetchAll().media, "screenshots").md5);
    }

    /** Asking for nothing fetches nothing - metadata only is a real thing to
     *  want, and it is the fast path for a multi-scrape. */
    @Test
    public void askingForNoMediaGetsNone() throws Exception {
        Canned http = new Canned().then(200, FOUND);
        ScreenScraper scraper = scraperOn(http);
        Candidate one = scraper.search(new AGame("x")).get(0);

        assertTrue(scraper.fetch(one, Provider.Wanted.nothing()).media.isEmpty());
    }

    /** And asking for only some gets only those. */
    @Test
    public void eachKindOfMediaCanBeAskedForOnItsOwn() throws Exception {
        Canned http = new Canned().then(200, FOUND);
        ScreenScraper scraper = scraperOn(http);
        Candidate one = scraper.search(new AGame("x")).get(0);

        List<Medium> justTheVideo =
                scraper.fetch(one, Provider.Wanted.of("videos")).media;

        assertEquals(1, justTheVideo.size());
        assertEquals("videos", justTheVideo.get(0).folder);
    }

    /** A candidate from somewhere else is refused rather than quietly
     *  fetching the wrong game. */
    @Test
    public void acandidateThisProviderDidNotMakeIsRefused() throws Exception {
        Canned http = new Canned().then(200, FOUND);
        ScreenScraper scraper = scraperOn(http);
        scraper.search(new AGame("x"));

        try {
            scraper.fetch(new Candidate("999", "Someone else's", null, null, true),
                          Provider.Wanted.nothing());
            fail("should have refused a foreign candidate");
        } catch (ScrapeException e) {
            assertEquals(ScrapeException.Kind.MALFORMED, e.kind);
        }
    }

    /** A game with no media at all is an empty list, not a crash - plenty of
     *  Spectrum games have nothing scraped. */
    @Test
    public void agameWithNoMediaIsAnEmptyList() throws Exception {
        String bare = "{\"response\":{" + USER
                    + ",\"jeu\":{\"id\":\"1\",\"noms\":[{\"region\":\"wor\",\"text\":\"Bare\"}]}}}";
        Canned http = new Canned().then(200, bare);
        ScreenScraper scraper = scraperOn(http);

        Candidate one = scraper.search(new AGame("x")).get(0);
        Provider.Scraped got = scraper.fetch(one, Provider.Wanted.of("covers", "backcovers", "physicalmedia", "miximages",
                                   "screenshots", "titlescreens", "videos", "manuals"));

        assertTrue(got.media.isEmpty());
        assertEquals("Bare", got.meta.name);
        assertNull(got.meta.developer);
        assertNull(got.meta.rating);
    }

    /** What it is called, for a screen that offers a choice between
     *  providers. */
    @Test
    public void ithasAName() {
        assertEquals("ScreenScraper", scraperOn(new Canned()).name());
    }

    /** And a candidate describes itself the way a chooser would show it. */
    @Test
    public void acandidateDescribesItselfForAChooser() {
        assertEquals("Batman (1989) · Ocean",
                     new Candidate("1", "Batman", "1989", "Ocean", false).describe());
        assertEquals("Batman", new Candidate("1", "Batman", null, null, false).describe());
        assertEquals("Batman (1989)",
                     new Candidate("1", "Batman", "1989", "", false).describe());
    }

    // --- the control layout ------------------------------------------------------------

    /**
     * {@code sp2kcfg} comes back verbatim.
     *
     * The one field here that changes how a game plays rather than how it
     * looks: hand-authored config naming which Spectrum key each pad control
     * should send, for this game in particular. Nothing reads it yet - mapping
     * it onto ControlProfiles is its own piece of work - so it is stored as it
     * arrived rather than parsed, which would settle that mapping's shape in
     * advance.
     */
    @Test
    public void thecontrolLayoutIsCarriedThroughVerbatim() throws Exception {
        String controls = fetchAll().meta.controls;

        assertNotNull("sp2kcfg was dropped", controls);
        assertTrue("the layout was reformatted on the way: " + controls,
                   controls.contains("0:left = q") && controls.contains("0:a = v"));
    }

    /** A game without one says nothing rather than an empty string, so
     *  "has a layout" is a null check like every other field here. */
    @Test
    public void agameWithNoLayoutHasNone() throws Exception {
        String bare = "{\"response\":{" + USER
                    + ",\"jeu\":{\"id\":\"1\",\"noms\":[{\"region\":\"wor\",\"text\":\"Bare\"}]}}}";
        Canned http = new Canned().then(200, bare);
        ScreenScraper scraper = scraperOn(http);

        Candidate one = scraper.search(new AGame("x")).get(0);
        assertNull(scraper.fetch(one, Provider.Wanted.nothing()).meta.controls);
    }

    /** And it costs no request of its own - it rides in the reply the search
     *  already paid for. */
    @Test
    public void thelayoutCostsNoExtraRequest() throws Exception {
        Canned http = new Canned().then(200, FOUND);
        ScreenScraper scraper = scraperOn(http);

        Candidate one = scraper.search(new AGame("d41d8cd98f00b204e9800998ecf8427e")).get(0);
        assertNotNull(scraper.fetch(one, Provider.Wanted.nothing()).meta.controls);

        assertEquals("the layout should not have cost a second request",
                     1, http.asked.size());
    }

    /**
     * A game they have never heard of answers with nothing, and does not
     * throw.
     *
     * ScreenScraper says 404 for that, and treating it as a failure is a real
     * bug rather than a nicety: most of a Spectrum collection is obscure, so a
     * multi-scrape over eight hundred games would raise an exception for every
     * one they do not know. Found on the bench - searching for a real
     * filename from this collection returned 404 and the client threw.
     */
    @Test
    public void a404IsNothingFoundRatherThanAFailure() throws Exception {
        Canned http = new Canned().then(404, "Erreur : Rom/Jeu non trouvee !")
                                  .then(404, "Erreur : Rom/Jeu non trouvee !");

        assertTrue("a 404 should be an empty answer",
                   scraperOn(http).search(new AGame("unknown")).isEmpty());
    }

    /** And a 404 on the hash still falls through to the filename, which is the
     *  whole point of having two questions. */
    @Test
    public void a404OnTheHashStillTriesTheFilename() throws Exception {
        Canned http = new Canned().then(404, "not found").then(200, FOUND);

        List<Candidate> found = scraperOn(http).search(new AGame("unknownhash"));

        assertEquals(1, found.size());
        assertFalse("the filename answer is a guess", found.get(0).exact);
        assertEquals(2, http.asked.size());
    }
}
