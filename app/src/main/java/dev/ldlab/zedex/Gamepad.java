package dev.ldlab.zedex;

import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * A physical controller, driving the same five controls the on-screen pad does.
 *
 * There is nothing to set up: a connected pad works, and it goes through
 * {@link Controls} exactly as the on-screen one does, so it comes out as
 * whichever interface the joystick type says — or as the profile's keys when
 * that type is Keyboard. A game that wants QAOP is playable on a gamepad without
 * the gamepad knowing what a Q is.
 *
 * <b>A is fire</b>, and B, X and Y are the three key buttons beside it, in that
 * order. The stick, the hat and the D-pad all steer.
 *
 * The shoulders and the middle two are the app rather than the machine, because
 * a controller is usually the only thing in reach: <b>Start</b> is Enter, which
 * is what a game asks for whatever its own keys are; <b>Select</b> puts the
 * on-screen keyboard away and brings it back; <b>L1</b> loads a state, <b>R1</b>
 * saves one, and <b>R2</b> held runs the machine fast. Those are not part of a
 * profile - they are the app's own, and rebinding them would only hide them.
 *
 * R2 is read twice over. A trigger is a button on some pads and an axis on
 * others, and on a few it is both, so the axis is watched as well and whichever
 * arrives first wins - {@code fastForward()} is told a change and not a press.
 *
 * The two ways a direction can arrive are tracked apart and combined, because
 * many pads report the hat as both an axis and a D-pad key: a release down one
 * path would otherwise cancel a press that came down the other, and the stick
 * would stick.
 */
final class Gamepad {

    /** What a controller can ask of the app rather than of the machine. */
    interface Actions {
        void toggleKeyboard();
        void loadState();
        void saveState();

        /** Held down rather than tapped: on going down, off coming up. */
        void fastForward(boolean on);
    }

    private final Actions actions;

    Gamepad(Actions actions) {
        this.actions = actions;
    }

    /** Past this, from the middle, a stick is pushed. */
    private static final float DEAD_ZONE = 0.4f;

    /** Fuse's four directions, as this class indexes its own state. */
    private static final int[] WAYS = {
        FuseNative.JOYSTICK_LEFT, FuseNative.JOYSTICK_RIGHT,
        FuseNative.JOYSTICK_UP, FuseNative.JOYSTICK_DOWN,
    };

    private final boolean[] fromKeys = new boolean[4];
    private final boolean[] fromAxes = new boolean[4];

    /** What has actually been sent, so only changes are. */
    private final boolean[] sent = new boolean[ControlProfiles.SLOTS];

