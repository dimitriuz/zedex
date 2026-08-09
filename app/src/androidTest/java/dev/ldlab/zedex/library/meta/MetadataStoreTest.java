package dev.ldlab.zedex.library.meta;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The scraped store itself: written, read back, and every way it can be
 * broken.
 *
 * The rest of 11.4's number three - {@link EsdeSettingsTest} covers the
 * synthetic-root wrapper over ES-DE's own settings file; this is our own
 * {@code library/gamelist.xml}, the thing a link produces and every row in the
 * library then reads.
 *
 * What makes it worth its own file rather than a corner of the link's is that
 * a mistake here is not visible as an error. The store failing to load reads
 * as "nothing has ever been scraped", which is exactly what a device that has
 * never been linked looks like - so the app would show filenames, and the
 * Library tab would say so calmly, and nothing anywhere would suggest the
 * eight hundred games it knew about yesterday had gone.
 *
 * The bench's real store is moved aside in {@link #setUp} and put back in
 * {@link #putItBack}. It is somebody's whole scraped collection and it takes
 * minutes to rebuild.
 */
@RunWith(AndroidJUnit4.class)
public class MetadataStoreTest {

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

        // The in-memory copy has to be told, or whatever runs next in this
        // process reads the test's games instead of the bench's.
        Metadata.refresh(context);
    }

    private static Meta game(String path, String name) {
        return new Meta(path, name, "a description", "Ocean", "Ocean",
                        "Platform", "1984", "1-2", "0.9", "esde");
    }

    // --- the round trip -------------------------------------------------------------

    @Test
    public void agameSurvivesBeingWrittenAndReadBack() {
        Metadata.replaceAll(context, Collections.singletonList(
                game("./games/Tujad.z80", "Tujad")));
        Metadata.refresh(context);

        Meta back = Metadata.forPath(context, "./games/Tujad.z80");
        assertNotNull("the game was not found by the path it was stored under", back);
        assertEquals("Tujad", back.name);
        assertEquals("Ocean", back.developer);
        assertEquals("1984", back.year());
        assertEquals("Platform", back.genre);
    }

    /** It really goes through the file, not just the cache: a fresh read of
     *  the store has to find it, which is the only thing that survives the
     *  app being killed. */
    @Test
    public void whatWasWrittenIsOnDiskAndNotOnlyInMemory() throws IOException {
        Metadata.replaceAll(context, Collections.singletonList(
                game("./games/Tujad.z80", "Tujad")));

        assertTrue("no store file was written", store.isFile());

        String xml = new String(Files.readAllBytes(store.toPath()), StandardCharsets.UTF_8);
        assertTrue("the file does not name the game: " + xml, xml.contains("Tujad"));

        Metadata.refresh(context);
        assertNotNull(Metadata.forPath(context, "./games/Tujad.z80"));
    }

    @Test
    public void thecountAndTheWholeCollectionAgree() {
        Metadata.replaceAll(context, Arrays.asList(
                game("./a.tap", "A"), game("./b.tap", "B"), game("./c.tap", "C")));
        Metadata.refresh(context);

        assertEquals(3, Metadata.count(context));
        assertEquals(3, Metadata.all(context).size());
    }

    /** A path nothing was stored under answers null, not somebody else's
     *  game - attaching one game's description to another is the failure this
     *  keying exists to avoid. */
    @Test
    public void anUnknownPathAnswersNothing() {
        Metadata.replaceAll(context, Collections.singletonList(game("./a.tap", "A")));
        Metadata.refresh(context);

        assertNull(Metadata.forPath(context, "./somewhere/else.tap"));
    }

    /** A game with no path of its own is dropped rather than stored under an
     *  empty key that every unmatched lookup would then find. */
    @Test
    public void agameWithNoPathIsNotStored() {
        Metadata.replaceAll(context, Arrays.asList(
                game("", "No path"), game(null, "Also none"), game("./real.tap", "Real")));
        Metadata.refresh(context);

        assertEquals("only the game with a path should be stored",
                     1, Metadata.count(context));
        assertNull(Metadata.forPath(context, ""));
        assertNotNull(Metadata.forPath(context, "./real.tap"));
    }

    // --- when it was last linked -------------------------------------------------------

    /**
     * {@code lastLinked} moves when the store is replaced, and that is what
     * the library watches.
     *
     * {@code LibraryActivity.onResume} compares it and throws away every
     * cached name and picture when it has changed - see its own comment. A
     * link that did not move it would leave the list showing filenames for
     * games it now knows the names of, until something else happened to clear
     * the cache.
     */
    @Test
    public void relinkingMovesTheLinkedTime() throws InterruptedException {
        Metadata.replaceAll(context, Collections.singletonList(game("./a.tap", "A")));
        Metadata.refresh(context);
        long first = Metadata.lastLinked(context);
        assertTrue("nothing recorded a link time at all", first > 0);

        Thread.sleep(1100);   // the stamp is in milliseconds but the file's mtime is not

        Metadata.replaceAll(context, Collections.singletonList(game("./b.tap", "B")));
        Metadata.refresh(context);

        assertTrue("relinking did not move lastLinked: " + first + " then "
                   + Metadata.lastLinked(context),
                   Metadata.lastLinked(context) > first);
    }

    /** Nothing linked is a link time of zero, which is what the settings row
     *  reads as "never". */
    @Test
    public void neverLinkedIsZero() {
        Metadata.clear(context);

        assertEquals(0, Metadata.lastLinked(context));
        assertEquals(0, Metadata.count(context));
    }

    // --- unlinking -----------------------------------------------------------------------

    @Test
    public void clearingForgetsTheGamesAndTheFile() {
        Metadata.replaceAll(context, Collections.singletonList(game("./a.tap", "A")));
        Metadata.refresh(context);
        assertEquals(1, Metadata.count(context));

        Metadata.clear(context);

        assertEquals(0, Metadata.count(context));
        assertFalse("Unlink left the store file behind", store.isFile());
        assertNull(Metadata.forPath(context, "./a.tap"));
    }

    // --- a store that will not read ---------------------------------------------------------

    /**
     * A corrupt store reads as nothing scraped, and never throws.
     *
     * The library reads this while binding rows, so throwing here is not an
     * error message - it is a list that cannot be drawn.
     */
    @Test
    public void acorruptStoreReadsAsEmptyRatherThanThrowing() throws IOException {
        store.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(store)) {
            out.write("<gameList><game><path>./a.tap".getBytes(StandardCharsets.UTF_8));
        }
        Metadata.refresh(context);

        assertEquals(0, Metadata.count(context));
        assertNull(Metadata.forPath(context, "./a.tap"));
    }

    /** And it can be linked over afterwards - the store is mendable from
     *  inside the app, which is the point of not throwing. */
    @Test
    public void acorruptStoreCanBeReplacedByLinkingAgain() throws IOException {
        store.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(store)) {
            out.write("not xml at all".getBytes(StandardCharsets.UTF_8));
        }
        Metadata.refresh(context);

        Metadata.replaceAll(context, Collections.singletonList(game("./a.tap", "A")));
        Metadata.refresh(context);

        assertEquals(1, Metadata.count(context));
    }

    /**
     * No temporary file is left beside the store.
     *
     * The write goes to {@code gamelist.xml.tmp} and is renamed, so that a
     * failure part way through cannot leave the real file half written - which
     * would then read back as no metadata at all, the moment it lost the tag
     * that made it well formed. A {@code .tmp} still there afterwards means
     * the rename did not happen and what is being read is the old store.
     */
    @Test
    public void nothingIsLeftHalfWritten() {
        Metadata.replaceAll(context, Collections.singletonList(game("./a.tap", "A")));

        File temp = new File(store.getParentFile(), "gamelist.xml.tmp");
        assertFalse("a half-written " + temp.getName() + " was left behind", temp.exists());
        assertTrue("the store itself is not there", store.isFile());
    }

    /**
     * Replacing everything with nothing does replace it.
     *
     * Deliberate, and its own comment says it is logged loudly rather than
     * refused: the caller has already decided, and second-guessing here would
     * make Unlink and an empty link behave differently for no reason a user
     * could see.
     */
    @Test
    public void replacingWithAnEmptyListEmptiesTheStore() {
        Metadata.replaceAll(context, Collections.singletonList(game("./a.tap", "A")));
        Metadata.refresh(context);
        assertEquals(1, Metadata.count(context));

        Metadata.replaceAll(context, new ArrayList<>());
        Metadata.refresh(context);

        assertEquals(0, Metadata.count(context));
    }

    /** Two games claiming the same path leave one - a store keyed by path
     *  cannot hold both, and the later one wins rather than the read failing. */
    @Test
    public void twoGamesOnOnePathLeaveOne() {
        Metadata.replaceAll(context, Arrays.asList(
                game("./same.tap", "First"), game("./same.tap", "Second")));
        Metadata.refresh(context);

        assertEquals(1, Metadata.count(context));
        assertEquals("Second", Metadata.forPath(context, "./same.tap").name);
    }

    /** A description with characters XML has rules about survives - a game
     *  blurb with an ampersand in it is not unusual. */
    @Test
    public void awkwardTextSurvivesTheRoundTrip() {
        String awkward = "Tom & Jerry <the> \"one\" with 'apostrophes'";

        Metadata.replaceAll(context, Collections.singletonList(
                new Meta("./a.tap", awkward, awkward, null, null, null,
                         null, null, null, "esde")));
        Metadata.refresh(context);

        Meta back = Metadata.forPath(context, "./a.tap");
        assertNotNull(back);
        assertEquals(awkward, back.name);
        assertEquals(awkward, back.desc);
    }

    /** And the list survives being large enough to be the real thing - this
     *  is read on every cold start and bound per row. */
    @Test
    public void athousandGamesRoundTrip() {
        List<Meta> many = new ArrayList<>();
        for (int at = 0; at < 1000; at++) many.add(game("./game" + at + ".tap", "Game " + at));

        Metadata.replaceAll(context, many);
        Metadata.refresh(context);

        assertEquals(1000, Metadata.count(context));
        assertEquals("Game 999", Metadata.forPath(context, "./game999.tap").name);
    }
}
