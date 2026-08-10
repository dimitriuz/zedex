package dev.ldlab.zedex.input;

import dev.ldlab.zedex.FuseNative;

import android.view.KeyEvent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A scraped key layout, read into a profile.
 *
 * ScreenScraper's {@code sp2kcfg} is Recalbox's {@code p2k.cfg} - lines of
 * {@code 0:a = v ;; jump}, hand-authored per game, saying which keyboard key
 * each pad button should send. It is the only scraped field that changes how a
 * game <em>plays</em> rather than how it looks, and it is what makes the setup
 * dialog's keyboard offer honest: "the profile's keys" is a useless answer
 * when nothing knows which keys the game reads.
 *
 * <b>What it does not name, it does not change.</b> A layout is read over
 * {@link ControlProfiles#QAOPM} rather than replacing it, the same rule the
 * built-in layouts follow: a game that wants keys for the stick still wants
 * Enter to start, and most files name four directions and a fire button and
 * stop there.
 *
 * <b>Lenient, and empty-handed rather than wrong.</b> The p2k vocabulary is
 * larger than a Spectrum has any use for - keypads, function keys, shoulder
 * buttons, a second player - and it is not published anywhere this could read.
 * So a line naming something unrecognised is skipped, and a layout with
 * nothing usable in it answers null rather than a profile of defaults. That
 * distinction is load-bearing: a profile named after the game whose keys were
 * simply QAOPM would claim it had been set up when nothing was read at all,
 * and {@code Suggested.keyboard} decides whether to offer the keyboard by
 * asking exactly this.
 */
public final class Keymap {

    private Keymap() {
    }

    /** How a p2k line separates its comment from its content; {@code #}
     *  starts one too, as the first line of the recorded sample does. */
    private static final String COMMENT = ";;";

    /**
     * Which pad button each of the profile's slots is, by p2k's names.
     *
     * The four directions are the four directions. Fire is A - the primary
     * action button on every pad p2k was written for - and the two spare
     * buttons take Start and Select, whose defaults they already resemble:
     * Enter and Space. The third button is not in here because nothing in a
     * p2k file corresponds to it.
     */
    private static final Object[][] SLOTS = {
        { "left",   FuseNative.JOYSTICK_LEFT },
        { "right",  FuseNative.JOYSTICK_RIGHT },
        { "up",     FuseNative.JOYSTICK_UP },
        { "down",   FuseNative.JOYSTICK_DOWN },
        { "a",      FuseNative.JOYSTICK_FIRE },
        { "start",  ControlProfiles.BUTTON_1 },
        { "select", ControlProfiles.BUTTON_2 },
    };

    /** What fire falls back to. A pad has one button that means "do the
     *  thing", and a layout that calls it B still has one. */
    private static final String[] ALSO_FIRE = { "b", "x", "y" };

    /**
     * The keys a Spectrum has, under the names Recalbox writes them with.
     *
     * The two shifts are the interesting pair: {@code SHIFT_LEFT} is CAPS
     * SHIFT to Fuse and {@code CTRL_LEFT} is SYMBOL SHIFT, which is the same
     * translation the rest of this app makes - see {@link ControlProfiles}.
     * Letters and digits are not in here; they are worked out from the
     * character.
     */
    private static final Map<String, Integer> NAMED = new HashMap<>();

    static {
        NAMED.put("enter", KeyEvent.KEYCODE_ENTER);
        NAMED.put("return", KeyEvent.KEYCODE_ENTER);
        NAMED.put("space", KeyEvent.KEYCODE_SPACE);
        NAMED.put("shift", KeyEvent.KEYCODE_SHIFT_LEFT);
        NAMED.put("leftshift", KeyEvent.KEYCODE_SHIFT_LEFT);
        NAMED.put("rightshift", KeyEvent.KEYCODE_SHIFT_LEFT);
        NAMED.put("ctrl", KeyEvent.KEYCODE_CTRL_LEFT);
        NAMED.put("leftctrl", KeyEvent.KEYCODE_CTRL_LEFT);
        NAMED.put("rightctrl", KeyEvent.KEYCODE_CTRL_LEFT);
    }

    /**
     * The layout as a profile named after the game, or null when there is
     * nothing in it this app can use.
     *
     * @param name   what to call it in the profile list - the game's own name
     * @param keymap {@code Meta.keymap}, verbatim as the provider gave it
     */
    public static ControlProfiles.Profile profile(String name, String keymap) {
        Map<String, Integer> said = read(keymap);
        if (said.isEmpty()) return null;

        int[] keys = ControlProfiles.QAOPM.clone();
        boolean any = false;

        for (Object[] entry : SLOTS) {
            Integer key = said.get((String) entry[0]);
            if (key == null) continue;

            keys[(Integer) entry[1]] = key;
            any = true;
        }

        // Fire, when the layout calls that button something else.
        if (!said.containsKey("a")) {
            for (String button : ALSO_FIRE) {
                Integer key = said.get(button);
                if (key == null) continue;

                keys[FuseNative.JOYSTICK_FIRE] = key;
                any = true;
                break;
            }
        }

        return any ? new ControlProfiles.Profile(name, keys) : null;
    }

    /** Whether {@link #profile} would answer with one, asked without building
     *  it - which is how the dialog decides whether the keyboard is worth
     *  offering at all. */
    public static boolean readable(String keymap) {
        return profile("", keymap) != null;
    }

    /**
     * Every {@code button = key} the layout names, as p2k's button name
     * against an Android keycode.
     *
     * <b>Player one only.</b> A file can carry a second player's half, and
     * reading both into one profile lets the second overwrite the first -
     * silently, and only for the games whose author bothered to write both.
     * An unprefixed line is player one: the prefix is the exception, not the
     * rule.
     */
    private static Map<String, Integer> read(String keymap) {
        Map<String, Integer> said = new HashMap<>();
        if (keymap == null) return said;

        for (String line : keymap.split("\\R")) {
            String content = line;

            int comment = content.indexOf(COMMENT);
            if (comment >= 0) content = content.substring(0, comment);

            int hash = content.indexOf('#');
            if (hash >= 0) content = content.substring(0, hash);

            int equals = content.indexOf('=');
            if (equals < 0) continue;

            String button = content.substring(0, equals).trim().toLowerCase(Locale.US);
            String key = content.substring(equals + 1).trim().toLowerCase(Locale.US);

            int colon = button.indexOf(':');
            if (colon >= 0) {
                if (!"0".equals(button.substring(0, colon).trim())) continue;
                button = button.substring(colon + 1).trim();
            }

            int code = keycode(key);
            if (code != KeyEvent.KEYCODE_UNKNOWN) said.put(button, code);
        }

        return said;
    }

    /** One key by p2k's name for it, or {@code KEYCODE_UNKNOWN} for the many
     *  a Spectrum does not have. */
    private static int keycode(String key) {
        if (key.length() == 1) {
            char one = key.charAt(0);

            if (one >= 'a' && one <= 'z') return KeyEvent.KEYCODE_A + (one - 'a');
            if (one >= '0' && one <= '9') return KeyEvent.KEYCODE_0 + (one - '0');
        }

        Integer named = NAMED.get(key);
        return named == null ? KeyEvent.KEYCODE_UNKNOWN : named;
    }
}
