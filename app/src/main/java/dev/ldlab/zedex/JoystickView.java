package dev.ldlab.zedex;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.Button;

import java.util.ArrayList;
import java.util.List;

/**
 * The on-screen joystick: a thumb pad, or the fire button that goes with it.
 *
 * One class and two instances rather than two classes, because everything but
 * the drawing is the same — the presses go the same way, the controls are
 * published to accessibility the same way, and a template places the two boxes
 * side by side. {@link Part} is which of the two this one is.
 *
 * Nothing here knows what a Kempston is. The five controls are Fuse's own
 * {@code joystick_button} values and go through {@link FuseNative#joystick},
 * which hands them to whichever interface the menu has chosen; Cursor and
 * Sinclair come out as key presses inside Fuse, Kempston and Timex as port
 * bits, and this side is the same either way.
 *
 * The pad is a stick, not four buttons: the direction comes from the angle of
 * the finger about the centre, snapped to eight, so a push into a corner is a
 * genuine diagonal and sliding around the pad steers without lifting off.
 */
final class JoystickView extends View {

    enum Part { PAD, FIRE }

    /** Face, edge and legend, from the same palette as the ☰ sheet. */
    private static final int FACE = 0x26ffffff;
    private static final int EDGE = 0x88ededf2;
    private static final int MARK = 0x99ededf2;

    /** The keyboard's press colour, so a pressed control reads the same way. */
    private static final int PRESSED = 0xcc00b0c8;

    /** Of the control's radius: inside this the stick is centred. */
    private static final float DEAD_ZONE = 0.3f;

    /** How far the knob may travel, of the radius. */
    private static final float THROW = 0.62f;

    private static final float KNOB = 0.34f;

    /** Leaves the ring clear of whatever it is sitting next to. */
    private static final float INSET_DP = 6f;

    private final Part part;
    private final float density;

    private final Paint face = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint legend = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();

    /** Indexed by Fuse's button number; which of the five are down now. */
    private final boolean[] held = new boolean[5];

    private float centreX, centreY, radius;

    /** Where the knob sits, in pixels from the centre; zero when let go. */
    private float knobX, knobY;

    JoystickView(Context context, Part part) {
        super(context);

        this.part = part;
        this.density = getResources().getDisplayMetrics().density;

        face.setColor(FACE);
        face.setStyle(Paint.Style.FILL);

        edge.setColor(EDGE);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(2f * density);

        mark.setColor(MARK);
        mark.setStyle(Paint.Style.FILL);

        legend.setColor(MARK);
        legend.setTextAlign(Paint.Align.CENTER);
        legend.setFakeBoldText(true);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        centreX = width / 2f;
        centreY = height / 2f;
        radius = Math.min(width, height) / 2f - INSET_DP * density;
        if (radius < 0) radius = 0;

        legend.setTextSize(radius * 0.42f);
    }

    // --- drawing ----------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        if (radius <= 0) return;

        if (part == Part.FIRE) {
            drawFire(canvas);
            return;
        }

        canvas.drawCircle(centreX, centreY, radius, face);
        canvas.drawCircle(centreX, centreY, radius, edge);

        drawArrow(canvas, FuseNative.JOYSTICK_UP, 0, -1);
        drawArrow(canvas, FuseNative.JOYSTICK_DOWN, 0, 1);
        drawArrow(canvas, FuseNative.JOYSTICK_LEFT, -1, 0);
        drawArrow(canvas, FuseNative.JOYSTICK_RIGHT, 1, 0);

