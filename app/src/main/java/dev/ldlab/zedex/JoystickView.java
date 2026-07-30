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
 * The on-screen joystick: a thumb pad, the fire button that goes with it, or one
 * of the three key buttons in the arc beside fire.
 *
 * One class and several instances rather than a class each, because everything
 * but the drawing is the same — the presses go the same way, the controls are
 * published to accessibility the same way, and a template places the boxes.
 * {@link Part} is which of them this one is.
 *
 * Nothing here knows what a Kempston is, and nothing here knows which keys a
 * profile holds. The five pad controls are Fuse's own {@code joystick_button}
 * values and the three buttons are slots in the current profile; both go through
 * {@link Controls}, which is the one place that decides whether a press reaches
 * the machine as a joystick or as a key.
 *
 * The pad is a stick, not four buttons: the direction comes from the angle of
 * the finger about the centre, snapped to eight, so a push into a corner is a
 * genuine diagonal and sliding around the pad steers without lifting off.
 */
final class JoystickView extends View {

    enum Part { PAD, FIRE, KEY }

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

    /** Which profile slot a {@link Part#KEY} sends; -1 for the other two. */
    private final int slot;

    /** Which control this is, for a parent laying the three of them out. */
    Part part() {
        return part;
    }

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

    /** Whether a {@link Part#KEY} is being held. */
    private boolean keyDown;

    /** The pad or fire. */
    JoystickView(Context context, Part part) {
        this(context, part, -1);
    }

    /** One of the three buttons, sending the key in {@code slot}. */
    JoystickView(Context context, int slot) {
        this(context, Part.KEY, slot);
    }

    private JoystickView(Context context, Part part, int slot) {
        super(context);

        this.part = part;
        this.slot = slot;
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
    }

    // --- drawing ----------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        if (radius <= 0) return;

        if (part == Part.FIRE) {
            // The key underneath, but only when there is one: with a real
            // interface selected fire is a joystick button and no key is
            // involved, and a name that was there whatever the type would be a
            // lie half the time.
            String key = Controls.padSendsKeys()
                    ? ControlProfiles.label(Controls.key(FuseNative.JOYSTICK_FIRE))
                    : null;

            drawRound(canvas, "FIRE", key, held[FuseNative.JOYSTICK_FIRE]);
            return;
        }

        if (part == Part.KEY) {
            drawRound(canvas, ControlProfiles.label(Controls.key(slot)), null,
                      keyDown);
            return;
        }

        canvas.drawCircle(centreX, centreY, radius, face);
        canvas.drawCircle(centreX, centreY, radius, edge);

        drawWay(canvas, FuseNative.JOYSTICK_UP, 0, -1);
        drawWay(canvas, FuseNative.JOYSTICK_DOWN, 0, 1);
        drawWay(canvas, FuseNative.JOYSTICK_LEFT, -1, 0);
        drawWay(canvas, FuseNative.JOYSTICK_RIGHT, 1, 0);

        // The knob, wherever the finger last put it.
        mark.setColor(knobX != 0 || knobY != 0 ? PRESSED : MARK);
        canvas.drawCircle(centreX + knobX, centreY + knobY, radius * KNOB, mark);
        mark.setColor(MARK);
    }

    /**
     * A ring with a word in it, and a second smaller one under it when there is
     * something to say. Text is shrunk to fit rather than clipped, because a
     * button's word is whatever key a profile put there and BREAK SPACE is five
     * times the width of M.
     */
    private void drawRound(Canvas canvas, String text, String under, boolean down) {
        face.setColor(down ? PRESSED : FACE);
        canvas.drawCircle(centreX, centreY, radius, face);
        canvas.drawCircle(centreX, centreY, radius, edge);
        face.setColor(FACE);

        float size = radius * (part == Part.KEY ? 0.62f : 0.42f);
        float room = radius * 1.6f;

        // Two lines are drawn either side of the middle rather than one in it.
        float offset = under == null ? 0f : radius * 0.2f;

        glyph(canvas, text, centreX, centreY - offset, size, room, MARK);
        if (under != null) {
            glyph(canvas, under, centreX, centreY + radius * 0.62f,
                  size * 0.78f, room, MARK);
        }
    }

    /**
     * A word centred on a point, shrunk to fit the room it is given rather than
     * clipped: what a control says is whatever key a profile put there, and
     * BREAK SPACE is five times the width of M.
     */
    private void glyph(Canvas canvas, String text, float x, float y, float size,
                       float room, int colour) {
        legend.setColor(colour);
        legend.setTextSize(size);

        float width = legend.measureText(text);
        if (width > room) legend.setTextSize(size * room / width);

        // Centred on the glyphs rather than on the line box, which sits low.
        Paint.FontMetrics metrics = legend.getFontMetrics();
        canvas.drawText(text, x, y - (metrics.ascent + metrics.descent) / 2f, legend);

        legend.setColor(MARK);
    }

    /**
     * One of the four ways, at the rim: an arrow, or the key it sends when the
     * pad is a Keyboard joystick.
     *
     * The key instead of the arrow rather than as well as it. Where the four sit
     * says which way each one is - that is what a pad is - so the arrow is the
     * part that can be spared, and a letter squeezed in beside one would be too
     * small to read at a glance while playing.
     */
    private void drawWay(Canvas canvas, int button, float dx, float dy) {
        if (!Controls.padSendsKeys()) {
            drawArrow(canvas, button, dx, dy);
            return;
        }

        glyph(canvas, ControlProfiles.label(Controls.key(button)),
              centreX + dx * radius * 0.66f, centreY + dy * radius * 0.66f,
              radius * 0.34f, radius * 0.62f, held[button] ? PRESSED : MARK);
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
        Controls.press(button, pressed);
    }

    private void sendKey(boolean pressed) {
        if (keyDown == pressed) return;

        keyDown = pressed;
        Controls.pressKey(slot, pressed);
    }

    /** Everything up: the finger left, or the view is being taken away. */
    private void releaseAll() {
        for (int button = 0; button < held.length; button++) send(button, false);
        if (part == Part.KEY) sendKey(false);

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
                if (part == Part.KEY) {
                    sendKey(true);
                    invalidate();
                } else if (part == Part.FIRE) {
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

    /**
     * A button is named for the key it sends, as it is drawn, so that a tester
     * or a screen reader asks for what it can see. That name changes when the
     * profile does — which is a handful of times ever, not the continuous
     * churn that once took the whole instrumentation suite down.
     */
    private String nameFor(int id) {
        switch (part) {
            case KEY: return "BUTTON " + ControlProfiles.label(Controls.key(slot));
            case FIRE: return FIRE_NAME;
            default: return PAD_NAMES[id];
        }
    }

    private int controlCount() {
        return part == Part.PAD ? PAD_NAMES.length : 1;
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

        if (part != Part.PAD) {
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
            if (part == Part.KEY) {
                sendKey(true);
                sendKey(false);
            } else {
                int button = buttonFor(virtualViewId);
                send(button, true);
                send(button, false);
            }
            invalidate();

            return true;
        }
    };

    @Override
    public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return provider;
    }
}
