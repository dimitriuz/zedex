package dev.ldlab.zedex.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.KeyEvent;

/**
 * The 48K's keyboard: forty rubber keys on a black case.
 *
 * Where the 128K's plate keeps everything white and inside a recess, this one
 * spreads its legends around the key and colours them — the keyword and the
 * letter white on the key, the symbol-shift token red beside it, extended mode
 * green above the key and extended mode with symbol shift red below it, and the
 * eight colour names in the colours they name. BLACK is a white box with the
 * word knocked out of it, as it is on the machine, because black on black is
 * nothing at all.
 *
 * The space is 1040x316: a 96 pitch, keys 84 by 48, each row further to the
 * right than the one above it by what the machine staggers them by. Both the
 * gaps between the keys and the bands above and below them are cut to what the
 * legends need rather than to the generous case of the real machine — a keyboard
 * here is something to hit with a thumb, and every unit it does not use is a
 * unit of picture.
 *
 * Slim keeps every key and takes the BASIC off. Nothing is left above or below a
 * key, so the keys grow into the gaps and the four rows come down to 192 units
 * from 316 — sideways that room is the picture's. What survives is what you need
 * when you are not typing BASIC: the letter, the character symbol shift gives,
 * the four cursor arrows on 5-8 and DELETE on 0.
 */
final class RubberPlate extends Plate {

    private static final int WIDTH = 1040;
    private static final int FULL = 316, SLIM = 192;

    static final float ASPECT = WIDTH / (float) FULL;
    static final float SLIM_ASPECT = WIDTH / (float) SLIM;

    /** A key, and the gap to the next one: 96 of pitch either way. */
    private static final float KEY = 84f, GAP = 12f;
    private static final float SLIM_KEY = 88f, SLIM_GAP = 8f;

    /*
     * A row is a band: the legends above the keys, the keys, and the legends
     * below them. Only the digits carry two lines above - the colour they print
     * and what CAPS SHIFT with them does - so only that row is paid for twice.
     */
    private static final float[] BAND = {4f, 91f, 165f, 239f};
    private static final float DEEP = 74f, DIGITS = 87f;
    private static final float TALL = 48f;     // a key
    private static final float LEGENDS = 13f;  // above one, and below it

    private static final int SHIFT = KeyEvent.KEYCODE_SHIFT_LEFT;

    /*
     * Fuse's artwork of this keyboard, which the app drew until now, holds the
     * machine's own colours softened to 212 and 67 rather than 255 and 0, on a
     * case of 27s and keys of grey with the green in it that rubber ones have.
     * They are sampled from it, because they were right.
     */
    private static final int CASE = 0xff1b1b1b;
    private static final int LIT = 0xff6b807e;
    private static final int SHADE = 0xff4a605e;
    private static final int SKIRT = 0xff33403f;

    private static final int PAINT = 0xffffffff;      // the white legends
    private static final int RED = 0xffd44343;
    private static final int GREEN = 0xff43d443;

    /** The eight colours the digits print, in the colours they name. */
    private static final int BLUE = 0xff4343d4, MAGENTA = 0xffd443d4,
                             CYAN = 0xff43d4d4, YELLOW = 0xffd4d443,
                             BLACK = 0xff000000;

    RubberPlate(boolean slim) {
        super(slim);
        layOut();
    }

    @Override
    int width() {
        return WIDTH;
    }

    @Override
    int height() {
        return slim ? SLIM : FULL;
    }

    // --- the keyboard -----------------------------------------------------

