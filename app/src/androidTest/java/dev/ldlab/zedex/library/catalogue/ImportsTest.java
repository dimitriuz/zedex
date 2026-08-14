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
     * is exactly what a real {@code Deflater} shrinks best, and a finished
     * zip whose actual size depends on how compressible its own padding
     * happened to be is a fixture that could pass or fail depending on
     * nothing this test controls. (The flicker actually observed while this
     * fixture was being built came earlier than that, and for a different
     * reason: before padding existed at all, the fake's real byte count
     * came only from a few dozen bytes of literal content plus zip
     * overhead, which shifted slightly with how many digits a `stamp`'s
     * {@code nanoTime} happened to have that run - enough, on one occasion,
     * to land on either side of a stated size the check compared it
     * against. Padding removes that dependency outright; the compression
     * hazard here is a second, separate one worth closing at the same
     * time.) Whoever next touches this fixture: keep the level pinned to
     * {@code NO_COMPRESSION}, or a new flicker of this second kind becomes
     * possible.
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

                    // A name ending in "/" is a directory entry: no bytes and
                    // no padding, which is exactly what makes an archive of a
                    // million of them cheap to build and expensive to walk.
                    // See thewalkCapCountsDirectoriesToo.
                    if (!name.endsWith("/")) {
                        zip.write(padded(
                                ("contents of " + name).getBytes(StandardCharsets.US_ASCII),
                                padTo));
                    }
                    zip.closeEntry();
                }
            }
            return "00000000000000000000000000000000";
        }
    }

    /**
     * Delivers a real zip that stops partway through, in place of a
     * download that broke off mid-transfer.
     *
     * Two entries, each padded well past a single buffer's worth, and only
     * the first two thirds of the finished bytes are ever written to disk -
     * enough for the first entry to extract whole and the second to fail
     * mid-stream (a truncated {@code Inflater} input throws rather than
     * quietly returning less), which is exactly the shape that once leaked
     * the first entry's cache file: {@code Imports.unzip} only handed its
     * findings back at the very end, so an exception partway through took
     * everything found so far down with it before {@code Imports.bring}'s
     * cleanup ever saw it.
     *
     * No stated size, deliberately - see {@code
     * acorruptZipLeaksNothingIntoTheCache} for why rule 2's length check
     * must not be the thing that catches this.
     */
    private final class Truncated implements Http {
        private final String[] names;

        Truncated(String... names) {
            this.names = names;
        }

        @Override
        public Reply get(String url) {
            throw new UnsupportedOperationException("not this test's business");
        }

        @Override
        public String save(String url, File into) throws java.io.IOException {
            java.io.ByteArrayOutputStream whole = new java.io.ByteArrayOutputStream();

            try (ZipOutputStream zip = new ZipOutputStream(whole)) {
                zip.setLevel(Deflater.NO_COMPRESSION);

                for (String name : names) {
                    zip.putNextEntry(new ZipEntry(name));
                    zip.write(padded(("contents of " + name).getBytes(StandardCharsets.US_ASCII),
                                      5000));
                    zip.closeEntry();
                }
            }

            byte[] bytes = whole.toByteArray();
            try (FileOutputStream out = new FileOutputStream(into)) {
                out.write(bytes, 0, bytes.length * 2 / 3);
            }
            return "00000000000000000000000000000000";
        }
    }

    /** {@code content}, followed by zero bytes out to {@code padTo} -
     *  untouched when {@code content} already reaches it. Shared by both
     *  fakes above, since a truncated archive still needs entries big
     *  enough for the cut to land inside one rather than neatly between
     *  them. */
    private static byte[] padded(byte[] content, long padTo) {
        if (content.length >= padTo) return content;

        byte[] out = new byte[(int) padTo];
        System.arraycopy(content, 0, out, 0, content.length);
        return out;
    }

    /** The title carries the stamp too: the several-files test names the
     *  folder after it, and a bare "Head over Heels" would collide with
     *  anything a previous run - or a real import - left behind. */
    private Catalogue.Item item(String kind, Catalogue.Download... files) {
        return new Catalogue.Item("0002259", stamp + "-Head over Heels", "1987", "Ocean",
                                  kind, "Available", null,
                                  Collections.singletonList(
                                          new Catalogue.Version(null, "1987",
                                                                Arrays.asList(files))),
                                  null);
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

        // And the details go to a member, not to the folder. A folder has no
        // row that draws metadata - EntryAdapter returns before it looks
        // anything up for one - so a Meta keyed on the folder is a scrape
        // request and a download of artwork spent on a key nothing reads. See
        // Imports.Result.describeUri.
        assertEquals("a folder import must describe one of its members",
                     Tree.find(context, folder, stamp + "-Robocop - Side 1.tap"),
                     result.describeUri);
    }

    /** And a single file describes itself, which is almost every import - the
     *  two uris are the same thing whenever there is only one. */
    @Test
    public void asingleFileDescribesItself() {
        Imports.Result result = kept(Imports.game(
                context, new Zipped(41232, stamp + "-HeadOverHeels.tzx"),
                item("Arcade Game", download("tzx", 41232)), download("tzx", 41232)));

        assertNull(result.failure);
        assertEquals(result.documentUri, result.describeUri);
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

    /**
     * <b>The entry cap counts what is kept, not what is declared.</b>
     *
     * A release with seventy cover scans and one tape in it is an ordinary
     * thing to find in this archive, and it used to be refused as a zip bomb:
     * the count was taken before {@code Types.openable} had a say, so
     * seventy-one entries tripped a cap meant to bound how many <em>files</em>
     * one import can put in front of somebody. What a bomb actually costs is
     * the walk, and that has a cap of its own now.
     */
    @Test
    public void anarchiveOfMostlyPicturesIsNotAbomb() {
        String[] names = new String[71];
        for (int at = 0; at < 70; at++) names[at] = stamp + "-scan-" + at + ".jpg";
        names[70] = stamp + "-HeadOverHeels.tzx";

        Imports.Result result = kept(Imports.game(
                context, new Zipped(41232, names),
                item("Arcade Game", download("tzx", 41232)), download("tzx", 41232)));

        assertNull("seventy pictures beside one tape was refused", result.failure);
        assertEquals(stamp + "-HeadOverHeels.tzx", result.displayName);
    }

    /**
     * <b>And the walk cap counts every entry, directories included.</b>
     *
     * The cap above bounds what is kept; this one bounds what is read, and
     * the case it is written for is "a bomb of a million empty names". A
     * million empty names <em>is</em> a million directory entries - so a
     * walk that skipped directories before counting them left that exact
     * case unbounded while its comment claimed otherwise. The test above
     * never comes near either cap (seventy-one entries against four
     * thousand), so nothing here reached the boundary before this.
     *
     * Four thousand and ninety-seven directories, then a perfectly good
     * tape that the walk must never get to. Both caps are private, so the
     * number is written out; if {@code MAX_SCANNED} moves, this moves with
     * it - the message is asserted so a refusal for some other reason
     * cannot pass as this one.
     */
    @Test
    public void thewalkCapCountsDirectoriesToo() {
        String[] names = new String[4098];
        for (int at = 0; at < 4097; at++) names[at] = stamp + "-empty-" + at + "/";
        names[4097] = stamp + "-HeadOverHeels.tzx";

        Imports.Result result = kept(Imports.game(
                context, new Zipped(names),
                item("Arcade Game", download("tzx", -1)), download("tzx", -1)));

        assertNotNull("an archive of four thousand empty names was walked to the end",
                      result.failure);
        assertTrue("refused for some other reason: " + result.failure.getMessage(),
                   result.failure.getMessage().contains("entries inside"));
        assertNull(result.documentUri);
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

    /**
     * A corrupt or truncated archive must not leak whatever it managed to
     * extract before it gave out.
     *
     * No stated size, deliberately: rule 2's length check only ever sees the
     * whole zip's own byte count, and this fake's truncated file is smaller
     * than nothing it claims to be - stating a size here would mean the
     * length check catches it first and this test would never reach {@code
     * unzip} at all, which is the code path this test exists to cover. The
     * failure has to come from a corrupt archive breaking apart mid-read,
     * after its first entry already landed a real file in the cache.
     */
    @Test
    public void acorruptZipLeaksNothingIntoTheCache() {
        File cache = new File(context.getCacheDir(), "imports");

        Imports.Result result = kept(Imports.game(
                context, new Truncated(stamp + "-Robocop - Side 1.tap",
                                       stamp + "-Robocop - Side 2.tap"),
                item("Arcade Game", download("tap", -1)), download("tap", -1)));

        assertNotNull("a truncated archive was accepted", result.failure);
        assertNull(result.documentUri);

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
