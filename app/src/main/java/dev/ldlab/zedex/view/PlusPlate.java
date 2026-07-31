package dev.ldlab.zedex.view;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * The 128K's keyboard, drawn rather than photographed.
 *
 * The layout is the ZX Spectrum+ plate: a grid of recesses, each holding one
 * key with its two extended-mode legends on the plate above it. Everything is
 * in a 1040x413 space the view scales to wherever the keyboard lands, so the
 * legends are as sharp as the screen allows - a photograph of a real plate was
 * the skin before this and no amount of scaling made its small print readable.
 *
 * Rows are on an exact grid: a 74.5 pitch across every row, staggered by the
 * width of the keys at the left of it, which is how the real plate does it.
 * The legends were read off a photograph of a plate and RetroTechLab's layout
 * drawing side by side.
 *
 * The block graphics on 1-8 are the plate's own set, CHR$ 129 to 135 and then
 * 128, held as quadrant masks - top-right 1, top-left 2, bottom-right 4,
 * bottom-left 8. Fuse's 48K artwork prints the complements of these.
 */
final class PlusPlate {

    static final int WIDTH = 1040;
    static final int HEIGHT = 413;
    static final float ASPECT = WIDTH / (float) HEIGHT;

    private static final float LEFT = 20f, RIGHT = 1020f;
    private static final float FIRST = 13f;    // top of the first recess
    private static final float RECESS = 74f;   // how deep one is
    private static final float PITCH = 78f;    // and where the next one starts
    private static final float WALL = 4f;      // plate left between two of them
    private static final float INSET = 2f;     // recess edge to key edge
    private static final float ABOVE = 27f;    // the plate legends' band
    private static final float KEY = 70.5f;    // a letter's recess: 74.5 pitch

    private static final int SHIFT = KeyEvent.KEYCODE_SHIFT_LEFT;
    private static final int NONE = -1;        // no block graphic on this key

    private static final int PLATE = 0xff2b2c2e;
    private static final int FLOOR = 0xff141517;
    private static final int KEY_TOP = 0xff4f5156;
    private static final int KEY_FOOT = 0xff37383c;
    private static final int SKIRT = 0xff1e1f21;
    private static final int LEGEND = 0xffd8d8d8;
    private static final int WHITE = 0xfff6f6f6;

    /** Which way one of the four cursor keys points. */
    private static final int NO_ARROW = -1, WEST = 0, NORTH = 90, EAST = 180,
                             SOUTH = 270;

    /**
     * One key: where it is, what it sends, and every legend it carries.
     *
     * A key either has a {@link #label} - the function keys, whose whole face
     * is one or two centred words - or a {@link #letter} at its foot with a
     * keyword above it, which is every key the Spectrum types with.
     */
    private static final class Face {
        final float width;                    // as declared, before the row
        final int keycode, modifier;          // is stretched out to the margin
        final String name;                    // as the Spectrum names it

        final RectF recess = new RectF();
        final RectF top = new RectF();        // the key itself

        String label, letter, keyword, symbol, plateTop, plateFoot;
        int graphic = NONE;
        int arrow = NO_ARROW;

        /** ENTER's upper half, which the lower half draws as one key with it. */
        Face partner;
        boolean flatTop, flatBottom, hidden;

        Path outline;
        Shader shader;

        Face(float width, int keycode, int modifier, String name) {
            this.width = width;
            this.keycode = keycode;
            this.modifier = modifier;
            this.name = name;
        }

        Face label(String centred) { label = centred; return this; }
        Face letter(String big) { letter = big; return this; }
        Face arrow(int direction) { arrow = direction; return this; }

        /** The BASIC keyword, and the symbol-shift token beside it. */
        Face keyword(String word, String shifted) {
            keyword = word;
            symbol = shifted;
            return this;
        }

        /** The two extended-mode legends, on the plate above the key. */
        Face plate(String first, String second) {
            plateTop = first;
            plateFoot = second;
            return this;
        }

        Face graphic(int mask) { graphic = mask; return this; }
    }

    private final List<Face> faces = new ArrayList<>();
    private final List<List<Face>> bands = new ArrayList<>();

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    PlusPlate() {
        ink.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        ink.setTextAlign(Paint.Align.CENTER);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.MITER);

