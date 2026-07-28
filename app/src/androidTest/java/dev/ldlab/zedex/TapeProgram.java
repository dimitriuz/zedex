package dev.ldlab.zedex;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A BASIC program, as a {@code .tap} file the machine can load.
 *
 * Typing a program on the on-screen keyboard costs a tap and 150ms per
 * character, and a test that does it is mostly waiting: the keyboard is read
 * once a frame, so pressing faster loses keys. Handing the machine a tape
 * instead takes one call and a second or two, and what the program says stays
 * readable in the test rather than being spelled out key by key.
 *
 * The program is written in ordinary BASIC text and tokenised here, because
 * that is what the Spectrum stores: a keyword is one byte, not a word. Two
 * things follow from the format and are easy to get wrong —
 *
 * <ul>
 * <li>Every number in a line is stored <em>twice</em>: as the digits you see,
 *     and again as a five byte binary form the interpreter is the one that
 *     actually reads. A line without them lists correctly and then misbehaves
 *     when run.</li>
 * <li>A line's length is big endian for the line number and little endian for
 *     the length, which is not a typo.</li>
 * </ul>
 *
 * Give the header an autostart line and the machine runs it as it lands, so a
 * test needs no keystrokes at all.
 */
final class TapeProgram {

    /** The 48K token table, {@code RND} at 0xa5 through {@code COPY} at 0xff. */
    private static final Map<String, Integer> TOKENS = new LinkedHashMap<>();

    static {
        String[] words = {
            "RND", "INKEY$", "PI", "FN", "POINT", "SCREEN$", "ATTR", "AT",
            "TAB", "VAL$", "CODE", "VAL", "LEN", "SIN", "COS", "TAN", "ASN",
            "ACS", "ATN", "LN", "EXP", "INT", "SQR", "SGN", "ABS", "PEEK",
            "IN", "USR", "STR$", "CHR$", "NOT", "BIN", "OR", "AND", "<=",
            ">=", "<>", "LINE", "THEN", "TO", "STEP", "DEF FN", "CAT",
            "FORMAT", "MOVE", "ERASE", "OPEN #", "CLOSE #", "MERGE",
            "VERIFY", "BEEP", "CIRCLE", "INK", "PAPER", "FLASH", "BRIGHT",
            "INVERSE", "OVER", "OUT", "LPRINT", "LLIST", "STOP", "READ",
            "DATA", "RESTORE", "NEW", "BORDER", "CONTINUE", "DIM", "REM",
            "FOR", "GO TO", "GO SUB", "INPUT", "LOAD", "LIST", "LET",
            "PAUSE", "NEXT", "POKE", "PRINT", "PLOT", "RUN", "SAVE",
            "RANDOMIZE", "IF", "CLS", "DRAW", "CLEAR", "RETURN", "COPY",
        };

        for (int i = 0; i < words.length; i++) TOKENS.put(words[i], 0xa5 + i);
    }

    /** What the header carries when the program is not to start itself. */
    private static final int NO_AUTOSTART = 32768;

    /** A tape block is a flag byte, the payload, and their XOR. */
    private static final int HEADER_FLAG = 0x00;
    private static final int DATA_FLAG = 0xff;

    /** Header block: type, ten characters of name, and three words. */
    private static final int TYPE_PROGRAM = 0;
    private static final int NAME_LENGTH = 10;

    private final ByteArrayOutputStream program = new ByteArrayOutputStream();
    private int autostart = NO_AUTOSTART;

    /** Adds one numbered line, written the way you would type it. */
    TapeProgram line(int number, String basic) {
        byte[] body = tokenise(basic);

        program.write(number >> 8);            // big endian, unlike everything else
        program.write(number & 0xff);
        program.write((body.length + 1) & 0xff);
        program.write((body.length + 1) >> 8);
        program.write(body, 0, body.length);
        program.write(0x0d);

        return this;
    }

    /** Makes the machine RUN from this line the moment the tape has loaded. */
    TapeProgram startingAt(int line) {
        autostart = line;
        return this;
    }

