package dev.ldlab.zedex.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The contract between the release workflow and the updater.
 *
 * {@code latest.json} is written by a shell heredoc in
 * {@code .github/workflows/release.yml} and read by {@link Updater#parse}, which
 * is two languages and two files with nothing but agreement between them. A
 * release is published long before anybody would find out the parser disagreed,
 * so the shape is pinned here instead.
 *
 * The string below is what that step actually produced when it was run - not a
 * hand-written approximation of it.
 *
 * On the device rather than in a unit test because {@code org.json} is a stub
 * off-device: every method throws, and the test would pass for the wrong reason.
 */
@RunWith(AndroidJUnit4.class)
public class UpdaterParseTest {

    /** Verbatim output of the "Write what the app will read" step. */
    private static final String REAL = "{\n"
            + "  \"version\": \"1.0.5\",\n"
            + "  \"apk\": \"https://github.com/dimitriuz/zedex/releases/download/v1.0.5/Zedex-1.0.5.apk\",\n"
            + "  \"sha256\": \"b7a46f34af3684d4070fe8b45bcfe1202306f98fff03dd2ad10a2676d5c4a4ab\"\n"
            + "}\n";

    @Test
    public void readsWhatTheWorkflowWrites() {
        Updater.Release release = Updater.parse(REAL);

        assertNotNull("the workflow's own output did not parse", release);
        assertEquals("1.0.5", release.name);
        assertEquals("https://github.com/dimitriuz/zedex/releases/download/v1.0.5/Zedex-1.0.5.apk",
                     release.apk);
        assertEquals("b7a46f34af3684d4070fe8b45bcfe1202306f98fff03dd2ad10a2676d5c4a4ab",
                     release.sha256);
    }

    /** A tag may be written either way round; the app compares numbers. */
    @Test
    public void takesTheVWithOrWithoutIt() {
        assertEquals("1.2.3", Updater.parse(
                "{\"version\":\"v1.2.3\",\"apk\":\"http://x/a.apk\"}").name);
        assertEquals("1.2.3", Updater.parse(
                "{\"version\":\"1.2.3\",\"apk\":\"http://x/a.apk\"}").name);
    }

    /**
     * Half a release is no release. Anything missing has to come back null, or
     * the app would offer an update it cannot fetch.
     */
    @Test
    public void refusesWhatItCannotUse() {
        assertNull("no version", Updater.parse("{\"apk\":\"http://x/a.apk\"}"));
        assertNull("no apk", Updater.parse("{\"version\":\"1.0.5\"}"));
        assertNull("not json at all", Updater.parse("<html>404</html>"));
        assertNull("empty", Updater.parse(""));
    }

    /**
     * A release with no hash is still installable - Android checks the signature
     * - so this must parse rather than be refused.
     */
    @Test
    public void allowsAReleaseWithNoHash() {
        Updater.Release release = Updater.parse(
                "{\"version\":\"1.0.5\",\"apk\":\"http://x/a.apk\"}");

        assertNotNull(release);
        assertNull("no hash to check against", release.sha256);
    }
}
