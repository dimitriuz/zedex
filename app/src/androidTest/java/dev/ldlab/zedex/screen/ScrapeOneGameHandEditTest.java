package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.Screen;
import dev.ldlab.zedex.library.Entry;
import dev.ldlab.zedex.library.meta.Meta;
import dev.ldlab.zedex.library.meta.Metadata;
import dev.ldlab.zedex.library.scrape.Candidate;
import dev.ldlab.zedex.library.scrape.Provider;
import dev.ldlab.zedex.library.scrape.Quota;
import dev.ldlab.zedex.library.scrape.ScrapeException;
import dev.ldlab.zedex.storage.Storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The defect Task 7's review found: a one-game scrape must not destroy a
 * hand-edited row.
 *
 * Before this task, {@code ScrapeOneGame} wrote a scraped answer through
 * {@code Scrape.apply}, whose {@code owned()} rebuilds the row from the
 * provider's own {@code Meta} - replacing every field the provider also has,
 * including one a person typed, and dropping {@link Meta#USER} from the
 * contributor list so the editor's "forget my edits" stopped acknowledging
 * the edit ever happened. Converting to {@code Blend.run}, which merges with
 * {@code Merge.of} and never overwrites a value that is already there, is
 * what this test exists to prove actually happened - reading the diff is not
 * enough, because the same defect shipped once already behind a green suite.
 *
 * Driven through {@code ScrapeOneGame.look}, the package-private method
 * {@code scrape(Entry)} delegates to once a path has been resolved, rather
 * than through {@code scrape} itself: {@code scrape} asks {@code
 * Scrapers.enabled}, which for this build is a real, network-backed source,
 * and a hand-edit-survival test must not depend on a live service answering
 * in a particular way. {@code look} takes the source list directly, so a
 * fake source that answers instantly and offers no media is enough to
 * exercise the whole merge-and-store path with no network and no dialog -
 * the one candidate it returns is exact, so {@code Blend} never needs a
 * chooser, and it offers no media, so nothing is staged and {@code
 * ArtworkChoice} never appears.
 */
@RunWith(AndroidJUnit4.class)
public class ScrapeOneGameHandEditTest {

    private static final String PATH = "./zedex-handedit-test/Game.tap";
    private static final long TIMEOUT_MS = 10_000;

    private Context context;
    private Activity activity;
    private java.io.File store;
    private byte[] theirs;

    @Before
    public void setUp() throws IOException {
        context = ApplicationProvider.getApplicationContext();

        java.io.File root = Storage.root(context);
        org.junit.Assume.assumeTrue("the data folder is not usable on this device: " + root,
                                    root.isDirectory() && Storage.isWritable(root));

        store = new java.io.File(Storage.libraryDirectory(context), "metadata.json");
        theirs = store.isFile() ? Files.readAllBytes(store.toPath()) : null;

        Metadata.clear(context);
        activity = anyActivity();
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

    /** Any activity of ours to construct {@code ScrapeOneGame} against - what
     *  is under test is the merge, not who launched it. */
    private Activity anyActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                LibraryActivity.class.getName(), null, false);

        Screen.suppressFirstRun(context);
        context.startActivity(
                new android.content.Intent(context, LibraryActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));

        Activity launched = instrumentation.waitForMonitorWithTimeout(monitor, TIMEOUT_MS);
        assertTrue("the library never came up", launched != null);
        return launched;
    }

    /**
     * Answers with one certain candidate and, once fetched, a genre that
     * disagrees with what was typed - proving a disagreement does not win -
     * plus a field the hand-edited row never had, proving a real gap is
     * still filled.
     */
    private static final class FillsAGapAndDisagreesOnGenre implements Provider {
        @Override public String name() { return "Fake"; }
        @Override public boolean configured() { return true; }

        @Override
        public List<Candidate> search(Game game) {
            // Exact, so Blend.identify uses it without a chooser - this is
            // about the merge, not about which of several to pick.
            return Collections.singletonList(
                    new Candidate("h1", "Hand-Typed Game", "1987", "Imagine", true));
        }

        @Override
        public Scraped fetch(Candidate candidate, Wanted wanted) {
            return new Scraped(
                    Meta.at(null)
                            .name("Hand-Typed Game")
                            .genre("Provider's Genre")
                            .developer("A Developer Nobody Typed")
                            .build(),
                    Collections.emptyList());
        }

        @Override public Quota quota() { return Quota.unknown(); }
        @Override public int costPerGame(Wanted wanted) { return 1; }

        @Override
        public ScrapeException refusalFor(int status) {
            return new ScrapeException(ScrapeException.Kind.NETWORK, "status " + status);
        }
    }

    @Test
    public void ascrapeFillsGapsAroundAHandEditAndNeverOverwritesIt() throws Exception {
        Meta handEdited = Meta.at(PATH)
                .name("Hand-Typed Game")
                .genre("My Own Genre")
                .contributor(Meta.USER)
                .build();
        Metadata.put(context, handEdited);

        assertTrue("the fixture itself is not a hand edit; fix it first", handEdited.isMine());

        Entry entry = new Entry(Entry.Kind.FILE, "Game.tap", null, null, 4096, 0);
        List<Provider> sources = Collections.singletonList(new FillsAGapAndDisagreesOnGenre());

        // look() shows a ProgressDialog and so, like scrape(), has to be
        // called from the UI thread - the ordinary case, since a menu action
        // runs there already.
        ScrapeOneGame scraper = new ScrapeOneGame((LibraryActivity) activity);
        activity.runOnUiThread(() -> scraper.look(sources, entry, PATH));

        Meta after = awaitConsultedBy("Fake");

        assertEquals("a source's own guess must never replace what was typed",
                     "My Own Genre", after.genre);
        assertEquals("a real gap must still be filled in around the edit",
                     "A Developer Nobody Typed", after.developer);

        assertTrue("scraping must not turn off isMine() - the editor's "
                   + "'forget my edits' has to keep meaning something",
                   after.isMine());
        assertTrue("the source that answered has to be recorded too",
                   after.sources().contains("Fake"));
        assertEquals(Arrays.asList(Meta.USER, "Fake"), after.sources());
    }

    /**
     * Polls the store rather than sleeping a guessed duration - {@code
     * Blend.run} happens on {@code Work.alone}'s own thread, and how long
     * that takes is not this test's business to predict.
     */
    private Meta awaitConsultedBy(String source) throws InterruptedException {
        long until = System.currentTimeMillis() + TIMEOUT_MS;

        while (System.currentTimeMillis() < until) {
            Meta found = Metadata.forPath(context, PATH);
            if (found != null && found.sources().contains(source)) return found;
            Thread.sleep(50);
        }

        throw new AssertionError("the scrape never finished merging within " + TIMEOUT_MS + "ms");
    }
}
