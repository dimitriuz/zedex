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

    private static final String IMAGE_ASSET = "fuse/keyboard.png";

    /** How long a shift must be held before it latches. */
    private static final long LATCH_MS = 400;

    private static final class Key {
        final Rect image = new Rect();       // as drawn, in image pixels
        final Rect touch = new Rect();       // expanded to swallow the gaps
        final int keycode;
        final String name;
        final boolean canLatch;
        boolean pressed;
        boolean latched;

        Key(int left, int right, int keycode) {
            this.image.left = left;
            this.image.right = right;
            this.keycode = keycode;
            this.name = nameOf( keycode );
            this.canLatch = keycode == KeyEvent.KEYCODE_SHIFT_LEFT
                         || keycode == KeyEvent.KEYCODE_CTRL_LEFT;
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

    private final Row[] rows = {
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
        return new Key(left, right, keycode);
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

        try (InputStream in = context.getAssets().open(IMAGE_ASSET)) {
            keyboard = BitmapFactory.decodeStream(in);
        } catch (IOException e) {
            Log.e(TAG, "cannot load " + IMAGE_ASSET, e);
        }

        computeTouchAreas();
        setBackgroundColor(0xff1b1b1b);
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
        int height = keyboard != null
                ? Math.round(width * keyboard.getHeight() / (float) keyboard.getWidth())
                : Math.round(width * 0.37f);

        setMeasuredDimension(width, height);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        if (keyboard == null) return;

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

    private void send(Key key, boolean pressed) {
        FuseNative.key(key.keycode, pressed);
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

        if (key.canLatch) {
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
