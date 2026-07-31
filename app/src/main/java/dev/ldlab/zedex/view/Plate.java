package dev.ldlab.zedex.view;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.List;

/**
 * A Spectrum keyboard, drawn rather than photographed.
 *
 * One subclass per machine, because the two agree about almost nothing: the 48K
 * prints its keywords around the keys on a black case in three colours, the
 * 128K's plate keeps every legend white and inside a recess. Here is what they
 * do share — the key and its shadow, legend text fitted to the room there is,
 * the block graphics, the cursor arrows, and the table of rectangles the view
 * presses.
 *
 * A slim plate is the same keys with the BASIC taken off: no keywords, no
 * extended-mode legends, nothing but what a key sends by itself or with symbol
 * shift, in rows short enough to leave the picture some room.
 *
 * Rectangles are in the subclass's own space, which the view scales; being
 * vector work it is as sharp as the screen allows.
 */
abstract class Plate {

    /** Which way an arrow points, as a rotation of the westward one. */
    static final int NO_ARROW = -1, WEST = 0, NORTH = 90, EAST = 180, SOUTH = 270;

    /** No block graphic on this key. */
    static final int NONE = -1;

    static final int WHITE = 0xfff6f6f6;

    /**
     * One key: where it is, what it sends, and every legend it carries.
     *
     * A key either has a {@link #label} — the function keys, whose whole face is
     * one or two centred words — or a {@link #letter} with the rest arranged
     * around it. Which legends are drawn, and where, is the subclass's business.
     */
    static final class Face {
        final float width;                 // as asked for, before a row is fitted
        final int keycode, modifier;
        final String name;                 // as the Spectrum names it

        final RectF band = new RectF();    // all this key owns, legends included
        final RectF top = new RectF();     // the key itself

        String label, letter, keyword, symbol;
        String extended, extendedShifted;  // SIN and ASN; BLUE and DEF FN
        int extendedInk = WHITE;           // a colour name is in its own colour
        String capsLabel;                  // what CAPS SHIFT with it does...
        int capsArrow = NO_ARROW;          // ...or the arrow it gives
        int graphic = NONE;
        int arrow = NO_ARROW;              // this key is an arrow of its own

        /** ENTER's other half: one key across two rows. */
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
        Face graphic(int mask) { graphic = mask; return this; }
        Face caps(String does) { capsLabel = does; return this; }
        Face capsArrow(int direction) { capsArrow = direction; return this; }
        Face ink(int colour) { extendedInk = colour; return this; }

        /** The BASIC keyword, and the symbol-shift token beside it. */
        Face keyword(String word, String shifted) {
            keyword = word;
            symbol = shifted;
            return this;
        }

        /** Extended mode, and extended mode with symbol shift. */
        Face extended(String plain, String shifted) {
            extended = plain;
            extendedShifted = shifted;
            return this;
        }

        /** Whether the symbol-shift token is a word rather than a character. */
        boolean shiftedWord() {
            return symbol != null && Character.isLetter(symbol.charAt(0));
        }
    }

    final boolean slim;

    final List<Face> faces = new ArrayList<>();
    private final List<List<Face>> bands = new ArrayList<>();

