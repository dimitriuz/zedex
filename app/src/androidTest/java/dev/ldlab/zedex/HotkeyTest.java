package dev.ldlab.zedex;

import dev.ldlab.zedex.input.Gamepad;
import dev.ldlab.zedex.input.Hotkeys;
import static org.junit.Assert.assertEquals;

import android.view.InputDevice;
import android.view.KeyEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The hotkey chords, fed straight into {@link Gamepad}.
 *
 * Not a UI Automator test: no emulator on the build machine has a controller
 * plugged into it, and the part worth testing is the arithmetic of a modifier
 * anyway — which button counts while which other one is down, and that a held
 * action is ended exactly once however the two are let go. Synthetic
 * {@link KeyEvent}s with a gamepad source are the whole of what the class sees.
 *
 * <b>Nothing here touches a bound face button, Start or the D-pad.</b> Those
 * fall through to the machine and would call into Fuse, which is not running in
 * a test that never launched the activity; L1 and R2 fall through to nothing, so
 * they are what the bindings use.
 */
@RunWith(AndroidJUnit4.class)
public class HotkeyTest {

    private static final int HOTKEY = KeyEvent.KEYCODE_BUTTON_SELECT;
    private static final int TAPPED = KeyEvent.KEYCODE_BUTTON_L1;
    private static final int HELD = KeyEvent.KEYCODE_BUTTON_R2;

    private final List<String> fired = new ArrayList<>();
    private Gamepad pad;

    @Before
    public void setUp() {
        fired.clear();
        pad = new Gamepad((action, pressed) ->
                fired.add(action.name() + (pressed ? ":on" : ":off")));
        pad.setHotkeys(bindings(HOTKEY));
    }

    private static Hotkeys.Bindings bindings(int modifier) {
        Hotkeys.Bindings keys = new Hotkeys.Bindings(modifier);

        keys.put(Hotkeys.Action.QUICK_SAVE, TAPPED);
        keys.put(Hotkeys.Action.FAST_FORWARD, HELD);

        return keys;
    }

    private void down(int keycode) {
        pad.key(event(KeyEvent.ACTION_DOWN, keycode));
    }

    private void up(int keycode) {
        pad.key(event(KeyEvent.ACTION_UP, keycode));
    }

    private static KeyEvent event(int action, int keycode) {
        return new KeyEvent(0, 0, action, keycode, 0, 0, -1, 0, 0,
                            InputDevice.SOURCE_GAMEPAD);
    }

    /** The whole point of a modifier: the button alone is left alone. */
    @Test
    public void aBoundButtonOnItsOwnDoesNothing() {
        down(TAPPED);
        up(TAPPED);

        assertEquals(Arrays.asList(), fired);
    }

    @Test
    public void theHotkeyAndTheButtonFireOnce() {
        down(HOTKEY);
        down(TAPPED);
        up(TAPPED);
        up(HOTKEY);

        assertEquals(Arrays.asList("QUICK_SAVE:on"), fired);
    }

    /** Order matters: the hotkey has to be down first, as on a keyboard. */
    @Test
    public void theHotkeyArrivingSecondDoesNotFire() {
        down(TAPPED);
        down(HOTKEY);
        up(TAPPED);
        up(HOTKEY);

        assertEquals(Arrays.asList(), fired);
    }

    @Test
    public void aHeldActionRunsWhileBothAreDown() {
        down(HOTKEY);
        down(HELD);

        assertEquals(Arrays.asList("FAST_FORWARD:on"), fired);

        up(HELD);
        up(HOTKEY);

        assertEquals(Arrays.asList("FAST_FORWARD:on", "FAST_FORWARD:off"), fired);
    }

    /**
     * Letting go of the hotkey first is the ordinary way out of a chord, and it
     * has to end the hold - otherwise the machine is left running at 500%.
     */
    @Test
    public void lettingGoOfTheHotkeyEndsTheHold() {
        down(HOTKEY);
        down(HELD);
        up(HOTKEY);

        assertEquals(Arrays.asList("FAST_FORWARD:on", "FAST_FORWARD:off"), fired);

        // And the button coming up afterwards does not end it twice.
        up(HELD);

        assertEquals(Arrays.asList("FAST_FORWARD:on", "FAST_FORWARD:off"), fired);
    }

    /** With no hotkey set, the bindings are the buttons themselves. */
    @Test
    public void noHotkeyMeansTheButtonsFireBare() {
        pad.setHotkeys(bindings(0));

        down(TAPPED);
        up(TAPPED);
        down(HELD);
        up(HELD);

        assertEquals(Arrays.asList("QUICK_SAVE:on",
                                   "FAST_FORWARD:on", "FAST_FORWARD:off"), fired);
    }

    /** Auto-repeat is a stream of presses with no release between them. */
    @Test
    public void autoRepeatDoesNotFireAgain() {
        down(HOTKEY);
        down(TAPPED);
        pad.key(new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, TAPPED, 3, 0, -1, 0, 0,
                             InputDevice.SOURCE_GAMEPAD));

        assertEquals(Arrays.asList("QUICK_SAVE:on"), fired);
    }
}