    /** Whether any pad or stick is plugged in at this moment. */
    static boolean connected() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device != null && isPad(device.getSources())) return true;
        }
        return false;
    }

    /**
     * Whether an event came from a controller rather than a keyboard.
     *
     * Worth asking because a keyboard's arrow keys are {@code DPAD_*} too, and
     * the machine has its own use for those: swallowing them here would take the
     * cursor keys away from anyone playing on a Bluetooth keyboard.
     */
    static boolean isFrom(InputEvent event) {
        return isPad(event.getSource());
    }

    private static boolean isPad(int sources) {
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    /**
     * A button or D-pad event. Returns whether it was one this understands —
     * anything else has to go back to Android, since a pad has buttons that
     * belong to the system.
     */
    boolean key(KeyEvent event) {
        if (!isFrom(event)) return false;

        // Auto-repeat would be a stream of presses with no release between.
        if (event.getRepeatCount() > 0) return true;

        boolean pressed = event.getAction() == KeyEvent.ACTION_DOWN;

        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:  return way(0, pressed);
            case KeyEvent.KEYCODE_DPAD_RIGHT: return way(1, pressed);
            case KeyEvent.KEYCODE_DPAD_UP:    return way(2, pressed);
            case KeyEvent.KEYCODE_DPAD_DOWN:  return way(3, pressed);

            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                send(FuseNative.JOYSTICK_FIRE, pressed);
                return true;

            case KeyEvent.KEYCODE_BUTTON_B:
                send(ControlProfiles.BUTTON_1, pressed);
                return true;

            case KeyEvent.KEYCODE_BUTTON_X:
                send(ControlProfiles.BUTTON_2, pressed);
                return true;

            case KeyEvent.KEYCODE_BUTTON_Y:
                send(ControlProfiles.BUTTON_3, pressed);
                return true;

            // Enter as itself, not as whatever Button 1 happens to hold: a game
            // that says PRESS ENTER wants Enter.
            case KeyEvent.KEYCODE_BUTTON_START:
                FuseNative.key(KeyEvent.KEYCODE_ENTER, pressed);
                return true;

            // The app's own three, on the press only - a release has nothing to
            // undo, and doing them twice per push would open and close a dialog.
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                if (pressed) actions.toggleKeyboard();
                return true;

            case KeyEvent.KEYCODE_BUTTON_L1:
                if (pressed) actions.loadState();
                return true;

            case KeyEvent.KEYCODE_BUTTON_R1:
                if (pressed) actions.saveState();
                return true;

            // The one that is held rather than tapped, so both ends matter.
            case KeyEvent.KEYCODE_BUTTON_R2:
                actions.fastForward(pressed);
                return true;

            default:
                return false;
        }
    }

    private boolean way(int index, boolean pressed) {
        fromKeys[index] = pressed;
        steer();
        return true;
    }

    /** The stick and the hat, which arrive as axes rather than as keys. */
    boolean motion(MotionEvent event) {
        if (!isFrom(event) || event.getAction() != MotionEvent.ACTION_MOVE) {
            return false;
        }

        float x = axis(event, MotionEvent.AXIS_X, MotionEvent.AXIS_HAT_X);
        float y = axis(event, MotionEvent.AXIS_Y, MotionEvent.AXIS_HAT_Y);

        fromAxes[0] = x <= -DEAD_ZONE;
        fromAxes[1] = x >= DEAD_ZONE;
        fromAxes[2] = y <= -DEAD_ZONE;
        fromAxes[3] = y >= DEAD_ZONE;

        // The right trigger, where it is an axis rather than a button.
        float trigger = Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                                 event.getAxisValue(MotionEvent.AXIS_GAS));
        actions.fastForward(trigger >= DEAD_ZONE);

        steer();
        return true;
    }

    /** Whichever of the stick and the hat is pushed furthest. */
    private static float axis(MotionEvent event, int stick, int hat) {
        float one = event.getAxisValue(stick);
        float other = event.getAxisValue(hat);

        return Math.abs(one) >= Math.abs(other) ? one : other;
    }

    /**
     * Sends what the two paths add up to.
     *
     * Releases before presses, for the reason {@code JoystickView.steer()} does
     * it: Fuse holds a release over to the next frame, so a swing from left to
     * right would otherwise be read as a stick pushed both ways at once.
     */
    private void steer() {
        for (int i = 0; i < WAYS.length; i++) {
            if (!fromKeys[i] && !fromAxes[i]) send(WAYS[i], false);
        }
        for (int i = 0; i < WAYS.length; i++) {
            if (fromKeys[i] || fromAxes[i]) send(WAYS[i], true);
        }
    }

    private void send(int slot, boolean pressed) {
        if (sent[slot] == pressed) return;

        sent[slot] = pressed;

        if (slot < ControlProfiles.BUTTON_1) {
            Controls.press(slot, pressed);
        } else {
            Controls.pressKey(slot, pressed);
        }
    }

    /**
     * Everything up. A pad unplugged mid-press, or an app sent to the
     * background, would otherwise leave the machine holding a direction that
     * nothing is going to let go of.
     */
    void releaseAll() {
        actions.fastForward(false);

        for (int i = 0; i < fromKeys.length; i++) {
            fromKeys[i] = false;
            fromAxes[i] = false;
        }
        for (int slot = 0; slot < sent.length; slot++) send(slot, false);
    }
}