    private void layOut() {
        place(0, 10f,
            digit(KeyEvent.KEYCODE_1, "1", 14, "!").extended("BLUE", "DEF FN")
                    .ink(BLUE).caps("EDIT"),
            digit(KeyEvent.KEYCODE_2, "2", 13, "@").extended("RED", "FN")
                    .ink(RED).caps("CAPS LOCK"),
            digit(KeyEvent.KEYCODE_3, "3", 12, "#").extended("MAGENTA", "LINE")
                    .ink(MAGENTA).caps("TRUE VIDEO"),
            digit(KeyEvent.KEYCODE_4, "4", 11, "$").extended("GREEN", "OPEN #")
                    .ink(GREEN).caps("INV. VIDEO"),
            digit(KeyEvent.KEYCODE_5, "5", 10, "%").extended("CYAN", "CLOSE #")
                    .ink(CYAN).capsArrow(WEST),
            digit(KeyEvent.KEYCODE_6, "6", 9, "&").extended("YELLOW", "MOVE")
                    .ink(YELLOW).capsArrow(SOUTH),
            digit(KeyEvent.KEYCODE_7, "7", 8, "'").extended("WHITE", "ERASE")
                    .ink(PAINT).capsArrow(NORTH),
            digit(KeyEvent.KEYCODE_8, "8", 15, "(").extended(null, "POINT")
                    .capsArrow(EAST),
            digit(KeyEvent.KEYCODE_9, "9", NONE, ")").extended(null, "CAT")
                    .caps("GRAPHICS"),
            digit(KeyEvent.KEYCODE_0, "0", NONE, "_").extended("BLACK", "FORMAT")
                    .ink(BLACK).caps("DELETE"));

        place(1, 58f,
            letter(KeyEvent.KEYCODE_Q, "Q").keyword("PLOT", "<=").extended("SIN", "ASN"),
            letter(KeyEvent.KEYCODE_W, "W").keyword("DRAW", "<>").extended("COS", "ACS"),
            letter(KeyEvent.KEYCODE_E, "E").keyword("REM", ">=").extended("TAN", "ATN"),
            letter(KeyEvent.KEYCODE_R, "R").keyword("RUN", "<").extended("INT", "VERIFY"),
            letter(KeyEvent.KEYCODE_T, "T").keyword("RAND", ">").extended("RND", "MERGE"),
            letter(KeyEvent.KEYCODE_Y, "Y").keyword("RETURN", "AND").extended("STR$", "["),
            letter(KeyEvent.KEYCODE_U, "U").keyword("IF", "OR").extended("CHR$", "]"),
            letter(KeyEvent.KEYCODE_I, "I").keyword("INPUT", "AT").extended("CODE", "IN"),
            letter(KeyEvent.KEYCODE_O, "O").keyword("POKE", ";").extended("PEEK", "OUT"),
            letter(KeyEvent.KEYCODE_P, "P").keyword("PRINT", "\"").extended("TAB", "©"));

        place(2, 81f,
            letter(KeyEvent.KEYCODE_A, "A").keyword("NEW", "STOP").extended("READ", "~"),
            letter(KeyEvent.KEYCODE_S, "S").keyword("SAVE", "NOT").extended("RESTORE", "|"),
            letter(KeyEvent.KEYCODE_D, "D").keyword("DIM", "STEP").extended("DATA", "\\"),
            letter(KeyEvent.KEYCODE_F, "F").keyword("FOR", "TO").extended("SGN", "{"),
            letter(KeyEvent.KEYCODE_G, "G").keyword("GOTO", "THEN").extended("ABS", "}"),
            letter(KeyEvent.KEYCODE_H, "H").keyword("GOSUB", "↑").extended("SQR", "CIRCLE"),
            letter(KeyEvent.KEYCODE_J, "J").keyword("LOAD", "−").extended("VAL", "VAL$"),
            letter(KeyEvent.KEYCODE_K, "K").keyword("LIST", "+").extended("LEN", "SCREEN$"),
            letter(KeyEvent.KEYCODE_L, "L").keyword("LET", "=").extended("USR", "ATTR"),
            fn(KEY, KeyEvent.KEYCODE_ENTER, 0, "ENTER").label("ENTER"));

        place(3, 10f,
            fn(104f, SHIFT, 0, "CAPS SHIFT").label("CAPS\nSHIFT"),
            letter(KeyEvent.KEYCODE_Z, "Z").keyword("COPY", ":").extended("LN", "BEEP"),
            letter(KeyEvent.KEYCODE_X, "X").keyword("CLEAR", "£").extended("EXP", "INK"),
            letter(KeyEvent.KEYCODE_C, "C").keyword("CONT", "?").extended("LPRINT", "PAPER"),
            letter(KeyEvent.KEYCODE_V, "V").keyword("CLS", "/").extended("LLIST", "FLASH"),
            letter(KeyEvent.KEYCODE_B, "B").keyword("BORDER", "*").extended("BIN", "BRIGHT"),
            letter(KeyEvent.KEYCODE_N, "N").keyword("NEXT", ",").extended("INKEY$", "OVER"),
            letter(KeyEvent.KEYCODE_M, "M").keyword("PAUSE", ".").extended("PI", "INVERSE"),
            fn(KEY, KeyEvent.KEYCODE_CTRL_LEFT, 0, "SYMBOL SHIFT")
                    .label("SYMBOL\nSHIFT"),
            fn(130f, KeyEvent.KEYCODE_SPACE, 0, "BREAK SPACE").label("BREAK\nSPACE"));
    }

