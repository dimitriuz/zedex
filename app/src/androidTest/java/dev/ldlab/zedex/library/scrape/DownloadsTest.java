package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.library.meta.Artwork;
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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Media onto disk, and what happens to one that arrived wrong.
 *
 * The rule worth the file: <b>a download that does not hash to what the
 * provider said is deleted, not kept</b>. {@code Artwork} decides a picture
 * exists by whether it can be read and is longer than nothing, both of which
 * half a cover passes - so a truncated download that stays on disk becomes a
 * broken image the app will show for ever, with nothing to distinguish it from
 * a real one and no way to retry. It is the same lesson as the updater's
 * checksum, and it is cheap here for the same reason.
 *
 * No socket: {@link Http} is supplied. Everything written goes under a game
 * path no collection has, and is removed afterwards.
 */
@RunWith(AndroidJUnit4.class)
public class DownloadsTest {

    private static final String GAME = "./zedex-test/Downloaded Game.tap";

    private Context context;
    private final List<File> made = new ArrayList<>();

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();

        File root = Storage.root(context);
        assumeTrue("the data folder is not usable on this device: " + root,
                   root.isDirectory() && Storage.isWritable(root));

        Artwork.forget();
    }

    @After
    public void tidyUp() {
        for (File file : made) file.delete();
        made.clear();

        for (String folder : new String[] { "covers", "screenshots", "videos", "manuals" }) {
            new File(Storage.mediaDirectory(context), folder + "/zedex-test").delete();
        }
        Artwork.forget();
    }

    /** ScreenScraper's own reading of a status, without its credentials -
     *  Downloads has to ask somebody, and this is who it would ask. */
    private static Provider classifier() {
        return new ScreenScraper(new Http() {
            @Override public Reply get(String url) { throw new UnsupportedOperationException(); }
            @Override public String save(String url, File into) {
                throw new UnsupportedOperationException();
            }
        }, "id", "password", "", "");
    }

    /** Refuses everything with one status. */
    private final class Refusing implements Http {
        private final int status;

        Refusing(int status) {
            this.status = status;
        }

        @Override
        public Reply get(String url) {
            throw new UnsupportedOperationException("not this test's business");
        }

        @Override
        public String save(String url, File into) throws IOException {
            made.add(into);
            throw new Http.Refused(status);
        }
    }

    /** Hands over fixed bytes and remembers where they were asked to go. */
    private final class Bytes implements Http {
        private final byte[] content;
        private final IOException fail;
        final List<String> asked = new ArrayList<>();

        Bytes(String content) {
            this.content = content == null ? null
                    : content.getBytes(StandardCharsets.UTF_8);
            this.fail = null;
        }

        Bytes(IOException fail) {
            this.content = null;
            this.fail = fail;
        }

        @Override
        public Reply get(String url) {
            throw new UnsupportedOperationException("not this test's business");
        }

        @Override
        public String save(String url, File into) throws IOException {
            asked.add(url);
            made.add(into);

            if (fail != null) throw fail;

            try (FileOutputStream out = new FileOutputStream(into)) {
                if (content != null) out.write(content);
            }
            return md5Of(content == null ? new byte[0] : content);
        }
    }

    private static String md5Of(byte[] bytes) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            StringBuilder hex = new StringBuilder();
            for (byte b : md5.digest(bytes)) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** Downloads.fetch, with the classifier and the exception unwrapped for
     *  the cases that are not about refusals. */
    private Downloads.Result fetch(Http http, String path, java.util.List<Medium> media) {
        try {
            return Downloads.fetch(context, http, classifier(), path, media);
        } catch (ScrapeException e) {
            throw new AssertionError("unexpected refusal: " + e.kind, e);
        }
    }

    private static Medium cover(String md5) {
        return new Medium("covers", "https://x/cover.png", "png", md5);
    }

    // --- the happy path -------------------------------------------------------------

    @Test
    public void amediumIsWrittenWhereArtworkWillFindIt() {
        String body = "a picture, near enough";
        Downloads.Result result = fetch(new Bytes(body), GAME,
                Collections.singletonList(cover(md5Of(body.getBytes(StandardCharsets.UTF_8)))));

        assertEquals(1, result.saved);
        assertEquals(0, result.failed);
        assertTrue(result.anything());

        assertNotNull("Artwork cannot find what was just written",
                      Artwork.picture(context, GAME));
    }

    /**
     * And the caches are told, or the cover stays invisible.
     *
     * A miss is remembered - see {@code OwnMediaTest} - so a scrape that wrote
     * a cover and did not forget the game would leave the library showing
     * nothing until something unrelated cleared the cache.
     */
    @Test
    public void thegameIsForgottenSoTheNewCoverIsSeen() {
        assertNull("nothing yet", Artwork.picture(context, GAME));   // caches the miss

        String body = "a picture";
        fetch(new Bytes(body), GAME,
                Collections.singletonList(cover(md5Of(body.getBytes(StandardCharsets.UTF_8)))));

        assertNotNull("the miss was never forgotten, so the cover is invisible",
                      Artwork.picture(context, GAME));
    }

    /** Several media, several files, one count. */
    @Test
    public void everyMediumAskedForIsFetched() {
        String body = "content";
        String hash = md5Of(body.getBytes(StandardCharsets.UTF_8));

        Bytes http = new Bytes(body);
        Downloads.Result result = fetch(http, GAME, Arrays.asList(
                new Medium("covers", "https://x/a.png", "png", hash),
                new Medium("screenshots", "https://x/b.png", "png", hash),
                new Medium("videos", "https://x/c.mp4", "mp4", hash)));

        assertEquals(3, result.saved);
        assertEquals(3, http.asked.size());
        assertNotNull(Artwork.video(context, GAME));
    }

    // --- what arrived wrong ------------------------------------------------------------

    /**
     * A file that does not hash to what was promised is deleted.
     *
     * The one that matters. Kept, it is a broken cover for ever: nothing
     * downstream can tell it from a real one, and {@code fileFor} would hand
     * back the same name on a retry, so it would not even be replaced.
     */
    @Test
    public void adownloadThatHashesWrongIsDeletedRatherThanKept() {
        Downloads.Result result = fetch(new Bytes("what actually arrived"),
                GAME, Collections.singletonList(cover("0000notthehashatall0000")));

        assertEquals(0, result.saved);
        assertEquals(1, result.failed);
        assertFalse(result.anything());

        assertNull("a wrong download was left where Artwork would show it",
                   Artwork.picture(context, GAME));
        assertFalse("the file is still on disk",
                    Artwork.fileFor(context, GAME, "covers", "png").exists());
    }

    /** A provider that gives no hash is trusted - not every one does, and
     *  refusing everything unhashed would fetch nothing at all from them. */
    @Test
    public void amediumWithNoStatedHashIsKept() {
        Downloads.Result result = fetch(new Bytes("no hash offered"),
                GAME, Collections.singletonList(cover(null)));

        assertEquals(1, result.saved);
        assertNotNull(Artwork.picture(context, GAME));
    }

    /** A 200 with nothing behind it leaves nothing behind either - Artwork
     *  reads a zero-length file as absent, so keeping it is clutter that never
     *  heals. */
    @Test
    public void anEmptyDownloadIsNotKept() {
        Downloads.Result result = fetch(new Bytes(""),
                GAME, Collections.singletonList(cover(null)));

        assertEquals(0, result.saved);
        assertEquals(1, result.failed);
        assertFalse(Artwork.fileFor(context, GAME, "covers", "png").exists());
    }

    /** A socket that went away mid-picture takes the part-file with it. */
    @Test
    public void afailedDownloadLeavesNoHalfFile() {
        Downloads.Result result = fetch(new Bytes(new IOException("connection reset")),
                GAME, Collections.singletonList(cover(null)));

        assertEquals(0, result.saved);
        assertEquals(1, result.failed);
        assertFalse("a half-written cover was left behind",
                    Artwork.fileFor(context, GAME, "covers", "png").exists());
    }

    /**
     * One medium failing does not take the others with it.
     *
     * A game whose cover arrived and whose video did not has largely worked,
     * and a multi-scrape that stopped at the first missing manual would get
     * through very little of a collection.
     */
    @Test
    public void onefailureDoesNotStopTheRest() {
        String body = "content";
        String right = md5Of(body.getBytes(StandardCharsets.UTF_8));

        Downloads.Result result = fetch(new Bytes(body), GAME, Arrays.asList(
                cover("wronghash"),
                new Medium("screenshots", "https://x/b.png", "png", right)));

        assertEquals(1, result.saved);
        assertEquals(1, result.failed);

        assertFalse("the cover that hashed wrong was kept",
                    Artwork.fileFor(context, GAME, "covers", "png").exists());
        assertTrue("the screenshot that arrived intact was lost",
                   Artwork.fileFor(context, GAME, "screenshots", "png").isFile());
    }

    /** Nothing asked for is nothing done, and not a failure. */
    @Test
    public void nomediaIsNoWork() {
        Downloads.Result result =
                fetch(new Bytes("unused"), GAME, Collections.emptyList());

        assertEquals(0, result.saved);
        assertEquals(0, result.failed);
        assertFalse(result.anything());
    }

    /** The extension the provider stated is the one on disk, so a jpg cover
     *  is found as a jpg rather than written as a png nothing decodes. */
    @Test
    public void theStatedExtensionIsWhatLandsOnDisk() {
        String body = "a jpeg, allegedly";
        fetch(new Bytes(body), GAME, Collections.singletonList(
                new Medium("covers", "https://x/cover.jpg", "jpg",
                           md5Of(body.getBytes(StandardCharsets.UTF_8)))));

        assertTrue(Artwork.fileFor(context, GAME, "covers", "jpg").isFile());
        assertFalse(Artwork.fileFor(context, GAME, "covers", "png").exists());
    }

    // --- a refusal from the media endpoint --------------------------------------------

    /**
     * A spent quota during a download stops everything, rather than counting
     * as one missing cover.
     *
     * The hole this closes. ScreenScraper's media are fetched from the same
     * API as its metadata - a cover is a {@code mediaJeu.php} call with the
     * credentials in the query - so the quota can run out <em>between</em> the
     * search and the pictures. Treated as an ordinary failure, a multi-scrape
     * would carry on through eight hundred more games downloading nothing and
     * reporting each one as a missing picture.
     */
    @Test
    public void aspentQuotaWhileDownloadingStopsRatherThanCountingAsAMissingPicture() {
        try {
            Downloads.fetch(context, new Refusing(429), classifier(), GAME,
                            Collections.singletonList(cover(null)));
            fail("a spent quota was swallowed as a missing picture");
        } catch (ScrapeException e) {
            assertEquals(ScrapeException.Kind.QUOTA_EXCEEDED, e.kind);
        }
    }

    /** Credentials refused mid-scrape is the same: asking again will be
     *  refused identically. */
    @Test
    public void refusedCredentialsWhileDownloadingStopToo() {
        try {
            Downloads.fetch(context, new Refusing(401), classifier(), GAME,
                            Collections.singletonList(cover(null)));
            fail("refused credentials were swallowed");
        } catch (ScrapeException e) {
            assertEquals(ScrapeException.Kind.BAD_CREDENTIALS, e.kind);
        }
    }

    /**
     * A picture the service simply does not have is <em>not</em> a reason to
     * stop.
     *
     * The other half, and the one that makes the rule worth having: a 404 on
     * one manual must not abandon a collection.
     */
    @Test
    public void amissingPictureIsNotAReasonToStop() throws Exception {
        Downloads.Result result = Downloads.fetch(context, new Refusing(404), classifier(),
                GAME, Collections.singletonList(cover(null)));

        assertEquals(0, result.saved);
        assertEquals(1, result.failed);
    }

    /** Nor is a server hiccup - the next game is still worth trying. */
    @Test
    public void aserverHiccupIsNotAReasonToStop() throws Exception {
        Downloads.Result result = Downloads.fetch(context, new Refusing(500), classifier(),
                GAME, Collections.singletonList(cover(null)));

        assertEquals(1, result.failed);
    }

    /** And a refusal leaves nothing half-written behind it. */
    @Test
    public void arefusalLeavesNoFile() {
        try {
            Downloads.fetch(context, new Refusing(429), classifier(), GAME,
                            Collections.singletonList(cover(null)));
        } catch (ScrapeException expected) {
            // the point is what is on disk
        }

        assertFalse("a refused download left a file behind",
                    Artwork.fileFor(context, GAME, "covers", "png").exists());
    }

    /**
     * What arrived before the refusal is still made visible.
     *
     * A scrape stopped halfway still fetched the covers it got to, and leaving
     * them behind a cached miss would throw away work already paid for out of
     * the day's allowance.
     */
    @Test
    public void whatArrivedBeforeTheRefusalIsNotWasted() {
        assertNull("nothing yet", Artwork.picture(context, GAME));   // caches the miss

        String body = "a picture";
        String hash = md5Of(body.getBytes(StandardCharsets.UTF_8));

        // First medium arrives, second is refused with a quota.
        Http mixed = new Http() {
            private int calls;

            @Override public Reply get(String url) { throw new UnsupportedOperationException(); }

            @Override
            public String save(String url, File into) throws IOException {
                made.add(into);
                if (calls++ == 0) {
                    try (FileOutputStream out = new FileOutputStream(into)) {
                        out.write(body.getBytes(StandardCharsets.UTF_8));
                    }
                    return hash;
                }
                throw new Http.Refused(429);
            }
        };

        try {
            Downloads.fetch(context, mixed, classifier(), GAME, Arrays.asList(
                    new Medium("covers", "https://x/a.png", "png", hash),
                    new Medium("screenshots", "https://x/b.png", "png", hash)));
            fail("the quota refusal should have come out");
        } catch (ScrapeException e) {
            assertEquals(ScrapeException.Kind.QUOTA_EXCEEDED, e.kind);
        }

        assertNotNull("the cover that did arrive was left behind a cached miss",
                      Artwork.picture(context, GAME));
    }
}
