package dev.ldlab.zedex.library.ui;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;

/**
 * Reading a text file somebody typed up in 1999.
 *
 * The instructions ZXDB carries are plain text, mostly ASCII, written over
 * twenty-five years by whoever was transcribing an inlay that evening - so
 * there is no encoding declared anywhere and no way to ask. Two rules cover
 * every one of them.
 *
 * <b>UTF-8 if it decodes, Latin-1 if it does not.</b> UTF-8 is strict enough
 * to be a test of itself: a file of arbitrary high bytes almost never happens
 * to be valid UTF-8, so trying it and falling back loses nothing and gets the
 * modern ones right. Latin-1 then cannot fail - every byte is a character -
 * which is the property that makes it the right last resort rather than a
 * guess that can still throw.
 *
 * <b>The line breaks are the author's and stay that way.</b> These are
 * hard-wrapped at about seventy-eight columns, with rules under headings and
 * the occasional table, so re-flowing one to a phone's width turns a neat
 * document into ragged nonsense. Only the carriage returns go, because a
 * {@code TextView} draws them as boxes.
 */
public final class PlainText {

    private PlainText() {
    }

    /** How wide these were typed, and so how wide they want to be read - see
     *  {@code InstructionsActivity}, which sizes its text to fit this. */
    public static final int COLUMNS = 78;

    public static String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";

        return withoutCarriageReturns(text(bytes));
    }

    private static String text(byte[] bytes) {
        CharsetDecoder strict = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);

        try {
            return strict.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException notUtf8) {
            // Every byte is a character in Latin-1, so this cannot fail in
            // turn - which is the whole reason it is the fallback.
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    /**
     * CRLF becomes LF, and a lone CR does too.
     *
     * These files come from a DOS-era world and nearly all of them are CRLF;
     * a TextView draws the carriage return as a missing-glyph box at the end
     * of every line. A lone CR - an old Mac transcription - is a line break in
     * its own right and becomes one.
     */
    private static String withoutCarriageReturns(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }
}
