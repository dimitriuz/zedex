package dev.ldlab.zedex.input;

import dev.ldlab.zedex.FuseNative;
import dev.ldlab.zedex.screen.SettingsActivity;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

/**
 * The Kempston mouse, driven by a finger or by whatever is standing in for one.
 *
 * A Kempston mouse is <b>relative</b>: it has no idea where any pointer is, only
 * a pair of counters the program reads and does its own arithmetic on. That is
 * lucky, because a phone has no pointer either — so a drag across the picture is
 * a movement, and the joystick pushed in a direction is a movement that keeps
 * happening while it is held. Nothing has to know where the program thinks its
 * cursor is, which is the thing an absolute touchscreen could never tell it.
 *
 * <b>It is a mode</b>, and it has to be: while it is on, the pad and the stick
 * move the pointer rather than the joystick, fire and the first key button are
 * the mouse's two buttons, and a drag on the picture is a movement rather than a
 * tap. Turning it off puts all of that back, and unplugs the interface — which
 * is worth doing, since it answers three ports a game might read for something
 * else.
 *
 * Everything here runs on the UI thread and leaves through {@link FuseNative},
 * which queues for the emulation thread like every other input.
 */
public final class Mouse {

    private Mouse() {
    }

    /** Which button is which, as {@link FuseNative#mouseButton} takes them. */
    public static final int LEFT = 0;
    public static final int RIGHT = 1;

    /** How often a held direction nudges the pointer, in milliseconds. */
    private static final long NUDGE_MS = 16;

    /**
     * How far a held direction moves the pointer each nudge, at 100%.
     *
     * Six mouse units sixty times a second is about a third of the way across a
     * 256 pixel screen in a second, which is the pace of a trackpad rather than
     * of a mouse thrown across a desk - and a stick that crosses the screen in a
     * blink is no use for pointing at anything.
     */
    private static final float NUDGE_UNITS = 6f;

    private static boolean on;

    /** Per cent, from the settings; 100 is the pace described above. */
    private static int sensitivity = 100;

    /** Which way the pad or the D-pad is held, in Fuse's button order. */
    private static final boolean[] held = new boolean[4];

    /** How far a physical stick is pushed, -1 to 1. */
    private static float stickX, stickY;

    /**
     * What a drag or a nudge has moved that has not been sent yet.
     *
     * The mouse takes whole units and a drag scaled down is mostly fractions, so
     * the remainder is kept rather than thrown away - otherwise a slow drag
     * moves nothing at all and a fast one is short by however much was rounded
     * off each time.
     */
    private static float pendingX, pendingY;

    private static final Handler handler = new Handler(Looper.getMainLooper());

    private static final Runnable nudge = new Runnable() {
        @Override
        public void run() {
            if (!on) return;

            float x = ( held[FuseNative.JOYSTICK_RIGHT] ? 1 : 0 )
                    - ( held[FuseNative.JOYSTICK_LEFT] ? 1 : 0 ) + stickX;
            float y = ( held[FuseNative.JOYSTICK_DOWN] ? 1 : 0 )
                    - ( held[FuseNative.JOYSTICK_UP] ? 1 : 0 ) + stickY;

            if (x != 0 || y != 0) {
                move(x * NUDGE_UNITS, y * NUDGE_UNITS);
                handler.postDelayed(this, NUDGE_MS);
            }
        }
    };

    public static boolean enabled() {
        return on;
    }

    /**
     * Turns the mode on or off, and plugs the interface in or out with it.
     *
     * Everything held is let go on the way through: a button still down when the
     * mode ends would be a button the machine never sees released, and a
     * direction still held would go on nudging a pointer nothing is driving.
     */
    public static void setEnabled(boolean wanted) {
        if (on == wanted) return;

        releaseAll();
        on = wanted;

        FuseNative.setKempstonMouse(wanted);
    }

    private static void setSensitivity(int percent) {
        sensitivity = Math.max(10, Math.min(400, percent));
    }

    public static void apply(SharedPreferences preferences) {
        setSensitivity(SettingsActivity.SettingsFragment.number(
                preferences, SettingsActivity.KEY_MOUSE_SENSITIVITY, 100));
    }

    /** A finger dragging across the picture, in device pixels. */
    public static void drag(float dx, float dy) {
        if (!on) return;

        // A device pixel is finer than a mouse unit at any sensible sensitivity,
        // so this is where the scaling happens and where the remainder matters.
        move(dx * 0.35f, dy * 0.35f);
    }

    /** The pad or the D-pad, in Fuse's own button numbering. */
    public static void steer(int direction, boolean pressed) {
        if (direction < 0 || direction >= held.length) return;

        held[direction] = pressed;
        if (pressed) start();
    }

    /** A physical stick, as it is pushed. */
    public static void stick(float x, float y) {
        stickX = x;
        stickY = y;

        if (x != 0 || y != 0) start();
    }

    public static void button(int which, boolean down) {
        FuseNative.mouseButton(which, down);
    }

    /** Both buttons up, nothing held, and the pointer still. */
    public static void releaseAll() {
        for (int i = 0; i < held.length; i++) held[i] = false;
        stickX = stickY = 0;
        pendingX = pendingY = 0;

        handler.removeCallbacks(nudge);

        if (on) {
            button(LEFT, false);
            button(RIGHT, false);
        }
    }

    private static void start() {
        if (!on) return;

        handler.removeCallbacks(nudge);
        handler.post(nudge);
    }

    private static void move(float dx, float dy) {
        pendingX += dx * sensitivity / 100f;
        pendingY += dy * sensitivity / 100f;

        int x = (int) pendingX;
        int y = (int) pendingY;

        if (x == 0 && y == 0) return;

        pendingX -= x;
        pendingY -= y;

        FuseNative.mouseMove(x, y);
    }
}
