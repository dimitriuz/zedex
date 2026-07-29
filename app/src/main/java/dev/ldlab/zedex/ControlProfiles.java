package dev.ldlab.zedex;

import android.content.SharedPreferences;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Named sets of keys for the on-screen controls.
 *
 * A profile is eight keys: the pad's four directions and fire, then the three
 * buttons beside fire. The first five only mean anything when the joystick type
 * is <i>Keyboard</i> — every other type is a real Spectrum interface and the pad
 * goes to Fuse as a joystick — but the three buttons are keys whatever the type
 * is, since they exist to reach a key the game wants that is not a direction.
 *
 * One profile rather than two, and it is a whole set rather than a switch per
 * slot, because what a game wants is a set: QAOP with Enter to start is one
 * thing to name and one thing to choose. A physical gamepad, when there is one,
 * will read the same profile.
 *
 * <b>Slots 0 to 4 are Fuse's own {@code joystick_button} numbers</b> — left,
 * right, up, down, fire — so a profile can be indexed by one directly and the
 * pad needs no table of its own. The three buttons follow.
 *
 * Keys are stored as Android keycodes, which is what the whole app speaks: the
 * on-screen keyboard reports them, {@link FuseNative#key} takes them, and Fuse's
 * own keysym table maps them — {@code SHIFT_LEFT} is CAPS SHIFT and
 * {@code CTRL_LEFT} is SYMBOL SHIFT. Nothing here needs to know that.
 */
final class ControlProfiles {

    private ControlProfiles() {
    }

    /** The three buttons, after Fuse's five. */
    static final int BUTTON_1 = 5;
    static final int BUTTON_2 = 6;
    static final int BUTTON_3 = 7;

    static final int SLOTS = 8;

    /** A JSON array of {@code {name, keys}}; see {@link #all}. */
    static final String KEY_PROFILES = "controlProfiles";

    /** Which of them is in use, as an index. */
    static final String KEY_CURRENT = "controlProfile";

    /**
     * The classic one, and what every new profile starts from: Q up, A down,
     * O left, P right, M fire - QAOPM, which is what the games that wanted it
     * called it - then Enter, Space and SYMBOL SHIFT.
     *
     * In slot order, so the first five are in Fuse's button order rather than
     * the order they are read out in.
     */
    static final int[] QAOPM = {
        KeyEvent.KEYCODE_O,             // JOYSTICK_LEFT
        KeyEvent.KEYCODE_P,             // JOYSTICK_RIGHT
        KeyEvent.KEYCODE_Q,             // JOYSTICK_UP
        KeyEvent.KEYCODE_A,             // JOYSTICK_DOWN
        KeyEvent.KEYCODE_M,             // JOYSTICK_FIRE
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_CTRL_LEFT,     // SYMBOL SHIFT
    };

    /**
     * The other layouts a Spectrum game is likely to offer, since a game that
     * takes the keyboard names the keys it wants and they are nearly always one
     * of these.
     *
     * Cursor and the two Sinclair sets are the same keys the Protek and
     * Interface II hardware pressed, and Fuse can pretend to be either of those
     * instead - but plenty of games read the keys directly and never look at a
     * port, and for those the interface does nothing while these work.
     *
     * Each is in slot order like {@link #QAOPM}, and each keeps the same three
     * buttons: a game that wants keys for the stick still wants Enter to start.
     */
    private static final Object[][] TYPICAL = {
        { "QAOP + Space", new int[] {
            KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_P,
            KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_SPACE } },

        { "Cursor keys", new int[] {
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_8,
            KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_6,
            KeyEvent.KEYCODE_0 } },

        { "Sinclair 1 keys", new int[] {
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2,
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_3,
            KeyEvent.KEYCODE_5 } },

        { "Sinclair 2 keys", new int[] {
            KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7,
            KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_8,
            KeyEvent.KEYCODE_0 } },

        { "WASD + Space", new int[] {
            KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_D,
            KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_S,
            KeyEvent.KEYCODE_SPACE } },
    };

    /** QAOPM and the rest, as a device with nothing stored yet starts out. */
    private static List<Profile> builtIn() {
        List<Profile> profiles = new ArrayList<>();
        profiles.add(new Profile(QAOPM_NAME, QAOPM));

        for (Object[] entry : TYPICAL) {
            int[] stick = (int[]) entry[1];
            int[] keys = QAOPM.clone();

            // The five the layout names; the buttons stay as they are.
            System.arraycopy(stick, 0, keys, 0, stick.length);
            profiles.add(new Profile((String) entry[0], keys));
        }

        return profiles;
    }

    /** A name and its eight keys. Immutable; editing makes a new one. */
    static final class Profile {
        final String name;
        final int[] keys;

        Profile(String name, int[] keys) {
            this.name = name;
            this.keys = keys.length == SLOTS ? keys.clone() : QAOPM.clone();
        }

        Profile withKey(int slot, int keycode) {
            int[] changed = keys.clone();
            changed[slot] = keycode;
            return new Profile(name, changed);
        }

        Profile withName(String renamed) {
            return new Profile(renamed, keys);
        }
    }

    /**
     * Every profile there is, never empty: a device with nothing stored yet gets
     * the built-in layouts, which is also what a stored value that will not parse
     * falls back to. A profile list is not worth an error message — the controls
     * have to do something, and doing what they have always done is the least
     * surprising thing available.
     */
    static List<Profile> all(SharedPreferences preferences) {
        List<Profile> profiles = new ArrayList<>();
        String stored = preferences.getString(KEY_PROFILES, null);

        if (stored != null) {
            try {
                JSONArray array = new JSONArray(stored);

                for (int i = 0; i < array.length(); i++) {
                    JSONObject object = array.getJSONObject(i);
                    JSONArray keys = object.getJSONArray("keys");
                    int[] codes = new int[SLOTS];

                    for (int slot = 0; slot < SLOTS && slot < keys.length(); slot++) {
                        codes[slot] = keys.getInt(slot);
                    }
                    profiles.add(new Profile(object.getString("name"), codes));
                }
            } catch (JSONException e) {
                profiles.clear();
            }
        }

        return profiles.isEmpty() ? builtIn() : profiles;
    }

    /** The name the default profile is given, and only ever a default. */
    static final String QAOPM_NAME = "QAOPM";

    /** Which profile is in use; always a profile that exists. */
    static int currentIndex(SharedPreferences preferences) {
        int stored = preferences.getInt(KEY_CURRENT, 0);
        int count = all(preferences).size();

        return stored >= 0 && stored < count ? stored : 0;
    }

    static Profile current(SharedPreferences preferences) {
        return all(preferences).get(currentIndex(preferences));
    }

    /** Writes the lot back, and which one of them is in use. */
    static void store(SharedPreferences preferences, List<Profile> profiles,
                      int current) {
        JSONArray array = new JSONArray();

        try {
            for (Profile profile : profiles) {
                JSONObject object = new JSONObject();
                JSONArray keys = new JSONArray();

                for (int key : profile.keys) keys.put(key);

                object.put("name", profile.name);
                object.put("keys", keys);
                array.put(object);
            }
        } catch (JSONException e) {
            // put() only throws on a NaN, and there are no doubles here.
            return;
        }

        preferences.edit()
                .putString(KEY_PROFILES, array.toString())
                .putInt(KEY_CURRENT, current)
                .apply();
    }

    /**
     * What a key is called on a control's face: short, because it is drawn
     * inside a circle a thumb's width across. The two shifts become CS and SS,
     * which is what a Spectrum manual calls them anyway.
     */
    static String label(int keycode) {
        switch (keycode) {
            case KeyEvent.KEYCODE_ENTER: return "ENTER";
            case KeyEvent.KEYCODE_SPACE: return "SPACE";
            case KeyEvent.KEYCODE_SHIFT_LEFT: return "CS";
            case KeyEvent.KEYCODE_CTRL_LEFT: return "SS";
            default: {
                String name = KeyEvent.keyCodeToString(keycode);
                return name.startsWith("KEYCODE_")
                        ? name.substring("KEYCODE_".length()) : name;
            }
        }
    }

    /** The long name, as the keyboard's own keys are labelled. */
    static String name(int keycode) {
        switch (keycode) {
            case KeyEvent.KEYCODE_SPACE: return "BREAK SPACE";
            case KeyEvent.KEYCODE_SHIFT_LEFT: return "CAPS SHIFT";
            case KeyEvent.KEYCODE_CTRL_LEFT: return "SYMBOL SHIFT";
            default: return label(keycode);
        }
    }

    /** What a slot is called in the editor, in slot order. */
    static String slotName(int slot) {
        switch (slot) {
            case FuseNative.JOYSTICK_LEFT: return "Left";
            case FuseNative.JOYSTICK_RIGHT: return "Right";
            case FuseNative.JOYSTICK_UP: return "Up";
            case FuseNative.JOYSTICK_DOWN: return "Down";
            case FuseNative.JOYSTICK_FIRE: return "Fire";
            case BUTTON_1: return "Button 1";
            case BUTTON_2: return "Button 2";
            default: return "Button 3";
        }
    }
}
