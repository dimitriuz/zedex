package dev.ldlab.zedex.library.catalogue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;

import android.content.Context;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import dev.ldlab.zedex.library.scrape.Http;
import dev.ldlab.zedex.storage.Storage;
import dev.ldlab.zedex.storage.Tree;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Bringing a file in from somewhere else.
 *
 * On a device because the destination is SAF and the source is a zip, and
 * neither exists on the JVM. Nothing here touches the network: the fake Http
 * writes a zip this test built, with exactly the awkward contents worth asking
 * about - which is not something a real archive can be relied on to have.
 *
 * <b>Every fixture is named uniquely and only its own documents are removed.</b>
 * The obvious tidy-up - delete Downloaded/Games/ and start clean - deletes
 * real games on any bench that has imported one by hand, and a test that can
 * destroy the collection it is run against is not a test anybody will run
 * twice. So each run stamps its names with nanoTime and the @After deletes
 * the uris this run created, which also fixes the other half of the problem:
 * a run killed by hand never reaches its @After, and a leftover under a
 * unique name collides with nothing.
 */
@RunWith(AndroidJUnit4.class)
public class ImportsTest {

    /** New every run, so a killed run's leftovers collide with nothing and
     *  nothing this test deletes was ever somebody's own. */
    private final String stamp = "zedex-" + System.nanoTime();

    private Context context;
    private Uri tree;
    private final List<Uri> made = new ArrayList<>();

    /**
     * Writes a zip of whatever it was told to hold, in place of a download.
     *
     * <b>Padded to a size the test controls, separate from what the
     * catalogue states.</b> Without this the fake always delivered the same
     * few hundred bytes no matter what a test's {@code download(...)}
     * claimed, so the length check in {@code Imports} - the whole point of
     * rule 2 - fired on every "ordinary" test too, not only the one meant to
     * exercise it. Passing the same number the test's {@code download(...)}
     * states makes "ordinary" mean stated and delivered genuinely agree, and
     * "short" mean this fake deliberately under-delivers against a stated
     * size - see the two constructors below.
     *
     * <b>Stored, never deflated.</b> {@code Deflater.NO_COMPRESSION} keeps
     * the padding's own bytes from being compressed away - repeated filler
     * is exactly what a real {@code Deflater} shrinks best, and letting that
     * happen made the finished zip's actual size depend on how well a
     * handful of padding bytes happened to compress, which is what made an
     * "ordinary" test's outcome flicker between runs on nothing more than
     * incidental stamp-length noise. Whoever next touches this fixture:
     * keep the level pinned, or the flicker comes back.
     */
    private final class Zipped implements Http {
        private final long padTo;
        private final String[] names;

        /**
         * No padding - the fake delivers its bare few hundred bytes.
         *
         * Right only where a small delivery is the point: the short-download
         * test states a size no delivery here could ever reach, and the
         * unstated-size test has nothing to compare against in the first
         * place.
         */
        Zipped(String... names) {
            this(0, names);
        }

        /**
         * Padded so the finished zip reaches at least {@code padTo} bytes -
         * ordinarily the same number the test's own {@code download(...)}
         * states, so the length check is genuinely exercised on the accepted
         * path rather than dodged by an implausibly tiny fixture.
         */
        Zipped(long padTo, String... names) {
            this.padTo = padTo;
            this.names = names;
        }

        @Override
        public Reply get(String url) {
            throw new UnsupportedOperationException("not this test's business");
        }

