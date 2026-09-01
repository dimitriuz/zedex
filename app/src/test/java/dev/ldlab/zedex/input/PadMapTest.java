package dev.ldlab.zedex.input;

import static org.junit.Assert.assertEquals;

import android.view.KeyEvent;
import android.view.MotionEvent;

import dev.ldlab.zedex.FuseNative;

import org.junit.Test;

/**
 * Which physical binding drives which control.
 *
 * On the JVM tier deliberately: the bench has no controller, and an
 * instrumentation test that skips when none is connected prints OK having
 * asserted nothing. Everything interesting here is a table and a rule, and
 * both are reachable without a pad - which is the whole reason the mapping is
 * a value object rather than a switch inside Gamepad.
 */
public class PadMapTest {

    /**
     * The defaults are what Gamepad's switch did, case for case.
     *
     * This is the test that makes deleting that switch safe, so it is written
     * out button by button rather than looped: a loop over a table here would
     * be the same table twice and would agree with itself however wrong it was.
     */
    @Test
    public void theDefaultsAreTheOldSwitch() {
        PadMap map = PadMap.defaults();

        assertEquals(FuseNative.JOYSTICK_LEFT,  map.slotFor(KeyEvent.KEYCODE_DPAD_LEFT));
        assertEquals(FuseNative.JOYSTICK_RIGHT, map.slotFor(KeyEvent.KEYCODE_DPAD_RIGHT));
        assertEquals(FuseNative.JOYSTICK_UP,    map.slotFor(KeyEvent.KEYCODE_DPAD_UP));
        assertEquals(FuseNative.JOYSTICK_DOWN,  map.slotFor(KeyEvent.KEYCODE_DPAD_DOWN));

        assertEquals(FuseNative.JOYSTICK_FIRE, map.slotFor(KeyEvent.KEYCODE_BUTTON_A));
        assertEquals(FuseNative.JOYSTICK_FIRE, map.slotFor(KeyEvent.KEYCODE_DPAD_CENTER));

        assertEquals(ControlProfiles.BUTTON_1, map.slotFor(KeyEvent.KEYCODE_BUTTON_B));
        assertEquals(ControlProfiles.BUTTON_2, map.slotFor(KeyEvent.KEYCODE_BUTTON_X));
        assertEquals(ControlProfiles.BUTTON_3, map.slotFor(KeyEvent.KEYCODE_BUTTON_Y));
    }

    /** Start is Enter and is not a slot; Gamepad handles it past the map. */
    @Test
    public void startIsNotASlot() {
        assertEquals(PadMap.NONE,
                     PadMap.defaults().slotFor(KeyEvent.KEYCODE_BUTTON_START));
    }

    /**
     * A direction has two default bindings, the stick and the hat, because
     * Gamepad already takes whichever of the two is pushed furthest. The table
     * says the same thing in the new vocabulary.
     */
    @Test
    public void bothTheStickAndTheHatSteerByDefault() {
        PadMap map = PadMap.defaults();

        assertEquals(FuseNative.JOYSTICK_LEFT,  map.slotFor(MotionEvent.AXIS_X, -1));
        assertEquals(FuseNative.JOYSTICK_RIGHT, map.slotFor(MotionEvent.AXIS_X, +1));
        assertEquals(FuseNative.JOYSTICK_UP,    map.slotFor(MotionEvent.AXIS_Y, -1));
        assertEquals(FuseNative.JOYSTICK_DOWN,  map.slotFor(MotionEvent.AXIS_Y, +1));

        assertEquals(FuseNative.JOYSTICK_LEFT,  map.slotFor(MotionEvent.AXIS_HAT_X, -1));
        assertEquals(FuseNative.JOYSTICK_RIGHT, map.slotFor(MotionEvent.AXIS_HAT_X, +1));
        assertEquals(FuseNative.JOYSTICK_UP,    map.slotFor(MotionEvent.AXIS_HAT_Y, -1));
        assertEquals(FuseNative.JOYSTICK_DOWN,  map.slotFor(MotionEvent.AXIS_HAT_Y, +1));
    }

    /** An axis nobody bound drives nothing, whichever way it is pushed. */
    @Test
    public void anUnboundAxisDrivesNothing() {
        PadMap map = PadMap.defaults();

        assertEquals(PadMap.NONE, map.slotFor(MotionEvent.AXIS_RZ, +1));
        assertEquals(PadMap.NONE, map.slotFor(MotionEvent.AXIS_RZ, -1));
    }
}
