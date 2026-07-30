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
 * order. The stick, the hat and the D-pad all steer. <b>Start</b> is Enter, which
 * is what a game asks for whatever its own keys are.
 *
 * Everything the app wants for itself is behind a <b>hotkey</b> instead — see
 * {@link Hotkeys}. A pad has no spare buttons, so rather than take four of them
 * away from the game, one button is a modifier and the rest mean one thing while
 * it is down and another while it is not.
 *
 * The triggers are read twice over. A trigger is a button on some pads and an
 * axis on others, and on a few it is both, so the axes are watched as well and
 * turned back into the buttons they would have been; only a change is passed on,
 * since motion events arrive in a stream.
 *
 * The two ways a direction can arrive are tracked apart and combined, because
 * many pads report the hat as both an axis and a D-pad key: a release down one
 * path would otherwise cancel a press that came down the other, and the stick
 * would stick.
 */
final class Gamepad {

    /** What a controller can ask of the app rather than of the machine. */
    interface Actions {
        /**
         * Runs one. {@code pressed} is only ever false for the held kind - the
         * others are told once, when the buttons go down.
         */
        void run(Hotkeys.Action action, boolean pressed);
    }

    private final Actions actions;

    /** The hotkey and what it carries. Replaced when the settings change. */
    private Hotkeys.Bindings keys = new Hotkeys.Bindings(0);

    /** Whether the hotkey is down now. */
    private boolean hotkeyDown;

    /** The held action currently running, so it can be ended once. */
    private Hotkeys.Action holding;

    Gamepad(Actions actions) {
        this.actions = actions;
    }

    void setHotkeys(Hotkeys.Bindings bindings) {
        // Whatever was held was held under the old arrangement.
        endHold();
        hotkeyDown = false;
        keys = bindings;
    }

    /** Whether a bound button would fire: the hotkey is down, or there is none. */
    private boolean armed() {
        return keys.modifier == 0 || hotkeyDown;
    }

    private void endHold() {
        if (holding == null) return;

        actions.run(holding, false);
        holding = null;
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

        if (hotkey(event.getKeyCode(), pressed)) return true;

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
            // that says PRESS ENTER wants Enter. On its own, that is - with the
            // hotkey down it is whatever the hotkey list says, which by default
            // is the way out of the app.
            case KeyEvent.KEYCODE_BUTTON_START:
                FuseNative.key(KeyEvent.KEYCODE_ENTER, pressed);
                return true;

            default:
                return false;
        }
    }

    /**
     * The hotkey and anything bound behind it. Returns whether the button was
     * spoken for, in which case the machine never hears it.
     *
     * Taken by button rather than by event so a trigger can come through here
     * too: on some pads L2 and R2 are buttons and on others they are axes, and a
     * hotkey has no business knowing which sort it was given.
     */
    private boolean hotkey(int keycode, boolean pressed) {
        // The hotkey first, since it decides what everything else means, and it
        // is swallowed either way: a modifier that also did something of its own
        // would do it every time anyone reached past it.
        if (keys.modifier != 0 && keycode == keys.modifier) {
            hotkeyDown = pressed;
            if (!pressed) endHold();
            return true;
        }

        Hotkeys.Action bound = keys.forButton(keycode);
        if (bound == null) return false;

        // Held actions have two ends and the hotkey may be let go first, so what
        // matters is whether both are down now - and only a change is sent on.
        if (bound.held) {
            boolean on = pressed && armed();

            if (on && holding != bound) {
                holding = bound;
                actions.run(bound, true);
            } else if (!on && holding == bound) {
                endHold();
            }

            // Let go of it unarmed and it is an ordinary button again.
            return armed();
        }

        if (!armed()) return false;

        // On the press only: a release has nothing to undo, and doing it twice
        // per push would open a dialog and close it again.
        if (pressed) actions.run(bound, true);
        return true;
    }

    private boolean way(int index, boolean pressed) {
        fromKeys[index] = pressed;
        steer();
        return true;
    }

    /**
     * Whether each trigger was past the line last time it was looked at: L2 then
     * R2. Motion events arrive in a stream, so without this a hotkey on a trigger
     * would fire again with every one of them.
     */
    private final boolean[] triggerDown = new boolean[2];

    private void trigger(int keycode, float value) {
        int index = keycode == KeyEvent.KEYCODE_BUTTON_L2 ? 0 : 1;
        boolean down = value >= DEAD_ZONE;

        if (down == triggerDown[index]) return;

        triggerDown[index] = down;
        hotkey(keycode, down);
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

        // The triggers, where they are axes rather than buttons, as the same
        // buttons they would have been. Whichever way round a pad reports them,
        // whatever is bound to L2 or R2 works.
        trigger(KeyEvent.KEYCODE_BUTTON_L2,
                Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                         event.getAxisValue(MotionEvent.AXIS_BRAKE)));
        trigger(KeyEvent.KEYCODE_BUTTON_R2,
                Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                         event.getAxisValue(MotionEvent.AXIS_GAS)));

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
        endHold();
        hotkeyDown = false;
        triggerDown[0] = triggerDown[1] = false;

        for (int i = 0; i < fromKeys.length; i++) {
            fromKeys[i] = false;
            fromAxes[i] = false;
        }
        for (int slot = 0; slot < sent.length; slot++) send(slot, false);
    }
}