        layOut();
    }

    // --- the plate --------------------------------------------------------

    private void layOut() {
        place(0,
            fn(70, KeyEvent.KEYCODE_3, SHIFT, "TRUE VIDEO").label("TRUE\nVIDEO"),
            fn(70, KeyEvent.KEYCODE_4, SHIFT, "INV VIDEO").label("INV\nVIDEO"),
            digit(KeyEvent.KEYCODE_1, "1", 1, "!").plate("BLUE", "DEF FN"),
            digit(KeyEvent.KEYCODE_2, "2", 2, "@").plate("RED", "FN"),
            digit(KeyEvent.KEYCODE_3, "3", 3, "#").plate("MGNTA", "LINE"),
            digit(KeyEvent.KEYCODE_4, "4", 4, "$").plate("GREEN", "OPEN #"),
            digit(KeyEvent.KEYCODE_5, "5", 5, "%").plate("CYAN", "CLOSE #"),
            digit(KeyEvent.KEYCODE_6, "6", 6, "&").plate("YELLOW", "MOVE"),
            digit(KeyEvent.KEYCODE_7, "7", 7, "'").plate("WHITE", "ERASE"),
            digit(KeyEvent.KEYCODE_8, "8", 0, "(").plate(null, "POINT"),
            digit(KeyEvent.KEYCODE_9, "9", NONE, ")").plate(null, "CAT"),
            digit(KeyEvent.KEYCODE_0, "0", NONE, "_").letter("Ø")
                                                     .plate("BLACK", "FORMAT"),
            fn(107, KeyEvent.KEYCODE_SPACE, SHIFT, "BREAK").label("BREAK"));

        Face upper = fn(67, KeyEvent.KEYCODE_ENTER, 0, "ENTER");

        place(1,
            fn(103, KeyEvent.KEYCODE_DEL, 0, "DELETE").label("DELETE"),
            fn(74, KeyEvent.KEYCODE_9, SHIFT, "GRAPH").label("GRAPH"),
            letter(KeyEvent.KEYCODE_Q, "Q").keyword("PLOT", "<=").plate("SIN", "ASN"),
            letter(KeyEvent.KEYCODE_W, "W").keyword("DRAW", "<>").plate("COS", "ACS"),
            letter(KeyEvent.KEYCODE_E, "E").keyword("REM", ">=").plate("TAN", "ATN"),
            letter(KeyEvent.KEYCODE_R, "R").keyword("RUN", "<").plate("INT", "VERIFY"),
            letter(KeyEvent.KEYCODE_T, "T").keyword("RAND", ">").plate("RND", "MERGE"),
            letter(KeyEvent.KEYCODE_Y, "Y").keyword("RETURN", "AND").plate("STR$", "["),
            letter(KeyEvent.KEYCODE_U, "U").keyword("IF", "OR").plate("CHR$", "]"),
            letter(KeyEvent.KEYCODE_I, "I").keyword("INPUT", "AT").plate("CODE", "IN"),
            letter(KeyEvent.KEYCODE_O, "O").keyword("POKE", null).plate("PEEK", "OUT"),
            letter(KeyEvent.KEYCODE_P, "P").keyword("PRINT", null).plate("TAB", "©"),
            upper);

        Face enter = fn(127, KeyEvent.KEYCODE_ENTER, 0, "ENTER").label("ENTER");

        place(2,
            fn(108, KeyEvent.KEYCODE_CTRL_LEFT, SHIFT, "EXTEND MODE")
                    .label("EXTEND\nMODE"),
            fn(87, KeyEvent.KEYCODE_ESCAPE, 0, "EDIT").label("EDIT"),
            letter(KeyEvent.KEYCODE_A, "A").keyword("NEW", "STOP").plate("READ", "~"),
            letter(KeyEvent.KEYCODE_S, "S").keyword("SAVE", "NOT").plate("RESTR", "|"),
            letter(KeyEvent.KEYCODE_D, "D").keyword("DIM", "STEP").plate("DATA", "\\"),
            letter(KeyEvent.KEYCODE_F, "F").keyword("FOR", "TO").plate("SGN", "{"),
            letter(KeyEvent.KEYCODE_G, "G").keyword("GOTO", "THEN").plate("ABS", "}"),
            letter(KeyEvent.KEYCODE_H, "H").keyword("GOSUB", "↑").plate("SQR", "CIRCLE"),
            letter(KeyEvent.KEYCODE_J, "J").keyword("LOAD", "−").plate("VAL", "VAL$"),
            letter(KeyEvent.KEYCODE_K, "K").keyword("LIST", "+").plate("LEN", "SCRN$"),
            letter(KeyEvent.KEYCODE_L, "L").keyword("LET", "=").plate("USR", "ATTR"),
            enter);

        merge(upper, enter);

        place(3,
            fn(163, SHIFT, 0, "CAPS SHIFT").label("CAPS SHIFT"),
            fn(74, KeyEvent.KEYCODE_CAPS_LOCK, 0, "CAPS LOCK").label("CAPS\nLOCK"),
            letter(KeyEvent.KEYCODE_Z, "Z").keyword("COPY", ":").plate("LN", "BEEP"),
            letter(KeyEvent.KEYCODE_X, "X").keyword("CLEAR", "£").plate("EXP", "INK"),
            letter(KeyEvent.KEYCODE_C, "C").keyword("CONT", "?").plate("LPRINT", "PAPER"),
            letter(KeyEvent.KEYCODE_V, "V").keyword("CLS", "/").plate("LLIST", "FLASH"),
            letter(KeyEvent.KEYCODE_B, "B").keyword("BORDER", "*").plate("BIN", "BRIGHT"),
            letter(KeyEvent.KEYCODE_N, "N").keyword("NEXT", null).plate("INKEY$", "OVER"),
            letter(KeyEvent.KEYCODE_M, "M").keyword("PAUSE", null).plate("PI", "INVERS"),
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
     * The last key in a row runs out to the right margin whatever it asked
     * for, so every row ends flush the way the plate does, and the rounding
     * left over by a fractional pitch never shows up as a ragged edge.
     */
    private void place(int band, Face... row) {
        float top = FIRST + band * PITCH;
        float bottom = top + RECESS;

        // The bottom row carries no plate legends, so its keys are taller.
        float face = top + (band == 4 ? 14f : ABOVE);

        List<Face> keys = new ArrayList<>();
        float x = LEFT;

        for (int i = 0; i < row.length; i++) {
            float width = i == row.length - 1 ? RIGHT - x : row[i].width;

            row[i].recess.set(x, top, x + width, bottom);
            row[i].top.set(x + INSET, face, x + width - INSET, bottom - 3f);

            keys.add(row[i]);
            faces.add(row[i]);
            x += width + WALL;
        }

        bands.add(keys);
    }

    /**
     * Joins ENTER's two halves into the one Γ-shaped key it is.
     *
     * The recesses meet, the two key tops meet at the wall that would have
     * been between them, and the corners along that seam are square so the
     * union of the two does not come out notched.
     */
    private static void merge(Face upper, Face lower) {
        float seam = (upper.recess.bottom + lower.recess.top) / 2f;

        // Overlapping rather than meeting: two rounded rectangles that merely
        // touch leave a nick of plate showing at each end of the join.
        upper.recess.bottom = lower.recess.top + 8f;
        upper.top.bottom = seam;
        lower.top.top = seam;

        upper.flatBottom = true;
        lower.flatTop = true;
        upper.hidden = true;
        lower.partner = upper;
    }

    /**
     * The key table the view presses, in the same coordinates.
     *
     * A row's extent is its recess, not its keys, so that the gaps the view
     * hands out to its neighbours are the walls between the recesses.
     */
    SpectrumKeyboardView.Row[] rows() {
        SpectrumKeyboardView.Row[] rows = new SpectrumKeyboardView.Row[bands.size()];

        for (int r = 0; r < bands.size(); r++) {
            List<Face> band = bands.get(r);
            SpectrumKeyboardView.Key[] keys =
                    new SpectrumKeyboardView.Key[band.size()];

            for (int k = 0; k < band.size(); k++) {
                Face face = band.get(k);
                keys[k] = new SpectrumKeyboardView.Key(
                        face.top, face.keycode, face.modifier, face.name);
            }

            rows[r] = new SpectrumKeyboardView.Row(
                    Math.round(band.get(0).recess.top),
                    Math.round(band.get(0).recess.bottom), keys);
        }
        return rows;
    }

    // --- drawing ----------------------------------------------------------

    /** Draws the plate in its own 1040x413 space; the caller scales. */
    void draw(Canvas canvas) {
        canvas.drawColor(PLATE);

        fill.setColor(FLOOR);
        for (Face face : faces) {
            canvas.drawRoundRect(face.recess, 4f, 4f, fill);
        }

        for (Face face : faces) {
            if (face.hidden) continue;
            key(canvas, face);
        }

        for (Face face : faces) legends(canvas, face);
    }

    private void key(Canvas canvas, Face face) {
        if (face.outline == null) {
            face.outline = outline(face);
            face.shader = new LinearGradient(0, face.top.top, 0, face.top.bottom,
                                             KEY_TOP, KEY_FOOT,
                                             Shader.TileMode.CLAMP);
        }

        // The key's own side wall, showing under its foot.
        canvas.save();
        canvas.translate(0, 2.5f);
        fill.setColor(SKIRT);
        fill.setShader(null);
        canvas.drawPath(face.outline, fill);
        canvas.restore();

        fill.setShader(face.shader);
        canvas.drawPath(face.outline, fill);
        fill.setShader(null);
    }

    /**
     * A key top: flat across, and rounded off at the foot like the real one.
     */
    private static Path outline(Face face) {
        Path path = shape(face.top, face.flatTop, face.flatBottom);
        if (face.partner != null) {
            path.op(shape(face.partner.top, face.partner.flatTop,
                          face.partner.flatBottom), Path.Op.UNION);
        }
        return path;
    }

    private static Path shape(RectF box, boolean flatTop, boolean flatBottom) {
        float head = flatTop ? 0f : 6f;
        float foot = flatBottom ? 0f
                : Math.min(box.height() * 0.5f, box.width() * 0.45f);

        Path path = new Path();
        path.moveTo(box.left + head, box.top);
        path.lineTo(box.right - head, box.top);
        if (head > 0) path.quadTo(box.right, box.top, box.right, box.top + head);
        path.lineTo(box.right, box.bottom - foot);
        if (foot > 0) {
            path.quadTo(box.right, box.bottom, box.right - foot, box.bottom);
        }
        path.lineTo(box.left + foot, box.bottom);
        if (foot > 0) {
            path.quadTo(box.left, box.bottom, box.left, box.bottom - foot);
        }
        path.lineTo(box.left, box.top + head);
        if (head > 0) path.quadTo(box.left, box.top, box.left + head, box.top);
        path.close();
        return path;
    }

    private void legends(Canvas canvas, Face face) {
        RectF box = face.top;
        float room = face.recess.width() - 6f;

        if (face.plateTop != null) {
            write(canvas, face.plateTop, face.recess.centerX(),
                  face.recess.top + 12f, 11f, room, LEGEND);
        }
        if (face.plateFoot != null) {
            write(canvas, face.plateFoot, face.recess.centerX(),
                  face.recess.top + 23.5f, 11f, room, LEGEND);
        }

        if (face.hidden) return;                 // ENTER's upper half
        if (face.arrow != NO_ARROW) { arrow(canvas, box, face.arrow); return; }

        if (face.label != null) {
            String[] lines = face.label.split("\n");
            float size = lines.length > 1 ? 14f : 15.5f;

            // A single punctuation mark is a legend in its own right, and the
            // plate draws it as large as a letter.
            if (lines.length == 1 && lines[0].length() == 1
                    && !Character.isLetterOrDigit(lines[0].charAt(0))) {
                write(canvas, lines[0], box.centerX(), box.centerY() + 9f, 24f,
                      box.width() - 8f, WHITE);
                return;
            }

            float first = box.centerY() + (lines.length > 1 ? -2f : 5.5f);
            for (int i = 0; i < lines.length; i++) {
                write(canvas, lines[i], box.centerX(), first + i * 13f, size,
                      box.width() - 8f, WHITE);
            }
            return;
        }

        boolean word = face.symbol != null
                    && Character.isLetter(face.symbol.charAt(0));

        if (face.graphic != NONE || face.keyword == null) {
            graphic(canvas, face);
        } else {
            write(canvas, face.keyword, box.centerX(), box.top + 11f, 11f,
                  box.width() - 6f, WHITE);
            if (face.symbol != null && word) {
                write(canvas, face.symbol, box.centerX(), box.top + 21.5f, 11f,
                      box.width() - 6f, WHITE);
            }
        }

        // A symbol-shift character rides at the lower right of the keyword;
        // only a whole word gets a line to itself.
        if (face.symbol != null && !word && face.graphic == NONE
                && face.keyword != null) {
            ink.setTextAlign(Paint.Align.RIGHT);
            write(canvas, face.symbol, box.right - 5f, box.top + 22f, 12.5f,
                  box.width() / 2f, WHITE);
            ink.setTextAlign(Paint.Align.CENTER);
        }

        if (face.letter != null) {
            write(canvas, face.letter, box.centerX(), box.bottom - 4.5f, 18f,
                  box.width() - 6f, WHITE);
        }
    }

    /**
     * A digit's pair: the block graphic and the symbol-shift character, side
     * by side and centred together. 9 and 0 have no graphic, only the
     * character.
     */
    private void graphic(Canvas canvas, Face face) {
        RectF box = face.top;
        float cell = 12f;

        ink.setTextSize(12.5f);
        float symbol = face.symbol != null ? ink.measureText(face.symbol) : 0f;
        float wide = (face.graphic == NONE ? 0f : cell + 5f) + symbol;
        float x = box.centerX() - wide / 2f;
        float top = box.top + 4f;

        if (face.graphic != NONE) {
            stroke.setColor(WHITE);
            stroke.setStrokeWidth(1.6f);
            canvas.drawRect(x, top, x + cell, top + cell, stroke);

            fill.setColor(WHITE);
            float half = cell / 2f;
            if ((face.graphic & 2) != 0) quadrant(canvas, x, top, half, 0, 0);
            if ((face.graphic & 1) != 0) quadrant(canvas, x, top, half, 1, 0);
            if ((face.graphic & 8) != 0) quadrant(canvas, x, top, half, 0, 1);
            if ((face.graphic & 4) != 0) quadrant(canvas, x, top, half, 1, 1);

            x += cell + 5f;
        }

        if (face.symbol != null) {
            ink.setTextAlign(Paint.Align.LEFT);
            write(canvas, face.symbol, x, top + cell, 12.5f, symbol + 1f, WHITE);
            ink.setTextAlign(Paint.Align.CENTER);
        }
    }

    private void quadrant(Canvas canvas, float x, float y, float half,
                          int column, int row) {
        float inset = 1.6f;
        canvas.drawRect(x + column * half + (column == 0 ? inset : 0),
                        y + row * half + (row == 0 ? inset : 0),
                        x + (column + 1) * half - (column == 1 ? inset : 0),
                        y + (row + 1) * half - (row == 1 ? inset : 0), fill);
    }

    /** One of the four cursor keys: a hollow arrow, as the plate has them. */
    private void arrow(Canvas canvas, RectF box, int direction) {
        float wide = 30f, tall = 20f;

        Path path = new Path();
        path.moveTo(-wide / 2f, 0f);
        path.lineTo(-wide / 6f, -tall / 2f);
        path.lineTo(-wide / 6f, -tall / 5f);
        path.lineTo(wide / 2f, -tall / 5f);
        path.lineTo(wide / 2f, tall / 5f);
        path.lineTo(-wide / 6f, tall / 5f);
        path.lineTo(-wide / 6f, tall / 2f);
        path.close();

        canvas.save();
        canvas.translate(box.centerX(), box.centerY() + 3f);
        canvas.rotate(direction);
        stroke.setColor(WHITE);
        stroke.setStrokeWidth(1.8f);
        canvas.drawPath(path, stroke);
        canvas.restore();
    }

    /** Draws one legend, shrunk to fit if the key is too narrow for it. */
    private void write(Canvas canvas, String text, float x, float baseline,
                       float size, float room, int colour) {
        ink.setColor(colour);
        ink.setTextSize(size);

        float width = ink.measureText(text);
        if (width > room) ink.setTextSize(size * room / width);

        canvas.drawText(text, x, baseline, ink);
    }
}
