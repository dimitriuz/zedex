package dev.ldlab.zedex.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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

    /**
     * Binding a button to a slot takes it off whatever else held it.
     *
     * The rule that surprises, so it is asserted from both ends: B drives Fire
     * now, and Button 1 - whose default B was - has nothing, rather than
     * quietly still answering to it. One press doing two things is the bug this
     * exists to prevent.
     */
    @Test
    public void aCaptureTakesItsButtonOffTheSlotThatHadIt() {
        PadMap map = PadMap.defaults()
                .with(FuseNative.JOYSTICK_FIRE,
                      PadMap.Binding.button(KeyEvent.KEYCODE_BUTTON_B));

        assertEquals(FuseNative.JOYSTICK_FIRE, map.slotFor(KeyEvent.KEYCODE_BUTTON_B));
        assertEquals(PadMap.NONE, map.slotFor(KeyEvent.KEYCODE_BUTTON_A));
        assertEquals(PadMap.NONE, map.slotFor(KeyEvent.KEYCODE_DPAD_CENTER));
        assertEquals(ControlProfiles.BUTTON_2, map.slotFor(KeyEvent.KEYCODE_BUTTON_X));
        assertEquals(ControlProfiles.BUTTON_3, map.slotFor(KeyEvent.KEYCODE_BUTTON_Y));
    }

    /**
     * A captured direction is the only one.
     *
     * A direction has three defaults - the D-pad, the stick and the hat - and a
     * capture replaces all of them, because somebody who has just said "left is
     * this" does not mean "left is this as well".
     */
    @Test
    public void aCapturedDirectionReplacesEveryDefaultForIt() {
        PadMap map = PadMap.defaults()
                .with(FuseNative.JOYSTICK_LEFT,
                      PadMap.Binding.button(KeyEvent.KEYCODE_BUTTON_L1));

        assertEquals(FuseNative.JOYSTICK_LEFT, map.slotFor(KeyEvent.KEYCODE_BUTTON_L1));
        assertEquals(PadMap.NONE, map.slotFor(KeyEvent.KEYCODE_DPAD_LEFT));
        assertEquals(PadMap.NONE, map.slotFor(MotionEvent.AXIS_X, -1));
        assertEquals(PadMap.NONE, map.slotFor(MotionEvent.AXIS_HAT_X, -1));

        // And the other three directions are untouched.
        assertEquals(FuseNative.JOYSTICK_RIGHT, map.slotFor(MotionEvent.AXIS_X, +1));
        assertEquals(FuseNative.JOYSTICK_UP,    map.slotFor(KeyEvent.KEYCODE_DPAD_UP));
    }

    /** An axis binds as readily as a button, and takes the slot the same way. */
    @Test
    public void anAxisCanBeCaptured() {
        PadMap map = PadMap.defaults()
                .with(FuseNative.JOYSTICK_FIRE,
                      PadMap.Binding.axis(MotionEvent.AXIS_RZ, +1));

        assertEquals(FuseNative.JOYSTICK_FIRE, map.slotFor(MotionEvent.AXIS_RZ, +1));
        assertEquals(PadMap.NONE, map.slotFor(MotionEvent.AXIS_RZ, -1));
        assertEquals(PadMap.NONE, map.slotFor(KeyEvent.KEYCODE_BUTTON_A));
    }

    /** Two captures in a row both hold, and the second does not undo the first. */
    @Test
    public void capturesAccumulate() {
        PadMap map = PadMap.defaults()
                .with(FuseNative.JOYSTICK_FIRE,
                      PadMap.Binding.button(KeyEvent.KEYCODE_BUTTON_B))
                .with(ControlProfiles.BUTTON_1,
                      PadMap.Binding.button(KeyEvent.KEYCODE_BUTTON_A));

        assertEquals(FuseNative.JOYSTICK_FIRE, map.slotFor(KeyEvent.KEYCODE_BUTTON_B));
        assertEquals(ControlProfiles.BUTTON_1, map.slotFor(KeyEvent.KEYCODE_BUTTON_A));
    }

    /** What the screen draws in a row, and whether it is a choice or a default. */
    @Test
    public void aRowCanSayWhatItIsOnAndWhetherItWasChosen() {
        PadMap map = PadMap.defaults();

        // The first binding in DEFAULTS order, so the row says the same thing
        // every run: A for Fire rather than DPAD_CENTER, DPAD_LEFT for Left
        // rather than whichever of its three the table happened to yield.
        assertEquals(KeyEvent.KEYCODE_BUTTON_A,
                     map.bindingFor(FuseNative.JOYSTICK_FIRE).code);
        assertEquals(KeyEvent.KEYCODE_DPAD_LEFT,
                     map.bindingFor(FuseNative.JOYSTICK_LEFT).code);
        assertTrue(map.isDefault(FuseNative.JOYSTICK_FIRE));

        PadMap changed = map.with(FuseNative.JOYSTICK_FIRE,
                                  PadMap.Binding.button(KeyEvent.KEYCODE_BUTTON_B));

        assertEquals(KeyEvent.KEYCODE_BUTTON_B,
                     changed.bindingFor(FuseNative.JOYSTICK_FIRE).code);
        assertFalse(changed.isDefault(FuseNative.JOYSTICK_FIRE));

        // A slot whose binding was taken away has none to show.
        assertNull(changed.bindingFor(ControlProfiles.BUTTON_1));
    }

    /**
     * The same button captured onto a second slot leaves the first alone.
     *
     * Two captures colliding on one binding is an ordinary thing to do in a
     * remapping screen - you decide L1 should be Right after all - and the
     * most recent one has to win, or the screen disagrees with the pad. The
     * slot that lost its capture goes back to its defaults rather than being
     * stranded with nothing: a slot is always either captured or on defaults.
     */
    @Test
    public void recapturingAButtonTakesItOffTheSlotThatHadIt() {
        PadMap map = PadMap.defaults()
                .with(FuseNative.JOYSTICK_LEFT,
                      PadMap.Binding.button(KeyEvent.KEYCODE_BUTTON_L1))
                .with(FuseNative.JOYSTICK_RIGHT,
                      PadMap.Binding.button(KeyEvent.KEYCODE_BUTTON_L1));

        assertEquals(FuseNative.JOYSTICK_RIGHT, map.slotFor(KeyEvent.KEYCODE_BUTTON_L1));
        assertEquals(KeyEvent.KEYCODE_BUTTON_L1,
                     map.bindingFor(FuseNative.JOYSTICK_RIGHT).code);

        // Left has no capture any more, so it is back on its defaults.
        assertEquals(FuseNative.JOYSTICK_LEFT, map.slotFor(KeyEvent.KEYCODE_DPAD_LEFT));
        assertEquals(KeyEvent.KEYCODE_DPAD_LEFT,
                     map.bindingFor(FuseNative.JOYSTICK_LEFT).code);
        assertTrue(map.isDefault(FuseNative.JOYSTICK_LEFT));
    }
}
