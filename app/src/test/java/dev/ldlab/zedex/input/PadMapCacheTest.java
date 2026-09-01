package dev.ldlab.zedex.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import android.view.KeyEvent;

import dev.ldlab.zedex.FakePreferences;
import dev.ldlab.zedex.FuseNative;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Two pads at once, each on its own mapping.
 *
 * Not hypothetical: measured on a Realme RMX5061 with a GameSir-Cyclone Pro on
 * Bluetooth and an X-Box 360 pad on USB, both reporting GAMEPAD and JOYSTICK at
 * the same time. An earlier draft of this took "the first pad in the device
 * list", which would have applied one pad's mapping to the other's buttons.
 */
public class PadMapCacheTest {

    private static final String GAMESIR = "f5c2919f";
    private static final String XBOX = "dc4619ee";

    /** The device table an event's id is resolved against. */
    private static final class Devices implements PadMapCache.Devices {
        final Map<Integer, String> keys = new HashMap<>();
        int lookups;

        @Override
        public String keyFor(int deviceId) {
            lookups++;
            return keys.get(deviceId);
        }
    }

    @Test
    public void eachPadGetsItsOwnMapping() {
        FakePreferences preferences = new FakePreferences();

        PadMaps.save(preferences, GAMESIR, "GameSir-Cyclone Pro",
                     PadMap.defaults().with(FuseNative.JOYSTICK_FIRE,
                             PadMap.Binding.button(KeyEvent.KEYCODE_BUTTON_B)));

        Devices devices = new Devices();
        devices.keys.put(19, GAMESIR);
        devices.keys.put(22, XBOX);

        PadMapCache cache = new PadMapCache(preferences, devices);

        // The GameSir has Fire on B; the X-Box, untouched, still has it on A.
        assertEquals(FuseNative.JOYSTICK_FIRE,
                     cache.forDevice(19).slotFor(KeyEvent.KEYCODE_BUTTON_B));
        assertEquals(PadMap.NONE,
                     cache.forDevice(19).slotFor(KeyEvent.KEYCODE_BUTTON_A));
        assertEquals(FuseNative.JOYSTICK_FIRE,
                     cache.forDevice(22).slotFor(KeyEvent.KEYCODE_BUTTON_A));
    }

    /** A device that has gone away drives the defaults, not nothing. */
    @Test
    public void anUnknownDeviceGetsTheDefaults() {
        PadMapCache cache = new PadMapCache(new FakePreferences(), new Devices());

        assertEquals(FuseNative.JOYSTICK_FIRE,
                     cache.forDevice(99).slotFor(KeyEvent.KEYCODE_BUTTON_A));
    }

    /** Asked twice, resolved once: this runs for every button press. */
    @Test
    public void aMappingIsResolvedOncePerDevice() {
        Devices devices = new Devices();
        devices.keys.put(19, GAMESIR);

        PadMapCache cache = new PadMapCache(new FakePreferences(), devices);

        assertSame(cache.forDevice(19), cache.forDevice(19));
        assertEquals(1, devices.lookups);
    }

    /** An edit is seen after forget(), which is what the editor calls. */
    @Test
    public void forgettingPicksUpAnEdit() {
        FakePreferences preferences = new FakePreferences();
        Devices devices = new Devices();
        devices.keys.put(19, GAMESIR);

        PadMapCache cache = new PadMapCache(preferences, devices);
        assertEquals(FuseNative.JOYSTICK_FIRE,
                     cache.forDevice(19).slotFor(KeyEvent.KEYCODE_BUTTON_A));

        PadMaps.save(preferences, GAMESIR, "GameSir-Cyclone Pro",
                     PadMap.defaults().with(FuseNative.JOYSTICK_FIRE,
                             PadMap.Binding.button(KeyEvent.KEYCODE_BUTTON_B)));
        cache.forget();

        assertEquals(FuseNative.JOYSTICK_FIRE,
                     cache.forDevice(19).slotFor(KeyEvent.KEYCODE_BUTTON_B));
    }
}