        @Override
        public String save(String url, File into) throws java.io.IOException {
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(into))) {
                zip.setLevel(Deflater.NO_COMPRESSION);

                for (String name : names) {
                    zip.putNextEntry(new ZipEntry(name));
                    zip.write(padded(("contents of " + name).getBytes(StandardCharsets.US_ASCII)));
                    zip.closeEntry();
                }
            }
            return "00000000000000000000000000000000";
        }

        /** {@code content}, followed by zero bytes out to {@code padTo} -
         *  untouched when {@code content} already reaches it. */
        private byte[] padded(byte[] content) {
            if (content.length >= padTo) return content;

            byte[] out = new byte[(int) padTo];
            System.arraycopy(content, 0, out, 0, content.length);
            return out;
        }
    }

    /** The title carries the stamp too: the several-files test names the
     *  folder after it, and a bare "Head over Heels" would collide with
     *  anything a previous run - or a real import - left behind. */
    private Catalogue.Item item(String kind, Catalogue.Download... files) {
        return new Catalogue.Item("0002259", stamp + "-Head over Heels", "1987", "Ocean",
                                  kind, "Available", null,
                                  Collections.singletonList(
                                          new Catalogue.Version(null, "1987",
                                                                Arrays.asList(files))));
    }

    private static Catalogue.Download download(String format, long size) {
        return new Catalogue.Download("https://example/HeadOverHeels." + format + ".zip",
                                      format, size);
    }

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        tree = Storage.contentFolder(context);
        assumeNotNull("no content folder granted on this device", tree);
    }

    /**
     * Removes what this run made, and nothing else.
     *
     * Never the kind folders themselves - Downloaded/Games is the feature's
     * own folder and may hold games somebody imported on purpose.
     */
    @After
    public void tidyUp() {
        for (Uri document : made) {
            try {
                android.provider.DocumentsContract.deleteDocument(
                        context.getContentResolver(), document);
            } catch (Exception ignored) {
                // A leftover under a nanoTime name collides with nothing;
                // untidy is not a failure.
            }
        }
    }

    // --- the ordinary case --------------------------------------------------------------

    /**
     * A zip of one file yields that file, named as it is named inside.
     *
     * The archive ships HeadOverHeels.tzx.zip holding HeadOverHeels.tzx, and
     * it is the inner name that is already unique and TOSEC-ish. The store
     * keys on path, so an import and a hand-copied file look identical to
     * everything downstream.
     */
    @Test
    public void azipOfOneFileYieldsThatFileUnderItsInnerName() {
        Imports.Result result = kept(Imports.game(
                context, new Zipped(41232, stamp + "-HeadOverHeels.tzx"),
                item("Arcade Game", download("tzx", 41232)),
                download("tzx", 41232)));

        assertNull(result.failure);
        assertEquals(stamp + "-HeadOverHeels.tzx", result.displayName);
        assertEquals(Kinds.GAMES, result.folder);
        assertNotNull(result.documentUri);
    }

    /** And it lands under Downloaded/<kind>/, which is the whole point of not
     *  writing into the root of somebody's collection. */
    @Test
    public void itlandsUnderDownloadedAndTheKindSfolder() {
        kept(Imports.game(context, new Zipped(900, stamp + "-Tasword.tap"),
                     item("Utility", download("tap", 900)), download("tap", 900)));

        Uri downloaded = Tree.find(context, Tree.folder(context, tree), "Downloaded");
        assertNotNull("no Downloaded folder", downloaded);
        assertNotNull("no Applications folder",
                      Tree.find(context, downloaded, Kinds.APPLICATIONS));
    }

    // --- several files ------------------------------------------------------------------

    /**
     * A multi-load game becomes a folder named after it.
     *
     * Several openable files and nowhere to put a second - the library already
     * browses folders, so that is where they go.
     */
    @Test
    public void azipOfSeveralOpenableFilesBecomesAfolder() {
        Imports.Result result = kept(Imports.game(
                context, new Zipped(100000, stamp + "-Robocop - Side 1.tap",
                                    stamp + "-Robocop - Side 2.tap"),
                item("Arcade Game", download("tap", 100000)), download("tap", 100000)));

        assertNull(result.failure);
        assertEquals(stamp + "-Head over Heels", result.displayName);

        Uri downloaded = Tree.find(context, Tree.folder(context, tree), "Downloaded");
        Uri games = Tree.find(context, downloaded, Kinds.GAMES);
        Uri folder = Tree.find(context, games, stamp + "-Head over Heels");

        assertNotNull("no folder for the multi-load game", folder);
        assertNotNull(Tree.find(context, folder, stamp + "-Robocop - Side 1.tap"));
        assertNotNull(Tree.find(context, folder, stamp + "-Robocop - Side 2.tap"));
    }

    /** Anything in the zip that this app cannot open is left behind - a
     *  readme is not a game and a folder full of them is not a library. */
    @Test
    public void whatCannotBeOpenedIsLeftInTheZip() {
        Imports.Result result = kept(Imports.game(
                context, new Zipped(41232, stamp + "-readme.txt", stamp + "-HeadOverHeels.tzx",
                                    stamp + "-cover.jpg"),
                item("Arcade Game", download("tzx", 41232)), download("tzx", 41232)));

        assertEquals(stamp + "-HeadOverHeels.tzx", result.displayName);
    }

    /** A zip with nothing usable in it is a failure with a reason, not a
     *  file written with a name and no contents. */
    @Test
    public void azipOfNothingUsableIsArefusal() {
        Imports.Result result = kept(Imports.game(
                context, new Zipped(41232, stamp + "-readme.txt"),
                item("Arcade Game", download("tzx", 41232)), download("tzx", 41232)));

        assertNotNull("nothing openable was accepted", result.failure);
        assertNull(result.documentUri);
    }

    // --- already there ---------------------------------------------------------------------

    /**
     * A second import of the same thing says so rather than writing a second
     * copy.
     *
     * SAF would happily make "HeadOverHeels (1).tzx", which is how a
     * collection acquires four of everything and how somebody comes to think
     * the first import failed.
     */
    @Test
    public void asecondImportOfTheSameThingSaysSo() {
        kept(Imports.game(context, new Zipped(41232, stamp + "-HeadOverHeels.tzx"),
                     item("Arcade Game", download("tzx", 41232)), download("tzx", 41232)));

        Imports.Result again = kept(Imports.game(
                context, new Zipped(41232, stamp + "-HeadOverHeels.tzx"),
                item("Arcade Game", download("tzx", 41232)), download("tzx", 41232)));

        assertTrue("a second copy was written", again.alreadyThere);
        assertNull(again.failure);
        assertNotNull("nothing to open", again.documentUri);
    }

    // --- recordings -------------------------------------------------------------------------

    /**
     * A recording goes to Recordings whatever the entry's genre says.
     *
     * The one place a file's kind outranks the entry's category: the folder
     * scheme answers "what kind of thing is this file", and a recording of
     * Bomb Jack in the Games folder is not Bomb Jack.
     */
    @Test
    public void arecordingGoesToRecordingsWhateverTheGenreSays() {
        Imports.Result result = kept(Imports.recording(
                context, new Zipped(190222, stamp + "-HeadOverHeels.rzx"),
                item("Arcade Game", download("rzx", 190222)), download("rzx", 190222)));

        assertNull(result.failure);
        assertEquals(Kinds.RECORDINGS, result.folder);
    }

    // --- a short download ----------------------------------------------------------------------

    /**
     * Short means discarded, before anything is unpacked.
     *
     * ZXDB gives a size per file and no checksum, so length is what there is -
     * and a truncated zip that unpacks to half a tape is a game that loads and
     * then crashes, which nobody attributes to the download.
     */
    @Test
    public void adownloadThatArrivedShortIsThrownAway() {
        Imports.Result result = kept(Imports.game(
                context, new Zipped(stamp + "-HeadOverHeels.tzx"),
                item("Arcade Game", download("tzx", 9_000_000)),
                download("tzx", 9_000_000)));

        assertNotNull("a short download was accepted", result.failure);
        assertNull(result.documentUri);
    }

    /** A catalogue that does not say how big is not a reason to refuse - most
     *  of them do not. */
    @Test
    public void anunstatedSizeIsNotAfailure() {
        Imports.Result result = kept(Imports.game(
                context, new Zipped(stamp + "-HeadOverHeels.tzx"),
                item("Arcade Game", download("tzx", -1)), download("tzx", -1)));

        assertNull(result.failure);
        assertNotNull(result.documentUri);
    }

    // --- nothing is left behind -----------------------------------------------------------------

    /** The cache is empty afterwards, however it went. A failed import that
     *  leaves a megabyte behind per attempt is a disk that fills up for a
     *  reason nobody can find. */
    @Test
    public void thecacheIsEmptyAfterwards() {
        File cache = new File(context.getCacheDir(), "imports");

        kept(Imports.game(context, new Zipped(41232, stamp + "-readme.txt"),
                     item("Arcade Game", download("tzx", 41232)), download("tzx", 41232)));

        File[] left = cache.listFiles();
        assertTrue("the cache kept " + (left == null ? 0 : left.length) + " files",
                   left == null || left.length == 0);
    }

    /** Remembers what a call wrote, so tidyUp removes that and only that. */
    private Imports.Result kept(Imports.Result result) {
        if (result != null && result.documentUri != null && !result.alreadyThere) {
            made.add(result.documentUri);
        }
        return result;
    }
}