    private static Face fn(float width, int keycode, int modifier, String name) {
        return new Face(width, keycode, modifier, name);
    }

    private static Face letter(int keycode, String name) {
        return new Face(KEY, keycode, 0, name).letter(name);
    }

    private static Face digit(int keycode, String name, int mask, String symbol) {
        return new Face(KEY, keycode, 0, name).letter(name)
                                              .graphic(mask)
                                              .keyword(null, symbol);
    }

    /**
     * Lays a row out from where it starts, on a pitch.
     *
     * Each row starts further right than the one above it, which is the whole of
     * the rubber keyboard's stagger; the bands are contiguous, so every part of
     * the case belongs to the key whose legends are printed on it.
     */
    private void place(int row, float start, Face... keys) {
        float top = slim ? 4f + row * 46f : BAND[row];
        float deep = slim ? 46f : row == 0 ? DIGITS : DEEP;

        float keyTop = top + (slim ? 3f : row == 0 ? 2f * LEGENDS : LEGENDS);
        float keyBottom = keyTop + (slim ? 40f : TALL);

        // Slim has nothing printed between the keys, so they take the room the
        // legends had: wider by the gap they no longer need.
        float grown = slim ? SLIM_KEY - KEY : 0f;
        float x = start - grown / 2f;

        for (Face face : keys) {
            float width = face.width + grown;

            face.band.set(x, top, x + width, top + deep);
            face.top.set(x, keyTop, x + width, keyBottom);

            x += width + (slim ? SLIM_GAP : GAP);
        }

        band(keys);
    }

    // --- drawing ----------------------------------------------------------

    @Override
    int background() {
        return CASE;
    }

    @Override
    int keyLit() {
        return LIT;
    }

    @Override
    int keyFoot() {
        return SHADE;
    }

    @Override
    int keySkirt() {
        return SKIRT;
    }

    @Override
    float head() {
        return 5f;
    }

    @Override
    float foot(RectF box) {
        return 5f;
    }

    @Override
    void legends(Canvas canvas, Face face) {
        if (slim) {
            slim(canvas, face);
            return;
        }

        RectF box = face.top;
        float room = face.band.width() - 4f;
        float centre = face.band.centerX();

        // Above the key: extended mode in green, or on the digits the colour it
        // prints, in that colour - an ink of its own is what says which it is.
        if (face.extended != null) {
            if (face.extendedInk == WHITE) {
                write(canvas, face.extended, centre, face.band.top + 11f, 11f,
                      room, GREEN);
            } else {
                colourName(canvas, face.extended, centre, face.band.top + 11f,
                           11f, room, face.extendedInk);
            }
        }

        // And then what CAPS SHIFT with it does, which only the digits have.

        if (face.capsLabel != null) {
            write(canvas, face.capsLabel, centre, face.band.top + 23f, 11f, room,
                  PAINT);
        }
        if (face.capsArrow != NO_ARROW) {
            arrow(canvas, centre, face.band.top + 19f, 19f, 11f, face.capsArrow,
                  PAINT);
        }

        // And below it, extended mode with symbol shift.
        if (face.extendedShifted != null) {
            write(canvas, face.extendedShifted, centre, face.band.bottom - 2f, 11f,
                  room, RED);
        }

        if (face.label != null) {
            label(canvas, face, box);
            return;
        }

        if (face.graphic != NONE || face.keyword == null) {
            digitFace(canvas, face, box);
            return;
        }

        // On the key: the letter large at the left, the symbol-shift token red
        // at the right, and the keyword across the foot.
        write(canvas, face.letter, Paint.Align.LEFT, box.left + 9f,
              box.top + 30f, 24f, box.width() * 0.5f, PAINT);

        if (face.symbol != null) {
            write(canvas, face.symbol, Paint.Align.RIGHT, box.right - 6f,
                  box.top + (face.shiftedWord() ? 18f : 21f),
                  face.shiftedWord() ? 10f : 14f, box.width() * 0.48f, RED);
        }

        write(canvas, face.keyword, box.centerX(), box.bottom - 6f, 11f,
              box.width() - 8f, PAINT);
    }

