package dev.ldlab.zedex.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.KeyEvent;

/**
 * The 128K's keyboard: the ZX Spectrum+ plate.
 *
 * A grid of recesses, each holding one key with its two extended-mode legends on
 * the plate above it, all of it white on dark grey. The space is 1040x413, the
 * same one the photograph used that this replaced.
 *
 * The rows are an exact grid — a 74.5 pitch across every one of them, staggered
 * by the width of the function keys at the left, and the last key in a row runs
 * out to the margin so the rows end flush the way the plate does. The legends
 * were read off a photograph and RetroTechLab's layout drawing side by side.
 *
 * Slim drops the legend band and the keywords with it: the keys keep their names
 * and the characters symbol shift gives, and five rows fit in 230 units instead
 * of 413 — the point of it being the picture above, not the keyboard.
 */
final class PlusPlate extends Plate {

    private static final int WIDTH = 1040;
    private static final int FULL = 413, SLIM = 230;

    static final float ASPECT = WIDTH / (float) FULL;
    static final float SLIM_ASPECT = WIDTH / (float) SLIM;

    private static final float LEFT = 20f, RIGHT = 1020f;
    private static final float WALL = 4f;      // plate left between two recesses
    private static final float INSET = 2f;     // recess edge to key edge
    private static final float KEY = 70.5f;    // a letter's recess: 74.5 pitch

    private static final int SHIFT = KeyEvent.KEYCODE_SHIFT_LEFT;

    private static final int PLATE = 0xff2b2c2e;
    private static final int FLOOR = 0xff141517;
    private static final int LIT = 0xff4f5156;
    private static final int SHADE = 0xff37383c;
    private static final int SKIRT = 0xff1e1f21;
    private static final int LEGEND = 0xffd8d8d8;

