package dev.ldlab.zedex.library.meta;

import dev.ldlab.zedex.frontend.EsDe;

import static org.junit.Assert.assertEquals;

import android.net.Uri;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reading ES-DE's media folder out of a settings file that is not XML.
 *
 * {@code ES-DE/settings/es_settings.xml} is a declaration followed by about a
 * hundred and seventy sibling elements with no wrapping root, so a plain parse
 * throws - and what that cost, before anyone knew, was every scraped picture
 * silently disappearing. {@code EsdeLink.wrapped} puts a synthetic root round
 * it; this is what says the wrapper works, and keeps working.
 *
 * The other half is as important and easier to break: <b>every failure answers
 * with the default</b>, never with "unknown". That is deliberate and the
 * method's own comment argues it at length - a wrong media root produces
 * misses, never somebody else's cover, so guessing ES-DE's default is strictly
 * better than giving up. Giving up is what turned a malformed file into no
 * pictures at all. Four of the cases below are that rule from four directions.
 *
 * No device: {@link EsDe.Reach} is already the seam this reads ES-DE's folder
 * through, so a test supplies one over a string.
 */
public class EsdeSettingsTest {

    /** ES-DE's own default, which is the empty string here - see
     *  {@code mediaDirectory}, where "" means "wherever ES-DE puts it". */
    private static final String DEFAULT = "";

    /** {@link EsDe.Reach} over one string, or over nothing at all. */
    private static final class Given implements EsDe.Reach {

        private final String contents;
        private final boolean throwOnOpen;

        private Given(String contents, boolean throwOnOpen) {
            this.contents = contents;
            this.throwOnOpen = throwOnOpen;
        }

        static Given file(String contents) { return new Given(contents, false); }
        static Given nothing() { return new Given(null, false); }
        static Given unreadable() { return new Given(null, true); }

        @Override
        public InputStream open(String relativePath) throws IOException {
            if (throwOnOpen) throw new IOException("the grant lapsed");
            return contents == null ? null
                    : new ByteArrayInputStream(contents.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public Uri locate(String relativePath) {
            return null;
        }
    }

    private static String mediaFolderFrom(EsDe.Reach reach) {
        return EsdeLink.mediaDirectory(reach);
    }

    // --- the file as ES-DE actually writes it ------------------------------------

    /**
     * A declaration and a flat run of siblings, no root - which is not
     * well-formed XML and is exactly what is on disk.
     */
    @Test
    public void theMediaFolderIsReadOutOfARootlessFile() {
        String esDeWritesThis =
                "<?xml version=\"1.0\"?>\n"
                + "<string name=\"ApplicationVersion\" value=\"3.1.0\" />\n"
                + "<bool name=\"VSync\" value=\"true\" />\n"
                + "<string name=\"MediaDirectory\" value=\"/storage/1234-5678/ES-DE/media\" />\n"
                + "<string name=\"ROMDirectory\" value=\"/storage/emulated/0/ROMs\" />\n";

        assertEquals("/storage/1234-5678/ES-DE/media",
                     mediaFolderFrom(Given.file(esDeWritesThis)));
    }

    /** No declaration is fine too - the wrapper only strips one if it is
     *  there, and must not eat the first element when it is not. */
    @Test
    public void aFileWithNoDeclarationStillParses() {
        assertEquals("/media/here", mediaFolderFrom(Given.file(
                "<string name=\"MediaDirectory\" value=\"/media/here\" />\n")));
    }

    /**
     * Entities and quoting are XML's rules, and a real parse is what gets
     * them right - the reason this is not a scan for one attribute.
     */
    @Test
    public void theValueIsUnescapedTheWayXmlSays() {
        assertEquals("/media/Tom & Jerry", mediaFolderFrom(Given.file(
                "<?xml version=\"1.0\"?>\n"
                + "<string name=\"MediaDirectory\" value=\"/media/Tom &amp; Jerry\" />\n")));
    }

    /** The key can be anywhere among the hundred and seventy, including last. */
    @Test
    public void theKeyIsFoundWhereverItSits() {
        StringBuilder file = new StringBuilder("<?xml version=\"1.0\"?>\n");
        for (int at = 0; at < 170; at++) {
            file.append("<string name=\"Filler").append(at).append("\" value=\"x\" />\n");
        }
        file.append("<string name=\"MediaDirectory\" value=\"/last/one\" />\n");

        assertEquals("/last/one", mediaFolderFrom(Given.file(file.toString())));
    }

    // --- every failure is the default, never "unknown" -----------------------------

    /** No settings file yet - ES-DE has been installed and not configured. */
    @Test
    public void noSettingsFileIsTheDefault() {
        assertEquals(DEFAULT, mediaFolderFrom(Given.nothing()));
    }

    /** The grant lapsed, or the folder went. Still the default. */
    @Test
    public void afileThatCannotBeOpenedIsTheDefault() {
        assertEquals(DEFAULT, mediaFolderFrom(Given.unreadable()));
    }

    /**
     * A settings file that is broken beyond the missing root.
     *
     * The one that matters most: the wrapper makes an ordinary file parseable,
     * and this says an extraordinary one still does not take the pictures with
     * it. Answering "unknown" here is what once meant nothing was scraped at
     * all.
     */
    @Test
    public void afileThatWillNotParseAtAllIsStillTheDefault() {
        assertEquals(DEFAULT, mediaFolderFrom(Given.file(
                "<?xml version=\"1.0\"?>\n<string name=\"MediaDirectory\" value=\"unclosed")));
    }

    /** The key simply is not set, which is ES-DE's default as well - a person
     *  who never moved their media folder has no line for it. */
    @Test
    public void anAbsentKeyIsTheDefault() {
        assertEquals(DEFAULT, mediaFolderFrom(Given.file(
                "<?xml version=\"1.0\"?>\n<bool name=\"VSync\" value=\"true\" />\n")));
    }

    /** An empty file is the default too, and must not throw on the way there. */
    @Test
    public void anEmptyFileIsTheDefault() {
        assertEquals(DEFAULT, mediaFolderFrom(Given.file("")));
    }

    /** A key that merely looks like it - the match is exact, not a contains. */
    @Test
    public void aSimilarlyNamedKeyIsNotTheOne() {
        assertEquals(DEFAULT, mediaFolderFrom(Given.file(
                "<?xml version=\"1.0\"?>\n"
                + "<string name=\"MediaDirectoryOld\" value=\"/not/this/one\" />\n"
                + "<string name=\"UserMediaDirectory\" value=\"/nor/this\" />\n")));
    }
}
