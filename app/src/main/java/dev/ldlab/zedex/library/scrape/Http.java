package dev.ldlab.zedex.library.scrape;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The one thing in scraping that touches a socket.
 *
 * A seam, and a deliberately small one: every test in this package supplies
 * its own, so the suite never reaches the network and a provider's parsing,
 * error handling and quota arithmetic are all exercised against bodies written
 * by hand. The alternative - a scraper that can only be tested by scraping -
 * means the failure paths are the ones nobody ever runs, and the failure paths
 * are most of what a scraper is.
 *
 * Two operations, because there are two: fetch a short document and read it,
 * and fetch a large one straight to disk. A cover is a megabyte and a video is
 * twenty; neither belongs in memory on the way past.
 */
public interface Http {

    /** Everything short: an API reply, read whole. */
    Reply get(String url) throws IOException;

    /**
     * Everything long: a picture, a video, a manual, written straight to
     * {@code into} and hashed on the way.
     *
     * @return the MD5 of what arrived, so a caller can check it against what
     *         the provider said it would be - see {@code Media#save}, which
     *         does the same for a downloaded update and for the same reason:
     *         a file that arrived short must not become a broken cover with no
     *         way to tell it from a real one.
     */
    String save(String url, File into) throws IOException;

    /**
     * A reply: what the server said, and what it said it with.
     *
     * The status is kept apart from the body because ScreenScraper answers
     * most of its refusals with a real HTTP code and a one-line body - 429 for
     * a spent quota, 430 for too many threads, 426 for credentials it does not
     * like - and telling those apart is the whole of what a multi-scrape needs
     * to decide between pausing and stopping.
     */
    final class Reply {
        public final int status;
        public final String body;

        public Reply(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }

        public boolean ok() {
            return status == HttpURLConnection.HTTP_OK;
        }
    }

    /** What the app actually uses; the tests use their own. */
    final class Real implements Http {

        private static final String TAG = "Zedex";

        /** Long enough for a busy French server on a phone's connection,
         *  short enough that a stalled multi-scrape notices. */
        private static final int CONNECT_MS = 15_000;
        private static final int READ_MS = 30_000;

        /** A reply is a few kilobytes of JSON; anything approaching this is a
         *  server having a bad day, and reading it whole into memory is how a
         *  scrape becomes an out-of-memory kill. */
        private static final int MOST_BODY = 2 * 1024 * 1024;

        @Override
        public Reply get(String url) throws IOException {
            HttpURLConnection connection = open(url);

            try {
                int status = connection.getResponseCode();

                // The refusals carry their reason in the body, so the error
                // stream is read exactly like the ordinary one.
                InputStream in = status == HttpURLConnection.HTTP_OK
                        ? connection.getInputStream() : connection.getErrorStream();

                return new Reply(status, in == null ? "" : readAll(in));
            } finally {
                connection.disconnect();
            }
        }

        @Override
        public String save(String url, File into) throws IOException {
            HttpURLConnection connection = open(url);

            try {
                int status = connection.getResponseCode();
                if (status != HttpURLConnection.HTTP_OK) {
                    throw new IOException("HTTP " + status + " for " + url);
                }

                MessageDigest md5;
                try {
                    md5 = MessageDigest.getInstance("MD5");
                } catch (Exception e) {
                    throw new IOException("no MD5 on this device", e);
                }

                try (InputStream in = connection.getInputStream();
                     OutputStream out = new FileOutputStream(into)) {

                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        md5.update(buffer, 0, read);
                    }
                }

                return hex(md5.digest());
            } finally {
                connection.disconnect();
            }
        }

        private static HttpURLConnection open(String url) throws IOException {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(CONNECT_MS);
            connection.setReadTimeout(READ_MS);
            return connection;
        }

        private static String readAll(InputStream in) throws IOException {
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;

            while ((read = in.read(buffer)) != -1) {
                all.write(buffer, 0, read);

                if (all.size() > MOST_BODY) {
                    Log.w(TAG, "a reply ran past " + MOST_BODY + " bytes; giving up on it");
                    throw new IOException("reply too large");
                }
            }
            return all.toString(StandardCharsets.UTF_8.name());
        }

        static String hex(byte[] bytes) {
            StringBuilder text = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) text.append(String.format("%02x", b));
            return text.toString();
        }
    }
}
