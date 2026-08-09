package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.library.meta.Meta;
import dev.ldlab.zedex.library.meta.Metadata;
import dev.ldlab.zedex.storage.Storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
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
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The part of a scrape that needs no screen: what is certain, what is written,
 * and who ends up owning it.
 *
 * The decisions a person makes - which of several candidates, whether to
 * replace a hand edit - live in {@code ScrapeOneGame} because they need a
 * dialog. Everything else is here, which is what makes it checkable.
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

        store = new File(Storage.libraryDirectory(context), "gamelist.xml");
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

    private static Meta fromProvider() {
        return new Meta(null, "Arkanoid", "Bat and ball", "Taito", "Imagine",
                        "Action", "19870101T000000", "1", "0.7500", null);
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
                new Meta("./something-else.tap", "Theirs", null, null, null, null,
                         null, null, null, Meta.ESDE)));
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
                new Meta(PATH, "What ES-DE calls it", null, null, null, null,
                         null, null, null, Meta.ESDE)));
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

    // --- somebody's own work ------------------------------------------------------------

    /**
     * A hand-edited row is noticed before it is replaced.
     *
     * Rare, and the case where losing work is most annoying: the editor
     * shipped so that a wrong scrape could be corrected, and a scrape that
     * silently undid the correction would make the pair of features fight.
     */
    @Test
    public void ahandEditedRowIsRecognisedBeforeBeingOverwritten() {
        Metadata.put(context, new Meta(PATH, "What I called it", null, null, null,
                                       null, null, null, null, Meta.USER));
        Metadata.refresh(context);

        assertTrue(Scrape.wouldOverwriteAHandEdit(context, PATH));
    }

    /** A scraped row is not somebody's work, so re-scraping it asks nothing. */
    @Test
    public void ascrapedRowIsNotTreatedAsSomebodysWork() {
        Metadata.put(context, Scrape.owned(fromProvider(), PATH, "ScreenScraper"));
        Metadata.refresh(context);

        assertFalse(Scrape.wouldOverwriteAHandEdit(context, PATH));
    }

    /** Nor is an ES-DE row, nor a game nothing is known about. */
    @Test
    public void neitherIsAnEsDeRowOrAnUnknownGame() {
        assertFalse("a game nothing is known about",
                    Scrape.wouldOverwriteAHandEdit(context, PATH));

        Metadata.replaceScraped(context, Collections.singletonList(
                new Meta(PATH, "Theirs", null, null, null, null, null, null, null, Meta.ESDE)));
        Metadata.refresh(context);

        assertFalse("an ES-DE row is not somebody's own work",
                    Scrape.wouldOverwriteAHandEdit(context, PATH));
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
}
