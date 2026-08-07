package dev.ldlab.zedex.library.ui;

import dev.ldlab.zedex.input.Gamepad;

import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Turns a physical controller's D-pad, stick, triggers and four buttons into
 * the six things {@link dev.ldlab.zedex.screen.LibraryActivity} already
 * offers by touch: a cursor moving through the list a step or a page at a
 * time, play, back, favourite, and the tab switch. See docs/LIBRARY.md for
 * the screen this drives.
 *
 * Deliberately not {@link Gamepad}: that class drives the emulator's own five
 * controls, is shared and in active use, and is not this screen's to change.
 * This reads only the subset of a pad the library needs and answers to a
 * screen instead of a machine - but it is built on the same two lessons
 * {@link Gamepad} already paid for, solved the same way on purpose rather
 * than reinvented:
 *
 * <ul>
 * <li><b>A direction can arrive as a D-pad key, as a hat axis, or - on many
 * pads - as both at once for the same physical push; a trigger the same way,
 * as a button on some pads, an axis on others, and both on a few.</b> {@link
 * Gamepad} tracks the two paths apart and combines them so a release down one
 * does not cancel a press that came down the other; a discrete cursor has the
 * opposite failure to avoid - one push must be one step, not two - so here
 * the two paths are tracked apart and only the first to claim a direction or
 * a page drives it. The other path's events still update the bookkeeping, so
 * letting go of either releases it, but they cause no second step - see
 * {@link #owner}, {@link #claim} and {@link #release}.</li>
 * <li><b>The stick is analogue and the list is discrete.</b> A push past the
 * deadzone moves the cursor once and then again at a steady rate from a
 * {@link Handler}, never from however often the device happens to report
 * motion, which varies by device. The D-pad needs none of this: unlike
 * {@link Gamepad#key}, which throws away a key's own auto-repeat because
 * held-down is all the emulator's joystick wants, this lets every repeat
 * through as one more step - Android's own key repeat already paces holding
 * a direction, which is exactly the feel wanted here. A trigger is different
 * again: unlike a direction it has no on-screen twin whose own auto-repeat
 * can be trusted to feel like the stick's, so both of its paths - a genuine
 * {@code ACTION_DOWN} and an axis crossing the deadzone alike - drive the
 * same {@link Handler} the stick uses, at the same cadence, and a key event's
 * own repeat is ignored in favour of it - see {@link #pageKey}.</li>
 * </ul>
 */
public final class GamepadCursor {

    /** The eight things a pad can ask of the screen; {@code LibraryActivity}
     *  is the only implementation. */
    public interface Nav {
        /** Moves the cursor by whole cells and selects whatever it lands on -
         *  {@code dx} within a row, {@code dy} by a row, never both at once. */
        void move(int dx, int dy);
        /** L2/R2: {@code -1} or {@code 1}, a full screenful of rows either
         *  way - see {@code LibraryActivity.pageSize}. */
        void page(int rows);
        /** A: plays a file, or enters a folder or an archive. */
        void activate();
        /** B: up one level of Browse's stack; nothing at the root, or off it -
         *  or, with the search field focused, drops it back to the list
         *  instead, without leaving the folder. */
        void back();
        /** Y: the same toggle a long press performs. */
        void toggleFavorite();
        /** L1/R1: -1 or +1, through Browse, Favourites and Recents. */
        void tab(int delta);
        /** X: focuses the search field and brings the keyboard up. */
        void search();
        /** Select, or the right stick's own click: the sort field, its
         *  direction and list-or-grid, all in one dialog - see {@code
         *  dev.ldlab.zedex.library.ui.OptionsDialog}. */
        void options();
    }

    private static final int LEFT = 0, RIGHT = 1, UP = 2, DOWN = 3;
    private static final int PAGE_UP = 4, PAGE_DOWN = 5;
    private static final int SLOTS = 6;

    /** Past this, from the middle, the stick is pushed, and past this a
     *  trigger is pulled - the same line {@link Gamepad} uses for both, for
     *  the same reason. */
    private static final float DEAD_ZONE = 0.4f;

    /** A pause before a direction or a page starts repeating, then a steady
     *  rate after - the same shape as holding a key down. The D-pad's own key
     *  path needs neither, since its own key repeat already paces it; a
     *  trigger's does, on both of its paths - see the class comment. */
    private static final int REPEAT_DELAY_MS = 400;
    private static final int REPEAT_INTERVAL_MS = 130;

    private static final int NONE = 0, KEY = 1, AXIS = 2;

    private final Nav nav;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Which path is driving each of the six slots right now, so the other
     *  path's events are seen but produce no second step. */
    private final int[] owner = new int[SLOTS];

    /** Whether the merged stick/hat axis, or a trigger read as an axis, is
     *  past the deadzone for each slot, so a stream of motion events acts
     *  only on a crossing. */
    private final boolean[] axisDown = new boolean[SLOTS];

    private final Runnable[] repeaters = {
        () -> repeatStep(LEFT), () -> repeatStep(RIGHT),
        () -> repeatStep(UP), () -> repeatStep(DOWN),
        () -> repeatStep(PAGE_UP), () -> repeatStep(PAGE_DOWN),
    };

    public GamepadCursor(Nav nav) {
        this.nav = nav;
    }

    /**
     * A button or D-pad event. Returns whether it was one this understands -
     * anything else has to go back to Android, exactly as {@link
     * Gamepad#key} already does for the same reason.
     */
    public boolean key(KeyEvent event) {
        if (!Gamepad.isFrom(event)) return false;

        boolean pressed = event.getAction() == KeyEvent.ACTION_DOWN;

        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:  direction(LEFT, pressed);  return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: direction(RIGHT, pressed); return true;
            case KeyEvent.KEYCODE_DPAD_UP:    direction(UP, pressed);   return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:  direction(DOWN, pressed); return true;

            // The same button either code names, on the pads that send one
            // rather than the other - see Gamepad.key for the same pairing.
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                once(event, pressed, nav::activate);
                return true;

            case KeyEvent.KEYCODE_BUTTON_B:
                once(event, pressed, nav::back);
                return true;

            case KeyEvent.KEYCODE_BUTTON_Y:
                once(event, pressed, nav::toggleFavorite);
                return true;

            case KeyEvent.KEYCODE_BUTTON_L1:
                once(event, pressed, () -> nav.tab(-1));
                return true;

            case KeyEvent.KEYCODE_BUTTON_R1:
                once(event, pressed, () -> nav.tab(1));
                return true;

            case KeyEvent.KEYCODE_BUTTON_L2:
                pageKey(PAGE_UP, pressed, event.getRepeatCount());
                return true;

            case KeyEvent.KEYCODE_BUTTON_R2:
                pageKey(PAGE_DOWN, pressed, event.getRepeatCount());
                return true;

            case KeyEvent.KEYCODE_BUTTON_X:
                once(event, pressed, nav::search);
                return true;

            // Both, since Select is the least consistently reported button
            // across pads - a right stick that also clicks costs nothing to
            // bind the same way.
            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                once(event, pressed, nav::options);
                return true;

            default:
                return false;
        }
    }

    /** Runs a one-shot action on the press only, never on the auto-repeat a
     *  button held down produces - unlike a direction, launching a game or
     *  flipping a favourite twice a second is not what holding it should do. */
    private void once(KeyEvent event, boolean pressed, Runnable action) {
        if (pressed && event.getRepeatCount() == 0) action.run();
    }

    /**
     * A key-path press or release for one of the four directions.
     *
     * Every press this class is told about steps once, including Android's
     * own auto-repeat while the key stays down - see the class comment - but
     * only while the axis path is not already the one driving this direction;
     * if it is, a key event for the same physical push is bookkeeping only, so
     * that whichever one lets go last is the one that releases it.
     */
    private void direction(int index, boolean pressed) {
        if (!pressed) {
            if (owner[index] == KEY) owner[index] = NONE;
            return;
        }

        if (owner[index] == AXIS) return;

        owner[index] = KEY;
        step(index);
    }

    /**
     * A key-path press or release for L2 or R2. Unlike {@link #direction},
     * which lets Android's own key repeat drive every step, a trigger has no
     * repeat of its own worth trusting - a pad's driver may not send one at
     * all, and one that does answers to nobody's idea of the stick's cadence
     * but its own - so the first press claims the slot exactly the axis path
     * would and starts the same {@link Handler} repeat, and every repeat
     * Android sends after that is ignored in favour of it.
     */
    private void pageKey(int index, boolean pressed, int repeatCount) {
        if (!pressed) {
            release(index, KEY);
            return;
        }

        if (repeatCount > 0) return;

        claim(index, KEY);
    }

    /** The stick, the hat and the triggers, which all arrive as axes rather
     *  than as keys. */
    public boolean motion(MotionEvent event) {
        if (!Gamepad.isFrom(event) || event.getAction() != MotionEvent.ACTION_MOVE) {
            return false;
        }

        float x = axis(event, MotionEvent.AXIS_X, MotionEvent.AXIS_HAT_X);
        float y = axis(event, MotionEvent.AXIS_Y, MotionEvent.AXIS_HAT_Y);

        edge(LEFT,  x <= -DEAD_ZONE);
        edge(RIGHT, x >=  DEAD_ZONE);
        edge(UP,    y <= -DEAD_ZONE);
        edge(DOWN,  y >=  DEAD_ZONE);

        // A trigger reads from the middle up rather than from a centre, and
        // as one axis rather than two the way the stick's opposite ends are -
        // AXIS_LTRIGGER/AXIS_RTRIGGER on most pads, AXIS_BRAKE/AXIS_GAS on the
        // ones that report it as though it were a wheel's pedals instead; see
        // Gamepad.motion, which reads the same four for the same reason.
        edge(PAGE_UP, Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                                event.getAxisValue(MotionEvent.AXIS_BRAKE)) >= DEAD_ZONE);
        edge(PAGE_DOWN, Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                                  event.getAxisValue(MotionEvent.AXIS_GAS)) >= DEAD_ZONE);

        return true;
    }

    /** Whichever of the stick and the hat is pushed furthest - the same merge
     *  {@link Gamepad}'s own {@code axis} does, for the same reason: many pads
     *  report the hat as an axis too, and either one might be the one held. */
    private static float axis(MotionEvent event, int stick, int hat) {
        float one = event.getAxisValue(stick);
        float other = event.getAxisValue(hat);

        return Math.abs(one) >= Math.abs(other) ? one : other;
    }

    /**
     * A crossing of the deadzone for one slot, from a stream of motion
     * events that says the same thing many times over - acted on only when it
     * changes. Shared by the four directions and the two page slots alike:
     * a trigger pulled past the deadzone is "pushed" in exactly the sense a
     * direction is.
     */
    private void edge(int index, boolean pushed) {
        if (pushed == axisDown[index]) return;
        axisDown[index] = pushed;

        if (pushed) claim(index, AXIS);
        else release(index, AXIS);
    }

    /**
     * Gives a slot to a path, if nothing already holds it - the other path's
     * own claim otherwise, which this leaves running rather than restarting.
     * Steps once immediately and starts this slot's repeat, exactly the
     * shape holding a key down already has.
     */
    private void claim(int index, int by) {
        if (owner[index] != NONE) return;

        owner[index] = by;
        step(index);
        handler.postDelayed(repeaters[index], REPEAT_DELAY_MS);
    }

    /**
     * Gives a slot back, but only if {@code from} is the path that holds it -
     * a release from the path that does not is bookkeeping only, exactly the
     * guard {@code claim} makes the other way round. This is what stops one
     * path's release from cancelling a repeat the other path is still owed:
     * a trigger read as both a button and an axis at once, with the axis
     * momentarily dipping back under the deadzone while the button is still
     * down, must not lose its repeat to that dip.
     */
    private void release(int index, int from) {
        if (owner[index] != from) return;

        owner[index] = NONE;
        handler.removeCallbacks(repeaters[index]);
    }

    private void repeatStep(int index) {
        if (owner[index] == NONE) return;

        step(index);
        handler.postDelayed(repeaters[index], REPEAT_INTERVAL_MS);
    }

    private void step(int index) {
        switch (index) {
            case LEFT:  nav.move(-1, 0); break;
            case RIGHT: nav.move(1, 0);  break;
            case UP:    nav.move(0, -1); break;
            case DOWN:  nav.move(0, 1);  break;
            case PAGE_UP:   nav.page(-1); break;
            case PAGE_DOWN: nav.page(1);  break;
            default: break;
        }
    }

    /**
     * Everything let go - the screen leaving the window, most likely - so a
     * repeat left running does not go on moving a cursor nobody can see.
     */
    public void release() {
        for (Runnable repeater : repeaters) handler.removeCallbacks(repeater);

        for (int i = 0; i < owner.length; i++) {
            owner[i] = NONE;
            axisDown[i] = false;
        }
    }
}
