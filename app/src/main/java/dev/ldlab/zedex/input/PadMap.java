package dev.ldlab.zedex.input;

import dev.ldlab.zedex.FuseNative;

import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Which physical binding on a controller drives which of the eight controls.
 *
 * A pad that agrees with Android about what its buttons are called needs none
 * of this, and gets none of it: the defaults below are exactly what
 * {@link Gamepad} used to decide with a switch, so an unmapped pad behaves as
 * it always has. What this adds is somewhere for the pads that disagree to
 * say so - and for the ones whose directions arrive on an axis nothing here
 * watches, which no amount of remapping buttons could have fixed.
 *
 * <b>A binding is a button or an axis.</b> An axis carries a sign, since one
 * axis is two directions.
 *
 * <b>The lookup is a HashMap and not a SparseArray</b>, which matters more than
 * it looks: the stub android.jar answers SparseArray's methods with defaults,
 * so a lookup held in one cannot be read on the JVM test tier at all - see
 * HotkeysTest, where that is the one thing it could not cover. Every rule in
 * this class is a rule about a table, and a table nobody can assert against is
 * how a mapping quietly stops mapping.
 */
public final class PadMap {

    /** No control is on this binding. */
    public static final int NONE = -1;

    /**
     * A button, or one direction of one axis, as a single lookup key.
     *
     * Keycodes and axis numbers are both small non-negative ints from
     * different vocabularies, so they are pushed apart rather than trusted not
     * to collide: KEYCODE_BUTTON_A and AXIS_HAT_X are both perfectly ordinary
     * numbers and one of them would otherwise be the other.
     */
    private static final int AXIS_BASE = 1 << 16;

    static int button(int keycode) {
        return keycode;
    }

    static int axis(int axis, int sign) {
        return AXIS_BASE + axis * 2 + (sign < 0 ? 0 : 1);
    }

    /**
     * What a pad does before anyone changes anything: Gamepad's old switch,
     * plus the two axis pairs its motion path already read.
     *
     * A direction has three bindings - the D-pad key, the stick and the hat -
     * because all three steered before this class existed and taking one away
     * would be a regression wearing the clothes of a feature.
     */
    private static final int[][] DEFAULTS = {
        { button(KeyEvent.KEYCODE_DPAD_LEFT),   FuseNative.JOYSTICK_LEFT },
        { button(KeyEvent.KEYCODE_DPAD_RIGHT),  FuseNative.JOYSTICK_RIGHT },
        { button(KeyEvent.KEYCODE_DPAD_UP),     FuseNative.JOYSTICK_UP },
        { button(KeyEvent.KEYCODE_DPAD_DOWN),   FuseNative.JOYSTICK_DOWN },

        { axis(MotionEvent.AXIS_X, -1),         FuseNative.JOYSTICK_LEFT },
        { axis(MotionEvent.AXIS_X, +1),         FuseNative.JOYSTICK_RIGHT },
        { axis(MotionEvent.AXIS_Y, -1),         FuseNative.JOYSTICK_UP },
        { axis(MotionEvent.AXIS_Y, +1),         FuseNative.JOYSTICK_DOWN },

        { axis(MotionEvent.AXIS_HAT_X, -1),     FuseNative.JOYSTICK_LEFT },
        { axis(MotionEvent.AXIS_HAT_X, +1),     FuseNative.JOYSTICK_RIGHT },
        { axis(MotionEvent.AXIS_HAT_Y, -1),     FuseNative.JOYSTICK_UP },
        { axis(MotionEvent.AXIS_HAT_Y, +1),     FuseNative.JOYSTICK_DOWN },

        { button(KeyEvent.KEYCODE_BUTTON_A),    FuseNative.JOYSTICK_FIRE },
        { button(KeyEvent.KEYCODE_DPAD_CENTER), FuseNative.JOYSTICK_FIRE },

        { button(KeyEvent.KEYCODE_BUTTON_B),    ControlProfiles.BUTTON_1 },
        { button(KeyEvent.KEYCODE_BUTTON_X),    ControlProfiles.BUTTON_2 },
        { button(KeyEvent.KEYCODE_BUTTON_Y),    ControlProfiles.BUTTON_3 },
    };

