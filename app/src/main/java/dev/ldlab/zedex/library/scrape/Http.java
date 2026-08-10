package dev.ldlab.zedex.library.scrape;

import android.content.Context;
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
     * A refusal from the media endpoint, with the status that explains it.
     *
     * A plain {@code IOException} would be enough to know the picture did not
     * arrive, and not enough to know why - and the why is the difference
     * between one missing cover and a spent quota that makes the next eight
     * hundred pointless. See {@code Downloads}.
     *
     * <b>Never carries the URL.</b> ScreenScraper's media URLs are ordinary
     * API calls with {@code devpassword} and {@code sspassword} in the query,
     * so a message built from one puts credentials into logcat and into every
     * bug report taken afterwards.
     */
    final class Refused extends IOException {
        public final int status;

        public Refused(int status) {
            super("the server answered " + status);
            this.status = status;
        }
    }

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
                    // Deliberately without the URL - it carries the
                    // credentials; see Refused.
                    throw new Refused(status);
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

        /**
         * What this app calls itself to a server: {@code Zedex/1.4.2
         * (+https://github.com/dimitriuz/zedex)}.
         *
         * Without it every request goes out as {@code Dalvik/2.1.0 (Linux; U;
         * Android …)}, which says a JVM asked and nothing about who. That is
         * poor manners to a service run by volunteers, and practically it is
         * the difference between an operator who can see what a client is
         * doing - and ask, or allow-list it - and one whose only option when
         * traffic looks odd is to block a range. ZXInfo's own specification
         * asks for it and says access without one risks being treated as a
         * crawler, which is exactly what happened.
         *
         * The project URL rather than an email: it is already public, it is
         * where anybody would go to complain, and it does not put an address
         * into every log file on the way.
         *
         * <b>The version is the installed one, not a build constant.</b> Read
         * from the package manager, so it is what is actually running and
         * needs neither the {@code buildConfig} feature this project keeps off
         * nor a resource that could disagree with the APK it is in. A version
         * is worth the {@code Context} it costs: it is how an operator tells
         * "the old build with the bug" from "the one that fixed it", and
         * without it every report is about "Zedex".
         */
        private static final String IDENTITY = "(+https://github.com/dimitriuz/zedex)";

        private final String userAgent;

        /**
         * The only constructor, so nothing can be built that goes out
         * unversioned - the header is not worth having if half the traffic
         * lacks it, and a caller with no {@code Context} at all does not
         * exist here.
         */
        public Real(Context context) {
            this.userAgent = "Zedex/" + versionOf(context) + " " + IDENTITY;
        }

        /** Whatever is installed, or "?" - a missing version must not be the
         *  reason a scrape cannot start. */
        private static String versionOf(Context context) {
            try {
                String name = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0).versionName;

                return name == null || name.isEmpty() ? "?" : name;
            } catch (Exception e) {
                Log.w(TAG, "cannot read this app's own version", e);
                return "?";
            }
        }

        private HttpURLConnection open(String url) throws IOException {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(CONNECT_MS);
            connection.setReadTimeout(READ_MS);
            connection.setRequestProperty("User-Agent", userAgent);
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
