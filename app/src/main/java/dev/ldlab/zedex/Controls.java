package dev.ldlab.zedex;

/**
 * Where a control's press goes: Fuse's joystick, or a key.
 *
 * One place, because there is more than one thing pressing: the pad and fire,
 * the three buttons beside fire, a screen reader's click, and — when there is
 * one — a physical gamepad. All of them want the same answer to the same
 * question, and the answer depends on two pieces of state that only the settings
 * change.
 *
 * The pad's five controls go to Fuse as a joystick for every type Fuse knows
 * about, and as keys for <i>Keyboard</i>, which is ours. The three buttons are
 * always keys. Nothing else differs, which is why this is eight lines of routing
 * rather than two implementations.
 *
 * Static, like {@link FuseNative}: the routing is a property of the machine and
 * the settings, not of any one view, and three views would otherwise each need
 * telling.
 */
final class Controls {

    private Controls() {
    }

    /**
     * Our own joystick type, well past anything Fuse's {@code joystick_type_t}
     * can hold: the pad sends the profile's five keys instead of going to an
     * interface. Chosen large rather than one past Fuse's list so that a stored
     * setting cannot come to mean a real interface if that list ever grows.
     */
    static final int JOYSTICK_KEYBOARD = 1000;

    /** Fuse's own None, which is what it is told to use for ours. */
    static final int JOYSTICK_NONE = 0;

    private static int[] keys = ControlProfiles.QAOPM.clone();

    /** Whether the pad sends keys rather than joystick buttons. */
    private static boolean padSendsKeys;

    static void setProfile(int[] profile) {
        keys = profile.clone();
    }

    static void setPadSendsKeys(boolean sendsKeys) {
        padSendsKeys = sendsKeys;
    }

    /** Whether the pad's controls are keys, which fire says on its face. */
    static boolean padSendsKeys() {
        return padSendsKeys;
    }

    /** The key in a slot, for a control that has to draw its own name. */
    static int key(int slot) {
        return slot >= 0 && slot < keys.length ? keys[slot] : 0;
    }

    /**
     * One of the pad's five, as either a joystick button or its key.
     *
     * {@code button} is one of Fuse's {@code joystick_button} values, which is
     * also its slot in the profile — see {@link ControlProfiles}.
     */
    static void press(int button, boolean pressed) {
        // Mouse mode first, since it takes the pad away from the machine
        // altogether: the four directions move the pointer and fire is the left
        // button. Here rather than in each view, for the same reason the rest of
        // this class is here - the pad, the gamepad and a screen reader all
        // arrive at this one door.
        if (Mouse.enabled()) {
            if (button == FuseNative.JOYSTICK_FIRE) {
                Mouse.button(Mouse.LEFT, pressed);
            } else {
                Mouse.steer(button, pressed);
            }
            return;
        }

        if (padSendsKeys) {
            FuseNative.key(key(button), pressed);
        } else {
            FuseNative.joystick(button, pressed);
        }
    }

    /** One of the three buttons, which is always a key - or the right button. */
    static void pressKey(int slot, boolean pressed) {
        if (Mouse.enabled() && slot == ControlProfiles.BUTTON_1) {
            Mouse.button(Mouse.RIGHT, pressed);
            return;
        }

        FuseNative.key(key(slot), pressed);
    }
}
