package dev.ldlab.zedex.input;

import dev.ldlab.zedex.storage.Prefs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * {@code Hotkeys.load} and the lookup it builds - the part {@link HotkeysTest}
 * on the JVM cannot reach.
 *
 * {@code Bindings} holds its map in an {@code android.util.SparseArray}, whose
 * stubs answer defaults, so on the JVM every lookup below would answer null
 * whatever the code did. On a device it is an ordinary class and needs nothing
 * arranged - which is the whole correction here: this was written off as
 * wanting Robolectric or a rewrite to a plain map, and it wanted neither. The
 * instrumentation suite was already running.
 *
 * What it adds over the JVM half is the direction that actually matters at
 * play time. {@code keycodeFor} answers "which button has this action", which
 * is the editor's question; {@code forButton} answers "which action has this
 * button", which is what every single pad press asks. They are built from the
 * same stored JSON and they can disagree.
 *
 * A scratch preferences file, so a bench's own hotkeys are not touched.
 */
@RunWith(AndroidJUnit4.class)
public class BindingsTest {

    private SharedPreferences scratch;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        scratch = context.getSharedPreferences("hotkeys-test", Context.MODE_PRIVATE);
        scratch.edit().clear().commit();
    }

    /**
     * With nothing stored, the built-in layout is loaded - and looked up by
     * button, which is the direction the pad asks in.
     *
     * The two halves are built by different code from the same table:
     * {@code keycodeFor} walks DEFAULTS looking for an action, {@code load}
     * walks it putting every pair into the map. A defaults entry the loop
     * skips is a hotkey that the editor shows as bound and the pad ignores.
     */
    @Test
    public void thebuiltInLayoutIsLoadedAndFoundByButton() {
        Hotkeys.Bindings bindings = Hotkeys.load(scratch);

        int found = 0;
        for (Hotkeys.Action action : Hotkeys.Action.values()) {
            int keycode = Hotkeys.keycodeFor(scratch, action);
            if (keycode == 0) continue;

            found++;
            assertSame("the editor says " + action + " is on button " + keycode
                       + " but the pad's own lookup does not agree",
                       action, bindings.forButton(keycode));
        }

        assertEquals("no default binding was loaded at all", true, found > 0);
    }

    /** A button nothing is bound to answers null, not some other action - the
     *  pad asks this of every press, including all the ones that are not
     *  hotkeys at all. */
    @Test
    public void anUnboundButtonAnswersNothing() {
        Hotkeys.Bindings bindings = Hotkeys.load(scratch);

        assertNull("an unbound button came back carrying an action",
                   bindings.forButton(KeyEvent.KEYCODE_BUTTON_16));
    }

    /** And 0 - "no button" - is never an action either, which matters because
     *  it is the value an unset binding stores. */
    @Test
    public void thenoButtonValueIsNotBoundToAnything() {
        Hotkeys.Bindings bindings = Hotkeys.load(scratch);

        assertNull("keycode 0 answered with an action", bindings.forButton(0));
    }

    /** A stored binding is what the pad finds, not the default it replaced. */
    @Test
    public void astoredBindingIsWhatTheLookupAnswers() {
        Hotkeys.bind(scratch, Hotkeys.Action.PAUSE, KeyEvent.KEYCODE_BUTTON_A);

        Hotkeys.Bindings bindings = Hotkeys.load(scratch);

        assertSame(Hotkeys.Action.PAUSE, bindings.forButton(KeyEvent.KEYCODE_BUTTON_A));
    }

    /**
     * Taking a button from one action and giving it to another leaves exactly
     * one owner in the lookup.
     *
     * The JVM half asserts the stored side of this; here is the side the pad
     * reads. A map that kept both would answer whichever went in last, which
     * is the "hiding which of the two would win" that {@code bind}'s own
     * comment says the editor must not allow.
     */
    @Test
    public void abuttonHandedOverHasOneOwnerInTheLookupToo() {
        Hotkeys.bind(scratch, Hotkeys.Action.PAUSE, KeyEvent.KEYCODE_BUTTON_A);
        Hotkeys.bind(scratch, Hotkeys.Action.RESET, KeyEvent.KEYCODE_BUTTON_A);

        Hotkeys.Bindings bindings = Hotkeys.load(scratch);

        assertSame("the button did not end up with the action that took it",
                   Hotkeys.Action.RESET, bindings.forButton(KeyEvent.KEYCODE_BUTTON_A));
    }

    /** A cleared action is not in the lookup at all, so its old button goes
     *  back to doing nothing rather than still firing. */
    @Test
    public void aclearedActionLeavesItsButtonDoingNothing() {
        Hotkeys.bind(scratch, Hotkeys.Action.PAUSE, KeyEvent.KEYCODE_BUTTON_A);
        Hotkeys.bind(scratch, Hotkeys.Action.PAUSE, 0);

        Hotkeys.Bindings bindings = Hotkeys.load(scratch);

        assertNull("a cleared hotkey still fires on its old button",
                   bindings.forButton(KeyEvent.KEYCODE_BUTTON_A));
    }

    /** A stored map that will not parse loads as nothing bound, rather than
     *  throwing on the way into the emulator screen. */
    @Test
    public void arubbishStoredMapLoadsAsNothingBound() {
        scratch.edit().putString(Hotkeys.KEY_BINDINGS, "}{ not json").commit();

        Hotkeys.Bindings bindings = Hotkeys.load(scratch);

        for (int keycode : Hotkeys.buttons()) {
            assertNull("button " + keycode + " came back bound from a rubbish map",
                       bindings.forButton(keycode));
        }
    }

    /** The modifier travels with the bindings - {@code Gamepad} reads it off
     *  the object it was handed, not from the preferences again, so a load
     *  that dropped it would make every hotkey fire without the shoulder. */
    @Test
    public void themodifierComesAlongWithTheBindings() {
        Hotkeys.setModifier(scratch, KeyEvent.KEYCODE_BUTTON_MODE);

        assertEquals(KeyEvent.KEYCODE_BUTTON_MODE, Hotkeys.load(scratch).modifier);
    }
}
