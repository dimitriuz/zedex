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

    /** A capture survives being written down and read back. */
    @Test
    public void aMapRoundTripsThroughJson() {
        PadMap map = PadMap.defaults()
                .with(FuseNative.JOYSTICK_FIRE,
                      PadMap.Binding.button(KeyEvent.KEYCODE_BUTTON_B))
                .with(FuseNative.JOYSTICK_LEFT,
                      PadMap.Binding.axis(MotionEvent.AXIS_RZ, -1));

        PadMap back = PadMap.fromJson(map.toJson());

        assertEquals(FuseNative.JOYSTICK_FIRE, back.slotFor(KeyEvent.KEYCODE_BUTTON_B));
        assertEquals(FuseNative.JOYSTICK_LEFT, back.slotFor(MotionEvent.AXIS_RZ, -1));
        assertEquals(PadMap.NONE, back.slotFor(KeyEvent.KEYCODE_BUTTON_A));
    }

    /**
     * One bad row does not cost the others.
     *
     * A mapping is worth more than the row somebody's future version wrote
     * into it: an unknown slot name and an unparseable binding are both
     * skipped, and what was understood still applies.
     */
    @Test
    public void whatCannotBeUnderstoodIsSkippedAndTheRestStands() {
        PadMap map = PadMap.fromJson(
                "{\"FIRE\":\"k97\",\"WARP_DRIVE\":\"k42\",\"BUTTON_1\":\"nonsense\"}");

        // FIRE was understood: B (97) is Fire, and A has lost it the way any
        // capture takes a binding away from the slot that held it.
        assertEquals(FuseNative.JOYSTICK_FIRE, map.slotFor(KeyEvent.KEYCODE_BUTTON_B));
        assertEquals(PadMap.NONE, map.slotFor(KeyEvent.KEYCODE_BUTTON_A));

        // WARP_DRIVE is not a slot and BUTTON_1's value is not a binding, so
        // neither was applied - and the rest of the defaults are untouched.
        assertEquals(ControlProfiles.BUTTON_2, map.slotFor(KeyEvent.KEYCODE_BUTTON_X));
        assertEquals(ControlProfiles.BUTTON_3, map.slotFor(KeyEvent.KEYCODE_BUTTON_Y));
    }

    /** Malformed JSON is a pad with no mapping, not a pad with no controls. */
    @Test
    public void malformedJsonFallsBackToTheDefaults() {
        PadMap map = PadMap.fromJson("{not json");

        assertEquals(FuseNative.JOYSTICK_FIRE, map.slotFor(KeyEvent.KEYCODE_BUTTON_A));
        assertEquals(ControlProfiles.BUTTON_1, map.slotFor(KeyEvent.KEYCODE_BUTTON_B));
    }

    /**
     * {@code bindingFor()} must not let a slot that lost the race also claim
     * the binding.
     *
     * {@link PadMap#with} dedups as it goes - a binding is evicted from every
     * other slot before being granted, so a map built through it never
     * disagrees with itself. {@link PadMap#fromJson} has no such guard: two
     * slot names in a hand-edited or corrupted store can both decode to the
     * same binding. {@link PadMap#resolve} still gives it to exactly one of
     * them (whichever the stored map's iteration happens to visit last), but
     * before this fix {@code bindingFor()} read {@code chosen} directly and
     * would answer with the captured binding for the slot that lost too - a
     * row lying about what actually happens when the button is pressed.
     *
     * Which of FIRE or Button 1 wins is not pinned here on purpose: {@code
     * chosen} is a HashMap, and its entry order is not part of this class's
     * contract. What is asserted is the invariant - exactly one of the two
     * slots reports the binding, and it agrees with {@link PadMap#slotFor}
     * about which; the other reports nothing, honestly, rather than the same
     * binding a second time.
     */
    @Test
    public void bindingForDoesNotAlsoNameTheSlotThatLostTheRace() {
        PadMap map = PadMap.fromJson("{\"FIRE\":\"k97\",\"BUTTON_1\":\"k97\"}");

        boolean fireWon = map.slotFor(KeyEvent.KEYCODE_BUTTON_B) == FuseNative.JOYSTICK_FIRE;
        int winner = fireWon ? FuseNative.JOYSTICK_FIRE : ControlProfiles.BUTTON_1;
        int loser = fireWon ? ControlProfiles.BUTTON_1 : FuseNative.JOYSTICK_FIRE;

        assertEquals(KeyEvent.KEYCODE_BUTTON_B, map.bindingFor(winner).code);
        assertNull("the slot that lost the race must not also claim button B",
                   map.bindingFor(loser));
    }
}
