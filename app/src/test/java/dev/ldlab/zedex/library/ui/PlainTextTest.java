package dev.ldlab.zedex.library.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * Reading twenty-five years of transcribed inlays.
 *
 * No encoding is declared in any of them and there is nobody to ask, so what
 * is tested here is that the two rules cover what actually turns up: modern
 * files written as UTF-8, and older ones that are Latin-1 and would throw if
 * that were assumed either way round.
 */
public class PlainTextTest {

    @Test
    public void plainAsciiIsItself() {
        assertEquals("Head Over Heels",
                     PlainText.decode("Head Over Heels".getBytes(StandardCharsets.US_ASCII)));
    }

    /** The modern ones, which are the reason UTF-8 is tried first. */
    @Test
    public void utf8IsReadAsUtf8() {
        String said = "Ocean Software — Manuel d'instructions";

        assertEquals(said, PlainText.decode(said.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * And the older ones, which are not.
     *
     * A Latin-1 é is a single 0xE9, which is not valid UTF-8 - so decoding
     * strictly fails, the fallback runs, and the character survives. Read as
     * UTF-8 with replacement instead it would come out as a question mark in
     * a diamond, quietly, in exactly the games whose titles are French or
     * Spanish.
     */
    @Test
    public void latin1IsReadAsLatin1WhenItCannotBeUtf8() {
        byte[] bytes = "Le manuel dé Zédex".getBytes(StandardCharsets.ISO_8859_1);

        String read = PlainText.decode(bytes);

        assertEquals("Le manuel dé Zédex", read);
        assertFalse("the accents were replaced rather than decoded",
                    read.contains("�"));
    }

    /** Nothing in, nothing out - an empty file is not an error. */
    @Test
    public void nothingIsNotAfailure() {
        assertEquals("", PlainText.decode(null));
        assertEquals("", PlainText.decode(new byte[0]));
    }

    /**
     * Carriage returns go; the author's own line breaks stay.
     *
     * A TextView draws a stray CR as a missing-glyph box at the end of every
     * single line, which is what these files would look like untouched -
     * nearly all of them are CRLF.
     */
    @Test
    public void carriageReturnsGoAndTheLineBreaksStay() {
        assertEquals("one\ntwo\nthree",
                     PlainText.decode("one\r\ntwo\r\nthree"
                                              .getBytes(StandardCharsets.US_ASCII)));

        assertEquals("an old Mac transcription breaks lines with CR alone",
                     "one\ntwo",
                     PlainText.decode("one\rtwo".getBytes(StandardCharsets.US_ASCII)));
    }

    /**
     * <b>The wrapping is the author's and is not touched.</b>
     *
     * These are hard-wrapped at about seventy-eight columns, with rules under
     * the headings; re-flowing one to a phone's width turns a neat document
     * into ragged nonsense, and the viewer sizes its text to fit instead.
     */
    @Test
    public void thehardWrappingIsLeftExactlyAsItIs() {
        String document = "===============\nHead Over Heels\n===============\n"
                + "Is one of the most addictive, playable, cuddly, cute and fun games\n"
                + "ever. Miss it at your peril. (Crash)";

        String read = PlainText.decode(document.getBytes(StandardCharsets.US_ASCII));

        assertEquals(document, read);
        assertTrue("the rule under the heading was reflowed",
                   read.contains("===============\nHead Over Heels"));
    }
}
