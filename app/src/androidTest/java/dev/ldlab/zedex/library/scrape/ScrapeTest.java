package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.library.meta.Artwork;
import dev.ldlab.zedex.library.meta.Meta;
import dev.ldlab.zedex.library.meta.Metadata;
import dev.ldlab.zedex.storage.Storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The part of a scrape that needs no screen: what is certain, what is written,
 * and who ends up owning it.
 *
 * {@code ScrapeOneGame} no longer calls any of this - it runs the multi-source
 * loop in {@code Blend}, which merges rather than replaces. {@link
 * Scrape#apply} has exactly one caller left, {@code Imports.describe}, for a
 * catalogue import: there the candidate is not a guess but the entry the
 * catalogue itself just fetched, so there is nobody to ask and nothing to
 * merge against - the row is new. {@code owned()}'s replace-the-row shape is
 * still correct there for the same reason it stopped being correct for a
 * one-game scrape of an existing row: nothing has been typed into a game that
 * did not exist a moment ago.
 *
 * The bench's real store is moved aside and put back: it is somebody's whole
 * scraped collection.
 */
@RunWith(AndroidJUnit4.class)
public class ScrapeTest {

    private static final String PATH = "./zedex-test/Scraped Game.tap";

    private Context context;
    private File store;
    private byte[] theirs;

    @Before
    public void setUp() throws IOException {
        context = ApplicationProvider.getApplicationContext();

        File root = Storage.root(context);
        assumeTrue("the data folder is not usable on this device: " + root,
                   root.isDirectory() && Storage.isWritable(root));

        store = new File(Storage.libraryDirectory(context), "metadata.json");
        theirs = store.isFile() ? Files.readAllBytes(store.toPath()) : null;

        Metadata.clear(context);
    }

    @After
    public void putItBack() throws IOException {
        if (store == null) return;

        if (theirs == null) {
            store.delete();
        } else {
            try (FileOutputStream out = new FileOutputStream(store)) {
                out.write(theirs);
            }
        }
        Metadata.refresh(context);
    }

    private static final String LAYOUT = "# Arkanoid \\n0:left = o ;; left";

    private static Meta fromProvider() {
        return Meta.at(null)
                .name("Arkanoid").desc("Bat and ball")
                .developer("Taito").publisher("Imagine")
                .genre("Action").released("19870101T000000")
                .players("1").rating("0.7500")
                .keymap(LAYOUT)
                .build();
    }

    // --- who owns what comes back ------------------------------------------------

    /**
     * A scraped row carries the provider's name, not ES-DE's and not the
     * user's.
     *
     * Both halves matter. Marked as ES-DE's, the next link would overwrite it -
     * the thing the ownership rule exists to prevent. Marked as the user's, the
     * hand editor would offer to "forget my edits" on something nobody typed.
     */
    @Test
    public void ascrapedRowIsOwnedByTheProviderThatFetchedIt() {
        Meta owned = Scrape.owned(fromProvider(), PATH, "ScreenScraper");

        assertEquals("ScreenScraper", owned.source);
        assertFalse("a scraped row must not read as ES-DE's", owned.isEsde());
        assertFalse("a scraped row must not read as a hand edit", owned.isMine());
    }

    /** And it is keyed, because the provider has never heard of the path. */
    @Test
    public void thepathIsPutOnByUsBecauseTheProviderHasNone() {
        assertNull("the provider should not be inventing paths", fromProvider().path);
        assertEquals(PATH, Scrape.owned(fromProvider(), PATH, "ScreenScraper").path);
    }

    /** Everything else survives the journey. */
    @Test
    public void thefactsAreCarriedThroughUnchanged() {
        Meta owned = Scrape.owned(fromProvider(), PATH, "ScreenScraper");

        assertEquals("Arkanoid", owned.name);
        assertEquals("Taito", owned.developer);
        assertEquals("Imagine", owned.publisher);
        assertEquals("Action", owned.genre);
        assertEquals("19870101T000000", owned.released);
        assertEquals("0.7500", owned.rating);
        assertEquals("1", owned.players);
    }

    /**
     * A link leaves a scraped row alone.
     *
     * The generalisation this part forced: the rule was "a link keeps hand
     * edits", which was the same thing as "a link keeps what ES-DE does not
     * own" right up until this app could scrape for itself. A row fetched from
     * ScreenScraper is nobody's hand edit and must survive a link every bit as
     * much as one that was typed.
     */
    @Test
    public void alinkLeavesAScrapedRowAlone() {
        Metadata.put(context, Scrape.owned(fromProvider(), PATH, "ScreenScraper"));
        Metadata.refresh(context);

        Metadata.replaceScraped(context, Collections.singletonList(
                Meta.at("./something-else.tap").name("Theirs").source(Meta.ESDE).build()));
        Metadata.refresh(context);

        assertNotNull("an ES-DE link discarded what this app had scraped",
                      Metadata.forPath(context, PATH));
        assertEquals("Arkanoid", Metadata.forPath(context, PATH).name);
    }

    /** And wins over a scraped row for the same game, the same way a hand edit
     *  does - somebody asked for this one deliberately. */
    @Test
    public void ascrapedRowWinsOverWhatALinkBringsForTheSameGame() {
        Metadata.put(context, Scrape.owned(fromProvider(), PATH, "ScreenScraper"));

        Metadata.replaceScraped(context, Collections.singletonList(
                Meta.at(PATH).name("What ES-DE calls it").source(Meta.ESDE).build()));
        Metadata.refresh(context);

        assertEquals("Arkanoid", Metadata.forPath(context, PATH).name);
    }

    // --- when to ask, and when not ----------------------------------------------------

    private static Candidate exact(String name) {
        return new Candidate("1", name, "1987", "Imagine", true);
    }

    private static Candidate guess(String name) {
        return new Candidate("2", name, "1987", "Imagine", false);
    }

    /**
     * One the provider is sure of is used without asking; anything else is
     * asked about.
     *
     * A guess acted on silently is one game's cover on another for ever, and
     * the filename search is a guess more often than not on a Spectrum
     * collection - so even a single uncertain candidate earns a dialog.
     */
    @Test
    public void onlyOneCertainCandidateGoesThroughWithoutAsking() {
        assertTrue(Scrape.certain(Collections.singletonList(exact("Arkanoid"))));

        assertFalse("a single guess is still a guess",
                    Scrape.certain(Collections.singletonList(guess("Arkanoid"))));
        assertFalse("two certain answers are not one certain answer",
                    Scrape.certain(Arrays.asList(exact("A"), exact("B"))));
        assertFalse(Scrape.certain(Collections.emptyList()));
    }

    // --- what a scrape costs -------------------------------------------------------------

    /**
     * The default is the three folders this app actually draws.
     *
     * Not a nicety: every medium is a separate request against the day's
     * allowance, because a cover is a {@code mediaJeu.php} call exactly like a
     * search is. Three plus the search is four a game, which leaves room for a
     * collection twice over; everything the provider has would be nine or ten
     * and would not.
     */
    @Test
    public void thedefaultTakesTheThreeThisAppDraws() {
        Provider.Wanted usual = Provider.Wanted.usual();

        assertTrue("the grid draws the cover", usual.wants("covers"));
        assertTrue("the pane's gallery wants a screenshot", usual.wants("screenshots"));
        assertTrue(usual.wants("titlescreens"));

        assertFalse("video is twenty megabytes and opt-in", usual.wants("videos"));
        assertFalse("a manual is rarely looked at and opt-in", usual.wants("manuals"));

        assertEquals("three media plus the search is four requests a game",
                     3, usual.requests());
    }

    /** Metadata only is a real thing to want, and the cheapest scrape there
     *  is - one request. */
    @Test
    public void askingForNothingCostsNothingExtra() {
        assertFalse(Provider.Wanted.nothing().any());
        assertEquals(0, Provider.Wanted.nothing().requests());
    }

    /**
     * The control layout survives being keyed and owned.
     *
     * It did not. {@code owned} rebuilt the row through the ten-argument
     * constructor, which leaves the layout null, so every scrape stored
     * everything except the one field that had just been added. Nothing
     * failed and nothing logged - the store simply had no layout in it, and it
     * took comparing a real scrape against a live reply to see.
     *
     * The same trap {@code Meta.with}'s own comment names: ten arguments is
     * more than anybody counts correctly at a call site.
     */
    @Test
    public void thecontrolLayoutSurvivesBeingKeyedAndOwned() {
        assertEquals("owned() dropped the control layout",
                     LAYOUT, Scrape.owned(fromProvider(), PATH, "ScreenScraper").keymap);
    }

    /** And through the store, which is the whole journey. */
    @Test
    public void thelayoutSurvivesTheWholeJourney() {
        Metadata.put(context, Scrape.owned(fromProvider(), PATH, "ScreenScraper"));
        Metadata.refresh(context);

        assertEquals(LAYOUT, Metadata.forPath(context, PATH).keymap);
    }

    // --- apply() owns the half of the guarantee Downloads gave up ---------------------

    /**
     * The happy path: a fetched cover must not stay behind a cached miss.
     *
     * {@code Downloads.fetch} stopped forgetting the game on its own account -
     * see its own javadoc - so this is entirely {@code apply}'s job now, and
     * nothing before this test called {@code apply} at all to prove it still
     * happens.
     */
    @Test
    public void applyForgetsTheGameSoAFetchedCoverIsSeen() throws Exception {
        assertNull("nothing yet", Artwork.picture(context, PATH));   // caches the miss

        try {
            Scrape.apply(context, new FetchesOneCover(), new WritesRealBytes(),
                        exact("Arkanoid"), PATH, Provider.Wanted.of("covers"));

            assertNotNull("the miss was never forgotten, so the cover is invisible",
                          Artwork.picture(context, PATH));
        } finally {
            cleanUpMedia();
        }
    }

    /**
     * The one that matters: a refusal partway through must not strand what
     * already arrived behind that same cached miss.
     *
     * A spent quota on the second medium throws out of {@code Downloads.fetch}
     * before it ever returns, so the old shape - forgetting only on the way
     * to a normal return - would skip the forget entirely on exactly the path
     * where losing the cover already paid for matters most. The mutation
     * check in the report moves the forget back to that shape and shows this
     * test catching it.
     */
    @Test
    public void applyForgetsWhatArrivedEvenWhenARefusalCutTheRestShort() throws Exception {
        assertNull("nothing yet", Artwork.picture(context, PATH));   // caches the miss

        try {
            try {
                Scrape.apply(context, new FetchesTwoMediaSecondRefused(),
                            new WritesFirstThenRefusesSecond(), exact("Arkanoid"), PATH,
                            Provider.Wanted.of("covers", "screenshots"));
                fail("the quota refusal should have come out");
            } catch (ScrapeException e) {
                assertEquals(ScrapeException.Kind.QUOTA_EXCEEDED, e.kind);
            }

            assertNotNull("the cover that did arrive was left behind a cached miss",
                          Artwork.picture(context, PATH));
        } finally {
            cleanUpMedia();
        }
    }

    /** Whatever the two tests above wrote to disk, and the cache entries they
     *  made for {@link #PATH} - the class's other tests never touch either. */
    private void cleanUpMedia() {
        Artwork.fileFor(context, PATH, "covers", "png").delete();
        Artwork.fileFor(context, PATH, "screenshots", "png").delete();
        Artwork.forget();
    }

    /** Answers one cover for whatever it is asked, ignoring the candidate and
     *  the wanted set - the fakes below are about apply()'s wiring, not about
     *  what a provider decides to fetch. */
    private static final class FetchesOneCover implements Provider {
        @Override public String name() { return "Fake"; }
        @Override public boolean configured() { return true; }
        @Override public List<Candidate> search(Game game) { return null; }

        @Override public Scraped fetch(Candidate candidate, Wanted wanted) {
            return new Scraped(fromProvider(), Collections.singletonList(
                    new Medium("covers", "https://x/cover.png", "png", null)));
        }

        @Override public Quota quota() { return Quota.unknown(); }
        @Override public int costPerGame(Wanted wanted) { return 1; }

        @Override public ScrapeException refusalFor(int status) {
            return new ScrapeException(ScrapeException.Kind.NETWORK, "status " + status);
        }
    }

    /** Answers two media, and maps every status to a spent quota - the status
     *  itself does not matter here, only that {@code Downloads.stopIfHopeless}
     *  rethrows rather than counting the second medium as one missing
     *  picture. */
    private static final class FetchesTwoMediaSecondRefused implements Provider {
        @Override public String name() { return "Fake"; }
        @Override public boolean configured() { return true; }
        @Override public List<Candidate> search(Game game) { return null; }

        @Override public Scraped fetch(Candidate candidate, Wanted wanted) {
            return new Scraped(fromProvider(), Arrays.asList(
                    new Medium("covers", "https://x/a.png", "png", null),
                    new Medium("screenshots", "https://x/b.png", "png", null)));
        }

        @Override public Quota quota() { return Quota.unknown(); }
        @Override public int costPerGame(Wanted wanted) { return 2; }

        @Override public ScrapeException refusalFor(int status) {
            return new ScrapeException(ScrapeException.Kind.QUOTA_EXCEEDED, "status " + status);
        }
    }

    /** Writes real bytes for whatever it is asked to save - no hash to check,
     *  since these two tests are about visibility, not about a truncated
     *  download. */
    private static final class WritesRealBytes implements Http {
        @Override public Reply get(String url) { throw new UnsupportedOperationException(); }

        @Override public String save(String url, File into) throws IOException {
            File parent = into.getParentFile();
            if (parent != null) parent.mkdirs();

            try (FileOutputStream out = new FileOutputStream(into)) {
                out.write("a cover".getBytes(StandardCharsets.UTF_8));
            }
            return null;
        }
    }

    /** The first medium arrives; the second is refused with a status the fake
     *  provider above maps to a spent quota. */
    private static final class WritesFirstThenRefusesSecond implements Http {
        private int calls;

        @Override public Reply get(String url) { throw new UnsupportedOperationException(); }

        @Override public String save(String url, File into) throws IOException {
            if (calls++ == 0) {
                File parent = into.getParentFile();
                if (parent != null) parent.mkdirs();

                try (FileOutputStream out = new FileOutputStream(into)) {
                    out.write("a cover".getBytes(StandardCharsets.UTF_8));
                }
                return null;
            }
            throw new Http.Refused(429);
        }
    }
}