        // The knob, wherever the finger last put it.
        mark.setColor(knobX != 0 || knobY != 0 ? PRESSED : MARK);
        canvas.drawCircle(centreX + knobX, centreY + knobY, radius * KNOB, mark);
        mark.setColor(MARK);
    }

    private void drawFire(Canvas canvas) {
        boolean down = held[FuseNative.JOYSTICK_FIRE];

        face.setColor(down ? PRESSED : FACE);
        canvas.drawCircle(centreX, centreY, radius, face);
        canvas.drawCircle(centreX, centreY, radius, edge);
        face.setColor(FACE);

        // Centred on the glyphs rather than on the line box, which sits low.
        Paint.FontMetrics metrics = legend.getFontMetrics();
        canvas.drawText("FIRE", centreX,
                        centreY - (metrics.ascent + metrics.descent) / 2f, legend);
    }

    /** A triangle pointing the way, at the rim, lit while that way is held. */
    private void drawArrow(Canvas canvas, int button, float dx, float dy) {
        float tip = radius * 0.82f;
        float back = radius * 0.58f;
        float half = radius * 0.13f;

        // Perpendicular to the direction, for the two back corners.
        float px = -dy, py = dx;

        arrow.rewind();
        arrow.moveTo(centreX + dx * tip, centreY + dy * tip);
        arrow.lineTo(centreX + dx * back + px * half, centreY + dy * back + py * half);
        arrow.lineTo(centreX + dx * back - px * half, centreY + dy * back - py * half);
        arrow.close();

        mark.setColor(held[button] ? PRESSED : MARK);
        canvas.drawPath(arrow, mark);
        mark.setColor(MARK);
    }

    // --- presses ----------------------------------------------------------

    private void send(int button, boolean pressed) {
        if (held[button] == pressed) return;

        held[button] = pressed;
        FuseNative.joystick(button, pressed);
    }

    /** Everything up: the finger left, or the view is being taken away. */
    private void releaseAll() {
        for (int button = 0; button < held.length; button++) send(button, false);

        knobX = 0;
        knobY = 0;
        invalidate();
    }

    /**
     * The eight directions, from the angle of the finger about the centre.
     * Snapping to eight rather than testing each axis against a threshold is
     * what makes a corner a reliable diagonal: every push is one of exactly
     * eight answers, and there is no band where a hard push registers nothing.
     */
    private void steer(float x, float y) {
        float dx = x - centreX;
        float dy = y - centreY;
        double distance = Math.hypot(dx, dy);

        boolean up = false, down = false, left = false, right = false;

        if (distance >= radius * DEAD_ZONE) {
            int octant = (int) Math.round(Math.atan2(dy, dx) / (Math.PI / 4));
            if (octant < 0) octant += 8;

            right = octant == 0 || octant == 1 || octant == 7;
            down  = octant == 1 || octant == 2 || octant == 3;
            left  = octant == 3 || octant == 4 || octant == 5;
            up    = octant == 5 || octant == 6 || octant == 7;

            float travel = (float) Math.min(distance, radius * THROW);
            knobX = (float) (dx / distance) * travel;
            knobY = (float) (dy / distance) * travel;
        } else {
            knobX = 0;
            knobY = 0;
        }

        // Releases first, so a swing from left to right is never both at once:
        // Fuse holds a release over to the next frame, and the machine would
        // otherwise read a stick pushed two ways.
        if (!left) send(FuseNative.JOYSTICK_LEFT, false);
        if (!right) send(FuseNative.JOYSTICK_RIGHT, false);
        if (!up) send(FuseNative.JOYSTICK_UP, false);
        if (!down) send(FuseNative.JOYSTICK_DOWN, false);

        if (left) send(FuseNative.JOYSTICK_LEFT, true);
        if (right) send(FuseNative.JOYSTICK_RIGHT, true);
        if (up) send(FuseNative.JOYSTICK_UP, true);
        if (down) send(FuseNative.JOYSTICK_DOWN, true);

        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                // fall through
            case MotionEvent.ACTION_MOVE:
                if (part == Part.FIRE) {
                    send(FuseNative.JOYSTICK_FIRE, true);
                    invalidate();
                } else {
                    steer(event.getX(), event.getY());
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                return true;

            default:
                return true;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // A control that goes away mid-press would leave the stick held.
        releaseAll();
    }

    @Override
    protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (visibility != VISIBLE) releaseAll();
    }

    // --- accessibility ----------------------------------------------------

    /*
     * Two circles drawn on a canvas are, without this, two unnamed views: a
     * screen reader has nothing to say about them and a test is reduced to
     * tapping coordinates. Each control is published as a virtual node
     * instead, named for the direction it is, the same way every key of the
     * keyboard is.
     */

    private static final String[] PAD_NAMES = {
        "JOYSTICK LEFT", "JOYSTICK RIGHT", "JOYSTICK UP", "JOYSTICK DOWN",
    };

    private static final String FIRE_NAME = "JOYSTICK FIRE";

    /** The button each virtual node stands for, in node order. */
    private int buttonFor(int id) {
        return part == Part.FIRE ? FuseNative.JOYSTICK_FIRE : id;
    }

    private String nameFor(int id) {
        return part == Part.FIRE ? FIRE_NAME : PAD_NAMES[id];
    }

    private int controlCount() {
        return part == Part.FIRE ? 1 : PAD_NAMES.length;
    }

    /**
     * Where a control is on the screen. The pad's four share the ring, offset
     * towards the direction they are, so a screen reader's explore-by-touch
     * lands on the one under the finger.
     */
    private void screenBounds(int id, Rect out) {
        int[] location = new int[2];
        getLocationOnScreen(location);

        float x = location[0] + centreX;
        float y = location[1] + centreY;

        if (part == Part.FIRE) {
            out.set(Math.round(x - radius), Math.round(y - radius),
                    Math.round(x + radius), Math.round(y + radius));
            return;
        }

        float dx = 0, dy = 0;
        switch (buttonFor(id)) {
            case FuseNative.JOYSTICK_LEFT: dx = -1; break;
            case FuseNative.JOYSTICK_RIGHT: dx = 1; break;
            case FuseNative.JOYSTICK_UP: dy = -1; break;
            default: dy = 1; break;
        }

        float quarter = radius / 2f;
        float cx = x + dx * quarter;
        float cy = y + dy * quarter;

        out.set(Math.round(cx - quarter), Math.round(cy - quarter),
                Math.round(cx + quarter), Math.round(cy + quarter));
    }

    private final AccessibilityNodeProvider provider = new AccessibilityNodeProvider() {

        @Override
        public AccessibilityNodeInfo createAccessibilityNodeInfo(int virtualViewId) {
            if (virtualViewId == HOST_VIEW_ID) {
                AccessibilityNodeInfo host = AccessibilityNodeInfo.obtain(JoystickView.this);
                onInitializeAccessibilityNodeInfo(host);

                for (int i = 0; i < controlCount(); i++) {
                    host.addChild(JoystickView.this, i);
                }
                return host;
            }

            if (virtualViewId < 0 || virtualViewId >= controlCount()) return null;

            AccessibilityNodeInfo node =
                    AccessibilityNodeInfo.obtain(JoystickView.this, virtualViewId);

            Rect bounds = new Rect();
            screenBounds(virtualViewId, bounds);

            node.setPackageName(getContext().getPackageName());
            node.setClassName(Button.class.getName());
            node.setContentDescription(nameFor(virtualViewId));
            node.setText(nameFor(virtualViewId));
            node.setParent(JoystickView.this);
            node.setBoundsInScreen(bounds);
            node.setEnabled(true);
            node.setVisibleToUser(true);
            node.setFocusable(true);
            node.setClickable(true);
            node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);

            return node;
        }

        @Override
        public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByText(
                String text, int virtualViewId) {

            List<AccessibilityNodeInfo> found = new ArrayList<>();
            if (text == null) return found;

            for (int i = 0; i < controlCount(); i++) {
                if (nameFor(i).equalsIgnoreCase(text.trim())) {
                    found.add(createAccessibilityNodeInfo(i));
                }
            }
            return found;
        }

        @Override
        public boolean performAction(int virtualViewId, int action, Bundle arguments) {
            if (virtualViewId < 0 || virtualViewId >= controlCount()) return false;
            if (action != AccessibilityNodeInfo.ACTION_CLICK) return false;

            // A press and a release: Fuse holds the release back to the next
            // frame, so the machine still sees the two apart.
            int button = buttonFor(virtualViewId);
            send(button, true);
            send(button, false);
            invalidate();

            return true;
        }
    };

    @Override
    public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return provider;
    }
}
