package dev.ldlab.zedex.update;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * That a download which arrived wrong is refused.
 *
 * The rest of 11.4's number four. {@code versionFrom} has been covered since
 * the JVM tier existed; this is the other end - the {@code .sha256} the
 * release workflow publishes beside the APK, and whether anything actually
 * checks it.
 *
 * It is the one place in the app where getting it wrong hands somebody an
 * installer prompt for a file that is not what it claims to be. Android's own
 * signature check is behind it and would refuse a tampered APK - but a
 * download that arrived <em>short</em>, which is the ordinary failure on a
 * phone that lost signal, is refused by the installer instead, after the user
 * has already been asked to allow installs from this app. Catching it here is
 * the difference between "the download failed" and a permission prompt
 * followed by a corrupt package.
 *
 * The seam this needs is new and small: the hashing half of {@code fetch} is
 * now {@code save}, which takes an {@code InputStream}. The other half is a
 * socket and stays where it is. Cut deliberately narrowly - nothing about the
 * install path itself moved.
 */
@RunWith(AndroidJUnit4.class)
public class UpdaterDownloadTest {

    private Context context;
    private File apk;

    /** Every percentage the bar was told about, in order. */
    private final List<Integer> reported = new ArrayList<>();
    private final Updater.Progress progress = reported::add;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        apk = new File(context.getCacheDir(), "update-test.apk");
        apk.delete();
        reported.clear();
    }

    @After
    public void tidyUp() {
        apk.delete();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static InputStream streamOf(byte[] content) {
        return new ByteArrayInputStream(content);
    }

    /** What the release workflow would have published for this content. */
    private static String sha256Of(byte[] content) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        StringBuilder hex = new StringBuilder();
        for (byte b : sha.digest(content)) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    // --- the hash ---------------------------------------------------------------------

    @Test
    public void adownloadThatMatchesItsPublishedHashIsAccepted() throws Exception {
        byte[] content = bytes("pretend this is fourteen megabytes of APK");

        String failure = Updater.save(streamOf(content), content.length, apk,
                                      sha256Of(content), progress);

        assertNull("a correct download was refused: " + failure, failure);
        assertArrayEquals("the file on disk is not what came down the stream",
                          content, Files.readAllBytes(apk.toPath()));
    }

    /**
     * A download that arrived short is refused.
     *
     * The ordinary failure - a phone that lost signal mid-download - and the
     * one Android's signature check would only catch after the user had been
     * asked to allow an install.
     */
    @Test
    public void adownloadThatArrivedShortIsRefused() throws Exception {
        byte[] whole = bytes("the whole APK, every last byte of it");
        byte[] truncated = bytes("the whole APK, every last");

        String failure = Updater.save(streamOf(truncated), whole.length, apk,
                                      sha256Of(whole), progress);

        assertEquals("checksum", failure);
    }

    /** And one that arrived scrambled - the same rejection, since the hash
     *  cannot tell the two apart and does not need to. */
    @Test
    public void adownloadThatArrivedScrambledIsRefused() throws Exception {
        byte[] whole = bytes("the whole APK, every last byte of it");
        byte[] scrambled = bytes("the whole APK, every lost byte of it");

        assertEquals("checksum",
                     Updater.save(streamOf(scrambled), whole.length, apk,
                                  sha256Of(whole), progress));
    }

    /** The comparison is not case sensitive: the published file is lower-case
     *  hex, but a hash typed or generated elsewhere may not be, and refusing a
     *  good download over letter case would be an update nobody can install. */
    @Test
    public void thehashIsComparedWithoutRegardToCase() throws Exception {
        byte[] content = bytes("an APK");

        assertNull(Updater.save(streamOf(content), content.length, apk,
                                sha256Of(content).toUpperCase(), progress));
    }

    /**
     * A release with no published hash installs anyway.
     *
     * Deliberate, and the comment says why: Android's own signature check is
     * behind it. Worth pinning because the safe-looking change - refuse
     * anything unhashed - would break every release published before the
     * workflow started publishing one.
     */
    @Test
    public void areleaseWithNoPublishedHashIsStillAccepted() throws Exception {
        byte[] content = bytes("an older release, before the .sha256 existed");

        assertNull(Updater.save(streamOf(content), content.length, apk, null, progress));
        assertArrayEquals(content, Files.readAllBytes(apk.toPath()));
    }

    // --- the progress bar --------------------------------------------------------------

    /** It is told, it only goes forwards, and it ends at a hundred. */
    @Test
    public void theprogressBarIsToldAndReachesTheEnd() throws Exception {
        byte[] content = new byte[512 * 1024];   // several buffers' worth

        Updater.save(streamOf(content), content.length, apk, null, progress);

        assertTrue("the progress bar was never told anything", reported.size() > 1);
        assertEquals("the progress bar did not reach the end",
                     Integer.valueOf(100), reported.get(reported.size() - 1));

        int last = -1;
        for (int percent : reported) {
            assertTrue("progress went backwards: " + reported, percent >= last);
            assertTrue("progress went past the end: " + percent, percent <= 100);
            last = percent;
        }
    }

    /**
     * A server that did not say how big the file is leaves the bar alone.
     *
     * {@code getContentLengthLong} answers -1 for a chunked response, and a
     * percentage of an unknown total is either a division by zero or a bar
     * that jumps about. Nothing is reported instead.
     */
    @Test
    public void anUnknownLengthReportsNoProgressRatherThanNonsense() throws Exception {
        byte[] content = new byte[128 * 1024];

        Updater.save(streamOf(content), -1, apk, null, progress);

        assertEquals("progress was reported for a download of unknown length",
                     0, reported.size());
    }

    // --- what is left on disk ---------------------------------------------------------------

    /**
     * A refused download is still on disk, and that is on purpose here: {@code
     * save} answers "checksum" and it is the caller's business what to do
     * next. Recorded rather than asserted as desirable - if this ever becomes
     * "delete it", this is the test that says the behaviour changed on
     * purpose.
     */
    @Test
    public void arefusedDownloadIsLeftForTheCallerToDealWith() throws Exception {
        byte[] whole = bytes("the whole thing");

        Updater.save(streamOf(bytes("part")), whole.length, apk, sha256Of(whole), progress);

        assertTrue("the refused download vanished; the caller cannot clean up what it "
                   + "cannot see", apk.isFile());
    }

    /** An empty download is refused against any real hash rather than
     *  producing a zero-byte APK that the installer would reject later. */
    @Test
    public void anEmptyDownloadIsRefused() throws Exception {
        byte[] whole = bytes("something");

        assertEquals("checksum",
                     Updater.save(streamOf(new byte[0]), whole.length, apk,
                                  sha256Of(whole), progress));
        assertEquals(0, apk.length());
    }

    /** The stream is closed even though the caller passed it in - it is a
     *  socket in the real one, and leaking it holds the connection. */
    @Test
    public void thestreamIsClosed() throws Exception {
        byte[] content = bytes("an APK");
        boolean[] closed = { false };

        InputStream watched = new ByteArrayInputStream(content) {
            @Override
            public void close() throws IOException {
                closed[0] = true;
                super.close();
            }
        };

        Updater.save(watched, content.length, apk, null, progress);

        assertTrue("the stream was left open", closed[0]);
    }

    /** And the version parser is still what it was - the half of the updater
     *  that was already covered, asserted here so this file names the whole
     *  of what the updater is trusted to get right. */
    @Test
    public void theversionStillComesOutOfARedirect() {
        assertNotNull(Updater.versionFrom(
                "https://github.com/dimitriuz/zedex/releases/tag/v1.4.0"));
    }
}
