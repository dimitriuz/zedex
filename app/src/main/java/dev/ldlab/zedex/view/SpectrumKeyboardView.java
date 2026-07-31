package dev.ldlab.zedex.view;

import dev.ldlab.zedex.FuseNative;
import dev.ldlab.zedex.screen.SettingsActivity;
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
 * The ZX Spectrum keyboard.
 *
 * Every legend a Spectrum key has — the BASIC keyword, the symbol-shift
 * character, the colour, the extended-mode token — is on it, which is what
 * makes typing Spectrum BASIC possible at all. The 48K's is Fuse's own
 * keyboard.png with the key rectangles below measured off it, in its 541x201
 * coordinate space; the 128K's is drawn by {@link PlusPlate}, which supplies
 * its own table in the space it draws in. Either way the rectangles are scaled
 * to wherever the picture lands.
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
     * Which machine's keyboard is shown.
     *
     * A picture and a table of key rectangles in that picture's own
     * coordinates: everything else - the presses, the latching, the
     * accessibility nodes, the scaling - is the same for all of them, because a
     * skin is only a picture and where its keys are.
     *
     * The rubber one is Fuse's own artwork, which the app already installs; the
     * 128K plate has no artwork because {@link PlusPlate} draws it.
     */
    public enum Skin {
        RUBBER("rubber", "ZX Spectrum 48K", "fuse/keyboard.png", 541f / 201f),
        PLUS("plus", "ZX Spectrum 128K", null, PlusPlate.ASPECT),

        /**
         * Not a picture at all: the device's own input method types instead, and
         * this keyboard is not drawn. It is in the same list because it is the
         * same choice - which keyboard you use - and it has no key table
         * because it has no keys of its own.
         */
        SYSTEM("system", "Android keyboard", null, 541f / 201f);

        public final String value;             /* as stored in the preferences */
        public final String title;
        final String asset;
        final float aspect;             /* before the image has loaded */

        Skin(String value, String title, String asset, float aspect) {
            this.value = value;
            this.title = title;
            this.asset = asset;
            this.aspect = aspect;
        }

        /** Whether this one appears here rather than being Android's own. */
        boolean drawn() {
            return this != SYSTEM;
        }

        public static Skin of(String stored) {
            for (Skin skin : values()) {
                if (skin.value.equals(stored)) return skin;
            }
            return RUBBER;
        }
    }

    private Skin skin = Skin.RUBBER;
    private Row[] rows = RUBBER_ROWS;

    /** The space the current table's rectangles are measured in. */
    private int sourceWidth = 541, sourceHeight = 201;

    /** Fuse's own artwork, for before anything has been loaded. */
    public static final float NATURAL_ASPECT = 541f / 201f;

    /** How long a shift must be held before it latches. */
    private static final long LATCH_MS = 400;

    static final class Key {
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

        /** A key that knows its whole rectangle, which a drawn plate's do. */
        Key(RectF box, int keycode, int modifier, String name) {
            this(Math.round(box.left), Math.round(box.right),
                 keycode, modifier, name);
            image.top = Math.round(box.top);
            image.bottom = Math.round(box.bottom);
        }

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

    static final class Row {
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

    private static Key key(int left, int right, int keycode) {
        return new Key(left, right, keycode, 0, null);
    }

    private static Row row(int top, int bottom, Key... keys) {
        return new Row(top, bottom, keys);
    }

    private final SparseArray<Key> pointers = new SparseArray<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Paint bitmapPaint = new Paint();
    private final Paint pressedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint latchedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Bitmap keyboard;                 // artwork, when a skin has any
    private PlusPlate plate;                 // or the plate drawn here
    private Bitmap drawn;                    // it, at the size it is shown

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
    public void setSkin(Skin wanted) {
        releaseEverything();

        skin = wanted;
        keyboard = null;
        plate = null;
        drawn = null;

        // The flattened list is what the accessibility nodes are numbered by,
        // and it belongs to the table that has just been replaced.
        flat.clear();

        if (wanted == Skin.PLUS) {
            plate = new PlusPlate();
            rows = plate.rows();
            sourceWidth = PlusPlate.WIDTH;
            sourceHeight = PlusPlate.HEIGHT;
        } else {
            rows = RUBBER_ROWS;
            sourceWidth = 541;
            sourceHeight = 201;

            if (wanted.asset != null) {
                try (InputStream in = getContext().getAssets().open(wanted.asset)) {
                    keyboard = BitmapFactory.decodeStream(in);
                    sourceWidth = keyboard.getWidth();
                    sourceHeight = keyboard.getHeight();
                } catch (IOException e) {
                    Log.e(TAG, "cannot load " + wanted.asset, e);
                }
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

    public Skin skin() {
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
     * dead. Fingers are wider than the gutters in either keyboard.
     */
    private void computeTouchAreas() {
        for (int r = 0; r < rows.length; r++) {
            Row row = rows[r];
            int top = r == 0 ? 0 : (rows[r - 1].bottom + row.top) / 2;
            int bottom = r == rows.length - 1 ? sourceHeight
                                              : (row.bottom + rows[r + 1].top) / 2;

            for (int k = 0; k < row.keys.length; k++) {
                Key key = row.keys[k];

                // A key may have brought its own rectangle: the 128K's ENTER
                // spans two rows, and one of them is not the row it is in.
                if (key.image.bottom == 0) {
                    key.image.top = row.top;
                    key.image.bottom = row.bottom;
                }
                key.touch.top = top;
                key.touch.bottom = bottom;
                key.touch.left = k == 0 ? 0
                        : (row.keys[k - 1].image.right + key.image.left) / 2;
                key.touch.right = k == row.keys.length - 1 ? sourceWidth
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
        int mode = MeasureSpec.getMode(heightMeasureSpec);
        int given = MeasureSpec.getSize(heightMeasureSpec);
        int natural = Math.round(width / aspect());

        // A limit is still a limit: asked to wrap inside a box shorter than the
        // natural shape - a second screen's panel, say - the keys have to fit
        // in it rather than run off the bottom of it.
        int height = mode == MeasureSpec.EXACTLY ? given
                   : mode == MeasureSpec.AT_MOST ? Math.min(natural, given)
                   : natural;

        setMeasuredDimension(width, height);
    }

    /** Width over height of the keyboard, so a parent can shape its box. */
    public float aspect() {
        if (sourceHeight == 0) return skin.aspect;
        return sourceWidth / (float) sourceHeight;
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
        if (!skin.drawn() || width <= 0 || height <= 0) return;

        scale = Math.min(width / (float) sourceWidth,
                         height / (float) sourceHeight);

        int drawWidth = Math.round(sourceWidth * scale);
        int drawHeight = Math.round(sourceHeight * scale);
        int top = bottom ? height - drawHeight : (height - drawHeight) / 2;

        destination.set((width - drawWidth) / 2, top,
                        (width + drawWidth) / 2, top + drawHeight);
    }

    /**
     * Where the keys sit in a box taller than they need.
     *
     * Centred everywhere the box is cut to fit them anyway, and against the
     * foot of it on a second screen, where the room left over is the panel's
     * and keys halfway up it are keys away from the thumbs holding it.
     */
    private boolean bottom;

    public void setBottomAligned(boolean against) {
        if (bottom == against) return;

        bottom = against;
        fit(getWidth(), getHeight());
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (keyboard != null) {
            canvas.drawBitmap(keyboard, null, destination, bitmapPaint);
        } else if (plate != null) {
            canvas.drawBitmap(rendered(), destination.left, destination.top, null);
        } else {
            return;
        }

        // The plate's keys are rounded off at the foot, so square corners on
        // the highlight hang over the edge of one. The rubber keyboard's keys
        // are rectangles and take a radius of nothing.
        float corner = plate != null ? 10f * scale : 0f;

        for (Row row : rows) {
            for (Key key : row.keys) {
                if (!key.pressed && !key.latched) continue;

                highlight.set(destination.left + key.image.left * scale,
                              destination.top + key.image.top * scale,
                              destination.left + key.image.right * scale,
                              destination.top + key.image.bottom * scale);
                canvas.drawRoundRect(highlight, corner, corner,
                                     key.latched ? latchedPaint : pressedPaint);
            }
        }
    }

    /**
     * The drawn plate, kept at the size it is shown at.
     *
     * Drawing it again on every invalidate would be forty keys and a hundred
     * and fifty legends for each key press, and a press only puts a highlight
     * over a picture that has not changed.
     */
    private Bitmap rendered() {
        if (drawn != null && drawn.getWidth() == destination.width()
                          && drawn.getHeight() == destination.height()) {
            return drawn;
        }

        drawn = Bitmap.createBitmap(destination.width(), destination.height(),
                                    Bitmap.Config.ARGB_8888);
        Canvas into = new Canvas(drawn);
        into.scale(destination.width() / (float) sourceWidth,
                   destination.height() / (float) sourceHeight);
        plate.draw(into);
        return drawn;
    }

    private Key keyAt(float x, float y) {
        if (!skin.drawn() || scale <= 0) return null;

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
    public interface Picker {
        void picked(int keycode);
    }

    private Picker picker;

    public void setPicker(Picker picker) {
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
