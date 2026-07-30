package dev.ldlab.zedex;

import android.content.SharedPreferences;
import android.util.SparseArray;
import android.view.KeyEvent;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * What a controller's buttons do to the app, as against to the machine.
 *
 * One button is the <b>hotkey</b> — Select unless it is changed — and every
 * action is that button <i>and</i> another: Select+R1 saves a state, R1 on its
 * own is left alone. That is RetroArch's arrangement and it is the right one for
 * a pad, because a pad has no spare buttons: the four faces are fire and the key
 * profile, the stick steers, and anything the app wants for itself would
 * otherwise be taken away from the game. With a modifier nothing is taken away —
 * a button means one thing while the hotkey is down and another while it is not,
 * and there is never a question which.
 *
 * The hotkey may also be set to <b>none</b>, and then the bindings fire on their
 * own. For a pad with buttons to spare, or a second pad kept for the purpose.
 *
 * Stored as Android keycodes against the action's own name, so a keycode that
 * means nothing here is ignored rather than shifting everything after it, and
 * the enum can be reordered without spoiling what anyone has saved.
 */
final class Hotkeys {

    private Hotkeys() {
    }

    /**
     * The things a hotkey can do.
     *
     * All of them are things the app already does from a menu, which is the test
     * for being here: a hotkey is a shortcut to something, not a feature of its
     * own. {@code held} marks the one kind that runs while the buttons are down
     * rather than once when they go down.
     */
    enum Action {
        PAUSE(R.string.hotkey_pause, false),
        RESET(R.string.hotkey_reset, false),
        NMI(R.string.hotkey_nmi, false),
        QUIT(R.string.hotkey_quit, false),

        QUICK_SAVE(R.string.hotkey_quick_save, false),
        QUICK_LOAD(R.string.hotkey_quick_load, false),
        SAVE_STATE(R.string.hotkey_save_state, false),
        LOAD_STATE(R.string.hotkey_load_state, false),

        FAST_FORWARD(R.string.hotkey_fast_forward, true),
        SPEED_UP(R.string.hotkey_speed_up, false),
        SPEED_DOWN(R.string.hotkey_speed_down, false),

        FULLSCREEN(R.string.hotkey_fullscreen, false),
        SCREENSHOT(R.string.hotkey_screenshot, false),
        RECORD(R.string.hotkey_record, false),

        KEYBOARD(R.string.hotkey_keyboard, false),
        JOYSTICK(R.string.hotkey_joystick, false),
        INDICATORS(R.string.hotkey_indicators, false),
        NEXT_PROFILE(R.string.hotkey_next_profile, false),
        NEXT_JOYSTICK(R.string.hotkey_next_joystick, false),

        MENU(R.string.hotkey_menu, false),
        QUICK_BAR(R.string.hotkey_quick_bar, false),
        SETTINGS(R.string.hotkey_settings, false);

        /** What the editor calls it. */
        final int title;

        /** Whether it runs while held rather than once on the press. */
        final boolean held;

        Action(int title, boolean held) {
            this.title = title;
            this.held = held;
        }
    }

    /** Which button is the hotkey, as an Android keycode; 0 for none. */
    static final String KEY_MODIFIER = "hotkeyButton";

    /** A JSON object of action name to keycode. */
    static final String KEY_BINDINGS = "hotkeyBindings";

    /**
     * What a pad does before anyone changes anything — the five that were
     * hard-wired before this existed, with Select promoted from "hide the
     * keyboard" to the hotkey itself and the keyboard moved to Y.
     *
     * Quit on Start is RetroArch's, and it is the one worth having by default:
     * a pad in a stand with the phone across the room needs a way out.
     */
    private static final Object[][] DEFAULTS = {
        { Action.QUIT, KeyEvent.KEYCODE_BUTTON_START },
        { Action.LOAD_STATE, KeyEvent.KEYCODE_BUTTON_L1 },
        { Action.SAVE_STATE, KeyEvent.KEYCODE_BUTTON_R1 },
        { Action.FAST_FORWARD, KeyEvent.KEYCODE_BUTTON_R2 },
        { Action.KEYBOARD, KeyEvent.KEYCODE_BUTTON_Y },
    };

    /**
     * A snapshot of the lot, for the thread that reads events.
     *
     * Read for every button on the pad, so it is looked up rather than parsed:
     * {@link Gamepad} holds one of these and is handed a new one when the
     * settings change.
     */
    static final class Bindings {
        /** The hotkey's keycode, or 0 while there is none. */
        final int modifier;

        private final SparseArray<Action> byButton = new SparseArray<>();

