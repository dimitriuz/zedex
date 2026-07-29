package dev.ldlab.zedex;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.Button;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The ZX Spectrum keyboard, drawn from Fuse's own keyboard.png.
 *
 * That image carries every legend a Spectrum key has — the BASIC keyword, the
 * symbol-shift character, the colour, the extended-mode token — which is what
 * makes typing Spectrum BASIC possible at all. The key rectangles below were
 * measured off the image, so they are in its 541x201 coordinate space and are
 * scaled to wherever the bitmap lands.
 *
 * Keys are reported as Android keycodes and travel the same path as a physical
 * keyboard: {@link FuseNative#key} queues them and Fuse's keysym table turns
 * them into Spectrum keys. Caps Shift is SHIFT_LEFT and Symbol Shift is
 * CTRL_LEFT, both of which Fuse already maps.
 *
 * Presses are real presses, so two fingers give you a genuine shifted key.
 * Because that is awkward one-handed, holding either shift latches it until it
 * is tapped again.
 */
public class SpectrumKeyboardView extends View {

    private static final String TAG = "Zedex";

    /**
     * Which machine's keyboard is drawn.
     *
     * Two pictures with a table of key rectangles each, in their own image's
     * pixels: everything else - the presses, the latching, the accessibility
     * nodes, the scaling - is the same for both, because a skin is only a
     * picture and where its keys are.
     *
     * The rubber one is Fuse's own artwork, which the app already installs; the
     * 128K is a photograph of a real plate, cropped to the keys - the badge and
     * the heatsink beside them are inches of nothing to press.
     */
    enum Skin {
        RUBBER("rubber", "ZX Spectrum 48K", "fuse/keyboard.png", 541f / 201f),
        PLUS("plus", "ZX Spectrum 128K", "skins/spectrum128.webp", 1040f / 413f),

        /**
         * Not a picture at all: the device's own input method types instead, and
         * this keyboard is not drawn. It is in the same list because it is the
         * same choice - which keyboard you use - and it has no asset and no key
         * table because it has no keys of its own.
         */
        SYSTEM("system", "Android keyboard", null, 541f / 201f);

        final String value;             /* as stored in the preferences */
        final String title;
        final String asset;
        final float aspect;             /* before the image has loaded */

        Skin(String value, String title, String asset, float aspect) {
            this.value = value;
            this.title = title;
            this.asset = asset;
            this.aspect = aspect;
        }

        Row[] rows() {
            return this == PLUS ? PLUS_ROWS : RUBBER_ROWS;
        }

        /** Whether this one is drawn here rather than by Android. */
        boolean drawn() {
            return asset != null;
        }

        static Skin of(String stored) {
            for (Skin skin : values()) {
                if (skin.value.equals(stored)) return skin;
            }
            return RUBBER;
        }
    }

    private Skin skin = Skin.RUBBER;
    private Row[] rows = RUBBER_ROWS;

    /** Fuse's own artwork, for before anything has been loaded. */
    static final float NATURAL_ASPECT = 541f / 201f;

    /** How long a shift must be held before it latches. */
    private static final long LATCH_MS = 400;

    private static final class Key {
        final Rect image = new Rect();       // as drawn, in image pixels
        final Rect touch = new Rect();       // expanded to swallow the gaps
        final int keycode;

        /**
         * Held down with it, or zero. The 128K's plate has keys the machine
         * does not: TRUE VIDEO is CAPS SHIFT and 3, GRAPH is CAPS SHIFT and 9.
         * Most of the rest turn out to be single keycodes Fuse already maps -
         * Escape is EDIT, Caps Lock is CAPS LOCK, Backspace is DELETE, and the
         * arrows and the punctuation are what Fuse does with a PC keyboard -
         * so only five keys need this.
         */
        final int modifier;

        final String name;
        final boolean canLatch;
        boolean pressed;
        boolean latched;

        Key(int left, int right, int keycode, int modifier, String name) {
            this.image.left = left;
            this.image.right = right;
            this.keycode = keycode;
            this.modifier = modifier;
            this.name = name != null ? name : nameOf( keycode );

            // A shift that is part of a combination is not a shift being
            // pressed: EXTEND MODE is CAPS SHIFT and SYMBOL SHIFT together,
            // and latching it would leave the machine in extended mode.
            this.canLatch = modifier == 0
                         && ( keycode == KeyEvent.KEYCODE_SHIFT_LEFT
                           || keycode == KeyEvent.KEYCODE_CTRL_LEFT );
        }

        /** What the key is called on the Spectrum, not what Android calls it. */
        private static String nameOf(int keycode) {
            switch (keycode) {
                case KeyEvent.KEYCODE_ENTER: return "ENTER";
                case KeyEvent.KEYCODE_SPACE: return "BREAK SPACE";
                case KeyEvent.KEYCODE_SHIFT_LEFT: return "CAPS SHIFT";
                case KeyEvent.KEYCODE_CTRL_LEFT: return "SYMBOL SHIFT";
                default:
                    return KeyEvent.keyCodeToString(keycode)
                            .substring("KEYCODE_".length());
            }
        }
    }

    private static final class Row {
        final int top, bottom;
        final Key[] keys;

        Row(int top, int bottom, Key... keys) {
            this.top = top;
            this.bottom = bottom;
            this.keys = keys;
        }
    }

    private static final Row[] RUBBER_ROWS = {
        row(20, 43,
            key(10, 43, KeyEvent.KEYCODE_1),
            key(60, 93, KeyEvent.KEYCODE_2),
            key(110, 143, KeyEvent.KEYCODE_3),
            key(160, 193, KeyEvent.KEYCODE_4),
            key(210, 243, KeyEvent.KEYCODE_5),
            key(260, 293, KeyEvent.KEYCODE_6),
            key(310, 343, KeyEvent.KEYCODE_7),
            key(360, 393, KeyEvent.KEYCODE_8),
            key(410, 443, KeyEvent.KEYCODE_9),
            key(460, 493, KeyEvent.KEYCODE_0)),
        row(69, 92,
            key(35, 68, KeyEvent.KEYCODE_Q),
            key(85, 118, KeyEvent.KEYCODE_W),
            key(135, 168, KeyEvent.KEYCODE_E),
            key(185, 218, KeyEvent.KEYCODE_R),
            key(235, 268, KeyEvent.KEYCODE_T),
            key(285, 318, KeyEvent.KEYCODE_Y),
            key(335, 368, KeyEvent.KEYCODE_U),
            key(385, 418, KeyEvent.KEYCODE_I),
            key(435, 468, KeyEvent.KEYCODE_O),
            key(485, 518, KeyEvent.KEYCODE_P)),
        row(118, 141,
            key(47, 80, KeyEvent.KEYCODE_A),
            key(97, 130, KeyEvent.KEYCODE_S),
            key(147, 180, KeyEvent.KEYCODE_D),
            key(197, 230, KeyEvent.KEYCODE_F),
            key(247, 280, KeyEvent.KEYCODE_G),
            key(297, 330, KeyEvent.KEYCODE_H),
            key(347, 380, KeyEvent.KEYCODE_J),
            key(397, 430, KeyEvent.KEYCODE_K),
            key(447, 480, KeyEvent.KEYCODE_L),
            key(497, 530, KeyEvent.KEYCODE_ENTER)),
        row(167, 190,
            key(10, 55, KeyEvent.KEYCODE_SHIFT_LEFT),
            key(72, 105, KeyEvent.KEYCODE_Z),
            key(122, 155, KeyEvent.KEYCODE_X),
            key(172, 205, KeyEvent.KEYCODE_C),
            key(222, 255, KeyEvent.KEYCODE_V),
            key(272, 305, KeyEvent.KEYCODE_B),
            key(322, 355, KeyEvent.KEYCODE_N),
            key(372, 405, KeyEvent.KEYCODE_M),
            key(422, 455, KeyEvent.KEYCODE_CTRL_LEFT),
            key(472, 530, KeyEvent.KEYCODE_SPACE)),
    };

    /**
     * The 128K's plate, measured off a photograph of one.
     *
     * The keys are on a strict grid - a uniform 111.8 pixels of pitch across
     * every row of the original, which is what made this a calculation rather
     * than sixty measurements. Only the wide keys and the L of ENTER are
     * spelled out, and ENTER appears twice because it is one key in two places.
     */
    private static final Row[] PLUS_ROWS = {
        row(45, 98,
            shifted(23, 89, KeyEvent.KEYCODE_3, "TRUE VIDEO"),
            shifted(97, 164, KeyEvent.KEYCODE_4, "INV VIDEO"),
            named(172, 239, KeyEvent.KEYCODE_1, "1"),
            named(247, 313, KeyEvent.KEYCODE_2, "2"),
            named(321, 388, KeyEvent.KEYCODE_3, "3"),
            named(395, 462, KeyEvent.KEYCODE_4, "4"),
            named(470, 537, KeyEvent.KEYCODE_5, "5"),
            named(545, 611, KeyEvent.KEYCODE_6, "6"),
            named(619, 686, KeyEvent.KEYCODE_7, "7"),
            named(694, 761, KeyEvent.KEYCODE_8, "8"),
            named(768, 835, KeyEvent.KEYCODE_9, "9"),
            named(843, 909, KeyEvent.KEYCODE_0, "0"),
            shifted(918, 1021, KeyEvent.KEYCODE_SPACE, "BREAK")),
        row(127, 171,
            named(23, 128, KeyEvent.KEYCODE_DEL, "DELETE"),
            shifted(133, 208, KeyEvent.KEYCODE_9, "GRAPH"),
            named(210, 277, KeyEvent.KEYCODE_Q, "Q"),
            named(285, 351, KeyEvent.KEYCODE_W, "W"),
            named(359, 426, KeyEvent.KEYCODE_E, "E"),
            named(433, 500, KeyEvent.KEYCODE_R, "R"),
            named(508, 575, KeyEvent.KEYCODE_T, "T"),
            named(583, 649, KeyEvent.KEYCODE_Y, "Y"),
            named(657, 724, KeyEvent.KEYCODE_U, "U"),
            named(732, 799, KeyEvent.KEYCODE_I, "I"),
            named(806, 873, KeyEvent.KEYCODE_O, "O"),
            named(881, 947, KeyEvent.KEYCODE_P, "P"),
            named(953, 1021, KeyEvent.KEYCODE_ENTER, "ENTER")),
        row(200, 245,
            shifted(23, 128, KeyEvent.KEYCODE_CTRL_LEFT, "EXTEND MODE"),
            named(133, 225, KeyEvent.KEYCODE_ESCAPE, "EDIT"),
            named(228, 295, KeyEvent.KEYCODE_A, "A"),
            named(303, 369, KeyEvent.KEYCODE_S, "S"),
            named(377, 444, KeyEvent.KEYCODE_D, "D"),
            named(451, 518, KeyEvent.KEYCODE_F, "F"),
            named(526, 593, KeyEvent.KEYCODE_G, "G"),
            named(601, 667, KeyEvent.KEYCODE_H, "H"),
            named(675, 742, KeyEvent.KEYCODE_J, "J"),
            named(750, 817, KeyEvent.KEYCODE_K, "K"),
            named(824, 891, KeyEvent.KEYCODE_L, "L"),
            named(900, 1021, KeyEvent.KEYCODE_ENTER, "ENTER")),
        row(275, 321,
            named(23, 181, KeyEvent.KEYCODE_SHIFT_LEFT, "CAPS SHIFT"),
            named(190, 259, KeyEvent.KEYCODE_CAPS_LOCK, "CAPS LOCK"),
            named(267, 333, KeyEvent.KEYCODE_Z, "Z"),
            named(341, 408, KeyEvent.KEYCODE_X, "X"),
            named(416, 483, KeyEvent.KEYCODE_C, "C"),
            named(490, 557, KeyEvent.KEYCODE_V, "V"),
            named(565, 631, KeyEvent.KEYCODE_B, "B"),
            named(639, 706, KeyEvent.KEYCODE_N, "N"),
            named(714, 781, KeyEvent.KEYCODE_M, "M"),
            named(789, 855, KeyEvent.KEYCODE_PERIOD, "."),
            named(860, 1021, KeyEvent.KEYCODE_SHIFT_LEFT, "CAPS SHIFT")),
        row(340, 397,
            named(23, 95, KeyEvent.KEYCODE_CTRL_LEFT, "SYMBOL SHIFT"),
            named(97, 163, KeyEvent.KEYCODE_SEMICOLON, ";"),
            named(171, 238, KeyEvent.KEYCODE_APOSTROPHE, "QUOTE"),
            named(246, 313, KeyEvent.KEYCODE_DPAD_LEFT, "LEFT"),
            named(320, 387, KeyEvent.KEYCODE_DPAD_RIGHT, "RIGHT"),
            named(395, 727, KeyEvent.KEYCODE_SPACE, "SPACE"),
            named(730, 797, KeyEvent.KEYCODE_DPAD_UP, "UP"),
            named(805, 871, KeyEvent.KEYCODE_DPAD_DOWN, "DOWN"),
            named(879, 946, KeyEvent.KEYCODE_COMMA, ","),
            named(948, 1021, KeyEvent.KEYCODE_CTRL_LEFT, "SYMBOL SHIFT")),
    };

    private static Key key(int left, int right, int keycode) {
        return new Key(left, right, keycode, 0, null);
    }

    /** A key whose legend is not what Android calls its keycode. */
    private static Key named(int left, int right, int keycode, String name) {
        return new Key(left, right, keycode, 0, name);
    }

    /** One of the 128K's keys that the machine reaches with CAPS SHIFT held. */
    private static Key shifted(int left, int right, int keycode, String name) {
        return new Key(left, right, keycode, KeyEvent.KEYCODE_SHIFT_LEFT, name);
    }

    private static Row row(int top, int bottom, Key... keys) {
        return new Row(top, bottom, keys);
    }

    private final SparseArray<Key> pointers = new SparseArray<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Paint bitmapPaint = new Paint();
    private final Paint pressedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint latchedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Bitmap keyboard;
    private final Rect destination = new Rect();
    private final RectF highlight = new RectF();
    private float scale = 1f;

    public SpectrumKeyboardView(Context context) {
        this(context, null);
    }

    public SpectrumKeyboardView(Context context, AttributeSet attrs) {
        super(context, attrs);

        // The image is 541px wide and will be drawn several times that, so
        // keep it sharp rather than smearing its one-pixel legends.
        bitmapPaint.setFilterBitmap(false);
        pressedPaint.setColor(0x9900b0c8);
        latchedPaint.setColor(0x99ffb000);

        // Read here rather than pushed in, so that every keyboard in the app
        // is the same one: the emulator's, and the profile editor's.
        setSkin(Skin.of(context.getSharedPreferences(SettingsActivity.PREFS,
                                                     Context.MODE_PRIVATE)
                        .getString(SettingsActivity.KEY_KEYBOARD_SKIN, null)));

        setBackgroundColor(0xff1b1b1b);
    }

    /**
     * Draws a different machine's keyboard.
     *
     * The picture and the key table go together and neither means anything
     * without the other, so they change in one step - and the aspect changes
     * with them, which is why this asks for a layout and not just a redraw.
     */
    void setSkin(Skin wanted) {
        releaseEverything();

        skin = wanted;
        rows = wanted.rows();

        keyboard = null;

        if (wanted.drawn()) {
            try (InputStream in = getContext().getAssets().open(wanted.asset)) {
                keyboard = BitmapFactory.decodeStream(in);
            } catch (IOException e) {
                Log.e(TAG, "cannot load " + wanted.asset, e);
            }
        }

        computeTouchAreas();
        fit(getWidth(), getHeight());
        requestLayout();
        invalidate();

        // Every key is a virtual accessibility node, and they have all just
        // moved or changed their names, so say so. Once when a skin changes,
        // which is nothing like the continuous churn that once took the whole
        // instrumentation suite down.
        //
        // Worth knowing: this is enough for a screen reader and not enough for
        // UI Automator, which caches a window's tree and went on reporting the
        // other skin's keys until the app was relaunched. Nothing the app can
        // say clears that cache - a blunter attempt at it, removing this view
        // from the tree and putting it back, made no difference either.
        if (getParent() != null) {
            getParent().notifySubtreeAccessibilityStateChanged(
                    this, this, AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE);
        }

    }

    Skin skin() {
        return skin;
    }

    /** Everything up, for when the keys under the fingers are about to change. */
    private void releaseEverything() {
        for (Row row : rows) {
            for (Key key : row.keys) {
                if (key.pressed || key.latched) {
                    key.latched = false;
                    key.pressed = false;
                    send(key, false);
                }
            }
        }
        pointers.clear();
    }

    /**
     * Grows each key to meet its neighbours, so the gaps between them are not
     * dead. Fingers are wider than the 6px gutters in the artwork.
     */
    private void computeTouchAreas() {
        int imageHeight = keyboard != null ? keyboard.getHeight() : 201;
        int imageWidth = keyboard != null ? keyboard.getWidth() : 541;

        for (int r = 0; r < rows.length; r++) {
            Row row = rows[r];
            int top = r == 0 ? 0 : (rows[r - 1].bottom + row.top) / 2;
            int bottom = r == rows.length - 1 ? imageHeight
                                              : (row.bottom + rows[r + 1].top) / 2;

            for (int k = 0; k < row.keys.length; k++) {
                Key key = row.keys[k];
                key.image.top = row.top;
                key.image.bottom = row.bottom;
                key.touch.top = top;
                key.touch.bottom = bottom;
                key.touch.left = k == 0 ? 0
                        : (row.keys[k - 1].image.right + key.image.left) / 2;
                key.touch.right = k == row.keys.length - 1 ? imageWidth
                        : (key.image.right + row.keys[k + 1].image.left) / 2;
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);

        // An exact height is a box the parent has already decided on - a
        // template capping the keyboard, say - and is taken as given. Only
        // when left to choose does the natural aspect apply, and then a
        // landscape window would otherwise hand the keyboard four fifths of
        // the height.
        int height = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(heightMeasureSpec)
                : Math.round(width / aspect());

        setMeasuredDimension(width, height);
    }

    /** Width over height of the artwork, so a parent can shape its box. */
    float aspect() {
        if (keyboard == null || keyboard.getHeight() == 0) return skin.aspect;
        return keyboard.getWidth() / (float) keyboard.getHeight();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        fit(width, height);
    }

    /**
     * Where the artwork lands in the view, and by how much it is scaled.
     *
     * Called on a size change and <b>also when the skin changes</b>, which is not
     * the same event: sideways the keyboard's box is capped at a fraction of the
     * window and is therefore the same size for either skin, so nothing resized
     * and this was left holding the other artwork's scale. The 128K's picture was
     * then stretched to the 48K's shape, every hit test was off by the difference,
     * and the highlight landed between the keys. Portrait hid it, because there
     * the box follows the skin's aspect and a switch really does resize it.
     */
    private void fit(int width, int height) {
        if (keyboard == null || width <= 0 || height <= 0) return;

        scale = Math.min(width / (float) keyboard.getWidth(),
                         height / (float) keyboard.getHeight());

        int drawWidth = Math.round(keyboard.getWidth() * scale);
        int drawHeight = Math.round(keyboard.getHeight() * scale);
        destination.set((width - drawWidth) / 2, (height - drawHeight) / 2,
                        (width + drawWidth) / 2, (height + drawHeight) / 2);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (keyboard == null) return;

        canvas.drawBitmap(keyboard, null, destination, bitmapPaint);

        for (Row row : rows) {
            for (Key key : row.keys) {
                if (!key.pressed && !key.latched) continue;

                highlight.set(destination.left + key.image.left * scale,
                              destination.top + key.image.top * scale,
                              destination.left + key.image.right * scale,
                              destination.top + key.image.bottom * scale);
                canvas.drawRect(highlight, key.latched ? latchedPaint : pressedPaint);
            }
        }
    }

    private Key keyAt(float x, float y) {
        if (keyboard == null || scale <= 0) return null;

        int imageX = Math.round((x - destination.left) / scale);
        int imageY = Math.round((y - destination.top) / scale);

        for (Row row : rows) {
            for (Key key : row.keys) {
                if (key.touch.contains(imageX, imageY)) return key;
            }
        }
        return null;
    }

    /**
     * Told which key was tapped, instead of the machine being told.
     *
     * The profile editor needs a way to say "that key", and this keyboard
     * already knows where every one of the forty is and what it is called.
     * Pointing at a picture of the real thing beats choosing SYMBOL SHIFT off a
     * list of forty names.
     */
    interface Picker {
        void picked(int keycode);
    }

    private Picker picker;

    void setPicker(Picker picker) {
        this.picker = picker;
    }

    private void send(Key key, boolean pressed) {
        if (picker != null) {
            // On the press, not the release: the highlight is already showing
            // and waiting for the finger to lift would feel slow.
            if (pressed) picker.picked(key.keycode);
            return;
        }

        // The modifier first going down and last coming up, so the machine
        // never sees the key without its shift.
        if (key.modifier != 0 && pressed) FuseNative.key(key.modifier, true);

        FuseNative.key(key.keycode, pressed);

        if (key.modifier != 0 && !pressed && !isLatched(key.modifier)) {
            FuseNative.key(key.modifier, false);
        }
    }

    /**
     * Whether a shift is being held by its own key.
     *
     * A latched CAPS SHIFT and then GRAPH, which is CAPS SHIFT and 9, would
     * otherwise let go of the latch on the way out: the combination would
     * release a shift the user is still holding.
     */
    private boolean isLatched(int keycode) {
        for (Row row : rows) {
            for (Key key : row.keys) {
                if (key.keycode == keycode && key.modifier == 0 && key.latched) {
                    return true;
                }
            }
        }
        return false;
    }

    private void press(int pointerId, Key key) {
        if (key == null) return;

        // Tapping a latched shift is how you let it go again.
        if (key.latched) {
            key.latched = false;
            key.pressed = false;
            send(key, false);
            invalidate();
            announce(key);
            return;
        }

        pointers.put(pointerId, key);
        key.pressed = true;
        send(key, true);
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        invalidate();

        // Nothing to latch a shift for while picking one: it is being named,
        // not used, and a latched shift would stay lit after the tap.
        if (key.canLatch && picker == null) {
            handler.postDelayed(() -> latch(pointerId, key), LATCH_MS);
        }
    }

    private void latch(int pointerId, Key key) {
        // Only if that same press is still being held.
        if (pointers.get(pointerId) != key || key.latched) return;

        key.latched = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        invalidate();
        announce(key);
    }

    private void release(int pointerId) {
        Key key = pointers.get(pointerId);
        if (key == null) return;

        pointers.remove(pointerId);

        // A latched shift stays down until it is tapped again.
        if (key.latched) return;

        key.pressed = false;
        send(key, false);
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int index = event.getActionIndex();
        int pointerId = event.getPointerId(index);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                press(pointerId, keyAt(event.getX(index), event.getY(index)));
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                release(pointerId);
                return true;

            case MotionEvent.ACTION_CANCEL:
                for (int i = pointers.size() - 1; i >= 0; i--) {
                    release(pointers.keyAt(i));
                }
                return true;

            default:
                return true;
        }
    }

    // --- accessibility ----------------------------------------------------

    /*
     * The keyboard is one bitmap, so without this it is a single unnamed
     * View: nothing can say which key it means, and a test would be reduced
     * to tapping coordinates measured off the artwork. Each key is published
     * as a virtual node instead, named the way the Spectrum names it, so
     * both a screen reader and UI Automator can address "ENTER" or "CAPS
     * SHIFT" and land on the right pixels.
     */

    private final List<Key> flat = new ArrayList<>();

    private List<Key> keys() {
        if (flat.isEmpty()) {
            for (Row row : rows) {
                for (Key key : row.keys) flat.add(key);
            }
        }
        return flat;
    }

    /** Where a key is on the screen, which is what the node has to report. */
    private void screenBounds(Key key, Rect out) {
        int[] location = new int[2];
        getLocationOnScreen(location);

        int x = location[0] + destination.left;
        int y = location[1] + destination.top;

        out.set(x + Math.round(key.image.left * scale),
                y + Math.round(key.image.top * scale),
                x + Math.round(key.image.right * scale),
                y + Math.round(key.image.bottom * scale));
    }

    private final AccessibilityNodeProvider provider = new AccessibilityNodeProvider() {

        @Override
        public AccessibilityNodeInfo createAccessibilityNodeInfo(int virtualViewId) {
            if (virtualViewId == HOST_VIEW_ID) {
                AccessibilityNodeInfo host =
                        AccessibilityNodeInfo.obtain(SpectrumKeyboardView.this);
                onInitializeAccessibilityNodeInfo(host);

                for (int i = 0; i < keys().size(); i++) {
                    host.addChild(SpectrumKeyboardView.this, i);
                }
                return host;
            }

            if (virtualViewId < 0 || virtualViewId >= keys().size()) return null;
            Key key = keys().get(virtualViewId);

            AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(
                    SpectrumKeyboardView.this, virtualViewId);

            Rect bounds = new Rect();
            screenBounds(key, bounds);

            node.setPackageName(getContext().getPackageName());
            node.setClassName(Button.class.getName());
            node.setContentDescription(key.name);
            node.setText(key.name);
            node.setParent(SpectrumKeyboardView.this);
            node.setBoundsInScreen(bounds);
            node.setEnabled(true);
            node.setVisibleToUser(true);
            node.setFocusable(true);
            node.setClickable(true);
            node.setCheckable(key.canLatch);
            node.setChecked(key.latched);
            node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);

            if (key.canLatch) {
                node.setLongClickable(true);
                node.addAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK);
            }

            return node;
        }

        @Override
        public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByText(
                String text, int virtualViewId) {

            List<AccessibilityNodeInfo> found = new ArrayList<>();
            if (text == null) return found;

            for (int i = 0; i < keys().size(); i++) {
                if (keys().get(i).name.equalsIgnoreCase(text.trim())) {
                    found.add(createAccessibilityNodeInfo(i));
                }
            }
            return found;
        }

        @Override
        public boolean performAction(int virtualViewId, int action, Bundle arguments) {
            if (virtualViewId < 0 || virtualViewId >= keys().size()) return false;
            Key key = keys().get(virtualViewId);

            // A tap through accessibility is a press and a release; Fuse
            // reads the keyboard once a frame and the release is held back
            // until the next one, so the machine still sees them apart.
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                press(ACCESSIBILITY_POINTER, key);
                release(ACCESSIBILITY_POINTER);
                return true;
            }

            if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK && key.canLatch) {
                press(ACCESSIBILITY_POINTER, key);
                latch(ACCESSIBILITY_POINTER, key);
                pointers.remove(ACCESSIBILITY_POINTER);
                return true;
            }

            return false;
        }
    };

    /** No real pointer will ever have this id. */
    private static final int ACCESSIBILITY_POINTER = -42;

    @Override
    public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return provider;
    }

    /**
     * Keeps a latched shift's node in step with the highlight.
     *
     * Only when something is listening: sending an event with accessibility
     * switched off throws, and off is the normal case.
     */
    private void announce(Key key) {
        AccessibilityManager manager = (AccessibilityManager)
                getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null || !manager.isEnabled()) return;

        ViewParent parent = getParent();
        if (parent == null) return;

        int id = keys().indexOf(key);
        if (id < 0) return;

        AccessibilityEvent event = AccessibilityEvent.obtain(
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        event.setPackageName(getContext().getPackageName());
        event.setSource(this, id);
        parent.requestSendAccessibilityEvent(this, event);
    }
}
