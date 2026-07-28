package com.fusemobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The 40-key ZX Spectrum keyboard.
 *
 * Keys are reported as Android keycodes and travel the same path as a
 * physical keyboard: {@link FuseNative#key} queues them, and Fuse's own
 * keysym table turns them into Spectrum keys. That is why Caps Shift is sent
 * as SHIFT_LEFT and Symbol Shift as CTRL_LEFT - Fuse already maps both.
 *
 * Presses are real presses rather than taps, so holding Caps Shift with one
 * finger and pressing a letter with another behaves like the real machine.
 */
public class SpectrumKeyboardView extends View {

    /** Fraction of the view's width used for its height (4 rows of keys). */
    private static final float ASPECT = 0.34f;

    /** Upper bound on how much of the window the keyboard may take. */
    private static final float MAX_HEIGHT_FRACTION = 0.42f;

    private static final class Key {
        final String label;
        final float weight;
        final int keycode;
        final RectF bounds = new RectF();
        boolean pressed;

        Key(String label, int keycode, float weight) {
            this.label = label;
            this.keycode = keycode;
            this.weight = weight;
        }
    }

    private final List<List<Key>> rows = new ArrayList<>();

    /** Which key each active pointer is holding down. */
    private final android.util.SparseArray<Key> pointers = new android.util.SparseArray<>();

    private final Paint keyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pressedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SpectrumKeyboardView(Context context) {
        this(context, null);
    }

    public SpectrumKeyboardView(Context context, AttributeSet attrs) {
        super(context, attrs);

        keyPaint.setColor(0xff2b2b2b);
        pressedPaint.setColor(0xff00b0c8);
        textPaint.setColor(0xffe8e8e8);
        textPaint.setTextAlign(Paint.Align.CENTER);

        buildKeys();
        setBackgroundColor(0xff141414);
    }

    private void buildKeys() {
        rows.add(row(
            key("1", KeyEvent.KEYCODE_1), key("2", KeyEvent.KEYCODE_2),
            key("3", KeyEvent.KEYCODE_3), key("4", KeyEvent.KEYCODE_4),
            key("5", KeyEvent.KEYCODE_5), key("6", KeyEvent.KEYCODE_6),
            key("7", KeyEvent.KEYCODE_7), key("8", KeyEvent.KEYCODE_8),
            key("9", KeyEvent.KEYCODE_9), key("0", KeyEvent.KEYCODE_0)));

        rows.add(row(
            key("Q", KeyEvent.KEYCODE_Q), key("W", KeyEvent.KEYCODE_W),
            key("E", KeyEvent.KEYCODE_E), key("R", KeyEvent.KEYCODE_R),
            key("T", KeyEvent.KEYCODE_T), key("Y", KeyEvent.KEYCODE_Y),
            key("U", KeyEvent.KEYCODE_U), key("I", KeyEvent.KEYCODE_I),
            key("O", KeyEvent.KEYCODE_O), key("P", KeyEvent.KEYCODE_P)));

        rows.add(row(
            key("A", KeyEvent.KEYCODE_A), key("S", KeyEvent.KEYCODE_S),
            key("D", KeyEvent.KEYCODE_D), key("F", KeyEvent.KEYCODE_F),
            key("G", KeyEvent.KEYCODE_G), key("H", KeyEvent.KEYCODE_H),
            key("J", KeyEvent.KEYCODE_J), key("K", KeyEvent.KEYCODE_K),
            key("L", KeyEvent.KEYCODE_L),
            new Key("ENTER", KeyEvent.KEYCODE_ENTER, 1.6f)));

        rows.add(row(
            new Key("CAPS", KeyEvent.KEYCODE_SHIFT_LEFT, 1.6f),
            key("Z", KeyEvent.KEYCODE_Z), key("X", KeyEvent.KEYCODE_X),
            key("C", KeyEvent.KEYCODE_C), key("V", KeyEvent.KEYCODE_V),
            key("B", KeyEvent.KEYCODE_B), key("N", KeyEvent.KEYCODE_N),
            key("M", KeyEvent.KEYCODE_M),
            new Key("SYM", KeyEvent.KEYCODE_CTRL_LEFT, 1.6f),
            new Key("SPACE", KeyEvent.KEYCODE_SPACE, 2.2f)));
    }

    private static Key key(String label, int keycode) {
        return new Key(label, keycode, 1f);
    }

    private static List<Key> row(Key... keys) {
        List<Key> list = new ArrayList<>(keys.length);
        for (Key k : keys) list.add(k);
        return list;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = Math.round(width * ASPECT);

        // Square-ish keys are the right shape in portrait but would swallow a
        // landscape window, so never take more than MAX_HEIGHT_FRACTION of it.
        int available = MeasureSpec.getSize(heightMeasureSpec);
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED
                && available > 0) {
            height = Math.min(height, Math.round(available * MAX_HEIGHT_FRACTION));
        }

        setMeasuredDimension(width, height);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        float gap = width * 0.004f;
        float rowHeight = height / (float) rows.size();

        for (int r = 0; r < rows.size(); r++) {
            List<Key> keys = rows.get(r);

            float total = 0f;
            for (Key k : keys) total += k.weight;

            float x = 0f;
            for (Key k : keys) {
                float keyWidth = width * (k.weight / total);
                k.bounds.set(x + gap, r * rowHeight + gap,
                             x + keyWidth - gap, (r + 1) * rowHeight - gap);
                x += keyWidth;
            }
        }

        textPaint.setTextSize(rowHeight * 0.38f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float radius = getHeight() * 0.03f;

        for (List<Key> keys : rows) {
            for (Key k : keys) {
                canvas.drawRoundRect(k.bounds, radius, radius,
                                     k.pressed ? pressedPaint : keyPaint);
                float baseline = k.bounds.centerY()
                        - (textPaint.descent() + textPaint.ascent()) / 2f;
                canvas.drawText(k.label, k.bounds.centerX(), baseline, textPaint);
            }
        }
    }

    private Key keyAt(float x, float y) {
        for (List<Key> keys : rows) {
            for (Key k : keys) {
                if (k.bounds.contains(x, y)) return k;
            }
        }
        return null;
    }

    private void press(int pointerId, Key key) {
        if (key == null) return;
        pointers.put(pointerId, key);
        key.pressed = true;
        FuseNative.key(key.keycode, true);
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        invalidate();
    }

    private void release(int pointerId) {
        Key key = pointers.get(pointerId);
        if (key == null) return;
        pointers.remove(pointerId);
        key.pressed = false;
        FuseNative.key(key.keycode, false);
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
}
