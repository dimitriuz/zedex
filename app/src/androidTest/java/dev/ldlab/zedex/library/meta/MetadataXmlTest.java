package dev.ldlab.zedex.library.meta;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * That what the store writes, the store can read.
 *
 * It could not, and one byte was enough. A scraped description on a real phone
 * ended in U+0001 — "Selection between right-hand and left-hand drive." and
 * then a control character, from whatever filled ES-DE's own gamelist. The DOM
 * accepted it, the Transformer wrote it out as the reference {@code &#1;}, and
 * that is not well-formed XML 1.0: the next read threw, the catch turned it
 * into an empty store, and 803 games' worth of metadata disappeared behind an
 * app reporting that the library had never been linked. Artwork carried on
 * working, because it comes from ES-DE's media folder rather than from here,
 * which is what made the link look as though it had succeeded.
 *
 * The round trip is the thing worth testing rather than the helper on its own:
 * a test of {@link Metadata#xmlSafe} by itself would pass against whatever
 * definition of "safe" somebody happened to write down, which is how this got
 * out in the first place.
 */
@RunWith(AndroidJUnit4.class)
public class MetadataXmlTest {

    /** The exact text from the phone, with the byte that broke it. */
    private static final String FROM_THE_PHONE =
            "Selection between right-hand and left-hand drive." + "\u0001";

    private static final String CLEANED =
            "Selection between right-hand and left-hand drive.";

    @Test
    public void stripsTheCharacterThatBrokeARealStore() {
        String safe = Metadata.xmlSafe(FROM_THE_PHONE);

        assertEquals(CLEANED, safe);
        assertFalse("a control character survived", safe.contains("\u0001"));
    }

    /** Everything a description legitimately holds has to come through. */
    @Test
    public void keepsEverythingItShould() {
        String text = "Dizzy & Pogie\nrun <fast>\ttoday — café, "
                    + "Пример, 日本語";

        assertEquals("legitimate text was altered", text, Metadata.xmlSafe(text));
    }

    /**
     * An astral character is two Java chars in the surrogate range, and
     * dropping either would corrupt exactly what this is meant to protect.
     */
    @Test
    public void keepsSurrogatePairsWhole() {
        String emoji = "🎮";   // U+1F3AE, a game controller

        assertEquals(emoji, Metadata.xmlSafe(emoji));
        assertEquals(2, Metadata.xmlSafe(emoji).length());
    }

    /** The whole point: text that has been through this survives a real write
     *  and a real read, which is the step that used to throw. */
    @Test
    public void survivesAnXmlRoundTrip() throws Exception {
        String safe = Metadata.xmlSafe(FROM_THE_PHONE);

        org.w3c.dom.Document out = dev.ldlab.zedex.storage.Xml.builder().newDocument();
        org.w3c.dom.Element root = out.createElement("gameList");
        out.appendChild(root);

        org.w3c.dom.Element desc = out.createElement("desc");
        desc.setTextContent(safe);
        root.appendChild(desc);

        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        javax.xml.transform.TransformerFactory.newInstance().newTransformer()
                .transform(new javax.xml.transform.dom.DOMSource(out),
                           new javax.xml.transform.stream.StreamResult(bytes));

        String written = bytes.toString("UTF-8");
        assertFalse("an invalid character reference was written: " + written,
                    written.contains("&#1;"));

        org.w3c.dom.Document in = dev.ldlab.zedex.storage.Xml.builder()
                .parse(new java.io.ByteArrayInputStream(bytes.toByteArray()));

        assertTrue("the round trip lost the text",
                   in.getDocumentElement().getTextContent()
                     .contains("right-hand and left-hand drive"));
    }

    /**
     * What the platform does with the raw character, recorded rather than
     * required.
     *
     * This is where the emulator and the phone disagree, and the disagreement
     * is the whole argument for sanitising rather than trusting the stack. On
     * the emulator's AOSP transformer U+0001 goes out and comes back without
     * complaint. On the phone that reported the bug it was written as the
     * reference {@code &#1;}, which is not well-formed XML 1.0 and which no
     * parser will read - so the file that machine wrote was one it could never
     * read again.
     *
     * Asserting either behaviour would make this test fail on the other kind of
     * device. What it asserts instead is that we do not depend on the answer:
     * whatever the transformer would have done, {@link Metadata#xmlSafe} has
     * already removed the character before it can do it.
     */
    @Test
    public void weDoNotDependOnWhatThePlatformDoesWithIt() throws Exception {
        assertFalse("xmlSafe let the character through, so the file this device"
                    + " writes depends on its own transformer",
                    Metadata.xmlSafe(FROM_THE_PHONE).contains("\u0001"));

        String written = serialise(Metadata.xmlSafe(FROM_THE_PHONE));

        assertFalse("a reference below #x20 reached the file: " + written,
                    written.matches("(?s).*&#x?0*(?:[0-9]|1[0-9]|2[0-9]|3[01]);.*"));
    }

    private String serialise(String text) throws Exception {
        org.w3c.dom.Document out = dev.ldlab.zedex.storage.Xml.builder().newDocument();
        org.w3c.dom.Element root = out.createElement("gameList");
        out.appendChild(root);

        org.w3c.dom.Element desc = out.createElement("desc");
        desc.setTextContent(text);
        root.appendChild(desc);

        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        javax.xml.transform.TransformerFactory.newInstance().newTransformer()
                .transform(new javax.xml.transform.dom.DOMSource(out),
                           new javax.xml.transform.stream.StreamResult(bytes));

        return bytes.toString("UTF-8");
    }
}
