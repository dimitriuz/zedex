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

    /** Binding to slot, already resolved. Read for every event, so it is flat. */
    private final Map<Integer, Integer> effective;

    private PadMap(Map<Integer, Integer> effective) {
        this.effective = effective;
    }

    /** What every pad does until somebody says otherwise. */
    public static PadMap defaults() {
        Map<Integer, Integer> table = new HashMap<>();

        for (int[] entry : DEFAULTS) table.put(entry[0], entry[1]);

        return new PadMap(table);
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