    PlusPlate(boolean slim) {
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

    // --- the plate --------------------------------------------------------

    private void layOut() {
        place(0,
            fn(70, KeyEvent.KEYCODE_3, SHIFT, "TRUE VIDEO").label("TRUE\nVIDEO"),
            fn(70, KeyEvent.KEYCODE_4, SHIFT, "INV VIDEO").label("INV\nVIDEO"),
            digit(KeyEvent.KEYCODE_1, "1", 1, "!").extended("BLUE", "DEF FN"),
            digit(KeyEvent.KEYCODE_2, "2", 2, "@").extended("RED", "FN"),
            digit(KeyEvent.KEYCODE_3, "3", 3, "#").extended("MGNTA", "LINE"),
            digit(KeyEvent.KEYCODE_4, "4", 4, "$").extended("GREEN", "OPEN #"),
            digit(KeyEvent.KEYCODE_5, "5", 5, "%").extended("CYAN", "CLOSE #"),
            digit(KeyEvent.KEYCODE_6, "6", 6, "&").extended("YELLOW", "MOVE"),
            digit(KeyEvent.KEYCODE_7, "7", 7, "'").extended("WHITE", "ERASE"),
            digit(KeyEvent.KEYCODE_8, "8", 0, "(").extended(null, "POINT"),
            digit(KeyEvent.KEYCODE_9, "9", NONE, ")").extended(null, "CAT"),
            digit(KeyEvent.KEYCODE_0, "0", NONE, "_").letter("Ø")
                                                     .extended("BLACK", "FORMAT"),
            fn(107, KeyEvent.KEYCODE_SPACE, SHIFT, "BREAK").label("BREAK"));

        Face upper = fn(67, KeyEvent.KEYCODE_ENTER, 0, "ENTER");

        place(1,
            fn(103, KeyEvent.KEYCODE_DEL, 0, "DELETE").label("DELETE"),
            fn(74, KeyEvent.KEYCODE_9, SHIFT, "GRAPH").label("GRAPH"),
            letter(KeyEvent.KEYCODE_Q, "Q").keyword("PLOT", "<=").extended("SIN", "ASN"),
            letter(KeyEvent.KEYCODE_W, "W").keyword("DRAW", "<>").extended("COS", "ACS"),
            letter(KeyEvent.KEYCODE_E, "E").keyword("REM", ">=").extended("TAN", "ATN"),
            letter(KeyEvent.KEYCODE_R, "R").keyword("RUN", "<").extended("INT", "VERIFY"),
            letter(KeyEvent.KEYCODE_T, "T").keyword("RAND", ">").extended("RND", "MERGE"),
            letter(KeyEvent.KEYCODE_Y, "Y").keyword("RETURN", "AND").extended("STR$", "["),
            letter(KeyEvent.KEYCODE_U, "U").keyword("IF", "OR").extended("CHR$", "]"),
            letter(KeyEvent.KEYCODE_I, "I").keyword("INPUT", "AT").extended("CODE", "IN"),
            letter(KeyEvent.KEYCODE_O, "O").keyword("POKE", null).extended("PEEK", "OUT"),
            letter(KeyEvent.KEYCODE_P, "P").keyword("PRINT", null).extended("TAB", "©"),
            upper);

        Face enter = fn(127, KeyEvent.KEYCODE_ENTER, 0, "ENTER").label("ENTER");

        place(2,
            fn(108, KeyEvent.KEYCODE_CTRL_LEFT, SHIFT, "EXTEND MODE")
                    .label("EXTEND\nMODE"),
            fn(87, KeyEvent.KEYCODE_ESCAPE, 0, "EDIT").label("EDIT"),
            letter(KeyEvent.KEYCODE_A, "A").keyword("NEW", "STOP").extended("READ", "~"),
            letter(KeyEvent.KEYCODE_S, "S").keyword("SAVE", "NOT").extended("RESTR", "|"),
            letter(KeyEvent.KEYCODE_D, "D").keyword("DIM", "STEP").extended("DATA", "\\"),
            letter(KeyEvent.KEYCODE_F, "F").keyword("FOR", "TO").extended("SGN", "{"),
            letter(KeyEvent.KEYCODE_G, "G").keyword("GOTO", "THEN").extended("ABS", "}"),
            letter(KeyEvent.KEYCODE_H, "H").keyword("GOSUB", "↑").extended("SQR", "CIRCLE"),
            letter(KeyEvent.KEYCODE_J, "J").keyword("LOAD", "−").extended("VAL", "VAL$"),
            letter(KeyEvent.KEYCODE_K, "K").keyword("LIST", "+").extended("LEN", "SCRN$"),
            letter(KeyEvent.KEYCODE_L, "L").keyword("LET", "=").extended("USR", "ATTR"),
            enter);

        merge(upper, enter);

        place(3,
            fn(163, SHIFT, 0, "CAPS SHIFT").label("CAPS SHIFT"),
            fn(74, KeyEvent.KEYCODE_CAPS_LOCK, 0, "CAPS LOCK").label("CAPS\nLOCK"),
            letter(KeyEvent.KEYCODE_Z, "Z").keyword("COPY", ":").extended("LN", "BEEP"),
            letter(KeyEvent.KEYCODE_X, "X").keyword("CLEAR", "£").extended("EXP", "INK"),
            letter(KeyEvent.KEYCODE_C, "C").keyword("CONT", "?").extended("LPRINT", "PAPER"),
            letter(KeyEvent.KEYCODE_V, "V").keyword("CLS", "/").extended("LLIST", "FLASH"),
            letter(KeyEvent.KEYCODE_B, "B").keyword("BORDER", "*").extended("BIN", "BRIGHT"),
            letter(KeyEvent.KEYCODE_N, "N").keyword("NEXT", null).extended("INKEY$", "OVER"),
            letter(KeyEvent.KEYCODE_M, "M").keyword("PAUSE", null).extended("PI", "INVERS"),
            fn(KEY, KeyEvent.KEYCODE_PERIOD, 0, ".").label("."),
            fn(159, SHIFT, 0, "CAPS SHIFT").label("CAPS SHIFT"));

        place(4,
            fn(76, KeyEvent.KEYCODE_CTRL_LEFT, 0, "SYMBOL SHIFT").label("SYMBOL\nSHIFT"),
            fn(KEY, KeyEvent.KEYCODE_SEMICOLON, 0, ";").label(";"),
            fn(KEY, KeyEvent.KEYCODE_APOSTROPHE, 0, "QUOTE").label("”"),
            fn(KEY, KeyEvent.KEYCODE_DPAD_LEFT, 0, "LEFT").arrow(WEST),
            fn(KEY, KeyEvent.KEYCODE_DPAD_RIGHT, 0, "RIGHT").arrow(EAST),
            fn(328, KeyEvent.KEYCODE_SPACE, 0, "SPACE"),
            fn(KEY, KeyEvent.KEYCODE_DPAD_UP, 0, "UP").arrow(NORTH),
            fn(KEY, KeyEvent.KEYCODE_DPAD_DOWN, 0, "DOWN").arrow(SOUTH),
            fn(KEY, KeyEvent.KEYCODE_COMMA, 0, ",").label(","),
            fn(66.5f, KeyEvent.KEYCODE_CTRL_LEFT, 0, "SYMBOL SHIFT")
                    .label("SYMBOL\nSHIFT"));
    }

    private static Face fn(float width, int keycode, int modifier, String name) {
        return new Face(width, keycode, modifier, name);
    }

    /** A key the Spectrum types a letter with: keyword, symbol, letter. */
    private static Face letter(int keycode, String name) {
        return new Face(KEY, keycode, 0, name).letter(name);
    }

    /** A digit, with its block graphic and symbol-shift character. */
    private static Face digit(int keycode, String name, int mask, String symbol) {
        return new Face(KEY, keycode, 0, name).letter(name)
                                              .graphic(mask)
                                              .keyword(null, symbol);
    }

    /**
     * Fits one row of recesses between the margins.
     *
     * The last key in a row runs out to the right margin whatever it asked for,
     * so every row ends flush and the rounding left over by a fractional pitch
     * never shows as a ragged edge.
     */
    private void place(int row, Face... keys) {
        float pitch = slim ? 44f : 78f;
        float deep = slim ? 40f : 74f;
        float top = (slim ? 6f : 13f) + row * pitch;
        float bottom = top + deep;

        // Above each key, the room its extended-mode legends need. The bottom
        // row has none even on the full plate, so its keys are taller there.
        float above = slim ? 3f : row == 4 ? 14f : 27f;
        float under = !slim && row == 4 ? 4f : 3f;

        float x = LEFT;

        for (int i = 0; i < keys.length; i++) {
            float width = i == keys.length - 1 ? RIGHT - x : keys[i].width;

            keys[i].band.set(x, top, x + width, bottom);
            keys[i].top.set(x + INSET, top + above,
                            x + width - INSET, bottom - under);

            x += width + WALL;
        }

        band(keys);
    }

    // --- drawing ----------------------------------------------------------

    @Override
    int background() {
        return PLATE;
    }

    @Override
    void well(Canvas canvas, Face face) {
        fill.setColor(FLOOR);
        canvas.drawRoundRect(face.band, 4f, 4f, fill);
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
        return 6f;
    }

    @Override
    float foot(RectF box) {
        return Math.min(box.height() * 0.5f, box.width() * 0.45f);
    }

    @Override
    void legends(Canvas canvas, Face face) {
        RectF box = face.top;

        if (!slim) {
            if (face.extended != null) {
                write(canvas, face.extended, face.band.centerX(),
                      face.band.top + 12f, 11f, face.band.width() - 6f, LEGEND);
            }
            if (face.extendedShifted != null) {
                write(canvas, face.extendedShifted, face.band.centerX(),
                      face.band.top + 23.5f, 11f, face.band.width() - 6f, LEGEND);
            }
        }

        if (face.hidden) return;                        // ENTER's upper half

        if (face.arrow != NO_ARROW) {
            arrow(canvas, box.centerX(), box.centerY() + (slim ? 0f : 3f),
                  slim ? 24f : 30f, slim ? 15f : 20f, face.arrow, WHITE);
            return;
        }

        if (face.label != null) {
            label(canvas, face.label, box);
            return;
        }

        if (slim) {
            pair(canvas, face, box);
            return;
        }

        if (face.graphic != NONE || face.keyword == null) {
            block(canvas, face, box);
        } else {
            write(canvas, face.keyword, box.centerX(), box.top + 11f, 11f,
                  box.width() - 6f, WHITE);

            // A symbol-shift token that is a word takes the line under the
            // keyword; a character rides at the lower right of it.
            if (face.shiftedWord()) {
                write(canvas, face.symbol, box.centerX(), box.top + 21.5f, 11f,
                      box.width() - 6f, WHITE);
            } else if (face.symbol != null) {
                write(canvas, face.symbol, Paint.Align.RIGHT, box.right - 5f,
                      box.top + 22f, 12.5f, box.width() / 2f, WHITE);
            }
        }

        if (face.letter != null) {
            write(canvas, face.letter, box.centerX(), box.bottom - 4.5f, 18f,
                  box.width() - 6f, WHITE);
        }
    }

    /** One or two words across the middle of a function key. */
    private void label(Canvas canvas, String text, RectF box) {
        String[] lines = text.split("\n");

        // A single punctuation mark is a legend in its own right, and the plate
        // draws it as large as a letter.
        if (lines.length == 1 && lines[0].length() == 1
                && !Character.isLetterOrDigit(lines[0].charAt(0))) {
            write(canvas, lines[0], box.centerX(),
                  box.centerY() + (slim ? 7f : 9f), slim ? 19f : 24f,
                  box.width() - 8f, WHITE);
            return;
        }

        float step = slim ? 10.5f : 13f;
        float size = lines.length > 1 ? (slim ? 10.5f : 14f)
                                      : (slim ? 12.5f : 15.5f);
        float first = box.centerY() + (lines.length > 1 ? -step / 2f + 3f
                                                        : size / 3f);

        for (int i = 0; i < lines.length; i++) {
            write(canvas, lines[i], box.centerX(), first + i * step, size,
                  box.width() - 8f, WHITE);
        }
    }

    /**
     * A digit's pair: the block graphic and the symbol-shift character, side by
     * side and centred together. 9 and 0 have no graphic, only the character.
     */
    private void block(Canvas canvas, Face face, RectF box) {
        float cell = 12f;

        ink.setTextSize(12.5f);
        float wide = face.symbol != null ? ink.measureText(face.symbol) : 0f;
        float x = box.centerX()
                - ((face.graphic == NONE ? 0f : cell + 5f) + wide) / 2f;
        float top = box.top + 4f;

        if (face.graphic != NONE) {
            graphic(canvas, face.graphic, x, top, cell, WHITE);
            x += cell + 5f;
        }
        if (face.symbol != null) {
            write(canvas, face.symbol, Paint.Align.LEFT, x, top + cell, 12.5f,
                  wide + 1f, WHITE);
        }
    }

    /**
     * Slim: the key's own legend and the character symbol shift gives, centred
     * together. Nothing that only means something to BASIC.
     */
    private void pair(Canvas canvas, Face face, RectF box) {
        if (face.letter == null) return;

        boolean symbol = face.symbol != null && !face.shiftedWord();

        ink.setTextSize(17f);
        float name = ink.measureText(face.letter);
        ink.setTextSize(11f);
        float shifted = symbol ? ink.measureText(face.symbol) + 6f : 0f;

        float left = box.centerX() - (name + shifted) / 2f;
        float middle = box.centerY() + 6f;

        write(canvas, face.letter, Paint.Align.LEFT, left, middle, 17f,
              box.width() - 6f, WHITE);

        if (symbol) {
            write(canvas, face.symbol, Paint.Align.LEFT, left + name + 6f,
                  middle - 1f, 11f, shifted, LEGEND);
        }
    }
}
