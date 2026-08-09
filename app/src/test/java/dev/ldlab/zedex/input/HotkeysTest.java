package dev.ldlab.zedex.input;

import dev.ldlab.zedex.FakePreferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * What a pad button is bound to, and the rule that keeps two things off one
 * press.
 *
 * The other half of 11.4's number eight. {@code Hotkeys.Bindings} holds its
 * lookup in an {@code android.util.SparseArray}, whose methods the stub
 * android.jar answers with defaults - so {@code load} is genuinely out of
 * reach here and is the one thing below that is not covered. It matters less
 * than it looks: {@code load} and {@code keycodeFor} read the same stored
 * JSON, consult the same defaults table and handle a malformed value the same
 * way, so all three of those paths are exercised through {@code keycodeFor}.
 * What {@code load} adds on top is putting the answers into a map, which is
 * three lines.
 *
 * Everything else - the defaults, the modifier, and {@code bind} - is
 * {@code SharedPreferences} and {@code org.json}, both of which are already
 * answered.
 */
public class HotkeysTest {

    private static FakePreferences empty() {
        return new FakePreferences();
    }

    // --- the defaults --------------------------------------------------------------

    /**
     * A device with nothing bound has the built-in layout, and every action in
     * it answers with a real button.
     *
     * Walked by the enum rather than by a list written out here: an action
     * added to {@code Action} and forgotten in {@code DEFAULTS} answers 0,
     * which is "no button", and the only place that shows is an editor row
     * somebody has to notice is blank.
     */
    @Test
    public void afreshInstallHasTheBuiltInBindings() {
        FakePreferences preferences = empty();

        int bound = 0;
        for (Hotkeys.Action action : Hotkeys.Action.values()) {
            if (Hotkeys.keycodeFor(preferences, action) != 0) bound++;
        }

        assertTrue("no action has a default button at all", bound > 0);
    }

    /** No two defaults share a button, which is the same rule {@code bind}
     *  enforces later - a layout that shipped breaking it would put two
     *  actions on one press before anybody touched the editor. */
    @Test
    public void nodefaultButtonIsUsedTwice() {
        FakePreferences preferences = empty();
        Set<Integer> used = new HashSet<>();

        for (Hotkeys.Action action : Hotkeys.Action.values()) {
            int keycode = Hotkeys.keycodeFor(preferences, action);
            if (keycode == 0) continue;

            assertTrue("two actions share button " + keycode + " by default",
                       used.add(keycode));
        }
    }

    // --- the modifier ----------------------------------------------------------------

    @Test
    public void themodifierDefaultsToSelectAndSurvivesBeingSet() {
        FakePreferences preferences = empty();

        assertEquals(KeyEvent.KEYCODE_BUTTON_SELECT, Hotkeys.modifier(preferences));

        Hotkeys.setModifier(preferences, KeyEvent.KEYCODE_BUTTON_MODE);

        assertEquals(KeyEvent.KEYCODE_BUTTON_MODE, Hotkeys.modifier(preferences));
    }

    // --- binding -----------------------------------------------------------------------

    @Test
    public void abindingSurvivesBeingWrittenAndReadBack() {
        FakePreferences preferences = empty();

        Hotkeys.bind(preferences, Hotkeys.Action.PAUSE, KeyEvent.KEYCODE_BUTTON_A);

        assertEquals(KeyEvent.KEYCODE_BUTTON_A,
                     Hotkeys.keycodeFor(preferences, Hotkeys.Action.PAUSE));
    }

    /**
     * One button, one action.
     *
     * The rule its own comment states: "giving a button to something else
     * takes it away from whatever had it, because two actions on one press is
     * not a thing anyone means to ask for and an editor that allowed it would
     * only be hiding which of the two would win." Nothing checked it, and the
     * failure is not a crash - it is a press that does two things, or one of
     * two things depending on iteration order.
     */
    @Test
    public void givingAButtonToOneActionTakesItFromTheOther() {
        FakePreferences preferences = empty();

        Hotkeys.bind(preferences, Hotkeys.Action.PAUSE, KeyEvent.KEYCODE_BUTTON_A);
        Hotkeys.bind(preferences, Hotkeys.Action.RESET, KeyEvent.KEYCODE_BUTTON_A);

        assertEquals("the second action did not get the button",
                     KeyEvent.KEYCODE_BUTTON_A,
                     Hotkeys.keycodeFor(preferences, Hotkeys.Action.RESET));
        assertEquals("the first action kept a button that was taken from it",
                     0, Hotkeys.keycodeFor(preferences, Hotkeys.Action.PAUSE));
    }