        Bindings(int modifier) {
            this.modifier = modifier;
        }

        void put(Action action, int keycode) {
            if (keycode != 0) byButton.put(keycode, action);
        }

        /** The action this button carries, or null. */
        Action forButton(int keycode) {
            return byButton.get(keycode);
        }
    }

    static Bindings load(SharedPreferences preferences) {
        Bindings bindings = new Bindings(modifier(preferences));
        String stored = preferences.getString(KEY_BINDINGS, null);

        if (stored == null) {
            for (Object[] entry : DEFAULTS) {
                bindings.put((Action) entry[0], (Integer) entry[1]);
            }
            return bindings;
        }

        try {
            JSONObject object = new JSONObject(stored);

            for (Action action : Action.values()) {
                int keycode = object.optInt(action.name(), 0);
                bindings.put(action, keycode);
            }
        } catch (JSONException e) {
            // A binding list is not worth an error message: an empty one means
            // the hotkey does nothing, which is visible in the editor and
            // mendable there.
        }

        return bindings;
    }

    static int modifier(SharedPreferences preferences) {
        return preferences.getInt(KEY_MODIFIER, KeyEvent.KEYCODE_BUTTON_SELECT);
    }

    static void setModifier(SharedPreferences preferences, int keycode) {
        preferences.edit().putInt(KEY_MODIFIER, keycode).apply();
    }

    /** The button bound to an action, for the editor to show. 0 for none. */
    static int keycodeFor(SharedPreferences preferences, Action action) {
        String stored = preferences.getString(KEY_BINDINGS, null);

        if (stored == null) {
            for (Object[] entry : DEFAULTS) {
                if (entry[0] == action) return (Integer) entry[1];
            }
            return 0;
        }

        try {
            return new JSONObject(stored).optInt(action.name(), 0);
        } catch (JSONException e) {
            return 0;
        }
    }

    /**
     * Binds a button to an action, or clears it with 0.
     *
     * One button, one action: giving a button to something else takes it away
     * from whatever had it, because two actions on one press is not a thing
     * anyone means to ask for and an editor that allowed it would only be
     * hiding which of the two would win.
     */
    static void bind(SharedPreferences preferences, Action action, int keycode) {
        JSONObject object = new JSONObject();

        try {
            for (Action other : Action.values()) {
                int existing = keycodeFor(preferences, other);

                if (other == action) continue;
                if (existing == 0 || (keycode != 0 && existing == keycode)) continue;

                object.put(other.name(), existing);
            }

            if (keycode != 0) object.put(action.name(), keycode);
        } catch (JSONException e) {
            return;
        }

        preferences.edit().putString(KEY_BINDINGS, object.toString()).apply();
    }

    /** Every button worth offering, in the order a pad wears them. */
    static int[] buttons() {
        return new int[] {
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_MODE,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_C, KeyEvent.KEYCODE_BUTTON_Z,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        };
    }

    /**
     * What a pad's button is called, in the words printed on a pad rather than
     * Android's - {@code KEYCODE_BUTTON_THUMBL} is the left stick's click, and
     * nobody has ever called it that.
     */
    static String buttonName(int keycode) {
        switch (keycode) {
            case 0: return "—";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "Select";
            case KeyEvent.KEYCODE_BUTTON_START: return "Start";
            case KeyEvent.KEYCODE_BUTTON_MODE: return "Mode";
            case KeyEvent.KEYCODE_BUTTON_L1: return "L1";
            case KeyEvent.KEYCODE_BUTTON_R1: return "R1";
            case KeyEvent.KEYCODE_BUTTON_L2: return "L2";
            case KeyEvent.KEYCODE_BUTTON_R2: return "R2";
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return "Left stick click";
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return "Right stick click";
            case KeyEvent.KEYCODE_BUTTON_A: return "A";
            case KeyEvent.KEYCODE_BUTTON_B: return "B";
            case KeyEvent.KEYCODE_BUTTON_X: return "X";
            case KeyEvent.KEYCODE_BUTTON_Y: return "Y";
            case KeyEvent.KEYCODE_BUTTON_C: return "C";
            case KeyEvent.KEYCODE_BUTTON_Z: return "Z";
            case KeyEvent.KEYCODE_DPAD_UP: return "D-pad up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "D-pad down";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "D-pad left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "D-pad right";
            default: {
                String name = KeyEvent.keyCodeToString(keycode);
                return name.startsWith("KEYCODE_BUTTON_")
                        ? name.substring("KEYCODE_BUTTON_".length())
                        : name.startsWith("KEYCODE_")
                                ? name.substring("KEYCODE_".length()) : name;
            }
        }
    }
}