    final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    Plate(boolean slim) {
        this.slim = slim;

        ink.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        ink.setTextAlign(Paint.Align.CENTER);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.MITER);
    }

    // --- the layout -------------------------------------------------------

    abstract int width();

    abstract int height();

    float aspect() {
        return width() / (float) height();
    }

    /** Registers a row, once its rectangles are set. */
    void band(Face... row) {
        List<Face> keys = new ArrayList<>();
        for (Face face : row) {
            keys.add(face);
            faces.add(face);
        }
        bands.add(keys);
    }

    /**
     * Joins ENTER's two halves into the one key it is.
     *
     * Their bands meet, the key tops meet where the gap between them was, and
     * the corners along that seam are squared off because the union of two
     * round-footed shapes comes out notched.
     */
    static void merge(Face upper, Face lower) {
        float seam = (upper.band.bottom + lower.band.top) / 2f;

        // Overlapping rather than meeting: two rounded rectangles that merely
        // touch leave a nick showing at each end of the join.
        upper.band.bottom = lower.band.top + 8f;
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
     * A row's extent is the band, not the keys, so the gaps the view hands out
     * to a key's neighbours are the whole surface between them: a legend
     * belongs to the key it describes, and a fingertip that lands on SIN has
     * pressed Q.
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
                    Math.round(band.get(0).band.top),
                    Math.round(band.get(0).band.bottom), keys);
        }
        return rows;
    }

    // --- drawing ----------------------------------------------------------

    void draw(Canvas canvas) {
        canvas.drawColor(background());

        for (Face face : faces) {
            if (!face.hidden) well(canvas, face);
        }
        for (Face face : faces) {
            if (!face.hidden) key(canvas, face);
        }
        for (Face face : faces) legends(canvas, face);
    }

    abstract int background();

    /** What the key sits in, if the machine has anything there. */
    void well(Canvas canvas, Face face) { }

    abstract void legends(Canvas canvas, Face face);

    abstract int keyLit();

    abstract int keyFoot();

    abstract int keySkirt();

    /** The radius of a key's top corners, and of the ones at its foot. */
    abstract float head();

    abstract float foot(RectF box);

    private void key(Canvas canvas, Face face) {
        if (face.outline == null) {
            face.outline = outline(face);
            face.shader = new LinearGradient(0, face.top.top, 0, face.top.bottom,
                                             keyLit(), keyFoot(),
                                             Shader.TileMode.CLAMP);
        }

        // The key's own side wall, showing under its foot.
        canvas.save();
        canvas.translate(0, 2.5f);
        fill.setShader(null);
        fill.setColor(keySkirt());
        canvas.drawPath(face.outline, fill);
        canvas.restore();

        fill.setShader(face.shader);
        canvas.drawPath(face.outline, fill);
        fill.setShader(null);
    }

    private Path outline(Face face) {
        Path path = shape(face.top, face.flatTop, face.flatBottom);
        if (face.partner != null) {
            path.op(shape(face.partner.top, face.partner.flatTop,
                          face.partner.flatBottom), Path.Op.UNION);
        }
        return path;
    }

    private Path shape(RectF box, boolean flatTop, boolean flatBottom) {
        float head = flatTop ? 0f : head();
        float foot = flatBottom ? 0f : foot(box);

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

    // --- legends ----------------------------------------------------------

    /** Draws one legend, shrunk to fit if the key is too narrow for it. */
    void write(Canvas canvas, String text, float x, float baseline, float size,
               float room, int colour) {
        ink.setColor(colour);
        ink.setTextSize(size);

        float width = ink.measureText(text);
        if (width > room) ink.setTextSize(size * room / width);

        canvas.drawText(text, x, baseline, ink);
    }

    void write(Canvas canvas, String text, Paint.Align align, float x,
               float baseline, float size, float room, int colour) {
        ink.setTextAlign(align);
        write(canvas, text, x, baseline, size, room, colour);
        ink.setTextAlign(Paint.Align.CENTER);
    }

    /**
     * A colour name, printed in its colour — except BLACK, which is a white
     * box with the name knocked out of it, as it is on the machine.
     */
    void colourName(Canvas canvas, String name, float centre, float baseline,
                    float size, float room, int colour) {
        if (colour != 0xff000000) {
            write(canvas, name, centre, baseline, size, room, colour);
            return;
        }

        ink.setTextSize(size);
        float wide = Math.min(ink.measureText(name), room) + 6f;

        fill.setColor(WHITE);
        canvas.drawRect(centre - wide / 2f, baseline - size + 1f,
                        centre + wide / 2f, baseline + 2.5f, fill);
        write(canvas, name, centre, baseline, size, room, 0xff101010);
    }

    /**
     * A character cell's worth of block graphic, as a mask of its quadrants:
     * top-right 1, top-left 2, bottom-right 4, bottom-left 8.
     */
    void graphic(Canvas canvas, int mask, float left, float top, float cell,
                 int colour) {
        stroke.setColor(colour);
        stroke.setStrokeWidth(cell / 8f);
        canvas.drawRect(left, top, left + cell, top + cell, stroke);

        fill.setColor(colour);
        float half = cell / 2f;
        if ((mask & 2) != 0) quadrant(canvas, left, top, half, 0, 0);
        if ((mask & 1) != 0) quadrant(canvas, left, top, half, 1, 0);
        if ((mask & 8) != 0) quadrant(canvas, left, top, half, 0, 1);
        if ((mask & 4) != 0) quadrant(canvas, left, top, half, 1, 1);
    }

    private void quadrant(Canvas canvas, float x, float y, float half,
                          int column, int row) {
        float inset = half / 4f;
        canvas.drawRect(x + column * half + (column == 0 ? inset : 0),
                        y + row * half + (row == 0 ? inset : 0),
                        x + (column + 1) * half - (column == 1 ? inset : 0),
                        y + (row + 1) * half - (row == 1 ? inset : 0), fill);
    }

    /** A hollow arrow, the way both keyboards draw a cursor key. */
    void arrow(Canvas canvas, float centreX, float centreY, float wide,
               float tall, int direction, int colour) {
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
        canvas.translate(centreX, centreY);
        canvas.rotate(direction);
        stroke.setColor(colour);
        stroke.setStrokeWidth(tall / 11f);
        canvas.drawPath(path, stroke);
        canvas.restore();
    }
}