    /** Binding one action leaves every other one alone - the whole map is
     *  rewritten each time, so a bug here silently forgets the rest. */
    @Test
    public void bindingOneActionKeepsTheOthers() {
        FakePreferences preferences = empty();

        Hotkeys.bind(preferences, Hotkeys.Action.PAUSE, KeyEvent.KEYCODE_BUTTON_A);
        Hotkeys.bind(preferences, Hotkeys.Action.RESET, KeyEvent.KEYCODE_BUTTON_B);

        assertEquals(KeyEvent.KEYCODE_BUTTON_A,
                     Hotkeys.keycodeFor(preferences, Hotkeys.Action.PAUSE));
        assertEquals(KeyEvent.KEYCODE_BUTTON_B,
                     Hotkeys.keycodeFor(preferences, Hotkeys.Action.RESET));
    }

    /** Binding 0 is how the editor clears a row, and it must not take the
     *  button away from anyone else on the way past - see the {@code keycode
     *  != 0} in bind's own condition. */
    @Test
    public void clearingAnActionLeavesEverybodyElseBound() {
        FakePreferences preferences = empty();

        Hotkeys.bind(preferences, Hotkeys.Action.PAUSE, KeyEvent.KEYCODE_BUTTON_A);
        Hotkeys.bind(preferences, Hotkeys.Action.RESET, KeyEvent.KEYCODE_BUTTON_B);
        Hotkeys.bind(preferences, Hotkeys.Action.PAUSE, 0);

        assertEquals("clearing one action cleared another",
                     KeyEvent.KEYCODE_BUTTON_B,
                     Hotkeys.keycodeFor(preferences, Hotkeys.Action.RESET));
        assertEquals(0, Hotkeys.keycodeFor(preferences, Hotkeys.Action.PAUSE));
    }

    /**
     * The first bind leaves the defaults behind.
     *
     * Worth saying because it is the moment the stored map replaces the
     * built-in table wholesale: everything that had a default and is not
     * written into that first JSON is unbound from then on. {@code bind}
     * copies the others across through {@code keycodeFor}, which is what makes
     * that safe - and this is what says so.
     */
    @Test
    public void thefirstBindingCarriesTheOtherDefaultsWithIt() {
        FakePreferences preferences = empty();

        Hotkeys.Action other = null;
        int wasBoundTo = 0;
        for (Hotkeys.Action action : Hotkeys.Action.values()) {
            int keycode = Hotkeys.keycodeFor(preferences, action);
            if (keycode != 0 && action != Hotkeys.Action.PAUSE) {
                other = action;
                wasBoundTo = keycode;
                break;
            }
        }
        assertTrue("no second action has a default to be carried", other != null);

        // A button nothing already holds, so nothing is displaced.
        Hotkeys.bind(preferences, Hotkeys.Action.PAUSE, KeyEvent.KEYCODE_BUTTON_C);

        assertEquals("binding one action dropped another action's default",
                     wasBoundTo, Hotkeys.keycodeFor(preferences, other));
    }

    // --- what a broken stored value does -----------------------------------------------

    /** A stored map that will not parse leaves everything unbound rather than
     *  throwing: "an empty one means the hotkey does nothing, which is visible
     *  in the editor and mendable there". */
    @Test
    public void arubbishStoredMapReadsAsNothingBound() {
        FakePreferences preferences = empty()
                .with(Hotkeys.KEY_BINDINGS, "}}not json{{");

        for (Hotkeys.Action action : Hotkeys.Action.values()) {
            assertEquals(action + " came back bound from a rubbish stored map",
                         0, Hotkeys.keycodeFor(preferences, action));
        }
    }

    /** And once anything is stored, the defaults are gone - an action absent
     *  from the stored map is unbound, not defaulted. That is what makes
     *  "clear this row" possible at all. */
    @Test
    public void astoredMapReplacesTheDefaultsRatherThanAddingToThem() {
        FakePreferences preferences = empty()
                .with(Hotkeys.KEY_BINDINGS, "{\"PAUSE\":" + KeyEvent.KEYCODE_BUTTON_A + "}");

        assertEquals(KeyEvent.KEYCODE_BUTTON_A,
                     Hotkeys.keycodeFor(preferences, Hotkeys.Action.PAUSE));
        assertEquals("an action missing from the stored map fell back to its default",
                     0, Hotkeys.keycodeFor(preferences, Hotkeys.Action.RESET));
    }

    // --- the buttons offered --------------------------------------------------------------

    /** Every button the editor offers is a distinct real keycode - a repeat
     *  would be two rows a person cannot tell apart. */
    @Test
    public void theButtonsOfferedAreAllDistinct() {
        int[] buttons = Hotkeys.buttons();
        Set<Integer> seen = new HashSet<>();

        assertTrue("no buttons offered at all", buttons.length > 0);
        for (int keycode : buttons) {
            assertNotEquals("0 is \"no button\" and cannot be offered as one", 0, keycode);
            assertTrue("button " + keycode + " is offered twice", seen.add(keycode));
        }
    }
}
