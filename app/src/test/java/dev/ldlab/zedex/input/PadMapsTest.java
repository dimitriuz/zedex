package dev.ldlab.zedex.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import dev.ldlab.zedex.FakePreferences;
import dev.ldlab.zedex.FuseNative;

import org.junit.Test;

/**
 * One mapping per pad, which is the whole reason this is a store and not a
 * preference.
 *
 * The case it exists for is a handheld with a built-in pad and a Bluetooth pad
 * beside it: a single mapping would mean correcting one breaks the other, and
 * the person would never find out which.
 */
public class PadMapsTest {

    private static final String ONE = "descriptor-one";
    private static final String TWO = "descriptor-two";

    @Test
    public void aPadNobodyHasTouchedGetsTheDefaults() {
        PadMap map = PadMaps.load(new FakePreferences(), ONE);

        assertEquals(FuseNative.JOYSTICK_FIRE, map.slotFor(KeyEvent.KEYCODE_BUTTON_A));
    }

    @Test
    public void aSavedMappingComesBack() {
        FakePreferences preferences = new FakePreferences();

        PadMaps.save(preferences, ONE, "8BitDo SN30 Pro",
                     PadMap.defaults().with(FuseNative.JOYSTICK_FIRE,
                             PadMap.Binding.button(KeyEvent.KEYCODE_BUTTON_B)));

        assertEquals(FuseNative.JOYSTICK_FIRE,
                     PadMaps.load(preferences, ONE).slotFor(KeyEvent.KEYCODE_BUTTON_B));
    }

    @Test
    public void twoPadsKeepSeparateMappings() {
        FakePreferences preferences = new FakePreferences();

        PadMaps.save(preferences, ONE, "one",
                     PadMap.defaults().with(FuseNative.JOYSTICK_FIRE,
                             PadMap.Binding.button(KeyEvent.KEYCODE_BUTTON_B)));

        assertEquals(FuseNative.JOYSTICK_FIRE,
                     PadMaps.load(preferences, ONE).slotFor(KeyEvent.KEYCODE_BUTTON_B));
        assertEquals(FuseNative.JOYSTICK_FIRE,
                     PadMaps.load(preferences, TWO).slotFor(KeyEvent.KEYCODE_BUTTON_A));
    }

    @Test
    public void aForgottenPadIsBackOnTheDefaults() {
        FakePreferences preferences = new FakePreferences();

        PadMaps.save(preferences, ONE, "one",
                     PadMap.defaults().with(FuseNative.JOYSTICK_FIRE,
                             PadMap.Binding.button(KeyEvent.KEYCODE_BUTTON_B)));
        PadMaps.forget(preferences, ONE);

        assertEquals(FuseNative.JOYSTICK_FIRE,
                     PadMaps.load(preferences, ONE).slotFor(KeyEvent.KEYCODE_BUTTON_A));
    }

    /** The picker lists a pad that is not plugged in, so the name is stored. */
    @Test
    public void aStoredPadIsListedByName() {
        FakePreferences preferences = new FakePreferences();

        PadMaps.save(preferences, ONE, "8BitDo SN30 Pro", PadMap.defaults()
                .with(FuseNative.JOYSTICK_FIRE,
                      PadMap.Binding.button(KeyEvent.KEYCODE_BUTTON_B)));

        assertEquals("8BitDo SN30 Pro", PadMaps.known(preferences).get(ONE));
    }

    /** A store that will not parse is every pad on its defaults, and no crash. */
    @Test
    public void malformedStorageIsSurvived() {
        FakePreferences preferences = new FakePreferences();
        preferences.edit().putString(PadMaps.KEY, "{not json").apply();

        assertEquals(FuseNative.JOYSTICK_FIRE,
                     PadMaps.load(preferences, ONE).slotFor(KeyEvent.KEYCODE_BUTTON_A));
        assertTrue(PadMaps.known(preferences).isEmpty());
    }
}
