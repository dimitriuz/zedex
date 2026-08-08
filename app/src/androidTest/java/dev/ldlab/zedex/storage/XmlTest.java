package dev.ldlab.zedex.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.w3c.dom.Document;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import javax.xml.parsers.DocumentBuilder;

/**
 * That {@link Xml}'s parser reads our files and refuses a hostile one.
 *
 * Every XML file this app parses is one anybody can write, and the release and
 * sideload builds hold All files access - so a document that declares an
 * external entity is another app borrowing this one's permission to read a
 * file it could not open itself. {@code EsDe} makes that reachable: it parses
 * ES-DE's own {@code es_systems.xml} and writes the document back out again,
 * so a resolved entity lands in a file the planting app can read.
 *
 * On a device rather than the JVM, because the parser is not the same one, and
 * the difference turned out to matter twice over. Android's is
 * {@code org.apache.harmony.xml.parsers}, and it:
 *
 *   * throws ParserConfigurationException for {@code disallow-doctype-decl}
 *     and all three of its companions, so the textbook fix does nothing here;
 *   * throws UnsupportedOperationException — not the checked exception — from
 *     {@code setXIncludeAware} and {@code setExpandEntityReferences}.
 *
 * The second of those escaped {@link Xml}'s first version and broke every XML
 * read in the app. It was caught here, on the first run, which is the argument
 * for this file existing.
 *
 * So the checks below are all on observed behaviour and never on whether a
 * setter threw. A guard that is quietly refused looks exactly like a guard that
 * works, right up until someone plants a file.
 */
@RunWith(AndroidJUnit4.class)
public class XmlTest {

    /**
     * The builder is made outside every try below, deliberately.
     *
     * The first version of this test built it inside, and when
     * setXIncludeAware turned out to throw UnsupportedOperationException on
     * Android the three hostile-document tests all *passed* - they caught the
     * builder blowing up and read it as the document being refused. A parser
     * that cannot be constructed is a broken app, not a safe one, so a failure
     * here has to surface as a failure.
     */
    private DocumentBuilder builder() throws Exception {
        DocumentBuilder builder = Xml.builder();
        assertNotNull("Xml.builder() returned nothing", builder);
        return builder;
    }

    private Document parse(DocumentBuilder builder, String xml) throws Exception {
        InputStream in = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        return builder.parse(in);
    }

    /** The ordinary case: our own files still read. */
    @Test
    public void parsesAnOrdinaryDocument() throws Exception {
        Document document = parse(builder(),
                "<?xml version='1.0' encoding='UTF-8'?>"
                + "<systemList><system><name>zxspectrum</name></system></systemList>");

        assertEquals("systemList", document.getDocumentElement().getNodeName());
        assertNotNull(document.getElementsByTagName("name").item(0));
        assertEquals("zxspectrum",
                document.getElementsByTagName("name").item(0).getTextContent());
    }

    /**
     * A doctype is accepted, and that is recorded here rather than asserted
     * against.
     *
     * Refusing it is the textbook fix and it is not available on Android:
     * {@code disallow-doctype-decl} throws ParserConfigurationException, as do
     * all three of its companions. This test exists to pin that, so that the
     * two tests below are understood to be the real guarantee rather than
     * belt-and-braces behind a guard that is not there.
     *
     * If a future Android does refuse it, this starts failing and the right
     * response is to delete it — not to weaken {@link Xml}.
     */
    @Test
    public void acceptsADoctype_becauseAndroidCannotRefuseOne() throws Exception {
        Document document = parse(builder(),
                "<?xml version='1.0'?>"
                + "<!DOCTYPE systemList [<!ELEMENT systemList ANY>]>"
                + "<systemList/>");

        assertEquals("a doctype is now refused; see this test's own comment",
                "systemList", document.getDocumentElement().getNodeName());
    }

    /**
     * The attack itself, end to end: an external entity pointed at a real file
     * must not end up in the parsed document.
     *
     * The file is one of ours in the app's own directory, so the test proves
     * the parser declines to fetch it rather than proving it cannot - a parser
     * that resolved this would put the secret in the element's text, and on a
     * release build the file could be anywhere All files access reaches.
     */
    @Test
    public void doesNotResolveAnExternalEntity() throws Exception {
        DocumentBuilder builder = builder();
        File secret = File.createTempFile("xxe-probe", ".txt");
        String contents = "TOP-SECRET-" + System.nanoTime();

        try (FileOutputStream out = new FileOutputStream(secret)) {
            out.write(contents.getBytes(StandardCharsets.UTF_8));
        }

        try {
            // Strict: the doctype is accepted here (see the test above), so
            // this must parse and must come back without the file in it. A
            // throw would mean the parser changed under us, and the right
            // response is to read why rather than to widen a catch.
            Document document = parse(builder,
                    "<?xml version='1.0'?>"
                    + "<!DOCTYPE systemList ["
                    + "<!ENTITY stolen SYSTEM 'file://" + secret.getAbsolutePath() + "'>"
                    + "]>"
                    + "<systemList><name>&stolen;</name></systemList>");

            // Null, on Android: the entity reference is left unexpanded and
            // the element has no text content at all. Empty would do as well.
            // Either way the file did not get in, which is the whole claim.
            String text = document.getDocumentElement().getTextContent();
            if (text != null && text.contains(contents)) {
                fail("the parser read " + secret + " into the document");
            }
        } finally {
            secret.delete();
        }
    }

    /**
     * Billion laughs: nested entities that expand to more than memory holds.
     *
     * Same guard, different consequence - this one is a denial of service
     * rather than a read, and it needs no interesting file on the device. Ten
     * levels is enough to be unmistakable without being slow if it did expand.
     */
    @Test
    public void doesNotExpandNestedEntities() throws Exception {
        DocumentBuilder builder = builder();
        StringBuilder entities = new StringBuilder("<!ENTITY a0 'aaaaaaaaaa'>");

        for (int i = 1; i < 10; i++) {
            entities.append("<!ENTITY a").append(i).append(" '");
            for (int n = 0; n < 10; n++) entities.append("&a").append(i - 1).append(';');
            entities.append("'>");
        }

        Document document = parse(builder,
                "<?xml version='1.0'?>"
                + "<!DOCTYPE systemList [" + entities + "]>"
                + "<systemList>&a9;</systemList>");

        // 10^10 characters if it expanded; anything near that is a failure
        // even on a device that could somehow hold it. Null means the
        // reference was left alone, which is the same answer.
        String text = document.getDocumentElement().getTextContent();
        int length = text == null ? 0 : text.length();
        if (length > 100_000) fail("entities expanded to " + length + " characters");
    }
}