    /** A button, or one direction of one axis. */
    public static final class Binding {
        public final boolean isAxis;

        /** The keycode, or the axis id. */
        public final int code;

        /** -1 or +1 for an axis; 0 for a button. */
        public final int sign;

        private Binding(boolean isAxis, int code, int sign) {
            this.isAxis = isAxis;
            this.code = code;
            this.sign = sign;
        }

        public static Binding button(int keycode) {
            return new Binding(false, keycode, 0);
        }

        public static Binding axis(int axisId, int sign) {
            return new Binding(true, axisId, sign < 0 ? -1 : +1);
        }

        int key() {
            return isAxis ? PadMap.axis(code, sign) : PadMap.button(code);
        }
    }

    /** What was captured, slot to binding. Empty on a pad nobody has changed. */
    private final Map<Integer, Binding> chosen;

    /** Binding to slot, already resolved. Read for every event, so it is flat. */
    private final Map<Integer, Integer> effective;

    private PadMap(Map<Integer, Binding> chosen) {
        this.chosen = chosen;
        this.effective = resolve(chosen);
    }

    /** What every pad does until somebody says otherwise. */
    public static PadMap defaults() {
        return new PadMap(new HashMap<>());
    }

    /**
     * The defaults, then each capture laid over them.
     *
     * Two removals per capture, and both are needed. Every entry pointing at
     * the slot goes, or a captured Left would be a third Left beside the stick
     * and the hat rather than instead of them. The entry keyed by the binding
     * goes, or B would be Fire and Button 1 at once and one press would do two
     * things.
     */
    private static Map<Integer, Integer> resolve(Map<Integer, Binding> chosen) {
        Map<Integer, Integer> table = new HashMap<>();

        for (int[] entry : DEFAULTS) table.put(entry[0], entry[1]);

        for (Map.Entry<Integer, Binding> capture : chosen.entrySet()) {
            int slot = capture.getKey();
            int binding = capture.getValue().key();

            table.values().removeIf(where -> where == slot);
            table.remove(binding);
            table.put(binding, slot);
        }

        return table;
    }

    /** This map with one slot moved to a binding, and that binding taken off
     *  whatever else held it. The original is unchanged. */
    public PadMap with(int slot, Binding binding) {
        Map<Integer, Binding> next = new HashMap<>(chosen);
        next.put(slot, binding);
        return new PadMap(next);
    }

    /** What drives this slot, for the screen to draw. Null when nothing does. */
    public Binding bindingFor(int slot) {
        Binding captured = chosen.get(slot);
        if (captured != null) return captured;

        // Walked in DEFAULTS order and not the table's, which is a HashMap and
        // has none. A direction has three default bindings and Fire has two, so
        // scanning the table would name an arbitrary one of them - and a
        // different one on another run, which is a row that changes what it
        // says for no reason and a test that passes on hash order.
        for (int[] entry : DEFAULTS) {
            Integer where = effective.get(entry[0]);
            if (where != null && where == slot) return fromKey(entry[0]);
        }

        // Unchosen, and its defaults were taken by a capture elsewhere.
        return null;
    }

    /** Whether this slot is still on what it was born with. */
    public boolean isDefault(int slot) {
        return !chosen.containsKey(slot);
    }

    private static Binding fromKey(int key) {
        if (key < AXIS_BASE) return Binding.button(key);

        int packed = key - AXIS_BASE;
        return Binding.axis(packed / 2, (packed % 2) == 0 ? -1 : +1);
    }

    /** The control this button drives, or {@link #NONE}. */
    public int slotFor(int keycode) {
        Integer slot = effective.get(button(keycode));
        return slot == null ? NONE : slot;
    }

    /** The control this axis drives when pushed this way, or {@link #NONE}. */
    public int slotFor(int axisId, int sign) {
        Integer slot = effective.get(axis(axisId, sign));
        return slot == null ? NONE : slot;
    }
}