    /**
     * A digit: the number at the left, the block graphic it gives in graphics
     * mode at the top right, and the symbol-shift character under that.
     */
    private void digitFace(Canvas canvas, Face face, RectF box) {
        write(canvas, face.letter, Paint.Align.LEFT, box.left + 9f,
              box.top + 32f, 25f, box.width() * 0.45f, PAINT);

        if (face.graphic != NONE) {
            graphic(canvas, face.graphic, box.right - 23f, box.top + 6f, 12f,
                    PAINT);
        }
        if (face.symbol != null) {
            write(canvas, face.symbol, Paint.Align.RIGHT, box.right - 6f,
                  box.bottom - 7f, 14f, box.width() * 0.4f, RED);
        }
    }

    /** ENTER, both shifts and SPACE: two words across the key. */
    private void label(Canvas canvas, Face face, RectF box) {
        String[] lines = face.label.split("\n");
        int colour = face.keycode == KeyEvent.KEYCODE_CTRL_LEFT ? RED : PAINT;

        float step = slim ? 10f : 12f;
        float size = lines.length > 1 ? (slim ? 9.5f : 11f) : (slim ? 12f : 13f);
        float first = box.centerY() + (lines.length > 1 ? -step / 2f + 2f
                                                        : size / 3f);

        for (int i = 0; i < lines.length; i++) {
            write(canvas, lines[i], box.centerX(), first + i * step, size,
                  box.width() - 8f, colour);
        }
    }

    /**
     * Slim: the letter and the character symbol shift gives, and on the digits
     * the arrow or DELETE that CAPS SHIFT with them reaches. Everything else on
     * this keyboard is BASIC.
     */
    private void slim(Canvas canvas, Face face) {
        RectF box = face.top;
        boolean marked = face.capsArrow != NO_ARROW
                      || "DELETE".equals(face.capsLabel);

        if (face.label != null) {
            label(canvas, face, box);
            return;
        }

        boolean symbol = face.symbol != null && !face.shiftedWord();

        ink.setTextSize(19f);
        float name = ink.measureText(face.letter);
        ink.setTextSize(12f);
        float shifted = symbol ? ink.measureText(face.symbol) + 6f : 0f;

        float left = box.centerX() - (name + shifted) / 2f;
        float middle = box.centerY() + (marked ? -1f : 7f);

        write(canvas, face.letter, Paint.Align.LEFT, left, middle, 19f,
              box.width() - 6f, PAINT);

        if (symbol) {
            write(canvas, face.symbol, Paint.Align.LEFT, left + name + 6f,
                  middle - 1f, 12f, shifted, RED);
        }

        if (!marked) return;

        if (face.capsArrow != NO_ARROW) {
            arrow(canvas, box.centerX(), middle + 10f, 18f, 11f, face.capsArrow,
                  PAINT);
        } else {
            write(canvas, face.capsLabel, box.centerX(), middle + 13f, 9f,
                  box.width() - 8f, PAINT);
        }
    }
}