    void writeTo(File file, String name) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();

        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(toTap(name));
        }
    }

    private byte[] toTap(String name) {
        byte[] body = program.toByteArray();
        ByteArrayOutputStream header = new ByteArrayOutputStream();

        header.write(TYPE_PROGRAM);
        for (int i = 0; i < NAME_LENGTH; i++) {
            header.write(i < name.length() ? name.charAt(i) : ' ');
        }
        word(header, body.length);
        word(header, autostart);
        // Where the variables start: at the end, since there are none.
        word(header, body.length);

        ByteArrayOutputStream tape = new ByteArrayOutputStream();
        block(tape, HEADER_FLAG, header.toByteArray());
        block(tape, DATA_FLAG, body);

        return tape.toByteArray();
    }

    private static void word(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
    }

    private static void block(ByteArrayOutputStream tape, int flag, byte[] payload) {
        int checksum = flag;
        for (byte b : payload) checksum ^= b & 0xff;

        word(tape, payload.length + 2);        // the flag and the checksum too
        tape.write(flag);
        tape.write(payload, 0, payload.length);
        tape.write(checksum);
    }

    // --- tokenising ---------------------------------------------------------

    /**
     * Turns BASIC text into what the Spectrum stores.
     *
     * A keyword is only recognised where a word can begin and end, so the
     * {@code IN} inside {@code POINT} stays three letters of a longer word.
     * Everything after a {@code REM}, and everything inside quotes, is
     * literal: no tokens and no number forms.
     */
    private static byte[] tokenise(String basic) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean quoted = false;
        boolean literal = false;

        for (int i = 0; i < basic.length(); ) {
            char c = basic.charAt(i);

            if (c == '"') quoted = !quoted;

            if (!quoted && !literal) {
                int token = tokenAt(basic, i);
                if (token != 0) {
                    String word = wordAt(basic, i);
                    out.write(token);
                    if (token == TOKENS.get("REM")) literal = true;
                    i += word.length();
                    continue;
                }

                if (Character.isDigit(c) && !continuesAName(basic, i)) {
                    int end = i;
                    while (end < basic.length() && Character.isDigit(basic.charAt(end))) {
                        end++;
                    }
                    String digits = basic.substring(i, end);
                    for (char d : digits.toCharArray()) out.write(d);
                    numberForm(out, Integer.parseInt(digits));
                    i = end;
                    continue;
                }
            }

            out.write(c);
            i++;
        }

        return out.toByteArray();
    }

    /** The longest keyword starting here, or 0 if this is not where one does. */
    private static int tokenAt(String basic, int at) {
        String found = wordAt(basic, at);
        return found == null ? 0 : TOKENS.get(found);
    }

    private static String wordAt(String basic, int at) {
        String best = null;

        for (String word : TOKENS.keySet()) {
            if (best != null && word.length() <= best.length()) continue;
            if (!basic.regionMatches(true, at, word, 0, word.length())) continue;

            // A word made of letters has to stand alone; "IN" is not the
            // middle of "POINT", and "TO" is not the end of "AUTO".
            if (Character.isLetter(word.charAt(0))) {
                if (at > 0 && isNamePart(basic.charAt(at - 1))) continue;

                int after = at + word.length();
                char last = word.charAt(word.length() - 1);
                if (Character.isLetter(last) && after < basic.length()
                        && isNamePart(basic.charAt(after))) {
                    continue;
                }
            }

            best = word;
        }

        return best;
    }

    /** Digits after a letter are part of a variable's name, not a number. */
    private static boolean continuesAName(String basic, int at) {
        return at > 0 && Character.isLetter(basic.charAt(at - 1));
    }

    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '$';
    }

    /**
     * The five byte binary form that follows the digits, which is the one the
     * interpreter reads. Small integers get the short form: a zero exponent,
     * a sign, the value, and a spare byte.
     */
    private static void numberForm(ByteArrayOutputStream out, int value) {
        out.write(0x0e);
        out.write(0x00);
        out.write(0x00);
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
        out.write(0x00);
    }
}
